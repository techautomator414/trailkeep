// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.catchThrowable;

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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * What every {@code load} IT needs (WP-26 parts t and u): an analyst and a manager in the seeded team, the manager the
 * team's default owner, signed-off runs with two legacy people staged, staged cases, the command started as the import
 * user, and what it wrote read back; each test's cases, with their attachments, comments, closures, audit events,
 * SLA and search rows and ledger rows, and its users are removed after it. The tests are in {@link AbstractLoadIT},
 * {@link AbstractCommentLoadIT} and {@link AbstractAttachmentLoadIT}.
 */
abstract class LoadSupport extends DryRunSupport {

    @Autowired
    ImportIssueRepository issueRows;

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
        jdbc.update("DELETE FROM IMPORT_LEDGER WHERE SOURCE_ID = 'memory1'");
        for (long id : cases) {
            jdbc.update(
                    "DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN"
                            + " (SELECT ID FROM CASE_ATTACHMENT WHERE CASE_ID = ?)",
                    id);
            for (String table : List.of(
                    "CASE_ATTACHMENT", "CASE_COMMENT", "CLOSURE_REQUEST", "AUDIT_EVENT", "CASE_SLA", "CASE_SEARCH")) {
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
}
