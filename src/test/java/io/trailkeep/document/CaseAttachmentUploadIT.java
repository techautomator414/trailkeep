// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

/**
 * Uploading an attachment per Part E (WP-14 part 3) in {@code clamd} mode. The scanner bean delegates to a real clamd
 * behind TLS ({@link RealClamd}) unless a test makes it unavailable, so EICAR is quarantined by ClamAV itself: the
 * allowed role (Analyst) uploads a clean PDF, stored {@code CLEAN} with its chunks and audited with the name redacted
 * and {@code scanMode} {@code CLAMD}, and EICAR, stored {@code QUARANTINED} with ClamAV's signature; the nearest
 * disallowed role (Reader) sees the list but no form and gets 403; an unreadable case answers 404 even with a bad
 * file; a missing CSRF token is 403; a disguised executable, a file over the cap, a mismatched extension and no file
 * are 422 with the reason; an unavailable scanner is 503 with "try again" and nothing stored. After every request the
 * upload temp directory is empty.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CaseAttachmentUploadIT {

    static final long ANALYST = 961_001L;
    static final long READER = 961_002L;
    static final long OTHER_MANAGER = 961_003L;
    static final long TEAM = 961_100L;
    static final long OTHER_TEAM = 961_101L;

    /** The cap here, so "too large" needs only a little over 1 MiB. */
    private static final DataSize CAP = DataSize.ofMegabytes(1);

    private static final Path TEMP = tempDir();

    @MockitoBean
    private VirusScanner scanner;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    @Autowired
    private EntityManager entityManager;

    private CaseNumber number;

    @DynamicPropertySource
    static void uploads(DynamicPropertyRegistry registry) {
        registry.add("app.attachments.temp-dir", TEMP::toString);
        registry.add("app.attachments.max-bytes", CAP::toString);
    }

    @BeforeEach
    void seed() {
        when(scanner.scan(any())).thenAnswer(call -> realClamd().scan(call.getArgument(0)));
        user(ANALYST, "Synthetic Upload Analyst");
        user(READER, "Synthetic Upload Reader");
        user(OTHER_MANAGER, "Synthetic Upload Other Manager");
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Upload Team')", TEAM);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Other Upload Team')", OTHER_TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        seed.member(READER, TEAM, TeamRole.READER);
        seed.member(OTHER_MANAGER, OTHER_TEAM, TeamRole.MANAGER);
        number = cases.create(
                new Actor(
                        new UserPermissions(ANALYST, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)),
                        "Synthetic Upload Analyst"),
                new CaseFields(
                        seed.caseType(),
                        "Synthetic upload case",
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        TEAM,
                        ANALYST,
                        "Synthetic subject",
                        "Synthetic description",
                        Sensitivity.STANDARD));
        entityManager.flush();
    }

    @AfterEach
    void tempFilesAreGone() throws IOException {
        try (Stream<Path> left = Files.list(TEMP)) {
            assertThat(left).as("upload temp files left behind").isEmpty();
        }
    }

    @Test
    @WithAppUser(id = ANALYST, name = "Synthetic Upload Analyst", team = TEAM, teamRole = TeamRole.ANALYST)
    void analystsUploadACleanFileThatIsStoredVerifiedAndAudited() throws Exception {
        mvc.perform(get("/cases/" + number))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No attachments yet.")))
                .andExpect(content().string(containsString("action=\"/cases/" + number + "/attachments\"")))
                .andExpect(content().string(containsString("enctype=\"multipart/form-data\"")))
                .andExpect(content().string(containsString("Allowed types: CSV, DOCX, EML, JPEG, JPG, MSG, PDF")))
                .andExpect(content().string(containsString("Maximum size:")));

        Cookie session = Objects.requireNonNull(mvc.perform(upload("report.pdf", UploadSamples.pdf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + number + "#attachments"))
                .andReturn()
                .getResponse()
                .getCookie("SESSION"));
        mvc.perform(get("/cases/" + number).cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The virus scan found nothing")))
                .andExpect(content().string(containsString("report.pdf")))
                .andExpect(content().string(containsString("Synthetic Upload Analyst on ")))
                .andExpect(content().string(containsString(">Verified<")))
                .andExpect(content().string(not(containsString("No attachments yet."))));
        entityManager.flush();

        long caseId = new CaseSeed(jdbc).caseId(number);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT ID, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, SCAN_STATUS, SCAN_DETAIL, UPLOADED_BY"
                        + " FROM CASE_ATTACHMENT WHERE CASE_ID = ?",
                caseId);
        assertThat(row)
                .containsEntry("FILE_NAME", "report.pdf")
                .containsEntry("CONTENT_TYPE", "application/pdf")
                .containsEntry("SIZE_BYTES", (long) UploadSamples.pdf().length)
                .containsEntry("SCAN_STATUS", "CLEAN")
                .containsEntry("SCAN_DETAIL", null)
                .containsEntry("UPLOADED_BY", ANALYST);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID = ?",
                        Long.class,
                        row.get("ID")))
                .isEqualTo(1);
        String audit = auditOf(caseId);
        assertThat(audit)
                .contains("\"fileName\"", "\"hmac\"", "\"scanMode\":\"CLAMD\"", "\"scanStatus\":\"CLEAN\"")
                .doesNotContain("report.pdf");
    }

    @Test
    @WithAppUser(id = ANALYST, name = "Synthetic Upload Analyst", team = TEAM, teamRole = TeamRole.ANALYST)
    void eicarIsStoredQuarantinedWithTheSignatureClamavFound() throws Exception {
        Cookie session = Objects.requireNonNull(mvc.perform(upload("C:\\synthetic\\eicar.txt", UploadSamples.eicar()))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getCookie("SESSION"));
        mvc.perform(get("/cases/" + number).cookie(session))
                .andExpect(content().string(containsString("the virus scan found a threat")))
                .andExpect(content().string(containsString(">Quarantined<")))
                .andExpect(content().string(not(containsString(">Verified<"))));
        entityManager.flush();

        long caseId = new CaseSeed(jdbc).caseId(number);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT FILE_NAME, SCAN_STATUS, SCAN_DETAIL FROM CASE_ATTACHMENT WHERE CASE_ID = ?", caseId);
        assertThat(row).containsEntry("FILE_NAME", "eicar.txt").containsEntry("SCAN_STATUS", "QUARANTINED");
        assertThat((String) row.get("SCAN_DETAIL")).containsIgnoringCase("eicar");
        assertThat(auditOf(caseId)).contains("\"scanStatus\":\"QUARANTINED\"", "\"scanMode\":\"CLAMD\"");
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void readersSeeAttachmentsButMayNotUpload() throws Exception {
        CaseSeed seed = new CaseSeed(jdbc);
        seed.attachment(seed.caseId(number), ANALYST, "<i>seeded</i>.txt", "QUARANTINED");

        mvc.perform(get("/cases/" + number))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;i&gt;seeded&lt;/i&gt;.txt")))
                .andExpect(content().string(containsString(">Quarantined<")))
                .andExpect(content().string(not(containsString("/attachments\""))))
                .andExpect(content().string(not(containsString("type=\"file\""))));
        mvc.perform(upload("notes.txt", UploadSamples.ascii("Synthetic notes"))).andExpect(status().isForbidden());
        mvc.perform(upload("report.pdf", UploadSamples.windowsExecutable())).andExpect(status().isForbidden());
        assertAttachments(1);
        verifyNoInteractions(scanner);
    }

    @Test
    @WithAppUser(id = OTHER_MANAGER, team = OTHER_TEAM, teamRole = TeamRole.MANAGER)
    void unreadableCasesAreNotFoundEvenWithBadFiles() throws Exception {
        mvc.perform(upload("notes.txt", UploadSamples.ascii("Synthetic probe"))).andExpect(status().isNotFound());
        mvc.perform(upload("report.pdf", UploadSamples.windowsExecutable())).andExpect(status().isNotFound());
        mvc.perform(multipart("/cases/CASE-2026-999999/attachments")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", UploadSamples.ascii("x")))
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(multipart("/cases/not-a-number/attachments").with(csrf())).andExpect(status().isNotFound());
        assertAttachments(0);
        verifyNoInteractions(scanner);
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void uploadsNeedCsrfTokens() throws Exception {
        mvc.perform(multipart("/cases/" + number + "/attachments")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", UploadSamples.ascii("x"))))
                .andExpect(status().isForbidden());
        assertAttachments(0);
        verifyNoInteractions(scanner);
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void refusedFilesAreUnprocessableWithTheirReasonAndNothingIsStored() throws Exception {
        mvc.perform(upload("report.pdf", UploadSamples.windowsExecutable()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("The file was not uploaded.")))
                .andExpect(content().string(containsString("this kind of file is not allowed")))
                .andExpect(content().string(containsString("href=\"#file\"")))
                .andExpect(content().string(containsString("aria-describedby=\"file-error file-hint\"")))
                .andExpect(content().string(containsString("Synthetic upload case")));
        mvc.perform(upload("big.txt", UploadSamples.ascii("a".repeat((int) CAP.toBytes() + 1))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("no larger than the maximum size")));
        mvc.perform(upload("report.docx", UploadSamples.pdf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("do not match its extension")));
        mvc.perform(multipart("/cases/" + number + "/attachments").with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("whose name is 1 to 255 characters")));
        assertAttachments(0);
        verifyNoInteractions(scanner);
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void anUnavailableScannerRefusesTheUploadWithTryAgainAndStoresNothing() throws Exception {
        doThrow(new ScannerUnavailableException("synthetic outage"))
                .when(scanner)
                .scan(any());

        mvc.perform(upload("notes.txt", UploadSamples.ascii("Synthetic notes")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("the virus scanner is unavailable, so nothing was stored")))
                .andExpect(content().string(containsString("Try again")))
                .andExpect(content().string(containsString("Synthetic upload case")));
        assertAttachments(0);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'ATTACHMENT_ADDED' AND CASE_ID = ?",
                        Long.class,
                        new CaseSeed(jdbc).caseId(number)))
                .isZero();
    }

    private RequestBuilder upload(String name, byte[] bytes) {
        return multipart("/cases/" + number + "/attachments")
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                .with(csrf());
    }

    private void assertAttachments(long count) {
        entityManager.flush();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM CASE_ATTACHMENT WHERE CASE_ID = ?",
                        Long.class,
                        new CaseSeed(jdbc).caseId(number)))
                .isEqualTo(count);
    }

    private String auditOf(long caseId) {
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT ACTOR_ID, ENTITY_TYPE, OLD_VALUES_JSON, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE CASE_ID = ? AND ACTION = 'ATTACHMENT_ADDED'",
                caseId);
        assertThat(event)
                .containsEntry("ACTOR_ID", ANALYST)
                .containsEntry("ENTITY_TYPE", "ATTACHMENT")
                .containsEntry("OLD_VALUES_JSON", null);
        return String.valueOf(event.get("NEW_VALUES_JSON"));
    }

    private void user(long id, String name) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "upload-subject-" + id,
                name);
    }

    /** The real scanner the mock delegates to, started on first use. */
    private ClamdClient realClamd() {
        AttachmentProperties properties = new AttachmentProperties(
                TEMP,
                CAP,
                25_000_000,
                ScanMode.CLAMD,
                new AttachmentProperties.Clamd(
                        "localhost",
                        RealClamd.tlsPort(),
                        "clamd",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(1)),
                8);
        return new ClamdClient(properties, TestTls.shared().bundles());
    }

    static Path tempDir() {
        try {
            Path dir = Files.createTempDirectory("tk-upload-it-");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
