// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A synthetic JSON Lines export for {@link AbstractExtractIT} (WP-26 part k2): two people, one case with a BOM and an
 * integral key, one comment that names its case as {@code 100.0}, one attachment whose file lies under {@code files/},
 * and the manifest that proves them.
 */
final class JsonLinesExport {

    /** The attachment file's bytes, under {@code files/a/scan.pdf}. */
    static final byte[] SCAN = "synthetic scan".getBytes(StandardCharsets.UTF_8);

    private JsonLinesExport() {}

    /**
     * Writes the export into a folder.
     *
     * @param directory the mapping's folder
     * @return the manifest's bytes
     * @throws Exception if a file cannot be written
     */
    static byte[] write(Path directory) throws Exception {
        List<List<String>> files = List.of(
                List.of("people.jsonl", "person", """
                        {"USER_ID": "u1", "name": "Synthetic One", "mail": "one@example.test"}
                        {"user_id": "u2", "name": "Synthetic Two", "mail": null}
                        """),
                List.of(
                        "cases.jsonl",
                        "case",
                        "\uFEFF{\"case_id\": 100, \"subject\": \"Synthetic case\", \"details\": \"Synthetic details\","
                                + " \"state\": \"Open\", \"opened\": \"2019-03-01T09:30:00\","
                                + " \"found_on\": \"2019-02-28\", \"owner\": \"u1\"}\n"),
                List.of(
                        "notes.jsonl",
                        "comment",
                        "{\"note_id\": \"n1\", \"case_id\": 100.0, \"author\": \"u2\","
                                + " \"created\": \"2019-03-02T10:00:00\", \"body\": \"Synthetic note\"}\n"),
                List.of("files.jsonl", "attachment", """
                        {"file_id": "f1", "case_id": 100, "name": "scan.pdf", "path": "a/scan.pdf", "bytes": 2048}
                        """));
        StringBuilder manifest =
                new StringBuilder("version: 1\nfixedPoint: { kind: freeze, value: synthetic }\nfiles:\n");
        for (List<String> file : files) {
            byte[] bytes = file.get(2).getBytes(StandardCharsets.UTF_8);
            Files.write(directory.resolve(file.get(0)), bytes);
            manifest.append("  - { file: %s, entity: %s, rows: %d, sha256: %s }\n"
                    .formatted(
                            file.get(0),
                            file.get(1),
                            file.get(2).lines().count(),
                            ValidateMappingCommand.sha256(bytes)));
        }
        Files.createDirectories(directory.resolve("files/a"));
        Files.write(directory.resolve("files/a/scan.pdf"), SCAN);
        byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("manifest.yaml"), bytes);
        return bytes;
    }
}
