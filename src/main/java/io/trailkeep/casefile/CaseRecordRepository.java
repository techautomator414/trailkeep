// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.access.CaseRooted;
import org.springframework.data.repository.Repository;

/**
 * Case-rooted access to {@code CASE_RECORD} (ADR-0005): no inherited or derived methods, only the scoped queries of
 * {@link CaseRecordQueries}.
 */
@CaseRooted
interface CaseRecordRepository extends Repository<CaseRecord, Long>, CaseRecordQueries {}
