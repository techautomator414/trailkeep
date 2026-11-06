// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.shared.access.CaseScope;
import java.util.List;
import java.util.Set;

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

    /**
     * One page of the events of one case, newest first, for a history view: {@link #forCase} cannot skip, so it cannot
     * page. Empty, with a total of zero, if the scope may not read the case.
     *
     * @param scope the caller's scope
     * @param caseId the case id
     * @param actions the actions to keep, such as {@code COMMENT_ADDED}; empty keeps every action
     * @param offset how many of the newest matching events to skip
     * @param limit the page size, 1 to 500
     * @return the page and how many events match in all
     */
    AuditPage pageForCase(CaseScope scope, long caseId, Set<String> actions, int offset, int limit);

    /**
     * One page of audit events.
     *
     * @param records the events of the page, newest first
     * @param total how many events match in all, on every page
     */
    record AuditPage(List<AuditRecord> records, long total) {

        /**
         * Copies the list, so the record holds nothing mutable.
         *
         * @param records the events of the page
         * @param total how many events match in all
         */
        public AuditPage {
            records = List.copyOf(records);
        }
    }
}
