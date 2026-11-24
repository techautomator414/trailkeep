// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.user.UserPermissions;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The audited re-send of mail that failed for good (#318): lists the {@code FAILED} outbox rows and puts chosen ones
 * back to {@code PENDING}, which RUNBOOK §8.2 used to do with unaudited SQL. For Compliance Admins only; a System Admin
 * is refused as everywhere that screen's data is case-adjacent, and a row about a case the scope cannot read is neither
 * listed nor re-queued, exactly as if it did not exist.
 */
public interface OutboxResendService {

    /** The most rows listed, and the most one re-queue takes: one dispatcher claim's worth. */
    int MAX_BATCH = 50;

    /**
     * One failed mail as the screen shows it.
     *
     * @param id the outbox row
     * @param recipient the address with its local part masked, never the whole address
     * @param templateId the template the mail was rendered from
     * @param caseNumber the case it is about, or {@code null} for none
     * @param attempts the attempts made
     * @param lastError the relay's reply with any address in it masked, or {@code null}
     * @param failedAt when the last attempt ended, read from {@code NEXT_ATTEMPT_AT}: the table has no failed-at column
     *     and the dispatcher sets that column to the time of the final failure
     */
    record FailedMail(
            long id,
            String recipient,
            String templateId,
            @Nullable String caseNumber,
            int attempts,
            @Nullable String lastError,
            Instant failedAt) {}

    /**
     * The failed mails the scope may see, oldest first.
     *
     * @param total how many there are
     * @param mails the first {@link #MAX_BATCH}
     */
    record Failed(long total, List<FailedMail> mails) {

        /**
         * Copies the list.
         *
         * @param total how many there are
         * @param mails the first {@link #MAX_BATCH}
         */
        public Failed {
            mails = List.copyOf(mails);
        }
    }

    /**
     * What a re-queue did.
     *
     * @param requeued rows moved from {@code FAILED} to {@code PENDING}, each audited
     * @param skipped rows asked for that were not failed any more, were not found, or are not the scope's to see
     */
    record Result(int requeued, int skipped) {}

    /**
     * Lists the failed mails.
     *
     * @param actor the person asking, with permissions rebuilt for this request
     * @param scope the cases the person may read
     * @return the mails
     * @throws io.trailkeep.shared.DomainException 403 unless the actor is a Compliance Admin
     */
    Failed failed(UserPermissions actor, CaseScope scope);

    /**
     * Re-queues the chosen mails and writes one {@code MAIL_REQUEUED} audit event for each, in the same transaction.
     * Idempotent: a row that is no longer {@code FAILED} is skipped, so of two admins re-queuing the same mail exactly
     * one moves it and one audit event exists.
     *
     * @param actor the person asking, with permissions rebuilt for this request
     * @param actorName their name, as the audit event keeps it
     * @param scope the cases the person may read
     * @param ids the outbox rows, at most {@link #MAX_BATCH}
     * @return what happened
     * @throws io.trailkeep.shared.DomainException 403 unless the actor is a Compliance Admin, 422 for more than
     *     {@link #MAX_BATCH} rows
     */
    Result requeue(UserPermissions actor, String actorName, CaseScope scope, Collection<Long> ids);
}
