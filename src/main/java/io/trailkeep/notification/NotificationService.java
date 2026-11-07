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

    /**
     * Tells a person about something, in the caller's transaction: writes their in-app alert (from {@code
     * <id>.alert.txt}, linking to {@code path}; see {@link UserAlertService}) and queues a mail to their address
     * through {@link #enqueue}, with the deep link ({@code app.base-url} followed by {@code path}) added to the model
     * as {@code link}. A person who is unknown or disabled gets neither; one without an address gets only the alert.
     *
     * @param userId the person
     * @param templateId the mail's templates under {@code templates/notification/}
     * @param model the values the templates read, besides {@code link}; never a description or subject text
     * @param caseId the case it is about, or {@code null}
     * @param path the page it links to, an absolute path such as {@code /cases/CASE-2026-000042#comments}
     * @return {@code true} if a mail was queued
     * @throws IllegalArgumentException for a path that does not start with a single {@code /}, or as {@link #enqueue}
     */
    boolean notifyUser(long userId, String templateId, Map<String, ?> model, @Nullable Long caseId, String path);

    /**
     * Tells a person that a scheduled report is ready (BUILD_PLAN §6 "Aging report delivery"), exactly as {@link
     * #notifyUser} does for no case, with the queued mail's outbox row naming the run ({@code REPORT_RUN_ID}). The
     * mail links to the run; it never carries the report.
     *
     * @param userId the person
     * @param templateId the mail's templates under {@code templates/notification/}
     * @param model the values the templates read, besides {@code link}; never the report's lines
     * @param reportRunId the run
     * @param path the page it links to
     * @return {@code true} if a mail was queued
     */
    boolean notifyOfReport(long userId, String templateId, Map<String, ?> model, long reportRunId, String path);
}
