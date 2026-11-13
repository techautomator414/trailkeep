// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.trailkeep.audit.ImportedHistoryService;
import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.closure.ClosureImportService;
import io.trailkeep.document.AttachmentImportService;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.user.TeamRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What every {@code load} IT needs (WP-26 parts t, u and w): an analyst and a manager in the seeded team, the manager
 * the team's default owner, signed-off runs with two legacy people staged, staged cases, the command started as the
 * import user, and what it wrote read back; each test's cases, with their pending owners, attachments, comments,
 * imported history, closures, audit events, SLA and search rows and ledger rows, and its users are removed after it.
 * The tests are in {@link AbstractLoadIT}, {@link AbstractCommentLoadIT}, {@link AbstractAttachmentLoadIT} and {@link
 * AbstractEventLoadIT}.
 */
abstract class LoadSupport extends DryRunSupport {

    @Autowired
    ImportIssueRepository issueRows;

    @Autowired
    CaseImportService caseImports;

    @Autowired
    ClosureImportService closureImports;

    @Autowired
    AttachmentImportService attachmentImports;

    @Autowired
    ImportedHistoryService history;

    @Autowired
    TransactionTemplate transactions;

    @Value("${app.db.import-user}")
    String importUser = "";

    final List<Long> users = new ArrayList<>();
    long analyst;
    long manager;
    Path owners = Path.of("unset");

    @BeforeEach
    void seedTheOwners() {
        CaseSeed seed = new CaseSeed(jdbc);
        analyst = seed.user();
        manager = seed.user();
        users.addAll(List.of(analyst, manager));
        seed.member(analyst, team, TeamRole.ANALYST);
        seed.member(manager, team, TeamRole.MANAGER);
    }

    @AfterEach
    void removeWhatTheLoadWrote() {
        List<Long> cases =
                jdbc.queryForList("SELECT ID FROM CASE_RECORD WHERE LEGACY_REF LIKE 'memory1:%'", Long.class);
        jdbc.update("DELETE FROM IMPORT_LEDGER_PART WHERE LEDGER_ID IN"
                + " (SELECT ID FROM IMPORT_LEDGER WHERE SOURCE_ID = 'memory1')");
        jdbc.update("DELETE FROM IMPORT_LEDGER WHERE SOURCE_ID = 'memory1'");
        for (long id : cases) {
            jdbc.update(
                    "DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN"
                            + " (SELECT ID FROM CASE_ATTACHMENT WHERE CASE_ID = ?)",
                    id);
            for (String table : List.of(
                    "IMPORT_OWNER_PENDING",
                    "ALERT_LEDGER",
                    "CASE_ATTACHMENT",
                    "CASE_COMMENT",
                    "CASE_IMPORTED_EVENT",
                    "LEGAL_HOLD_EVENT",
                    "CLOSURE_REQUEST",
                    "AUDIT_EVENT",
                    "CASE_SLA",
                    "CASE_SEARCH")) {
                // Test-only cleanup over the fixed table names of this list.
                jdbc.update("DELETE FROM " + table + " WHERE CASE_ID = ?", id);
            }
            jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", id);
        }
        for (long user : users) {
            jdbc.update("DELETE FROM APP_USER_TEAM WHERE USER_ID = ?", user);
            jdbc.update("DELETE FROM APP_USER WHERE ID = ?", user);
        }
        users.clear();
    }

    /** The mapping with {@code defaults.owner.byTeam} naming the manager for the seeded team. */
    Path mapping(Path directory) throws Exception {
        return ownedMapping(directory, MAPPING);
    }

    /** A mapping with {@code defaults.owner.byTeam} naming the manager for the seeded team. */
    Path ownedMapping(Path directory, String text) throws Exception {
        owners = Files.writeString(
                directory.resolve("owners.csv"),
                "team,owner\nSeed Team " + team + ",seed" + manager + "@example.test\n");
        return mapping(directory, text + "defaults: { owner: { byTeam: owners.csv } }\n");
    }

    /** An extracted, dry-run run, signed off as the Import runs screen would, with the two legacy people staged. */
    long signedOffRun(Path mapping) throws Exception {
        long run = extractedRun(mapping);
        jdbc.update("UPDATE IMPORT_RUN SET STATUS = 'DRY_RUN', SCAN_MODE = 'CLAMD' WHERE ID = ?", run);
        approve(run);
        String person = "INSERT INTO IMPORT_STG_PERSON (RUN_ID, LEGACY_KEY, ROW_HASH, DISPLAY_NAME, EMAIL)"
                + " VALUES (?, ?, ?, ?, ?)";
        jdbc.update(person, run, "p1", "ab".repeat(32), "Synthetic Matched Owner", "seed" + analyst + "@example.test");
        jdbc.update(person, run, "p2", "ab".repeat(32), "Synthetic Legacy Owner", "nobody@example.test");
        return run;
    }

    void approve(long run) {
        jdbc.update(
                "UPDATE IMPORT_RUN SET APPROVED_BY = REQUESTED_BY, APPROVED_AT = ? WHERE ID = ?",
                Timestamp.from(Instant.now()),
                run);
    }

    void loading(long run, Instant movedOn) {
        jdbc.update(
                "UPDATE IMPORT_RUN SET STATUS = 'LOADING', UPDATED_AT = ? WHERE ID = ?", Timestamp.from(movedOn), run);
    }

