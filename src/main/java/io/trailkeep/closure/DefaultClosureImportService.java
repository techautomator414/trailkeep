// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link ClosureImportService}: the approved, imported request and its audit event, in the caller's transaction. */
@Service
@Transactional
class DefaultClosureImportService implements ClosureImportService {

    /** The audit snapshot: the summary redacted like any closure's, the run in the details (design §7.4). */
    record ImportedClosureSnapshot(
            String outcome, @Redacted String summary, Instant closedAt, long runId, long approverId) {}

    /** {@code CLOSURE_REQUEST.OUTCOME}. */
    private static final int MAX_OUTCOME = 40;

    private final ClosureWrittenJdbcDao written;
    private final EntityManager entityManager;
    private final ImportPeopleService people;
    private final UserDirectoryService directory;
    private final AuditRecorder audit;
    private final InstantSource clock;

    DefaultClosureImportService(
            ClosureWrittenJdbcDao written,
            EntityManager entityManager,
            ImportPeopleService people,
            UserDirectoryService directory,
            AuditRecorder audit,
            InstantSource clock) {
        this.entityManager = entityManager;
        this.written = written;
        this.people = people;
        this.directory = directory;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public long importClosure(ImportedClosure closure) {
        String given = closure.resolutionSummary();
        String summary = given == null || given.isBlank() ? NO_SUMMARY : given;
        if (closure.outcome().isBlank()
                || closure.outcome().length() > MAX_OUTCOME
                || summary.length() > ClosureRequest.MAX_TEXT
                || closure.closedAt().isAfter(clock.instant())) {
            throw new ClosureException(Kind.INVALID, "an imported closure needs an outcome, a summary and a past time");
        }
        ImportPeopleService.ImportPrincipals principals = people.principals();
        ClosureRequest request =
                ClosureRequest.imported(closure.caseId(), principals, closure.outcome(), summary, closure.closedAt());
        entityManager.persist(request);
        entityManager.flush();
        long id = Objects.requireNonNull(request.getId(), "persisted");
        audit.record(new AuditEntry(
                principals.importer(),
                directory.displayName(principals.importer()).orElse("Trailkeep import"),
                AuditOrigin.IMPORT,
                closure.caseId(),
                "CLOSURE_REQUEST",
                id,
                "CLOSURE_IMPORTED",
                audit.diff(
                        null,
                        new ImportedClosureSnapshot(
                                closure.outcome(),
                                summary,
                                closure.closedAt(),
                                closure.runId(),
                                closure.runApproverId()))));
        return id;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, String> writtenClosures(Collection<Long> ids) {
        entityManager.flush();
        return written.byCase(ids);
    }
}
