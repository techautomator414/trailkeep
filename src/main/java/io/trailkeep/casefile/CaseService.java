// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.user.UserPermissions;
import jakarta.validation.Valid;

/**
 * Creating and reading cases (BUILD_PLAN §4.2, §4.3). Reads go through the actor's {@code CaseScope}, so a case the
 * actor may not read is "not found", exactly like an absent one (ADR-0005). Every change is audited in the same
 * transaction. Refusals are {@link CaseException}s.
 */
public interface CaseService {

    /**
     * Who is acting.
     *
     * @param permissions the actor's permissions, rebuilt for this request
     * @param displayName the actor's display name, kept in the audit trail
     */
    record Actor(UserPermissions permissions, String displayName) {}

    /**
     * Opens a case: draws its number, initialises its SLA row from the case type's policy and its search row from
     * title and description, and records {@code CASE_CREATED}. {@code FORBIDDEN} unless the actor may create cases in
     * the team; {@code INVALID} for an inactive or unknown case type or team, an owner who holds neither Analyst nor
     * Manager in the team, or an identified-on date after today.
     *
     * @param actor the actor
     * @param fields what the actor entered
     * @return the new case's number
     */
    CaseNumber create(Actor actor, @Valid CaseFields fields);

    /**
     * One case, for its page. {@code NOT_FOUND} if the number is malformed, the case is absent, or the actor may not
     * read it.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the case
     */
    CaseView view(Actor actor, String caseNumber);
}
