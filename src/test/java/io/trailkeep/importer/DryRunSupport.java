// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.CaseSeed;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * What every {@code dry-run} IT needs (WP-26 parts q, r): a team and a case type with one outcome, removed after each
 * test, extracted runs, staged cases seeded by the schema owner, the command started as the import user, and its issues
 * and figures read back. The tests are in {@link AbstractDryRunIT} and {@link AbstractDryRunPeopleIT}.
 */
abstract class DryRunSupport extends ExtractJobSupport {

    static final String INSERT = "INSERT INTO IMPORT_STG_CASE (RUN_ID, LEGACY_KEY, ROW_HASH, TITLE, DESCRIPTION,"
            + " TEAM_KEY, CASE_TYPE_KEY, STATUS, IDENTIFIED_ON, OPENED_AT, CLOSED_AT, CLOSURE_OUTCOME)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    long team;
    long caseType;

    @BeforeEach
    void seedTheReferenceData() {
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, ?, 1)",
                caseType,
                "SUBSTANTIATED");
    }

    @AfterEach
    void removeTheReferenceData() {
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE_OUTCOME WHERE CASE_TYPE_ID = ?", caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
    }

    long extractedRun(Path mapping) throws Exception {
        long run = validatedRun(mapping);
        jdbc.update("UPDATE IMPORT_RUN SET STATUS = 'EXTRACTED', SOURCE_FINGERPRINT = 'memory-fp-1' WHERE ID = ?", run);
        return run;
    }

    Object[] row(long run, String key, @Nullable String title, String status, @Nullable String outcome) {
        boolean closed = "CLOSED".equals(status);
        return new Object[] {
            run,
            key,
            "ab".repeat(32),
            title,
            "Synthetic description",
            "Seed Team " + team,
            "Seed " + caseType,
            status,
            Date.valueOf(LocalDate.of(2024, 1, 14)),
            Timestamp.from(Instant.parse("2024-01-15T10:00:00Z")),
            closed ? Timestamp.from(Instant.parse("2024-03-01T10:00:00Z")) : null,
            outcome
        };
    }

    ConfigurableApplicationContext dryRun(long run, Path mapping, String... more) throws SQLException {
        return command("dry-run", run, mapping, more);
    }

    /** Starts an importer command that reads the mapping and the hash key, with any further arguments. */
    ConfigurableApplicationContext command(String command, long run, Path mapping, String... more) throws SQLException {
        List<String> args = new ArrayList<>(List.of(
                "--import.command=" + command,
                "--import.run=" + run,
                "--import.mapping=" + mapping,
                "--import.hash-key=" + Base64.getEncoder().encodeToString(HASH_KEY)));
        args.addAll(List.of(more));
        return start(args.toArray(String[]::new));
    }

    List<List<String>> dryRunIssues(long run) {
        return jdbc.query(
                "SELECT ENTITY, SEVERITY, CODE, KEY_HASH FROM IMPORT_ISSUE WHERE RUN_ID = ? AND STAGE = 'DRY_RUN'",
                (row, index) -> List.of(
                        row.getString(1),
                        row.getString(2),
                        row.getString(3),
                        row.getString(4) == null ? "" : row.getString(4)),
                run);
    }

    List<String> figures(long run) {
        return jdbc.query(
                "SELECT METRIC, DIMENSION, METRIC_VALUE FROM IMPORT_RUN_SUMMARY WHERE RUN_ID = ?",
                (row, index) -> row.getString(1) + " " + Objects.requireNonNullElse(row.getString(2), "-") + " "
                        + row.getLong(3),
                run);
    }
}
