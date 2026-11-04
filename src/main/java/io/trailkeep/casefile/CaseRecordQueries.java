// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import java.util.Optional;

/** The scoped case queries; implemented with the Criteria API in {@link CaseRecordQueriesImpl}. */
interface CaseRecordQueries {

    /**
     * One case, if the scope reads it. An unreadable case and an absent one give the same answer.
     *
     * @param scope the caller's scope
     * @param caseNumber the case number
     * @return the case, or empty
     */
    Optional<CaseRecord> findOne(CaseScope scope, CaseNumber caseNumber);

    /**
     * Whether the scope reads a case, without loading it.
     *
     * @param scope the caller's scope
     * @param caseNumber the case number
     * @return {@code true} if the case exists and the scope reads it
     */
    boolean exists(CaseScope scope, CaseNumber caseNumber);
}
