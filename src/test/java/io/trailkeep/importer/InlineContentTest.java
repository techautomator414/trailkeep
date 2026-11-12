// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.importer.FieldTransformer.Issue;
import io.trailkeep.importer.RowStager.Staged;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Consumer;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Inline attachment bytes in a files source (WP-26 part l2, design §4.2.2, §4.3): {@code content: { binary, column }}
 * is decoded by the transform and written into the run's content area under its SHA-256, the cap checked before
 * writing, exactly like a content query's value; the bytes never reach a text column or an issue.
 */
class InlineContentTest {

    private static final SecretKey KEY =
            new SecretKeySpec("synthetic-hash-key-of-32-bytes!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private static final byte[] SCAN = "synthetic scanned bytes".getBytes(StandardCharsets.UTF_8);

    private static final String SCAN_SHA256 = ValidateMappingCommand.sha256(SCAN);

    private static final long RUN = 42;

    private static final String MAPPING = """
            source: { id: files1, kind: files, timezone: UTC, manifest: manifest.yaml }
            entities:
              attachment:
                file: files.csv
                key: file_id
                parent: { entity: case, columns: [case_id] }
                fields: { fileName: name, content: { binary: base64, column: data } }
            """;

    @TempDir
    private Path area;

    @Test
    void bytesAreWrittenUnderTheirHashAndStagedAsItsPointer() throws Exception {
        Staged staged = stage(MAPPING, Base64.getEncoder().encodeToString(SCAN), 1024);

        assertThat(staged.issues()).isEmpty();
        assertThat(staged.values())
                .containsEntry("content", SCAN_SHA256)
                .containsEntry("sha256", SCAN_SHA256)
                .containsEntry("size", (long) SCAN.length);
        Path file = area.resolve(Long.toString(RUN)).resolve(SCAN_SHA256);
        assertThat(file).hasBinaryContent(SCAN);
        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

        Staged hex = stage(
                MAPPING.replace("binary: base64", "binary: hex"), HexFormat.of().formatHex(SCAN), 1024);
        assertThat(hex.values()).containsEntry("content", SCAN_SHA256);
        assertThat(hex.rowHash()).as("the same bytes stage the same row").isEqualTo(staged.rowHash());
        assertThat(stage(MAPPING, Base64.getEncoder().encodeToString("other".getBytes(StandardCharsets.UTF_8)), 1024)
                        .rowHash())
                .isNotEqualTo(staged.rowHash());
    }

    @Test
    void bytesOverTheCapKeepTheirSizeAndAreNeverWritten() {
        Staged staged = stage(MAPPING, Base64.getEncoder().encodeToString(SCAN), 4);

        assertThat(staged.issues())
                .as("the dry run reports ATTACHMENT_TOO_LARGE")
                .isEmpty();
        assertThat(staged.values())
                .containsEntry("size", (long) SCAN.length)
                .containsEntry("content", null)
                .doesNotContainKey("sha256");
        assertThat(area.resolve(Long.toString(RUN))).doesNotExist();
    }

    @Test
    void undecodableOrMissingBytesWriteNothing() {
        Staged broken = stage(MAPPING.replace("binary: base64", "binary: hex"), "zz", 1024);
        assertThat(broken.issues()).extracting(Issue::code).containsExactly("BAD_BINARY");
        assertThat(broken.values()).containsEntry("content", null).doesNotContainKey("size");

        assertThat(stage(MAPPING, null, 1024).values()).containsEntry("content", null);
        assertThat(area.resolve(Long.toString(RUN))).doesNotExist();
    }

    @Test
    void inlineBytesNeedContentAreaAndNeverReachTextColumn() {
        JsonNode mapping = YAMLMapper.builder().build().readTree(MAPPING);
        assertThatThrownBy(() -> AttachmentFiles.of(mapping, new NoRows(), 1024, null, RUN))
                .hasMessage("CONTENT_AREA_UNWRITABLE");

        Staged unstored = new RowStager(MappingContext.of(mapping, KEY, Map.of()), StagedEntity.ATTACHMENT)
                .stage(row(Base64.getEncoder().encodeToString(SCAN)));
        assertThat(unstored.issues()).extracting(Issue::code).containsExactly("BINARY_NOT_TEXT");
        assertThat(unstored.values()).containsEntry("content", null);
    }

    private Staged stage(String text, @Nullable String data, long maxBytes) {
        JsonNode mapping = YAMLMapper.builder().build().readTree(text);
        AttachmentFiles files = AttachmentFiles.of(mapping, new NoRows(), maxBytes, area, RUN);
        return new RowStager(MappingContext.of(mapping, KEY, Map.of()), StagedEntity.ATTACHMENT, files)
                .stage(row(data));
    }

    private static Map<String, @Nullable Object> row(@Nullable String data) {
        Map<String, @Nullable Object> row = new HashMap<>();
        row.put("file_id", "f1");
        row.put("case_id", "c1");
        row.put("name", "scan.pdf");
        row.put("data", data);
        return row;
    }

    /** A files source: inline bytes need nothing from it. */
    private static final class NoRows implements SourceRows {

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
    }
}
