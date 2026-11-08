// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.casefile.CaseService.Actor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The teams' weekly aging report schedules (BUILD_PLAN §6 "Aging report delivery", WP-23 part b2), administered by
 * Compliance Admins only: every other method throws {@link ReportException} {@code FORBIDDEN} for anyone else, before
 * anything is read, and {@code NOT_FOUND} for an unknown team. Every team is listed, with or without a schedule; the
 * first save creates it. A save recomputes {@code NEXT_RUN_AT} from now with the schedule's own next-run rule, is
 * checked against the version the form was loaded with ({@code CONFLICT} when stale, also when the report went out
 * meanwhile), and is audited as {@code REPORT_SCHEDULE_CHANGED} with the old and new values.
 */
public interface ReportScheduleService {

    /** The version a team without a schedule is saved against. */
    int UNSCHEDULED = -1;

    /**
     * One team's schedule as the admin screen shows it.
     *
     * @param teamId the team
     * @param team its name
     * @param scheduled whether a schedule exists
     * @param enabled whether the report goes out
     * @param weekday the day it goes out (Monday for a team without a schedule)
     * @param hour the hour it goes out in {@code app.timezone}, 0 to 23
     * @param nextRun when it next goes out, in {@code app.timezone}; {@code null} unless enabled
     * @param version the version to save against, {@link #UNSCHEDULED} without a schedule
     */
    record Schedule(
            long teamId,
            String team,
            boolean scheduled,
            boolean enabled,
            DayOfWeek weekday,
            int hour,
            @Nullable LocalDateTime nextRun,
            int version) {}

    /**
     * A schedule as saved.
     *
     * @param enabled whether the report goes out
     * @param weekday the day
     * @param hour the hour in {@code app.timezone}
     */
    record ScheduleChange(
            boolean enabled,
            DayOfWeek weekday,
            @Min(0) @Max(23) int hour) {}

    /**
     * Whether the actor administers schedules, for links.
     *
     * @param actor the actor
     * @return {@code true} for a Compliance Admin
     */
    boolean administers(Actor actor);

    /**
     * Every team by name with its schedule.
     *
     * @param actor the actor
     * @return the schedules
     */
    List<Schedule> schedules(Actor actor);

    /**
     * One team's schedule.
     *
     * @param actor the actor
     * @param teamId the team
     * @return the schedule
     */
    Schedule schedule(Actor actor, long teamId);

    /**
     * Saves a team's schedule; {@code INVALID} for an hour outside 0 to 23.
     *
     * @param actor the actor
     * @param teamId the team
     * @param version the version the form was loaded with
     * @param change the new values
     */
    void save(Actor actor, long teamId, int version, ScheduleChange change);
}
