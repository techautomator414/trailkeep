// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
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
 * The case page (GET only, so no CSRF case): a team member reads it; a Reader asking for a restricted case, a member
 * of another team, a System Admin, an absent number and a malformed number all get the same 404 page. Permissions
 * come from seeded database rows.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CaseControllerIT {

    static final long ANALYST = 920_001L;
    static final long READER = 920_002L;
    static final long OTHER_MANAGER = 920_003L;
    static final long SYSTEM_ADMIN = 920_004L;
    static final long TEAM = 920_100L;
    static final long OTHER_TEAM = 920_101L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    private CaseNumber standard;
    private CaseNumber restricted;

    @BeforeEach
    void seed() {
        user(ANALYST, "Synthetic Analyst", null);
        user(READER, "Synthetic Reader", null);
        user(OTHER_MANAGER, "Synthetic Other Manager", null);
        user(SYSTEM_ADMIN, "Synthetic System Admin", GlobalRole.SYSTEM_ADMIN);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Case Team')", TEAM);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Other Team')", OTHER_TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        seed.member(READER, TEAM, TeamRole.READER);
        seed.member(OTHER_MANAGER, OTHER_TEAM, TeamRole.MANAGER);
        CaseService.Actor analyst = new CaseService.Actor(
                new UserPermissions(ANALYST, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)), "Synthetic Analyst");
        standard = cases.create(analyst, fields(seed.caseType(), "Synthetic standard case", Sensitivity.STANDARD));
        restricted =
                cases.create(analyst, fields(seed.caseType(), "Synthetic restricted case", Sensitivity.RESTRICTED));
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void theOwnerReadsBothCases() throws Exception {
        mvc.perform(get("/cases/{number}", restricted.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Synthetic restricted case")))
                .andExpect(content().string(containsString(restricted.toString())))
                .andExpect(content().string(containsString("Restricted")))
                .andExpect(content().string(containsString("Synthetic Analyst")))
                .andExpect(content().string(containsString("Synthetic Case Team")));
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void readersReadStandardCasesAndCannotTellRestrictedOnesFromAbsentOnes() throws Exception {
        mvc.perform(get("/cases/{number}", standard.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Synthetic standard case")))
                .andExpect(content().string(containsString("SLA: ")))
                .andExpect(content().string(not(containsString("Restricted"))));

        String unreadable = notFound(restricted.toString());
        String absent = notFound(new CaseNumber("CASE", 2026, 9_999_999_999L).toString());
        assertThat(unreadable).isEqualTo(absent).doesNotContain("Synthetic restricted case");
        assertThat(notFound("not-a-case-number")).isEqualTo(absent);
    }

    @Test
    @WithAppUser(id = OTHER_MANAGER, team = OTHER_TEAM, teamRole = TeamRole.MANAGER)
    void anotherTeamsManagerGetsNotFound() throws Exception {
        notFound(standard.toString());
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsGetNotFound() throws Exception {
        notFound(standard.toString());
    }

    private String notFound(String number) throws Exception {
        return mvc.perform(get("/cases/{number}", number))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString()
                // The per-request CSP nonce and CSRF token differ between responses.
                .replaceAll("nonce=\"[^\"]*\"", "")
                .replaceAll("(content|value)=\"[A-Za-z0-9_-]{40,}\"", "");
    }

    private static CaseFields fields(long caseType, String title, Sensitivity sensitivity) {
        return new CaseFields(
                caseType,
                title,
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC),
                TEAM,
                ANALYST,
                null,
                "Synthetic description",
                sensitivity);
    }

    private void user(long id, String name, @Nullable GlobalRole role) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "case-subject-" + id,
                name);
        if (role != null) {
            jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", id, role.name());
        }
    }
}
