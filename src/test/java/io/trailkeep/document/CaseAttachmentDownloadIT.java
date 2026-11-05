// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import java.io.OutputStream;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Downloading an attachment (WP-14 part 4, BUILD_PLAN §4.3, §9) through the endpoint, with the streamed body read in
 * its own transaction (so the rows are committed and removed afterwards). An Analyst and a Reader of the team get the
 * bytes, chunk by chunk, with {@code Content-Disposition: attachment}, {@code nosniff}, the stored type, the exact
 * length and {@code Cache-Control: private, no-store}; an unreadable, absent or malformed case is 404, and so is an id
 * of another case, readable or not, or no id at all; a quarantined file is 403; a {@code NOT_SCANNED} one downloads.
 * With one download slot ({@code app.attachments.max-concurrent-downloads=1}) a second download is 503 with
 * {@code Retry-After} while the slot is held, and the slot comes back after each download and is never taken by a
 * refusal. Hostile names cannot split the header. On the page, only downloadable files are links, and only a
 * Compliance Admin is offered Release, beside a quarantined file only.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(properties = "app.attachments.max-concurrent-downloads=1")
@AutoConfigureMockMvc
class CaseAttachmentDownloadIT {

    private static final long BASE = 964_000L;
    static final long ANALYST = BASE + 1;
    static final long READER = BASE + 2;
    static final long MANAGER = BASE + 3;
    static final long ADMIN = BASE + 4;
    static final long TEAM = BASE + 100;

