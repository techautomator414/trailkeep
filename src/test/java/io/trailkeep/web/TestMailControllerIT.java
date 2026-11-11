// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.notification.TestMailService;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
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
 * "Send me a test" over HTTP (WP-25 part e): a System Admin's test is queued through the outbox to their own address
 * only, with sample values and a link to the System screen, audited with the template id and never the address, and a
 * second one within 5 minutes is refused, also when the first was queued on another replica (the interval is read
 * from the audit trail); a person without an address is told so; CSRF is required, an unknown template
 * is 422, an Analyst 403, and every template renders with the sample values. Rows are rolled back after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TestMailControllerIT {

    private static final long SYSTEM_ADMIN = 989_001L;
    private static final long NO_ADDRESS = 989_002L;
    private static final long ANALYST = 989_003L;
    private static final long FIRST_OF_MANY = 989_100L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestMailService testMail;

    @Autowired
    private io.trailkeep.user.UserDirectoryService directory;

    @Autowired
    private EntityManager entityManager;

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsTestGoesToTheirOwnAddressOnceEveryFiveMinutes() throws Exception {
        user(SYSTEM_ADMIN, "SYSTEM_ADMIN", "sysadmin-test@example.invalid");
        mvc.perform(post("/admin/system/test-mail").param("template", "comment-added"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/admin/system/test-mail")
                        .param("template", "comment-added")
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/system?test=QUEUED#tk-test-mail"));
        entityManager.flush();
        List<Map<String, Object>> mails = jdbc.queryForList(
                "SELECT * FROM NOTIFICATION_OUTBOX WHERE RECIPIENT = 'sysadmin-test@example.invalid'");
        assertThat(mails).singleElement().satisfies(mail -> {
            assertThat(mail)
                    .containsEntry("TEMPLATE_ID", "comment-added")
                    .containsEntry("SUBJECT", "New comment on CASE-0000-000000")
                    .containsEntry("CASE_ID", null);
            assertThat(Objects.toString(mail.get("BODY_TEXT")))
                    .contains("A sample colleague commented on case CASE-0000-000000", "Sample case for a test mail")
                    .contains("https://trailkeep.example.invalid/admin/system");
        });
        assertThat(jdbc.queryForObject(
                        "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE ACTION = 'TEST_MAIL_QUEUED' AND ENTITY_ID = ?",
                        String.class,
                        SYSTEM_ADMIN))
                .contains("comment-added")
                .doesNotContain("example.invalid");

        mvc.perform(post("/admin/system/test-mail")
                        .param("template", "sla-overdue")
                        .with(csrf()))
                .andExpect(redirectedUrl("/admin/system?test=TOO_SOON#tk-test-mail"));
        entityManager.flush();
        assertThat(jdbc.queryForList(
                        "SELECT ID FROM NOTIFICATION_OUTBOX WHERE RECIPIENT = 'sysadmin-test@example.invalid'"))
                .hasSize(1);
        assertThat(page("/admin/system?test=TOO_SOON"))
                .contains("Not sent: you asked for a test mail less than 5 minutes ago.", "id=\"tk-test-template\"")
                .contains("<option value=\"sla-overdue-repeat\"", ">SLA still overdue</option>");
    }

    @Test
    @WithAppUser(id = NO_ADDRESS, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void personWithoutAnAddressIsToldSoAndAnUnknownTemplateIsRefused() throws Exception {
        user(NO_ADDRESS, "COMPLIANCE_ADMIN", "");
        mvc.perform(post("/admin/system/test-mail")
                        .param("template", "../layout")
                        .with(csrf()))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/admin/system/test-mail")
                        .param("template", "update-requested")
                        .with(csrf()))
                .andExpect(redirectedUrl("/admin/system?test=NO_ADDRESS#tk-test-mail"));
        assertThat(page("/admin/system?test=NO_ADDRESS"))
                .contains("Not sent: the identity provider gives no email address for you.");
        assertThat(page("/admin/system?test=SOMETHING")).doesNotContain("Not sent", "Test mail queued");
    }

    /** The interval is read from the audit trail, so a test queued on another replica counts too. */
    @Test
    void theIntervalComesFromTheAuditTrailNotFromThisReplica() {
        long other = FIRST_OF_MANY + 50;
        user(other, "COMPLIANCE_ADMIN", "other-replica@example.invalid");
        queuedElsewhere(-989_001L, other, Instant.now().minus(Duration.ofMinutes(3)));
        assertThat(testMail.sendTest(directory.permissionsOf(other), "Synthetic", "comment-added"))
                .isEqualTo(TestMailService.Outcome.TOO_SOON);

        jdbc.update("DELETE FROM AUDIT_EVENT WHERE ID = -989001");
        queuedElsewhere(-989_002L, other, Instant.now().minus(Duration.ofMinutes(6)));
        assertThat(testMail.sendTest(directory.permissionsOf(other), "Synthetic", "comment-added"))
                .isEqualTo(TestMailService.Outcome.QUEUED);
    }

    @Test
    @WithAppUser(id = ANALYST)
    void anyoneElseIsForbidden() throws Exception {
        user(ANALYST, null, "analyst-test@example.invalid");
        mvc.perform(post("/admin/system/test-mail")
                        .param("template", "comment-added")
                        .with(csrf()))
                .andExpect(status().isForbidden());
        entityManager.flush();
        assertThat(jdbc.queryForList(
                        "SELECT ID FROM NOTIFICATION_OUTBOX WHERE RECIPIENT = 'analyst-test@example.invalid'"))
                .isEmpty();
    }

    @Test
    void anonymousRequestsGoToTheIdentityProviderAndEveryTemplateRendersWithTheSampleValues() throws Exception {
        mvc.perform(post("/admin/system/test-mail")
                        .param("template", "comment-added")
                        .with(csrf()))
                .andExpect(status().isFound());
        for (int i = 0; i < TestMailService.TEMPLATES.size(); i++) {
            long id = FIRST_OF_MANY + i;
            String address = "template-" + i + "@example.invalid";
            user(id, "SYSTEM_ADMIN", address);
            assertThat(testMail.sendTest(directory.permissionsOf(id), "Synthetic", TestMailService.TEMPLATES.get(i)))
                    .isEqualTo(TestMailService.Outcome.QUEUED);
            entityManager.flush();
            assertThat(jdbc.queryForObject(
                            "SELECT BODY_TEXT FROM NOTIFICATION_OUTBOX WHERE RECIPIENT = ?", String.class, address))
                    .as(TestMailService.TEMPLATES.get(i))
                    .contains("/admin/system")
                    .doesNotContain("??");
        }
    }

    private String page(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** A TEST_MAIL_QUEUED row as another replica would have written it. */
    private void queuedElsewhere(long id, long userId, Instant at) {
        jdbc.update(
                "INSERT INTO AUDIT_EVENT (ID, AT_TS, ACTOR_ID, ORIGIN, ENTITY_TYPE, ENTITY_ID, ACTION)"
                        + " VALUES (?, ?, ?, 'UI', 'USER', ?, 'TEST_MAIL_QUEUED')",
                id,
                Timestamp.from(at),
                userId,
                userId);
    }

    private void user(long id, @Nullable String role, String email) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)",
                id,
                "test-mail-subject-" + id,
                "Synthetic Test Mail User " + id,
                email);
        if (role != null) {
            jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", id, role);
        }
    }
}
