// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The specification {@link AbstractDatabaseGrantsIT} proves: which DML privilege each grantee of the vendor
 * {@code R__grants.sql} holds on each table, and which sequences exist. When a migration adds a table, add its row
 * here and in both vendor grant files.
 */
final class GrantsMatrix {

    /** The DML privileges the matrix covers. */
    enum Privilege {
        SELECT,
        INSERT,
        UPDATE,
        DELETE
    }

    /**
     * Identity (V1), audit (V2), admin (V3), case (V4), comment (V4.1), pin (V4.2), attachment (V4.3), saved view
     * (V4.7), outbox (V4.8), alert (V4.9), update request (V4.10), closure (V5), reopen (V5.1), SLA job (V6), report
     * delivery (V7), legal hold history (V8), preference (V9), import run (V10), import staging (V13), imported
     * history (V16), import ledger (V17), pending owner (V18) and ledger part (V19) tables, and the column each probe
     * touches: the first key column.
     */
    static final Map<String, String> TABLES = Map.ofEntries(
            Map.entry("APP_USER", "ID"),
            Map.entry("TEAM", "ID"),
            Map.entry("APP_USER_TEAM", "USER_ID"),
            Map.entry("APP_USER_GLOBAL_ROLE", "USER_ID"),
            Map.entry("AUDIT_EVENT", "ID"),
            Map.entry("SLA_POLICY", "ID"),
            Map.entry("CASE_TYPE", "ID"),
            Map.entry("CASE_TYPE_OUTCOME", "CASE_TYPE_ID"),
            Map.entry("CASE_RECORD", "ID"),
            Map.entry("CASE_SLA", "CASE_ID"),
            Map.entry("CASE_SEARCH", "CASE_ID"),
            Map.entry("CASE_COMMENT", "ID"),
            Map.entry("CASE_COMMENT_PIN", "COMMENT_ID"),
            Map.entry("CASE_ATTACHMENT", "ID"),
            Map.entry("CASE_ATTACHMENT_CHUNK", "ATTACHMENT_ID"),
            Map.entry("SPRING_SESSION", "PRIMARY_ID"),
            Map.entry("SPRING_SESSION_ATTRIBUTES", "SESSION_PRIMARY_ID"),
            Map.entry("OIDC_SESSION", "SESSION_ID"),
            Map.entry("SAVED_VIEW", "ID"),
            Map.entry("NOTIFICATION_OUTBOX", "ID"),
            Map.entry("USER_ALERT", "ID"),
            Map.entry("UPDATE_REQUEST", "ID"),
            Map.entry("CLOSURE_REQUEST", "ID"),
            Map.entry("CASE_REOPEN", "ID"),
            Map.entry("ALERT_LEDGER", "ID"),
            Map.entry("JOB_RUN", "JOB_NAME"),
            Map.entry("SHEDLOCK", "NAME"),
            Map.entry("REPORT_SCHEDULE", "ID"),
            Map.entry("REPORT_RUN", "ID"),
            Map.entry("LEGAL_HOLD_EVENT", "ID"),
            Map.entry("USER_PREFERENCE", "USER_ID"),
            Map.entry("IMPORT_RUN", "ID"),
            Map.entry("IMPORT_ISSUE", "ID"),
            Map.entry("IMPORT_RUN_SUMMARY", "ID"),
            Map.entry("IMPORT_STG_PERSON", "RUN_ID"),
            Map.entry("IMPORT_STG_CASE", "RUN_ID"),
            Map.entry("IMPORT_STG_COMMENT", "RUN_ID"),
            Map.entry("IMPORT_STG_ATTACHMENT", "RUN_ID"),
            Map.entry("IMPORT_STG_EVENT", "RUN_ID"),
            Map.entry("IMPORT_RUN_CHECKPOINT", "RUN_ID"),
            Map.entry("CASE_IMPORTED_EVENT", "ID"),
            Map.entry("IMPORT_LEDGER", "ID"),
            Map.entry("IMPORT_OWNER_PENDING", "ID"),
            Map.entry("IMPORT_LEDGER_PART", "LEDGER_ID"));

