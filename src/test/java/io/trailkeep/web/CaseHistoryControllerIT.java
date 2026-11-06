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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseChanges;
import io.trailkeep.casefile.CaseCommentService;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * The History page of a case (WP-16, BUILD_PLAN §4.3): the owner sees the case's events newest first with who, when,
 * origin and old to new for fields; a redacted field (description, subject, comment text, file name) is only "set" or
 * "changed", never its value, its HMAC or its length; owners and teams are named; a kind narrows the list and a bad
 * kind or page is 422; fifty events make a page; a colleague who may not read the case, and an absent number, get 404
 * and no event is written for them; and reading the history of a restricted case is itself a read, so it records
 * {@code VIEW_RESTRICTED}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CaseHistoryControllerIT {

    private static final long OWNER = 968_001L;
    private static final long COLLEAGUE = 968_002L;
    private static final long MANAGER = 968_003L;
    private static final long TEAM = 968_100L;

    private static final Pattern HMAC = Pattern.compile("\"hmac\":\"([0-9a-f]+)\"");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    @Autowired
    private CaseCommentService comments;

    @Autowired
    private AuditRecorder audit;

    @Autowired
    private EntityManager entityManager;

    private CaseSeed seed;
    private CaseNumber standard;
    private CaseNumber restricted;

    @BeforeEach
    void seed() {
        user(OWNER, "Synthetic History Owner");
        user(COLLEAGUE, "Synthetic History Colleague");
        user(MANAGER, "Synthetic History Manager");
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic History Team')", TEAM);
        seed = new CaseSeed(jdbc);
        seed.member(OWNER, TEAM, TeamRole.ANALYST);
        seed.member(COLLEAGUE, TEAM, TeamRole.ANALYST);
        seed.member(MANAGER, TEAM, TeamRole.MANAGER);
        standard = cases.create(actor(OWNER, TeamRole.ANALYST), fields(Sensitivity.STANDARD));
        restricted = cases.create(actor(OWNER, TeamRole.ANALYST), fields(Sensitivity.RESTRICTED));
        entityManager.flush();
    }

    @Test
    @WithAppUser(id = OWNER, name = "Synthetic History Owner", team = TEAM, teamRole = TeamRole.ANALYST)
    void ownerSeesTheTimelineWithoutAnyRedactedValue() throws Exception {
        Actor owner = actor(OWNER, TeamRole.ANALYST);
        cases.edit(
                owner,
                standard.toString(),
                0,
                new CaseChanges("Edited history title", CasePriority.HIGH, null, "Edited SECRETWORD-TWO description"));
        comments.post(owner, standard.toString(), "A comment with SECRETCOMMENT inside");
        mvc.perform(post("/cases/" + standard + "/reassign")
                        .param("teamId", Long.toString(TEAM))
                        .param("ownerId", Long.toString(COLLEAGUE))
                        .param("version", "1")
                        .with(csrf()))
                .andExpect(status().isFound());
        entityManager.flush();

        String page = mvc.perform(get("/cases/" + standard + "/history"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("History of " + standard)))
                .andExpect(content().string(containsString("Case created")))
                .andExpect(content().string(containsString("Case edited")))
                .andExpect(content().string(containsString("Comment added")))
                .andExpect(content().string(containsString("Case reassigned")))
                .andExpect(content().string(containsString("Synthetic History Owner")))
                .andExpect(content().string(containsString("Screen")))
                .andExpect(content().string(containsString("<caption")))
                .andExpect(content().string(containsString("scope=\"col\"")))
                .andExpect(content().string(containsString("<time datetime=\"")))
                // Plain values show old to new; owners are named, not numbered.
                .andExpect(content().string(containsString("Title: Synthetic history case → Edited history title")))
                .andExpect(content()
                        .string(containsString("Owner: Synthetic History Owner → Synthetic History Colleague")))
                // Redacted fields say only that they were set or changed.
                .andExpect(content().string(containsString("Description: (set)")))
                .andExpect(content().string(containsString("Description: (changed)")))
                .andExpect(content().string(containsString("Comment: (set)")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(page).doesNotContain("SECRETWORD", "SECRETCOMMENT", "hmac", "\"length\"", "Synthetic subject");
        List<String> digests = jdbc.queryForList(
                "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE CASE_ID = ? AND NEW_VALUES_JSON IS NOT NULL",
                String.class,
                seed.caseId(standard));
        int found = 0;
        for (String json : digests) {
            Matcher digest = HMAC.matcher(json);
            while (digest.find()) {
                found++;
                assertThat(page).doesNotContain(digest.group(1));
            }
        }
        assertThat(found)
                .as("the stored events do hold HMACs, so the check above is not vacuous")
                .isPositive();
        // Newest first: the reassignment is listed before the creation.
        assertThat(page.indexOf("Case reassigned")).isLessThan(page.indexOf("Case created"));
    }

    @Test
    @WithAppUser(id = COLLEAGUE, team = TEAM, teamRole = TeamRole.ANALYST)
    void colleagueWhoMayNotReadItAndAnAbsentNumberGet404AndNothingIsWritten() throws Exception {
        long caseId = seed.caseId(restricted);
        int before = events(caseId);

        mvc.perform(get("/cases/" + restricted + "/history")).andExpect(status().isNotFound());
        mvc.perform(get("/cases/CASE-2026-999999/history")).andExpect(status().isNotFound());
        mvc.perform(get("/cases/not-a-case/history")).andExpect(status().isNotFound());

        assertThat(events(caseId)).isEqualTo(before);
        // The same colleague reads the standard case's history.
        mvc.perform(get("/cases/" + standard + "/history")).andExpect(status().isOk());
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void readingTheHistoryOfARestrictedCaseIsItselfAuditedOncePerView() throws Exception {
        long caseId = seed.caseId(restricted);

        mvc.perform(get("/cases/" + restricted + "/history"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Restricted case viewed")));
        assertThat(views(caseId)).isEqualTo(1);
        mvc.perform(get("/cases/" + restricted + "/history?kind=views")).andExpect(status().isOk());
        assertThat(views(caseId)).isEqualTo(2);
        mvc.perform(get("/cases/" + standard + "/history")).andExpect(status().isOk());
        assertThat(views(seed.caseId(standard))).isZero();
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void kindNarrowsTheListAndUnknownKindOrPageIs422() throws Exception {
        comments.post(actor(OWNER, TeamRole.ANALYST), standard.toString(), "Only a comment");
        entityManager.flush();

        mvc.perform(get("/cases/" + standard + "/history?kind=comments"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Comment added")))
                .andExpect(content().string(not(containsString("Case created"))))
                .andExpect(content().string(containsString("selected")));
        mvc.perform(get("/cases/" + standard + "/history?kind=fields"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Case created")))
                .andExpect(content().string(not(containsString("Comment added"))));
        mvc.perform(get("/cases/" + standard + "/history?kind=bogus")).andExpect(status().isUnprocessableContent());
        mvc.perform(get("/cases/" + standard + "/history?page=0")).andExpect(status().isUnprocessableContent());
        mvc.perform(get("/cases/" + standard + "/history?page=x")).andExpect(status().isUnprocessableContent());
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void fiftyEventsMakeOnePageAndPlainLinksTurnIt() throws Exception {
        long caseId = seed.caseId(standard);
        for (int i = 0; i < 119; i++) {
            audit.record(new AuditEntry(
                    OWNER,
                    "Synthetic History Owner",
                    AuditOrigin.UI,
                    caseId,
                    "CASE",
                    caseId,
                    "CASE_UPDATED",
                    AuditChanges.none()));
        }
        entityManager.flush();

        String first = mvc.perform(get("/cases/" + standard + "/history"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("120 events found. Page 1 of 3.")))
                .andExpect(content().string(containsString("rel=\"next\"")))
                .andExpect(content().string(not(containsString("rel=\"prev\""))))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(rows(first)).isEqualTo(50);
        String last = mvc.perform(get("/cases/" + standard + "/history?page=3"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("rel=\"prev\"")))
                .andExpect(content().string(not(containsString("rel=\"next\""))))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(rows(last)).isEqualTo(20);
        assertThat(last).contains("Case created");
        assertThat(rows(mvc.perform(get("/cases/" + standard + "/history?page=9"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString()))
                .isZero();
    }

    private static int rows(String page) {
        return page.split("<time datetime=", -1).length - 1;
    }

    private int events(long caseId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENT WHERE CASE_ID = ?", Integer.class, caseId));
    }

    private int views(long caseId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM AUDIT_EVENT WHERE CASE_ID = ? AND ACTION = 'VIEW_RESTRICTED'",
                Integer.class,
                caseId));
    }

    private static Actor actor(long id, TeamRole role) {
        return new Actor(new UserPermissions(id, false, Set.of(), Map.of(TEAM, role)), "Synthetic History Owner");
    }

    private CaseFields fields(Sensitivity sensitivity) {
        return new CaseFields(
                seed.caseType(),
                "Synthetic history case",
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC).minusDays(1),
                TEAM,
                OWNER,
                "Synthetic subject",
                "Synthetic SECRETWORD-ONE description",
                sensitivity);
    }

    private void user(long id, String name) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "history-subject-" + id,
                name);
    }
}
