// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The manifest of a files source (WP-26 part k2, design §4.2, §4.2.1): each exported file with its entity, row count
 * and SHA-256, and the fixed point the export was taken at, so the extract can prove every file complete and
 * unchanged. Its own SHA-256 is the run's source fingerprint: a restart on the same manifest resumes, on another it
 * starts over. Checked against {@code importer/manifest-v1.schema.json}.
 *
 * @param fingerprint {@code manifest:} and the manifest's SHA-256
 * @param files each listed file by its normalised name
 * @param contents each attachment content id with its file under {@code attachments.root} (§4.3)
 */
record FileManifest(String fingerprint, Map<String, Entry> files, Map<String, Content> contents) {

    /** A manifest is configuration, like the mapping: anything larger is refused before it is parsed. */
    static final int MAX_BYTES = MappingValidator.MAX_BYTES;

    /**
     * One exported file as the manifest lists it.
     *
     * @param entity the entity it holds, in lower case
     * @param rows how many rows it holds
     * @param sha256 its SHA-256, lower-case hex
     */
    record Entry(String entity, long rows, String sha256) {}

    /**
     * The file an attachment content id names.
     *
     * @param file its name under {@code attachments.root}
     * @param sha256 its SHA-256, lower-case hex
     */
    record Content(String file, String sha256) {}

    /**
     * The manifest's JSON Schema, {@code importer/manifest-v1.schema.json}.
     *
     * @return it
     */
    static Schema schema() {
        try (InputStream in = new ClassPathResource("importer/manifest-v1.schema.json").getInputStream()) {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("The manifest schema is missing", e);
        }
    }

    /**
     * Reads and checks the manifest a mapping names.
     *
     * @param root the mapping file's directory, as a real path
     * @param mapping the validated mapping
     * @param schema the manifest schema
     * @return the manifest
     * @throws SourceException {@code MANIFEST_MISSING}, {@code MANIFEST_INVALID}, or a file code of {@link #open}
     */
    static FileManifest read(Path root, JsonNode mapping, Schema schema) {
        JsonNode name = mapping.at("/source/manifest");
        if (!name.isString()) {
            throw new SourceException("MANIFEST_MISSING", null);
        }
        byte[] content;
        try (InputStream in = Files.newInputStream(open(root, name.asString()), LinkOption.NOFOLLOW_LINKS)) {
            content = in.readNBytes(MAX_BYTES + 1);
        } catch (IOException unreadable) {
            throw new SourceException("SOURCE_FILE_UNREADABLE", null);
        }
        JsonNode manifest = parsed(content, schema);
        return new FileManifest(
                "manifest:" + ValidateMappingCommand.sha256(content),
                unique(
                        manifest.path("files"),
                        file -> normalised(file.path("file").asString()),
                        file -> new Entry(
                                file.path("entity").asString(),
                                file.path("rows").asLong(),
                                file.path("sha256").asString())),
                unique(
                        manifest.path("contents"),
                        id -> id.path("id").asString(),
                        id -> new Content(
                                id.path("file").asString(), id.path("sha256").asString())));
    }

    private static JsonNode parsed(byte[] content, Schema schema) {
        JsonNode manifest;
        try {
            manifest = content.length > MAX_BYTES
                    ? null
                    : YAMLMapper.builder().build().readTree(content);
        } catch (JacksonException unreadable) {
            // Parser messages quote the file; only the fact is kept (§10).
            manifest = null;
        }
        if (manifest == null
                || !manifest.isObject()
                || !schema.validate(manifest).isEmpty()) {
            throw new SourceException("MANIFEST_INVALID", null);
        }
        return manifest;
    }

    /** A manifest list by key; a key listed twice makes the manifest invalid. */
    private static <T> Map<String, T> unique(
            JsonNode list, Function<JsonNode, String> key, Function<JsonNode, T> value) {
        Map<String, T> unique = new HashMap<>();
        for (JsonNode item : list) {
            if (unique.put(key.apply(item), value.apply(item)) != null) {
                throw new SourceException("MANIFEST_INVALID", null);
            }
        }
        return Map.copyOf(unique);
    }

    /**
     * The manifest's entry for a file the mapping names for an entity.
     *
     * @param file the mapping's file name
     * @param entity the entity the mapping reads from it
     * @return the entry
     * @throws SourceException {@code MANIFEST_MISMATCH} if the manifest does not list it for that entity
     */
    Entry entry(String file, StagedEntity entity) {
        Entry entry = files.get(normalised(file));
        if (entry == null || !entry.entity().equals(name(entity))) {
            throw new SourceException("MANIFEST_MISMATCH", null);
        }
        return entry;
    }

    /**
     * An entity's name in the manifest and under the mapping's {@code entities}.
     *
     * @param entity the entity
     * @return its name, in lower case
     */
    static String name(StagedEntity entity) {
        return switch (entity) {
            case PERSON -> "person";
            case CASE -> "case";
            case COMMENT -> "comment";
            case ATTACHMENT -> "attachment";
            case EVENT -> "event";
        };
    }

    /**
     * Resolves a relative name under a root at the moment it is opened (design §4.3 "Paths"): the real path, symlinks
     * followed, must still lie under the root, which {@code validate-mapping}'s check of the names alone cannot prove.
     *
     * @param root the root, as a real path
     * @param relative the name
     * @return the file's real path, free of symlinks, to open with {@link LinkOption#NOFOLLOW_LINKS}
     * @throws SourceException {@code SOURCE_FILE_MISSING}, {@code SOURCE_FILE_UNREADABLE} or {@code PATH_OUTSIDE_ROOT}
     */
    static Path open(Path root, String relative) {
        Path real;
        try {
            real = root.resolve(relative).toRealPath();
        } catch (NoSuchFileException missing) {
            throw new SourceException("SOURCE_FILE_MISSING", null);
        } catch (IOException | InvalidPathException unreadable) {
            throw new SourceException("SOURCE_FILE_UNREADABLE", null);
        }
        if (!real.startsWith(root)) {
            throw new SourceException("PATH_OUTSIDE_ROOT", null);
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new SourceException("SOURCE_FILE_MISSING", null);
        }
        return real;
    }

    private static String normalised(String file) {
        try {
            return Path.of(file).normalize().toString();
        } catch (InvalidPathException malformed) {
            throw new SourceException("MANIFEST_INVALID", null);
        }
    }
}
