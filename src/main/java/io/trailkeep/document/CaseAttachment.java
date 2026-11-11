// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A file attached to a case ({@code CASE_ATTACHMENT}), written only after it was validated and scanned. Its bytes are
 * not a field: they are {@code CASE_ATTACHMENT_CHUNK} rows written and read by {@link AttachmentContentJdbcDao}
 * (ADR-0004). Everything but the release is fixed at upload: those columns are {@code updatable = false}, and the
 * runtime user's UPDATE grant covers only the release columns (BUILD_PLAN §1.3 "immutable attachments").
 *
 * <p>Download rule ({@link ScanStatus#isDownloadable()}, ADR-0013): {@code CLEAN}, {@code RELEASED} and
 * {@code NOT_SCANNED} files may be downloaded; {@code QUARANTINED} files never are. {@code NOT_SCANNED} means scanning
 * was delegated outside Trailkeep ({@code app.attachments.scanning=external}), so it is never shown as clean, and
 * there is nothing to release: a release applies only to a quarantined file.
 */
@Entity
@Table(name = "CASE_ATTACHMENT")
class CaseAttachment {

    /** The longest file name kept, in characters. */
    static final int MAX_FILE_NAME = 255;

    /** The longest release reason, in characters. */
    static final int MAX_REASON = 1000;

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "caseAttachmentSeq")
    @SequenceGenerator(name = "caseAttachmentSeq", sequenceName = "SEQ_CASE_ATTACHMENT", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "CASE_ID", nullable = false, updatable = false)
    private long caseId;

    /** As the uploader named it. Possible PHI on a restricted case: never logged, audited only as an HMAC. */
    @Column(name = "FILE_NAME", nullable = false, updatable = false, length = MAX_FILE_NAME)
    private String fileName = "";

    /** Detected from the bytes, never taken from the client. */
    @Column(name = "CONTENT_TYPE", nullable = false, updatable = false, length = 100)
    private String contentType = "";

    @Column(name = "SIZE_BYTES", nullable = false, updatable = false)
    private long sizeBytes;

    /** Lower-case hex SHA-256 of the content. */
    @Column(name = "SHA256", nullable = false, updatable = false, length = 64)
    private String sha256 = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "SCAN_STATUS", nullable = false, length = 12)
    private ScanStatus scanStatus = ScanStatus.CLEAN;

    /** The scanner's signature name for a quarantined file; kept after a release as evidence. */
    @Column(name = "SCAN_DETAIL", updatable = false, length = 200)
    private @Nullable String scanDetail;

    @Column(name = "RELEASED_BY")
    private @Nullable Long releasedBy;

    @Column(name = "RELEASED_AT")
    private @Nullable Instant releasedAt;

    @Column(name = "RELEASED_REASON", length = MAX_REASON)
    private @Nullable String releasedReason;

    @Column(name = "UPLOADED_AT", nullable = false, updatable = false)
    private Instant uploadedAt = Instant.EPOCH;

    @Column(name = "UPLOADED_BY", nullable = false, updatable = false)
    private long uploadedBy;

    /** The legacy uploader's name, when an imported file's uploader matched no user (WP-26, V14). */
    @Column(name = "UPLOADER_NAME_SNAPSHOT", updatable = false, length = 200)
    private @Nullable String uploaderNameSnapshot;

    /** For JPA. */
    protected CaseAttachment() {}

    /**
     * A file that passed validation and was scanned.
     *
     * @param caseId the case
     * @param file what validation established about the file
     * @param status {@link ScanStatus#CLEAN}, {@link ScanStatus#QUARANTINED} or {@link ScanStatus#NOT_SCANNED};
     *     nothing is released at upload
     * @param scanDetail the signature found, for a quarantined file; {@code null} otherwise
     * @param uploadedBy who uploaded it
     * @param now when
     * @return the attachment, not yet persisted
     * @throws IllegalArgumentException if the status and detail disagree or the file's facts are malformed
     */
    static CaseAttachment scanned(
            long caseId,
            StoredFile file,
            ScanStatus status,
            @Nullable String scanDetail,
            long uploadedBy,
            Instant now) {
        if (status == ScanStatus.RELEASED || (status == ScanStatus.QUARANTINED) != (scanDetail != null)) {
            throw new IllegalArgumentException(
                    "a stored file is clean or not scanned, or quarantined with a signature");
        }
        CaseAttachment attachment = new CaseAttachment();
        attachment.caseId = caseId;
        attachment.fileName = file.fileName();
        attachment.contentType = file.contentType();
        attachment.sizeBytes = file.sizeBytes();
        attachment.sha256 = file.sha256();
        attachment.scanStatus = status;
        attachment.scanDetail = scanDetail;
        attachment.uploadedBy = uploadedBy;
        attachment.uploadedAt = now;
        return attachment;
    }

    /**
     * An imported file (WP-26 part g, design §8.1 R6 7): its legacy upload time, and the legacy uploader's name when
     * they matched no user.
     *
     * @param scanned the file as {@link #scanned} would record it, with the uploader already chosen
     * @param uploadedAt when it was uploaded to the legacy tracker
     * @param uploaderNameSnapshot the legacy uploader's name, or {@code null}
     * @return the attachment, not yet persisted
     */
    static CaseAttachment imported(CaseAttachment scanned, Instant uploadedAt, @Nullable String uploaderNameSnapshot) {
        scanned.uploadedAt = uploadedAt;
        scanned.uploaderNameSnapshot = uploaderNameSnapshot;
        return scanned;
    }

    /**
     * Releases a quarantined file. The signature found stays recorded. A {@code NOT_SCANNED} file is downloadable
     * already and is never released.
     *
     * @param by the Compliance Admin releasing it
     * @param reason why, 1 to {@link #MAX_REASON} characters
     * @param now when
     * @throws IllegalStateException if the file is not quarantined, {@code NOT_SCANNED} included
     * @throws IllegalArgumentException if the reason is blank or too long
     */
    void release(long by, String reason, Instant now) {
        if (scanStatus != ScanStatus.QUARANTINED) {
            throw new IllegalStateException("only a quarantined file is released");
        }
        if (reason.isBlank() || reason.length() > MAX_REASON) {
            throw new IllegalArgumentException("a release reason is 1 to " + MAX_REASON + " characters");
        }
        scanStatus = ScanStatus.RELEASED;
        releasedBy = by;
        releasedAt = now;
        releasedReason = reason;
    }

    @Nullable
    Long getId() {
        return id;
    }

    long getCaseId() {
        return caseId;
    }

    String getFileName() {
        return fileName;
    }

    String getContentType() {
        return contentType;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    String getSha256() {
        return sha256;
    }

    ScanStatus getScanStatus() {
        return scanStatus;
    }

    @Nullable
    String getScanDetail() {
        return scanDetail;
    }

    @Nullable
    Long getReleasedBy() {
        return releasedBy;
    }

    @Nullable
    Instant getReleasedAt() {
        return releasedAt;
    }

    @Nullable
    String getReleasedReason() {
        return releasedReason;
    }

    Instant getUploadedAt() {
        return uploadedAt;
    }

    long getUploadedBy() {
        return uploadedBy;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof CaseAttachment that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return CaseAttachment.class.hashCode();
    }

    /** Ids and state only: the file name can be PHI. */
    @Override
    public String toString() {
        return "CaseAttachment[id=" + id + ", caseId=" + caseId + ", scanStatus=" + scanStatus + "]";
    }

    /**
     * What validation established about a file before it is stored.
     *
     * @param fileName the name as uploaded, 1 to {@link #MAX_FILE_NAME} characters
     * @param contentType the detected type, 1 to 100 characters
     * @param sizeBytes the size, not negative
     * @param sha256 the lower-case hex SHA-256 of the content
     */
    record StoredFile(String fileName, String contentType, long sizeBytes, String sha256) {
        StoredFile {
            if (fileName.isBlank() || fileName.length() > MAX_FILE_NAME) {
                throw new IllegalArgumentException("a file name is 1 to " + MAX_FILE_NAME + " characters");
            }
            if (contentType.isBlank() || contentType.length() > 100) {
                throw new IllegalArgumentException("a content type is 1 to 100 characters");
            }
            if (sizeBytes < 0 || !SHA256_HEX.matcher(sha256).matches()) {
                throw new IllegalArgumentException("a size is not negative and a hash is 64 hex digits");
            }
        }

        /** The name can be PHI. */
        @Override
        public String toString() {
            return "StoredFile[contentType=" + contentType + ", sizeBytes=" + sizeBytes + "]";
        }
    }
}
