// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.document.ImportClamd;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.user.ImportPeopleService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code load} with attachments, as the import user (WP-26 part u, design §4.3, §8.2): each case's attachments scanned
 * before its transaction and stored in it, read where the extract hashed them (under {@code attachments.root} or in
 * the run's content area), with their uploaders, legacy times, ledger rows and the scanning mode in force; those with
 * an ERROR or no bytes staged left out silently, those the intake or clamd's size limit refuses left out as {@code
 * LOAD} issues while their case loads, infected ones stored quarantined and reported; a file that changed or went
 * keeping its case out until it is back; and a scanner outage stopping the load, resumable.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractAttachmentLoadIT extends LoadSupport {

    private static final String INSERT_ATTACHMENT = "INSERT INTO IMPORT_STG_ATTACHMENT (RUN_ID, LEGACY_KEY, ROW_HASH,"
            + " CASE_KEY, FILE_NAME, SIZE_BYTES, SHA256, CONTENT_POINTER, UPLOADED_AT, UPLOADER_KEY, UPLOADER_NAME)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final Instant UPLOADED = Instant.parse("2024-01-20T09:00:00Z");

    private static final String EXTERNAL = "--app.attachments.scanning=external";

    @Test
    void loadStoresEachCasesAttachmentsWithTheirUploadersAndLeavesOutWhatItMayNot(@TempDir Path directory)
            throws Exception {
        Path files = Files.createDirectories(directory.resolve("files")).toRealPath();
        Path mapping = ownedMapping(directory, MAPPING + "attachments: { root: \"" + files + "\" }\n");
        long run = signedOffRun(mapping);
        jdbc.update("UPDATE IMPORT_RUN SET SCAN_MODE = 'EXTERNAL' WHERE ID = ?", run);
        stage(run, "c1", "OPEN", "p1");
        attach(run, "a1", "c1", "a1.txt", "a1.txt", write(files, "a1.txt", "Synthetic legacy note"));
        attach(run, "a2", "c1", "C:\\legacy\\a2.txt", "a2.txt", write(files, "a2.txt", "Second"));
        jdbc.update(
                "UPDATE IMPORT_STG_ATTACHMENT SET UPLOADER_KEY = NULL, UPLOADER_NAME = ? WHERE RUN_ID = ?"
                        + " AND LEGACY_KEY = 'a2'",
                "Synthetic Typed Uploader",
                run);
        leftOut(run, files);

        load(run, mapping, EXTERNAL).close();

        long caseId = number(loadedCase("c1"), "ID");
        assertThat(attachments(caseId))
                .containsExactly(
                        "a1.txt NOT_SCANNED " + analyst + " - " + UPLOADED,
                        "a2.txt NOT_SCANNED " + ImportPeopleService.IMPORTER_ID + " Synthetic Typed Uploader "
                                + UPLOADED);
        assertThat(count(
                        "SELECT COUNT(*) FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN"
                                + " (SELECT ID FROM CASE_ATTACHMENT WHERE CASE_ID = ?)",
                        caseId))
                .isEqualTo(2);
        assertThat(count(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'ATTACHMENT_IMPORTED' AND CASE_ID = ?",
                        caseId))
                .isEqualTo(2);
        assertThat(jdbc.queryForList(
                        "SELECT LEGACY_KEY FROM IMPORT_LEDGER WHERE RUN_ID = ? AND ENTITY = 'ATTACHMENT'"
                                + " AND TARGET_ID IN (SELECT ID FROM CASE_ATTACHMENT WHERE CASE_ID = ?)"
                                + " ORDER BY LEGACY_KEY",
                        String.class,
                        run,
                        caseId))
                .containsExactly("a1", "a2");
        assertThat(attachmentIssues(run))
                .as("only what the load found; a3 and a4 stay as the dry run reported them")
                .containsExactly("ERROR ATTACHMENT_TYPE_NOT_ALLOWED " + hash("a5") + " -");
        assertThat(loadIssues(run)).containsExactly("ATTACHMENT_TYPE_NOT_ALLOWED " + hash("a5"));

        load(run, mapping, EXTERNAL).close();

        assertThat(attachmentIssues(run))
                .as("a loaded case's attachment issues outlive the next load's claim")
                .hasSize(1);
    }

    @Test
    void attachmentChangedOrGoneSinceTheExtractKeepsItsCaseOutUntilItIsBack(@TempDir Path directory) throws Exception {
        Path mapping = ownedMapping(
                directory,
                MAPPING.replace("content: { path: path, size: bytes }", "content: { binary: hex, column: data }"));
        long run = signedOffRun(mapping);
        jdbc.update("UPDATE IMPORT_RUN SET SCAN_MODE = 'EXTERNAL' WHERE ID = ?", run);
        stage(run, "c1", "OPEN", "p1");
        stage(run, "c2", "OPEN", "p1");
        Path area = Files.createDirectories(directory.resolve("area")).toRealPath();
        byte[] note = "Synthetic stored note".getBytes(StandardCharsets.UTF_8);
        String sha256 = ValidateMappingCommand.sha256(note);
        Path stored = Files.createDirectories(area.resolve(Long.toString(run))).resolve(sha256);
        Files.writeString(stored, "Synthetic stored note, changed after the extract");
        attach(run, "a1", "c1", "note.txt", sha256, sha256);
        attach(run, "a2", "c2", "gone.txt", "ef".repeat(32), "ef".repeat(32));
        String areaArgument = "--import.content-area=" + area;

        load(run, mapping, EXTERNAL, areaArgument).close();

        assertThat(loadIssues(run))
                .containsExactlyInAnyOrder(
                        AttachmentLoad.CONTENT_CHANGED + " " + hash("c1"),
                        AttachmentLoad.CONTENT_CHANGED + " " + hash("c2"));
        assertThat(loadedCases("memory1:%")).isZero();

        Files.write(stored, note);
        load(run, mapping, EXTERNAL, areaArgument).close();

        assertThat(loadIssues(run)).containsExactly(AttachmentLoad.CONTENT_CHANGED + " " + hash("c2"));
        assertThat(attachments(number(loadedCase("c1"), "ID")))
                .containsExactly("note.txt NOT_SCANNED " + analyst + " - " + UPLOADED);
    }

    @Test
    void scannerOutageStopsTheLoadAndTheNextLoadStoresScannedQuarantinedAndReportsTheOversized(@TempDir Path directory)
            throws Exception {
        Path files = Files.createDirectories(directory.resolve("files")).toRealPath();
        Path mapping = ownedMapping(directory, MAPPING + "attachments: { root: \"" + files + "\" }\n");
        long run = signedOffRun(mapping);
        stage(run, "c1", "OPEN", "p1");
        attach(run, "a1", "c1", "clean.txt", "clean.txt", write(files, "clean.txt", "Synthetic clean note"));
        attach(run, "a2", "c1", "bad.txt", "bad.txt", write(files, "bad.txt", "Synthetic infected note"));
        attach(run, "a3", "c1", "big.txt", "big.txt", write(files, "big.txt", "Synthetic oversized note"));

        // The test profile's clamd is unreachable on purpose.
        assertThatThrownBy(() -> load(run, mapping))
                .hasStackTraceContaining("Import run " + run + ": the load stopped");
        assertThat(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, run))
                .isEqualTo("FAILED");
        assertThat(loadedCases("memory1:%")).isZero();

        try (ImportClamd clamd = ImportClamd.answering(bytes -> {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.contains("infected")) {
                return "stream: Synthetic-Test-Signature FOUND";
            }
            return text.contains("oversized") ? ImportClamd.SIZE_LIMIT : "stream: OK";
        })) {
            load(run, mapping, clamd.arguments().toArray(String[]::new)).close();
        }

        assertThat(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, run))
                .isEqualTo("LOADED");
        assertThat(attachments(number(loadedCase("c1"), "ID")))
                .containsExactly(
                        "clean.txt CLEAN " + analyst + " - " + UPLOADED,
                        "bad.txt QUARANTINED " + analyst + " - " + UPLOADED);
        assertThat(attachmentIssues(run))
                .containsExactly(
                        "ERROR ATTACHMENT_TOO_LARGE " + hash("a3") + " 24",
                        "WARNING ATTACHMENT_QUARANTINED " + hash("a2") + " -");
    }

    /**
     * Stages three attachments of c1 the load leaves out: a3 over the cap at extract, so never hashed and skipped by
     * rule; a4 with an ERROR from the dry run; a5 an executable named as text, which the intake refuses.
     */
    private void leftOut(long run, Path files) throws Exception {
        jdbc.update(
                INSERT_ATTACHMENT,
                run,
                "a3",
                "cd".repeat(32),
                "c1",
                "big.txt",
                1L << 40,
                null,
                "big.txt",
                Timestamp.from(UPLOADED),
                null,
                null);
        attach(run, "a4", "c1", "a4.txt", "a4.txt", write(files, "a4.txt", "Synthetic note with an error"));
        issueRows.save(new ImportIssue(
                run,
                ImportIssue.Stage.DRY_RUN,
                Subject.ATTACHMENT,
                hash("a4"),
                Severity.ERROR,
                "UPLOADED_AT_MISSING",
                null,
                Instant.now()));
        Path disguised = files.resolve("invoice.txt");
        Files.write(disguised, new byte[] {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0});
        attach(
                run,
                "a5",
                "c1",
                "invoice.txt",
                "invoice.txt",
                ValidateMappingCommand.sha256(Files.readAllBytes(disguised)));
    }

    /** Writes a file under the root; its SHA-256, as the extract staged it. */
    private static String write(Path files, String name, String text) throws Exception {
        return ValidateMappingCommand.sha256(Files.readAllBytes(Files.writeString(files.resolve(name), text)));
    }

    /** Stages an attachment uploaded by the matched legacy person p1, staged as 24 bytes. */
    private void attach(long run, String key, String caseKey, String fileName, String pointer, String sha256) {
        jdbc.update(
                INSERT_ATTACHMENT,
                run,
                key,
                "cd".repeat(32),
                caseKey,
                fileName,
                24L,
                sha256,
                pointer,
                Timestamp.from(UPLOADED),
                "p1",
                null);
    }

    /** A case's attachments in the order stored: name, scan status, uploader, snapshot ("-" for none) and time. */
    private List<String> attachments(long caseId) {
        return jdbc.query(
                "SELECT FILE_NAME, SCAN_STATUS, UPLOADED_BY, UPLOADER_NAME_SNAPSHOT, UPLOADED_AT FROM CASE_ATTACHMENT"
                        + " WHERE CASE_ID = ? ORDER BY ID",
                (row, index) -> row.getString(1) + " " + row.getString(2) + " " + row.getLong(3) + " "
                        + (row.getString(4) == null ? "-" : row.getString(4)) + " "
                        + row.getTimestamp(5).toInstant(),
                caseId);
    }

    /** The run's {@code LOAD} issues on attachments: severity, code, keyed hash and detail ("-" for none). */
    private List<String> attachmentIssues(long run) {
        return jdbc.query(
                "SELECT SEVERITY, CODE, KEY_HASH, DETAIL FROM IMPORT_ISSUE WHERE RUN_ID = ? AND STAGE = 'LOAD'"
                        + " AND ENTITY = 'ATTACHMENT' ORDER BY SEVERITY, CODE",
                (row, index) -> row.getString(1) + " " + row.getString(2) + " " + row.getString(3) + " "
                        + (row.getObject(4) == null ? "-" : String.valueOf(row.getLong(4))),
                run);
    }
}
