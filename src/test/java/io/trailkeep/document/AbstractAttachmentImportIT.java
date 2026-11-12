// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.document.AttachmentImportService.Placement;
import io.trailkeep.document.AttachmentImportService.Prepared;
import io.trailkeep.document.AttachmentRejectedException.Reason;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.ImportPeopleService;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AttachmentImportService} (WP-26 part g, design §4.3, §8.1 R6 7, §8.2), in {@code external} scanning mode so
 * no scanner is needed: a file checked where it lies and left there, stored with its legacy time, uploader snapshot and
 * the importer's cap, its SHA-256 checked against the source and again at store. The clamd size refusal (R13) is
 * proven in {@code ClamdClientTest}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "app.attachments.scanning=external")
@Transactional
abstract class AbstractAttachmentImportIT {

    private static final Instant UPLOADED = Instant.parse("2019-05-06T07:08:09Z");
    private static final long APP_CAP = 25L * 1024 * 1024;

    @Autowired
    private AttachmentImportService imports;

    @Autowired
    private AttachmentContentJdbcDao content;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @TempDir
    private Path area;

    private long caseId;

    @BeforeEach
    void seed() {
        CaseSeed seed = new CaseSeed(jdbc);
        long owner = seed.user();
        CaseNumber number = seed.caseRow(seed.team(), owner, Sensitivity.STANDARD, CaseStatus.CLOSED);
        caseId = seed.caseId(number);
    }

    @Test
    void importedAttachmentKeepsLegacyTimeAndCap() throws IOException {
        Path big = area.resolve("legacy-report.txt");
        byte[] bytes =
                "synthetic legacy line\n".repeat((int) (APP_CAP / 22) + 1).getBytes(StandardCharsets.US_ASCII);
        Files.write(big, bytes);
        assertThat(bytes.length).as("above the upload cap").isGreaterThan((int) APP_CAP);

        Prepared prepared = imports.prepare("C:\\legacy\\files\\legacy-report.txt", big, sha256(bytes), 2 * APP_CAP);
        long id = imports.store(prepared, placement("Synthetic Legacy Uploader"));

        entityManager.flush();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT FILE_NAME, SCAN_STATUS, UPLOADER_NAME_SNAPSHOT, SIZE_BYTES FROM CASE_ATTACHMENT WHERE ID = ?",
                id);
        assertThat(row)
                .containsEntry("FILE_NAME", "legacy-report.txt")
                .containsEntry("SCAN_STATUS", "NOT_SCANNED")
                .containsEntry("UPLOADER_NAME_SNAPSHOT", "Synthetic Legacy Uploader");
        assertThat(((Number) Objects.requireNonNull(row.get("SIZE_BYTES"))).longValue())
                .isEqualTo(bytes.length);
        assertThat(jdbc.queryForObject("SELECT UPLOADED_AT FROM CASE_ATTACHMENT WHERE ID = ?", Timestamp.class, id))
                .isEqualTo(Timestamp.from(UPLOADED));
        assertThat(jdbc.queryForObject("SELECT UPLOADED_BY FROM CASE_ATTACHMENT WHERE ID = ?", Long.class, id))
                .isEqualTo(ImportPeopleService.IMPORTER_ID);
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        content.copy(id, bytes.length, stored);
        assertThat(stored.toByteArray()).isEqualTo(bytes);
        assertThat(big).as("the run's file is left where it was").exists();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ENTITY_ID = ? AND ACTION = 'ATTACHMENT_IMPORTED'"
                                + " AND ORIGIN = 'IMPORT' AND ACTOR_ID = ?",
                        Integer.class,
                        id,
                        ImportPeopleService.IMPORTER_ID))
                .isOne();
        assertThatThrownBy(() -> imports.prepare("legacy-report.txt", big, null, bytes.length - 1L))
                .as("the importer's cap still applies")
                .satisfies(reason(Reason.TOO_LARGE));
    }

    @Test
    void theSourcesHashIsCheckedAtPrepareAndTheFileAgainAtStore() throws IOException {
        Path file = area.resolve("note.txt");
        Files.writeString(file, "synthetic legacy note");

        assertThatThrownBy(() -> imports.prepare("note.txt", file, "ab".repeat(32), APP_CAP))
                .satisfies(reason(Reason.HASH_MISMATCH));

        Prepared prepared = imports.prepare("note.txt", file, null, APP_CAP);
        Files.writeString(file, "synthetic legacy note, changed after the scan");

        assertThatThrownBy(() -> imports.store(prepared, placement(null))).satisfies(reason(Reason.HASH_MISMATCH));
    }

    @Test
    void theUploadChecksStillApply() throws IOException {
        Path disguised = area.resolve("invoice.txt");
        Files.write(disguised, new byte[] {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0});

        assertThatThrownBy(() -> imports.prepare("invoice.txt", disguised, null, APP_CAP))
                .isInstanceOf(AttachmentRejectedException.class);
        assertThatThrownBy(() -> imports.prepare("   ", disguised, null, APP_CAP))
                .satisfies(reason(Reason.NAME));
    }

    private Placement placement(@Nullable String uploaderName) {
        return new Placement(caseId, ImportPeopleService.IMPORTER_ID, uploaderName, UPLOADED, 77L);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Consumer<Throwable> reason(Reason reason) {
        return e -> assertThat(((AttachmentRejectedException) e).reason()).isEqualTo(reason);
    }
}
