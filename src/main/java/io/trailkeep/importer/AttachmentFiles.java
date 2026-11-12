// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.FieldTransformer.Issue;
import io.trailkeep.importer.ImportIssue.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Attachments read by path or content id, hashed in place at extract (WP-26 part k2, design §4.3): the file stays in
 * the mounted folder, and its size and SHA-256 are staged so the load can prove it loads the bytes the dry run saw. A
 * legacy path loses {@code attachments.stripPrefix} and its backslashes; a content id is looked up in the manifest,
 * whose SHA-256 the file must match. Absolute paths and {@code ..} segments are refused, and the real path, symlinks
 * followed, must lie under the root's when the file is opened. A file over {@code import.attachments.max-bytes} keeps
 * its size and is never read; the dry run reports it ({@code ATTACHMENT_TOO_LARGE}, before any scan). A content query
 * (26n2) is run by the source in its own snapshot with the attachment's key as its bind variables, unless the mapped
 * size is already over the cap, and its one value is written to the run's {@link ContentArea}; so are a files
 * source's inline bytes ({@code content: { binary: hex | base64, column }}, 26l2), the cap checked before writing.
 * Every problem is an issue code, never a path or a value.
 */
final class AttachmentFiles {

    private final JsonNode attachment;
    private final JsonNode content;
    private final @Nullable Path root;
    private final @Nullable ContentArea area;
    private final String stripPrefix;
    private final long maxBytes;
    private final SourceRows source;

    private AttachmentFiles(
            JsonNode attachment,
            @Nullable Path root,
            @Nullable ContentArea area,
            String stripPrefix,
            long maxBytes,
            SourceRows source) {
        this.attachment = attachment;
        this.content = attachment.at("/fields/content");
        this.root = root;
        this.area = area;
        this.stripPrefix = stripPrefix;
        this.maxBytes = maxBytes;
        this.source = source;
    }

    /**
     * The attachment files of one extract.
     *
     * @param mapping the validated mapping
     * @param source the open source, which resolves content ids
     * @param maxBytes {@code import.attachments.max-bytes}
     * @param contentArea {@code import.content-area}, needed by a content query or inline bytes
     * @param runId the run, whose content area they write
     * @return them
     * @throws SourceException {@code ATTACHMENTS_ROOT_UNREADABLE} if {@code attachments.root} is not a readable folder,
     *     {@code CONTENT_AREA_UNWRITABLE} if a content query or inline bytes have no content area to write
     */
    static AttachmentFiles of(
            JsonNode mapping, SourceRows source, long maxBytes, @Nullable Path contentArea, long runId) {
        JsonNode root = mapping.at("/attachments/root");
        Path real = null;
        if (root.isString()) {
            try {
                real = Path.of(root.asString()).toRealPath();
            } catch (IOException | InvalidPathException unreadable) {
                throw new SourceException("ATTACHMENTS_ROOT_UNREADABLE", null);
            }
        }
        JsonNode attachment = mapping.at("/entities/attachment");
        return new AttachmentFiles(
                attachment,
                real,
                attachment.at("/fields/content").has("query")
                                || attachment.at("/fields/content").has("binary")
                        ? ContentArea.open(contentArea, runId)
                        : null,
                mapping.at("/attachments/stripPrefix").asString(""),
                maxBytes,
                source);
    }

    /**
     * The file the load reads for a staged attachment (WP-26 part u), contained exactly as at extract: a file in the
     * run's content area by its SHA-256, or a path under {@code attachments.root}, absolute paths and {@code ..}
     * refused and the real path, symlinks followed, under the root's.
     *
     * @param mapping the validated mapping
     * @param contentArea {@code import.content-area}
     * @param runId the run
     * @param pointer the staged content pointer
     * @return the file, as a real path
     * @throws SourceException with the code of what refused it: the file is not where the extract hashed it
     */
    static Path stored(JsonNode mapping, @Nullable Path contentArea, long runId, String pointer) {
        JsonNode content = mapping.at("/entities/attachment/fields/content");
        if (content.has("query") || content.has("binary")) {
            return ContentArea.open(contentArea, runId).file(pointer);
        }
        Path root;
        try {
            root = Path.of(mapping.at("/attachments/root").asString()).toRealPath();
        } catch (IOException | InvalidPathException unreadable) {
            throw new SourceException("ATTACHMENTS_ROOT_UNREADABLE", null);
        }
        List<Issue> refused = new ArrayList<>();
        Path file = open(root, pointer, refused);
        if (file == null) {
            throw new SourceException(refused.getFirst().code(), null);
        }
        return file;
    }

