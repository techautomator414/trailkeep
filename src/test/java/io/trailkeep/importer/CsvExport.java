// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The synthetic export of {@link JsonLinesExport} as CSV files with headers (WP-26 part l), as PostgreSQL's {@code
 * \copy ... (FORMAT csv, HEADER)} writes them: a null is an unquoted empty field, so the mapping declares {@code
 * nullToken: ""}. The case file starts with a BOM. Extracted, it stages the same canonical rows as the JSON Lines
 * export (design §4.2, §11).
 */
final class CsvExport {

    /** What the mapping's {@code source} declares for these files. */
    static final String DIALECT = "csv: { nullToken: \"\" }";

    private CsvExport() {}

    /**
     * Writes the export into a folder.
     *
     * @param directory the mapping's folder
     * @return the manifest's bytes
     * @throws Exception if a file cannot be written
     */
    static byte[] write(Path directory) throws Exception {
        List<List<String>> files = List.of(
                List.of("people.csv", "person", """
                        USER_ID,name,mail
                        u1,Synthetic One,one@example.test
                        u2,"Synthetic Two",
                        """),
                List.of(
                        "cases.csv",
                        "case",
                        (char) 0xFEFF
                                + "case_id,subject,details,state,opened,found_on,owner\n"
                                + "100,Synthetic case,Synthetic details,Open,2019-03-01T09:30:00,2019-02-28,u1\n"),
                List.of("notes.csv", "comment", """
                        note_id,case_id,author,created,body
                        n1,100,u2,2019-03-02T10:00:00,Synthetic note
                        """),
                List.of("files.csv", "attachment", """
                        file_id,case_id,name,path,bytes
                        f1,100,scan.pdf,a/scan.pdf,2048
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
                            file.get(2).lines().count() - 1,
                            ValidateMappingCommand.sha256(bytes)));
        }
        Files.createDirectories(directory.resolve("files/a"));
        Files.write(directory.resolve("files/a/scan.pdf"), JsonLinesExport.SCAN);
        byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("manifest.yaml"), bytes);
        return bytes;
    }
}
