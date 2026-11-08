// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.ReportFixture;
import io.trailkeep.testsupport.RequiresDocker;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * The first cycle after go-live (BUILD_PLAN §6): with {@code app.reports.admins-only}, a delivered run is stored as
 * always, but only the Compliance Admins are told; the team's Managers are not.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "app.reports.admins-only=true")
@Transactional
class ReportDeliveryAdminsOnlyIT {

    @Autowired
    private ReportDeliveryTick tick;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private InstantSource clock;

    @Autowired
    private AppProperties app;

    @Test
    void onlyTheComplianceAdminsAreToldDuringTheFirstCycle() {
        Instant now = clock.instant();
        new ReportFixture(jdbc, LocalDate.ofInstant(now, app.timezone()), app.timezone()).seed();
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, CREATED_AT) VALUES (?, ?, ?, ?, ?)",
                AbstractReportDeliveryIT.ADMIN,
                "report-admin",
                "Synthetic Report Admin",
                "report-admin@example.test",
                Timestamp.from(now));
        jdbc.update(
                "INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'COMPLIANCE_ADMIN')",
                AbstractReportDeliveryIT.ADMIN);
        ZonedDateTime created = now.minus(Duration.ofDays(8)).atZone(app.timezone());
        entityManager.persist(new ReportSchedule(
                ReportFixture.ALPHA, DayOfWeek.from(created), created.getHour(), created.toInstant(), app.timezone()));

        assertThat(tick.run(now)).isEqualTo(1);
        entityManager.flush();

        assertThat(told(ReportFixture.MANAGER)).isZero();
        assertThat(told(AbstractReportDeliveryIT.ADMIN)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM REPORT_RUN WHERE TEAM_ID = ?", Long.class, ReportFixture.ALPHA))
                .isEqualTo(1);
    }

    private long told(long user) {
        Long alerts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM USER_ALERT WHERE USER_ID = ? AND LINK LIKE '/reports/runs/%'", Long.class, user);
        return alerts == null ? 0 : alerts;
    }
}
