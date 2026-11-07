// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.notification.NotificationService;
import io.trailkeep.report.ReportService.AgingReport;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers one due schedule in one transaction (BUILD_PLAN §6 "Aging report delivery"): renders the team's aging
 * report through {@link ReportService} as a Manager of the team would see it, stores it as a {@link ReportRun}, tells
 * the team's enabled Managers and every enabled Compliance Admin (only the latter while {@code app.reports.admins-only}
 * holds, for the first cycle after go-live) by mail and in-app alert, linking to the run and never carrying it, and
 * moves the schedule on. The run, the outbox rows, the alerts and the schedule's new {@code NEXT_RUN_AT} commit
 * together, so a schedule is never delivered twice; the job's lock keeps two replicas from racing, and the schedule's
 * version from racing an edit.
 */
@Service
@Transactional
class ReportDeliveryWriter {

    /** Who a run reads as: no person, a Manager of the team only, so restricted cases of the team are included. */
    private static final long NO_USER = 0;

    private final ReportService reports;
    private final ReportHtml html;
    private final NotificationService notifications;
    private final UserDirectoryService directory;
    private final EntityManager entityManager;
    private final ZoneId zone;
    private final boolean adminsOnly;

    ReportDeliveryWriter(
            ReportService reports,
            ReportHtml html,
            NotificationService notifications,
            UserDirectoryService directory,
            EntityManager entityManager,
            AppProperties app,
            @Value("${app.reports.admins-only:false}") boolean adminsOnly) {
        this.reports = reports;
        this.html = html;
        this.notifications = notifications;
        this.directory = directory;
        this.entityManager = entityManager;
        this.zone = app.timezone();
        this.adminsOnly = adminsOnly;
    }

    /**
     * The enabled schedules due at {@code now}, the longest due first.
     *
     * @param now the tick's instant
     * @return their ids
     */
    @Transactional(readOnly = true)
    List<Long> due(Instant now) {
        return entityManager
                .createQuery(
                        "SELECT s.id FROM ReportSchedule s WHERE s.enabled = true AND s.nextRunAt <= :now"
                                + " ORDER BY s.nextRunAt, s.id",
                        Long.class)
                .setParameter("now", now)
                .getResultList();
    }

    /**
     * Delivers one schedule, if it is still enabled and due.
     *
     * @param scheduleId the schedule
     * @param now the tick's instant
     * @return {@code false} if it was not due any more
     */
    boolean deliver(long scheduleId, Instant now) {
        ReportSchedule schedule = entityManager.find(ReportSchedule.class, scheduleId);
        if (schedule == null || !schedule.isEnabled() || schedule.getNextRunAt().isAfter(now)) {
            return false;
        }
        long team = schedule.getTeamId();
        Actor reader = new Actor(
                new UserPermissions(NO_USER, false, Set.of(), Map.of(team, TeamRole.MANAGER)), "Scheduled report");
        AgingReport report = reports.aging(reader);
        ReportRun run = new ReportRun(
                schedule,
                now,
                report.asOf(),
                report.lines().size(),
                html.aging(report),
                DefaultReportExportService.agingCsv(report));
        entityManager.persist(run);
        String name = directory.team(team).map(TeamSummary::name).orElse("");
        Map<String, Object> model = Map.of("team", name, "asOf", report.asOf().toString());
        for (long person : recipients(team)) {
            notifications.notifyUser(person, "report-ready", model, null, "/reports/runs/" + run.getId());
        }
        schedule.ran(now, zone);
        return true;
    }

    /** The team's enabled Managers and every enabled Compliance Admin, each once; only the latter if admins-only. */
    private Set<Long> recipients(long team) {
        Set<Long> people = new LinkedHashSet<>();
        if (!adminsOnly) {
            people.addAll(directory.activeManagersOf(team));
        }
        people.addAll(directory.activeComplianceAdmins());
        return people;
    }
}
