// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.importer.FieldTransformer.Issue;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Attachment files hashed in place at extract (WP-26 part k2, design §4.3): legacy paths normalised under the root,
 * content ids through the manifest, containment proven on the real path when the file is opened, and the importer's
 * cap, which keeps an over-cap file unread. Problems are codes, never paths.
 */
class AttachmentFilesTest {

    private static final byte[] SCAN = "synthetic scan bytes".getBytes(StandardCharsets.UTF_8);

    private static final String SCAN_SHA256 = ValidateMappingCommand.sha256(SCAN);

    @TempDir
    private Path root;

    @BeforeEach
    void mountTheFolder() throws Exception {
        Files.createDirectories(root.resolve("cases/7"));
        Files.write(root.resolve("cases/7/scan.pdf"), SCAN);
    }

    @Test
    void legacyPathLosesItsPrefixAndBackslashesAndIsHashedInPlace() throws Exception {
        Map<String, @Nullable Object> values = values("\\\\FILESHARE\\cases\\cases\\7\\scan.pdf");
        values.put("size", 1L);

        List<Issue> issues = capture(path(), values, 1024);

        assertThat(issues).isEmpty();
        assertThat(values)
                .containsEntry("content", "cases/7/scan.pdf")
                .as("the file's size, not the legacy column's")
                .containsEntry("size", (long) SCAN.length)
                .containsEntry("sha256", SCAN_SHA256);
    }

    @Test
    void fileOverTheImportersCapKeepsItsSizeAndIsNeverRead() throws Exception {
        Map<String, @Nullable Object> values = values("cases/7/scan.pdf");

        assertThat(capture(path(), values, SCAN.length - 1)).isEmpty();
        assertThat(values).containsEntry("size", (long) SCAN.length).doesNotContainKey("sha256");
    }

    @Test
    void contentIdIsResolvedThroughTheManifestAndMustMatchItsHash() throws Exception {
        Map<String, @Nullable Object> known = values("blob-7");
        assertThat(capture(contentId(), known, 1024)).isEmpty();
        assertThat(known).containsEntry("content", "cases/7/scan.pdf").containsEntry("sha256", SCAN_SHA256);

        Files.write(root.resolve("cases/7/scan.pdf"), "other synthetic bytes".getBytes(StandardCharsets.UTF_8));
        Map<String, @Nullable Object> changed = values("blob-7");
        assertThat(codes(capture(contentId(), changed, 1024))).containsExactly("CONTENT_CHANGED");
        assertThat(changed).doesNotContainKey("sha256");
        assertThat(codes(capture(contentId(), values("blob-8"), 1024))).containsExactly("CONTENT_ID_UNKNOWN");
    }

    @Test
    void pathsOutsideTheRootAndMissingFilesAreIssuesNotReads(@TempDir Path elsewhere) throws Exception {
        Files.write(elsewhere.resolve("secret.pdf"), SCAN);
        Files.createSymbolicLink(root.resolve("cases/7/link.pdf"), elsewhere.resolve("secret.pdf"));
        Files.createSymbolicLink(root.resolve("inside.pdf"), root.resolve("cases/7/scan.pdf"));

        for (String escaping : List.of(
                "cases/7/link.pdf", "cases/../../" + elsewhere.getFileName() + "/secret.pdf", "/etc/hostname")) {
            Map<String, @Nullable Object> values = values(escaping);
            assertThat(codes(capture(path(), values, 1024))).as(escaping).containsExactly("PATH_OUTSIDE_ROOT");
            assertThat(values).doesNotContainKeys("sha256", "size");
        }
        assertThat(codes(capture(path(), values("cases/7/gone.pdf"), 1024))).containsExactly("ATTACHMENT_MISSING");
        assertThat(codes(capture(path(), values("cases/7"), 1024)))
                .as("a folder is not a file")
                .containsExactly("ATTACHMENT_MISSING");
        Map<String, @Nullable Object> inside = values("inside.pdf");
        assertThat(capture(path(), inside, 1024))
                .as("a symlink that stays inside is followed")
                .isEmpty();
        assertThat(inside).containsEntry("content", "cases/7/scan.pdf");
    }

    @Test
    void anUnmountedRootStopsTheExtract() {
        assertThatThrownBy(() -> AttachmentFiles.of(
                        mapping(path(), root.resolve("unmounted").toString()), new Manifested(), 1024, null, 1))
                .hasMessage("ATTACHMENTS_ROOT_UNREADABLE");
    }

    private String path() {
        return "{ path: rel }";
    }

    private String contentId() {
        return "{ contentId: ref }";
    }

    private List<Issue> capture(String content, Map<String, @Nullable Object> values, long maxBytes) {
        List<Issue> issues = new ArrayList<>();
        AttachmentFiles.of(mapping(content, root.toString()), new Manifested(), maxBytes, null, 1)
                .capture(Map.of(), values, issues);
        assertThat(issues).allMatch(issue -> issue.severity() == Severity.ERROR);
        return issues;
    }

    private static JsonNode mapping(String content, String folder) {
        return YAMLMapper.builder().build().readTree("""
                        entities: { attachment: { fields: { content: %s } } }
                        attachments: { root: "%s", stripPrefix: '\\\\fileshare\\cases\\' }
                        """.formatted(content, folder));
    }

    private static Map<String, @Nullable Object> values(String pointer) {
        Map<String, @Nullable Object> values = new HashMap<>();
        values.put("content", pointer);
        return values;
    }

    private static List<String> codes(List<Issue> issues) {
        return issues.stream().map(Issue::code).toList();
    }

    /** A files source whose manifest knows one content id. */
    private static final class Manifested implements SourceRows {

        @Override
        public String fingerprint() {
            return "manifest:synthetic";
        }

        @Override
        public void read(StagedEntity entity, JsonNode spec, Consumer<Map<String, @Nullable Object>> rows) {
            // No rows: only content ids are asked for.
        }

        @Override
        public FileManifest.@Nullable Content content(String contentId) {
            return "blob-7".equals(contentId) ? new FileManifest.Content("cases/7/scan.pdf", SCAN_SHA256) : null;
        }

        @Override
        public void close() {
            // Nothing is open.
        }
    }
}
