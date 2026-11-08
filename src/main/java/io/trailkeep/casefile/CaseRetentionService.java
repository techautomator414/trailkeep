// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Archiving, for the {@code retention} module's archive job (BUILD_PLAN §4.6, WP-24): a closed case not on legal hold
 * becomes {@code ARCHIVED} once its case type's {@code RETENTION_YEARS} have passed since it closed (the retention
 * clock starts at the approval that closed it; a reopen clears {@code CLOSED_AT} and restarts it). An archived case is
 * read-only through the permission matrix, cannot be reopened, and is hidden from the default lists, search and the
 * dashboard; its {@code PURGE_AFTER} is the archive instant plus the purge grace.
 */
public interface CaseRetentionService {

    /**
     * The cases due for archiving at {@code now}, by id: closed, not on legal hold, closed at least their case type's
     * retention years before {@code now} (calendar years in UTC).
     *
     * @param now the job's instant
     * @return the ids, ascending
     */
    List<Long> dueForArchive(Instant now);

    /**
     * Archives those of {@code caseIds} that are still closed and not on legal hold, in one transaction (the caller's,
     * or its own when called outside one):
     * {@code STATUS = ARCHIVED}, {@code ARCHIVED_AT = now}, {@code PURGE_AFTER = now + grace}, the version moved on
     * (a form loaded before is refused with Reload), and {@code CASE_ARCHIVED} audited for each with origin {@code
     * SCHEDULER}. A case reopened or put on hold meanwhile is left as it is.
     *
     * @param caseIds the cases, from {@link #dueForArchive}
     * @param now the job's instant
     * @param grace how long an archived case is kept before it may be purged
     * @return how many were archived
     */
    int archive(Collection<Long> caseIds, Instant now, Duration grace);
}
