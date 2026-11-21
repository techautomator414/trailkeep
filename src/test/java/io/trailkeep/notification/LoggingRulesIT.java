// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.casefile.CaseChanges;
import io.trailkeep.casefile.CaseCommentService;
import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.EditableCase;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.support.LoggingReset;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.updaterequest.UpdateRequestService;
import io.trailkeep.user.ExternalIdentity;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import io.trailkeep.user.UserSignInService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The logging rules of BUILD_PLAN §9 and Part B "Logging" (security test map gap 11), through the code paths that log
 * around a restricted case, with the log captured: restricted-case text (title, subject, description, comment, update
 * request, attachment names) never reaches the log, and people are logged by user id, never by email address.
 *
 * <p>Every text and every email address the test writes carries one fresh marker, so a single "the marker is absent"
 * covers them all. The paths run are: creating, opening and editing a restricted case; a comment; an upload that is
 * stored and one that is refused; an update request, which writes an alert and queues a mail; a mail dispatch that
 * fails against an unreachable server; a sign-in refused for a disabled user; and a role conflict at sign-in. Each is
 * asserted to have logged its line with the ids, so the absence of the marker is not the absence of logging. The
 * escaping of control characters is the JSON encoder's, asserted in {@code JsonLoggingIT}.
 *
 * <p>Nothing is rolled back: the dispatcher and the services commit their own transactions. Everything the test wrote
 * is removed afterwards, as the schema owner.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.attachments.scanning=external", "trailkeep.test.context=logging-rules"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LoggingRulesIT {

    static final long ANALYST = 992_001L;
    static final long MANAGER = 992_002L;
    static final long DISABLED = 992_003L;
    static final long CONFLICT = 992_004L;
    static final long TEAM = 992_100L;
    private static final long[] USERS = {ANALYST, MANAGER, DISABLED, CONFLICT};

    /** In every text and every email address the test writes; it must never be logged. */
    private static final String MARKER =
            "zqlog" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    @Autowired
    private CaseCommentService comments;

    @Autowired
    private UpdateRequestService updateRequests;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private UserSignInService signIn;

    private long caseType;

    @BeforeAll
    static void applyThisProfilesLoggingConfiguration() {
        LoggingReset.beforeNewContext();
    }

    @AfterAll
    static void letTheNextContextApplyItsOwn() {
        LoggingReset.beforeNewContext();
    }

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        for (long user : USERS) {
            jdbc.update(
                    "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    user,
                    "logging-" + user,
                    "Synthetic Logging User " + user,
                    email(user),
                    user == DISABLED,
                    now);
        }
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Logging Team')", TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        seed.member(MANAGER, TEAM, TeamRole.MANAGER);
        seed.member(CONFLICT, TEAM, TeamRole.READER);
        caseType = seed.caseType();
    }

    @AfterEach
    void remove() {
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = " + TEAM + ")";
        jdbc.update("DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN (SELECT ID FROM CASE_ATTACHMENT" + ofTeam
                + ")");
        for (String table : new String[] {
            "CASE_ATTACHMENT",
            "CASE_COMMENT",
            "UPDATE_REQUEST",
            "NOTIFICATION_OUTBOX",
            "USER_ALERT",
            "AUDIT_EVENT",
            "CASE_SEARCH",
            "CASE_SLA"
        }) {
            jdbc.update("DELETE FROM " + table + ofTeam);
        }
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", TEAM);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", TEAM);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", TEAM);
        for (long user : USERS) {
            jdbc.update("DELETE FROM NOTIFICATION_OUTBOX WHERE RECIPIENT = ?", email(user));
            jdbc.update("DELETE FROM AUDIT_EVENT WHERE ACTOR_ID = ?", user);
            jdbc.update("DELETE FROM APP_USER_GLOBAL_ROLE WHERE USER_ID = ?", user);
            jdbc.update("DELETE FROM APP_USER WHERE ID = ?", user);
        }
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    @WithAppUser(id = ANALYST, name = "Synthetic Logging User 992001", team = TEAM, teamRole = TeamRole.ANALYST)
    void restrictedCaseTextAndEmailAddressesNeverReachTheLogButTheIdsDo(CapturedOutput output) throws Exception {
        Actor analyst = actor(ANALYST, TeamRole.ANALYST);
        CaseNumber number = cases.create(
                analyst,
                new CaseFields(
                        caseType,
                        "Synthetic title " + MARKER,
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        TEAM,
                        ANALYST,
                        "Synthetic subject " + MARKER,
                        "Synthetic description " + MARKER,
                        Sensitivity.RESTRICTED));
        cases.open(analyst, number.toString());
        EditableCase editable = cases.editable(analyst, number.toString());
        cases.edit(
                analyst,
                number.toString(),
                editable.version(),
                new CaseChanges(
                        "Synthetic edited title " + MARKER,
                        CasePriority.HIGH,
                        "Synthetic edited subject " + MARKER,
                        "Synthetic edited description " + MARKER));
        comments.post(analyst, number.toString(), "Synthetic comment " + MARKER);
        mvc.perform(upload(number, MARKER + "-attached.pdf", "%PDF-1.7\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n"))
                .andExpect(status().isFound());
        mvc.perform(upload(number, MARKER + "-refused.pdf", "Synthetic text that is not a PDF"))
                .andExpect(status().isUnprocessableContent());
        updateRequests.request(analyst, number.toString(), MANAGER, "Synthetic question " + MARKER);
        dispatcher.dispatch();
        signIn.signIn(identity(DISABLED), Set.of());
        signIn.signIn(identity(CONFLICT), Set.of(GlobalRole.SYSTEM_ADMIN));

        String log = output.getAll();
        long caseId = new CaseSeed(jdbc).caseId(number);
        assertThat(log)
                .as("each path logged its line, with ids")
                .contains("Case " + number + " created in team " + TEAM)
                .contains("Case id " + caseId + " edited")
                .containsPattern("Comment \\d+ added to case id " + caseId)
                .containsPattern("Attachment \\d+ added to case id " + caseId)
                .containsPattern("Update request \\d+ on case id " + caseId + " sent to user " + MANAGER)
                .containsPattern("Alert \\d+ written for user " + MANAGER)
                .containsPattern("Mail \\d+ attempt 1 failed \\(\\w+\\)")
                .contains("Login refused: user " + DISABLED + " is disabled")
                .contains("SYSTEM_ADMIN claimed for user " + CONFLICT);
        assertThat(log)
                .as("restricted-case text, attachment names and email addresses (all carry the marker)")
                .doesNotContain(MARKER);
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile(
                "file", name, "application/octet-stream", content.getBytes(StandardCharsets.US_ASCII));
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload(
            CaseNumber number, String name, String content) {
        return multipart("/cases/" + number + "/attachments")
                .file(file(name, content))
                .with(csrf());
    }

    private static Actor actor(long user, TeamRole role) {
        return new Actor(
                new UserPermissions(user, false, Set.of(), Map.of(TEAM, role)), "Synthetic Logging User " + user);
    }

    private static ExternalIdentity identity(long user) {
        return new ExternalIdentity("logging-" + user, "Synthetic Logging User " + user, email(user));
    }

    private static String email(long user) {
        return MARKER + "." + user + "@example.invalid";
    }
}
