// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.security.CasePermissionService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link CaseImportService}: the case, its SLA row, its search row and its audit event, in one transaction. */
@Service
@Transactional
@CaseAccessPath
class DefaultCaseImportService implements CaseImportService {

    /** The audit snapshot of an imported comment: the body redacted as for any comment, the run in the details. */
    record ImportedCommentSnapshot(
            @Redacted String body,
            @Nullable Long authorId,
            String authorName,
            Instant writtenAt,
            long runId,
            long approverId) {}

    /**
     * The audit snapshot of an imported case: flat, with subject and description redacted as for any case, and the
     * run in the details (design §7.4). The legacy reference stays out: it is the internal identity, never shown, and
     * the import ledger keeps it.
     */
    record ImportedSnapshot(
            long caseTypeId,
            String title,
            CasePriority priority,
            LocalDate identifiedOn,
            long teamId,
            long ownerId,
            @Redacted @Nullable String subjectText,
            @Redacted String description,
            Sensitivity sensitivity,
            CaseStatus status,
            Instant openedAt,
            @Nullable Instant closedAt,
            @Nullable String legacyNumber,
            long runId,
            long approverId) {}

    /** {@code CASE_COMMENT.AUTHOR_NAME_SNAPSHOT}; the importer shortens longer legacy names with an issue. */
    private static final int MAX_AUTHOR_NAME = 200;

    private final CaseRecordRepository cases;
    private final CaseSearchJdbcDao search;
    private final CaseTypeRepository caseTypes;
    private final CaseTypeOutcomeRepository outcomes;
    private final SlaPolicyRepository policies;
    private final CaseNumberJdbcDao numbers;
    private final EntityManager entityManager;
    private final CasePermissionService permissions;
    private final UserDirectoryService directory;
    private final ImportPeopleService people;
    private final AuditRecorder audit;
    private final InstantSource clock;
    private final ZoneId zone;
    private final String prefix;

