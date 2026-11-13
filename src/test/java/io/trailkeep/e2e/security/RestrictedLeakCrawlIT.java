// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.e2e.pages.SignInPage;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * BUILD_PLAN §12.1's "no restricted leak" row (WP-28), as a crawl: signed in at the dev realm in Chromium, a Reader of
 * the restricted case's own team ({@code alpha.reader}) and an Analyst of another team ({@code beta.analyst}) each
 * follow every same-origin link, htmx URL and form action reachable from the dashboard, plus searches and CSV exports
 * for the markers (less their last character, as the search page echoes what was typed), through the browser's own
 * session; the restricted case's marker ({@link LeakFixture}) appears in no page, fragment, download or CSV either of
 * them gets, whatever its status. Controls keep the crawl honest: each finds
 * the marker of the standard case of their own team, on its case page and in their CSV export; the export holds
 * exactly that one case; the other team's standard case never reaches the Analyst of team B; and the crawl visits at
 * least {@link #VISITED_FLOOR} URLs.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class RestrictedLeakCrawlIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_READER = "00000000-0000-4000-8000-000000000001";
    private static final String BETA_ANALYST = "00000000-0000-4000-8000-000000000006";

    /** Fewer URLs than this means the crawl stopped following links. */
    private static final int VISITED_FLOOR = 15;

    /** A bound, so paging and sorting links cannot run away; far above what the fixture's pages link to. */
    private static final int MAX_URLS = 400;

    private static final int MAX_VARIANTS_PER_PATH = 8;
    private static final Pattern LINK = Pattern.compile("\\s(?:href|hx-get|action)=\"([^\"#]*)");
    private static final List<String> NOT_FOLLOWED =
            List.of("/logout", "/login", "/oauth2/", "/css/", "/js/", "/vendor/", "/session/");

    private static Playwright playwright;
    private static Browser browser;

    @Autowired
    private CaseService cases;

    @Autowired
    private JdbcTemplate jdbc;

    private @Nullable LeakFixture fixture;

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

    @AfterEach
    void removeCommittedRows() {
        if (fixture != null) {
            fixture.remove();
        }
    }

    @Test
    void neitherAReaderOfTheTeamNorAnotherTeamReachesAnyTextOfARestrictedCase() {
        Page reader = signIn("alpha.reader");
        Page betaAnalyst = signIn("beta.analyst");
        LeakFixture data = new LeakFixture(jdbc, cases, userId(ALPHA_READER), userId(BETA_ANALYST));
        fixture = data;

        Crawl readerCrawl = crawl(reader, data, data.twinA);
        Crawl betaCrawl = crawl(betaAnalyst, data, data.twinB);

        for (Crawl crawl : List.of(readerCrawl, betaCrawl)) {
            assertThat(crawl.leaks)
                    .as("URLs whose response carries the restricted case's marker")
                    .isEmpty();
            assertThat(crawl.bodies.size()).as("URLs visited").isGreaterThanOrEqualTo(VISITED_FLOOR);
        }
        assertThat(readerCrawl.found(data.twinA, "text/html"))
                .as("own team's case on a page")
                .isNotEmpty();
        assertThat(readerCrawl.found(data.twinA, "text/csv"))
                .as("own team's case in a CSV")
                .isNotEmpty();
        // The same kind of search finds the person's own case, so the one for the restricted case was a real search.
        assertThat(readerCrawl.body("/cases?q=" + typed(data.twinA))).contains(data.twinA);
        assertThat(betaCrawl.body("/cases?q=" + typed(data.twinB))).contains(data.twinB);
        assertThat(betaCrawl.found(data.twinB, "text/html")).isNotEmpty();
        assertThat(betaCrawl.found(data.twinB, "text/csv")).isNotEmpty();
        assertThat(betaCrawl.found(data.twinA, "")).as("the other team's case").isEmpty();
        assertThat(readerCrawl.found(data.twinB, ""))
                .as("the other team's case")
                .isEmpty();
        // The Reader's export lists exactly the twin: a header line and one row, not the restricted case.
        assertThat(readerCrawl.body("/cases/export.csv").strip().lines())
                .hasSize(2)
                .anyMatch(line -> line.contains(data.twinA));
    }

    /**
     * A breadth-first crawl from the dashboard and a few typed URLs, through the page's request context (the signed-in
     * browser's cookies), recording every response body.
     */
    private static Crawl crawl(Page page, LeakFixture data, String ownMarker) {
        Crawl crawl = new Crawl(data.secret);
        // Searches use the marker less its last character: the page echoes the query, and a hit still shows it whole.
        Deque<String> queue = new ArrayDeque<>(List.of(
                "/",
                "/cases?q=" + typed(data.secret),
                "/cases/export.csv?q=" + typed(data.secret),
                "/cases?q=" + typed(ownMarker),
                "/cases/export.csv",
                "/cases/" + data.restricted,
                "/cases/" + data.restricted + "/history",
                "/alerts"));
        Set<String> seen = new LinkedHashSet<>(queue);
        Map<String, Integer> variants = new HashMap<>();
        while (!queue.isEmpty() && crawl.bodies.size() < MAX_URLS) {
            String path = queue.removeFirst();
            APIResponse response = page.request().get(KEYCLOAK.app(path));
            String type = response.headers().getOrDefault("content-type", "");
            String body = response.text();
            response.dispose();
            crawl.record(path, type, body);
            if (!type.startsWith("text/html")) {
                continue;
            }
            Matcher link = LINK.matcher(body);
            while (link.find()) {
                String next = local(link.group(1).replace("&amp;", "&"));
                if (next == null || !seen.add(next)) {
                    continue;
                }
                String bare = next.contains("?") ? next.substring(0, next.indexOf('?')) : next;
                if (variants.merge(bare, 1, Integer::sum) <= MAX_VARIANTS_PER_PATH) {
                    queue.addLast(next);
                }
            }
        }
        return crawl;
    }

    private static String typed(String marker) {
        return marker.substring(0, marker.length() - 1);
    }

    /** The application path of a link, or {@code null} for anything off-site, static or session-ending. */
    private static @Nullable String local(String link) {
        String origin = KEYCLOAK.app("");
        String path = link.startsWith(origin) ? link.substring(origin.length()) : link;
        if (!path.startsWith("/") || path.startsWith("//")) {
            return null;
        }
        return NOT_FOLLOWED.stream().anyMatch(path::startsWith) ? null : path;
    }

    private Page signIn(String person) {
        Page page = browser.newContext().newPage();
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn(person, KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        return page;
    }

    private long userId(String externalId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, externalId));
    }

    /** What one person's crawl received. */
    private static final class Crawl {

        private final String secret;
        private final Map<String, String> bodies = new LinkedHashMap<>();
        private final Map<String, String> types = new HashMap<>();
        private final List<String> leaks = new ArrayList<>();

        Crawl(String secret) {
            this.secret = secret;
        }

        void record(String path, String type, String body) {
            bodies.put(path, body);
            types.put(path, type);
            if (body.contains(secret)) {
                leaks.add(path + " (" + type + ")");
            }
        }

        /** The URLs whose response, of a content type starting with {@code type}, carries the marker. */
        List<String> found(String marker, String type) {
            return bodies.entrySet().stream()
                    .filter(entry -> entry.getValue().contains(marker))
                    .map(Map.Entry::getKey)
                    .filter(path -> types.getOrDefault(path, "").startsWith(type))
                    .toList();
        }

        String body(String path) {
            return Objects.requireNonNull(bodies.get(path), path + " was not visited");
        }
    }
}
