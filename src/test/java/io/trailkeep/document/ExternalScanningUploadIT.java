// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/**
 * Uploading with {@code app.attachments.scanning=external} (ADR-0013): a file is stored {@code NOT_SCANNED}, shown as
 * "Not scanned by Trailkeep (scanned upstream)" and never as Verified, and audited with {@code scanMode}
 * {@code EXTERNAL}; the scanner is never called, even though a scanner bean exists here; every other check still
 * runs, so a disguised executable is 422. Two identical uploads at once both succeed, as two rows. The rows are
 * committed and removed afterwards, and the upload temp directory is empty after every test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(properties = "app.attachments.scanning=external")
@AutoConfigureMockMvc
class ExternalScanningUploadIT {

    private static final Path TEMP = CaseAttachmentUploadIT.tempDir();

    /** Present only to prove external mode never calls a scanner, whatever the context holds. */
    @MockitoBean
    private VirusScanner scanner;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    @Autowired
    private CaseAttachmentService attachments;

    static final long ANALYST = 962_001L;
    static final long TEAM = 962_100L;

    private long caseType;
    private CaseNumber number;

    @DynamicPropertySource
    static void uploads(DynamicPropertyRegistry registry) {
        registry.add("app.attachments.temp-dir", TEMP::toString);
    }

    @AfterEach
    void removeCommittedRowsAndCheckTempFiles() throws IOException {
        // Test-only cleanup as the schema owner; the application user may not delete attachments or audit events.
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        jdbc.update(
                "DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN (SELECT ID FROM CASE_ATTACHMENT" + ofTeam
                        + ")",
                TEAM);
        jdbc.update("DELETE FROM CASE_ATTACHMENT" + ofTeam, TEAM);
        jdbc.update("DELETE FROM AUDIT_EVENT" + ofTeam, TEAM);
        jdbc.update("DELETE FROM CASE_SEARCH" + ofTeam, TEAM);
        jdbc.update("DELETE FROM CASE_SLA" + ofTeam, TEAM);
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", TEAM);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", TEAM);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", TEAM);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", ANALYST);
        try (Stream<Path> left = Files.list(TEMP)) {
            assertThat(left).as("upload temp files left behind").isEmpty();
        }
    }

    @Test
    @WithAppUser(id = ANALYST, name = "Synthetic External Analyst", team = TEAM, teamRole = TeamRole.ANALYST)
    void filesAreStoredNotScannedWithTheModeAuditedAndNoScannerCall() throws Exception {
        Cookie session = Objects.requireNonNull(mvc.perform(multipart("/cases/" + number + "/attachments")
                        .file(new MockMultipartFile(
                                "file", "notes.txt", "text/plain", UploadSamples.ascii("Synthetic notes")))
                        .with(csrf()))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getCookie("SESSION"));
        mvc.perform(get("/cases/" + number).cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Trailkeep did not scan it for viruses")))
                .andExpect(content().string(containsString(">Not scanned by Trailkeep (scanned upstream)<")))
                .andExpect(content().string(not(containsString(">Verified<"))));

        long caseId = new CaseSeed(jdbc).caseId(number);
        assertThat(jdbc.queryForMap("SELECT SCAN_STATUS, SCAN_DETAIL FROM CASE_ATTACHMENT WHERE CASE_ID = ?", caseId))
                .containsEntry("SCAN_STATUS", "NOT_SCANNED")
                .containsEntry("SCAN_DETAIL", null);
        assertThat(jdbc.queryForObject(
                        "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE CASE_ID = ? AND ACTION = 'ATTACHMENT_ADDED'",
                        String.class,
                        caseId))
                .contains("\"scanMode\":\"EXTERNAL\"", "\"scanStatus\":\"NOT_SCANNED\"", "\"hmac\"")
                .doesNotContain("notes.txt");
        verifyNoInteractions(scanner);
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void disguisedExecutablesAreStillRefused() throws Exception {
        mvc.perform(multipart("/cases/" + number + "/attachments")
                        .file(new MockMultipartFile(
                                "file", "report.pdf", "application/pdf", UploadSamples.windowsExecutable()))
                        .with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("this kind of file is not allowed")));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM CASE_ATTACHMENT WHERE CASE_ID = ?",
                        Long.class,
                        new CaseSeed(jdbc).caseId(number)))
                .isZero();
        verifyNoInteractions(scanner);
    }

    @Test
    void identicalConcurrentUploadsBothSucceed() throws Exception {
        Actor actor = actor();
        byte[] bytes = UploadSamples.ascii("Synthetic notes uploaded twice at once");
        Callable<ScanStatus> upload =
                () -> attachments.upload(actor, number.toString(), "twice.txt", new ByteArrayInputStream(bytes));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ScanStatus>> results = pool.invokeAll(List.of(upload, upload));
            for (Future<ScanStatus> result : results) {
                assertThat(result.get()).isEqualTo(ScanStatus.NOT_SCANNED);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(attachments.attachments(actor, number.toString()))
                .extracting(CaseAttachmentService.AttachmentView::fileName)
                .containsExactly("twice.txt", "twice.txt");
    }

    @BeforeEach
    void seedCase() {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, 'external-upload-subject', 'Synthetic External Analyst',"
                        + " 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                ANALYST);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic External Upload Team')", TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        caseType = seed.caseType();
        number = cases.create(
                actor(),
                new CaseFields(
                        caseType,
                        "Synthetic external upload case",
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        TEAM,
                        ANALYST,
                        "Synthetic subject",
                        "Synthetic description",
                        Sensitivity.STANDARD));
    }

    private Actor actor() {
        return new Actor(
                new UserPermissions(ANALYST, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)),
                "Synthetic External Analyst");
    }
}
