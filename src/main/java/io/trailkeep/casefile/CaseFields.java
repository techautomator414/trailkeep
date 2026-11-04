// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.Redacted;
import io.trailkeep.shared.Sensitivity;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * The fields a person enters on a case (BUILD_PLAN §4.2), validated before they reach {@code CaseRecord}. Also the
 * audit snapshot of a case's fields: subject and description are {@link Redacted}, so the trail holds only an HMAC.
 * The sizes match the {@code V4__case} columns; the description is capped like a comment.
 *
 * @param caseTypeId the case type, which must be active
 * @param title the title, 1 to 200 characters; must not be sensitive (the form says so)
 * @param priority the priority
 * @param identifiedOn the day the matter was identified, not in the future
 * @param teamId the owning team, which must be active
 * @param ownerId the owner, who holds Analyst or Manager in the team
 * @param subjectText who or what is involved, up to 1000 characters; possible PHI
 * @param description the description, 1 to 20 000 characters; possible PHI
 * @param sensitivity the sensitivity
 */
public record CaseFields(
        @Min(1) long caseTypeId,
        @NotBlank @Size(max = 200) String title,
        @NotNull CasePriority priority,

        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate identifiedOn,

        @Min(1) long teamId,
        @Min(1) long ownerId,
        @Redacted @Nullable @Size(max = 1000) String subjectText,
        @Redacted @NotBlank @Size(max = 20_000) String description,
        @NotNull Sensitivity sensitivity) {}
