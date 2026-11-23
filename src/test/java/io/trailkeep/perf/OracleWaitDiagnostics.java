// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.perf;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * Opt-in wait-event evidence around a perf scenario on Oracle (issue 308), switched on by {@code
 * -Dtrailkeep.perf.oracle.diag=true}; without it every method does nothing and no connection is opened. It connects as
 * {@code SYSTEM} to the container's root ({@code CDB$ROOT}, where the redo and wait views are instance-wide), reads
 * {@code V$LOG}, {@code V$LOGFILE}, the switches in {@code V$LOG_HISTORY}, and the deltas of {@code V$SYSTEM_EVENT}
 * and {@code V$SYSSTAT} between {@link #start} and {@link #finish}, and samples the wait event of every active user
 * session twice a second in between, so a stall is attributed to the event it waited on. Read-only; the lines go to
 * the IT's report and to the build log.
 */
final class OracleWaitDiagnostics {

    static final String PROPERTY = "trailkeep.perf.oracle.diag";

    private static final String EVENTS = "SELECT EVENT, TOTAL_WAITS, TIME_WAITED_MICRO FROM V$SYSTEM_EVENT WHERE"
            + " WAIT_CLASS <> 'Idle' AND (EVENT LIKE 'log file%' OR EVENT LIKE 'enq:%' OR EVENT LIKE 'checkpoint%'"
            + " OR EVENT LIKE 'free buffer%' OR EVENT LIKE 'buffer busy%' OR EVENT LIKE 'DLM%' OR EVENT LIKE '%CTX%'"
            + " OR EVENT LIKE '%text%' OR EVENT LIKE 'direct path%' OR EVENT LIKE 'db file%')";
    private static final String STATS = "SELECT NAME, VALUE FROM V$SYSSTAT WHERE NAME IN ('redo size',"
            + " 'redo log space requests', 'redo log space wait time', 'redo synch writes', 'redo synch time',"
            + " 'user commits', 'DBWR checkpoints', 'background checkpoints completed')";
    /** Every session, background ones included, that has waited three seconds or more, with its blocker's state. */
    private static final String LONG_WAITS = "SELECT s.SID, NVL(s.PROGRAM, '?'), NVL(s.USERNAME, 'bg'), s.EVENT,"
            + " s.SECONDS_IN_WAIT, NVL(s.BLOCKING_SESSION, 0), s.P1, s.P2, s.P3, NVL(s.SQL_ID, '-'),"
            + " (SELECT SUBSTR(q.SQL_TEXT, 1, 120) FROM V$SQL q WHERE q.SQL_ID = s.SQL_ID AND ROWNUM = 1),"
            + " (SELECT b.EVENT || ' / ' || NVL(b.PROGRAM, '?') || ' / sql ' || NVL(b.SQL_ID, '-') || ' / prev '"
            + " || NVL(b.PREV_SQL_ID, '-') || ' / ' || b.STATUS FROM V$SESSION b WHERE b.SID = s.BLOCKING_SESSION)"
            + " FROM V$SESSION s WHERE s.STATE = 'WAITING' AND s.WAIT_CLASS <> 'Idle' AND s.SECONDS_IN_WAIT >= 3";

    private static final String SESSIONS = "SELECT EVENT, WAIT_CLASS, SECONDS_IN_WAIT FROM V$SESSION WHERE TYPE ="
            + " 'USER' AND STATUS = 'ACTIVE' AND SCHEMANAME NOT IN ('SYS', 'SYSTEM') AND WAIT_CLASS <> 'Idle'"
            + " AND STATE = 'WAITING'";

    private final boolean enabled = Boolean.getBoolean(PROPERTY);
    private final List<String> lines = new ArrayList<>();
    private final Map<String, long[]> sampled = new TreeMap<>();
    private final Map<String, String> longWaits = new TreeMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private Map<String, long[]> eventsBefore = Map.of();
    private Map<String, Long> statsBefore = Map.of();
    private long switchesBefore;
    private long startedMillis;
    private @Nullable Thread sampler;
    private @Nullable JdbcConnectionDetails details;

    boolean enabled() {
        return enabled;
    }

    /**
     * Prints the redo configuration and takes the first snapshot; starts the session sampler.
     *
     * @param connection the application's connection details (the {@code SYSTEM} password is the container's)
     * @return the report lines so far
     */
    List<String> start(JdbcConnectionDetails connection) {
        if (!enabled) {
            return List.of();
        }
        details = connection;
        try (Connection root = root();
                Statement st = root.createStatement()) {
            lines.add("Redo groups: "
                    + rows(
                            st,
                            "SELECT GROUP#, THREAD#, BYTES/1048576 MB, MEMBERS, STATUS, ARCHIVED"
                                    + " FROM V$LOG ORDER BY GROUP#"));
            lines.add("Redo files: " + rows(st, "SELECT GROUP#, MEMBER FROM V$LOGFILE ORDER BY GROUP#"));
            lines.add("Log mode: " + rows(st, "SELECT LOG_MODE FROM V$DATABASE"));
            lines.add("Redo params: "
                    + rows(
                            st,
                            "SELECT NAME, VALUE FROM V$PARAMETER WHERE NAME IN"
                                    + " ('fast_start_mttr_target', 'log_checkpoint_timeout', 'log_checkpoint_interval',"
                                    + " 'db_cache_size', 'sga_target', 'pga_aggregate_target', 'db_writer_processes',"
                                    + " 'db_block_checking', 'filesystemio_options')"));
            switchesBefore = scalar(st, "SELECT COUNT(*) FROM V$LOG_HISTORY");
            eventsBefore = events(st);
            statsBefore = stats(st);
        } catch (SQLException unreadable) {
            lines.add("Diagnostics unavailable at start: " + unreadable.getMessage());
            return lines;
        }
        startedMillis = System.currentTimeMillis();
        running.set(true);
        sampler = new Thread(this::sample, "oracle-wait-sampler");
        sampler.setDaemon(true);
        sampler.start();
        return lines;
    }

    /**
     * Stops the sampler and reports the deltas since {@link #start}.
     *
     * @return the report lines of the deltas
     */
    List<String> finish() {
        if (!enabled || !running.getAndSet(false)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try {
            Objects.requireNonNull(sampler).join(3000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try (Connection root = root();
                Statement st = root.createStatement()) {
            long elapsed = (System.currentTimeMillis() - startedMillis) / 1000;
            out.add("Window: " + elapsed + " s; log switches in the window: "
                    + (scalar(st, "SELECT COUNT(*) FROM V$LOG_HISTORY") - switchesBefore));
            out.add("Switch history (newest 15): "
                    + rows(
                            st,
                            "SELECT * FROM (SELECT SEQUENCE#, TO_CHAR(FIRST_TIME,"
                                    + "'HH24:MI:SS') FT FROM V$LOG_HISTORY ORDER BY SEQUENCE# DESC)"
                                    + " WHERE ROWNUM <= 15"));
            Map<String, long[]> after = events(st);
            out.add("Wait-event deltas (event: waits, seconds):");
            after.forEach((event, now) -> {
                long[] was = eventsBefore.getOrDefault(event, new long[] {0, 0});
                long waits = now[0] - was[0];
                if (waits > 0) {
                    out.add(String.format(
                            Locale.ROOT, "  %s: %d waits, %.3f s", event, waits, (now[1] - was[1]) / 1_000_000.0));
                }
            });
            Map<String, Long> statsAfter = stats(st);
            statsAfter.forEach(
                    (name, now) -> out.add("  stat " + name + ": +" + (now - statsBefore.getOrDefault(name, 0L))));
            out.add("Redo log groups after: "
                    + rows(st, "SELECT GROUP#, BYTES/1048576 MB, STATUS FROM V$LOG" + " ORDER BY GROUP#"));
        } catch (SQLException unreadable) {
            out.add("Diagnostics unavailable at finish: " + unreadable.getMessage());
        }
        out.add("Active user sessions' waits, sampled twice a second (event: samples, longest wait s):");
        sampled.forEach((event, v) -> out.add("  " + event + ": " + v[0] + " samples, " + v[1] + " s"));
        out.add("Waits of three seconds or more, last observation of each (session, event):");
        longWaits.forEach((key, line) -> out.add("  " + line));
        out.forEach(line -> System.out.println("[oracle-diag] " + line));
        lines.forEach(line -> System.out.println("[oracle-diag] " + line));
        out.addAll(0, lines);
        return out;
    }

    private void sample() {
        try (Connection root = root();
                Statement st = root.createStatement()) {
            while (running.get()) {
                try (ResultSet rs = st.executeQuery(SESSIONS)) {
                    while (rs.next()) {
                        long[] v = sampled.computeIfAbsent(rs.getString(1), k -> new long[2]);
                        v[0]++;
                        v[1] = Math.max(v[1], rs.getLong(3));
                    }
                }
                try (ResultSet rs = st.executeQuery(LONG_WAITS)) {
                    while (rs.next()) {
                        String key = rs.getString(1) + " " + rs.getString(4);
                        longWaits.put(
                                key,
                                String.format(
                                        Locale.ROOT,
                                        "sid %s [%s, %s] %s, %s s so far, blocked by sid %s (%s), p1=%s p2=%s p3=%s,"
                                                + " sql %s: %s",
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getString(12),
                                        rs.getString(7),
                                        rs.getString(8),
                                        rs.getString(9),
                                        rs.getString(10),
                                        rs.getString(11)));
                    }
                }
                Thread.sleep(500);
            }
        } catch (SQLException unreadable) {
            sampled.put("sampler failed: " + unreadable.getMessage(), new long[] {0, 0});
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Connection root() throws SQLException {
        // The container's PDB is <service>PDB1 (FREEPDB1, XEPDB1); its root is the service without the suffix.
        JdbcConnectionDetails details = Objects.requireNonNull(this.details);
        String url = details.getJdbcUrl().replaceAll("(?i)(free|xe)pdb1$", "$1");
        return DriverManager.getConnection(url, "system", details.getPassword());
    }

    private static Map<String, long[]> events(Statement st) throws SQLException {
        Map<String, long[]> map = new LinkedHashMap<>();
        try (ResultSet rs = st.executeQuery(EVENTS)) {
            while (rs.next()) {
                map.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)});
            }
        }
        return map;
    }

    private static Map<String, Long> stats(Statement st) throws SQLException {
        Map<String, Long> map = new LinkedHashMap<>();
        try (ResultSet rs = st.executeQuery(STATS)) {
            while (rs.next()) {
                map.put(rs.getString(1), rs.getLong(2));
            }
        }
        return map;
    }

    private static long scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String rows(Statement st, String sql) throws SQLException {
        StringBuilder out = new StringBuilder();
        try (ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                out.append('[');
                for (int i = 1; i <= columns; i++) {
                    out.append(i > 1 ? ", " : "").append(rs.getString(i));
                }
                out.append("] ");
            }
        }
        return out.toString().strip();
    }
}
