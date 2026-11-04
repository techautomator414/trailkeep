// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.user.UserPermissions;
import jakarta.validation.Valid;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * SLA policies and case types, administered by Compliance Admins only (BUILD_PLAN §3.2, §4.6). Every change is
 * audited. Every method throws {@link CaseConfigException}: {@code FORBIDDEN} unless the actor holds Compliance
 * Admin, {@code NOT_FOUND} for an unknown id, {@code CONFLICT} for a taken name; commands are validated.
 */
public interface CaseConfigService {

    /**
     * Who is acting.
     *
     * @param permissions the actor's permissions, rebuilt for this request
     * @param displayName the actor's display name, kept in the audit trail
     */
    record Actor(UserPermissions permissions, String displayName) {}

    /**
     * A stored SLA policy.
     *
     * @param id the id
     * @param values the values
     */
    record SlaPolicyView(long id, SlaPolicyCommand values) {}

    /**
     * A stored case type.
     *
     * @param id the id
     * @param values the values, outcomes in display order
     */
    record CaseTypeView(long id, CaseTypeCommand values) {}

    /**
     * All SLA policies by name.
     *
     * @param actor the actor
     * @return the policies
     */
    List<SlaPolicyView> policies(Actor actor);

    /**
     * One SLA policy.
     *
     * @param actor the actor
     * @param id the id
     * @return the policy
     */
    SlaPolicyView policy(Actor actor, long id);

    /**
     * Creates or, with an id, updates an SLA policy.
     *
     * @param actor the actor
     * @param id the policy to update, or {@code null} to create one
     * @param command the values
     * @return the policy's id
     */
    long savePolicy(Actor actor, @Nullable Long id, @Valid SlaPolicyCommand command);

    /**
     * All case types by name.
     *
     * @param actor the actor
     * @return the case types
     */
    List<CaseTypeView> caseTypes(Actor actor);

    /**
     * One case type.
     *
     * @param actor the actor
     * @param id the id
     * @return the case type
     */
    CaseTypeView caseType(Actor actor, long id);

    /**
     * Creates or, with an id, updates a case type; {@code INVALID} for an unknown policy or repeated outcomes.
     *
     * @param actor the actor
     * @param id the case type to update, or {@code null} to create one
     * @param command the values
     * @return the case type's id
     */
    long saveCaseType(Actor actor, @Nullable Long id, @Valid CaseTypeCommand command);
}
