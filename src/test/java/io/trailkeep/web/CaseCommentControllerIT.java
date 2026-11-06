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

import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * Posting a comment per Part E (WP-13): the allowed role (Analyst) posts, the comment is listed with its author
 * snapshot and rendered through the sanitizer, its text is appended to the search text and {@code COMMENT_ADDED} is
 * audited with the body redacted; the nearest disallowed role (Reader) sees the comments but no form and gets 403; a
 * case the person may not read answers 404, even with an invalid body; a missing CSRF token is 403; a blank body and
 * one over 20 000 characters are 422 with the text kept and the error summary shown. Permissions come from seeded
 * database rows.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CaseCommentControllerIT {

    static final long ANALYST = 960_001L;
    static final long READER = 960_002L;
    static final long OTHER_MANAGER = 960_003L;
    static final long TEAM = 960_100L;
    static final long OTHER_TEAM = 960_101L;

    private static final String MARKDOWN = "Synthetic **bold** note <script>alert(1)</script>";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    @Autowired
    private EntityManager entityManager;

    private CaseNumber number;

    @BeforeEach
    void seed() {
        user(ANALYST, "Synthetic Comment Analyst");
        user(READER, "Synthetic Comment Reader");
        user(OTHER_MANAGER, "Synthetic Comment Other Manager");
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Comment Team')", TEAM);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Other Comment Team')", OTHER_TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        seed.member(READER, TEAM, TeamRole.READER);
        seed.member(OTHER_MANAGER, OTHER_TEAM, TeamRole.MANAGER);
        number = cases.create(
                new Actor(
                        new UserPermissions(ANALYST, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)),
                        "Synthetic Comment Analyst"),
                new CaseFields(
                        seed.caseType(),
                        "Synthetic comment case",
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        TEAM,
                        ANALYST,
                        "Synthetic subject",
                        "Synthetic description",
                        Sensitivity.STANDARD));
        entityManager.flush();
    }

    @Test
    @WithAppUser(id = ANALYST, name = "Synthetic Comment Analyst", team = TEAM, teamRole = TeamRole.ANALYST)
    void analystsPostAuditedSanitizedCommentsAndSeeTheNoticeOnce() throws Exception {
        mvc.perform(get("/cases/" + number))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No comments yet.")))
                .andExpect(content().string(containsString("action=\"/cases/" + number + "/comments\"")))
                .andExpect(content().string(containsString("aria-describedby=\"body-hint\"")));

        Cookie session = Objects.requireNonNull(mvc.perform(comment(MARKDOWN + "\r\nSecond line"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + number + "#comments"))
                .andReturn()
                .getResponse()
                .getCookie("SESSION"));
        mvc.perform(get("/cases/" + number).cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Your comment was posted.")))
                .andExpect(content().string(containsString("Synthetic Comment Analyst wrote on")))
                .andExpect(content().string(containsString("<strong>bold</strong>")))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert"))))
                .andExpect(content().string(not(containsString("No comments yet."))));
        mvc.perform(get("/cases/" + number).cookie(session))
                .andExpect(content().string(not(containsString("Your comment was posted."))));
        entityManager.flush();

        long caseId = new CaseSeed(jdbc).caseId(number);
        assertThat(jdbc.queryForMap(
                        "SELECT BODY, AUTHOR_ID, AUTHOR_NAME_SNAPSHOT FROM CASE_COMMENT WHERE CASE_ID = ?", caseId))
                .containsEntry("BODY", MARKDOWN + "\nSecond line")
                .containsEntry("AUTHOR_ID", ANALYST)
                .containsEntry("AUTHOR_NAME_SNAPSHOT", "Synthetic Comment Analyst");
        assertThat(jdbc.queryForObject("SELECT SEARCH_TEXT FROM CASE_SEARCH WHERE CASE_ID = ?", String.class, caseId))
                .isEqualTo("synthetic comment case\nsynthetic description\n" + MARKDOWN.toLowerCase(Locale.ROOT)
                        + "\nsecond line");
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT ACTOR_ID, ENTITY_TYPE, OLD_VALUES_JSON, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE CASE_ID = ? AND ACTION = 'COMMENT_ADDED'",
                caseId);
        assertThat(event)
                .containsEntry("ACTOR_ID", ANALYST)
                .containsEntry("ENTITY_TYPE", "COMMENT")
                .containsEntry("OLD_VALUES_JSON", null);
        assertThat((String) event.get("NEW_VALUES_JSON"))
                .contains("\"body\"", "\"hmac\"", "\"length\":" + (MARKDOWN.length() + 12))
                .doesNotContain("Synthetic **bold**");
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void readersSeeCommentsButMayNotPost() throws Exception {
        new CaseSeed(jdbc).comment(new CaseSeed(jdbc).caseId(number), ANALYST, "A synthetic *earlier* comment");

        mvc.perform(get("/cases/" + number))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<em>earlier</em>")))
                .andExpect(content().string(not(containsString("/comments\""))));
        mvc.perform(comment("Synthetic reader comment")).andExpect(status().isForbidden());
        mvc.perform(comment(" ")).andExpect(status().isForbidden());
        assertComments(1);
    }

    @Test
    @WithAppUser(id = OTHER_MANAGER, team = OTHER_TEAM, teamRole = TeamRole.MANAGER)
    void unreadableCasesAreNotFoundEvenWithInvalidBodies() throws Exception {
        mvc.perform(comment("Synthetic probe")).andExpect(status().isNotFound());
        mvc.perform(comment(" ")).andExpect(status().isNotFound());
        mvc.perform(post("/cases/CASE-2026-999999/comments")
                        .param("body", "Probe")
                        .with(csrf()))
                .andExpect(status().isNotFound());
        assertComments(0);
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void commentsNeedCsrfTokens() throws Exception {
        mvc.perform(post("/cases/" + number + "/comments").param("body", "No token"))
                .andExpect(status().isForbidden());
        assertComments(0);
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void blankAndOverlongCommentsAreUnprocessableAndKeepTheText() throws Exception {
        mvc.perform(comment("  \r\n "))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("Your comment was not posted. Correct this field")))
                .andExpect(content().string(containsString("href=\"#body\"")))
                .andExpect(content().string(containsString("aria-describedby=\"body-error body-hint\"")));
        mvc.perform(post("/cases/" + number + "/comments").with(csrf())).andExpect(status().isUnprocessableContent());
        String overlong = "<b>x</b>" + "y".repeat(20_000);
        mvc.perform(comment(overlong))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("&lt;b&gt;x&lt;/b&gt;yyy")))
                .andExpect(content().string(containsString("Synthetic comment case")));
        assertComments(0);

        mvc.perform(comment("z".repeat(19_998) + "\r\nz")).andExpect(status().isFound());
        assertComments(1);
    }

    private MockHttpServletRequestBuilder comment(String body) {
        return post("/cases/" + number + "/comments").param("body", body).with(csrf());
    }

    private void assertComments(long count) {
        entityManager.flush();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM CASE_COMMENT WHERE CASE_ID = ?",
                        Long.class,
                        new CaseSeed(jdbc).caseId(number)))
                .isEqualTo(count);
    }

    private void user(long id, String name) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "comment-subject-" + id,
                name);
    }
}