    /**
     * Stages an attachment's content: a path or content-id file's pointer under the root, or a content query's file in
     * the content area, with its size and its SHA-256.
     *
     * @param row the source row, whose key binds a content query
     * @param values the row's staged values; {@code content}, {@code size} and {@code sha256} are replaced
     * @param issues where problems go
     */
    void capture(Map<String, @Nullable Object> row, Map<String, @Nullable Object> values, List<Issue> issues) {
        if (area != null) {
            fetch(area, row, values, issues);
            return;
        }
        if (root == null || !(values.get("content") instanceof String pointer)) {
            return;
        }
        String relative;
        String expected = null;
        if (content.has("contentId")) {
            FileManifest.Content found = source.content(pointer);
            if (found == null) {
                issues.add(new Issue("CONTENT_ID_UNKNOWN", Severity.ERROR));
                return;
            }
            relative = found.file();
            expected = found.sha256();
        } else {
            boolean prefixed = pointer.regionMatches(true, 0, stripPrefix, 0, stripPrefix.length());
            relative = (prefixed ? pointer.substring(stripPrefix.length()) : pointer).replace('\\', '/');
        }
        Path file = open(root, relative, issues);
        if (file != null) {
            values.put("content", root.relativize(file).toString());
            hash(file, expected, values, issues);
        }
    }

    /**
     * Inline bytes, or a content query's one value, into the content area. A mapped size over the cap means the bytes
     * are never fetched; a value over the cap once fetched keeps its size and is not written. A failed query stops the
     * extract: on PostgreSQL it ends the snapshot's transaction, so no later query could read the same point.
     */
    private void fetch(
            ContentArea area,
            Map<String, @Nullable Object> row,
            Map<String, @Nullable Object> values,
            List<Issue> issues) {
        if (values.get("content") instanceof byte[] bytes) {
            values.put("content", null);
            store(area, bytes, values);
            return;
        }
        if (!content.has("query") || (values.get("size") instanceof Long size && size > maxBytes)) {
            return;
        }
        List<@Nullable Object> key = new ArrayList<>();
        JsonNode columns = attachment.path("key");
        for (JsonNode column : columns.isArray() ? columns : List.of(columns)) {
            key.add(row.get(column.asString().toLowerCase(Locale.ROOT)));
        }
        value(source.contentRows(content.get("query").asString(), key), issues)
                .ifPresent(bytes -> store(area, bytes, values));
    }

    /** Bytes into the content area under their SHA-256, the cap checked first: over it, only the size is kept. */
    private void store(ContentArea area, byte[] bytes, Map<String, @Nullable Object> values) {
        values.put("size", (long) bytes.length);
        if (bytes.length <= maxBytes) {
            String sha256 = area.write(bytes);
            values.put("content", sha256);
            values.put("sha256", sha256);
        }
    }

    /** A content query's one binary value, or none with an issue. */
    private static Optional<byte[]> value(List<Map<String, @Nullable Object>> rows, List<Issue> issues) {
        Object value =
                rows.isEmpty() ? null : rows.getFirst().values().iterator().next();
        if (rows.size() > 1 || (rows.size() == 1 && rows.getFirst().size() != 1)) {
            issues.add(new Issue("CONTENT_NOT_ONE_VALUE", Severity.ERROR));
        } else if (value == null) {
            issues.add(new Issue("ATTACHMENT_MISSING", Severity.ERROR));
        } else if (value instanceof byte[] bytes) {
            return Optional.of(bytes);
        } else {
            issues.add(new Issue("CONTENT_NOT_BINARY", Severity.ERROR));
        }
        return Optional.empty();
    }

    /** The file under the root, or {@code null} with an issue: absolute, {@code ..}, outside, missing. */
    private static @Nullable Path open(Path root, String relative, List<Issue> issues) {
        try {
            if (relative.startsWith("/") || List.of(relative.split("/", -1)).contains("..")) {
                throw new SourceException("PATH_OUTSIDE_ROOT", null);
            }
            return FileManifest.open(root, relative);
        } catch (SourceException refused) {
            issues.add(new Issue(
                    "SOURCE_FILE_MISSING".equals(refused.code()) ? "ATTACHMENT_MISSING" : refused.code(),
                    Severity.ERROR));
            return null;
        }
    }

    private void hash(Path file, @Nullable String expected, Map<String, @Nullable Object> values, List<Issue> issues) {
        values.remove("size");
        values.remove("sha256");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            // The size of the file opened, not of the name: it cannot be swapped between the two.
            long size = channel.size();
            values.put("size", size);
            if (size > maxBytes) {
                return;
            }
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            InputStream in = Channels.newInputStream(channel);
            byte[] buffer = new byte[64 * 1024];
            long read = 0;
            for (int n = in.read(buffer); n >= 0 && read <= size; n = in.read(buffer)) {
                sha256.update(buffer, 0, n);
                read += n;
            }
            String hex = HexFormat.of().formatHex(sha256.digest());
            if (read != size || (expected != null && !expected.equals(hex))) {
                issues.add(new Issue("CONTENT_CHANGED", Severity.ERROR));
                return;
            }
            values.put("sha256", hex);
        } catch (IOException unreadable) {
            issues.add(new Issue("ATTACHMENT_UNREADABLE", Severity.ERROR));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }
}
