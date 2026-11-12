// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The CSV and TSV source (WP-26 part l, design §4.2, §4.3 "Paths", §10): RFC 4180 rows under the declared dialect as
 * lower-case columns, the declared encodings with their BOMs, the manifest proving each file, and failures that carry a
 * code and the line a row starts on, never a value.
 */
class CsvSourceTest {

    private static final String BOM = String.valueOf((char) 0xFEFF);

    private static final String MAPPING = """
            version: 1
            source: { id: legacy1, kind: files, timezone: UTC, manifest: export/manifest.yaml }
            people: { file: export/people.csv, key: user_id, match: [email], unmatched: snapshot }
            entities:
              case: { file: export/cases.csv, key: case_id, fields: {} }
            """;

    private static final String CASES = """
            Case_ID,Subject,Note,Empty
            c1,"Synthetic, with a comma","two
            lines",
            c2,"say ""hi""\",,""
            """;

    @TempDir
    private Path directory;

    private final CsvSource source = new CsvSource();

    @Test
    void readsQuotedRowsUnderTheirHeaderAndFingerprintsTheManifest() throws Exception {
        export("export/cases.csv", StandardCharsets.UTF_8, CASES, 2);

        List<Map<String, @Nullable Object>> rows = read(MAPPING.replace("UTC,", "UTC, csv: { nullToken: \"\" },"));

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst())
                .containsOnlyKeys("case_id", "subject", "note", "empty")
                .containsEntry("case_id", "c1")
                .containsEntry("subject", "Synthetic, with a comma")
                .containsEntry("note", "two\nlines")
                .as("an unquoted empty field is the null token")
                .containsEntry("empty", null);
        assertThat(rows.get(1))
                .containsEntry("subject", "say \"hi\"")
                .containsEntry("note", null)
                .as("a quoted one never is")
                .containsEntry("empty", "");
        assertThat(read(MAPPING).getFirst())
                .as("no null token declared: every field is text")
                .containsEntry("empty", "");
        try (SourceRows open = source.open(mapping(MAPPING), directory)) {
            assertThat(open.fingerprint())
                    .isEqualTo("manifest:"
                            + ValidateMappingCommand.sha256(
                                    Files.readAllBytes(directory.resolve("export/manifest.yaml"))));
        }
    }

    @Test
    void tsvSplitsOnTabsHeaderlessColumnsGoByPositionAndLoneNulIsEmpty() throws Exception {
        String tsv = "c1\tSynthetic tab\t" + (char) 0 + "\r\nc2\t\"quoted\ttab\"\tx\r\n";
        export("export/cases.tsv", StandardCharsets.UTF_8, tsv, 2);

        List<Map<String, @Nullable Object>> rows =
                read(MAPPING.replace("cases.csv", "cases.tsv").replace("UTC,", "UTC, csv: { header: false },"));

        assertThat(rows.getFirst())
                .containsOnlyKeys("column1", "column2", "column3")
                .containsEntry("column2", "Synthetic tab")
                .as("bcp writes an empty string as one 0x00 byte")
                .containsEntry("column3", "");
        assertThat(rows.get(1)).containsEntry("column2", "quoted\ttab");
    }

    @Test
    void declaredDialectIsParsedAndClashingOneIsRefused() throws Exception {
        export("export/cases.csv", StandardCharsets.UTF_8, "id;text\nc1;'a;b'\nc2;\\;x\nc3;\"q\"\n", 3);
        String dialect = "UTC, csv: { delimiter: \";\", quote: \"'\", escape: \"\\\\\" },";

        assertThat(read(MAPPING.replace("UTC,", dialect)))
                .extracting(row -> row.get("text"))
                .containsExactly("a;b", ";x", "\"q\"");
        export("export/cases.csv", StandardCharsets.UTF_8, "id;text\nc1;\"q\"\n", 1);
        assertThat(read(MAPPING.replace("UTC,", "UTC, csv: { delimiter: \";\", quote: \"\" },")))
                .as("no quote character: quotes are text")
                .extracting(row -> row.get("text"))
                .containsExactly("\"q\"");
        for (String clash : List.of("{ delimiter: \"'\", quote: \"'\" }", "{ delimiter: \"\\n\" }")) {
            assertThat(code(MAPPING.replace("UTC,", "UTC, csv: " + clash + ",")))
                    .isEqualTo("CSV_DIALECT_INVALID");
        }
    }

    @Test
    void declaredEncodingsAreDecodedAndLeadingBomConsumed() throws Exception {
        Map<String, Charset> encodings = new HashMap<>(Map.of(
                "UTF-8", StandardCharsets.UTF_8,
                "UTF-16LE", StandardCharsets.UTF_16LE,
                "UTF-16BE", StandardCharsets.UTF_16BE));
        for (Map.Entry<String, Charset> encoding : encodings.entrySet()) {
            export("export/cases.csv", encoding.getValue(), BOM + "id,text\nc1,café\n", 1);

            assertThat(read(MAPPING.replace("UTC,", "UTC, encoding: " + encoding.getKey() + ",")))
                    .as(encoding.getKey())
                    .containsExactly(Map.of("id", "c1", "text", "café"));
        }
        export("export/cases.csv", Charset.forName("windows-1252"), "id,text\nc1,€ café\n", 1);
        assertThat(read(MAPPING.replace("UTC,", "UTC, encoding: windows-1252,")))
                .containsExactly(Map.of("id", "c1", "text", "€ café"));
        export("export/cases.csv", StandardCharsets.ISO_8859_1, "id,text\nc1,café\n", 1);
        assertThat(read(MAPPING.replace("UTC,", "UTC, encoding: ISO-8859-1,")))
                .containsExactly(Map.of("id", "c1", "text", "café"));
    }

    @Test
    void undecodableByteFailsWithItsLine() throws Exception {
        for (String encoding : List.of("UTF-8", "windows-1252")) {
            export("export/cases.csv", StandardCharsets.UTF_8, "id,text\nc1,a\n", 1);
            // 0x81 alone is malformed UTF-8 and unassigned in windows-1252.
            Files.write(
                    directory.resolve("export/cases.csv"),
                    new byte[] {'i', 'd', '\n', 'c', '1', '\n', 'c', (byte) 0x81, '\n'});

            assertThatThrownBy(() -> read(MAPPING.replace("UTC,", "UTC, encoding: " + encoding + ",")))
                    .as(encoding)
                    .isInstanceOfSatisfying(SourceException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("SOURCE_ENCODING");
                        assertThat(failure.detail()).isEqualTo(3L);
                    })
                    .hasNoCause();
        }
    }

    @Test
    void malformedRowFailsWithTheLineItStartsOnAndNoValue() throws Exception {
        Map<String, Long> files = Map.of(
                "id,text\nc1,\"Synthetic\nsecret\"\nc2,Synthetic secret,extra\n", 4L,
                "id,text\nc1,a\nc2\n", 3L,
                "id,text\nc1,\"Synthetic secret\"x\n", 2L,
                "id,text\nc1,\"Synthetic secret\n", 2L,
                "id,text\nc1,a\n\n", 3L,
                "id,ID\nc1,Synthetic secret\n", 1L,
                "id,\nc1,Synthetic secret\n", 1L);
        for (Map.Entry<String, Long> file : files.entrySet()) {
            export("export/cases.csv", StandardCharsets.UTF_8, file.getKey(), 1);

            assertThatThrownBy(() -> read(MAPPING))
                    .as(file.getKey())
                    .isInstanceOfSatisfying(SourceException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("ROW_UNREADABLE");
                        assertThat(failure.detail()).isEqualTo(file.getValue());
                    })
                    .hasMessageNotContaining("Synthetic")
                    .hasNoCause();
        }
    }

    @Test
    void fileThatDiffersFromItsManifestOrLeavesTheDirectoryFails(@TempDir Path elsewhere) throws Exception {
        export("export/cases.csv", StandardCharsets.UTF_8, CASES, 3);
        assertThat(code(MAPPING)).as("a row count off by one").isEqualTo("MANIFEST_MISMATCH");

        export("export/cases.csv", StandardCharsets.UTF_8, CASES, 2);
        Files.writeString(directory.resolve("export/cases.csv"), CASES.replace("c2", "c3"));
        assertThat(code(MAPPING)).as("the same rows, other bytes").isEqualTo("MANIFEST_MISMATCH");

        export("export/cases.csv", StandardCharsets.UTF_8, CASES, 2);
        Path cases = directory.resolve("export/cases.csv");
        Files.delete(cases);
        Files.createSymbolicLink(cases, Files.writeString(elsewhere.resolve("cases.csv"), CASES));
        assertThat(code(MAPPING)).isEqualTo("PATH_OUTSIDE_ROOT");
    }

    @Test
    void supportsFileSourcesWhoseEveryFileIsCsvOrTsv() {
        assertThat(source.supports(mapping(MAPPING))).isTrue();
        assertThat(source.supports(mapping(MAPPING.replace("cases.csv", "cases.TSV"))))
                .isTrue();
        assertThat(source.supports(mapping(MAPPING.replace("cases.csv", "cases.jsonl"))))
                .as("one source per mapping: no CSV beside JSON Lines")
                .isFalse();
        assertThat(source.supports(mapping(MAPPING.replace("kind: files", "kind: jdbc"))))
                .isFalse();
    }

    /** Writes the people file, one case file and a manifest that lists the case file with a row count. */
    private void export(String name, Charset charset, String cases, int caseRows) throws Exception {
        Files.createDirectories(directory.resolve("export"));
        byte[] people = "user_id\nu1\n".getBytes(StandardCharsets.UTF_8);
        byte[] caseBytes = cases.getBytes(charset);
        Files.write(directory.resolve("export/people.csv"), people);
        Files.write(directory.resolve(name), caseBytes);
        Files.writeString(directory.resolve("export/manifest.yaml"), """
                version: 1
                fixedPoint: { kind: snapshot, value: synthetic-snapshot-1 }
                files:
                  - { file: export/people.csv, entity: person, rows: 1, sha256: %s }
                  - { file: %s, entity: case, rows: %d, sha256: %s }
                """.formatted(
                ValidateMappingCommand.sha256(people), name, caseRows, ValidateMappingCommand.sha256(caseBytes)));
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