    DefaultCaseImportService(
            CaseRecordRepository cases,
            CaseSearchJdbcDao search,
            CaseTypeRepository caseTypes,
            CaseTypeOutcomeRepository outcomes,
            SlaPolicyRepository policies,
            CaseNumberJdbcDao numbers,
            EntityManager entityManager,
            CasePermissionService permissions,
            UserDirectoryService directory,
            ImportPeopleService people,
            AuditRecorder audit,
            InstantSource clock,
            AppProperties app,
            CaseProperties properties) {
        this.cases = cases;
        this.search = search;
        this.caseTypes = caseTypes;
        this.outcomes = outcomes;
        this.policies = policies;
        this.numbers = numbers;
        this.entityManager = entityManager;
        this.permissions = permissions;
        this.directory = directory;
        this.people = people;
        this.audit = audit;
        this.clock = clock;
        this.zone = app.timezone();
        this.prefix = properties.numberPrefix();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImportCaseType> caseTypes() {
        return caseTypes.findAllByOrderByNameAsc().stream()
                .map(type -> new ImportCaseType(
                        type.getName(),
                        type.isActive(),
                        type.getRetentionYears(),
                        outcomes
                                .findByIdCaseTypeIdOrderBySortOrderAsc(
                                        Objects.requireNonNull(type.getId(), "persisted"))
                                .stream()
                                .map(CaseTypeOutcome::outcome)
                                .collect(Collectors.toUnmodifiableSet())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SlaForecast slaForecast(Instant at) {
        Map<String, SlaPolicyCommand> byType = new HashMap<>();
        for (CaseType type : caseTypes.findAllByOrderByNameAsc()) {
            policies.findById(type.getSlaPolicyId())
                    .ifPresent(policy -> byType.put(type.getName(), policy.toCommand()));
        }
        return (name, identifiedOn, openedAt) -> {
            SlaPolicyCommand policy = byType.get(name);
            return policy == null
                    ? null
                    : CaseSla.importedOpen(0, SlaClock.start(policy, identifiedOn, openedAt, zone), at)
                            .getState();
        };
    }

    @Override
    public CaseNumber importCase(ImportRun run, ImportedCase imported) {
        Instant now = clock.instant();
        CaseFields fields = imported.fields();
        boolean closed = checkDates(imported, now);
        final CaseType type = caseTypes
                .findById(fields.caseTypeId())
                .filter(found -> closed || found.isActive())
                .orElseThrow(() -> invalid("unknown case type, or an inactive one for an open case"));
        // R6 relaxes the case type for closed cases, not the team: every imported case needs an active team.
        if (!directory.team(fields.teamId()).map(TeamSummary::active).orElse(false)) {
            throw invalid("unknown or inactive team");
        }
        ImportPeopleService.ImportPrincipals principals = people.principals();
        checkOwner(fields, closed, principals);
        CaseNumber number = new CaseNumber(prefix, now.atZone(zone).getYear(), numbers.next());
        CaseRecord record = new CaseRecord(number, imported, principals.importer(), now);
        entityManager.persist(record);
        long caseId = Objects.requireNonNull(record.getId(), "persisted");
        SlaPolicyCommand policy = policies.findById(type.getSlaPolicyId())
                .orElseThrow(() -> new IllegalStateException("a case type has a policy"))
                .toCommand();
        entityManager.persist(
                closed
                        ? CaseSla.notApplicable(caseId)
                        : CaseSla.importedOpen(
                                caseId, SlaClock.start(policy, fields.identifiedOn(), imported.openedAt(), zone), now));
        entityManager.persist(
                new CaseSearch(caseId, CaseSearchWriter.caseText(record).toString(), now));
        audit.record(new AuditEntry(
                principals.importer(),
                directory.displayName(principals.importer()).orElse("Trailkeep import"),
                AuditOrigin.IMPORT,
                caseId,
                "CASE",
                caseId,
                "CASE_IMPORTED",
                audit.diff(null, snapshot(run, imported))));
        return number;
    }

    @Override
    public long importComment(ImportRun run, ImportedComment comment) {
        Instant now = clock.instant();
        String body = comment.body().replace("\r\n", "\n");
        if (body.isBlank()
                || body.length() > CaseComment.MAX_BODY
                || comment.authorName().isBlank()
                || comment.authorName().length() > MAX_AUTHOR_NAME
                || comment.writtenAt().isAfter(now)) {
            throw invalid("a comment is 1 to 20000 characters, has an author name and was written in the past");
        }
        CaseRecord record = cases.findOne(CaseScope.system(), comment.caseNumber())
                .orElseThrow(() -> new CaseException(Kind.NOT_FOUND, "case not found"));
        if (!record.isImported()) {
            // The relaxations are for imported history; a case created in Trailkeep takes comments as anyone posts.
            throw invalid("only an imported case takes imported comments");
        }
        long caseId = Objects.requireNonNull(record.getId(), "persisted");
        CaseComment imported =
                CaseComment.of(caseId, body, comment.authorId(), comment.authorName(), comment.writtenAt());
        entityManager.persist(imported);
        entityManager.flush();
        if (!search.append(caseId, "\n" + body, now)) {
            throw new IllegalStateException("an imported case has its search row from its import");
        }
        long commentId = Objects.requireNonNull(imported.getId(), "persisted");
        long principal = people.principals().importer();
        audit.record(new AuditEntry(
                principal,
                directory.displayName(principal).orElse("Trailkeep import"),
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
        return commentId;
    }

    /** Only {@code OPEN} or {@code CLOSED}, with dates in order and none in the future; true for {@code CLOSED}. */
    private boolean checkDates(ImportedCase imported, Instant now) {
        Instant closedAt = imported.closedAt();
        boolean closed = switch (imported.status()) {
            case OPEN -> false;
            case CLOSED -> true;
            default -> throw invalid("only open and closed cases are imported");
        };
        if (closed == (closedAt == null) || (closedAt != null && closedAt.isBefore(imported.openedAt()))) {
            throw invalid("a closed case needs a closing time after its opening, an open case none");
        }
        if (imported.openedAt().isAfter(now)
                || (closedAt != null && closedAt.isAfter(now))
                || imported.fields().identifiedOn().isAfter(LocalDate.ofInstant(now, zone))) {
            throw invalid("dated in the future");
        }
        return closed;
    }

    /**
     * An open case's owner must be able to own the team's cases (the importer has already put the team's default
     * owner in place of an unmatched one); a closed case's owner may be anyone who exists, including the import
     * principal. The legacy-approval principal never owns a case, so owner and approver always differ (§7.1).
     */
    private void checkOwner(CaseFields fields, boolean closed, ImportPeopleService.ImportPrincipals principals) {
        long owner = fields.ownerId();
        boolean allowed = owner != principals.legacyApproval()
                && (closed
                        ? directory.displayName(owner).isPresent()
                        : permissions.mayOwn(directory.permissionsOf(owner), fields.teamId()));
        if (!allowed) {
            throw invalid("the owner may not own this case");
        }
    }

    private static ImportedSnapshot snapshot(ImportRun run, ImportedCase imported) {
        CaseFields fields = imported.fields();
        return new ImportedSnapshot(
                fields.caseTypeId(),
                fields.title(),
                fields.priority(),
                fields.identifiedOn(),
                fields.teamId(),
                fields.ownerId(),
                fields.subjectText(),
                fields.description(),
                fields.sensitivity(),
                imported.status(),
                imported.openedAt(),
                imported.closedAt(),
                imported.legacyNumber(),
                run.runId(),
                run.approverId());
    }

    private static CaseException invalid(String reason) {
        return new CaseException(Kind.INVALID, reason);
    }
}
