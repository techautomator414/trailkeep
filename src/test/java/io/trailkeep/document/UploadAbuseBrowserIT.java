// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.FilePayload;
import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.e2e.pages.PolicyWatch;
import io.trailkeep.e2e.pages.SignInPage;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * Upload abuse through the browser (BUILD_PLAN §9 and §12.1, WP-28): the dev realm's {@code alpha.analyst} uploads
 * hostile files from the case page of an application wired as deployed, in {@code clamd} mode against a real ClamAV
 * behind TLS ({@link RealClamd}, shared with the other scanner ITs), with the cap lowered to 1 MB. A Windows
 * executable named {@code .pdf}, an HTML page, an SVG carrying a script, the same HTML named {@code .txt}, the same SVG
 * named {@code .png} and a file over the cap are each refused with the error summary and stored nowhere; EICAR is
 * stored quarantined, listed without a link and never served; a text file under a name built to climb directories
 * and break markup and headers keeps only its last path segment, is listed as plain text, and downloads as an
 * attachment, {@code nosniff}, as {@code text/plain}, with a {@code Content-Disposition} that keeps the name inside
 * its parameters. The browser reports no policy violation
 * throughout. In-process coverage of the same rules is {@code CaseAttachmentUploadIT} and {@code UploadIntakeTest}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class UploadAbuseBrowserIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";
    private static final String TITLE = "Synthetic upload abuse case";
    private static final int CAP = 1024 * 1024;

    /** The EICAR test file (not a virus; every scanner detects it), assembled so no scanner flags this source. */
    private static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    private static final String HTML = "<!DOCTYPE html><html><body><script>alert(1)</script></body></html>";
    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>";

    /** Quotes, angle brackets, percent signs and a right-to-left override, ending in {@code .txt}. */
    private static final String STORED_NAME = "\"><img src=x onerror=alert(1)>%0d%0a\u202Etxt.exe.txt";

    /** The same behind a path that climbs out of any directory; only the last segment is kept. */
    private static final String HOSTILE_NAME = "..\\..\\" + STORED_NAME;

    private static Playwright playwright;
    private static Browser browser;

    private Page page;
    private PolicyWatch watch;
    private long team;
    private long caseType;
    private long caseId;
    private CaseNumber number;

    @Autowired
    private CaseService cases;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        KEYCLOAK.register(registry);
        registry.add("app.attachments.max-bytes", () -> CAP + "B");
        registry.add("app.attachments.clamd.host", () -> "localhost");
        registry.add("app.attachments.clamd.port", RealClamd::tlsPort);
        for (String argument : TestTls.shared().trustStoreArguments()) {
            String property = argument.substring(2);
            int equals = property.indexOf('=');
            registry.add(property.substring(0, equals), () -> property.substring(equals + 1));
        }
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
    void signInAndOpenACase() {
        BrowserContext context = browser.newContext();
        watch = new PolicyWatch(context);
        page = context.newPage();
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn("alpha.analyst", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        long analyst = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_ANALYST));
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        seed.member(analyst, team, TeamRole.ANALYST);
        number = cases.create(
                new CaseService.Actor(
                        new UserPermissions(analyst, false, Set.of(), Map.of(team, TeamRole.ANALYST)),
                        "Synthetic Analyst"),
                new CaseFields(
                        caseType,
                        TITLE,
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(2),
                        team,
                        analyst,
                        "Synthetic subject",
                        "Synthetic description",
                        Sensitivity.STANDARD));
        caseId = seed.caseId(number);
        page.navigate(KEYCLOAK.app("/cases/" + number));
        page.waitForLoadState();
        assertThat(watch.drain(page)).isEmpty();
    }

    @AfterEach
    void removeCommittedRows() {
        page.context().close();
        // Test-only cleanup as the schema owner; the application user may not delete attachments or audit events.
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        jdbc.update(
                "DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN (SELECT ID FROM CASE_ATTACHMENT" + ofTeam
                        + ")",
                team);
        for (String table : new String[] {"CASE_ATTACHMENT", "AUDIT_EVENT", "CASE_SEARCH", "CASE_SLA"}) {
            jdbc.update("DELETE FROM " + table + ofTeam, team);
        }
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void everyDisguisedOrDangerousFileIsRefusedWithTheErrorSummaryAndStoredNowhere() {
        byte[] oversize = new byte[CAP + 1];
        Arrays.fill(oversize, (byte) 'a');
        Map<FilePayload, String> refused = Map.of(
                new FilePayload("report.pdf", "application/pdf", UploadSamples.windowsExecutable()),
                "this kind of file is not allowed",
                new FilePayload("page.html", "text/html", UploadSamples.ascii(HTML)),
                "this kind of file is not allowed",
                new FilePayload("drawing.svg", "image/svg+xml", UploadSamples.ascii(SVG)),
                "this kind of file is not allowed",
                new FilePayload("notes.txt", "text/plain", UploadSamples.ascii(HTML)),
                "this kind of file is not allowed",
                new FilePayload("photo.png", "image/png", UploadSamples.ascii(SVG)),
                "this kind of file is not allowed",
                new FilePayload("large.txt", "text/plain", oversize),
                "larger than");
        refused.forEach((file, reason) -> {
            int status = upload(file);
            assertThat(status).as(file.name).isGreaterThanOrEqualTo(400).isLessThan(500);
            page.waitForSelector("[data-tk-error-summary]");
            assertThat(page.textContent("[data-tk-error-summary]"))
                    .as(file.name)
                    .contains(reason);
            assertThat(watch.drain(page)).as(file.name).isEmpty();
            assertThat(attachments()).as(file.name).isZero();
            page.navigate(KEYCLOAK.app("/cases/" + number));
        });
    }

    @Test
    void eicarIsQuarantinedListedWithoutALinkAndNeverServed() {
        assertThat(upload(new FilePayload("eicar.txt", "text/plain", UploadSamples.ascii(EICAR))))
                .isLessThan(400);
        page.waitForLoadState();
        assertThat(attachments()).isOne();
        long id = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT ID FROM CASE_ATTACHMENT WHERE CASE_ID = ? AND SCAN_STATUS = 'QUARANTINED'",
                Long.class,
                caseId));
        String link = "/cases/" + number + "/attachments/" + id;
        assertThat(page.textContent(".tk-attachments")).contains("eicar.txt", "Quarantined");
        assertThat(page.querySelector("a[href='" + link + "']")).isNull();
        assertThat(watch.drain(page)).isEmpty();

        APIResponse download = page.request().get(KEYCLOAK.app(link));
        assertThat(download.status()).isGreaterThanOrEqualTo(400);
        assertThat(download.text()).doesNotContain("EICAR-STANDARD-ANTIVIRUS-TEST-FILE");
    }

    @Test
    void hostileFileNameIsListedAsTextAndDownloadsAsPlainAttachment() {
        assertThat(upload(new FilePayload(HOSTILE_NAME, "text/plain", UploadSamples.ascii("Synthetic notes"))))
                .isLessThan(400);
        page.waitForLoadState();
        assertThat(attachments()).isOne();
        // Browsers send a quote in a file name as %22 (HTML's form-data encoding); AttachmentDispositionTest has raw
        // ones.
        assertThat(jdbc.queryForObject("SELECT FILE_NAME FROM CASE_ATTACHMENT WHERE CASE_ID = ?", String.class, caseId))
                .isEqualTo(STORED_NAME.replace("\"", "%22"));
        assertThat(page.textContent(".tk-attachments"))
                .contains(STORED_NAME.replace("\"", "%22"))
                .doesNotContain("..");
        assertThat(page.querySelectorAll(".tk-attachments img")).isEmpty();
        assertThat(watch.drain(page)).isEmpty();

        String link = String.valueOf(page.getAttribute(".tk-attachments a[href*='/attachments/']", "href"));
        APIResponse response = page.request().get(KEYCLOAK.app(link));
        Map<String, String> headers = response.headers();
        response.dispose();
        assertThat(headers.get("content-type")).startsWith("text/plain");
        assertThat(headers.get("x-content-type-options")).isEqualTo("nosniff");
        // One attachment disposition: a quoted fallback with no quote, backslash or percent of its own, then filename*.
        assertThat(headers.get("content-disposition"))
                .matches("attachment; filename=\"[^\"\\\\%\\r\\n]+\"; filename\\*=UTF-8''[A-Za-z0-9!#$&+.^_`|~%-]+");
        // The browser saves it and stays on the case page: nothing is rendered from the file.
        String before = page.url();
        Download download = page.waitForDownload(() -> page.click(".tk-attachments a[href*='/attachments/']"));
        assertThat(download.failure()).isNull();
        assertThat(page.url()).isEqualTo(before);
    }

    /** Chooses the file on the case page's form and submits it; returns the status of the upload's response. */
    private int upload(FilePayload file) {
        page.setInputFiles("#file", file);
        return page.waitForResponse(
                        response -> response.request().method().equals("POST"),
                        () -> page.click("main button:has-text('Upload file')"))
                .status();
    }

    private long attachments() {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT COUNT(*) FROM CASE_ATTACHMENT WHERE CASE_ID = ?", Long.class, caseId));
    }
}
