// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code extract} as the import user (WP-26 part k1b, design §3 step 2, §4.1, §5), mostly from a source held in memory,
 * and end to end from JSON Lines files with their manifest (26k2); every source (26k2, 26l, 26n2) reaches the extract
 * through the same {@link SourceRows}. This class's own context (the schema owner) migrates, seeds the runs and reads
 * staging back.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractExtractIT extends ExtractJobSupport {

    @Test
    void extractStagesEveryEntityWithItsIssuesAndMovesTheRunOn(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory, MAPPING);
        long run = validatedRun(mapping);
        Instant before = Instant.now();

        extract(run, mapping, new MemorySource("memory-fp-1", null)).close();

        assertThat(jdbc.queryForMap(
                        "SELECT STATUS, ROWS_STAGED, SOURCE_FINGERPRINT, STAGING_EXPIRES_AT FROM IMPORT_RUN"
                                + " WHERE ID = ?",
                        run))
                .containsEntry("STATUS", "EXTRACTED")
                .containsEntry("SOURCE_FINGERPRINT", "memory-fp-1")
                .satisfies(row -> assertThat(number(row, "ROWS_STAGED")).isEqualTo(8))
                .satisfies(row -> assertThat(instant(row, "STAGING_EXPIRES_AT"))
                        .as("import.staging-ttl defaults to 14 days")
                        .isBetween(
                                before.plus(Duration.ofDays(14)), Instant.now().plus(Duration.ofDays(14))));
        assertThat(staged(run)).containsExactly(2, 3, 2, 1, 0);
        assertThat(count("SELECT COUNT(*) FROM IMPORT_RUN_CHECKPOINT WHERE RUN_ID = ? AND STAGE = 'EXTRACT'", run))
                .as("every entity, the unmapped event too")
                .isEqualTo(5);
        assertThat(jdbc.queryForMap(
                        "SELECT TITLE, STATUS, OWNER_KEY, OPENED_AT, ROW_HASH FROM IMPORT_STG_CASE"
                                + " WHERE RUN_ID = ? AND LEGACY_KEY = 'c1'",
                        run))
                .containsEntry("TITLE", "Synthetic case one")
                .containsEntry("STATUS", "OPEN")
                .containsEntry("OWNER_KEY", "u1")
                .satisfies(
                        row -> assertThat(instant(row, "OPENED_AT")).isEqualTo(Instant.parse("2019-03-01T09:30:00Z")))
                .satisfies(row -> assertThat((String) row.get("ROW_HASH")).hasSize(64));
        assertThat(jdbc.queryForMap(
                        "SELECT CASE_KEY, CONTENT_POINTER, SIZE_BYTES FROM IMPORT_STG_ATTACHMENT WHERE RUN_ID = ?",
                        run))
                .containsEntry("CASE_KEY", "c1")
                .containsEntry("CONTENT_POINTER", "a/scan.pdf")
                .satisfies(row -> assertThat(number(row, "SIZE_BYTES")).isEqualTo(2048));
        assertThat(issues(run))
                .containsExactlyInAnyOrder(
                        List.of("CASE", "ERROR", "LOOKUP_MISSING", hash("c3")),
                        List.of("COMMENT", "ERROR", "DUPLICATE_KEY", hash("n2")));
    }

    @Test
    void crashedExtractRestartsAtItsFirstUnfinishedEntityOrOverOnAnotherPoint(@TempDir Path directory)
            throws Exception {
        Path mapping = mapping(directory, MAPPING);
        long sameRun = validatedRun(mapping);
        long otherRun = validatedRun(mapping);
        for (long run : List.of(sameRun, otherRun)) {
            assertThatThrownBy(() -> extract(run, mapping, new MemorySource("memory-fp-1", StagedEntity.COMMENT)))
                    .hasMessage("planted source failure");
            assertThat(staged(run)).as("one batch of comments was written").containsExactly(2, 3, 2, 0, 0);
        }
        assertThatThrownBy(() -> start(
                        "--import.command=validate-mapping", "--import.run=" + sameRun, "--import.mapping=" + mapping))
                .as("the run is now this mapping and this snapshot")
                .hasMessageContaining("not awaiting a mapping");

        MemorySource same = new MemorySource("memory-fp-1", null);
        extract(sameRun, mapping, same).close();
        MemorySource other = new MemorySource("memory-fp-2", null);
        extract(otherRun, mapping, other).close();

        assertThat(same.entitiesRead()).containsExactly(StagedEntity.COMMENT, StagedEntity.ATTACHMENT);
        assertThat(other.entitiesRead())
                .containsExactly(StagedEntity.PERSON, StagedEntity.CASE, StagedEntity.COMMENT, StagedEntity.ATTACHMENT);
        for (long run : List.of(sameRun, otherRun)) {
            assertThat(staged(run)).containsExactly(2, 3, 2, 1, 0);
            assertThat(count("SELECT ROWS_STAGED FROM IMPORT_RUN WHERE ID = ? AND STATUS = 'EXTRACTED'", run))
                    .isEqualTo(8);
            assertThat(issues(run)).hasSize(2);
        }
    }

    @Test
    void extractRefusesWhatItCannotStageSoundly(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory, MAPPING);
        long changed = validatedRun(mapping);
        Files.writeString(mapping, MAPPING.replace("batchSize: 2", "batchSize: 3"));
        assertThatThrownBy(() -> extract(changed, mapping, new MemorySource("memory-fp-1", null)))
                .hasMessage("The mapping file changed after validate-mapping; validate it again");

        Path elsewhere = mapping(directory, MAPPING.replace("id: memory1", "id: legacy9"));
        long unsupported = validatedRun(elsewhere);
        assertThatThrownBy(() -> extract(unsupported, elsewhere, new MemorySource("memory-fp-1", null)))
                .hasMessage("Import run " + unsupported + " cannot be extracted: SOURCE_UNSUPPORTED");
        assertThat(issues(unsupported)).containsExactly(List.of("MAPPING", "FATAL", "SOURCE_UNSUPPORTED", ""));

        Path noCaseKey = mapping(directory, MAPPING.replace("key: case_id", "key: case_ref"));
        long fatal = validatedRun(noCaseKey);
        assertThatThrownBy(() -> extract(fatal, noCaseKey, new MemorySource("memory-fp-1", null)))
                .hasMessage(
                        "Import run " + fatal + " has a FATAL extract issue; fix the mapping and request a new run");
        assertThat(issues(fatal)).contains(List.of("CASE", "FATAL", "COLUMN_MISSING", ""));
        assertThat(staged(fatal))
                .as("nothing of the case entity is checkpointed")
                .containsExactly(2, 0, 0, 0, 0);

        assertThatThrownBy(() ->
                        start("--import.command=extract", "--import.run=" + fatal, "--import.mapping=" + noCaseKey))
                .as("no import.hash-key")
                .hasMessage("import.hash-key must be Base64 of at least 32 bytes to extract");
    }

    @Test
    void jsonLinesFilesAreStagedAgainstTheirManifestAndAChangedFileStopsTheExtract(@TempDir Path directory)
            throws Exception {
        byte[] manifest = JsonLinesExport.write(directory);
        Path mapping = mapping(
                directory,
                MAPPING.replace(".mem", ".jsonl") + "attachments: { root: \"" + directory.resolve("files") + "\" }\n");
        long run = validatedRun(mapping);

        extract(run, mapping, null).close();

        assertThat(jdbc.queryForMap(
                        "SELECT CONTENT_POINTER, SIZE_BYTES, SHA256 FROM IMPORT_STG_ATTACHMENT WHERE RUN_ID = ?", run))
                .as("hashed in place under attachments.root; the legacy size column yields to the file's")
                .containsEntry("CONTENT_POINTER", "a/scan.pdf")
                .containsEntry("SHA256", ValidateMappingCommand.sha256(JsonLinesExport.SCAN))
                .satisfies(row -> assertThat(number(row, "SIZE_BYTES")).isEqualTo(JsonLinesExport.SCAN.length));

        assertThat(jdbc.queryForMap("SELECT STATUS, SOURCE_FINGERPRINT FROM IMPORT_RUN WHERE ID = ?", run))
                .containsEntry("STATUS", "EXTRACTED")
                .containsEntry("SOURCE_FINGERPRINT", "manifest:" + ValidateMappingCommand.sha256(manifest));
        assertThat(staged(run)).containsExactly(2, 1, 1, 1, 0);
        assertThat(jdbc.queryForObject(
                        "SELECT CASE_KEY FROM IMPORT_STG_COMMENT WHERE RUN_ID = ? AND LEGACY_KEY = 'n1'",
                        String.class,
                        run))
                .as("100.0 in one file is the case 100 of another")
                .isEqualTo("100");
        assertThat(issues(run)).isEmpty();

        long changed = validatedRun(mapping);
        Files.writeString(directory.resolve("notes.jsonl"), "{\"note_id\": \"n2\", \"case_id\": 100}\n");
        assertThatThrownBy(() -> extract(changed, mapping, null))
                .hasMessage("Import run " + changed + " cannot be extracted: MANIFEST_MISMATCH");
        assertThat(issues(changed)).containsExactly(List.of("COMMENT", "FATAL", "MANIFEST_MISMATCH", ""));
        assertThat(count("SELECT COUNT(*) FROM IMPORT_RUN_CHECKPOINT WHERE RUN_ID = ?", changed))
                .as("persons and cases only")
                .isEqualTo(2);
    }
}
