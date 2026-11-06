// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The schema carries the ceilings the admin screens state (V4.6): 3650 target days, 365 reminder days and 100
 * retention years are accepted, one more is refused by the database itself, whatever the application validated.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SlaCheckCeilingsIT {

    private static final long POLICY = 9_000_000_001L;
    private static final long CASE_TYPE = 9_000_000_002L;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void targetDaysAtTheCeilingAreAccepted() {
        assertThatCode(() -> policy(3650, 7)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT TARGET_DAYS FROM SLA_POLICY WHERE ID = ?", Integer.class, POLICY))
                .isEqualTo(3650);
    }

    @Test
    void targetDaysAboveTheCeilingAreRefused() {
        assertThatThrownBy(() -> policy(3651, 7))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_sla_target_max");
    }

    @Test
    void reminderDaysAtTheCeilingAreAccepted() {
        assertThatCode(() -> policy(30, 365)).doesNotThrowAnyException();
    }

    @Test
    void reminderDaysAboveTheCeilingAreRefused() {
        assertThatThrownBy(() -> policy(30, 366))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_sla_reminder_max");
    }

    @Test
    void retentionYearsAtTheCeilingAreAccepted() {
        policy(30, 7);

        assertThatCode(() -> caseType(100)).doesNotThrowAnyException();
    }

    @Test
    void retentionYearsAboveTheCeilingAreRefused() {
        policy(30, 7);

        assertThatThrownBy(() -> caseType(101))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_case_type_retention_max");
    }

    private void policy(int targetDays, int reminderDays) {
        jdbc.update(
                "INSERT INTO SLA_POLICY (ID, NAME, TARGET_DAYS, OVERDUE_REMINDER_DAYS) VALUES (?, ?, ?, ?)",
                POLICY,
                "Synthetic ceiling policy",
                targetDays,
                reminderDays);
    }

    private void caseType(int retentionYears) {
        jdbc.update(
                "INSERT INTO CASE_TYPE (ID, NAME, SLA_POLICY_ID, RETENTION_YEARS) VALUES (?, ?, ?, ?)",
                CASE_TYPE,
                "Synthetic ceiling type",
                POLICY,
                retentionYears);
    }
}