    /** Two and a half chunks, so the last one is partial. */
    private static final byte[] BYTES = random(5 * AttachmentContentJdbcDao.CHUNK_SIZE / 2);

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
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void anAnalystGetsTheStreamedFileWithItsHeaders() throws Exception {
        long id = fixture.attachment(fixture.number, "synthetic report.txt", ScanStatus.CLEAN, BYTES);
        MockHttpServletResponse response = download(fixture.number, id);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"synthetic report.txt\"; filename*=UTF-8''synthetic%20report.txt");
        assertThat(response.getHeaders("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(response.getHeaders("Cache-Control")).containsExactly("private, no-store");
        assertThat(response.getContentType()).isEqualTo("text/plain");
        assertThat(response.getHeader("Content-Length")).isEqualTo(Integer.toString(BYTES.length));
        assertThat(response.getContentAsByteArray()).isEqualTo(BYTES);
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void readersMayDownloadAndNotScannedFilesDownload() throws Exception {
        long id = fixture.attachment(fixture.number, "upstream.txt", ScanStatus.NOT_SCANNED, BYTES);
        MockHttpServletResponse response = download(fixture.number, id);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(BYTES);
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void unreadableAbsentAndMalformedCasesAre404() throws Exception {
        long foreign = fixture.attachment(fixture.foreign, "foreign.txt", ScanStatus.CLEAN, BYTES);
        refused(fixture.foreign, Long.toString(foreign), 404);
        refused(new CaseNumber("SEED", 2026, 1), Long.toString(foreign), 404);
        mvc.perform(get("/cases/not-a-case/attachments/" + foreign)).andExpect(status().isNotFound());
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void anIdOfAnotherCaseIs404AsAnAbsentOneIs() throws Exception {
        long sibling = fixture.attachment(fixture.sibling, "sibling.txt", ScanStatus.CLEAN, BYTES);
        long foreign = fixture.attachment(fixture.foreign, "foreign.txt", ScanStatus.CLEAN, BYTES);
        refused(fixture.number, Long.toString(sibling), 404);
        refused(fixture.number, Long.toString(foreign), 404);
        refused(fixture.number, "999999999", 404);
        refused(fixture.number, "not-an-id", 404);
        assertThat(download(fixture.sibling, sibling).getStatus()).isEqualTo(200);
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void quarantinedFilesAre403EvenForComplianceAdminsAndTakeNoSlot() throws Exception {
        long quarantined = fixture.attachment(fixture.number, "eicar.txt", ScanStatus.QUARANTINED, BYTES);
        long clean = fixture.attachment(fixture.number, "clean.txt", ScanStatus.CLEAN, BYTES);
        refused(fixture.number, Long.toString(quarantined), 403);
        refused(fixture.number, Long.toString(quarantined), 403);
        assertThat(download(fixture.number, clean).getStatus()).isEqualTo(200);
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void fullGateIs503WithRetryAfterAndEachDownloadGivesItsSlotBack() throws Exception {
        long id = fixture.attachment(fixture.number, "clean.txt", ScanStatus.CLEAN, BYTES);
        // An opened download holds the only slot until its body is written.
        CaseAttachmentService.Download held =
                attachments.download(fixture.actor(ANALYST), fixture.number.toString(), id);
        try {
            mvc.perform(get("/cases/" + fixture.number + "/attachments/" + id))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(request().asyncNotStarted());
        } finally {
            held.writeTo(OutputStream.nullOutputStream());
        }
        for (int i = 0; i < 3; i++) {
            assertThat(download(fixture.number, id).getStatus()).isEqualTo(200);
        }
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void hostileNamesCannotSplitOrExtendTheHeader() throws Exception {
        long crlf = fixture.attachment(fixture.number, "a\"\r\nSet-Cookie: tk=1; x=\"y.txt", ScanStatus.CLEAN, BYTES);
        MockHttpServletResponse response = download(fixture.number, crlf);
        assertThat(response.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"a___Set-Cookie: tk=1; x=_y.txt\";"
                        + " filename*=UTF-8''a%22%0D%0ASet-Cookie%3A%20tk%3D1%3B%20x%3D%22y.txt");
        assertThat(response.getHeaderNames()).doesNotContain("Set-Cookie");
        long unicode = fixture.attachment(fixture.number, "résumé 100%.pdf", ScanStatus.CLEAN, BYTES);
        assertThat(download(fixture.number, unicode).getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"r_sum_ 100_.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9%20100%25.pdf");
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void onlyDownloadableFilesAreLinksAndOnlyQuarantinedOnesOfferRelease() throws Exception {
        long clean = fixture.attachment(fixture.number, "clean.txt", ScanStatus.CLEAN, BYTES);
        long quarantined = fixture.attachment(fixture.number, "eicar.txt", ScanStatus.QUARANTINED, BYTES);
        long upstream = fixture.attachment(fixture.number, "upstream.txt", ScanStatus.NOT_SCANNED, BYTES);
        String base = "/cases/" + fixture.number + "/attachments/";
        mvc.perform(get("/cases/" + fixture.number))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"" + base + clean + "\"")))
                .andExpect(content().string(containsString("href=\"" + base + upstream + "\"")))
                .andExpect(content().string(not(containsString("href=\"" + base + quarantined + "\""))))
                .andExpect(content().string(containsString("href=\"" + base + quarantined + "/release\"")))
                .andExpect(content().string(containsString("id=\"tk-release-" + quarantined + "\"")))
                .andExpect(content().string(not(containsString(base + upstream + "/release"))))
                .andExpect(content().string(not(containsString(base + clean + "/release"))));
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void managersAreNotOfferedRelease() throws Exception {
        long quarantined = fixture.attachment(fixture.number, "eicar.txt", ScanStatus.QUARANTINED, BYTES);
        mvc.perform(get("/cases/" + fixture.number))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("eicar.txt")))
                .andExpect(content().string(not(containsString("/attachments/" + quarantined))))
                .andExpect(content().string(not(containsString("tk-release-"))));
    }

    /** Starts a download and runs its streamed body, as the container's async dispatch would. */
    private MockHttpServletResponse download(CaseNumber number, long id) throws Exception {
        MvcResult started = mvc.perform(get("/cases/" + number + "/attachments/" + id))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mvc.perform(asyncDispatch(started)).andReturn().getResponse();
    }

    private void refused(CaseNumber number, String id, int expected) throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/cases/" + number + "/attachments/" + id))
                .andExpect(request().asyncNotStarted())
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).isEqualTo(expected);
        assertThat(response.getHeader("Content-Disposition")).isNull();
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new Random(20261105L).nextBytes(bytes);
        return bytes;
    }
}
