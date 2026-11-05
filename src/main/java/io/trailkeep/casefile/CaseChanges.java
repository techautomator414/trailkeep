// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.Redacted;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * The fields Edit changes on an open case (BUILD_PLAN §4.3), validated like {@link CaseFields}, and the audit
 * snapshot of an edit: subject and description are {@link Redacted}. Owner and team change only by reassignment,
 * sensitivity only by restricting; case type and identified-on day are fixed because they anchor the SLA clock
 * (ADR-0007).
 *
 * @param title the title, 1 to 200 characters; must not be sensitive
 * @param priority the priority
 * @param subjectText who or what is involved, up to 1000 characters; possible PHI
 * @param description the description, 1 to 20 000 characters; possible PHI
 */
public record CaseChanges(
        @NotBlank @Size(max = 200) String title,
        @NotNull CasePriority priority,
        @Redacted @Nullable @Size(max = 1000) String subjectText,
        @Redacted @NotBlank @Size(max = 20_000) String description) {}
