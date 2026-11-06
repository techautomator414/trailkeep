// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.TeamRole;
import java.util.Map;
import java.util.Objects;
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

/**
 * Reading a restricted case is audited (BUILD_PLAN §3.3, IMPLEMENTATION_PLAN A.2 #16): one {@code VIEW_RESTRICTED}
 * event per full page view and one per attachment download, written in the transaction of the read, naming the case
 * and the actor, and for a download the attachment. A STANDARD case, a refused read (404, 403, 503), an htmx request
 * and a 422 re-render of the page record nothing.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(properties = "app.attachments.max-concurrent-downloads=1")
@AutoConfigureMockMvc
class RestrictedViewAuditIT {

    private static final long BASE = 967_000L;
    private static final long OWNER = BASE + 1;
    private static final long READER = BASE + 2;
    private static final long MANAGER = BASE + 3;
    private static final long TEAM = BASE + 100;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AttachmentContentJdbcDao content;

    @Autowired
    private CaseAttachmentService attachments;

    private AttachmentFixture fixture;

    @BeforeEach
    void seed() {
        fixture = new AttachmentFixture(jdbc, content, BASE);
    }

    @AfterEach
    void remove() {
        fixture.remove();
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void ownerWritesOneEventPerFullPageView() throws Exception {
        CaseNumber restricted = fixture.restrictedCase();
        long caseId = fixture.caseId(restricted);

        mvc.perform(get("/cases/" + restricted)).andExpect(status().isOk());
        assertThat(views(caseId)).isEqualTo(1);
        mvc.perform(get("/cases/" + restricted)).andExpect(status().isOk());
        assertThat(views(caseId)).isEqualTo(2);

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT ACTOR_ID, ORIGIN, ENTITY_TYPE, ENTITY_ID, OLD_VALUES_JSON, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE CASE_ID = ? AND ACTION = 'VIEW_RESTRICTED' FETCH FIRST 1 ROWS ONLY",
                caseId);
        assertThat(event)
                .containsEntry("ACTOR_ID", OWNER)
                .containsEntry("ORIGIN", "UI")
                .containsEntry("ENTITY_TYPE", "CASE")
                .containsEntry("ENTITY_ID", caseId)
                .containsEntry("OLD_VALUES_JSON", null)
                .containsEntry("NEW_VALUES_JSON", null);
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void managerWhoMayReadItWritesOneEventToo() throws Exception {
        CaseNumber restricted = fixture.restrictedCase();
        long caseId = fixture.caseId(restricted);

        mvc.perform(get("/cases/" + restricted)).andExpect(status().isOk());

        assertThat(views(caseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT ACTOR_ID FROM AUDIT_EVENT WHERE CASE_ID = ? AND ACTION = 'VIEW_RESTRICTED'",
                        Long.class,
                        caseId))
                .isEqualTo(MANAGER);
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void standardCaseWritesNothing() throws Exception {
        mvc.perform(get("/cases/" + fixture.number)).andExpect(status().isOk());

        assertThat(views(fixture.caseId(fixture.number))).isZero();
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void colleagueWhoGets404WritesNothing() throws Exception {
        CaseNumber restricted = fixture.restrictedCase();

        mvc.perform(get("/cases/" + restricted)).andExpect(status().isNotFound());

        assertThat(anyEvents(fixture.caseId(restricted))).isZero();
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void htmxRequestAndInvalidCommentRerenderWriteNothing() throws Exception {
        CaseNumber restricted = fixture.restrictedCase();
        long caseId = fixture.caseId(restricted);

        mvc.perform(get("/cases/" + restricted).header("HX-Request", "true")).andExpect(status().isOk());
        assertThat(views(caseId)).isZero();

        mvc.perform(post("/cases/" + restricted + "/comments")
                        .param("body", "   ")
                        .with(csrf()))
                .andExpect(status().isUnprocessableContent());
        assertThat(views(caseId)).isZero();
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void downloadFromRestrictedCaseWritesOneEventNamingTheAttachment() {
        CaseNumber restricted = fixture.restrictedCase();
        long id = fixture.attachment(restricted, "synthetic.txt", ScanStatus.CLEAN, new byte[] {'x'});

        attachments
                .download(fixture.actor(fixture.analyst), restricted.toString(), id)
                .release();

        long caseId = fixture.caseId(restricted);
        assertThat(views(caseId)).isEqualTo(1);
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT ACTOR_ID, ENTITY_TYPE, ENTITY_ID, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE CASE_ID = ? AND ACTION = 'VIEW_RESTRICTED'",
                caseId);
        assertThat(event)
                .containsEntry("ACTOR_ID", OWNER)
                .containsEntry("ENTITY_TYPE", "CASE")
                .containsEntry("ENTITY_ID", caseId);
        assertThat(String.valueOf(event.get("NEW_VALUES_JSON"))).contains("attachmentId", Long.toString(id));
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void downloadFromStandardCaseWritesNothing() {
        long id = fixture.attachment(fixture.number, "synthetic.txt", ScanStatus.CLEAN, new byte[] {'x'});

        attachments
                .download(fixture.actor(fixture.analyst), fixture.number.toString(), id)
                .release();

        assertThat(views(fixture.caseId(fixture.number))).isZero();
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void refusedDownloadsWriteNothing() {
        CaseNumber restricted = fixture.restrictedCase();
        long quarantined = fixture.attachment(restricted, "eicar.txt", ScanStatus.QUARANTINED, new byte[] {'x'});
        long clean = fixture.attachment(restricted, "synthetic.txt", ScanStatus.CLEAN, new byte[] {'x'});
        long caseId = fixture.caseId(restricted);
        Actor actor = fixture.actor(fixture.analyst);

        assertThatThrownBy(() -> attachments.download(actor, restricted.toString(), quarantined))
                .isInstanceOf(AttachmentException.class);
        assertThat(views(caseId)).isZero();

        // The only slot is taken by a download that is recorded; the next is 503 and adds nothing.
        final CaseAttachmentService.Download held = attachments.download(actor, restricted.toString(), clean);
        assertThat(views(caseId)).isEqualTo(1);
        assertThatThrownBy(() -> attachments.download(actor, restricted.toString(), clean))
                .isInstanceOf(DownloadsBusyException.class);
        assertThat(views(caseId)).isEqualTo(1);
        held.release();
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void downloadByColleagueWhoGets404WritesNothing() {
        CaseNumber restricted = fixture.restrictedCase();
        long id = fixture.attachment(restricted, "synthetic.txt", ScanStatus.CLEAN, new byte[] {'x'});
        Actor reader = fixture.actor(fixture.reader);

        assertThatThrownBy(() -> attachments.download(reader, restricted.toString(), id))
                .isInstanceOf(RuntimeException.class);

        assertThat(anyEvents(fixture.caseId(restricted))).isZero();
    }

    private int views(long caseId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM AUDIT_EVENT WHERE CASE_ID = ? AND ACTION = 'VIEW_RESTRICTED'",
                Integer.class,
                caseId));
    }

    private int anyEvents(long caseId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENT WHERE CASE_ID = ?", Integer.class, caseId));
    }
}
