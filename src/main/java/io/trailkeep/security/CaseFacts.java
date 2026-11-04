// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import org.jspecify.annotations.Nullable;

/**
 * What a permission decision needs to know about a case.
 *
 * @param teamId the case's team
 * @param ownerId the owner's user id, or {@code null} when none is set
 * @param sensitivity the case's sensitivity
 * @param status the case's status
 */
public record CaseFacts(long teamId, @Nullable Long ownerId, Sensitivity sensitivity, CaseStatus status) {}
