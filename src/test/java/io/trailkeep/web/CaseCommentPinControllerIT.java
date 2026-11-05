// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.TeamRole;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pinning and unpinning a comment per Part E (WP-13 part 3): the allowed role (Analyst) pins, sees the pinned section
 * and the notice once, then unpins, each audited as {@code COMMENT_PINNED} / {@code COMMENT_UNPINNED} and repeated
 * requests changing nothing; the nearest disallowed role (Reader) sees the pin but no buttons and gets 403; a case the
 * person may not read answers 404 even with a bad comment id; a missing CSRF token is 403; and an id naming a
 * comment of another case, or nothing, is 422. Permissions come from seeded database rows.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CaseCommentPinControllerIT {

    static final long ANALYST = 970_001L;
    static final long READER = 970_002L;
    static final long OTHER_MANAGER = 970_003L;
    static final long TEAM = 970_100L;
    static final long OTHER_TEAM = 970_101L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private CaseNumber number;
    private long caseId;
    private long comment;
    private long commentOfAnotherCase;

    @BeforeEach
    void seed() {
        final CaseSeed seed = new CaseSeed(jdbc);
        user(ANALYST, "Synthetic Pin Analyst");
        user(READER, "Synthetic Pin Reader");
        user(OTHER_MANAGER, "Synthetic Pin Other Manager");
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Pin Team')", TEAM);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Other Pin Team')", OTHER_TEAM);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        seed.member(READER, TEAM, TeamRole.READER);
        seed.member(OTHER_MANAGER, OTHER_TEAM, TeamRole.MANAGER);
        number = seed.caseRow(TEAM, ANALYST, Sensitivity.STANDARD, CaseStatus.OPEN);
        caseId = seed.caseId(number);
        comment = seed.comment(caseId, ANALYST, "A synthetic **pinnable** comment");
        long otherCase = seed.caseId(seed.caseRow(TEAM, ANALYST, Sensitivity.STANDARD, CaseStatus.OPEN));
        commentOfAnotherCase = seed.comment(otherCase, ANALYST, "A synthetic comment on another case");
    }

    @Test
    @WithAppUser(id = ANALYST, name = "Synthetic Pin Analyst", team = TEAM, teamRole = TeamRole.ANALYST)
    void analystsPinAndUnpinAuditedWithTheNoticeOnce() throws Exception {
        mvc.perform(get("/cases/" + number))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/cases/" + number + "/comments/pin\"")))
                .andExpect(content().string(containsString("aria-describedby=\"comment-by-" + comment + "\"")))
                .andExpect(content().string(not(containsString("id=\"pinned-comments\""))));

        Cookie session = Objects.requireNonNull(mvc.perform(change("pin", String.valueOf(comment)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + number + "#comment-" + comment))
                .andReturn()
                .getResponse()
                .getCookie("SESSION"));
        mvc.perform(get("/cases/" + number).cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The comment was pinned.")))
                .andExpect(content().string(containsString("id=\"pinned-comments\"")))
                .andExpect(content().string(containsString("id=\"pinned-by-" + comment + "\"")))
                .andExpect(content().string(containsString("action=\"/cases/" + number + "/comments/unpin\"")))
                .andExpect(content().string(not(containsString("action=\"/cases/" + number + "/comments/pin\""))));
        mvc.perform(get("/cases/" + number).cookie(session))
                .andExpect(content().string(not(containsString("The comment was pinned."))));
        mvc.perform(change("pin", String.valueOf(comment))).andExpect(status().isFound());
        assertThat(pins()).containsExactly(comment);

        mvc.perform(change("unpin", String.valueOf(comment))).andExpect(status().isFound());
        mvc.perform(change("unpin", String.valueOf(comment))).andExpect(status().isFound());
        assertThat(pins()).isEmpty();
        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT ACTION, ACTOR_ID, ENTITY_TYPE, ENTITY_ID, OLD_VALUES_JSON, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE CASE_ID = ? ORDER BY ID",
                caseId);
        assertThat(events)
                .extracting(event -> event.get("ACTION"))
                .containsExactly("COMMENT_PINNED", "COMMENT_UNPINNED");
        assertThat(events)
                .allSatisfy(event -> assertThat(event)
                        .containsEntry("ACTOR_ID", ANALYST)
                        .containsEntry("ENTITY_TYPE", "COMMENT")
                        .containsEntry("ENTITY_ID", comment));
        assertThat((String) events.get(0).get("NEW_VALUES_JSON")).contains("\"pinned\":true");
        assertThat((String) events.get(1).get("NEW_VALUES_JSON")).contains("\"pinned\":false");
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void readersSeePinsButMayNotChangeThem() throws Exception {
        new CaseSeed(jdbc).pin(comment, caseId, ANALYST);

        mvc.perform(get("/cases/" + number))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"pinned-comments\"")))
                .andExpect(content().string(not(containsString("/comments/unpin\""))));
        mvc.perform(change("unpin", String.valueOf(comment))).andExpect(status().isForbidden());
        mvc.perform(change("pin", "not-a-number")).andExpect(status().isForbidden());
        assertThat(pins()).containsExactly(comment);
    }

    @Test
    @WithAppUser(id = OTHER_MANAGER, team = OTHER_TEAM, teamRole = TeamRole.MANAGER)
    void unreadableCasesAreNotFoundEvenWithBadCommentIds() throws Exception {
        mvc.perform(change("pin", String.valueOf(comment))).andExpect(status().isNotFound());
        mvc.perform(change("pin", "")).andExpect(status().isNotFound());
        mvc.perform(post("/cases/CASE-2026-999999/comments/pin")
                        .param("comment", "1")
                        .with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(pins()).isEmpty();
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void pinsNeedCsrfTokens() throws Exception {
        mvc.perform(post("/cases/" + number + "/comments/pin").param("comment", String.valueOf(comment)))
                .andExpect(status().isForbidden());
        assertThat(pins()).isEmpty();
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void commentsOfAnotherCaseOrNoCommentAreUnprocessable() throws Exception {
        mvc.perform(change("pin", String.valueOf(commentOfAnotherCase))).andExpect(status().isUnprocessableContent());
        mvc.perform(change("pin", "not-a-number")).andExpect(status().isUnprocessableContent());
        mvc.perform(post("/cases/" + number + "/comments/pin").with(csrf()))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(change("unpin", String.valueOf(commentOfAnotherCase))).andExpect(status().isUnprocessableContent());
        assertThat(pins()).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM CASE_COMMENT_PIN WHERE COMMENT_ID = ?", Long.class, commentOfAnotherCase))
                .as("no pin anywhere names the other case's comment")
                .isZero();
    }

    private MockHttpServletRequestBuilder change(String change, String commentId) {
        return post("/cases/" + number + "/comments/" + change)
                .param("comment", commentId)
                .with(csrf());
    }

    private List<Long> pins() {
        entityManager.flush();
        return jdbc.queryForList("SELECT COMMENT_ID FROM CASE_COMMENT_PIN WHERE CASE_ID = ?", Long.class, caseId);
    }

    private void user(long id, String name) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "pin-subject-" + id,
                name);
    }
}
