// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The JSON Lines source and its manifest (WP-26 part k2, design §4.2, §4.3 "Paths", §10): rows as lower-case columns
 * with exact decimals, the manifest proving each file, reads that never leave the mapping's directory, and failures
 * that carry a code and a line number, never a value.
 */
class JsonLinesSourceTest {

    private static final String MAPPING = """
            version: 1
            source: { id: legacy1, kind: files, timezone: UTC, manifest: export/manifest.yaml }
            people: { file: export/people.jsonl, key: user_id, match: [email], unmatched: snapshot }
            entities:
              case: { file: export/cases.jsonl, key: case_id, fields: {} }
            """;

    private static final String CASES = """
            {"Case_ID": 7, "amount": 1.50, "huge": 12345678901234567890.25, "open": true, "note": null, "t": "x"}

            {"case_id": "c8", "note": "line \\u2028 kept"}
            """;

    @TempDir
    private Path directory;

    private final JsonLinesSource source = new JsonLinesSource();

    @Test
    void readsEachLineAsLowerCaseColumnsWithExactDecimalsAndFingerprintsTheManifest() throws Exception {
        export(StandardCharsets.UTF_8, "\uFEFF" + CASES, 2);

        List<Map<String, @Nullable Object>> rows = read(MAPPING);

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst())
                .containsEntry("case_id", new BigDecimal("7"))
                .containsEntry("amount", new BigDecimal("1.50"))
                .containsEntry("huge", new BigDecimal("12345678901234567890.25"))
                .containsEntry("open", true)
                .containsEntry("note", null)
                .containsEntry("t", "x");
        assertThat(rows.get(1)).containsEntry("note", "line \u2028 kept");
        try (SourceRows open = source.open(mapping(MAPPING), directory)) {
            assertThat(open.fingerprint())
                    .isEqualTo("manifest:"
                            + ValidateMappingCommand.sha256(
                                    Files.readAllBytes(directory.resolve("export/manifest.yaml"))));
        }
    }

    @Test
    void declaredUtf16IsReadAndItsBomConsumed() throws Exception {
        export(StandardCharsets.UTF_16LE, "\uFEFF" + CASES, 2);

        assertThat(read(MAPPING.replace("timezone: UTC", "timezone: UTC, encoding: UTF-16LE")))
                .extracting(row -> row.get("case_id"))
                .containsExactly(new BigDecimal("7"), "c8");
    }

    @Test
    void lineThatIsNotOneFlatObjectFailsWithItsLineNumberAndNoValue() throws Exception {
        for (String line : List.of(
                "{\"case_id\": 1, \"nested\": {\"secret\": \"Synthetic secret\"}}",
                "{\"case_id\": 1, \"list\": [\"Synthetic secret\"]}",
                "{\"case_id\": 1, \"Secret\": \"a\", \"SECRET\": \"Synthetic secret\"}",
                "{\"case_id\": 1} {\"secret\": \"Synthetic secret\"}",
                "[\"Synthetic secret\"]",
                "{\"case_id\": \"Synthetic secret")) {
            export(StandardCharsets.UTF_8, "{\"case_id\": 0}\n" + line + "\n", 2);

            assertThatThrownBy(() -> read(MAPPING))
                    .isInstanceOfSatisfying(SourceException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("ROW_UNREADABLE");
                        assertThat(failure.detail()).isEqualTo(2L);
                    })
                    .hasMessageNotContaining("Synthetic")
                    .hasNoCause();
        }
        Files.write(directory.resolve("export/cases.jsonl"), new byte[] {'{', '}', '\n', (byte) 0xC3, '\n'});
        assertThatThrownBy(() -> read(MAPPING))
                .isInstanceOfSatisfying(
                        SourceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("SOURCE_ENCODING"));
    }

    @Test
    void fileThatDiffersFromItsManifestEntryFails() throws Exception {
        export(StandardCharsets.UTF_8, CASES, 3);
        assertThat(code(MAPPING)).as("a row count off by one").isEqualTo("MANIFEST_MISMATCH");

        export(StandardCharsets.UTF_8, CASES, 2);
        Files.writeString(directory.resolve("export/cases.jsonl"), CASES.replace("c8", "c9"));
        assertThat(code(MAPPING)).as("the same rows, other bytes").isEqualTo("MANIFEST_MISMATCH");

        export(StandardCharsets.UTF_8, CASES, 2);
        assertThat(code(MAPPING.replace("export/cases.jsonl", "export/./people.jsonl")))
                .as("listed for another entity")
                .isEqualTo("MANIFEST_MISMATCH");
        Files.writeString(directory.resolve("export/notes.jsonl"), "");
        assertThat(code(MAPPING.replace("export/cases.jsonl", "export/notes.jsonl")))
                .as("not listed")
                .isEqualTo("MANIFEST_MISMATCH");
        assertThat(code(MAPPING.replace(", manifest: export/manifest.yaml", "")))
                .isEqualTo("MANIFEST_MISSING");
        Files.writeString(directory.resolve("export/manifest.yaml"), "version: 1\nfiles: []\n");
        assertThat(code(MAPPING)).isEqualTo("MANIFEST_INVALID");
        Files.writeString(directory.resolve("export/manifest.yaml"), "- not\n- an object\n");
        assertThat(code(MAPPING)).isEqualTo("MANIFEST_INVALID");
        Files.delete(directory.resolve("export/manifest.yaml"));
        assertThat(code(MAPPING)).isEqualTo("SOURCE_FILE_MISSING");
    }

    @Test
    void symlinkLeavingTheMappingDirectoryIsRefusedWhenTheFileIsOpened(@TempDir Path elsewhere) throws Exception {
        export(StandardCharsets.UTF_8, CASES, 2);
        Path outside = Files.writeString(elsewhere.resolve("cases.jsonl"), CASES);
        Path cases = directory.resolve("export/cases.jsonl");
        Files.delete(cases);
        Files.createSymbolicLink(cases, outside);

        assertThat(code(MAPPING)).isEqualTo("PATH_OUTSIDE_ROOT");
        assertThat(code(MAPPING.replace("export/cases.jsonl", "../escape.jsonl")))
                .as("validate-mapping refuses the name; the read refuses it again")
                .isEqualTo("MANIFEST_MISMATCH");
        assertThat(FileManifest.open(directory.toRealPath(), "export/people.jsonl"))
                .isEqualTo(directory.toRealPath().resolve("export/people.jsonl"));
        assertThatThrownBy(() -> FileManifest.open(directory.toRealPath(), "../" + elsewhere.getFileName()))
                .hasMessage("PATH_OUTSIDE_ROOT");
        assertThatThrownBy(() -> FileManifest.open(directory.toRealPath(), "export"))
                .as("a directory is not a file")
                .hasMessage("SOURCE_FILE_MISSING");
    }

    @Test
    void supportsFileSourcesWhoseEveryFileIsJsonLines() {
        assertThat(source.supports(mapping(MAPPING))).isTrue();
        assertThat(source.supports(mapping(MAPPING.replace("cases.jsonl", "cases.csv"))))
                .isFalse();
        assertThat(source.supports(mapping(MAPPING.replace("file: export/cases.jsonl", "query: SELECT 1"))))
                .isFalse();
        assertThat(source.supports(mapping(MAPPING.replace("kind: files", "kind: jdbc"))))
                .isFalse();
    }

    /** Writes the people and case files and a manifest that lists the case file with a row count. */
    private void export(Charset charset, String cases, int caseRows) throws Exception {
        Files.createDirectories(directory.resolve("export"));
        byte[] people = "{\"user_id\": \"u1\"}\n".getBytes(StandardCharsets.UTF_8);
        byte[] caseBytes = cases.getBytes(charset);
        Files.write(directory.resolve("export/people.jsonl"), people);
        Files.write(directory.resolve("export/cases.jsonl"), caseBytes);
        Files.writeString(directory.resolve("export/manifest.yaml"), """
                version: 1
                fixedPoint: { kind: snapshot, value: synthetic-snapshot-1 }
                files:
                  - { file: export/people.jsonl, entity: person, rows: 1, sha256: %s }
                  - { file: export/cases.jsonl, entity: case, rows: %d, sha256: %s }
                """.formatted(
                        ValidateMappingCommand.sha256(people), caseRows, ValidateMappingCommand.sha256(caseBytes)));
    }

    private List<Map<String, @Nullable Object>> read(String mapping) {
        JsonNode parsed = mapping(mapping);
        List<Map<String, @Nullable Object>> rows = new ArrayList<>();
        try (SourceRows open = source.open(parsed, directory)) {
            open.read(StagedEntity.CASE, parsed.at("/entities/case"), rows::add);
        }
        return rows;
    }

    private String code(String mapping) {
        try {
            read(mapping);
            return "";
        } catch (SourceException failure) {
            return failure.code();
        }
    }

    private static JsonNode mapping(String text) {
        return YAMLMapper.builder().build().readTree(text);
    }
}
