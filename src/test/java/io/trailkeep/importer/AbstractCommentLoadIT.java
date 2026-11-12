// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.user.ImportPeopleService;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code load} with comments, as the import user (WP-26 part u, design §6.2, §6.4, §7.2, §8.2): each case's staged
 * comments written in its transaction in the order they were written, formatted as the mapping declares, with matched
 * authors named as Trailkeep knows them and unmatched ones by their snapshot, a comment longer than one comment takes
 * split into numbered parts, a comment with an ERROR left out, the rest of a description too long for a case
 * continued, escaped, in the case's first comment, each comment appended to the search text and audited, and each
 * staged comment given its ledger row; and a comment the import API refuses taking its case with it until it is fixed.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractCommentLoadIT extends LoadSupport {

    /** The mapping, its description HTML and continued in a comment when too long for a case. */
    private static final String CONTINUED = MAPPING.replace(
            "description: details,",
            "description: { text: details, format: html, maxLength: 20000, onOverflow: continueInComment },");

    private static final String INSERT_COMMENT =
            "INSERT INTO IMPORT_STG_COMMENT (RUN_ID, LEGACY_KEY, ROW_HASH, CASE_KEY,"
                    + " AUTHOR_KEY, AUTHOR_NAME, WRITTEN_AT, BODY, BODY_FORMAT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    @Test
    void loadWritesEachCasesCommentsInOrderWithAuthorsPartsAndTheRestOfItsDescription(@TempDir Path directory)
            throws Exception {
        Path mapping = ownedMapping(directory, CONTINUED);
        long run = signedOffRun(mapping);
        stage(run, "c1", "OPEN", "p1");
        stage(run, "c2", "OPEN", "p1");
        jdbc.update(
                "UPDATE IMPORT_STG_CASE SET DESCRIPTION = ? WHERE RUN_ID = ? AND LEGACY_KEY = 'c1'",
                "<b>Start</b>" + "x".repeat(19_995) + "#tail",
                run);
        comment(run, "n1", "c1", "p1", null, "2024-02-01T09:00:00Z", "1. *first*", "plain");
        comment(run, "n2", "c1", "p2", null, "2024-01-20T09:00:00Z", "**bold**", "markdown");
        comment(run, "n3", "c1", null, "Synthetic Typed Name", "2024-03-01T09:00:00Z", "y".repeat(45_000), "plain");
        comment(run, "n4", "c1", "p1", null, "2024-03-02T09:00:00Z", "Synthetic skipped note", "plain");
        comment(run, "n5", "c2", "p1", null, "2024-03-02T09:00:00Z", "Synthetic note of a skipped case", "plain");
        error(run, Subject.COMMENT, "n4");
        error(run, Subject.CASE, "c2");

        load(run, mapping).close();

        long caseId = number(loadedCase("c1"), "ID");
        assertThat(jdbc.queryForObject("SELECT DESCRIPTION FROM CASE_RECORD WHERE ID = ?", String.class, caseId))
                .as("the HTML reduced to its text, then cut to what a case takes")
                .isEqualTo("Start" + "x".repeat(19_995));
        String analystName =
                jdbc.queryForObject("SELECT DISPLAY_NAME FROM APP_USER WHERE ID = ?", String.class, analyst);
        String importerName = jdbc.queryForObject(
                "SELECT DISPLAY_NAME FROM APP_USER WHERE ID = ?", String.class, ImportPeopleService.IMPORTER_ID);
        List<String> comments = comments(caseId);
        assertThat(comments)
                .hasSize(6)
                .startsWith(
                        ImportPeopleService.IMPORTER_ID + " " + importerName + " 2024-01-15T10:00:00Z \\#tail",
                        "- Synthetic Legacy Owner 2024-01-20T09:00:00Z **bold**",
                        analyst + " " + analystName + " 2024-02-01T09:00:00Z 1\\. \\*first\\*");
        for (int part = 1; part <= 3; part++) {
            assertThat(comments.get(2 + part))
                    .startsWith("- Synthetic Typed Name 2024-03-01T09:00:00Z (" + part + "/3)\nyyy");
        }
        assertThat(comments.subList(3, 6).stream()
                        .mapToInt(part -> part.length() - part.indexOf('\n') - 1)
                        .sum())
                .as("the parts hold the whole body")
                .isEqualTo(45_000);
        String search = Objects.requireNonNull(
                jdbc.queryForObject("SELECT SEARCH_TEXT FROM CASE_SEARCH WHERE CASE_ID = ?", String.class, caseId));
        assertThat(search).contains("**bold**", "1\\. \\*first\\*", "\\#tail", "(3/3)");
        assertThat(count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'COMMENT_IMPORTED' AND CASE_ID = ?", caseId))
                .isEqualTo(6);
        long firstPart = Long.parseLong(Objects.requireNonNull(jdbc.queryForObject(
                "SELECT MIN(ID) FROM CASE_COMMENT WHERE CASE_ID = ? AND AUTHOR_NAME_SNAPSHOT = 'Synthetic Typed Name'",
                String.class,
                caseId)));
        assertThat(childLedger(run))
                .containsExactly(
                        "COMMENT n1 " + caseId, "COMMENT n2 " + caseId, "COMMENT n3 " + caseId + " " + firstPart);
        assertThat(loadedCases("memory1:%"))
                .as("the case with an ERROR is skipped")
                .isEqualTo(1);
    }

    @Test
    void commentTheImportApiRefusesTakesItsCaseWithItUntilFixed(@TempDir Path directory) throws Exception {
        Path mapping = ownedMapping(directory, CONTINUED);
        long run = signedOffRun(mapping);
        stage(run, "c1", "OPEN", "p1");
        comment(run, "n1", "c1", "p1", null, "2024-02-01T09:00:00Z", "Synthetic first note", "plain");
        // Changed after the dry run: written in the future, which the comment import API refuses.
        comment(run, "n2", "c1", "p2", null, "2100-01-01T00:00:00Z", "Synthetic later note", "plain");

        load(run, mapping).close();

        assertThat(loadIssues(run)).containsExactly(LoadCommand.CASE_REFUSED + " " + hash("c1"));
        assertThat(loadedCases("memory1:%"))
                .as("the case went with its comment")
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM IMPORT_LEDGER WHERE RUN_ID = ?", run))
                .isZero();

        jdbc.update(
                "UPDATE IMPORT_STG_COMMENT SET WRITTEN_AT = ? WHERE RUN_ID = ? AND LEGACY_KEY = 'n2'",
                Timestamp.from(Instant.parse("2024-02-02T09:00:00Z")),
                run);
        load(run, mapping).close();

        assertThat(loadIssues(run)).isEmpty();
        assertThat(comments(number(loadedCase("c1"), "ID"))).hasSize(2);
        assertThat(childLedger(run)).hasSize(2);
    }

    private void comment(
            long run,
            String key,
            String caseKey,
            @Nullable String author,
            @Nullable String authorName,
            String writtenAt,
            String body,
            String format) {
        jdbc.update(
                INSERT_COMMENT,
                run,
                key,
                "cd".repeat(32),
                caseKey,
                author,
                authorName,
                Timestamp.from(Instant.parse(writtenAt)),
                body,
                format);
    }

    private void error(long run, Subject subject, String key) {
        issueRows.save(new ImportIssue(
                run,
                ImportIssue.Stage.DRY_RUN,
                subject,
                hash(key),
                Severity.ERROR,
                "BODY_MISSING",
                null,
                Instant.now()));
    }

    /** A case's comments in the order they were written: author id ("-" for none), name, time and body. */
    private List<String> comments(long caseId) {
        return jdbc.query(
                "SELECT AUTHOR_ID, AUTHOR_NAME_SNAPSHOT, CREATED_AT, BODY FROM CASE_COMMENT WHERE CASE_ID = ?"
                        + " ORDER BY ID",
                (row, index) -> (row.getObject(1) == null ? "-" : String.valueOf(row.getLong(1))) + " "
                        + row.getString(2) + " " + row.getTimestamp(3).toInstant() + " " + row.getString(4),
                caseId);
    }

    /** The run's ledger rows of children: entity, key, case id and, when it is not the case, the target id. */
    private List<String> childLedger(long run) {
        return jdbc.query(
                "SELECT ENTITY, LEGACY_KEY, CASE_ID, TARGET_ID FROM IMPORT_LEDGER WHERE RUN_ID = ? AND ENTITY <> 'CASE'"
                        + " ORDER BY LEGACY_KEY",
                (row, index) -> row.getString(1) + " " + row.getString(2) + " " + row.getLong(3)
                        + (row.getString(2).equals("n3") ? " " + row.getLong(4) : ""),
                run);
    }
}
