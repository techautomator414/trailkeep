// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.Sensitivity;

/**
 * Who a case belongs to and who may see it: the audit snapshot of {@code CASE_REASSIGNED}, {@code CASE_RESTRICTED}
 * and {@code CASE_UNRESTRICTED}, of which only the changed components are recorded.
 *
 * @param teamId the owning team
 * @param ownerId the owner
 * @param sensitivity the sensitivity
 */
record CaseAssignment(long teamId, long ownerId, Sensitivity sensitivity) {}
