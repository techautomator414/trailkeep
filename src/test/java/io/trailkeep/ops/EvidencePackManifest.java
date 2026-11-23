// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The rows of {@code .github/release/evidence-pack.txt}, for the tests of the release evidence pack. */
final class EvidencePackManifest {

    static final Path PATH = Path.of(".github/release/evidence-pack.txt");

    private EvidencePackManifest() {}

    /** One file of the pack: its name ({@code -} for registry evidence), source, first version and requirement. */
    record Row(String file, String source, String since, String requirement) {

        boolean fromArtifact() {
            return source.startsWith("artifact:");
        }

        /** The artifact a row takes, without the path of a single file in it. */
        String artifact() {
            return source.substring("artifact:".length()).split("/", 2)[0];
        }
    }

    static List<Row> rows() {
        List<String> lines;
        try {
            lines = Files.readAllLines(PATH);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Row> rows = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split(" \\| ", -1);
            if (fields.length != 5) {
                throw new IllegalStateException("expected 5 fields: " + line);
            }
            rows.add(new Row(fields[0].strip(), fields[1].strip(), fields[2].strip(), fields[3].strip()));
        }
        return List.copyOf(rows);
    }
}
