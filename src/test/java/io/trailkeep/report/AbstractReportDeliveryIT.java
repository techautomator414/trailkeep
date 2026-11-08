// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import static io.trailkeep.testsupport.ReportFixture.ALPHA;
import static io.trailkeep.testsupport.ReportFixture.BETA;
import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.testsupport.ReportFixture;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled aging report delivery, once per vendor (WP-23 part a), on {@link ReportFixture}: with two schedules due
 * and one not, one tick stores one run for each due team whose HTML and CSV are exactly what the report pages give a
 * Manager of that team, tells the team's Managers and every Compliance Admin once each by mail and alert (linking to
 * the run, never carrying it; the outbox row names the run), moves each due schedule on a week, leaves the other alone;
 * a second tick at the same moment delivers nothing. The job's health is unknown before its first run, up after it
 * and down when its last success is older than the grace.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractReportDeliveryIT {

    static final long ADMIN = 984_006L;
    private static final long EMPTY_TEAM = 984_013L;

    @Autowired
    private ReportDeliveryTick tick;

    @Autowired
    private ReportService reports;

    @Autowired
    private ReportExportService exports;

    @Autowired
    private ReportHtml html;

    @Autowired
    private ReportDeliveryHealthIndicator health;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private InstantSource clock;

    @Autowired
    private AppProperties app;

    private Instant now;

    @BeforeEach
    void seed() {
        // Both vendors keep timestamps to the microsecond at most; milliseconds compare exactly after the round trip.
        now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        new ReportFixture(jdbc, LocalDate.ofInstant(now, app.timezone()), app.timezone()).seed();
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, CREATED_AT) VALUES (?, ?, ?, ?, ?)",
                ADMIN,
                "report-admin",
                "Synthetic Report Admin",
                "report-admin@example.test",
                Timestamp.from(now));
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'COMPLIANCE_ADMIN')", ADMIN);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, ?)", EMPTY_TEAM, "Empty Reporting Team");
    }

    @Test
    void oneTickDeliversEachDueTeamOnceAndMovesItsScheduleOnAWeek() {
        final ReportSchedule alpha = schedule(ALPHA, now.minus(Duration.ofDays(8)));
        final ReportSchedule beta = schedule(BETA, now.minus(Duration.ofDays(9)));
        final ReportSchedule later = schedule(EMPTY_TEAM, now);
        final Instant alphaDue = alpha.getNextRunAt();
        final Instant laterDue = later.getNextRunAt();
        assertThat(alphaDue).isBefore(now);
        assertThat(laterDue).isAfter(now);

        assertThat(tick.run(now)).isEqualTo(2);
        entityManager.flush();
        entityManager.clear();

        assertThat(runs()).extracting(ReportRun::getTeamId).containsExactlyInAnyOrder(ALPHA, BETA);
        for (ReportRun run : runs()) {
            Actor manager = manager(run.getTeamId());
            assertThat(run.getAsOf()).isEqualTo(LocalDate.ofInstant(now, app.timezone()));
            assertThat(run.getHtml()).isEqualTo(html.aging(reports.aging(manager)));
            assertThat(run.getCsv()).isEqualTo(exports.aging(manager).content());
            assertThat(run.getHtml()).contains("Synthetic report case").doesNotContain(ReportFixture.MESSAGE);
        }
        ReportSchedule moved = entityManager.find(ReportSchedule.class, alpha.getId());
        assertThat(moved.getNextRunAt())
                .isEqualTo(alphaDue.plus(Duration.ofDays(7)))
                .isAfter(now);
        assertThat(moved.getLastRunAt()).isEqualTo(now);
        assertThat(entityManager.find(ReportSchedule.class, later.getId()).getNextRunAt())
                .isEqualTo(laterDue);
        assertThat(entityManager.find(ReportSchedule.class, beta.getId()).getLastRunAt())
                .isEqualTo(now);

        assertThat(tick.run(now)).as("a second tick at the same moment").isZero();
        entityManager.flush();
        assertThat(runs()).hasSize(2);
    }

    @Test
    void theTeamsManagersAndEveryComplianceAdminAreToldOnceEachWithALinkAndNeverTheReport() {
        schedule(ALPHA, now.minus(Duration.ofDays(8)));
        schedule(BETA, now.minus(Duration.ofDays(8)));

        tick.run(now);
        entityManager.flush();

        long alphaRun = runOf(ALPHA);
        long betaRun = runOf(BETA);
        assertThat(alerts(ReportFixture.MANAGER)).containsExactly("/reports/runs/" + alphaRun);
        assertThat(alerts(ADMIN)).containsExactlyInAnyOrder("/reports/runs/" + alphaRun, "/reports/runs/" + betaRun);
        assertThat(alerts(ReportFixture.ANALYST)).isEmpty();
        assertThat(alerts(ReportFixture.BETA_ANALYST)).isEmpty();
        List<Map<String, Object>> mails = jdbc.queryForList(
                "SELECT RECIPIENT, SUBJECT, BODY_TEXT, BODY_HTML, REPORT_RUN_ID, CASE_ID FROM NOTIFICATION_OUTBOX"
                        + " WHERE TEMPLATE_ID = 'report-ready' AND REPORT_RUN_ID IN (?, ?) ORDER BY ID",
                alphaRun,
                betaRun);
        assertThat(mails)
                .extracting(mail -> mail.get("RECIPIENT"))
                .containsExactlyInAnyOrder(
                        "report984001@example.test", "report-admin@example.test", "report-admin@example.test");
        Map<String, Object> toManager = mails.stream()
                .filter(mail -> "report984001@example.test".equals(mail.get("RECIPIENT")))
                .findFirst()
                .orElseThrow();
        assertThat(toManager.get("REPORT_RUN_ID"))
                .isInstanceOf(Number.class)
                .extracting(id -> ((Number) id).longValue())
                .isEqualTo(alphaRun);
        assertThat(toManager.get("CASE_ID")).isNull();
        assertThat(toManager.get("SUBJECT"))
                .isEqualTo("Aging report for Alpha Reporting Team, " + LocalDate.ofInstant(now, app.timezone()));
        assertThat((String) toManager.get("BODY_TEXT"))
                .contains("https://trailkeep.example.invalid/reports/runs/" + alphaRun)
                .doesNotContain("Synthetic report case", "RPT-2026-", ReportFixture.MESSAGE);
        assertThat((String) toManager.get("BODY_HTML")).doesNotContain("Synthetic report case", "RPT-2026-");
    }

    @Test
    void theHealthIsUnknownBeforeTheFirstRunUpAfterItAndDownWhenStale() {
        jdbc.update("DELETE FROM JOB_RUN WHERE JOB_NAME = ?", ReportDeliveryTick.JOB);
        assertThat(health.health().getStatus()).isEqualTo(Status.UNKNOWN);

        tick.run(now);
        entityManager.flush();
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);

        jdbc.update(
                "UPDATE JOB_RUN SET LAST_SUCCESS_AT = ? WHERE JOB_NAME = ?",
                Timestamp.from(clock.instant().minus(Duration.ofHours(3))),
                ReportDeliveryTick.JOB);
        entityManager.clear();
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    }

    /** A schedule for the team on the weekday and hour of {@code createdAt}, so first due a week after it. */
    private ReportSchedule schedule(long team, Instant createdAt) {
        ZonedDateTime local = createdAt.atZone(app.timezone());
        ReportSchedule schedule =
                new ReportSchedule(team, DayOfWeek.from(local), local.getHour(), createdAt, app.timezone());
        entityManager.persist(schedule);
        return schedule;
    }

    private List<ReportRun> runs() {
        return entityManager
                .createQuery("SELECT r FROM ReportRun r WHERE r.teamId IN :teams", ReportRun.class)
                .setParameter("teams", Set.of(ALPHA, BETA, EMPTY_TEAM))
                .getResultList();
    }

    private long runOf(long team) {
        return runs().stream()
                .filter(run -> run.getTeamId() == team)
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private List<String> alerts(long user) {
        return jdbc.queryForList(
                "SELECT LINK FROM USER_ALERT WHERE USER_ID = ? AND LINK LIKE '/reports/runs/%'", String.class, user);
    }

    private static Actor manager(long team) {
        return new Actor(
                new UserPermissions(984_001L, false, Set.of(), Map.of(team, TeamRole.MANAGER)), "Synthetic Manager");
    }
}
