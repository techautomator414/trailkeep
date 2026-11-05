// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.document.CaseAttachment.StoredFile;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CaseAttachmentTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final StoredFile FILE = new StoredFile("synthetic-report.pdf", "application/pdf", 1234, HASH);

    @Test
    void cleanFileIsDownloadableAndCarriesNoSignature() {
        CaseAttachment attachment = CaseAttachment.scanned(7, FILE, ScanStatus.CLEAN, null, 3, NOW);

        assertThat(attachment.getScanStatus().isDownloadable()).isTrue();
        assertThat(attachment.getScanDetail()).isNull();
        assertThat(attachment.getFileName()).isEqualTo("synthetic-report.pdf");
        assertThat(attachment.getContentType()).isEqualTo("application/pdf");
        assertThat(attachment.getSizeBytes()).isEqualTo(1234);
        assertThat(attachment.getSha256()).isEqualTo(HASH);
        assertThat(attachment.getCaseId()).isEqualTo(7);
        assertThat(attachment.getUploadedBy()).isEqualTo(3);
        assertThat(attachment.getUploadedAt()).isEqualTo(NOW);
    }

    @Test
    void quarantinedFileNeedsItsSignatureAndIsNotDownloadable() {
        CaseAttachment attachment =
                CaseAttachment.scanned(7, FILE, ScanStatus.QUARANTINED, "Synthetic.Test.Signature", 3, NOW);

        assertThat(attachment.getScanStatus().isDownloadable()).isFalse();
        assertThat(attachment.getScanDetail()).isEqualTo("Synthetic.Test.Signature");
        assertThatThrownBy(() -> CaseAttachment.scanned(7, FILE, ScanStatus.QUARANTINED, null, 3, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CaseAttachment.scanned(7, FILE, ScanStatus.CLEAN, "Synthetic", 3, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notScannedFileIsDownloadableCarriesNoSignatureAndIsNeverReleased() {
        CaseAttachment attachment = CaseAttachment.scanned(7, FILE, ScanStatus.NOT_SCANNED, null, 3, NOW);

        assertThat(attachment.getScanStatus().isDownloadable()).isTrue();
        assertThat(attachment.getScanDetail()).isNull();
        assertThatThrownBy(() -> attachment.release(9, "Scanned upstream", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attachment.getScanStatus()).isEqualTo(ScanStatus.NOT_SCANNED);
        assertThatThrownBy(() -> CaseAttachment.scanned(7, FILE, ScanStatus.NOT_SCANNED, "Synthetic", 3, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyQuarantinedFilesAreWithheldFromDownload() {
        assertThat(ScanStatus.values())
                .filteredOn(status -> !status.isDownloadable())
                .containsExactly(ScanStatus.QUARANTINED);
    }

    @Test
    void nothingIsReleasedAtUpload() {
        assertThatThrownBy(() -> CaseAttachment.scanned(7, FILE, ScanStatus.RELEASED, null, 3, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void releasingQuarantinedFileRecordsWhoWhenAndWhyAndKeepsTheSignature() {
        CaseAttachment attachment = CaseAttachment.scanned(7, FILE, ScanStatus.QUARANTINED, "Synthetic.Sig", 3, NOW);

        attachment.release(9, "False positive confirmed with the vendor", NOW.plusSeconds(60));

        assertThat(attachment.getScanStatus()).isEqualTo(ScanStatus.RELEASED);
        assertThat(attachment.getScanStatus().isDownloadable()).isTrue();
        assertThat(attachment.getReleasedBy()).isEqualTo(9);
        assertThat(attachment.getReleasedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(attachment.getReleasedReason()).isEqualTo("False positive confirmed with the vendor");
        assertThat(attachment.getScanDetail()).isEqualTo("Synthetic.Sig");
    }

    @Test
    void onlyQuarantinedFileCanBeReleasedAndOnlyWithReason() {
        CaseAttachment clean = CaseAttachment.scanned(7, FILE, ScanStatus.CLEAN, null, 3, NOW);
        CaseAttachment quarantined = CaseAttachment.scanned(7, FILE, ScanStatus.QUARANTINED, "Synthetic", 3, NOW);

        assertThatThrownBy(() -> clean.release(9, "reason", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> quarantined.release(9, "   ", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quarantined.release(9, "x".repeat(1001), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        quarantined.release(9, "x".repeat(1000), NOW);
        assertThatThrownBy(() -> quarantined.release(9, "again", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void storedFileFactsAreValidatedAndTheNameNeverReachesToString() {
        assertThat(FILE.toString()).doesNotContain("synthetic-report");
        assertThat(CaseAttachment.scanned(7, FILE, ScanStatus.CLEAN, null, 3, NOW)
                        .toString())
                .doesNotContain("synthetic-report");
        assertThatThrownBy(() -> new StoredFile(" ", "application/pdf", 1, HASH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoredFile("n".repeat(256), "application/pdf", 1, HASH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoredFile("a.pdf", "", 1, HASH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoredFile("a.pdf", "t".repeat(101), 1, HASH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoredFile("a.pdf", "application/pdf", -1, HASH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoredFile("a.pdf", "application/pdf", 1, "A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoredFile("a.pdf", "application/pdf", 1, "a".repeat(63)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chunkCountCoversTheSizeWithoutAnEmptyTrailingChunk() {
        int mib = AttachmentContentJdbcDao.CHUNK_SIZE;

        assertThat(AttachmentContentJdbcDao.chunkCount(0)).isZero();
        assertThat(AttachmentContentJdbcDao.chunkCount(1)).isEqualTo(1);
        assertThat(AttachmentContentJdbcDao.chunkCount(mib)).isEqualTo(1);
        assertThat(AttachmentContentJdbcDao.chunkCount(mib + 1L)).isEqualTo(2);
        assertThat(AttachmentContentJdbcDao.chunkCount(25L * mib)).isEqualTo(25);
    }

    @Test
    void unsavedAttachmentsWithEqualFactsAreDistinct() {
        CaseAttachment one = CaseAttachment.scanned(7, FILE, ScanStatus.CLEAN, null, 3, NOW);
        CaseAttachment other = CaseAttachment.scanned(7, FILE, ScanStatus.CLEAN, null, 3, NOW);

        assertThat(one).isNotEqualTo(other).isNotEqualTo(null).hasSameHashCodeAs(other);
    }
}
