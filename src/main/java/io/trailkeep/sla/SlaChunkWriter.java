// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseSlaService;
import io.trailkeep.casefile.CaseSlaService.SlaCase;
import io.trailkeep.casefile.CaseSlaService.SlaTransition;
import io.trailkeep.casefile.EscalateTo;
import io.trailkeep.casefile.SlaState;
import io.trailkeep.notification.NotificationService;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One chunk of an evaluation in one transaction (BUILD_PLAN §6): the transitions, their history events and their
 * ledger rows commit or roll back together, so a crash between chunks loses nothing and repeats nothing.
 */
@Service
@Transactional
class SlaChunkWriter {

    private final CaseSlaService slas;
    private final AuditRecorder audit;
    private final NotificationService notifications;
    private final UserDirectoryService directory;
    private final EntityManager entityManager;
    private final ZoneId zone;

    SlaChunkWriter(
            CaseSlaService slas,
            AuditRecorder audit,
            NotificationService notifications,
            UserDirectoryService directory,
            EntityManager entityManager,
            AppProperties app) {
        this.slas = slas;
        this.audit = audit;
        this.notifications = notifications;
        this.directory = directory;
        this.entityManager = entityManager;
        this.zone = app.timezone();
    }

    /**
     * What one chunk did.
     *
     * @param atRisk cases that turned at risk
     * @param overdue cases that turned overdue
     * @param alerted people told, one per case and person
     */
    record Chunk(int atRisk, int overdue, int alerted) {

        /** Nothing moved. */
        static final Chunk NONE = new Chunk(0, 0, 0);

        Chunk plus(Chunk other) {
            return new Chunk(atRisk + other.atRisk, overdue + other.overdue, alerted + other.alerted);
        }

        int moved() {
            return atRisk + overdue;
        }
    }

    /**
     * Moves one chunk of cases on, and tells the people each change concerns (BUILD_PLAN §6): at risk, the owner;
     * overdue, the owner and, when the policy escalates to them, the team's enabled Managers. A case whose owner is
     * disabled tells the Managers instead. Each alert and mail rides on its ledger row, so it is sent once per cycle.
     *
     * @param caseIds at most one chunk of candidate ids
     * @param now the evaluation instant
     * @return what changed
     */
    Chunk write(List<Long> caseIds, Instant now) {
        Chunk done = Chunk.NONE;
        for (SlaTransition t : slas.advance(caseIds, now)) {
            audit.record(new AuditEntry(
                    null,
                    null,
                    AuditOrigin.SCHEDULER,
                    t.caseId(),
                    "CASE_SLA",
                    t.caseId(),
                    "SLA_STATE_CHANGED",
                    audit.diff(new State(t.from(), t.cycle()), new State(t.to(), t.cycle()))));
            boolean atRisk = t.to() == SlaState.AT_RISK;
            entityManager.persist(new AlertLedger(
                    t.caseId(), atRisk ? AlertType.SLA_AT_RISK : AlertType.SLA_OVERDUE, t.cycle(), now));
            SlaCase about = t.about();
            Map<String, String> model = Map.of(
                    "caseNumber", about.number().toString(),
                    "title", about.title(),
                    "due", LocalDate.ofInstant(t.dueAt(), zone).toString());
            List<Long> people = recipients(about, !atRisk);
            people.forEach(person -> notifications.notifyUser(
                    person, atRisk ? "sla-at-risk" : "sla-overdue", model, t.caseId(), "/cases/" + about.number()));
            done = done.plus(new Chunk(atRisk ? 1 : 0, atRisk ? 0 : 1, people.size()));
        }
        return done;
    }

    /** The owner, and the team's enabled Managers when the policy escalates or the owner is disabled; once each. */
    private List<Long> recipients(SlaCase about, boolean overdue) {
        boolean ownerActive = !directory.permissionsOf(about.ownerId()).disabled();
        Set<Long> people = new LinkedHashSet<>();
        if (ownerActive) {
            people.add(about.ownerId());
        }
        if (!ownerActive || (overdue && about.escalateTo() == EscalateTo.OWNER_AND_MANAGERS)) {
            people.addAll(directory.activeManagersOf(about.teamId()));
        }
        return List.copyOf(people);
    }

    /**
     * The audit snapshot of a transition.
     *
     * @param state the SLA state
     * @param cycle the SLA cycle
     */
    record State(SlaState state, int cycle) {}
}
