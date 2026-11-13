// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.document.AttachmentImportService;
import io.trailkeep.document.AttachmentImportService.Placement;
import io.trailkeep.document.AttachmentImportService.Prepared;
import io.trailkeep.document.AttachmentRejectedException;
import io.trailkeep.document.ScanSizeExceededException;
import io.trailkeep.document.ScanStatus;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.importer.staging.StagedAttachment;
import io.trailkeep.user.ImportPeopleDirectory.PersonMatch;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The attachment part of one load (WP-26 part u, design §4.3, §8.2). A case's staged attachments are scanned before
 * its transaction opens, through {@code document}'s import API, each read where the extract hashed it ({@link
 * AttachmentFiles#stored}); the transaction then stores them, each with its uploader and its import ledger row.
 *
 * <ul>
 *   <li>An attachment with an ERROR from the extract or the dry run, or with no bytes staged (over the importer's cap
 *       or missing, skipped by {@code limits.acceptSkip} or not), is left out with no issue beyond the dry run's.
 *   <li>One clamd refuses as over its own limit ({@code ScanSizeExceededException}, R13) is left out as an {@code
 *       ATTACHMENT_TOO_LARGE} {@code LOAD} issue with its staged size, and so is one the intake refuses ({@code
 *       ATTACHMENT_<reason>}); its case is loaded without it. An infected one is stored {@code QUARANTINED} and
 *       reported as an {@code ATTACHMENT_QUARANTINED} WARNING; in {@code external} mode each is stored {@code
 *       NOT_SCANNED}, as an upload is.
 *   <li>A file that is gone or no longer has the SHA-256 the extract recorded, at its scan or at its store, keeps its
 *       whole case out as a {@code CONTENT_CHANGED} {@code LOAD} issue, which the next load tries again.
 *   <li>Any other scanner failure stops the load, closed and resumable, as an upload fails closed.
 * </ul>
 *
 * <p>Scans happen outside every transaction, so the load moves its run on between them ({@link
 * LoadRunLock#keepAlive}): a case with many large files never looks like a load that died.
 */
final class AttachmentLoad {

    /** The {@code LOAD} issue code of a case with an attachment that is not the file the extract hashed. */
    static final String CONTENT_CHANGED = "CONTENT_CHANGED";

    /**
     * One attachment that passed its scan.
     *
     * @param staged the staged row
     * @param prepared the scanned file, ready to store
     */
    record Ready(StagedAttachment staged, Prepared prepared) {}

    /**
     * A case's attachments after their scans.
     *
     * @param ready those to store
     * @param issues the {@code LOAD} issues of those left out or quarantined, written in the case's transaction
     * @param refusal {@code CONTENT_CHANGED} when the case cannot be loaded now, else {@code null}
     */
    record Scanned(
            List<Ready> ready,
            List<ImportIssue> issues,
            @Nullable String refusal) {}

    private final CommentLoad.Context context;
    private final AttachmentImportService attachments;
    private final JsonNode mapping;
    private final @Nullable Path contentArea;
    private final long maxBytes;

    /**
     * The attachment part of a load.
     *
     * @param context what the load's children share
     * @param attachments {@code document}'s import API
     * @param mapping the validated mapping, which says where the files are
     * @param contentArea {@code import.content-area}
     * @param maxBytes {@code import.attachments.max-bytes}
     */
    AttachmentLoad(
            CommentLoad.Context context,
            AttachmentImportService attachments,
            JsonNode mapping,
            @Nullable Path contentArea,
            long maxBytes) {
        this.context = context;
        this.attachments = attachments;
        this.mapping = mapping;
        this.contentArea = contentArea;
        this.maxBytes = maxBytes;
    }

    /**
     * The staged attachments of a page's cases to load, by case key, in key order.
     *
     * @param caseKeys the cases' canonical keys
     * @return each case's attachments; a case without any is absent
     */
    Map<String, List<StagedAttachment>> staged(List<String> caseKeys) {
        Map<String, List<StagedAttachment>> byCase = CommentLoad.loadable(
                context, StagedAttachment.class, Subject.ATTACHMENT, StagedAttachment::getCaseKey, caseKeys);
        byCase.values()
                .forEach(rows -> rows.removeIf(row -> row.getSha256() == null || row.getContentPointer() == null));
        return byCase;
    }

    /**
     * Scans a case's attachments, before its transaction; the run is moved on before each scan once it is due.
     *
     * @param staged the case's attachments
     * @param lock the load's hold on its run
     * @return what to store, what to report, or why the case waits
     * @throws io.trailkeep.document.ScannerUnavailableException when the scanner gives no verdict
     */
    Scanned scan(List<StagedAttachment> staged, LoadRunLock lock) {
        List<Ready> ready = new ArrayList<>();
        List<ImportIssue> issues = new ArrayList<>();
        for (StagedAttachment row : staged) {
            lock.keepAlive();
            try {
                Path file = AttachmentFiles.stored(
                        mapping,
                        contentArea,
                        context.run().runId(),
                        Objects.requireNonNull(row.getContentPointer(), "staged"));
                Prepared prepared = attachments.prepare(
                        Objects.requireNonNull(row.getFileName(), "checked"), file, row.getSha256(), maxBytes);
                ready.add(new Ready(row, prepared));
                if (prepared.status() == ScanStatus.QUARANTINED) {
                    issues.add(issue(row, Severity.WARNING, "ATTACHMENT_QUARANTINED", null));
                }
            } catch (SourceException gone) {
                return new Scanned(List.of(), List.of(), CONTENT_CHANGED);
            } catch (ScanSizeExceededException tooLarge) {
                issues.add(issue(row, Severity.ERROR, "ATTACHMENT_TOO_LARGE", row.getSizeBytes()));
            } catch (AttachmentRejectedException rejected) {
                AttachmentRejectedException.Reason reason = rejected.reason();
                if (reason == AttachmentRejectedException.Reason.HASH_MISMATCH) {
                    return new Scanned(List.of(), List.of(), CONTENT_CHANGED);
                }
                String code = reason == AttachmentRejectedException.Reason.TOO_LARGE ? "TOO_LARGE" : reason.name();
                issues.add(issue(row, Severity.ERROR, "ATTACHMENT_" + code, null));
            }
        }
        return new Scanned(List.copyOf(ready), List.copyOf(issues), null);
    }

    /**
     * Stores a case's scanned attachments in its transaction, each by its matched uploader or, with the legacy
     * uploader's name, by the "Trailkeep import" principal, with its ledger row; and records the scans' issues.
     *
     * @param created the case
     * @param scanned its attachments, scanned
     * @param people the load's matched people
     * @param importer the "Trailkeep import" principal
     * @throws AttachmentRejectedException if a file changed since its scan, which rolls the case back
     */
    void write(CaseImportService.Created created, Scanned scanned, PeopleMatcher people, CaseComments.Author importer) {
        scanned.issues().forEach(context.issues()::save);
        for (Ready one : scanned.ready()) {
            StagedAttachment row = one.staged();
            PersonMatch match = people.match(row.getUploaderKey());
            String typed = row.getUploaderName();
            String name = typed != null ? typed : people.name(row.getUploaderKey());
            long id = attachments.store(
                    one.prepared(),
                    new Placement(
                            created.caseId(),
                            match == null ? Objects.requireNonNull(importer.id(), "set") : match.userId(),
                            match == null ? name : null,
                            Objects.requireNonNull(row.getUploadedAt(), "checked"),
                            context.run().runId()));
            context.ledger()
                    .save(ImportLedgerEntry.ofChild(
                                    context.run(),
                                    Subject.ATTACHMENT,
                                    row.getKey().legacyKey(),
                                    row.getRowHash(),
                                    created.caseId(),
                                    id,
                                    context.now())
                            .written(
                                    WrittenHash.of(
                                            context.hashKey(),
                                            List.of(id),
                                            attachments.writtenAttachments(List.of(id))),
                                    null,
                                    List.of()));
        }
    }

    private ImportIssue issue(StagedAttachment row, Severity severity, String code, @Nullable Long detail) {
        return new ImportIssue(
                context.run().runId(),
                ImportIssue.Stage.LOAD,
                Subject.ATTACHMENT,
                KeyedHash.hex(context.hashKey(), row.getKey().legacyKey()),
                severity,
                code,
                detail,
                context.now());
    }
}
