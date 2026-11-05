// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.casefile.CaseScopeService;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.document.CaseAttachment.StoredFile;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lists and uploads attachments. An upload is checked against the case first (404, 403), then received by {@link
 * UploadIntake} with no transaction open, because the scan is network I/O (Part B "Transactions"). Only then does one
 * transaction re-check the case, insert the row, write the chunks and record the audit event; the temp file is
 * deleted when the {@link ScannedUpload} closes, whichever way the upload ends.
 */
@Service
@CaseAccessPath
class DefaultCaseAttachmentService implements CaseAttachmentService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultCaseAttachmentService.class);

    private final CaseService cases;
    private final CaseScopeService scopes;
    private final CaseAttachmentRepository attachments;
    private final AttachmentContentJdbcDao content;
    private final UploadIntake intake;
    private final AttachmentProperties properties;
    private final UserDirectoryService directory;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final AuditRecorder audit;
    private final InstantSource clock;
    private final ZoneId zone;

    DefaultCaseAttachmentService(
            CaseService cases,
            CaseScopeService scopes,
            CaseAttachmentRepository attachments,
            AttachmentContentJdbcDao content,
            UploadIntake intake,
            AttachmentProperties properties,
            UserDirectoryService directory,
            EntityManager entityManager,
            TransactionTemplate transactions,
            AuditRecorder audit,
            InstantSource clock,
            AppProperties app) {
        this.cases = cases;
        this.scopes = scopes;
        this.attachments = attachments;
        this.content = content;
        this.intake = intake;
        this.properties = properties;
        this.directory = directory;
        this.entityManager = entityManager;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
        this.zone = app.timezone();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttachmentView> attachments(Actor actor, String caseNumber) {
        CaseNumber number;
        try {
            number = CaseNumber.parse(caseNumber);
        } catch (IllegalArgumentException malformed) {
            return List.of();
        }
        Map<Long, String> names = new HashMap<>();
        return attachments.findForCase(scopes.forUser(actor.permissions()), number).stream()
                .map(attachment -> new AttachmentView(
                        Objects.requireNonNull(attachment.getId()),
                        attachment.getFileName(),
                        attachment.getSizeBytes(),
                        names.computeIfAbsent(
                                attachment.getUploadedBy(),
                                id -> directory.displayName(id).orElse("")),
                        LocalDateTime.ofInstant(attachment.getUploadedAt(), zone)
                                .truncatedTo(ChronoUnit.MINUTES),
                        attachment.getScanStatus()))
                .toList();
    }

    @Override
    public UploadLimits limits() {
        return new UploadLimits(
                AttachmentTypes.BY_EXTENSION.keySet().stream().sorted().toList(),
                properties.maxBytes().toBytes());
    }

    @Override
    public ScanStatus upload(Actor actor, String caseNumber, String fileName, InputStream bytes) {
        cases.attachableCaseId(actor, caseNumber);
        // A browser may send a client path; only the last segment is a name. It never forms a path here.
        String name = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
        try (ScannedUpload upload = intake.receive(name, bytes)) {
            return Objects.requireNonNull(transactions.execute(status -> store(actor, caseNumber, name, upload)));
        }
    }

    /** Stores a scanned upload in the caller's transaction, re-checking the case as it stands after the scan. */
    private ScanStatus store(Actor actor, String caseNumber, String name, ScannedUpload upload) {
        long caseId = cases.attachableCaseId(actor, caseNumber);
        long actorId = actor.permissions().userId();
        ScanVerdict verdict = upload.verdict();
        StoredFile file = new StoredFile(name, upload.contentType(), upload.sizeBytes(), upload.sha256());
        CaseAttachment attachment =
                CaseAttachment.scanned(caseId, file, verdict.status(), verdict.signature(), actorId, clock.instant());
        entityManager.persist(attachment);
        entityManager.flush();
        long id = Objects.requireNonNull(attachment.getId());
        try (InputStream stored = Files.newInputStream(upload.file())) {
            content.write(id, stored);
        } catch (IOException e) {
            throw new UncheckedIOException("attachment content not stored", e);
        }
        audit.record(new AuditEntry(
                actorId,
                actor.displayName(),
                AuditOrigin.UI,
                caseId,
                "ATTACHMENT",
                id,
                "ATTACHMENT_ADDED",
                audit.diff(null, new AddedAttachment(file, verdict.status(), verdict.signature(), upload.scanMode()))));
        LOG.info("Attachment {} added to case id {}", id, caseId);
        return verdict.status();
    }

    /**
     * The audit snapshot of a stored upload. The name is possible PHI, so the trail holds only an HMAC and a length;
     * the scan mode in force is recorded, so a {@code NOT_SCANNED} file is traceable to its mode (ADR-0013).
     *
     * @param fileName the name as uploaded
     * @param contentType the detected type
     * @param sizeBytes the size
     * @param sha256 the content's hash
     * @param scanStatus the stored status, as the scan returned it
     * @param scanDetail the signature found, for a quarantined file
     * @param scanMode {@code CLAMD} or {@code EXTERNAL}
     */
    record AddedAttachment(
            @Redacted String fileName,
            String contentType,
            long sizeBytes,
            String sha256,
            ScanStatus scanStatus,
            @Nullable String scanDetail,
            ScanMode scanMode) {

        AddedAttachment(StoredFile file, ScanStatus scanStatus, @Nullable String scanDetail, ScanMode scanMode) {
            this(
                    file.fileName(),
                    file.contentType(),
                    file.sizeBytes(),
                    file.sha256(),
                    scanStatus,
                    scanDetail,
                    scanMode);
        }
    }
}
