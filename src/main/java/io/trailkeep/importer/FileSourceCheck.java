// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.MappingProblem.Code;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@code validate-mapping}'s check of a files source (WP-26 part n2, design §3 step 1 "files and manifest checked"):
 * the manifest must be readable and pass its schema; every mapped file must be listed for its entity, lie under the
 * mapping file's directory once symlinks are followed, and match the manifest's SHA-256; {@code attachments.root} must
 * be a readable folder. The codes are the ones the extract records, which checks the same again, row counts included,
 * as it reads; each problem sits at the place in the mapping that names the file. Runs only on a structurally sound
 * mapping.
 */
final class FileSourceCheck {

    private FileSourceCheck() {}

    /**
     * Checks a files source's manifest and files.
     *
     * @param file the mapping file
     * @param content its bytes
     * @return the problems, by place; empty for a JDBC source or when every file matches
     */
    static List<MappingProblem> check(Path file, byte[] content) {
        JsonNode mapping = YAMLMapper.builder().build().readTree(content);
        if (!"files".equals(mapping.at("/source/kind").asString(""))) {
            return List.of();
        }
        Set<MappingProblem> problems = new TreeSet<>();
        JsonNode root = mapping.at("/attachments/root");
        boolean readable;
        try {
            readable = !root.isString()
                    || Files.isDirectory(Path.of(root.asString()).toRealPath());
        } catch (IOException | InvalidPathException unreadable) {
            readable = false;
        }
        if (!readable) {
            problems.add(new MappingProblem(Code.ATTACHMENTS_ROOT_UNREADABLE, "/attachments/root"));
        }
        try {
            Path directory = Objects.requireNonNull(file.toAbsolutePath().getParent(), "a file has a directory")
                    .toRealPath();
            FileManifest manifest = FileManifest.read(directory, mapping, FileManifest.schema());
            for (StagedEntity entity : StagedEntity.values()) {
                String pointer = entity == StagedEntity.PERSON
                        ? "/people/file"
                        : "/entities/" + FileManifest.name(entity) + "/file";
                if (mapping.at(pointer).isString()) {
                    checkFile(directory, manifest, mapping.at(pointer).asString(), entity)
                            .ifPresent(code -> problems.add(new MappingProblem(code, pointer)));
                }
            }
        } catch (IOException unreadable) {
            problems.add(new MappingProblem(Code.SOURCE_FILE_UNREADABLE, "/source/manifest"));
        } catch (SourceException refused) {
            problems.add(new MappingProblem(Code.valueOf(refused.code()), "/source/manifest"));
        }
        return List.copyOf(problems);
    }

    /** One mapped file against its manifest entry: listed for its entity, contained, unchanged. */
    private static Optional<Code> checkFile(Path directory, FileManifest manifest, String name, StagedEntity entity) {
        try {
            String expected = manifest.entry(name, entity).sha256();
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(
                    Files.newInputStream(FileManifest.open(directory, name), LinkOption.NOFOLLOW_LINKS), sha256)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return expected.equals(HexFormat.of().formatHex(sha256.digest()))
                    ? Optional.empty()
                    : Optional.of(Code.MANIFEST_MISMATCH);
        } catch (SourceException refused) {
            return Optional.of(Code.valueOf(refused.code()));
        } catch (IOException unreadable) {
            return Optional.of(Code.SOURCE_FILE_UNREADABLE);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }
}
