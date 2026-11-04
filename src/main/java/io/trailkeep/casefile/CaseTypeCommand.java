// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The editable values of a case type (BUILD_PLAN §4.6).
 *
 * @param name unique name, 1 to 120 characters
 * @param slaPolicyId the SLA policy every case of this type follows
 * @param retentionYears years after closure before the case is archived, 0 to 100
 * @param active whether new cases may use the type
 * @param outcomes the allowed closure outcomes in display order: 1 to 20 distinct values of 1 to 40 characters
 */
public record CaseTypeCommand(
        @NotBlank @Size(max = 120) String name,
        @Min(1) long slaPolicyId,
        @Min(0) @Max(100) int retentionYears,
        boolean active,
        @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 40) String> outcomes) {

    /** Copies the outcome list. */
    public CaseTypeCommand {
        outcomes = List.copyOf(outcomes);
    }
}