    /** What the runtime user may do; anything absent is denied. */
    static final Map<String, Set<Privilege>> APP_USER_GRANTS = Map.ofEntries(
            Map.entry("APP_USER", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("TEAM", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("APP_USER_TEAM", EnumSet.allOf(Privilege.class)),
            Map.entry("APP_USER_GLOBAL_ROLE", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.DELETE)),
            Map.entry("AUDIT_EVENT", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            Map.entry("SLA_POLICY", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("CASE_TYPE", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("CASE_TYPE_OUTCOME", EnumSet.allOf(Privilege.class)),
            Map.entry("CASE_RECORD", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("CASE_SLA", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("CASE_SEARCH", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("CASE_COMMENT", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            Map.entry("CASE_COMMENT_PIN", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.DELETE)),
            // UPDATE of the release columns only; see theRuntimeUserMayChangeAnAttachmentOnlyByReleasingIt.
            Map.entry("CASE_ATTACHMENT", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            Map.entry("CASE_ATTACHMENT_CHUNK", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            Map.entry("SPRING_SESSION", EnumSet.allOf(Privilege.class)),
            Map.entry("SPRING_SESSION_ATTRIBUTES", EnumSet.allOf(Privilege.class)),
            Map.entry("OIDC_SESSION", EnumSet.allOf(Privilege.class)),
            // A person deletes their own views; a view is never changed, so no UPDATE.
            Map.entry("SAVED_VIEW", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.DELETE)),
            // Mail is sent and its status changed, never deleted by the app: trimming sent rows is the purge user's.
            Map.entry("NOTIFICATION_OUTBOX", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            // A person marks their alerts read and deletes them (BUILD_PLAN §8 rule 7 allows DELETE here).
            Map.entry("USER_ALERT", EnumSet.allOf(Privilege.class)),
            // A request is answered, never deleted: it is case history.
            Map.entry("UPDATE_REQUEST", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            // A closure request is decided, never deleted: it is case history.
            Map.entry("CLOSURE_REQUEST", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            // A reopen is history: written once, never changed or deleted by the app.
            Map.entry("CASE_REOPEN", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            // An alert once raised is history: never changed or deleted by the app (BUILD_PLAN §8 rule 7).
            Map.entry("ALERT_LEDGER", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            // Each job's last success, replaced on every successful run.
            Map.entry("JOB_RUN", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            // ShedLock's JDBC provider inserts and updates its lock rows and never deletes one, so no DELETE.
            Map.entry("SHEDLOCK", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            // A schedule is edited and disabled, never deleted.
            Map.entry("REPORT_SCHEDULE", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            // A run is what was sent: written once, never changed or deleted by the app.
            Map.entry("REPORT_RUN", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            // A hold placed or lifted is history: written once, never changed or deleted, kept by purge (V8).
            Map.entry("LEGAL_HOLD_EVENT", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            // A person's preferences: saved and changed, never deleted (V9).
            Map.entry("USER_PREFERENCE", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            // Requested on the Import runs screen; UPDATE of the sign-off columns only (see the column test).
            Map.entry("IMPORT_RUN", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            Map.entry("IMPORT_ISSUE", EnumSet.of(Privilege.SELECT)),
            Map.entry("IMPORT_RUN_SUMMARY", EnumSet.of(Privilege.SELECT)),
            // Legacy history: read by the History tab, written only by the importer, removed only by purge (V16).
            Map.entry("CASE_IMPORTED_EVENT", EnumSet.of(Privilege.SELECT)),
            // What each load wrote: written only by the importer, never changed (V17).
            Map.entry("IMPORT_LEDGER", EnumSet.of(Privilege.SELECT)),
            // Open cases awaiting their legacy owner: counted by the screen, written and deleted by the importer (V18).
            Map.entry("IMPORT_OWNER_PENDING", EnumSet.of(Privilege.SELECT)),
            // The comments a ledger row wrote besides its target: written only by the importer, never changed (V19).
            Map.entry("IMPORT_LEDGER_PART", EnumSet.of(Privilege.SELECT)));

    /** The purge user (WP-24 part c): deletes purged content and old runs, audits each tombstone, records its run. */
    static final Map<String, Set<Privilege>> PURGE_USER_GRANTS = Stream.of(
                    ("NOTIFICATION_OUTBOX CASE_COMMENT_PIN UPDATE_REQUEST CASE_COMMENT CASE_ATTACHMENT_CHUNK"
                                    + " CASE_ATTACHMENT CASE_SEARCH CASE_REOPEN CLOSURE_REQUEST REPORT_RUN CASE_RECORD"
                                    + " AUDIT_EVENT JOB_RUN CASE_IMPORTED_EVENT")
                            .split(" "))
            .collect(Collectors.toUnmodifiableMap(Function.identity(), table -> switch (table) {
                case "CASE_RECORD" -> EnumSet.of(Privilege.SELECT);
                case "AUDIT_EVENT" -> EnumSet.of(Privilege.INSERT);
                case "JOB_RUN" -> EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE);
                default -> EnumSet.of(Privilege.SELECT, Privilege.DELETE);
            }));

    /**
     * The import user (WP-26, design §5): its own tables, and SELECT alone on every other table an entity maps, so
     * Hibernate validate sees the whole model. The tables no entity maps (sessions, ShedLock) stay out of reach; the
     * chunks are written by the load (WP-26 part u).
     */
    static final Map<String, Set<Privilege>> IMPORT_USER_GRANTS = TABLES.keySet().stream()
            .filter(table -> !Set.of("SPRING_SESSION", "SPRING_SESSION_ATTRIBUTES", "OIDC_SESSION", "SHEDLOCK")
                    .contains(table))
            .collect(Collectors.toUnmodifiableMap(Function.identity(), table -> switch (table) {
                // UPDATE of the status, hash, scanning-mode, count and expiry columns only (see the column test).
                case "IMPORT_RUN" -> EnumSet.of(Privilege.SELECT, Privilege.INSERT);
                // Insert-only: imported history (V16), the ledger and its parts (V17, V19), and what the load writes
                // per case (parts t, u, the go-live alerts of part ac and a held case's hold event of part ad);
                // CASE_SEARCH's UPDATE is of two columns only (see the column test).
                case "CASE_IMPORTED_EVENT",
                        "ALERT_LEDGER",
                        "LEGAL_HOLD_EVENT",
                        "IMPORT_LEDGER",
                        "IMPORT_LEDGER_PART",
                        "CASE_RECORD",
                        "CASE_SLA",
                        "CASE_SEARCH",
                        "CASE_COMMENT",
                        "CASE_ATTACHMENT",
                        "CASE_ATTACHMENT_CHUNK",
                        "CLOSURE_REQUEST",
                        "AUDIT_EVENT" -> EnumSet.of(Privilege.SELECT, Privilege.INSERT);
                // Staging (V13) is written, read and deleted by the import user alone; nobody else may even read it.
                case "IMPORT_ISSUE",
                        "IMPORT_RUN_SUMMARY",
                        "IMPORT_STG_PERSON",
                        "IMPORT_STG_CASE",
                        "IMPORT_STG_COMMENT",
                        "IMPORT_STG_ATTACHMENT",
                        "IMPORT_STG_EVENT",
                        "IMPORT_RUN_CHECKPOINT" -> EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.DELETE);
                // Written by the load, deleted by relink-owners once a case has its legacy owner, never changed (V18).
                case "IMPORT_OWNER_PENDING" -> EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.DELETE);
                default -> EnumSet.of(Privilege.SELECT);
            }));

    /** The sequences the runtime user draws from. */
    static final List<String> SEQUENCES = List.of(
            "SEQ_APP_USER",
            "SEQ_TEAM",
            "SEQ_AUDIT_EVENT",
            "SEQ_SLA_POLICY",
            "SEQ_CASE_TYPE",
            "SEQ_CASE_RECORD",
            "SEQ_CASE_NUMBER",
            "SEQ_CASE_COMMENT",
            "SEQ_CASE_ATTACHMENT",
            "SEQ_SAVED_VIEW",
            "SEQ_NOTIFICATION_OUTBOX",
            "SEQ_USER_ALERT",
            "SEQ_UPDATE_REQUEST",
            "SEQ_CLOSURE_REQUEST",
            "SEQ_CASE_REOPEN",
            "SEQ_ALERT_LEDGER",
            "SEQ_REPORT_SCHEDULE",
            "SEQ_REPORT_RUN",
            "SEQ_LEGAL_HOLD_EVENT",
            "SEQ_IMPORT_RUN");

    /** The sequences only the importer draws from; the runtime user may only see them, for validate. */
    static final List<String> IMPORTER_SEQUENCES = List.of(
            "SEQ_IMPORT_ISSUE",
            "SEQ_IMPORT_RUN_SUMMARY",
            "SEQ_CASE_IMPORTED_EVENT",
            "SEQ_IMPORT_LEDGER",
            "SEQ_IMPORT_OWNER_PENDING");

    /**
     * The runtime user's sequences the load draws from too: its cases, closures, comments and attachments (t, u), and
     * the go-live alerts it records (ac) and the hold events of held cases (ad).
     */
    static final List<String> LOAD_SEQUENCES = List.of(
            "SEQ_ALERT_LEDGER",
            "SEQ_LEGAL_HOLD_EVENT",
            "SEQ_CASE_RECORD",
            "SEQ_CASE_NUMBER",
            "SEQ_CLOSURE_REQUEST",
            "SEQ_AUDIT_EVENT",
            "SEQ_CASE_COMMENT",
            "SEQ_CASE_ATTACHMENT");

    /** The outcome of one probe. */
    enum Outcome {
        ALLOWED,
        DENIED
    }

    /** One probe: who, which table, which privilege, and what the matrix says. */
    record Probe(String grantee, String table, Privilege privilege, Outcome expected) {
        @Override
        public String toString() {
            return grantee + " " + privilege + " " + table + " -> " + expected;
        }
    }

    /** Every probe of the matrix, for the parameterized test. */
    static Stream<Probe> matrix() {
        List<Probe> probes = new ArrayList<>();
        for (String table : TABLES.keySet()) {
            for (Privilege privilege : Privilege.values()) {
                probes.add(probe("app", table, privilege, APP_USER_GRANTS));
                probes.add(probe("purge", table, privilege, PURGE_USER_GRANTS));
                probes.add(probe("import", table, privilege, IMPORT_USER_GRANTS));
            }
        }
        return probes.stream();
    }

    private static Probe probe(String who, String table, Privilege privilege, Map<String, Set<Privilege>> grants) {
        boolean allowed = grants.getOrDefault(table, Set.of()).contains(privilege);
        return new Probe(who, table, privilege, allowed ? Outcome.ALLOWED : Outcome.DENIED);
    }

    private GrantsMatrix() {}
}
