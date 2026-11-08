// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import static io.trailkeep.testsupport.ReportFixture.ALPHA;
import static io.trailkeep.testsupport.ReportFixture.BETA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.report.ReportScheduleService.Schedule;
import io.trailkeep.report.ReportScheduleService.ScheduleChange;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.testsupport.ReportFixture;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administering the schedules, once per vendor (WP-23 part b2), on {@link ReportFixture}'s teams plus a Compliance
 * Admin: every team is listed, scheduled or not; the first save creates a schedule due at the next weekday and hour
 * after now, a later save recomputes {@code NEXT_RUN_AT} from now, and each is audited as {@code
 * REPORT_SCHEDULE_CHANGED} old to new; a stale version (an edit or a delivery since the form loaded) is {@code
 * CONFLICT} and changes nothing; anyone but a Compliance Admin is {@code FORBIDDEN}, an unknown team {@code NOT_FOUND}
 * and an hour outside 0 to 23 {@code INVALID}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractReportScheduleIT {

    static final long ADMIN = 984_006L;

    @Autowired
    private ReportScheduleService schedules;

    @Autowired
    private UserDirectoryService directory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private InstantSource clock;

    @Autowired
    private AppProperties app;

    @BeforeEach
    void seed() {
        new ReportFixture(jdbc, LocalDate.ofInstant(clock.instant(), app.timezone()), app.timezone()).seed();
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, CREATED_AT) VALUES (?, ?, ?, ?, ?)",
                ADMIN,
                "report-admin",
                "Synthetic Report Admin",
                "report-admin@example.test",
                Timestamp.from(clock.instant()));
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'COMPLIANCE_ADMIN')", ADMIN);
    }

    @Test
    void theFirstSaveCreatesTheScheduleDueAtTheNextWeekdayAndHourAndIsAudited() {
        assertThat(schedules.schedules(admin()))
                .contains(new Schedule(ALPHA, ReportFixture.ALPHA_NAME, false, false, DayOfWeek.MONDAY, 6, null, -1))
                .extracting(Schedule::team)
                .containsSubsequence(ReportFixture.ALPHA_NAME, ReportFixture.BETA_NAME);

        final Instant before = clock.instant();
        schedules.save(admin(), ALPHA, ReportScheduleService.UNSCHEDULED, change(true, DayOfWeek.WEDNESDAY, 9));
        entityManager.flush();
        entityManager.clear();

        Schedule saved = schedules.schedule(admin(), ALPHA);
        assertThat(saved.scheduled()).isTrue();
        assertThat(saved.enabled()).isTrue();
        assertThat(saved.weekday()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(saved.hour()).isEqualTo(9);
        assertThat(saved.nextRun()).isEqualTo(next(before, DayOfWeek.WEDNESDAY, 9));
        assertThat(audits()).hasSize(1);
        assertThat(audits().getFirst()).containsEntry("OLD_VALUES_JSON", null);
        assertThat((String) audits().getFirst().get("NEW_VALUES_JSON"))
                .contains("\"enabled\":true", "\"weekday\":\"WEDNESDAY\"", "\"hour\":9", "\"nextRunAt\":\"");
    }

    @Test
    void laterSavesRecomputeTheNextRunFromNowAndAreAuditedOldToNew() {
        Instant longAgo = clock.instant().minus(Duration.ofDays(30));
        ReportSchedule overdue = new ReportSchedule(BETA, DayOfWeek.FRIDAY, 14, longAgo, app.timezone());
        entityManager.persist(overdue);
        entityManager.flush();
        assertThat(overdue.getNextRunAt()).isBefore(clock.instant());
        int version = schedules.schedule(admin(), BETA).version();

        schedules.save(admin(), BETA, version, change(true, DayOfWeek.FRIDAY, 14));
        entityManager.flush();
        entityManager.clear();
        Schedule moved = schedules.schedule(admin(), BETA);
        assertThat(moved.nextRun()).isAfter(LocalDateTime.ofInstant(clock.instant(), app.timezone()));
        assertThat(moved.version()).isEqualTo(version + 1);

        schedules.save(admin(), BETA, moved.version(), change(false, DayOfWeek.TUESDAY, 0));
        entityManager.flush();
        entityManager.clear();
        assertThat(schedules.schedule(admin(), BETA))
                .extracting(Schedule::enabled, Schedule::weekday, Schedule::hour, Schedule::nextRun)
                .containsExactly(false, DayOfWeek.TUESDAY, 0, null);
        Map<String, Object> last = audits().getLast();
        assertThat((String) last.get("OLD_VALUES_JSON")).contains("\"enabled\":true", "\"weekday\":\"FRIDAY\"");
        assertThat((String) last.get("NEW_VALUES_JSON")).contains("\"enabled\":false", "\"weekday\":\"TUESDAY\"");
    }

    @Test
    void staleVersionsAreConflictsAndChangeNothing() {
        schedules.save(admin(), ALPHA, ReportScheduleService.UNSCHEDULED, change(true, DayOfWeek.MONDAY, 6));
        entityManager.flush();
        int loaded = schedules.schedule(admin(), ALPHA).version();
        jdbc.update("UPDATE REPORT_SCHEDULE SET VERSION = VERSION + 1 WHERE TEAM_ID = ?", ALPHA);
        entityManager.clear();

        assertRefused(Kind.CONFLICT, () -> schedules.save(admin(), ALPHA, loaded, change(false, DayOfWeek.MONDAY, 6)));
        assertRefused(
                Kind.CONFLICT,
                () -> schedules.save(
                        admin(), ALPHA, ReportScheduleService.UNSCHEDULED, change(false, DayOfWeek.MONDAY, 6)));
        assertRefused(Kind.CONFLICT, () -> schedules.save(admin(), BETA, 0, change(false, DayOfWeek.MONDAY, 6)));
        entityManager.clear();
        assertThat(schedules.schedule(admin(), ALPHA).enabled()).isTrue();
        assertThat(audits()).hasSize(1);
    }

    @Test
    void onlyComplianceAdminsSaveOnlyKnownTeamsAtOnlyRealHours() {
        Actor manager = actor(ReportFixture.MANAGER);
        assertThat(schedules.administers(admin())).isTrue();
        assertThat(schedules.administers(manager)).isFalse();
        assertRefused(Kind.FORBIDDEN, () -> schedules.schedules(manager));
        assertRefused(Kind.FORBIDDEN, () -> schedules.schedule(manager, ALPHA));
        assertRefused(Kind.FORBIDDEN, () -> schedules.save(manager, ALPHA, -1, change(true, DayOfWeek.MONDAY, 6)));
        assertRefused(Kind.FORBIDDEN, () -> schedules.schedule(actor(ReportFixture.SYSTEM_ADMIN), ALPHA));
        assertRefused(Kind.NOT_FOUND, () -> schedules.schedule(admin(), 984_999L));
        assertRefused(Kind.NOT_FOUND, () -> schedules.save(admin(), -1, -1, change(true, DayOfWeek.MONDAY, 6)));
        assertRefused(Kind.INVALID, () -> schedules.save(admin(), ALPHA, -1, change(true, DayOfWeek.MONDAY, 24)));
        assertRefused(Kind.INVALID, () -> schedules.save(admin(), ALPHA, -1, change(true, DayOfWeek.MONDAY, -1)));
        entityManager.flush();
        assertThat(audits()).isEmpty();
        assertThat(schedules.schedule(admin(), ALPHA).scheduled()).isFalse();
    }

    /** The first {@code day} at {@code hour} after {@code from} in {@code app.timezone}. */
    private LocalDateTime next(Instant from, DayOfWeek day, int hour) {
        ZonedDateTime local = from.atZone(app.timezone());
        ZonedDateTime candidate = local.with(TemporalAdjusters.nextOrSame(day))
                .withHour(hour)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        return (candidate.isAfter(local) ? candidate : candidate.plusWeeks(1)).toLocalDateTime();
    }

    private List<Map<String, Object>> audits() {
        return jdbc.queryForList(
                "SELECT OLD_VALUES_JSON, NEW_VALUES_JSON FROM AUDIT_EVENT WHERE ACTION = 'REPORT_SCHEDULE_CHANGED'"
                        + " AND ACTOR_ID = ? ORDER BY ID",
                ADMIN);
    }

    private static void assertRefused(Kind kind, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ReportException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    private static ScheduleChange change(boolean enabled, DayOfWeek day, int hour) {
        return new ScheduleChange(enabled, day, hour);
    }

    private Actor admin() {
        return actor(ADMIN);
    }

    private Actor actor(long user) {
        return new Actor(Objects.requireNonNull(directory.permissionsOf(user)), "Synthetic actor");
    }
}
