// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Sends email through the transactional outbox (BUILD_PLAN §4.7, §6 "Outbox dispatcher", IMPLEMENTATION_PLAN A.2 #24).
 * A module that changes something worth a mail calls {@link #enqueue} inside its own transaction: the mail is rendered
 * and written as a pending outbox row in that transaction, so the change and its mail commit, or roll back, together.
 * Nothing here talks to a mail server; the dispatcher does, later and outside any transaction, and delivery is
 * at-least-once. Templates carry a deep link and never a case's description or subject text (§9).
 */
public interface NotificationService {

    /** The longest recipient address, in characters (RFC 5321's path limit). */
    int MAX_RECIPIENT = 320;

    /**
     * Queues one email in the caller's transaction.
     *
     * @param recipient the address to send to
     * @param templateId the mail's templates under {@code templates/notification/}, such as {@code comment-added}
     * @param model the values the templates read; never a description or subject text
     * @param caseId the case the mail is about, or {@code null} for mail about no case
     * @return the outbox row's id
     * @throws IllegalArgumentException for a blank or over-long recipient, an unknown template id or a subject the
     *     templates rendered over 200 characters or across lines
     * @throws org.springframework.transaction.IllegalTransactionStateException when called outside a transaction
     */
    long enqueue(String recipient, String templateId, Map<String, ?> model, @Nullable Long caseId);
}
