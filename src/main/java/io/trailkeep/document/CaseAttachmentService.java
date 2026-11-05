// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.casefile.CaseService.Actor;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Attachments on the case page (BUILD_PLAN §4.3, §9): listed through the actor's {@code CaseScope} and uploaded after
 * the same refusals as a comment (404, then 403 on {@code COMMENT}, the "add comment / attachment" row of §3.2). An
 * upload is validated and scanned before anything is stored, outside any transaction; then the row, its content and
 * its audit event are written together. Nothing is ever stored unscanned in {@code clamd} mode.
 */
public interface CaseAttachmentService {

    /**
     * An attachment as the page shows it. The name is possible PHI: render it as text, never log it.
     *
     * @param id the attachment's id
     * @param fileName the name as uploaded
     * @param sizeBytes the size in bytes
     * @param uploadedBy the uploader's display name
     * @param uploadedAt when, in {@code app.timezone}, to the minute
     * @param status the scan status the badge shows; {@code NOT_SCANNED} is never shown as verified
     */
    record AttachmentView(
            long id, String fileName, long sizeBytes, String uploadedBy, LocalDateTime uploadedAt, ScanStatus status) {}

    /**
     * What the upload form states next to the file input.
     *
     * @param extensions the allowed extensions, lower case, without the dot, in alphabetical order
     * @param maxBytes the largest file accepted, in bytes
     */
    record UploadLimits(List<String> extensions, long maxBytes) {
        /**
         * Copies the extensions.
         *
         * @param extensions the allowed extensions
         * @param maxBytes the largest file accepted
         */
        public UploadLimits {
            extensions = List.copyOf(extensions);
        }
    }

    /**
     * A case's attachments, oldest first; empty if there are none or the actor may not read the case.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the attachments
     */
    List<AttachmentView> attachments(Actor actor, String caseNumber);

    /**
     * The allowed types and the size cap, as configured.
     *
     * @return the limits
     */
    UploadLimits limits();

    /**
     * Uploads a file: {@code NOT_FOUND} for a malformed, absent or unreadable case and {@code FORBIDDEN} if the matrix
     * denies {@code COMMENT}, both before the file is read; then validation and the scan. A stored file is recorded
     * as {@code ATTACHMENT_ADDED}, its name redacted and the scan mode in the snapshot. An infected file is stored
     * {@code QUARANTINED}; in {@code external} mode every stored file is {@code NOT_SCANNED}. Any path segments in
     * the name are dropped.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param fileName the name the browser sent
     * @param content the bytes; the caller closes it
     * @return the stored file's scan status
     * @throws AttachmentRejectedException if the name, size, type or image is refused (422); nothing is stored
     * @throws ScannerUnavailableException if the scanner gave no verdict; nothing is stored
     */
    ScanStatus upload(Actor actor, String caseNumber, String fileName, InputStream content);
}
