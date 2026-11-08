// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administers the schedules; see {@link ReportScheduleService}. A save is flushed inside its transaction, so a
 * concurrent save or delivery that moved the version on (the delivery job's {@code ran} does), or a second first save
 * for the same team (the unique key), is a {@code CONFLICT} rather than a lost update.
 */
@Service
@Transactional
class DefaultReportScheduleService implements ReportScheduleService {

    private static final DayOfWeek FIRST_DAY = DayOfWeek.MONDAY;
    private static final int FIRST_HOUR = 6;
    private static final int LAST_HOUR = 23;

    private final EntityManager entityManager;
    private final UserDirectoryService directory;
    private final AuditRecorder audit;
    private final InstantSource clock;
    private final ZoneId zone;

    DefaultReportScheduleService(
            EntityManager entityManager,
            UserDirectoryService directory,
            AuditRecorder audit,
            InstantSource clock,
            AppProperties app) {
        this.entityManager = entityManager;
        this.directory = directory;
        this.audit = audit;
        this.clock = clock;
        this.zone = app.timezone();
    }

    /**
     * What the audit trail keeps of a schedule.
     *
     * @param enabled whether the report goes out
     * @param weekday the day
     * @param hour the hour
     * @param nextRunAt when it next goes out
     */
    record Values(boolean enabled, String weekday, int hour, String nextRunAt) {}

    @Override
    public boolean administers(Actor actor) {
        return actor.permissions().has(GlobalRole.COMPLIANCE_ADMIN);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Schedule> schedules(Actor actor) {
        allowed(actor);
        Map<Long, ReportSchedule> byTeam =
                entityManager
                        .createQuery("SELECT s FROM ReportSchedule s", ReportSchedule.class)
                        .getResultList()
                        .stream()
                        .collect(Collectors.toMap(ReportSchedule::getTeamId, Function.identity()));
        return directory.allTeams().stream()
                .map(team -> view(team, byTeam.get(team.id())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Schedule schedule(Actor actor, long teamId) {
        allowed(actor);
        return view(team(teamId), find(teamId));
    }

    @Override
    public void save(Actor actor, long teamId, int version, ScheduleChange change) {
        allowed(actor);
        team(teamId);
        if (change.hour() < 0 || change.hour() > LAST_HOUR) {
            throw new ReportException(Kind.INVALID, "an hour is 0 to 23");
        }
        ReportSchedule schedule = find(teamId);
        if (version != (schedule == null ? UNSCHEDULED : schedule.getVersion())) {
            throw conflict();
        }
        final Values before = schedule == null ? null : values(schedule);
        Instant now = clock.instant();
        if (schedule == null) {
            schedule = new ReportSchedule(teamId, change.weekday(), change.hour(), now, zone);
            entityManager.persist(schedule);
        }
        schedule.change(change.enabled(), change.weekday(), change.hour(), now, zone);
        try {
            entityManager.flush();
        } catch (PersistenceException raced) {
            throw conflict();
        }
        audit.record(new AuditEntry(
                actor.permissions().userId(),
                actor.displayName(),
                AuditOrigin.UI,
                null,
                "REPORT_SCHEDULE",
                schedule.getId(),
                "REPORT_SCHEDULE_CHANGED",
                audit.diff(before, values(schedule))));
    }

    private @Nullable ReportSchedule find(long teamId) {
        return entityManager
                .createQuery("SELECT s FROM ReportSchedule s WHERE s.teamId = :team", ReportSchedule.class)
                .setParameter("team", teamId)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    private TeamSummary team(long teamId) {
        return directory.team(teamId).orElseThrow(() -> new ReportException(Kind.NOT_FOUND, "no such team"));
    }

    private Schedule view(TeamSummary team, @Nullable ReportSchedule schedule) {
        if (schedule == null) {
            return new Schedule(team.id(), team.name(), false, false, FIRST_DAY, FIRST_HOUR, null, UNSCHEDULED);
        }
        LocalDateTime next = schedule.isEnabled() ? LocalDateTime.ofInstant(schedule.getNextRunAt(), zone) : null;
        return new Schedule(
                team.id(),
                team.name(),
                true,
                schedule.isEnabled(),
                schedule.getWeekday(),
                schedule.getHour(),
                next,
                schedule.getVersion());
    }

    private static Values values(ReportSchedule schedule) {
        return new Values(
                schedule.isEnabled(),
                schedule.getWeekday().name(),
                schedule.getHour(),
                schedule.getNextRunAt().toString());
    }

    private static void allowed(Actor actor) {
        if (!actor.permissions().has(GlobalRole.COMPLIANCE_ADMIN)) {
            throw new ReportException(Kind.FORBIDDEN, "Compliance Admin only");
        }
    }

    private static ReportException conflict() {
        return new ReportException(Kind.CONFLICT, "the schedule changed since it was loaded");
    }
}
