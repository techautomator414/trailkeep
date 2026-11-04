// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.user.GlobalRole;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@Transactional
class DefaultCaseConfigService implements CaseConfigService {

    private final SlaPolicyRepository policies;
    private final CaseTypeRepository caseTypes;
    private final CaseTypeOutcomeRepository outcomes;
    private final AuditRecorder audit;

    DefaultCaseConfigService(
            SlaPolicyRepository policies,
            CaseTypeRepository caseTypes,
            CaseTypeOutcomeRepository outcomes,
            AuditRecorder audit) {
        this.policies = policies;
        this.caseTypes = caseTypes;
        this.outcomes = outcomes;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SlaPolicyView> policies(Actor actor) {
        requireComplianceAdmin(actor);
        return policies.findAllByOrderByNameAsc().stream()
                .map(DefaultCaseConfigService::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SlaPolicyView policy(Actor actor, long id) {
        requireComplianceAdmin(actor);
        return view(findPolicy(id));
    }

    @Override
    public long savePolicy(Actor actor, @Nullable Long id, SlaPolicyCommand command) {
        requireComplianceAdmin(actor);
        requireFreeName(policies.findByName(command.name().strip()).map(SlaPolicy::getId), id);
        SlaPolicy policy = id == null ? null : findPolicy(id);
        SlaPolicyCommand before = policy == null ? null : policy.toCommand();
        if (policy == null) {
            policy = policies.save(new SlaPolicy(command));
        } else {
            policy.apply(command);
        }
        long savedId = id(policy.getId());
        record(
                actor,
                "SLA_POLICY",
                savedId,
                before == null ? "CREATE" : "UPDATE",
                audit.diff(before, policy.toCommand()));
        return savedId;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseTypeView> caseTypes(Actor actor) {
        requireComplianceAdmin(actor);
        return caseTypes.findAllByOrderByNameAsc().stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CaseTypeView caseType(Actor actor, long id) {
        requireComplianceAdmin(actor);
        return view(findCaseType(id));
    }

    @Override
    public long saveCaseType(Actor actor, @Nullable Long id, CaseTypeCommand command) {
        requireComplianceAdmin(actor);
        requireFreeName(caseTypes.findByName(command.name().strip()).map(CaseType::getId), id);
        if (policies.findById(command.slaPolicyId()).isEmpty()) {
            throw new CaseConfigException(Kind.INVALID, "unknown SLA policy");
        }
        List<String> cleanOutcomes =
                command.outcomes().stream().map(String::strip).toList();
        if (new HashSet<>(cleanOutcomes).size() != cleanOutcomes.size()) {
            throw new CaseConfigException(Kind.INVALID, "outcomes must be distinct");
        }
        CaseType caseType = id == null ? null : findCaseType(id);
        final CaseTypeCommand before = caseType == null ? null : view(caseType).values();
        if (caseType == null) {
            caseType = caseTypes.save(new CaseType(command));
        } else {
            caseType.apply(command);
        }
        long savedId = id(caseType.getId());
        outcomes.deleteAllOfType(savedId);
        for (int i = 0; i < cleanOutcomes.size(); i++) {
            outcomes.save(new CaseTypeOutcome(savedId, cleanOutcomes.get(i), i));
        }
        CaseTypeCommand after = new CaseTypeCommand(
                caseType.getName(), command.slaPolicyId(), command.retentionYears(), command.active(), cleanOutcomes);
        record(actor, "CASE_TYPE", savedId, before == null ? "CREATE" : "UPDATE", audit.diff(before, after));
        return savedId;
    }

    private static void requireComplianceAdmin(Actor actor) {
        if (!actor.permissions().has(GlobalRole.COMPLIANCE_ADMIN)) {
            throw new CaseConfigException(Kind.FORBIDDEN, "Compliance Admin only");
        }
    }

    private static void requireFreeName(Optional<@Nullable Long> holder, @Nullable Long id) {
        if (holder.isPresent() && !Objects.equals(holder.get(), id)) {
            throw new CaseConfigException(Kind.CONFLICT, "the name is taken");
        }
    }

    private SlaPolicy findPolicy(long id) {
        return policies.findById(id).orElseThrow(() -> new CaseConfigException(Kind.NOT_FOUND, "no such policy"));
    }

    private CaseType findCaseType(long id) {
        return caseTypes.findById(id).orElseThrow(() -> new CaseConfigException(Kind.NOT_FOUND, "no such type"));
    }

    private static SlaPolicyView view(SlaPolicy policy) {
        return new SlaPolicyView(id(policy.getId()), policy.toCommand());
    }

    private CaseTypeView view(CaseType type) {
        long typeId = id(type.getId());
        List<String> names = outcomes.findByIdCaseTypeIdOrderBySortOrderAsc(typeId).stream()
                .map(CaseTypeOutcome::outcome)
                .toList();
        return new CaseTypeView(
                typeId,
                new CaseTypeCommand(
                        type.getName(), type.getSlaPolicyId(), type.getRetentionYears(), type.isActive(), names));
    }

    private static long id(@Nullable Long id) {
        if (id == null) {
            throw new IllegalStateException("a persisted entity has an id");
        }
        return id;
    }

    private void record(Actor actor, String type, long id, String action, AuditChanges changes) {
        if (!changes.equals(AuditChanges.none())) {
            long by = actor.permissions().userId();
            audit.record(new AuditEntry(by, actor.displayName(), AuditOrigin.UI, null, type, id, action, changes));
        }
    }
}
