// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.TeamRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A pin that meets a concurrent write of its case is a 409, not a 500 (issue 285). The pin loads the case, then locks
 * its row, and the lock raises the optimistic-lock conflict when the case was written in between. The write is
 * committed here from another connection right after the load, which is the interleaving a concurrent edit makes,
 * and the request must answer 409 like an edit that loses the same race: the problem fragment for htmx, the error
 * page otherwise, nothing pinned and no ERROR in the log. Committed rows (the request runs in its own transaction),
 * removed afterwards.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class CaseCommentPinConflictIT {

    private static final long ANALYST = 971_001L;
    private static final long TEAM = 971_100L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private CaseLoader loader;

    private CaseNumber number;
    private long caseId;
    private long comment;

    @BeforeEach
    void seed() {
        CaseSeed seed = new CaseSeed(jdbc);
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, 'pin-conflict-subject', 'Synthetic Pin Analyst', 'user@example.invalid', FALSE,"
                        + " CURRENT_TIMESTAMP)",
                ANALYST);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Pin Conflict Team')", TEAM);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        number = seed.caseRow(TEAM, ANALYST, Sensitivity.STANDARD, CaseStatus.OPEN);
        caseId = seed.caseId(number);
        comment = seed.comment(caseId, ANALYST, "A synthetic comment to pin");
    }

    @AfterEach
    void removeCommittedRows() {
        for (String table : new String[] {"CASE_COMMENT_PIN", "CASE_COMMENT", "AUDIT_EVENT", "CASE_SLA"}) {
            jdbc.update("DELETE FROM " + table + " WHERE CASE_ID = ?", caseId);
        }
        jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", caseId);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", TEAM);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", TEAM);
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", ANALYST);
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void pinAfterAConcurrentCaseWriteIsAConflictFragment(CapturedOutput output) throws Exception {
        writeCaseAfterNextLoad();

        mvc.perform(post("/cases/" + number + "/comments/pin")
                        .param("comment", String.valueOf(comment))
                        .header("HX-Request", "true")
                        .with(csrf()))
                .andExpect(status().isConflict());

        assertThat(pinCount()).isZero();
        assertThat(output.getAll()).doesNotContain(" ERROR ");
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void unpinAfterAConcurrentCaseWriteIsAConflictPage(CapturedOutput output) throws Exception {
        new CaseSeed(jdbc).pin(comment, caseId, ANALYST);
        writeCaseAfterNextLoad();

        mvc.perform(post("/cases/" + number + "/comments/unpin")
                        .param("comment", String.valueOf(comment))
                        .with(csrf()))
                .andExpect(status().isConflict());

        assertThat(pinCount()).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(" ERROR ");
    }

    /** The next load of the case is followed by another connection's committed write of it (its version moves on). */
    private void writeCaseAfterNextLoad() {
        doAnswer(invocation -> {
                    Object loaded = invocation.callRealMethod();
                    jdbc.update("UPDATE CASE_RECORD SET VERSION = VERSION + 1 WHERE ID = ?", caseId);
                    return loaded;
                })
                .when(loader)
                .load(any(), any(), any());
    }

    private long pinCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM CASE_COMMENT_PIN WHERE CASE_ID = ?", Long.class, caseId);
        return count == null ? 0 : count;
    }
}
