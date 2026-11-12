// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import java.nio.file.Path;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Stores imported attachments (WP-26 part g, docs/design/importer.md §4.3, §8.1, §8.2). Called only by {@code
 * importer} (ArchUnit). A file goes through the same intake as an upload, checked where it lies, in two steps: {@link
 * #prepare} validates and scans it before the case's transaction opens, because scanning is network I/O; {@link
 * #store} then writes it in that transaction, from the same file, and checks its SHA-256 again. The one relaxation
 * (R6 7, {@code importedAttachmentKeepsLegacyTimeAndCap}): the legacy upload time, the import principal or a matched
 * person as uploader with the legacy uploader's name, and the importer's size cap.
 */
public interface AttachmentImportService {

    /** A file that passed the intake and its scan, ready for {@link #store}; it never deletes the file. */
    final class Prepared {

        private final ScannedUpload upload;
        private final String fileName;

        Prepared(ScannedUpload upload, String fileName) {
            this.upload = upload;
            this.fileName = fileName;
        }

        ScannedUpload upload() {
            return upload;
        }

        String fileName() {
            return fileName;
        }

        /**
         * What the scan found, for the dry run's counts and the run's report.
         *
         * @return {@code CLEAN}, {@code QUARANTINED} or {@code NOT_SCANNED}
         */
        public ScanStatus status() {
            return upload.verdict().status();
        }

        /**
         * The size, for the report.
         *
         * @return bytes
         */
        public long sizeBytes() {
            return upload.sizeBytes();
        }
    }

    /**
     * Where an imported file is to be stored, and who uploaded it.
     *
     * @param caseId the imported case
     * @param uploadedBy the matched uploader, or the "Trailkeep import" principal
     * @param uploaderNameSnapshot the legacy uploader's name when they matched no user, or {@code null}
     * @param uploadedAt when it was uploaded to the legacy tracker
     * @param runId the import run, for the audit details
     */
    record Placement(
            long caseId, long uploadedBy, @Nullable String uploaderNameSnapshot, Instant uploadedAt, long runId) {}

    /**
     * Validates and scans a file in place, outside any transaction.
     *
     * @param fileName the legacy file name; only its last path segment is kept
     * @param file the file in the run's content area or the mounted folder, already contained by the importer
     * @param expectedSha256 the SHA-256 the source recorded, lower-case hex, or {@code null} if it recorded none
     * @param maxBytes the importer's size cap
     * @return the prepared file
     * @throws AttachmentRejectedException if the name, size, type, image or the source's SHA-256 is refused
     * @throws ScannerUnavailableException if the scanner gave no verdict; {@link ScanSizeExceededException} if the
     *     file is above the scanner's own limit
     */
    Prepared prepare(String fileName, Path file, @Nullable String expectedSha256, long maxBytes);

    /**
     * Stores a prepared file in the caller's transaction: the row, its chunks from the same file with its SHA-256
     * checked again, and an {@code ATTACHMENT_IMPORTED} audit event by the import principal.
     *
     * @param prepared the file
     * @param placement its case and uploader
     * @return the attachment's id
     * @throws AttachmentRejectedException if the file changed since it was prepared
     */
    long store(Prepared prepared, Placement placement);
}
