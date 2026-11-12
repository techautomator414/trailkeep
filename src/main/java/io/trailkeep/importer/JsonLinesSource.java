// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import com.networknt.schema.Schema;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON Lines files source (WP-26 part k2, design §4.2): a {@code kind: files} mapping whose every entity file ends
 * in {@code .jsonl}, each line one flat JSON object, read in {@code source.encoding} (UTF-8 by default; a leading BOM
 * is consumed). The manifest is required and fixes the point every file was exported at (§4.2.1); each file is hashed
 * and its rows counted while it is read, and a file that differs from its manifest entry fails the extract before its
 * entity is checkpointed. Files are opened by their real path under the mapping's directory, so a symlink cannot lead
 * a read outside it. A line that is not one flat object is a {@code ROW_UNREADABLE} carrying its line number only.
 */
@Component
@Profile("importer")
final class JsonLinesSource implements SourceRows.Factory {

    private final Schema manifestSchema = FileManifest.schema();

    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    @Override
    public boolean supports(JsonNode mapping) {
        List<JsonNode> specs = new ArrayList<>(List.of(mapping.path("people")));
        mapping.path("entities").forEach(specs::add);
        return "files".equals(mapping.at("/source/kind").asString(""))
                && specs.stream()
                        .allMatch(spec -> spec.path("file")
                                .asString("")
                                .toLowerCase(Locale.ROOT)
                                .endsWith(".jsonl"));
    }

    @Override
    public SourceRows open(JsonNode mapping, Path directory) {
        Path root;
        try {
            root = directory.toRealPath();
        } catch (IOException unreadable) {
            throw new SourceException("SOURCE_FILE_UNREADABLE", null);
        }
        FileManifest manifest = FileManifest.read(root, mapping, manifestSchema);
        Charset charset = Charset.forName(mapping.at("/source/encoding").asString("UTF-8"));
        return new OpenFiles(root, manifest, charset);
    }

    /** The files of one extract, against one manifest. */
    private final class OpenFiles implements SourceRows {

        private final Path root;
        private final FileManifest manifest;
        private final Charset charset;

        OpenFiles(Path root, FileManifest manifest, Charset charset) {
            this.root = root;
            this.manifest = manifest;
            this.charset = charset;
        }

        @Override
        public String fingerprint() {
            return manifest.fingerprint();
        }

        @Override
        public void read(StagedEntity entity, JsonNode spec, Consumer<Map<String, @Nullable Object>> rows) {
            String name = spec.path("file").asString();
            FileManifest.Entry expected = manifest.entry(name, entity);
            MessageDigest sha256 = sha256();
            long line = 0;
            long count = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new DigestInputStream(
                            Files.newInputStream(FileManifest.open(root, name), LinkOption.NOFOLLOW_LINKS), sha256),
                    charset.newDecoder()))) {
                for (String text = reader.readLine(); text != null; text = reader.readLine()) {
                    line++;
                    String object = line == 1 && text.startsWith("\uFEFF") ? text.substring(1) : text;
                    if (!object.isBlank()) {
                        count++;
                        rows.accept(row(object, line));
                    }
                }
            } catch (CharacterCodingException undecodable) {
                throw new SourceException("SOURCE_ENCODING", CsvSource.undecodableLine(root, name, charset));
            } catch (IOException unreadable) {
                throw new SourceException("SOURCE_FILE_UNREADABLE", null);
            }
            if (count != expected.rows()
                    || !HexFormat.of().formatHex(sha256.digest()).equals(expected.sha256())) {
                throw new SourceException("MANIFEST_MISMATCH", null);
            }
        }

        @Override
        public FileManifest.@Nullable Content content(String contentId) {
            return manifest.contents().get(contentId);
        }

        @Override
        public void close() {
            // Each file is closed when its entity has been read.
        }
    }

    /** One line as column name in lower case to a string, a decimal, a boolean or null. */
    private Map<String, @Nullable Object> row(String text, long line) {
        JsonNode object;
        try {
            object = json.readTree(text);
        } catch (JacksonException unreadable) {
            // Parser messages quote the line; only its number is kept (§10).
            throw new SourceException("ROW_UNREADABLE", line);
        }
        if (object == null || !object.isObject()) {
            throw new SourceException("ROW_UNREADABLE", line);
        }
        Map<String, @Nullable Object> row = new HashMap<>();
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            JsonNode value = property.getValue();
            if (value.isContainer() || row.containsKey(property.getKey().toLowerCase(Locale.ROOT))) {
                throw new SourceException("ROW_UNREADABLE", line);
            }
            row.put(
                    property.getKey().toLowerCase(Locale.ROOT),
                    value.isNull()
                            ? null
                            : value.isNumber()
                                    ? value.decimalValue()
                                    : value.isBoolean() ? value.asBoolean() : value.asString());
        }
        return row;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }
}
