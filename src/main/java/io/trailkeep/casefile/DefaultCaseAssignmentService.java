// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseService.Choice;
import io.trailkeep.casefile.CaseService.TeamChoice;
import io.trailkeep.security.CaseAction;
import io.trailkeep.security.CasePermissionService;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reassigns cases and changes their sensitivity. A team change is offered, and accepted, only into a team in which
 * the actor may create cases, so nobody can move a case somewhere they could not have opened it.
 */
@Service
@Transactional
class DefaultCaseAssignmentService implements CaseAssignmentService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultCaseAssignmentService.class);

    private final CaseLoader loader;
    private final CaseService cases;
    private final CasePermissionService permissions;
    private final UserDirectoryService directory;
    private final EntityManager entityManager;
    private final AuditRecorder audit;
    private final InstantSource clock;

    DefaultCaseAssignmentService(
            CaseLoader loader,
            CaseService cases,
            CasePermissionService permissions,
            UserDirectoryService directory,
            EntityManager entityManager,
            AuditRecorder audit,
            InstantSource clock) {
        this.loader = loader;
        this.cases = cases;
        this.permissions = permissions;
        this.directory = directory;
        this.entityManager = entityManager;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Reassignment reassignment(Actor actor, String caseNumber) {
        CaseRecord record = loader.load(actor.permissions(), caseNumber, CaseAction.REASSIGN);
        List<TeamChoice> teams = new ArrayList<>(cases.createChoices(actor).teams());
        long current = record.getTeamId();
        if (teams.stream().noneMatch(team -> team.id() == current)) {
            String name = directory.team(current).map(TeamSummary::name).orElse("");
            List<Choice> owners = directory.eligibleOwners(current).stream()
                    .map(owner -> new Choice(owner.userId(), owner.displayName()))
                    .toList();
            teams.addFirst(new TeamChoice(current, name, owners));
        }
        return new Reassignment(
                record.getCaseNumber(), record.getTitle(), record.getVersion(), current, record.getOwnerId(), teams);
    }

    @Override
    public boolean reassign(Actor actor, String caseNumber, long version, long teamId, long ownerId) {
        CaseRecord record = loader.load(actor.permissions(), caseNumber, CaseAction.REASSIGN);
        if (teamId != record.getTeamId() && !mayMoveInto(actor, teamId)) {
            throw new CaseException(Kind.INVALID, "may not move cases into this team");
        }
        if (!permissions.mayOwn(directory.permissionsOf(ownerId), teamId)) {
            throw new CaseException(Kind.INVALID, "the owner may not own cases of this team");
        }
        CaseAssignment after = new CaseAssignment(teamId, ownerId, record.getSensitivity());
        change(actor, record, version, after, "CASE_REASSIGNED");
        return loader.canRead(actor.permissions(), record.getCaseNumber());
    }

    @Override
    @Transactional(readOnly = true)
    public Confirmation sensitivityChange(Actor actor, String caseNumber, Sensitivity target) {
        CaseRecord record = loader.load(actor.permissions(), caseNumber, action(target));
        return new Confirmation(record.getCaseNumber(), record.getTitle(), record.getVersion());
    }

    @Override
    public void changeSensitivity(Actor actor, String caseNumber, long version, Sensitivity target) {
        CaseRecord record = loader.load(actor.permissions(), caseNumber, action(target));
        String event = target == Sensitivity.RESTRICTED ? "CASE_RESTRICTED" : "CASE_UNRESTRICTED";
        change(actor, record, version, new CaseAssignment(record.getTeamId(), record.getOwnerId(), target), event);
        if (!loader.canRead(actor.permissions(), record.getCaseNumber())) {
            // The matrix lets only people who keep reading restrict; this guards the rule against a matrix edit.
            throw new CaseException(Kind.FORBIDDEN, "the actor would no longer read the case");
        }
    }

    private boolean mayMoveInto(Actor actor, long teamId) {
        return cases.createChoices(actor).teams().stream().anyMatch(team -> team.id() == teamId);
    }

    private static CaseAction action(Sensitivity target) {
        return target == Sensitivity.RESTRICTED ? CaseAction.RESTRICT : CaseAction.UNRESTRICT;
    }

    /** Applies a checked assignment against the version the form was loaded with, and audits what changed. */
    private void change(Actor actor, CaseRecord record, long version, CaseAssignment after, String event) {
        if (record.getVersion() != version) {
            throw new CaseException(Kind.CONFLICT, "saved since the form was loaded");
        }
        AuditChanges diff = audit.diff(record.assignment(), after);
        if (diff.equals(AuditChanges.none())) {
            return;
        }
        long caseId = Objects.requireNonNull(record.getId(), "a persisted case has an id");
        long actorId = actor.permissions().userId();
        record.assign(after, actorId, clock.instant());
        try {
            entityManager.flush();
        } catch (OptimisticLockException concurrent) {
            throw new CaseException(Kind.CONFLICT, "saved concurrently");
        }
        audit.record(new AuditEntry(actorId, actor.displayName(), AuditOrigin.UI, caseId, "CASE", caseId, event, diff));
        LOG.info("Case id {}: owner, team or sensitivity changed", caseId);
    }
}
