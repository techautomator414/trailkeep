// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.security.CaseAction;
import io.trailkeep.security.CaseFacts;
import io.trailkeep.security.CasePermissionService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * Creates cases, reads them for their page and edits them. The case row, its SLA row and its search row are written
 * together with the audit event in one transaction; the side tables are persisted directly because no read goes through
 * them here.
 */
@Service
@Validated
@Transactional
@CaseAccessPath
class DefaultCaseService implements CaseService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultCaseService.class);

    private final CaseRecordRepository cases;
    private final CaseTypeRepository caseTypes;
    private final SlaPolicyRepository policies;
    private final CaseNumberJdbcDao numbers;
    private final EntityManager entityManager;
    private final CaseScopeService scopes;
    private final CaseLoader loader;
    private final CaseSearchWriter search;
    private final CasePermissionService permissions;
    private final UserDirectoryService directory;
    private final AuditRecorder audit;
    private final InstantSource clock;
    private final ZoneId zone;
    private final String prefix;

    DefaultCaseService(
            CaseRecordRepository cases,
            CaseTypeRepository caseTypes,
            SlaPolicyRepository policies,
            CaseNumberJdbcDao numbers,
            EntityManager entityManager,
            CaseScopeService scopes,
            CaseLoader loader,
            CaseSearchWriter search,
            CasePermissionService permissions,
            UserDirectoryService directory,
            AuditRecorder audit,
            InstantSource clock,
            AppProperties app,
            CaseProperties properties) {
        this.cases = cases;
        this.caseTypes = caseTypes;
        this.policies = policies;
        this.numbers = numbers;
        this.entityManager = entityManager;
        this.scopes = scopes;
        this.loader = loader;
        this.search = search;
        this.permissions = permissions;
        this.directory = directory;
        this.audit = audit;
        this.clock = clock;
        this.zone = app.timezone();
        this.prefix = properties.numberPrefix();
    }

    @Override
    public CaseNumber create(Actor actor, CaseFields entered) {
        CaseFields fields = normalized(entered);
        CaseFacts facts = new CaseFacts(fields.teamId(), fields.ownerId(), fields.sensitivity(), CaseStatus.OPEN);
        if (!permissions.isAllowed(actor.permissions(), facts, CaseAction.CREATE)) {
            throw new CaseException(Kind.FORBIDDEN, "may not create cases in this team");
        }
        Instant now = clock.instant();
        SlaPolicyCommand policy = checkReferences(fields, now);
        CaseNumber number = new CaseNumber(prefix, now.atZone(zone).getYear(), numbers.next());
        long actorId = actor.permissions().userId();
        CaseRecord record = new CaseRecord(number, fields, actorId, now);
        entityManager.persist(record);
        long caseId = id(record.getId());
        SlaClock sla = SlaClock.start(policy, fields.identifiedOn(), now, zone);
        entityManager.persist(new CaseSla(caseId, sla.atRiskAt(), sla.dueAt()));
        entityManager.persist(new CaseSearch(caseId, fields.title() + "\n" + fields.description(), now));
        audit.record(new AuditEntry(
                actorId,
                actor.displayName(),
                AuditOrigin.UI,
                caseId,
                "CASE",
                caseId,
                "CASE_CREATED",
                audit.diff(null, fields)));
        LOG.info("Case {} created in team {}", number, fields.teamId());
        return number;
    }

    @Override
    @Transactional(readOnly = true)
    public CreateChoices createChoices(Actor actor) {
        List<Choice> types = caseTypes.findAllByOrderByNameAsc().stream()
                .filter(CaseType::isActive)
                .map(type -> new Choice(id(type.getId()), type.getName()))
                .toList();
        List<TeamChoice> teams = actor.permissions().teamRoles().keySet().stream()
                .filter(team -> permissions.isAllowed(
                        actor.permissions(),
                        new CaseFacts(team, null, Sensitivity.STANDARD, CaseStatus.OPEN),
                        CaseAction.CREATE))
                .flatMap(team -> directory.team(team).stream())
                .filter(TeamSummary::active)
                .sorted(Comparator.comparing(TeamSummary::name))
                .map(team -> new TeamChoice(
                        team.id(),
                        team.name(),
                        directory.eligibleOwners(team.id()).stream()
                                .map(owner -> new Choice(owner.userId(), owner.displayName()))
                                .toList()))
                .toList();
        return new CreateChoices(types, teams);
    }

    @Override
    public Set<TitleWarning> titleWarnings(String title) {
        return TitleWarning.in(title);
    }

    @Override
    @Transactional(readOnly = true)
    public CaseView view(Actor actor, String caseNumber) {
        CaseNumber number = CaseLoader.parse(caseNumber);
        CaseRow row = cases.findRow(scopes.forUser(actor.permissions()), number)
                .orElseThrow(() -> new CaseException(Kind.NOT_FOUND, "case not found"));
        CaseRecord record = row.record();
        String team = directory.team(record.getTeamId()).map(TeamSummary::name).orElse("");
        String owner = directory.displayName(record.getOwnerId()).orElse("");
        return new CaseView(
                number,
                record.getTitle(),
                record.getStatus(),
                record.getSensitivity(),
                record.getPriority(),
                row.caseTypeName(),
                team,
                owner,
                record.getIdentifiedOn(),
                LocalDate.ofInstant(record.getOpenedAt(), zone),
                record.getSubjectText(),
                record.getDescription(),
                sla(row.slaState(), row.slaDueAt()),
                record.getVersion(),
                allowed(actor, record, CaseAction.EDIT),
                allowed(actor, record, CaseAction.REASSIGN),
                allowed(actor, record, CaseAction.RESTRICT),
                allowed(actor, record, CaseAction.UNRESTRICT),
                allowed(actor, record, CaseAction.COMMENT),
                allowed(actor, record, CaseAction.PIN));
    }

    @Override
    @Transactional(readOnly = true)
    public EditableCase editable(Actor actor, String caseNumber) {
        CaseRecord record = loader.load(actor.permissions(), caseNumber, CaseAction.EDIT);
        return new EditableCase(
                record.getCaseNumber(),
                record.changes(),
                record.getVersion(),
                directory.displayName(record.getUpdatedBy()).orElse(""),
                LocalDateTime.ofInstant(record.getUpdatedAt(), zone).truncatedTo(ChronoUnit.MINUTES));
    }

    @Override
    public void edit(Actor actor, String caseNumber, int version, CaseChanges entered) {
        CaseRecord record = loader.load(actor.permissions(), caseNumber, CaseAction.EDIT);
        if (record.getVersion() != version) {
            throw new CaseException(Kind.CONFLICT, "saved since the form was loaded");
        }
        CaseChanges changes = normalized(entered);
        AuditChanges diff = audit.diff(record.changes(), changes);
        if (diff.equals(AuditChanges.none())) {
            return;
        }
        long actorId = actor.permissions().userId();
        Instant now = clock.instant();
        record.apply(changes, actorId, now);
        try {
            entityManager.flush();
        } catch (OptimisticLockException concurrent) {
            throw new CaseException(Kind.CONFLICT, "saved concurrently");
        }
        // After the flush, so the comment query that follows cannot raise the version conflict outside the catch.
        search.rebuild(actor.permissions(), record, now);
        long caseId = id(record.getId());
        audit.record(new AuditEntry(
                actorId, actor.displayName(), AuditOrigin.UI, caseId, "CASE", caseId, "CASE_UPDATED", diff));
        LOG.info("Case id {} edited", caseId);
    }

    private boolean allowed(Actor actor, CaseRecord record, CaseAction action) {
        return permissions.isAllowed(actor.permissions(), CaseLoader.facts(record), action);
    }

    /** Checks what the form offers as choices, and returns the case type's policy. */
    private SlaPolicyCommand checkReferences(CaseFields fields, Instant now) {
        if (fields.identifiedOn().isAfter(LocalDate.ofInstant(now, zone))) {
            throw new CaseException(Kind.INVALID, "identified in the future");
        }
        CaseType type = caseTypes
                .findById(fields.caseTypeId())
                .filter(CaseType::isActive)
                .orElseThrow(() -> new CaseException(Kind.INVALID, "unknown or inactive case type"));
        if (!directory.team(fields.teamId()).map(TeamSummary::active).orElse(false)) {
            throw new CaseException(Kind.INVALID, "unknown or inactive team");
        }
        if (!permissions.mayOwn(directory.permissionsOf(fields.ownerId()), fields.teamId())) {
            throw new CaseException(Kind.INVALID, "the owner may not own cases of this team");
        }
        return policies.findById(type.getSlaPolicyId())
                .orElseThrow(() -> new IllegalStateException("a case type has a policy"))
                .toCommand();
    }

    private CaseView.Sla sla(@Nullable SlaState state, @Nullable Instant dueAt) {
        if (state == null || dueAt == null) {
            return new CaseView.Sla(SlaState.NOT_APPLICABLE, null, 0);
        }
        LocalDate dueOn = LocalDate.ofInstant(dueAt, zone);
        return new CaseView.Sla(
                state, dueOn, ChronoUnit.DAYS.between(LocalDate.ofInstant(clock.instant(), zone), dueOn));
    }

    private static CaseFields normalized(CaseFields fields) {
        String subject = fields.subjectText();
        return new CaseFields(
                fields.caseTypeId(),
                fields.title().strip(),
                fields.priority(),
                fields.identifiedOn(),
                fields.teamId(),
                fields.ownerId(),
                subject == null || subject.isBlank() ? null : subject.strip(),
                fields.description().strip(),
                fields.sensitivity());
    }

    private static CaseChanges normalized(CaseChanges changes) {
        String subject = changes.subjectText();
        return new CaseChanges(
                changes.title().strip(),
                changes.priority(),
                subject == null || subject.isBlank() ? null : subject.strip(),
                changes.description().strip());
    }

    private static long id(@Nullable Long id) {
        if (id == null) {
            throw new IllegalStateException("a persisted case has an id");
        }
        return id;
    }
}
