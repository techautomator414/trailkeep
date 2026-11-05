// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import java.time.LocalDateTime;

/**
 * An open case as the edit form shows it, and the last change a stale save conflicts with (BUILD_PLAN §4.3:
 * "This case was updated by <i>name</i> at <i>time</i>").
 *
 * @param number the case number
 * @param current the fields as saved now
 * @param version the version the form saves against
 * @param updatedBy the display name of whoever saved last
 * @param updatedAt when they saved, in {@code app.timezone}, to the minute
 */
public record EditableCase(
        CaseNumber number, CaseChanges current, int version, String updatedBy, LocalDateTime updatedAt) {}
