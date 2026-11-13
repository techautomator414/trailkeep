// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CaseImportService.Created;
import io.trailkeep.casefile.CaseImportService.ImportRun;
import io.trailkeep.casefile.CaseImportService.ImportedCase;
import io.trailkeep.casefile.CaseImportService.ImportedComment;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.closure.ClosureImportService.ImportedClosure;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The import ledger's written hashes (WP-26 part aa1, design §9): the load records, for each row, the keyed hash of
 * what it wrote as read back from the database in its own transaction, and the same reads in a later transaction, as
 * {@code reconcile} will make them, give the same hash. The comments a row wrote besides its target (a split comment's
 * later parts, a case's description continuation) are listed in {@code IMPORT_LEDGER_PART}, so they are found exactly.
 * A case written with an empty optional text and instants finer than a microsecond, which Oracle stores as {@code NULL}
 * and to the microsecond, hashes the same at write time as later, because the forms are read from the database, never
 * from the persistence context.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractWrittenHashIT extends LoadSupport {

    /** The mapping, its description HTML and continued in a comment when too long for a case. */
    private static final String CONTINUED = MAPPING.replace(
            "description: details,",
            "description: { text: details, format: html, maxLength: 20000, onOverflow: continueInComment },");

    private static final SecretKey KEY = new SecretKeySpec(HASH_KEY, "HmacSHA256");

    /** Sub-microsecond instants: the database keeps six fractional digits of them. */
    private static final Instant OPENED = Instant.parse("2024-01-15T10:00:00.123456789Z");

    private static final Instant CLOSED = Instant.parse("2024-02-15T10:00:00.987654321Z");

    @Test
    void eachLedgerRowHashesWhatItsLoadWroteAndListsTheCommentsBesidesItsTarget(@TempDir Path directory)
            throws Exception {
        Path mapping = ownedMapping(directory, CONTINUED);
        long run = signedOffRun(mapping);
        stage(run, "c1", "CLOSED", "p1");
        jdbc.update(
                "UPDATE IMPORT_STG_CASE SET DESCRIPTION = ? WHERE RUN_ID = ? AND LEGACY_KEY = 'c1'",
                "x".repeat(20_000) + "#tail",
                run);
        jdbc.update(
                "INSERT INTO IMPORT_STG_COMMENT (RUN_ID, LEGACY_KEY, ROW_HASH, CASE_KEY, AUTHOR_KEY, WRITTEN_AT, BODY,"
                        + " BODY_FORMAT) VALUES (?, 'n1', ?, 'c1', 'p1', ?, ?, 'plain')",
                run,
                "cd".repeat(32),
                Timestamp.from(Instant.parse("2024-02-01T09:00:00Z")),
                "y".repeat(45_000));

        load(run, mapping).close();

        long caseId = number(loadedCase("c1"), "ID");
        List<Long> comments =
                jdbc.queryForList("SELECT ID FROM CASE_COMMENT WHERE CASE_ID = ? ORDER BY ID", Long.class, caseId);
        assertThat(comments).as("the continuation, then three parts").hasSize(4);
        assertThat(parts(run, "CASE")).containsExactly(comments.getFirst());
        assertThat(parts(run, "COMMENT")).containsExactly(comments.get(2), comments.get(3));
        assertThat(assertWrittenHashesHold(run))
                .as("the closed case's closure hash")
                .isEqualTo(1);
    }

    @Test
    void anEmptyTextAndSubMicrosecondInstantsHashTheSameWhenWrittenAndWhenReadLater(@TempDir Path directory)
            throws Exception {
        long run = signedOffRun(mapping(directory));
        long approver =
                number(jdbc.queryForMap("SELECT REQUESTED_BY FROM IMPORT_RUN WHERE ID = ?", run), "REQUESTED_BY");
        ImportRun audited = new ImportRun(run, approver);
        CaseFields fields = new CaseFields(
                caseType,
                "Synthetic written case",
                CasePriority.NORMAL,
                LocalDate.parse("2024-01-15"),
                team,
                analyst,
                "",
                "Synthetic description",
                Sensitivity.STANDARD);
        ImportedCase imported = new ImportedCase(
                fields, "memory1:written", "", "Synthetic Matched Owner", CaseStatus.CLOSED, OPENED, CLOSED, null);

        // As the load's case transaction does: write, then hash what the database now holds.
        String[] atWrite = Objects.requireNonNull(transactions.execute(status -> {
            Created created = caseImports.importCase(audited, imported);
            long comment = caseImports.importComment(
                    audited, new ImportedComment(created.number(), "Synthetic note", null, "Synthetic Author", OPENED));
            closureImports.importClosure(
                    new ImportedClosure(created.caseId(), "SUBSTANTIATED", "", CLOSED, run, approver));
            return new String[] {
                String.valueOf(created.caseId()),
                String.valueOf(comment),
                WrittenHash.ofCase(KEY, caseImports, created.caseId(), List.of(comment)),
                WrittenHash.ofClosure(KEY, closureImports, created.caseId())
            };
        }));
        long caseId = Long.parseLong(atWrite[0]);
        long comment = Long.parseLong(atWrite[1]);

        String caseLater =
                transactions.execute(status -> WrittenHash.ofCase(KEY, caseImports, caseId, List.of(comment)));
        String closureLater = transactions.execute(status -> WrittenHash.ofClosure(KEY, closureImports, caseId));
        assertThat(caseLater).isEqualTo(atWrite[2]);
        assertThat(closureLater).isEqualTo(atWrite[3]);
    }

    /** The comment ids listed for the run's ledger rows of one entity, in order. */
    private List<Long> parts(long run, String entity) {
        return jdbc.queryForList(
                "SELECT P.COMMENT_ID FROM IMPORT_LEDGER_PART P JOIN IMPORT_LEDGER L ON L.ID = P.LEDGER_ID"
                        + " WHERE L.RUN_ID = ? AND L.ENTITY = ? ORDER BY P.PART_NO",
                Long.class,
                run,
                entity);
    }
}
