// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.document.AttachmentRejectedException.Reason;
import io.trailkeep.document.CaseAttachment.StoredFile;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link AttachmentImportService}: the upload intake on a file in place, then the row, chunks and audit event. */
@Service
class DefaultAttachmentImportService implements AttachmentImportService {

    /**
     * The audit snapshot of an imported file: the name redacted like an upload's, the scan mode in force (ADR-0013),
     * the legacy upload time and uploader name, and the run (design §7.4).
     */
    record ImportedAttachment(
            @Redacted String fileName,
            String contentType,
            long sizeBytes,
            String sha256,
            ScanStatus scanStatus,
            @Nullable String scanDetail,
            ScanMode scanMode,
            Instant uploadedAt,
            @Nullable String uploaderNameSnapshot,
            long runId) {}

    private final UploadIntake intake;
    private final AttachmentContentJdbcDao content;
    private final AttachmentWrittenJdbcDao written;
    private final EntityManager entityManager;
    private final ImportPeopleService people;
    private final UserDirectoryService directory;
    private final AuditRecorder audit;
    private final InstantSource clock;

    DefaultAttachmentImportService(
            AttachmentWrittenJdbcDao written,
            UploadIntake intake,
            AttachmentContentJdbcDao content,
            EntityManager entityManager,
            ImportPeopleService people,
            UserDirectoryService directory,
            AuditRecorder audit,
            InstantSource clock) {
        this.intake = intake;
        this.content = content;
        this.entityManager = entityManager;
        this.written = written;
        this.people = people;
        this.directory = directory;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public Prepared prepare(String fileName, Path file, @Nullable String expectedSha256, long maxBytes) {
        // Legacy names may carry a client path, Windows or not; only the last segment is a name. It never forms a path.
        String name = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
        ScannedUpload upload = intake.receive(name, file, maxBytes);
        if (expectedSha256 != null && !sameHash(expectedSha256, upload.sha256())) {
            throw new AttachmentRejectedException(Reason.HASH_MISMATCH, "content differs from the source's SHA-256");
        }
        return new Prepared(upload, name);
    }

    @Override
    @Transactional
    public long store(Prepared prepared, Placement placement) {
        ScannedUpload upload = prepared.upload();
        ScanVerdict verdict = upload.verdict();
        StoredFile file =
                new StoredFile(prepared.fileName(), upload.contentType(), upload.sizeBytes(), upload.sha256());
        CaseAttachment attachment = CaseAttachment.imported(
                CaseAttachment.scanned(
                        placement.caseId(),
                        file,
                        verdict.status(),
                        verdict.signature(),
                        placement.uploadedBy(),
                        clock.instant()),
                placement.uploadedAt(),
                placement.uploaderNameSnapshot());
        entityManager.persist(attachment);
        entityManager.flush();
        long id = Objects.requireNonNull(attachment.getId(), "persisted");
        if (!sameHash(upload.sha256(), writeChunks(id, upload.file()))) {
            throw new AttachmentRejectedException(Reason.HASH_MISMATCH, "file changed between its scan and its store");
        }
        long principal = people.principals().importer();
        audit.record(new AuditEntry(
                principal,
                directory.displayName(principal).orElse("Trailkeep import"),
                AuditOrigin.IMPORT,
                placement.caseId(),
                "ATTACHMENT",
                id,
                "ATTACHMENT_IMPORTED",
                audit.diff(
                        null,
                        new ImportedAttachment(
                                file.fileName(),
                                file.contentType(),
                                file.sizeBytes(),
                                file.sha256(),
                                verdict.status(),
                                verdict.signature(),
                                upload.scanMode(),
                                placement.uploadedAt(),
                                placement.uploaderNameSnapshot(),
                                placement.runId()))));
        return id;
    }

    /** Two hex SHA-256 values, in either case, compared in constant time; a malformed one matches nothing. */
    private static boolean sameHash(String expected, String actual) {
        try {
            return MessageDigest.isEqual(
                    HexFormat.of().parseHex(expected), HexFormat.of().parseHex(actual));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** Writes the chunks from the file, hashing what is written; the hash, in hex. */
    private String writeChunks(long attachmentId, Path file) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), sha256)) {
                content.write(attachmentId, in);
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("imported file not stored", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides SHA-256", e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, String> writtenAttachments(Collection<Long> ids) {
        entityManager.flush();
        return written.attachments(ids);
    }
}
