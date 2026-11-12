// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.MappingProblem.Code;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code validate-mapping}'s check of a files source (WP-26 part n2, design §3 step 1): the manifest and every mapped
 * file are checked before any extract, with the codes the extract records, each at the place that names the file.
 */
class FileSourceCheckTest {

    private static final String MAPPING = """
            source: { kind: files, manifest: manifest.yaml }
            people: { file: people.jsonl }
            entities:
              case: { file: cases.jsonl }
              comment: { file: notes.jsonl }
              attachment: { file: files.jsonl }
            attachments: { root: "%s" }
            """;

    @TempDir
    private Path directory;

    @BeforeEach
    void export() throws Exception {
        JsonLinesExport.write(directory);
    }

    @Test
    void soundExportPassesAndJdbcSourcesAreNotItsBusiness() throws Exception {
        assertThat(check(MAPPING.formatted(directory.resolve("files")))).isEmpty();
        assertThat(check("source: { kind: jdbc, vendor: postgresql }")).isEmpty();
    }

    @Test
    void eachFileIsCheckedAgainstItsManifestEntryAtItsPlace() throws Exception {
        Files.writeString(directory.resolve("notes.jsonl"), "{\"note_id\": \"n2\"}\n");
        Files.delete(directory.resolve("people.jsonl"));
        Path outside = Files.createTempFile("outside", ".jsonl");
        try {
            Files.delete(directory.resolve("files.jsonl"));
            Files.createSymbolicLink(directory.resolve("files.jsonl"), outside);

            assertThat(check(MAPPING.formatted(directory.resolve("unmounted"))
                            .replace("case: { file: cases.jsonl }", "case: { file: files.jsonl }")))
                    .containsExactly(
                            new MappingProblem(Code.ATTACHMENTS_ROOT_UNREADABLE, "/attachments/root"),
                            new MappingProblem(Code.PATH_OUTSIDE_ROOT, "/entities/attachment/file"),
                            new MappingProblem(Code.MANIFEST_MISMATCH, "/entities/case/file"),
                            new MappingProblem(Code.MANIFEST_MISMATCH, "/entities/comment/file"),
                            new MappingProblem(Code.SOURCE_FILE_MISSING, "/people/file"));
        } finally {
            Files.delete(outside);
        }
    }

    @Test
    void csvDialectThatTheParserRefusesIsOneProblemAtTheDialect() throws Exception {
        assertThat(check(MAPPING.formatted(directory.resolve("files"))
                        .replace("manifest.yaml }", "manifest.yaml, csv: { delimiter: \"\\n\" } }")
                        .replace("cases.jsonl", "cases.csv")))
                .containsExactly(
                        new MappingProblem(Code.MANIFEST_MISMATCH, "/entities/case/file"),
                        new MappingProblem(Code.CSV_DIALECT_INVALID, "/source/csv"));
    }

    @Test
    void missingOrBrokenManifestIsOneProblemAtTheManifest() throws Exception {
        String mapping = MAPPING.formatted(directory.resolve("files"));
        Files.writeString(directory.resolve("manifest.yaml"), "version: 2\n");
        assertThat(check(mapping)).containsExactly(new MappingProblem(Code.MANIFEST_INVALID, "/source/manifest"));

        Files.delete(directory.resolve("manifest.yaml"));
        assertThat(check(mapping)).containsExactly(new MappingProblem(Code.SOURCE_FILE_MISSING, "/source/manifest"));
    }

    private List<MappingProblem> check(String mapping) throws Exception {
        Path file = Files.writeString(directory.resolve("mapping.yaml"), mapping);
        return FileSourceCheck.check(file, mapping.getBytes(StandardCharsets.UTF_8));
    }
}
