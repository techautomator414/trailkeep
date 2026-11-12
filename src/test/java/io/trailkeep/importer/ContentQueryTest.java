// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.importer.FieldTransformer.Issue;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Attachments read by a content query at extract (WP-26 part n2, design §4.3): the source runs the query with the
 * attachment key as its binds, the one value lands in the run's content area under its SHA-256, an attachment whose
 * mapped size is over the cap is never fetched, and every other outcome is an issue code. The content area goes with
 * the run's staging.
 */
class ContentQueryTest {

    private static final byte[] SCAN = "synthetic scanned bytes".getBytes(StandardCharsets.UTF_8);

    private static final String SCAN_SHA256 = ValidateMappingCommand.sha256(SCAN);

    private static final long RUN = 42;

    @TempDir
    private Path area;

    @Test
    void theOneValueIsWrittenUnderItsHashWithTheKeyAsBinds() throws Exception {
        Queried source = new Queried(List.of(Map.of("content", SCAN)));
        Map<String, @Nullable Object> values = values(10L);

        assertThat(capture(source, values, 1024)).isEmpty();

        assertThat(source.binds).containsExactly(List.of("r1", 7));
        assertThat(values)
                .containsEntry("content", SCAN_SHA256)
                .containsEntry("sha256", SCAN_SHA256)
                .as("the bytes' size, not the legacy column's")
                .containsEntry("size", (long) SCAN.length);
        Path file = area.resolve(Long.toString(RUN)).resolve(SCAN_SHA256);
        assertThat(file).hasBinaryContent(SCAN);
        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(area.resolve(Long.toString(RUN))).isDirectoryNotContaining("glob:**.tmp");
    }

    @Test
    void anAttachmentOverTheCapIsNeverFetchedOrNeverWritten() {
        Queried source = new Queried(List.of(Map.of("content", SCAN)));
        Map<String, @Nullable Object> declared = values(4096L);
        assertThat(capture(source, declared, 1024)).isEmpty();
        assertThat(source.binds)
                .as("the mapped size settles it before any fetch")
                .isEmpty();
        assertThat(declared).containsEntry("size", 4096L).doesNotContainKey("sha256");

        Map<String, @Nullable Object> understated = values(1L);
        assertThat(capture(source, understated, 4)).isEmpty();
        assertThat(understated).containsEntry("size", (long) SCAN.length).doesNotContainKey("sha256");
        assertThat(area.resolve(Long.toString(RUN)))
                .as("made by the first write only")
                .doesNotExist();
    }

    @Test
    void anythingButOneBinaryValueIsAnIssue() {
        Map<String, @Nullable Object> nullContent = new HashMap<>();
        nullContent.put("content", null);
        assertThat(codes(new Queried(List.of()))).containsExactly("ATTACHMENT_MISSING");
        assertThat(codes(new Queried(List.of(nullContent)))).containsExactly("ATTACHMENT_MISSING");
        assertThat(codes(new Queried(List.of(Map.of("content", SCAN), Map.of("content", SCAN)))))
                .containsExactly("CONTENT_NOT_ONE_VALUE");
        assertThat(codes(new Queried(List.of(Map.of("content", SCAN, "name", "scan.pdf")))))
                .containsExactly("CONTENT_NOT_ONE_VALUE");
        assertThat(codes(new Queried(List.of(Map.of("content", "not bytes"))))).containsExactly("CONTENT_NOT_BINARY");
    }

    @Test
    void contentQueriesNeedContentAreaAndFilesSourcesRunNoQuery() {
        assertThatThrownBy(() -> AttachmentFiles.of(mapping(), new Queried(List.of()), 1024, null, RUN))
                .hasMessage("CONTENT_AREA_UNWRITABLE");
        assertThatThrownBy(
                        () -> AttachmentFiles.of(mapping(), new Queried(List.of()), 1024, area.resolve("missing"), RUN))
                .hasMessage("CONTENT_AREA_UNWRITABLE");

        SourceRows files = new SourceRows() {
            @Override
            public String fingerprint() {
                return "manifest:synthetic";
            }

            @Override
            public void read(StagedEntity entity, JsonNode spec, Consumer<Map<String, @Nullable Object>> rows) {
                // No rows.
            }

            @Override
            public void close() {
                // Nothing is open.
            }
        };
        assertThatThrownBy(() -> capture(files, values(1L), 1024)).hasMessage("SOURCE_UNSUPPORTED");
    }

    @Test
    void theContentAreaGoesWithTheRun() throws Exception {
        assertThat(capture(new Queried(List.of(Map.of("content", SCAN))), values(1L), 1024))
                .isEmpty();
        final Path other = Files.createDirectories(area.resolve("43"));
        Path outside = Files.writeString(area.resolve("outside.txt"), "kept");
        Files.createSymbolicLink(area.resolve(Long.toString(RUN)).resolve("link"), outside);

        ContentArea.delete(area, RUN);
        ContentArea.delete(area, RUN);
        ContentArea.delete(null, RUN);

        assertThat(area.resolve(Long.toString(RUN))).doesNotExist();
        assertThat(other).exists();
        assertThat(outside).as("a link is removed, never what it points at").hasContent("kept");
    }

    private List<String> codes(Queried source) {
        return capture(source, values(1L), 1024).stream().map(Issue::code).toList();
    }

    private List<Issue> capture(SourceRows source, Map<String, @Nullable Object> values, long maxBytes) {
        List<Issue> issues = new ArrayList<>();
        AttachmentFiles.of(mapping(), source, maxBytes, area, RUN)
                .capture(Map.of("region", "r1", "file_seq", 7, "other", "x"), values, issues);
        return issues;
    }

    private static JsonNode mapping() {
        return YAMLMapper.builder().build().readTree("""
                entities:
                  attachment:
                    key: [REGION, file_seq]
                    fields: { content: { query: "SELECT content FROM files WHERE region = ? AND seq = ?", size: n } }
                """);
    }

    private static Map<String, @Nullable Object> values(long size) {
        Map<String, @Nullable Object> values = new HashMap<>();
        values.put("content", null);
        values.put("size", size);
        return values;
    }

    /** A JDBC source whose content query returns the given rows and records its binds. */
    private static class Queried implements SourceRows {

        private final List<Map<String, @Nullable Object>> rows;
        private final List<List<@Nullable Object>> binds = new ArrayList<>();

        Queried(List<? extends Map<String, ? extends @Nullable Object>> rows) {
            this.rows = rows.stream()
                    .<Map<String, @Nullable Object>>map(HashMap::new)
                    .toList();
        }

        @Override
        public String fingerprint() {
            return "postgresql:synthetic";
        }

        @Override
        public void read(StagedEntity entity, JsonNode spec, Consumer<Map<String, @Nullable Object>> rows) {
            // Only content queries are asked for.
        }

        @Override
        public List<Map<String, @Nullable Object>> contentRows(String query, List<@Nullable Object> key) {
            binds.add(key);
            return rows;
        }

        @Override
        public void close() {
            // Nothing is open.
        }
    }
}
