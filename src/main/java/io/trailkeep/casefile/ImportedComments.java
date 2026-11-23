// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.casefile.CaseImportService.ImportRun;
import io.trailkeep.casefile.CaseImportService.ImportedComment;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The imported comments of one case (WP-26 part e, R6 6), for {@link DefaultCaseImportService}, in its transaction:
 * each kept with its legacy time and author snapshot and audited as {@code COMMENT_IMPORTED} by the "Trailkeep import"
 * principal, and all of them appended to the case's search text. A case's comments are written together (WP-30 part
 * b): the case is read once, the comments and their audit rows are persisted with the ids their pooled sequences give
 * and inserted at one flush, in JDBC batches, and the search text takes one append of every body in order, which is
 * the text one append per comment would leave.
 */
@CaseAccessPath
final class ImportedComments {

    /** The audit snapshot of an imported comment: the body redacted as for any comment, the run in the details. */
    record ImportedCommentSnapshot(
            @Redacted String body,
            @Nullable Long authorId,
            String authorName,
            Instant writtenAt,
            long runId,
            long approverId) {}

    /** {@code CASE_COMMENT.AUTHOR_NAME_SNAPSHOT}; the importer shortens longer legacy names with an issue. */
    private static final int MAX_AUTHOR_NAME = 200;

    private final CaseRecordRepository cases;
    private final CaseSearchJdbcDao search;
    private final EntityManager entityManager;
    private final UserDirectoryService directory;
    private final ImportPeopleService people;
    private final AuditRecorder audit;
    private final InstantSource clock;

    ImportedComments(
            CaseRecordRepository cases,
            CaseSearchJdbcDao search,
            EntityManager entityManager,
            UserDirectoryService directory,
            ImportPeopleService people,
            AuditRecorder audit,
            InstantSource clock) {
        this.cases = cases;
        this.search = search;
        this.entityManager = entityManager;
        this.directory = directory;
        this.people = people;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Writes a case's imported comments, every one checked before any is written.
     *
     * @param run the run
     * @param comments the comments, all of one case, in the order they were written
     * @return their ids, in the same order
     * @throws CaseException {@code INVALID} for a comment the rules refuse or comments of more than one case; {@code
     *     NOT_FOUND} for an unknown case
     */
    List<Long> write(ImportRun run, List<ImportedComment> comments) {
        if (comments.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        List<String> bodies = checked(comments, now);
        CaseRecord record = cases.findOne(
                        CaseScope.system(), comments.getFirst().caseNumber())
                .orElseThrow(() -> new CaseException(Kind.NOT_FOUND, "case not found"));
        if (!record.isImported()) {
            // The relaxations are for imported history; a case created in Trailkeep takes comments as anyone posts.
            throw invalid("only an imported case takes imported comments");
        }
        long caseId = Objects.requireNonNull(record.getId(), "persisted");
        long principal = people.principals().importer();
        String principalName = directory.displayName(principal).orElse("Trailkeep import");
        List<Long> ids = new ArrayList<>(comments.size());
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < comments.size(); i++) {
            ImportedComment comment = comments.get(i);
            String body = bodies.get(i);
            CaseComment imported =
                    CaseComment.of(caseId, body, comment.authorId(), comment.authorName(), comment.writtenAt());
            entityManager.persist(imported);
            long commentId = Objects.requireNonNull(imported.getId(), "persisted");
            ids.add(commentId);
            text.append('\n').append(body);
            audit.record(new AuditEntry(
                    principal,
                    principalName,
                    AuditOrigin.IMPORT,
                    caseId,
                    "COMMENT",
                    commentId,
                    "COMMENT_IMPORTED",
                    audit.diff(
                            null,
                            new ImportedCommentSnapshot(
                                    body,
                                    comment.authorId(),
                                    comment.authorName(),
                                    comment.writtenAt(),
                                    run.runId(),
                                    run.approverId()))));
        }
        // The search row may still wait to be inserted in this transaction (the case's own import): the update needs
        // it.
        entityManager.flush();
        if (!search.append(caseId, text.toString(), now)) {
            throw new IllegalStateException("an imported case has its search row from its import");
        }
        return List.copyOf(ids);
    }

    /** The comments' bodies with Windows line ends made plain, once every comment keeps the rules and the one case. */
    private static List<String> checked(List<ImportedComment> comments, Instant now) {
        CaseNumber number = comments.getFirst().caseNumber();
        List<String> bodies = new ArrayList<>(comments.size());
        for (ImportedComment comment : comments) {
            String body = comment.body().replace("\r\n", "\n");
            if (body.isBlank()
                    || body.length() > CaseComment.MAX_BODY
                    || comment.authorName().isBlank()
                    || comment.authorName().length() > MAX_AUTHOR_NAME
                    || comment.writtenAt().isAfter(now)) {
                throw invalid("a comment is 1 to 20000 characters, has an author name and was written in the past");
            }
            if (!comment.caseNumber().equals(number)) {
                throw invalid("the comments imported together belong to one case");
            }
            bodies.add(body);
        }
        return bodies;
    }

    private static CaseException invalid(String reason) {
        return new CaseException(Kind.INVALID, reason);
    }
}
