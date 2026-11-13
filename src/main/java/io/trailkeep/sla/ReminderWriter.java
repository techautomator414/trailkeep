// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.trailkeep.casefile.CaseScopeService;
import io.trailkeep.casefile.CaseSlaService;
import io.trailkeep.casefile.CaseSlaService.OverdueCase;
import io.trailkeep.casefile.CaseSlaService.SlaCase;
import io.trailkeep.closure.ClosureService;
import io.trailkeep.closure.ClosureService.AwaitingDecision;
import io.trailkeep.notification.NotificationService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.updaterequest.UpdateRequestService;
import io.trailkeep.updaterequest.UpdateRequestService.DueReminder;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One page of the reminders tick in one transaction (BUILD_PLAN §6 "Reminders tick"): the reminders, their ledger rows
 * and, for an update request, its {@code LAST_REMINDED_AT} commit together. A reminder's ledger row is keyed by its
 * request and the UTC day of the tick, so a restart or a second tick never repeats one. Nobody who no longer reads the
 * case is reminded about it. Never the request's message or the closure's summary.
 */
@Service
@Transactional
class ReminderWriter {

    private static final String LAST = "SELECT MAX(l.recordedAt) FROM AlertLedger l"
            + " WHERE l.caseId = :caseId AND l.alertType = :type AND l.periodKey LIKE :request";
    private static final String TOLD = "SELECT COUNT(l) FROM AlertLedger l WHERE l.caseId = :caseId"
            + " AND l.alertType = :type AND l.slaCycle = :cycle AND l.periodKey = :period";

    private final UpdateRequestService updates;
    private final ClosureService closures;
    private final CaseSlaService slas;
    private final CaseScopeService scopes;
    private final NotificationService notifications;
    private final UserDirectoryService directory;
    private final SlaChunkWriter sla;
    private final EntityManager entityManager;
    private final ZoneId zone;

    ReminderWriter(
            UpdateRequestService updates,
            ClosureService closures,
            CaseSlaService slas,
            CaseScopeService scopes,
            NotificationService notifications,
            UserDirectoryService directory,
            SlaChunkWriter sla,
            EntityManager entityManager,
            AppProperties app) {
        this.updates = updates;
        this.closures = closures;
        this.slas = slas;
        this.scopes = scopes;
        this.notifications = notifications;
        this.directory = directory;
        this.sla = sla;
        this.entityManager = entityManager;
        this.zone = app.timezone();
    }

    /**
     * What one page did.
     *
     * @param lastId the last id read, where the next page starts
     * @param read how many candidates were read
     * @param reminded how many people were reminded
     */
    record Page(long lastId, int read, int reminded) {}

    /**
     * Reminds the recipients of one page of open update requests last asked or reminded at or before {@code cutoff}.
     *
     * @param cutoff the latest ask or reminder that is due again
     * @param afterId where the page starts
     * @param now the tick's instant
     * @return what it did
     */
    Page updateRequests(Instant cutoff, long afterId, Instant now) {
        List<DueReminder> due = updates.dueForReminder(cutoff, afterId, SlaEvaluator.CHUNK);
        Map<Long, SlaCase> cases =
                slas.about(due.stream().map(DueReminder::caseId).toList());
        int reminded = 0;
        for (DueReminder r : due) {
            SlaCase about = cases.get(r.caseId());
            if (about != null && reads(r.recipientId(), about.number()) && updates.reminded(r.id(), now)) {
                remember(r.caseId(), AlertType.UPDATE_REMINDER, about.cycle(), r.id(), now);
                notifications.notifyUser(
                        r.recipientId(),
                        "update-reminder",
                        model(about, r.requestedBy(), ""),
                        r.caseId(),
                        "/cases/" + about.number() + "#comments");
                reminded++;
            }
        }
        return new Page(due.isEmpty() ? afterId : due.getLast().id(), due.size(), reminded);
    }

