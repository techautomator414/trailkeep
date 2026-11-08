// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.retention;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything the purge job does in the database, as the {@code TRAILKEEP_PURGE} user (BUILD_PLAN §4.6, §8 rule 7,
 * WP-24 part c), and nothing else: the statements here are the purge user's grants in {@code R__grants.sql}, read in
 * order. Native SQL, because JPA cannot express it: the job runs without Hibernate's schema validation (the purge user
 * sees only the tables it purges), it deletes from tables whose entities are {@code @Immutable} (comments, chunks,
 * reopens), which Hibernate refuses to bulk-delete, and it must delete across modules in foreign-key order in one
 * transaction per chunk, never loading a row's content.
 *
 * <p>A case is purged under its row lock, taken by re-reading it with every condition: archived, not yet purged, its
 * {@code PURGE_AFTER} passed and not on legal hold at that moment, so a hold placed while the job runs wins. Then, in
 * this order: comment pins; update requests (they name the comment that answered them); comments; attachment chunks;
 * attachments; search text; reopens (they name a closure request); closure requests; the case's queued or sent mail.
 * Last, the case row becomes a tombstone: description and subject cleared, {@code PURGED_AT} set, the version moved on.
 * Kept: the case row (number, type, team, dates, title), its SLA row, the audit trail, the alert ledger, the legal hold
 * events and in-app alerts, all of which reference the case and stay valid because it is never deleted.
 */
@Repository
class PurgeJdbcDao {

    private static final String DUE = "SELECT ID FROM CASE_RECORD WHERE STATUS = 'ARCHIVED' AND PURGED_AT IS NULL"
            + " AND LEGAL_HOLD = :noHold AND PURGE_AFTER <= :now ORDER BY ID";
    private static final String LOCK = "SELECT ID FROM CASE_RECORD WHERE ID IN (:ids) AND STATUS = 'ARCHIVED'"
            + " AND PURGED_AT IS NULL AND LEGAL_HOLD = :noHold AND PURGE_AFTER <= :now ORDER BY ID FOR UPDATE";

    /** The deletes, in foreign-key order, each with the name its count is logged under. */
    private static final Map<String, String> DELETES = deletes();

    private static final String TOMBSTONE = "UPDATE CASE_RECORD SET DESCRIPTION = NULL, SUBJECT_TEXT = NULL,"
            + " PURGED_AT = :now, VERSION = VERSION + 1 WHERE ID IN (:ids)";
    private static final String RUN_MAIL = "DELETE FROM NOTIFICATION_OUTBOX WHERE REPORT_RUN_ID IN"
            + " (SELECT ID FROM REPORT_RUN WHERE RAN_AT < :before)";
    private static final String RUNS = "DELETE FROM REPORT_RUN WHERE RAN_AT < :before";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    PurgeJdbcDao(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    private static Map<String, String> deletes() {
        Map<String, String> deletes = new LinkedHashMap<>();
        deletes.put("pins", "DELETE FROM CASE_COMMENT_PIN WHERE CASE_ID IN (:ids)");
        deletes.put("updateRequests", "DELETE FROM UPDATE_REQUEST WHERE CASE_ID IN (:ids)");
        deletes.put("comments", "DELETE FROM CASE_COMMENT WHERE CASE_ID IN (:ids)");
        deletes.put(
                "chunks",
                "DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN"
                        + " (SELECT ID FROM CASE_ATTACHMENT WHERE CASE_ID IN (:ids))");
        deletes.put("attachments", "DELETE FROM CASE_ATTACHMENT WHERE CASE_ID IN (:ids)");
        deletes.put("searchText", "DELETE FROM CASE_SEARCH WHERE CASE_ID IN (:ids)");
        deletes.put("reopens", "DELETE FROM CASE_REOPEN WHERE CASE_ID IN (:ids)");
        deletes.put("closureRequests", "DELETE FROM CLOSURE_REQUEST WHERE CASE_ID IN (:ids)");
        deletes.put("mail", "DELETE FROM NOTIFICATION_OUTBOX WHERE CASE_ID IN (:ids)");
        return Collections.unmodifiableMap(deletes);
    }

    /**
     * The cases due for purging at {@code now}.
     *
     * @param now the run's instant
     * @return their ids, ascending
     */
    List<Long> due(Instant now) {
        return jdbc.queryForList(DUE, Map.of("noHold", false, "now", Timestamp.from(now)), Long.class);
    }

    /**
     * Purges those of {@code caseIds} that are still due, in one transaction.
     *
     * @param caseIds the cases, from {@link #due}
     * @param now the run's instant
     * @return how many rows each step removed or changed, by name; {@code cases} counts the tombstones
     */
    Map<String, Integer> purge(Collection<Long> caseIds, Instant now) {
        Map<String, Integer> counts = transactions.execute(status -> {
            Map<String, Object> lock = Map.of("ids", caseIds, "noHold", false, "now", Timestamp.from(now));
            List<Long> locked = jdbc.queryForList(LOCK, lock, Long.class);
            Map<String, Integer> done = new LinkedHashMap<>();
            if (locked.isEmpty()) {
                return done;
            }
            Map<String, Object> ids = Map.of("ids", locked);
            DELETES.forEach((name, sql) -> done.put(name, jdbc.update(sql, ids)));
            done.put("cases", jdbc.update(TOMBSTONE, Map.of("ids", locked, "now", Timestamp.from(now))));
            return done;
        });
        return counts == null ? Map.of() : counts;
    }

    /**
     * Deletes the report runs that ran before {@code before}, and first the mail that names them, in one transaction.
     *
     * @param before the cut-off
     * @return how many runs went
     */
    int trimReportRuns(Instant before) {
        Integer runs = transactions.execute(status -> {
            Map<String, Object> cutOff = Map.of("before", Timestamp.from(before));
            jdbc.update(RUN_MAIL, cutOff);
            return jdbc.update(RUNS, cutOff);
        });
        return runs == null ? 0 : runs;
    }
}
