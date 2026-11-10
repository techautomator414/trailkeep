// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.TeamRole;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * A purged case still opens (WP-24 part c): after the purge job tombstones a seeded case with a description, a
 * subject and a comment, a Manager of its team finds it through the cases list's Archived filter, its page answers 200
 * with "Content removed on (day) under the retention policy" in place of the subject and description and no comment,
 * and its History shows the purge, newest first, with what it removed.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PurgedCasePageIT {

    private static final long MANAGER = 986_901L;
    private static final long TEAM = 986_911L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PurgeJob purge;

    private CaseNumber number;
    private final Instant now = Instant.now();

    @BeforeEach
    void seedAndPurge() {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, 'purged-page-manager', 'Synthetic Purge Manager', 'user@example.invalid', FALSE,"
                        + " CURRENT_TIMESTAMP)",
                MANAGER);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Purge Team')", TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(MANAGER, TEAM, TeamRole.MANAGER);
        number = seed.caseRow(TEAM, MANAGER, Sensitivity.STANDARD, CaseStatus.ARCHIVED);
        long id = seed.caseId(number);
        seed.comment(id, MANAGER, "Synthetic comment the purge removes");
        jdbc.update("INSERT INTO CASE_SLA (CASE_ID, SLA_STATE) VALUES (?, 'NOT_APPLICABLE')", id);
        jdbc.update(
                "UPDATE CASE_RECORD SET SUBJECT_TEXT = 'Synthetic subject', CLOSED_AT = ?, ARCHIVED_AT = ?,"
                        + " PURGE_AFTER = ? WHERE ID = ?",
                Timestamp.from(now.minus(Duration.ofDays(3000))),
                Timestamp.from(now.minus(Duration.ofDays(40))),
                Timestamp.from(now.minus(Duration.ofDays(10))),
                id);
        assertThat(purge.run(now).get("cases")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void purgedCasesOpenFromTheArchivedListWithTheirHistory() throws Exception {
        assertThat(page("/cases?status=ARCHIVED")).contains("/cases/" + number);
        assertThat(page("/cases/" + number))
                .contains("<p class=\"alert alert-secondary\">Content removed on "
                        + LocalDate.ofInstant(now, ZoneOffset.UTC) + " under the retention policy.</p>")
                .contains("Seed case " + seed())
                .doesNotContain("Synthetic description", "Synthetic subject", "Synthetic comment the purge removes");
        assertThat(page("/cases/" + number + "/history"))
                .contains("Content removed by the retention purge", "Comments removed")
                .doesNotContain("Synthetic comment the purge removes");
    }

    private long seed() {
        Long id =
                jdbc.queryForObject("SELECT ID FROM CASE_RECORD WHERE CASE_NUMBER = ?", Long.class, number.toString());
        return id == null ? -1 : id;
    }

    private String page(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(">\\s+<", "><");
    }
}
