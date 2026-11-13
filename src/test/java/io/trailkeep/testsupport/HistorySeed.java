// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;

/**
 * Synthetic history rows written straight to the tables at chosen times, for History tab tests (WP-26 part x): an
 * import run, imported legacy events ({@code CASE_IMPORTED_EVENT}, plain or with a stand-in digest) and audit events.
 * The writing services stamp the clock or refuse future times, so a test that needs the two sources interleaved writes
 * them here. Ids start far above the pooled sequences; use it inside a rolled-back transaction or delete what it wrote.
 */
public final class HistorySeed {

    /** A stand-in digest: tests only check that it never reaches a page. */
    public static final String DIGEST = "d1".repeat(32);

    private static final AtomicLong NEXT = new AtomicLong(960_000);

    private final JdbcTemplate jdbc;

    public HistorySeed(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a loaded import run.
     *
     * @param requestedBy the user who asked for it
     * @return the run's id
     */
    public long run(long requestedBy) {
        long id = NEXT.incrementAndGet();
        Timestamp at = Timestamp.from(Instant.parse("2026-11-01T09:00:00Z"));
        jdbc.update(
                "INSERT INTO IMPORT_RUN (ID, KIND, SOURCE_ID, STATUS, REQUESTED_BY, REQUESTED_AT, UPDATED_AT)"
                        + " VALUES (?, 'IMPORT', 'synthetic', 'LOADED', ?, ?, ?)",
                id,
                requestedBy,
                at,
                at);
        return id;
    }

    /**
     * Inserts an imported event whose values are stored as text.
     *
     * @param caseId the case
     * @param runId the import run
     * @param at the legacy time
     * @param who the legacy actor as recorded, or {@code null}
     * @param kind the kind, such as {@code STATUS_CHANGE}
     * @param field the canonical field, or {@code null}
     * @param before the old value, or {@code null}
     * @param after the new value, or {@code null}
     * @return the row's id
     */
    public long plain(
            long caseId,
            long runId,
            Instant at,
            @Nullable String who,
            String kind,
            @Nullable String field,
            @Nullable String before,
            @Nullable String after) {
        return imported(caseId, runId, at, who, kind, field, before, after, false);
    }

    /**
     * Inserts an imported event whose new value is redacted (the stand-in {@link #DIGEST} and a length of 42).
     *
     * @param caseId the case
     * @param runId the import run
     * @param at the legacy time
     * @param kind the kind, such as {@code NOTE}
     * @param field the canonical field, or {@code null}
     * @return the row's id
     */
    public long redacted(long caseId, long runId, Instant at, String kind, @Nullable String field) {
        return imported(caseId, runId, at, "Synthetic Legacy Clerk", kind, field, null, null, true);
    }

    /**
     * Inserts an audit event of a case with no values.
     *
     * @param caseId the case
     * @param at when it happened
     * @param actorName the actor's name snapshot
     * @param action the action, such as {@code CASE_UPDATED}
     * @return the row's id
     */
    public long audit(long caseId, Instant at, String actorName, String action) {
        long id = NEXT.incrementAndGet();
        jdbc.update(
                "INSERT INTO AUDIT_EVENT (ID, AT_TS, ACTOR_NAME_SNAPSHOT, ORIGIN, CASE_ID, ENTITY_TYPE, ENTITY_ID,"
                        + " ACTION) VALUES (?, ?, ?, 'UI', ?, 'CASE', ?, ?)",
                id,
                Timestamp.from(at),
                actorName,
                caseId,
                caseId,
                action);
        return id;
    }

    private long imported(
            long caseId,
            long runId,
            Instant at,
            @Nullable String who,
            String kind,
            @Nullable String field,
            @Nullable String before,
            @Nullable String after,
            boolean redacted) {
        long id = NEXT.incrementAndGet();
        jdbc.update(
                "INSERT INTO CASE_IMPORTED_EVENT (ID, CASE_ID, AT_TS, WHO_NAME_SNAPSHOT, KIND, FIELD_NAME, OLD_VALUE,"
                        + " NEW_VALUE, NEW_HMAC, NEW_LENGTH, RUN_ID) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                caseId,
                Timestamp.from(at),
                text(who),
                kind,
                text(field),
                text(before),
                text(after),
                text(redacted ? DIGEST : null),
                new SqlParameterValue(Types.INTEGER, redacted ? 42 : null),
                runId);
        return id;
    }

    private static SqlParameterValue text(@Nullable String value) {
        return new SqlParameterValue(Types.VARCHAR, value);
    }
}
