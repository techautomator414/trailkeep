// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared;

/** A case's sensitivity (BUILD_PLAN §3.3); comments and attachments inherit it. */
public enum Sensitivity {
    /** Readable by every member of the case's team. */
    STANDARD,
    /** Readable only by the owner, the team's Managers and Compliance Admins. */
    RESTRICTED
}
