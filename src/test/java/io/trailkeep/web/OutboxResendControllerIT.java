// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
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
 * Failed mail over HTTP (#318): a Compliance Admin lists the failed rows with a masked recipient and a CSRF-protected
 * form, and re-queues one, which becomes {@code PENDING} with one audit event; a System Admin and an Analyst get 403 on
 * both the page and the post, as does a post without a CSRF token, and nothing changes; anonymous requests go to the
 * identity provider. Rows are rolled back after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OutboxResendControllerIT {

    private static final long COMPLIANCE_ADMIN = 986_001L;
    private static final long SYSTEM_ADMIN = 986_002L;
    private static final long ANALYST = 986_003L;
    private static final long MAIL = 986_101L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seed() {
        user(COMPLIANCE_ADMIN, "COMPLIANCE_ADMIN");
        user(SYSTEM_ADMIN, "SYSTEM_ADMIN");
        user(ANALYST, null);
        jdbc.update("DELETE FROM NOTIFICATION_OUTBOX");
        Timestamp at = Timestamp.from(Instant.now().minusSeconds(120));
        jdbc.update(
                "INSERT INTO NOTIFICATION_OUTBOX (ID, RECIPIENT, TEMPLATE_ID, SUBJECT, BODY_TEXT, BODY_HTML, STATUS,"
                        + " ATTEMPTS, NEXT_ATTEMPT_AT, LAST_ERROR, CREATED_AT) VALUES (?, 'alice@example.invalid',"
                        + " 'comment-added', 'Synthetic', 'Synthetic body', '<p>Synthetic body</p>', 'FAILED', 10, ?,"
                        + " '550 <alice@example.invalid> rejected', ?)",
                MAIL,
                at,
                at);
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminSeesTheFailedMailWithAMaskedRecipientAndAFormThatCarriesTheCsrfToken() throws Exception {
        String page = html("/admin/outbox");

        assertThat(page)
                .contains(
                        "<h1>Failed mail</h1>", "<caption id=\"tk-outbox-caption\">Failed mail, oldest first</caption>")
                .contains("<th scope=\"row\">a***@example.invalid</th>", "<td>comment-added</td>", "<td>10</td>")
                .contains("550 &lt;a***@example.invalid&gt; rejected", "name=\"_csrf\"")
                .contains("for=\"tk-outbox-" + MAIL + "\"")
                .doesNotContain("alice");
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void requeueingRedirectsWithTheCountsAndAuditsTheChange() throws Exception {
        mvc.perform(post("/admin/outbox/requeue")
                        .param("id", String.valueOf(MAIL))
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/outbox?requeued=1&skipped=0"));

        assertThat(mailStatus()).isEqualTo("PENDING");
        assertThat(audited()).isEqualTo(1);
        assertThat(html("/admin/outbox?requeued=1&skipped=0"))
                .contains("1 mails queued to be sent again.")
                .contains("No mail has failed.");
        mvc.perform(post("/admin/outbox/requeue")
                        .param("id", String.valueOf(MAIL))
                        .with(csrf()))
                .andExpect(redirectedUrl("/admin/outbox?requeued=0&skipped=1"));
        assertThat(audited()).isEqualTo(1);
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void postWithoutACsrfTokenIsForbiddenAndChangesNothing() throws Exception {
        mvc.perform(post("/admin/outbox/requeue").param("id", String.valueOf(MAIL)))
                .andExpect(status().isForbidden());

        assertThat(mailStatus()).isEqualTo("FAILED");
        assertThat(audited()).isZero();
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminIsForbiddenOnThePageAndThePost() throws Exception {
        mvc.perform(get("/admin/outbox")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/outbox/requeue")
                        .param("id", String.valueOf(MAIL))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(mailStatus()).isEqualTo("FAILED");
        assertThat(audited()).isZero();
    }

    @Test
    @WithAppUser(id = ANALYST)
    void anyoneElseIsForbiddenAndSeesNothingOfTheMail() throws Exception {
        String page = mvc.perform(get("/admin/outbox"))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();
        mvc.perform(post("/admin/outbox/requeue")
                        .param("id", String.valueOf(MAIL))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(page).doesNotContain("comment-added", "example.invalid");
        assertThat(mailStatus()).isEqualTo("FAILED");
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/admin/outbox")).andExpect(status().isFound());
        mvc.perform(post("/admin/outbox/requeue").with(csrf())).andExpect(status().isFound());
    }

    private String mailStatus() {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT STATUS FROM NOTIFICATION_OUTBOX WHERE ID = ?", String.class, MAIL));
    }

    private long audited() {
        entityManager.flush();
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ENTITY_TYPE = 'NOTIFICATION_OUTBOX' AND ENTITY_ID = ?"
                        + " AND ACTION = 'MAIL_REQUEUED'",
                Long.class,
                MAIL);
        return count == null ? 0 : count;
    }

    private String html(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(">\\s+<", "><");
    }

    private void user(long id, @Nullable String role) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)",
                id,
                "outbox-resend-subject-" + id,
                "Synthetic Outbox User " + id,
                "outbox-resend-" + id + "@example.invalid");
        if (role != null) {
            jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", id, role);
        }
    }
}
