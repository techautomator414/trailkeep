// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import io.trailkeep.e2e.pages.SignInPage;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.user.TeamRole;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The DAST harness's first step (WP-28 part c), run only by the {@code dast} Maven profile against a running stack:
 * the built image in the {@code prod} profile with the compose PostgreSQL, Keycloak dev realm and ClamAV
 * ({@code .github/workflows/zap.yml}). It signs the dev-realm people in through Chromium, gives them teams and a
 * synthetic case configuration as the schema owner (the {@code prod} profile seeds nothing, and the {@code local}
 * profile is deliberately not active under the scan), creates a small synthetic data set through the application's
 * own forms as those people (cases in two teams, one restricted, a comment, an attachment, an update request and a
 * pending closure), and writes one {@code SESSION} cookie per scan identity to {@code target/dast/<person>.cookie}
 * for ZAP. Each exported session is checked to reach the dashboard without the browser. The cookie files are never
 * uploaded.
 *
 * <p>System properties: {@code dast.base-url} (default {@code http://localhost:8080}), {@code dast.jdbc-url},
 * {@code dast.jdbc-user} and {@code dast.jdbc-password} (default the compose PostgreSQL's dev-only owner).
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("dast")
class DastSessionIT {

    private static final String BASE = System.getProperty("dast.base-url", "http://localhost:8080");
    private static final String PASSWORD = "dev-password"; // the dev realm's, deliberately obvious
    private static final Path OUT = Path.of("target", "dast");
    private static final Pattern CSRF = Pattern.compile("<meta name=\"tk-csrf-token\" content=\"([^\"]+)\">");
    private static final Pattern CASE_NUMBER = Pattern.compile("/cases/([A-Z]+-[0-9]{4}-[0-9]+)");

    /** The scan identities (decision Q3) and the people the data set needs, with their dev-realm subjects. */
    private static final Map<String, String> PEOPLE = new LinkedHashMap<>();

    static {
        PEOPLE.put("compliance.admin", "00000000-0000-4000-8000-000000000101");
        PEOPLE.put("alpha.analyst", "00000000-0000-4000-8000-000000000002");
        PEOPLE.put("alpha.manager1", "00000000-0000-4000-8000-000000000003");
        PEOPLE.put("beta.analyst", "00000000-0000-4000-8000-000000000006");
    }

    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, Long> ids = new LinkedHashMap<>();

    @Test
    void seedsTheStackAndExportsOneSessionPerScanIdentity() throws IOException, InterruptedException {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                System.getProperty("dast.jdbc-url", "jdbc:postgresql://localhost:5432/trailkeep"),
                System.getProperty("dast.jdbc-user", "trailkeep"),
                System.getProperty("dast.jdbc-password", "trailkeep")));
        try (Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch()) {
            for (Map.Entry<String, String> person : PEOPLE.entrySet()) {
                pages.put(person.getKey(), signIn(browser.newContext(), person.getKey()));
                ids.put(person.getKey(), userId(jdbc, person.getValue()));
            }
            seed(jdbc);
            Files.createDirectories(OUT);
            for (String identity : new String[] {"compliance.admin", "alpha.analyst"}) {
                String session = page(identity).context().cookies(BASE).stream()
                        .filter(cookie -> "SESSION".equals(cookie.name))
                        .map(cookie -> cookie.value)
                        .findFirst()
                        .orElseThrow();
                String header = "SESSION=" + session;
                assertThat(dashboardWith(header))
                        .as(identity + "'s exported session")
                        .isEqualTo(200);
                Files.writeString(OUT.resolve(identity + ".cookie"), header, StandardCharsets.US_ASCII);
            }
        }
    }

    /**
     * Teams and a case type as the schema owner (what an administrator would have set up), then the data set through
     * the application's own forms as the people who would enter it.
     */
    private void seed(JdbcTemplate jdbc) {
        CaseSeed seed = new CaseSeed(jdbc);
        long alpha = seed.team();
        final long beta = seed.team();
        long type = seed.caseType();
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'Resolved', 1)", type);
        long owner = id("alpha.analyst");
        seed.member(owner, alpha, TeamRole.ANALYST);
        seed.member(id("alpha.manager1"), alpha, TeamRole.MANAGER);
        seed.member(id("beta.analyst"), beta, TeamRole.ANALYST);

        Page analyst = page("alpha.analyst");
        String open = createCase(analyst, type, alpha, owner, "STANDARD", "Synthetic DAST case");
        createCase(analyst, type, alpha, owner, "RESTRICTED", "Synthetic restricted DAST case");
        final String closing = createCase(analyst, type, alpha, owner, "STANDARD", "Synthetic DAST case to close");
        createCase(page("beta.analyst"), type, beta, id("beta.analyst"), "STANDARD", "Synthetic DAST case of team B");
        post(analyst, "/cases/" + open + "/comments", Map.of("body", "Synthetic **comment**"));
        upload(analyst, open);
        post(
                page("alpha.manager1"),
                "/cases/" + open + "/request-update",
                Map.of("recipientId", Long.toString(owner), "message", "Synthetic question"));
        post(
                analyst,
                "/cases/" + closing + "/request-closure",
                Map.of(
                        "outcome", "Resolved",
                        "summary", "Synthetic summary",
                        "approverId", Long.toString(id("alpha.manager1"))));
    }

    private Page page(String person) {
        return Objects.requireNonNull(pages.get(person));
    }

    private long id(String person) {
        return Objects.requireNonNull(ids.get(person));
    }

    private static Page signIn(BrowserContext context, String person) {
        Page page = context.newPage();
        page.navigate(BASE + "/");
        new SignInPage(page).signIn(person, PASSWORD, BASE);
        page.waitForLoadState();
        return page;
    }

    private static long userId(JdbcTemplate jdbc, String subject) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, subject));
    }

    /** Submits the new-case form's fields as the person; returns the new case's number. */
    private static String createCase(Page page, long type, long team, long owner, String sensitivity, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("caseTypeId", Long.toString(type));
        fields.put("title", title);
        fields.put("priority", "NORMAL");
        fields.put("identifiedOn", LocalDate.now(ZoneOffset.UTC).minusDays(2).toString());
        fields.put("teamId", Long.toString(team));
        fields.put("ownerId", Long.toString(owner));
        fields.put("subjectText", "Synthetic subject");
        fields.put("description", "Synthetic description");
        fields.put("sensitivity", sensitivity);
        fields.put("titleConfirmed", "true");
        String location = post(page, "/cases", fields);
        Matcher number = CASE_NUMBER.matcher(location);
        assertThat(number.find())
                .as("redirect after creating " + title + ": " + location)
                .isTrue();
        return number.group(1);
    }

    private static void upload(Page page, String number) {
        APIResponse response = page.request()
                .post(
                        BASE + "/cases/" + number + "/attachments",
                        RequestOptions.create()
                                .setMaxRedirects(0)
                                .setMultipart(FormData.create()
                                        .set("_csrf", token(page))
                                        .set(
                                                "file",
                                                new FilePayload(
                                                        "synthetic-notes.txt",
                                                        "text/plain",
                                                        "Synthetic notes".getBytes(StandardCharsets.US_ASCII)))));
        assertThat(response.status()).as("upload").isEqualTo(302);
        response.dispose();
    }

    /** Posts a form with the page's CSRF token; expects the redirect a successful form answers with. */
    private static String post(Page page, String path, Map<String, String> fields) {
        FormData form = FormData.create().set("_csrf", token(page));
        fields.forEach(form::set);
        APIResponse response = page.request()
                .post(BASE + path, RequestOptions.create().setMaxRedirects(0).setForm(form));
        String location = response.headers().getOrDefault("location", "");
        int status = response.status();
        response.dispose();
        assertThat(status).as("POST " + path).isEqualTo(302);
        return location;
    }

    private static String token(Page page) {
        APIResponse dashboard = page.request().get(BASE + "/");
        Matcher token = CSRF.matcher(dashboard.text());
        dashboard.dispose();
        assertThat(token.find()).as("the layout's CSRF token").isTrue();
        return token.group(1);
    }

    /** The dashboard's status for a request carrying only this cookie, as ZAP will send it. */
    private static int dashboardWith(String cookie) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(
                            HttpRequest.newBuilder(URI.create(BASE + "/"))
                                    .header("Cookie", cookie)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        }
    }
}