    /**
     * Reminds the approvers of one page of pending closure requests made at or before {@code cutoff} and not reminded
     * since it (the ledger holds the last reminder; there is no column for it).
     *
     * @param cutoff the latest request or reminder that is due again
     * @param afterId where the page starts
     * @param now the tick's instant
     * @return what it did
     */
    Page closureRequests(Instant cutoff, long afterId, Instant now) {
        List<AwaitingDecision> due = closures.pendingSince(cutoff, afterId, SlaEvaluator.CHUNK);
        Map<Long, SlaCase> cases =
                slas.about(due.stream().map(AwaitingDecision::caseId).toList());
        int reminded = 0;
        for (AwaitingDecision r : due) {
            SlaCase about = cases.get(r.caseId());
            Instant last = entityManager
                    .createQuery(LAST, Instant.class)
                    .setParameter("caseId", r.caseId())
                    .setParameter("type", AlertType.CLOSURE_REMINDER)
                    .setParameter("request", r.id() + ":%")
                    .getSingleResult();
            if (about != null && (last == null || !last.isAfter(cutoff)) && reads(r.approverId(), about.number())) {
                remember(r.caseId(), AlertType.CLOSURE_REMINDER, about.cycle(), r.id(), now);
                notifications.notifyUser(
                        r.approverId(),
                        "closure-reminder",
                        model(about, r.requestedBy(), r.outcome()),
                        r.caseId(),
                        "/cases/" + about.number());
                reminded++;
            }
        }
        return new Page(due.isEmpty() ? afterId : due.getLast().id(), due.size(), reminded);
    }

    /**
     * Repeats the overdue alert for one page of overdue cases, once per {@code OVERDUE_REMINDER_DAYS} period since
     * the due time (period 0 is the breach itself, which the evaluator announced), to the people the evaluator told.
     * The ledger row is keyed by the period's index, so each period is told once whatever the ticks.
     *
     * @param afterId where the page starts
     * @param now the tick's instant
     * @return what it did
     */
    Page overdueCases(long afterId, Instant now) {
        List<OverdueCase> due = slas.overdue(afterId, SlaEvaluator.CHUNK);
        Map<Long, SlaCase> cases =
                slas.about(due.stream().map(OverdueCase::caseId).toList());
        int reminded = 0;
        for (OverdueCase overdue : due) {
            SlaCase about = cases.get(overdue.caseId());
            long days = Duration.between(overdue.dueAt(), now).toDays();
            String period = overdue.repeatPeriod(now);
            if (about == null || period == null || told(overdue.caseId(), about.cycle(), period)) {
                continue;
            }
            entityManager.persist(
                    new AlertLedger(overdue.caseId(), AlertType.SLA_OVERDUE_REPEAT, about.cycle(), period, now));
            Map<String, String> model = Map.of(
                    "caseNumber", about.number().toString(),
                    "title", about.title(),
                    "due", LocalDate.ofInstant(overdue.dueAt(), zone).toString(),
                    "days", Long.toString(days));
            for (long person : sla.recipients(about, true)) {
                notifications.notifyUser(
                        person, "sla-overdue-repeat", model, overdue.caseId(), "/cases/" + about.number());
                reminded++;
            }
        }
        return new Page(due.isEmpty() ? afterId : due.getLast().caseId(), due.size(), reminded);
    }

    private boolean told(long caseId, int cycle, String period) {
        return entityManager
                        .createQuery(TOLD, Long.class)
                        .setParameter("caseId", caseId)
                        .setParameter("type", AlertType.SLA_OVERDUE_REPEAT)
                        .setParameter("cycle", cycle)
                        .setParameter("period", period)
                        .getSingleResult()
                > 0;
    }

    /** The ledger row: unique per case, type, cycle and {@code <request id>:<UTC day>}. */
    private void remember(long caseId, AlertType type, int cycle, long requestId, Instant now) {
        String period = requestId + ":" + LocalDate.ofInstant(now, ZoneOffset.UTC);
        entityManager.persist(new AlertLedger(caseId, type, cycle, period, now));
    }

    /** Whether a person is enabled and still reads the case. */
    private boolean reads(long userId, CaseNumber number) {
        UserPermissions person = directory.permissionsOf(userId);
        return !person.disabled() && scopes.forUser(person).canRead(number);
    }

    private Map<String, String> model(SlaCase about, long requestedBy, String outcome) {
        return Map.of(
                "caseNumber", about.number().toString(),
                "title", about.title(),
                "requester", directory.displayName(requestedBy).orElse(""),
                "outcome", outcome);
    }
}
