// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.shared.access.CaseScope;
import java.util.List;

/**
 * Reads the audit trail through a {@link CaseScope} (ADR-0005). Events with no case (logins, admin changes, exports)
 * are visible only to an unrestricted scope ({@link CaseScope#system()} or {@link CaseScope#all()}).
 */
public interface AuditTrailService {

    /**
     * The most recent events the scope may see, newest first.
     *
     * @param scope the caller's scope
     * @param limit the maximum number of events, 1 to 500
     * @return the events
     */
    List<AuditRecord> recent(CaseScope scope, int limit);

    /**
     * The events of one case, newest first; empty if the scope may not read the case.
     *
     * @param scope the caller's scope
     * @param caseId the case id
     * @param limit the maximum number of events, 1 to 500
     * @return the events
     */
    List<AuditRecord> forCase(CaseScope scope, long caseId, int limit);
}
