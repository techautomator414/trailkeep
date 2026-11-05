// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Releasing a quarantined attachment per Part E (WP-14 part 4, BUILD_PLAN §4.3): a Compliance Admin releases it with a
 * reason, it is stored {@code RELEASED} with who, when and why, audited once as {@code ATTACHMENT_RELEASED} with the
 * reason redacted, announced once, badged Released and downloadable. The refusals come in order: 404 for an unreadable
 * or absent case and for another case's id (before the reason is looked at), 403 for the nearest disallowed role (a
 * Manager of the team), then 422 for a blank or too long reason (the form again, the text kept) or a file that is not
 * quarantined, {@code NOT_SCANNED} included. A missing CSRF token is 403. Two concurrent releases release once and
 * write one audit event. Rows are committed (the concurrent test needs it) and removed afterwards.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CaseAttachmentReleaseIT {

    private static final long BASE = 965_000L;
    static final long MANAGER = BASE + 3;
    static final long ADMIN = BASE + 4;
    static final long OTHER_MANAGER = BASE + 5;
    static final long TEAM = BASE + 100;
    static final long OTHER_TEAM = BASE + 101;

    private static final byte[] BYTES = "X5O synthetic quarantined content".getBytes(StandardCharsets.US_ASCII);
    private static final String REASON = "Synthetic reason: the signature is a known false positive";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AttachmentContentJdbcDao content;

    @Autowired
    private CaseAttachmentService attachments;

    private AttachmentFixture fixture;
    private long quarantined;

    @BeforeEach
    void seed() {
        fixture = new AttachmentFixture(jdbc, content, BASE);
        quarantined = fixture.attachment(fixture.number, "eicar.txt", ScanStatus.QUARANTINED, BYTES);
    }

    @AfterEach
    void remove() {
        fixture.remove();
    }

    @Test
    @WithAppUser(id = ADMIN, name = "Synthetic Compliance Admin", globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminsReleaseWithReasonAndTheFileBecomesDownloadable() throws Exception {
        Cookie session = Objects.requireNonNull(release(fixture.number, quarantined, REASON)
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + fixture.number + "#attachment-" + quarantined))
                .andReturn()
                .getResponse()
                .getCookie("SESSION"));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT SCAN_STATUS, SCAN_DETAIL, RELEASED_BY, RELEASED_AT, RELEASED_REASON FROM CASE_ATTACHMENT"
                        + " WHERE ID = ?",
                quarantined);
        assertThat(row)
                .containsEntry("SCAN_STATUS", "RELEASED")
                .containsEntry("SCAN_DETAIL", "Synthetic.Test.Signature")
                .containsEntry("RELEASED_BY", ADMIN)
                .containsEntry("RELEASED_REASON", REASON);
        assertThat(row.get("RELEASED_AT")).isNotNull();
        List<Map<String, Object>> events = releaseEvents();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst())
                .containsEntry("ENTITY_TYPE", "ATTACHMENT")
                .containsEntry("ENTITY_ID", quarantined)
                .containsEntry("ACTOR_ID", ADMIN);
        assertThat(String.valueOf(events.getFirst().get("OLD_VALUES_JSON"))).contains("\"scanStatus\":\"QUARANTINED\"");
        assertThat(String.valueOf(events.getFirst().get("NEW_VALUES_JSON")))
                .contains("\"scanStatus\":\"RELEASED\"", "\"hmac\"")
                .doesNotContain("false positive");

        mvc.perform(get("/cases/" + fixture.number).cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The file was released.")))
                .andExpect(content().string(containsString(">Released<")))
                .andExpect(content()
                        .string(containsString(
                                "href=\"/cases/" + fixture.number + "/attachments/" + quarantined + "\"")));
        MvcResult started = mvc.perform(get("/cases/" + fixture.number + "/attachments/" + quarantined))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(BYTES);
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void theReleaseFormWorksWithoutJavaScript() throws Exception {
        mvc.perform(get("/cases/" + fixture.number + "/attachments/" + quarantined + "/release"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<h1>Release a quarantined file</h1>")))
                .andExpect(content().string(containsString("File: eicar.txt")))
                .andExpect(content().string(containsString("name=\"reason\"")));
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void managersAre403AndNothingChanges() throws Exception {
        release(fixture.number, quarantined, REASON).andExpect(status().isForbidden());
        mvc.perform(get("/cases/" + fixture.number + "/attachments/" + quarantined + "/release"))
                .andExpect(status().isForbidden());
        assertUnreleased();
    }

    @Test
    @WithAppUser(id = OTHER_MANAGER, team = OTHER_TEAM, teamRole = TeamRole.MANAGER)
    void anUnreadableCaseIs404BeforeTheReasonIsLookedAt() throws Exception {
        release(fixture.number, quarantined, "").andExpect(status().isNotFound());
        assertUnreleased();
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void anAbsentCaseAndAnotherCasesIdAre404() throws Exception {
        long sibling = fixture.attachment(fixture.sibling, "sibling.txt", ScanStatus.QUARANTINED, BYTES);
        release(new CaseNumber("SEED", 2026, 1), quarantined, REASON).andExpect(status().isNotFound());
        release(fixture.number, sibling, REASON).andExpect(status().isNotFound());
        mvc.perform(post("/cases/" + fixture.number + "/attachments/not-an-id/release")
                        .param("reason", REASON)
                        .with(csrf()))
                .andExpect(status().isNotFound());
        assertUnreleased();
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void missingCsrfTokenIs403() throws Exception {
        mvc.perform(post("/cases/" + fixture.number + "/attachments/" + quarantined + "/release")
                        .param("reason", REASON))
                .andExpect(status().isForbidden());
        assertUnreleased();
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void blankOrTooLongReasonIs422WithTheFormAgain() throws Exception {
        release(fixture.number, quarantined, "   ")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("The file was not released.")))
                .andExpect(content().string(containsString("aria-invalid=\"true\"")));
        String tooLong = "y".repeat(1001);
        release(fixture.number, quarantined, tooLong)
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString(tooLong)));
        assertUnreleased();
        // A browser counts a CRLF as one character, as the form's maxlength does.
        release(fixture.number, quarantined, "z".repeat(998) + "\r\n!").andExpect(status().isFound());
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void filesThatAreNotQuarantinedAre422NotScannedIncluded() throws Exception {
        long clean = fixture.attachment(fixture.number, "clean.txt", ScanStatus.CLEAN, BYTES);
        long upstream = fixture.attachment(fixture.number, "upstream.txt", ScanStatus.NOT_SCANNED, BYTES);
        release(fixture.number, clean, REASON).andExpect(status().isUnprocessableContent());
        release(fixture.number, upstream, REASON).andExpect(status().isUnprocessableContent());
        mvc.perform(get("/cases/" + fixture.number + "/attachments/" + upstream + "/release"))
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("SELECT SCAN_STATUS FROM CASE_ATTACHMENT WHERE ID = ?", String.class, upstream))
                .isEqualTo("NOT_SCANNED");
        assertThat(releaseEvents()).isEmpty();
    }

    @Test
    void twoConcurrentReleasesReleaseOnceWithOneAuditEvent() throws Exception {
        CyclicBarrier together = new CyclicBarrier(2);
        Callable<Boolean> release = () -> {
            together.await();
            return attachments.release(fixture.actor(fixture.admin), fixture.number.toString(), quarantined, REASON);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Boolean> outcomes = pool.invokeAll(List.of(release, release)).stream()
                    .map(CaseAttachmentReleaseIT::outcome)
                    .toList();
            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdown();
        }
        assertThat(releaseEvents()).hasSize(1);
    }

    private ResultActions release(CaseNumber number, long id, String reason) throws Exception {
        return mvc.perform(post("/cases/" + number + "/attachments/" + id + "/release")
                .param("reason", reason)
                .with(csrf()));
    }

    private void assertUnreleased() {
        assertThat(jdbc.queryForObject(
                        "SELECT SCAN_STATUS FROM CASE_ATTACHMENT WHERE ID = ?", String.class, quarantined))
                .isEqualTo("QUARANTINED");
        assertThat(releaseEvents()).isEmpty();
    }

    private List<Map<String, Object>> releaseEvents() {
        return jdbc.queryForList(
                "SELECT ENTITY_TYPE, ENTITY_ID, ACTOR_ID, OLD_VALUES_JSON, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID IN (?, ?))"
                        + " AND ACTION = 'ATTACHMENT_RELEASED'",
                TEAM,
                OTHER_TEAM);
    }

    private static Boolean outcome(Future<Boolean> outcome) {
        try {
            return outcome.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
