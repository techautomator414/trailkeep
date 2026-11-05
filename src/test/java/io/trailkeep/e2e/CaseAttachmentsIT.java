// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.FilePayload;
import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.e2e.pages.ShellPage;
import io.trailkeep.e2e.pages.SignInPage;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Attachments in a real browser (WP-14 part 3, BUILD_PLAN §4.3, §10.1), with {@code app.attachments.scanning=external}
 * so the browser's uploads need no clamd (ADR-0013; EICAR against a real ClamAV is {@code CaseAttachmentUploadIT}).
 * The dev realm's {@code alpha.analyst} opens a case with a seeded quarantined file, chooses a disguised executable
 * by keyboard and gets the 422 page with the focused error summary, follows it back to the file input, and uploads a
 * text file, which the page then lists as "Not scanned by Trailkeep (scanned upstream)" after the one-shot notice.
 * Every state has 0 axe violations and no CSP violation in the console. The rows the test commits are removed
 * afterwards. Browser handling follows {@link ShellAccessibilityIT}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "app.attachments.scanning=external")
class CaseAttachmentsIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";
    private static final String TITLE = "Synthetic attachment case";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;
    private long team;
    private long caseType;

    @Autowired
    private CaseService cases;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        KEYCLOAK.register(registry);
    }

    @BeforeAll
    static void launchBrowser() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch();
        } catch (PlaywrightException e) {
            if (System.getenv("CI") != null) {
                throw e;
            }
            Assumptions.abort("No Playwright browser in this environment ("
                    + String.valueOf(e.getMessage()).lines().findFirst().orElse("")
                    + "); CI runs this test and fails without one");
        }
    }

    @AfterAll
    static void closeBrowser() {
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void openBrowser() {
        page = browser.newContext().newPage();
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")
                    || message.text().contains("Refused to")) {
                consoleProblems.add(message.text());
            }
        });
        page.onPageError(error -> consoleProblems.add(error));
    }

    @AfterEach
    void removeCommittedRows() {
        // Test-only cleanup as the schema owner; the application user may not delete attachments or audit events.
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        if (team != 0) {
            jdbc.update(
                    "DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN (SELECT ID FROM CASE_ATTACHMENT" + ofTeam
                            + ")",
                    team);
            jdbc.update("DELETE FROM CASE_ATTACHMENT" + ofTeam, team);
            jdbc.update("DELETE FROM AUDIT_EVENT" + ofTeam, team);
            jdbc.update("DELETE FROM CASE_SEARCH" + ofTeam, team);
            jdbc.update("DELETE FROM CASE_SLA" + ofTeam, team);
            jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
            jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
            jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
            Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
            jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
            jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
        }
    }

    @Test
    void anAnalystMeetsTheErrorSummaryThenUploadsByKeyboard() {
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn("alpha.analyst", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
        long analyst = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_ANALYST));
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        seed.member(analyst, team, TeamRole.ANALYST);
        CaseNumber number = cases.create(actor(analyst), fields(analyst));
        seed.attachment(seed.caseId(number), analyst, "seeded-evidence.txt", "QUARANTINED");

        page.navigate(KEYCLOAK.app("/cases/" + number));
        assertClean();
        assertThat(page.textContent(".tk-attachments")).contains("seeded-evidence.txt", "Quarantined");
        assertThat(page.textContent("#file-hint")).contains("Allowed types: CSV, DOCX", "Maximum size:");

        chooseByKeyboard(new FilePayload("report.pdf", "application/pdf", windowsExecutable()));
        page.keyboard().press("Tab");
        assertThat(page.evaluate("() => document.activeElement.textContent.trim()"))
                .isEqualTo("Upload file");
        assertThat(page.waitForResponse(
                                response -> response.request().method().equals("POST"),
                                () -> page.keyboard().press("Enter"))
                        .status())
                .isEqualTo(422);
        page.waitForSelector("[data-tk-error-summary]");
        assertThat(page.evaluate("() => document.activeElement.hasAttribute('data-tk-error-summary')"))
                .isEqualTo(true);
        assertThat(page.textContent("[data-tk-error-summary]")).contains("this kind of file is not allowed");
        assertThat(page.getAttribute("#file", "aria-invalid")).isEqualTo("true");
        assertClean();

        page.keyboard().press("Tab");
        page.keyboard().press("Enter");
        assertThat(page.evaluate("() => document.activeElement.id")).isEqualTo("file");
        chooseByKeyboard(new FilePayload(
                "synthetic-notes.txt", "text/plain", "Synthetic notes".getBytes(StandardCharsets.US_ASCII)));
        page.keyboard().press("Tab");
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/cases/" + number + "#attachments"));
        assertClean();
        assertThat(page.textContent("[data-tk-toast]")).contains("Trailkeep did not scan it for viruses");
        assertThat(page.textContent(".tk-attachments"))
                .contains("synthetic-notes.txt", "Not scanned by Trailkeep (scanned upstream)")
                .doesNotContain("Verified");

        page.reload();
        assertClean();
        assertThat(page.querySelector("[data-tk-toast]")).isNull();
    }

    /** With focus on the file input, opens the native chooser by keyboard and picks a file. */
    private void chooseByKeyboard(FilePayload file) {
        page.focus("#file");
        FileChooser chooser = page.waitForFileChooser(() -> page.keyboard().press("Space"));
        chooser.setFiles(file);
    }

    private void assertClean() {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).contains(TITLE);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }

    /** The start of a Windows PE (MZ header pointing at a {@code PE\0\0} signature), named as a PDF. */
    private static byte[] windowsExecutable() {
        ByteBuffer pe = ByteBuffer.allocate(512);
        pe.put("MZ".getBytes(StandardCharsets.US_ASCII));
        pe.putInt(0x3C, Integer.reverseBytes(0x80));
        pe.position(0x80);
        pe.put("PE".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) 0);
        pe.putShort((short) 0x4C01);
        return pe.array();
    }

    private CaseService.Actor actor(long userId) {
        return new CaseService.Actor(
                new UserPermissions(userId, false, Set.of(), Map.of(team, TeamRole.ANALYST)), "Synthetic Analyst");
    }

    private CaseFields fields(long owner) {
        return new CaseFields(
                caseType,
                TITLE,
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC).minusDays(2),
                team,
                owner,
                "Synthetic subject",
                "Synthetic description",
                Sensitivity.STANDARD);
    }
}
