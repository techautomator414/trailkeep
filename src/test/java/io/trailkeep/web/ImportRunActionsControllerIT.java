// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The run request and the sign-off over HTTP (WP-26 part s2). The list offers "Request an import run" in a native
 * dialog and as its own page; a well-formed source id opens the run and goes to its page, a malformed one comes back
 * with 422, escaped. A Compliance Admin sees the sign-off on a completed dry run with no FATAL issue: one checkbox per
 * WARNING class and the recorded scanning mode; signing off goes back to the run, which now says who signed it off and
 * what was acknowledged. A missing acknowledgement is 422 with the ticks kept, a stale form 409 with Reload, a FATAL
 * issue hides the form and refuses the post. A System Admin sees no form and may not post (403); a team Manager gets
 * 403 everywhere; a post without CSRF is 403 and anonymous requests go to the identity provider. Rolled back after
 * each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ImportRunActionsControllerIT {

    private static final long SYSTEM_ADMIN = 988_501L;
    private static final long COMPLIANCE_ADMIN = 988_502L;
    private static final long MANAGER = 988_503L;
    private static final long RUN = 988_601L;
    private static final String PAGE = "/admin/import-runs/988601";
    private static final String SIGN_OFF = PAGE + "/sign-off";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seed() {
        user(SYSTEM_ADMIN, "SYSTEM_ADMIN");
        user(COMPLIANCE_ADMIN, "COMPLIANCE_ADMIN");
        user(MANAGER, null);
        Instant at = Instant.now().minusSeconds(3_600);
        jdbc.update(
                "INSERT INTO IMPORT_RUN (ID, KIND, SOURCE_ID, STATUS, REQUESTED_BY, REQUESTED_AT, SCAN_MODE,"
                        + " UPDATED_AT) VALUES (?, 'IMPORT', 'synthetic-c', 'DRY_RUN', ?, ?, 'EXTERNAL', ?)",
                RUN,
                COMPLIANCE_ADMIN,
                Timestamp.from(at),
                Timestamp.from(at));
        issue(1, "WARNING", "NOT_SCANNED");
        issue(2, "WARNING", "AMBIGUOUS_PERSON");
        issue(3, "ERROR", "TITLE_MISSING");
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void runsAreRequestedFromTheListOrTheirOwnPage() throws Exception {
        assertThat(html(get("/admin/import-runs"), 200))
                .contains("<a class=\"btn btn-primary\" href=\"/admin/import-runs/new\""
                        + " data-tk-dialog-open=\"tk-request-dialog\">Request an import run</a>")
                .contains("<dialog id=\"tk-request-dialog\" class=\"tk-dialog\" aria-labelledby=\"tk-request-title\">")
                .contains("data-tk-dialog-focus=\"true\"", "data-tk-dialog-close=\"true\"");
        assertThat(html(get("/admin/import-runs/new"), 200))
                .contains("<h1>Request an import run</h1>", "<label class=\"form-label\" for=\"tk-source-id\">")
                .doesNotContain("data-tk-dialog-focus", "aria-invalid=\"true\"");

        mvc.perform(post("/admin/import-runs").with(csrf()).param("sourceId", "legacy-a1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/admin/import-runs/*"));
        entityManager.flush();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM IMPORT_RUN WHERE SOURCE_ID = 'legacy-a1' AND REQUESTED_BY = ?",
                        Long.class,
                        SYSTEM_ADMIN))
                .isEqualTo(1);
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void malformedSourceIdComesBackEscapedWith422() throws Exception {
        assertThat(html(post("/admin/import-runs").with(csrf()).param("sourceId", "<b>Legacy"), 422))
                .contains("data-tk-error-summary", "aria-invalid=\"true\"", "value=\"&lt;b&gt;Legacy\"")
                .contains("Not requested: the source id is 1 to 16 lowercase letters, digits or hyphens.")
                .doesNotContain("<b>Legacy");
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void theRequesterSignsOffWithEveryWarningClassAcknowledged() throws Exception {
        assertThat(html(get(PAGE), 200))
                .contains("<form method=\"post\" action=\"" + SIGN_OFF + "\">")
                .contains("<input type=\"hidden\" name=\"version\" value=\"0\">")
                .contains("<p>The load must run in the scanning mode this dry run recorded: External: attachments are"
                        + " stored as not scanned.</p>")
                .contains("<legend class=\"h6\">Acknowledge each warning class</legend>")
                .contains("value=\"AMBIGUOUS_PERSON\" id=\"tk-ack-0\"", "value=\"NOT_SCANNED\" id=\"tk-ack-1\"")
                .contains("<label class=\"form-check-label\" for=\"tk-ack-1\">I have reviewed the NOT_SCANNED"
                        + " warnings</label>")
                .contains(">Sign off import run 988601</button>")
                .doesNotContain("value=\"TITLE_MISSING\"");

        mvc.perform(signOff("0", "NOT_SCANNED", "AMBIGUOUS_PERSON"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(PAGE));
        assertThat(html(get(PAGE), 200))
                .contains("<dd class=\"col-sm-8\">Signed off, waiting to load</dd>")
                .contains("<dd class=\"col-sm-8\">AMBIGUOUS_PERSON, NOT_SCANNED</dd>", "This run is signed off.")
                .doesNotContain("/sign-off");
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void missingAcknowledgementIs422WithTheTicksKept() throws Exception {
        assertThat(html(signOff("0", "NOT_SCANNED"), 422))
                .contains("Not signed off: acknowledge every warning class.")
                .contains("value=\"AMBIGUOUS_PERSON\" id=\"tk-ack-0\">")
                .contains("value=\"NOT_SCANNED\" id=\"tk-ack-1\" checked=\"checked\">");
        assertThat(approved()).isZero();
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void staleFormIs409WithReloadAndNoForm() throws Exception {
        assertThat(html(signOff("x", "NOT_SCANNED", "AMBIGUOUS_PERSON"), 409))
                .contains("<h3 id=\"tk-conflict-title\" class=\"h5\">This run changed meanwhile</h3>")
                .contains("<a class=\"btn btn-primary\" href=\"" + PAGE + "\">Reload</a>")
                .doesNotContain("action=\"" + SIGN_OFF + "\"");
        assertThat(approved()).isZero();
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void fatalIssueHidesTheSignOffAndRefusesThePost() throws Exception {
        issue(4, "FATAL", "DEFAULT_OWNER_MISSING");

        assertThat(html(get(PAGE), 200))
                .contains("Sign-off needs a completed dry run without fatal issues that nobody has signed off yet.")
                .doesNotContain("action=\"" + SIGN_OFF + "\"");
        mvc.perform(signOff("0", "NOT_SCANNED", "AMBIGUOUS_PERSON")).andExpect(status().isConflict());
        assertThat(approved()).isZero();
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsSeeNoSignOffAndMayNotPostOne() throws Exception {
        assertThat(html(get(PAGE), 200))
                .contains("Only a Compliance Admin can sign this run off.")
                .doesNotContain("action=\"" + SIGN_OFF + "\"");
        mvc.perform(signOff("0", "NOT_SCANNED", "AMBIGUOUS_PERSON")).andExpect(status().isForbidden());
        assertThat(approved()).isZero();
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void postsWithoutCsrfAreForbidden() throws Exception {
        mvc.perform(post(SIGN_OFF).param("version", "0").param("acknowledged", "NOT_SCANNED", "AMBIGUOUS_PERSON"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/import-runs").param("sourceId", "legacy-a1")).andExpect(status().isForbidden());
        assertThat(approved()).isZero();
    }

    @Test
    @WithAppUser(id = MANAGER)
    void anyoneElseIsForbidden() throws Exception {
        mvc.perform(get("/admin/import-runs/new")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/import-runs").with(csrf()).param("sourceId", "legacy-a1"))
                .andExpect(status().isForbidden());
        mvc.perform(signOff("0", "NOT_SCANNED", "AMBIGUOUS_PERSON")).andExpect(status().isForbidden());
        assertThat(approved()).isZero();
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/admin/import-runs/new")).andExpect(status().isFound());
        mvc.perform(post("/admin/import-runs").with(csrf()).param("sourceId", "legacy-a1"))
                .andExpect(status().isFound());
        mvc.perform(signOff("0", "NOT_SCANNED", "AMBIGUOUS_PERSON")).andExpect(status().isFound());
    }

    private MockHttpServletRequestBuilder signOff(String version, String... acknowledged) {
        return post(SIGN_OFF).with(csrf()).param("version", version).param("acknowledged", acknowledged);
    }

    private long approved() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM IMPORT_RUN WHERE ID = ? AND APPROVED_BY IS NOT NULL", Long.class, RUN);
        return count == null ? 0 : count;
    }

    private String html(MockHttpServletRequestBuilder request, int status) throws Exception {
        return mvc.perform(request)
                .andExpect(status().is(status))
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll("\\s+", " ")
                .replace("> <", "><");
    }

    private void user(long id, @Nullable String role) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "import-action-" + id,
                "Synthetic Import Actor " + id);
        if (role != null) {
            jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", id, role);
        }
    }

    private void issue(long id, String severity, String code) {
        jdbc.update(
                "INSERT INTO IMPORT_ISSUE (ID, RUN_ID, STAGE, ENTITY, KEY_HASH, SEVERITY, CODE, CREATED_AT)"
                        + " VALUES (?, ?, 'DRY_RUN', 'CASE', ?, ?, ?, CURRENT_TIMESTAMP)",
                988_700L + id,
                RUN,
                "ab".repeat(31) + id + id,
                severity,
                code);
    }
}
