// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseScope;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The audit read path; the one class allowed to hold {@link AuditEventRepository}. */
@Service
@CaseAccessPath
@Transactional(readOnly = true)
class DefaultAuditTrailService implements AuditTrailService {

    static final int MAX_LIMIT = 500;

    private final AuditEventRepository repository;
    private final StandingEventQueries standing;

    DefaultAuditTrailService(AuditEventRepository repository, StandingEventQueries standing) {
        this.repository = repository;
        this.standing = standing;
    }

    @Override
    public List<AuditRecord> recent(CaseScope scope, int limit) {
        return toRecords(repository.findRecent(scope, checked(limit)));
    }

    @Override
    public List<AuditRecord> forCase(CaseScope scope, long caseId, int limit) {
        return toRecords(repository.findByCase(scope, caseId, checked(limit)));
    }

    @Override
    public AuditPage pageForCase(CaseScope scope, long caseId, Set<String> actions, int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        Set<String> kept = Set.copyOf(actions);
        long total = repository.countByCase(scope, caseId, kept);
        return new AuditPage(
                total == 0 ? List.of() : toRecords(repository.pageByCase(scope, caseId, kept, offset, checked(limit))),
                total);
    }

    @Override
    public List<StandingEvent> standing(String entityType, String action, Set<String> clearedBy) {
        return standing.standing(entityType, action, clearedBy);
    }

    private static int checked(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1 to " + MAX_LIMIT);
        }
        return limit;
    }

    private static List<AuditRecord> toRecords(List<AuditEvent> events) {
        return events.stream().map(AuditEvent::toRecord).toList();
    }
}
