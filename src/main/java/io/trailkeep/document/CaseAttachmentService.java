// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.casefile.CaseService.Actor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Attachments on the case page (BUILD_PLAN §4.3, §9): listed through the actor's {@code CaseScope} and uploaded after
 * the same refusals as a comment (404, then 403 on {@code COMMENT}, the "add comment / attachment" row of §3.2). An
 * upload is validated and scanned before anything is stored, outside any transaction; then the row, its content and
 * its audit event are written together. Nothing is ever stored unscanned in {@code clamd} mode. A download streams
 * in its own read-only transaction behind a per-replica bound; a quarantined file is never served until a Compliance
 * Admin releases it.
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

    /** A file ready to stream. It holds one of the replica's download slots until {@link #writeTo} has run once. */
    interface Download {

        /**
         * The name as uploaded; possible PHI, never logged.
         *
         * @return the name
         */
        String fileName();

        /**
         * The type detected at upload.
         *
         * @return the media type as stored
         */
        String contentType();

        /**
         * The exact length of the content.
         *
         * @return the size in bytes
         */
        long sizeBytes();

        /**
         * Streams the content, one chunk per query in its own read-only transaction, and frees the download slot
         * whichever way it ends. Call it exactly once.
         *
         * @param out where the bytes go; not closed
         * @throws IOException if writing fails, for example because the client went away
         */
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * Opens a download, deciding in order: {@code NOT_FOUND} for a malformed, absent or unreadable case and for an id
     * naming no attachment of that case; {@code FORBIDDEN} if the matrix denies {@code DOWNLOAD_ATTACHMENT} or the
     * file is quarantined ({@link ScanStatus#isDownloadable()}); then a download slot. Nothing is read yet.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param attachmentId the attachment
     * @return the download, holding a slot
     * @throws AttachmentException for 404 and 403
     * @throws DownloadsBusyException if every slot is taken
     */
    Download download(Actor actor, String caseNumber, long attachmentId);

    /**
     * A quarantined file as the release form names it.
     *
     * @param id the attachment's id
     * @param fileName the name as uploaded
     * @param signature what the scanner found
     */
    record Quarantined(long id, String fileName, @Nullable String signature) {}

    /**
     * A quarantined file for the release form: refusals as for {@link #release}, without a reason.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param attachmentId the attachment
     * @return the file
     */
    Quarantined quarantined(Actor actor, String caseNumber, long attachmentId);

    /**
     * Releases a quarantined file with a reason (BUILD_PLAN §4.3), deciding in order: {@code NOT_FOUND} as for {@link
     * #download}; {@code FORBIDDEN} if the matrix denies {@code RELEASE_QUARANTINE} (only a Compliance Admin may);
     * {@code INVALID} for a blank reason or one over 1000 characters, or a file that is neither quarantined nor
     * released. A file already released is left alone, so a second, concurrent release is a no-op: releases on one
     * case are serialized on its row. A release is audited as {@code ATTACHMENT_RELEASED}, the reason redacted.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param attachmentId the attachment
     * @param reason why the file is safe to release; possible PHI
     * @return {@code true} if this call released it, {@code false} if it was released already
     */
    boolean release(Actor actor, String caseNumber, long attachmentId, String reason);
}