    void stage(long run, String key, String status, String owner) {
        jdbc.update(INSERT, row(run, key, "Synthetic case", status, "CLOSED".equals(status) ? "SUBSTANTIATED" : null));
        jdbc.update("UPDATE IMPORT_STG_CASE SET OWNER_KEY = ? WHERE RUN_ID = ? AND LEGACY_KEY = ?", owner, run, key);
    }

    ConfigurableApplicationContext load(long run, Path mapping, String... more) throws SQLException {
        return command("load", run, mapping, more);
    }

    /** The code a refused load names; the refusal is the cause of the failed start. */
    LoadRefusal refusal(long run, Path mapping, String... more) {
        Throwable failure = catchThrowable(() -> load(run, mapping, more));
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof LoadRefusal.RefusedException refused) {
                return refused.refusal();
            }
        }
        throw new AssertionError("load was not refused", failure);
    }

    /**
     * Archives a loaded case as the archive job does and, with {@code purge}, tombstones it as the purge job does: its
     * comments and search text deleted, description and subject cleared, {@code PURGED_AT} set. Its ledger rows stay.
     */
    void archive(long caseId, boolean purge) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "UPDATE CASE_RECORD SET STATUS = 'ARCHIVED', ARCHIVED_AT = ?, PURGE_AFTER = ? WHERE ID = ?",
                now,
                now,
                caseId);
        if (purge) {
            jdbc.update("DELETE FROM CASE_COMMENT WHERE CASE_ID = ?", caseId);
            jdbc.update("DELETE FROM CASE_SEARCH WHERE CASE_ID = ?", caseId);
            jdbc.update(
                    "UPDATE CASE_RECORD SET DESCRIPTION = NULL, SUBJECT_TEXT = NULL, PURGED_AT = ? WHERE ID = ?",
                    now,
                    caseId);
        }
    }

    int loadedCases(String legacyRefs) {
        Integer cases = jdbc.queryForObject(
                "SELECT COUNT(*) FROM CASE_RECORD WHERE LEGACY_REF LIKE ?", Integer.class, legacyRefs);
        return cases == null ? 0 : cases;
    }

    Map<String, @Nullable Object> loadedCase(String key) {
        return jdbc.queryForMap(
                "SELECT ID, STATUS, OWNER_ID, OWNER_NAME_SNAPSHOT FROM CASE_RECORD WHERE LEGACY_REF = ?",
                "memory1:" + key);
    }

    /** The run's {@code LOAD} issues, each an ERROR: code and keyed hash, in code order. */
    List<String> loadIssues(long run) {
        return jdbc.query(
                "SELECT CODE, KEY_HASH FROM IMPORT_ISSUE WHERE RUN_ID = ? AND STAGE = 'LOAD' AND SEVERITY = 'ERROR'"
                        + " ORDER BY CODE",
                (row, index) -> row.getString(1) + " " + row.getString(2),
                run);
    }

    /** The run's ledger rows: key, status, case id and whether a closing time was recorded, in key order. */
    List<String> ledger(long run) {
        return jdbc.query(
                "SELECT LEGACY_KEY, STATUS, CASE_ID, CLOSED_AT FROM IMPORT_LEDGER WHERE RUN_ID = ? ORDER BY LEGACY_KEY",
                (row, index) -> row.getString(1) + " " + row.getString(2) + " " + row.getLong(3) + " "
                        + (row.getTimestamp(4) == null ? "-" : "closed"),
                run);
    }

    /**
     * Recomputes every written hash of the run's ledger rows from Trailkeep in a new transaction, as {@code reconcile}
     * will (WP-26 part aa1, design §9), and asserts each equals the hash the load recorded.
     *
     * @return how many rows held a closure hash
     */
    int assertWrittenHashesHold(long run) {
        SecretKey key = new SecretKeySpec(HASH_KEY, "HmacSHA256");
        List<Map<String, @Nullable Object>> rows = jdbc.queryForList(
                "SELECT ID, ENTITY, TARGET_ID, WRITTEN_HASH, CLOSURE_HASH FROM IMPORT_LEDGER WHERE RUN_ID = ?", run);
        assertThat(rows).isNotEmpty();
        int closures = 0;
        for (Map<String, @Nullable Object> row : rows) {
            long target = number(row, "TARGET_ID");
            List<Long> parts = jdbc.queryForList(
                    "SELECT COMMENT_ID FROM IMPORT_LEDGER_PART WHERE LEDGER_ID = ? ORDER BY PART_NO",
                    Long.class,
                    number(row, "ID"));
            List<Long> all = Stream.concat(Stream.of(target), parts.stream()).toList();
            String entity = String.valueOf(row.get("ENTITY"));
            String expected = transactions.execute(status -> switch (entity) {
                case "CASE" -> WrittenHash.ofCase(key, caseImports, target, parts);
                case "COMMENT" -> WrittenHash.of(key, all, caseImports.writtenComments(all));
                case "ATTACHMENT" -> WrittenHash.of(key, all, attachmentImports.writtenAttachments(all));
                default -> WrittenHash.of(key, all, history.writtenEvents(all));
            });
            assertThat(row.get("WRITTEN_HASH")).as(entity + " " + target).isEqualTo(expected);
            if (row.get("CLOSURE_HASH") != null) {
                closures++;
                assertThat(row.get("CLOSURE_HASH"))
                        .as("closure of " + target)
                        .isEqualTo(transactions.execute(status -> WrittenHash.ofClosure(key, closureImports, target)));
            }
        }
        return closures;
    }
}
