// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A team's weekly aging report (V7 {@code REPORT_SCHEDULE}): on which weekday and at which hour of {@code
 * app.timezone} it goes out, and when it next does.
 */
@Entity
@Table(name = "REPORT_SCHEDULE")
class ReportSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "reportScheduleSeq")
    @SequenceGenerator(name = "reportScheduleSeq", sequenceName = "SEQ_REPORT_SCHEDULE", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "TEAM_ID", nullable = false, updatable = false)
    private long teamId;

    @Column(name = "ENABLED", nullable = false)
    private boolean enabled = true;

    @Column(name = "RUN_WEEKDAY", nullable = false)
    private int runWeekday = DayOfWeek.MONDAY.getValue();

    @Column(name = "RUN_HOUR", nullable = false)
    private int runHour;

    @Column(name = "NEXT_RUN_AT", nullable = false)
    private Instant nextRunAt = Instant.EPOCH;

    @Column(name = "LAST_RUN_AT")
    private @Nullable Instant lastRunAt;

    @Version
    @Column(name = "VERSION", nullable = false)
    private int version;

    /** For JPA. */
    protected ReportSchedule() {}

    /**
     * A new, enabled schedule, first due at the next weekday and hour after {@code now}.
     *
     * @param teamId the team
     * @param weekday the day it goes out
     * @param hour the hour it goes out, 0 to 23
     * @param now the current instant
     * @param zone {@code app.timezone}
     */
    ReportSchedule(long teamId, DayOfWeek weekday, int hour, Instant now, ZoneId zone) {
        this.teamId = teamId;
        this.runWeekday = weekday.getValue();
        this.runHour = hour;
        this.nextRunAt = next(now, zone);
    }

    /**
     * Records a run and moves the schedule on to the next weekday and hour after it: a week later when the run was on
     * time, and never into the past when the job had been down for longer.
     *
     * @param at when it ran
     * @param zone {@code app.timezone}
     */
    void ran(Instant at, ZoneId zone) {
        this.lastRunAt = at;
        this.nextRunAt = next(at, zone);
    }

    /** The first instant after {@code after} that is this schedule's weekday and hour in {@code zone}. */
    private Instant next(Instant after, ZoneId zone) {
        LocalDateTime local = LocalDateTime.ofInstant(after, zone);
        LocalDateTime candidate = local.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(runWeekday)))
                .withHour(runHour)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        if (!candidate.atZone(zone).toInstant().isAfter(after)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate.atZone(zone).toInstant();
    }

    long getId() {
        return Objects.requireNonNull(id, "not persisted");
    }

    long getTeamId() {
        return teamId;
    }

    boolean isEnabled() {
        return enabled;
    }

    Instant getNextRunAt() {
        return nextRunAt;
    }

    @Nullable
    Instant getLastRunAt() {
        return lastRunAt;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof ReportSchedule that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return ReportSchedule.class.hashCode();
    }
}
