// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.updaterequest;

import io.trailkeep.shared.access.CaseRooted;
import org.springframework.data.repository.Repository;

/**
 * Case-rooted access to {@code UPDATE_REQUEST} (ADR-0005): no inherited or derived methods, only the scoped queries
 * of {@link UpdateRequestQueries}. Requests are written with {@code EntityManager.persist} and answered by a
 * conditional bulk update.
 */
@CaseRooted
interface UpdateRequestRepository extends Repository<UpdateRequest, Long>, UpdateRequestQueries {}
