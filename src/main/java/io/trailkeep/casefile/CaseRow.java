// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The case page's row, read in one statement by {@link CaseRecordQueries#findRow}.
 *
 * @param record the case
 * @param caseTypeName the case type's name
 * @param slaState the SLA state, or {@code null} if the case has no SLA row (only rows written outside the service)
 * @param slaDueAt the SLA due instant, or {@code null}
 */
record CaseRow(
        CaseRecord record,
        String caseTypeName,
        @Nullable SlaState slaState,
        @Nullable Instant slaDueAt) {}
