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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Attachments read by path or content id, hashed in place at extract (WP-26 part k2, design §4.3): the file stays in
 * the mounted folder, and its size and SHA-256 are staged so the load can prove it loads the bytes the dry run saw. A
 * legacy path loses {@code attachments.stripPrefix} and its backslashes; a content id is looked up in the manifest,
 * whose SHA-256 the file must match. Absolute paths and {@code ..} segments are refused, and the real path, symlinks
 * followed, must lie under the root's when the file is opened. A file over {@code import.attachments.max-bytes} keeps
 * its size and is never read; the dry run reports it ({@code ATTACHMENT_TOO_LARGE}, before any scan). Content queries
 * are the JDBC sources' (26n2). Every problem is an issue code, never a path.
 */
final class AttachmentFiles {

    private final JsonNode content;
    private final @Nullable Path root;
    private final String stripPrefix;
    private final long maxBytes;
    private final SourceRows source;

    private AttachmentFiles(
            JsonNode content, @Nullable Path root, String stripPrefix, long maxBytes, SourceRows source) {
        this.content = content;
        this.root = root;
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
     * @return them
     * @throws SourceException {@code ATTACHMENTS_ROOT_UNREADABLE} if {@code attachments.root} is not a readable folder
     */
    static AttachmentFiles of(JsonNode mapping, SourceRows source, long maxBytes) {
        JsonNode root = mapping.at("/attachments/root");
        Path real = null;
        if (root.isString()) {
            try {
                real = Path.of(root.asString()).toRealPath();
            } catch (IOException | InvalidPathException unreadable) {
                throw new SourceException("ATTACHMENTS_ROOT_UNREADABLE", null);
            }
        }
        return new AttachmentFiles(
                mapping.at("/entities/attachment/fields/content"),
                real,
                mapping.at("/attachments/stripPrefix").asString(""),
                maxBytes,
                source);
    }

    /**
     * Stages a path or content-id attachment's file: its pointer under the root, its size and its SHA-256.
     *
     * @param values the row's staged values; {@code content}, {@code size} and {@code sha256} are replaced
     * @param issues where problems go
     */
    void capture(Map<String, @Nullable Object> values, List<Issue> issues) {
        if (root == null || !(values.get("content") instanceof String pointer) || content.has("query")) {
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
