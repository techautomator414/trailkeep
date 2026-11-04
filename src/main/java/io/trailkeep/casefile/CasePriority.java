// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

/** A case's priority; stored by name in {@code CASE_RECORD.PRIORITY}. */
public enum CasePriority {
    /** The default. */
    NORMAL,
    /** Worked first; shown with a badge. */
    HIGH
}
