// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

/** Who receives SLA escalations (BUILD_PLAN §3.2); stored by name in {@code SLA_POLICY.ESCALATE_TO}. */
public enum EscalateTo {
    /** The case owner only. */
    OWNER,
    /** The owner and every Manager of the case's team. */
    OWNER_AND_MANAGERS
}
