// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues a test mail to the actor's own address. The sample values are synthetic: a case number of zeros, and a
 * title, person, team and outcome from {@code tk.system.test.sample.*}. The interval is kept per replica in memory:
 * enough to stop the button being used to flood the actor's own mailbox, without a table for it.
 */
@Service
@Transactional
class DefaultTestMailService implements TestMailService {

    private static final String SAMPLE = "tk.system.test.sample.";

    private final NotificationService notifications;
    private final UserDirectoryService directory;
    private final AuditRecorder audit;
    private final MessageSource messages;
    private final InstantSource clock;
    private final ZoneId zone;
    private final String baseUrl;
    private final Map<Long, Instant> lastSent = new ConcurrentHashMap<>();

    DefaultTestMailService(
            NotificationService notifications,
            UserDirectoryService directory,
            AuditRecorder audit,
            MessageSource messages,
            InstantSource clock,
            AppProperties app) {
        this.notifications = notifications;
        this.directory = directory;
        this.audit = audit;
        this.messages = messages;
        this.clock = clock;
        this.zone = app.timezone();
        String base = app.baseUrl().toString();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** What the audit trail keeps of a test mail: the template only, never the address. */
    record Queued(String templateId) {}

    @Override
    public Outcome sendTest(UserPermissions actor, String actorName, String templateId) {
        if (Collections.disjoint(actor.globalRoles(), List.of(GlobalRole.SYSTEM_ADMIN, GlobalRole.COMPLIANCE_ADMIN))) {
            throw new Refused(DomainException.Kind.FORBIDDEN, "test mail is for System Admins and Compliance Admins");
        }
        if (!TEMPLATES.contains(templateId)) {
            throw new Refused(DomainException.Kind.INVALID, "not a mail template");
        }
        long userId = actor.userId();
        Instant now = clock.instant();
        Instant last = lastSent.get(userId);
        if (last != null && Duration.between(last, now).toMinutes() < INTERVAL_MINUTES) {
            return Outcome.TOO_SOON;
        }
        Optional<String> address = directory.mailAddress(userId);
        if (address.isEmpty()) {
            return Outcome.NO_ADDRESS;
        }
        notifications.enqueue(address.get(), templateId, sample(now), null);
        lastSent.put(userId, now);
        audit.record(new AuditEntry(
                userId,
                actorName,
                AuditOrigin.UI,
                null,
                "USER",
                userId,
                "TEST_MAIL_QUEUED",
                audit.diff(null, new Queued(templateId))));
        return Outcome.QUEUED;
    }

    /** Every value any template reads, synthetic. */
    private Map<String, Object> sample(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        return Map.ofEntries(
                Map.entry("caseNumber", "CASE-0000-000000"),
                Map.entry("title", text("title")),
                Map.entry("link", baseUrl + "/admin/system"),
                Map.entry("reopener", text("person")),
                Map.entry("decider", text("person")),
                Map.entry("requester", text("person")),
                Map.entry("commenter", text("person")),
                Map.entry("verbKey", "tk.mail.closure-decided.verb.APPROVED"),
                Map.entry("outcome", text("outcome")),
                Map.entry("team", text("team")),
                Map.entry("asOf", today.toString()),
                Map.entry("due", today.plusDays(3).toString()),
                Map.entry("days", "3"));
    }

    private String text(String key) {
        return messages.getMessage(SAMPLE + key, null, Locale.ENGLISH);
    }

    /** A refused request: not an admin, or a template that is not offered. */
    static final class Refused extends DomainException {

        private static final long serialVersionUID = 1L;

        Refused(Kind kind, String message) {
            super(kind, message);
        }
    }
}
