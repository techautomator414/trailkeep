// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import static io.trailkeep.testsupport.ReportFixture.ALPHA;
import static io.trailkeep.testsupport.ReportFixture.BETA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.report.ReportExportService.Csv;
import io.trailkeep.report.ReportRunService.RunSummary;
import io.trailkeep.report.ReportRunService.RunView;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Paged;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.shared.html.StoredReportSanitizer;
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
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading the stored runs, once per vendor (WP-23 part b1), on {@link ReportFixture} plus a Compliance Admin, with
 * real runs from the delivery job: a Manager lists and opens their team's runs only, a Compliance Admin every team's,
 * newest first and 20 to a page; the view is the stored HTML through the stored-report sanitizer, and the CSV is the
 * stored file, each download audited ({@code REPORT_RUN_DOWNLOADED}). Everyone else, including a disabled Manager and
 * the Manager of another team, gets {@code NOT_FOUND}, as do malformed and unknown ids, and nothing is audited.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractReportRunIT {

    static final long ADMIN = 984_006L;
    private static final long BETA_MANAGER = 984_007L;

    @Autowired
    private ReportRunService runs;

    @Autowired
    private ReportDeliveryTick tick;

    @Autowired
    private StoredReportSanitizer sanitizer;

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

    private Instant now;

    @BeforeEach
    void seed() {
        now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        new ReportFixture(jdbc, LocalDate.ofInstant(now, app.timezone()), app.timezone()).seed();
        user(ADMIN, "Synthetic Report Admin");
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'COMPLIANCE_ADMIN')", ADMIN);
        user(BETA_MANAGER, "Synthetic Beta Manager");
        jdbc.update(
                "INSERT INTO APP_USER_TEAM (USER_ID, TEAM_ID, ROLE_IN_TEAM) VALUES (?, ?, 'MANAGER')",
                BETA_MANAGER,
                BETA);
        schedule(ALPHA, now.minus(Duration.ofDays(8)));
        schedule(BETA, now.minus(Duration.ofDays(9)));
        assertThat(tick.run(now)).isEqualTo(2);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void managersReadTheirTeamsRunsAndComplianceAdminsEveryTeamsNewestFirst() {
        long alpha = runOf(ALPHA);
        long beta = runOf(BETA);
        long older = copy(alpha, now.minus(Duration.ofDays(7)));

        Paged<RunSummary> managers = runs.runs(actor(ReportFixture.MANAGER), 1);
        assertThat(managers.rows()).extracting(RunSummary::id).containsExactly(alpha, older);
        assertThat(managers.rows().getFirst())
                .isEqualTo(
                        new RunSummary(alpha, ReportFixture.ALPHA_NAME, LocalDate.ofInstant(now, app.timezone()), 3));
        assertThat(runs.runs(actor(BETA_MANAGER), 1).rows())
                .extracting(RunSummary::id)
                .containsExactly(beta);
        assertThat(runs.runs(actor(ADMIN), 1).rows())
                .extracting(RunSummary::id)
                .containsSubsequence(alpha, older)
                .contains(beta);
        assertThat(runs.readsRuns(actor(ReportFixture.MANAGER))).isTrue();
        assertThat(runs.readsRuns(actor(ADMIN))).isTrue();
    }

    @Test
    void theListIsPagedByTwenty() {
        long alpha = runOf(ALPHA);
        for (int day = 1; day <= 20; day++) {
            copy(alpha, now.minus(Duration.ofDays(7L * day)));
        }

        Paged<RunSummary> first = runs.runs(actor(ReportFixture.MANAGER), 1);
        assertThat(first.total()).isEqualTo(21);
        assertThat(first.pages()).isEqualTo(2);
        assertThat(first.rows()).hasSize(20).first().extracting(RunSummary::id).isEqualTo(alpha);
        assertThat(runs.runs(actor(ReportFixture.MANAGER), 2).rows()).hasSize(1);
        assertThat(runs.runs(actor(ReportFixture.MANAGER), 9).page()).isEqualTo(2);
    }

    @Test
    void theViewIsTheStoredReportSanitizedAndTheCsvTheStoredFileAudited() {
        long alpha = runOf(ALPHA);
        Map<String, Object> stored = jdbc.queryForMap("SELECT HTML, CSV FROM REPORT_RUN WHERE ID = ?", alpha);

        RunView view = runs.run(actor(ReportFixture.MANAGER), Long.toString(alpha));
        assertThat(view.run().team()).isEqualTo(ReportFixture.ALPHA_NAME);
        assertThat(view.html()).isEqualTo(sanitizer.sanitize((String) Objects.requireNonNull(stored.get("HTML"))));
        assertThat(view.html().isBlank()).isFalse();

        Csv csv = runs.csv(actor(ADMIN), Long.toString(alpha));
        assertThat(csv.content()).isEqualTo(stored.get("CSV"));
        assertThat(csv.fileName())
                .isEqualTo("aging-run-" + alpha + "-" + LocalDate.ofInstant(now, app.timezone()) + ".csv");
        entityManager.flush();
        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT ACTOR_ID, CASE_ID, ENTITY_TYPE, ENTITY_ID, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE ACTION = 'REPORT_RUN_DOWNLOADED' AND ENTITY_ID = ?",
                alpha);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("ACTOR_ID"))
                .isInstanceOf(Number.class)
                .extracting(id -> ((Number) id).longValue())
                .isEqualTo(ADMIN);
        assertThat(events.getFirst()).containsEntry("CASE_ID", null).containsEntry("ENTITY_TYPE", "REPORT_RUN");
        assertThat((String) events.getFirst().get("NEW_VALUES_JSON"))
                .contains("\"team\":" + ALPHA, "\"asOf\":\"" + LocalDate.ofInstant(now, app.timezone()) + "\"")
                .contains("\"lines\":3");
    }

    @Test
    void everyoneElseAndEveryOtherIdIsNotFoundAndNothingIsAudited() {
        String alpha = Long.toString(runOf(ALPHA));
        assertNotFound(ReportFixture.ANALYST, alpha);
        assertNotFound(ReportFixture.SYSTEM_ADMIN, alpha);
        assertNotFound(BETA_MANAGER, alpha);
        for (String id : List.of("abc", "-1", "1e3", "99999999999999999999", "984999999", "")) {
            assertNotFound(ReportFixture.MANAGER, id);
        }
        jdbc.update("UPDATE APP_USER SET DISABLED = ? WHERE ID = ?", true, ReportFixture.MANAGER);
        entityManager.clear();
        assertNotFound(ReportFixture.MANAGER, alpha);
        for (long nobody : List.of(ReportFixture.ANALYST, ReportFixture.SYSTEM_ADMIN, ReportFixture.MANAGER)) {
            assertThat(runs.readsRuns(actor(nobody))).isFalse();
            assertThatThrownBy(() -> runs.runs(actor(nobody), 1))
                    .isInstanceOfSatisfying(
                            ReportException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_FOUND));
        }
        entityManager.flush();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'REPORT_RUN_DOWNLOADED' AND ENTITY_ID = ?",
                        Long.class,
                        Long.parseLong(alpha)))
                .isZero();
    }

    private void assertNotFound(long user, String id) {
        assertThatThrownBy(() -> runs.run(actor(user), id))
                .as("view %s as %s", id, user)
                .isInstanceOfSatisfying(
                        ReportException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_FOUND));
        assertThatThrownBy(() -> runs.csv(actor(user), id))
                .as("csv %s as %s", id, user)
                .isInstanceOfSatisfying(
                        ReportException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_FOUND));
    }

    /** A copy of a stored run, as if its schedule had also run at {@code ranAt}; returns its id. */
    private long copy(long run, Instant ranAt) {
        long id = Objects.requireNonNull(jdbc.queryForObject("SELECT MAX(ID) FROM REPORT_RUN", Long.class)) + 1;
        jdbc.update(
                "INSERT INTO REPORT_RUN (ID, SCHEDULE_ID, TEAM_ID, REPORT, RAN_AT, AS_OF, LINE_COUNT, HTML, CSV)"
                        + " SELECT ?, SCHEDULE_ID, TEAM_ID, REPORT, ?, AS_OF, LINE_COUNT, HTML, CSV FROM REPORT_RUN"
                        + " WHERE ID = ?",
                id,
                Timestamp.from(ranAt),
                run);
        return id;
    }

    private long runOf(long team) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT MAX(ID) FROM REPORT_RUN WHERE TEAM_ID = ?", Long.class, team));
    }

    /** A schedule for the team on the weekday and hour of {@code createdAt}, so first due a week after it. */
    private void schedule(long team, Instant createdAt) {
        ZonedDateTime local = createdAt.atZone(app.timezone());
        entityManager.persist(
                new ReportSchedule(team, DayOfWeek.from(local), local.getHour(), createdAt, app.timezone()));
    }

    private void user(long id, String name) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, CREATED_AT) VALUES (?, ?, ?, ?, ?)",
                id,
                "report-" + id,
                name,
                "report-" + id + "@example.test",
                Timestamp.from(now));
    }

    private Actor actor(long user) {
        return new Actor(directory.permissionsOf(user), "Synthetic actor");
    }
}
