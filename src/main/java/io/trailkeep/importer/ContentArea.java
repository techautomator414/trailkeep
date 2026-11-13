// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A run's content area (WP-26 part n2, design §4.3): {@code <import.content-area>/<run id>/}, on the Job's dedicated
 * writable volume, holding each attachment a content query fetched at extract as one file named by its SHA-256, so the
 * load reads the bytes the dry run validated without opening a source connection. It holds legacy content: files are
 * created owner-only, and the folder goes with the run's staging ({@code purge-staging}, the staging TTL, an extract
 * that starts over). Paths never reach an issue or a log line.
 */
final class ContentArea {

    /** A file's name in the area: its SHA-256, lower-case hex. */
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final Path directory;

    private ContentArea(Path directory) {
        this.directory = directory;
    }

    /**
     * A run's content area; its folder is made by the first write, so an extract that starts over can empty it first.
     *
     * @param root {@code import.content-area}; {@code null} when the deployment has none
     * @param runId the run
     * @return its content area
     * @throws SourceException {@code CONTENT_AREA_UNWRITABLE} without a root, or when the root is not a folder
     */
    static ContentArea open(@Nullable Path root, long runId) {
        try {
            Path real = root == null ? null : root.toRealPath();
            if (real == null || !Files.isDirectory(real)) {
                throw new IOException("import.content-area is not a folder");
            }
            return new ContentArea(real.resolve(Long.toString(runId)));
        } catch (IOException unwritable) {
            throw new SourceException("CONTENT_AREA_UNWRITABLE", null);
        }
    }

    /**
     * Stores one attachment's bytes under their SHA-256: written to an owner-only temporary file, then moved into
     * place, so a crash never leaves a partial file under a hash name.
     *
     * @param bytes the content
     * @return its SHA-256, lower-case hex, which is also its name in the area
     * @throws SourceException {@code CONTENT_AREA_UNWRITABLE} if it cannot be written
     */
    String write(byte[] bytes) {
        String sha256 = ValidateMappingCommand.sha256(bytes);
        try {
            Path part = Files.createTempFile(Files.createDirectories(directory), "part", ".tmp");
            Files.write(part, bytes);
            Files.move(part, directory.resolve(sha256), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unwritable) {
            throw new SourceException("CONTENT_AREA_UNWRITABLE", null);
        }
        return sha256;
    }

    /**
     * The file the load reads for an attachment the extract stored here (WP-26 part u): named by its SHA-256 alone,
     * and contained in the area as every file the mapping names is ({@link FileManifest#open}).
     *
     * @param sha256 the staged content pointer
     * @return the file, as a real path
     * @throws SourceException {@code PATH_OUTSIDE_ROOT} for a pointer that is not a SHA-256, {@code
     *     SOURCE_FILE_MISSING} when the file or the area is gone
     */
    Path file(String sha256) {
        if (!SHA256.matcher(sha256).matches()) {
            throw new SourceException("PATH_OUTSIDE_ROOT", null);
        }
        try {
            return FileManifest.open(directory.toRealPath(), sha256);
        } catch (IOException gone) {
            throw new SourceException("SOURCE_FILE_MISSING", null);
        }
    }

    /**
     * Deletes a run's content area, if there is one.
     *
     * @param root {@code import.content-area}; {@code null} when the deployment has none
     * @param runId the run
     * @throws UncheckedIOException if a file cannot be deleted
     */
    static void delete(@Nullable Path root, long runId) {
        Path directory = root == null ? null : root.resolve(Long.toString(runId));
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        // Symlinks are not followed: a link is deleted, never what it points at.
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete the content area of import run " + runId, e);
        }
    }
}
