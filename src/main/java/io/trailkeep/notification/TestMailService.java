// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import io.trailkeep.user.UserPermissions;
import java.util.List;

/**
 * "Send me a test" from the System screen (WP-25 part e, BUILD_PLAN §4.6 "Email wording"): a System Admin or a
 * Compliance Admin has one of the real mail templates rendered with sample values and queued to their own address,
 * so wording overrides in {@code messages_*.properties} and the mail relay can be checked end to end. There is no
 * address field: the recipient is always the actor. It goes through the outbox like any mail, is audited, and is
 * limited to one per person every {@link #INTERVAL_MINUTES} minutes, from the audit trail, across replicas.
 */
public interface TestMailService {

    /** The minutes a person waits between two test mails. */
    int INTERVAL_MINUTES = 5;

    /** The templates a test may use: every mail Trailkeep sends. */
    List<String> TEMPLATES = List.of(
            "case-reopened",
            "closure-decided",
            "closure-reminder",
            "closure-requested",
            "comment-added",
            "report-ready",
            "sla-at-risk",
            "sla-overdue",
            "sla-overdue-repeat",
            "update-reminder",
            "update-requested");

    /** What happened to a request for a test mail. */
    enum Outcome {
        /** Queued to the actor's address. */
        QUEUED,
        /** The actor asked within the interval; nothing was queued. */
        TOO_SOON,
        /** The actor has no address from the identity provider; nothing was queued. */
        NO_ADDRESS
    }

    /**
     * Queues a test of one template to the actor's own address, in the caller's transaction, and audits {@code
     * TEST_MAIL_QUEUED} with the template's id (never the address).
     *
     * @param actor the person asking, with permissions rebuilt for this request
     * @param actorName their name, as the audit event keeps it
     * @param templateId one of {@link #TEMPLATES}
     * @return queued, too soon or no address
     * @throws io.trailkeep.shared.DomainException 403 unless the actor is a System Admin or a Compliance Admin, 422
     *     for a template not in {@link #TEMPLATES}
     */
    Outcome sendTest(UserPermissions actor, String actorName, String templateId);
}
