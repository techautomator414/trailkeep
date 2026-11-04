// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The editable values of an SLA policy (BUILD_PLAN §4.6). The ranges match the {@code V3__admin} CHECKs, plus upper
 * bounds the schema leaves open.
 *
 * @param name unique name, 1 to 120 characters
 * @param anchor where the clock starts
 * @param targetDays days until the case is due, 1 to 3650
 * @param atRiskPct share of the target after which the case is at risk, 1 to 99
 * @param overdueReminderDays days between reminders once overdue, 1 to 365
 * @param escalateTo who receives escalations
 */
public record SlaPolicyCommand(
        @NotBlank @Size(max = 120) String name,
        @NotNull SlaAnchor anchor,
        @Min(1) @Max(3650) int targetDays,
        @Min(1) @Max(99) int atRiskPct,
        @Min(1) @Max(365) int overdueReminderDays,
        @NotNull EscalateTo escalateTo) {}
