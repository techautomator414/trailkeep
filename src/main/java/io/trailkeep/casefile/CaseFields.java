// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.Sensitivity;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * The fields a person enters on a case, validated before they reach {@link CaseRecord}.
 *
 * @param caseTypeId the case type
 * @param title the title; must not be sensitive (the form says so)
 * @param priority the priority
 * @param identifiedOn the day the matter was identified
 * @param teamId the owning team
 * @param ownerId the owner, who holds Analyst or Manager in the team
 * @param subjectText who or what is involved; possible PHI
 * @param description the description; possible PHI
 * @param sensitivity the sensitivity
 */
record CaseFields(
        long caseTypeId,
        String title,
        CasePriority priority,
        LocalDate identifiedOn,
        long teamId,
        long ownerId,
        @Nullable String subjectText,
        String description,
        Sensitivity sensitivity) {}
