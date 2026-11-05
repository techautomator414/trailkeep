// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.document.CaseAttachment.StoredFile;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code CASE_ATTACHMENT} and its chunked content, once per vendor (WP-14 part 1): content is stored as rows of at
 * most 1 MiB and read back byte for byte, one row per query; a missing chunk fails the copy; the entity round-trips
 * and releases; Hibernate updates exactly the columns the runtime user's UPDATE grant covers; and the database
 * refuses an inconsistent release and a chunk without its attachment.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractAttachmentContentIT {

    private static final int MIB = AttachmentContentJdbcDao.CHUNK_SIZE;
    private static final Instant UPLOADED = Instant.parse("2026-11-05T09:30:00Z");

    @Autowired
    private AttachmentContentJdbcDao content;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    private long caseId;
    private long uploader;

    @BeforeEach
    void seed() {
        CaseSeed seed = new CaseSeed(jdbc);
        long team = seed.team();
        uploader = seed.user();
        caseId = seed.caseId(seed.caseRow(team, uploader, Sensitivity.STANDARD, CaseStatus.OPEN));
    }

    @Test
    void contentOfSeveralChunksIsStoredInMebibyteRowsAndReadBackByteForByte() throws IOException {
        byte[] data = synthetic(2 * MIB + MIB / 2 + 7);
        long id = persisted(data, ScanStatus.CLEAN, null);

        assertThat(content.write(id, new ByteArrayInputStream(data))).isEqualTo(data.length);

        assertThat(chunkLengths(id)).containsExactly(MIB, MIB, MIB / 2 + 7);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        content.copy(id, data.length, out);
        assertThat(out.toByteArray()).isEqualTo(data);
        assertThat(sha256(out.toByteArray())).isEqualTo(sha256(data));
    }

    @Test
    void contentOfAnExactMultipleOfTheChunkSizeHasNoEmptyTrailingChunk() throws IOException {
        byte[] data = synthetic(2 * MIB);
        long id = persisted(data, ScanStatus.CLEAN, null);

        content.write(id, new ByteArrayInputStream(data));

        assertThat(chunkLengths(id)).containsExactly(MIB, MIB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        content.copy(id, data.length, out);
        assertThat(out.toByteArray()).isEqualTo(data);
    }

    @Test
    void anEmptyFileHasNoChunks() throws IOException {
        long id = persisted(new byte[0], ScanStatus.CLEAN, null);

        assertThat(content.write(id, new ByteArrayInputStream(new byte[0]))).isZero();

        assertThat(chunkLengths(id)).isEmpty();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        content.copy(id, 0, out);
        assertThat(out.size()).isZero();
    }

    @Test
    void missingChunkFailsTheCopyInsteadOfEndingItEarly() throws IOException {
        byte[] data = synthetic(MIB + 1);
        long id = persisted(data, ScanStatus.CLEAN, null);
        content.write(id, new ByteArrayInputStream(data));
        jdbc.update("DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID = ? AND SEQ = 1", id);

        assertThatThrownBy(() -> content.copy(id, data.length, new ByteArrayOutputStream()))
                .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void quarantinedAttachmentRoundTripsAndIsReleasedWithItsSignatureKept() {
        long id = persisted(synthetic(10), ScanStatus.QUARANTINED, "Synthetic.Test.Signature");
        entityManager.clear();

        CaseAttachment stored = Objects.requireNonNull(entityManager.find(CaseAttachment.class, id));
        assertThat(stored.getCaseId()).isEqualTo(caseId);
        assertThat(stored.getFileName()).isEqualTo("synthetic-évidence.pdf");
        assertThat(stored.getContentType()).isEqualTo("application/pdf");
        assertThat(stored.getSizeBytes()).isEqualTo(10);
        assertThat(stored.getSha256()).isEqualTo(sha256(synthetic(10)));
        assertThat(stored.getScanStatus()).isEqualTo(ScanStatus.QUARANTINED);
        assertThat(stored.getUploadedBy()).isEqualTo(uploader);
        assertThat(stored.getUploadedAt()).isEqualTo(UPLOADED);
        assertThat(stored.getReleasedBy()).isNull();

        stored.release(uploader, "Synthetic false positive", UPLOADED.plusSeconds(600));
        entityManager.flush();
        entityManager.clear();

        CaseAttachment released = Objects.requireNonNull(entityManager.find(CaseAttachment.class, id));
        assertThat(released.getScanStatus()).isEqualTo(ScanStatus.RELEASED);
        assertThat(released.getScanDetail()).isEqualTo("Synthetic.Test.Signature");
        assertThat(released.getReleasedBy()).isEqualTo(uploader);
        assertThat(released.getReleasedAt()).isEqualTo(UPLOADED.plusSeconds(600));
        assertThat(released.getReleasedReason()).isEqualTo("Synthetic false positive");
    }

    /**
     * The runtime user may update only these columns ({@code R__grants.sql}); if the mapping let Hibernate write
     * another, a release would fail in production with a privilege error that no test running as the owner sees.
     */
    @Test
    void hibernateUpdatesExactlyTheColumnsTheRuntimeUserMayUpdate() {
        TreeSet<String> updatable = new TreeSet<>();
        entityManagerFactory
                .unwrap(SessionFactoryImplementor.class)
                .getMappingMetamodel()
                .getEntityDescriptor(CaseAttachment.class)
                .forEachSelectable((index, selectable) -> {
                    if (selectable.isUpdateable()) {
                        updatable.add(selectable.getSelectionExpression().toUpperCase(Locale.ROOT));
                    }
                });

        assertThat(updatable).containsExactly("RELEASED_AT", "RELEASED_BY", "RELEASED_REASON", "SCAN_STATUS");
    }

    @Test
    void theDatabaseRefusesAReleasedAttachmentWithoutItsRelease() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO CASE_ATTACHMENT (ID, CASE_ID, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, SHA256,"
                                + " SCAN_STATUS, UPLOADED_AT, UPLOADED_BY) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        -7L,
                        caseId,
                        "synthetic.txt",
                        "text/plain",
                        1L,
                        "b".repeat(64),
                        "RELEASED",
                        Timestamp.from(UPLOADED),
                        uploader))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRefusesAChunkWithoutItsAttachment() {
        assertThatThrownBy(() -> content.write(-7L, new ByteArrayInputStream(synthetic(3))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long persisted(byte[] data, ScanStatus status, @Nullable String detail) {
        CaseAttachment attachment = CaseAttachment.scanned(
                caseId,
                new StoredFile("synthetic-évidence.pdf", "application/pdf", data.length, sha256(data)),
                status,
                detail,
                uploader,
                UPLOADED);
        entityManager.persist(attachment);
        entityManager.flush();
        return Objects.requireNonNull(attachment.getId());
    }

    private List<Integer> chunkLengths(long attachmentId) {
        return jdbc.query(
                "SELECT SEQ, CHUNK FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID = ? ORDER BY SEQ",
                (row, index) -> {
                    assertThat(row.getInt(1)).isEqualTo(index);
                    return row.getBytes(2).length;
                },
                attachmentId);
    }

    /** Deterministic bytes that cover every value and do not repeat at chunk boundaries. */
    private static byte[] synthetic(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i * 31 + (i >>> 11));
        }
        return data;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
