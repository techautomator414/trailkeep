// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.closure.ClosureDecisionService;
import io.trailkeep.closure.ClosureService;
import io.trailkeep.e2e.pages.PolicyWatch;
import io.trailkeep.e2e.pages.ShellPage;
import io.trailkeep.e2e.pages.SignInPage;
import io.trailkeep.e2e.security.Routes.Route;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The browser half of BUILD_PLAN §12.1's CSP row (WP-28): every page the application maps, opened in Chromium after a
 * real sign-in at the dev realm as each of four people ({@code alpha.analyst}, {@code alpha.manager1},
 * {@code compliance.admin}, {@code system.admin}), carries the exact policy of BUILD_PLAN §9 with a fresh nonce on
 * every script, no inline style or event handler, and {@code nosniff} and {@code Referrer-Policy: same-origin}; and
 * the browser reports no policy violation, neither on load, nor with each of the page's dialogs open, nor after an
 * htmx swap. Downloads and CSV exports carry the policy and {@code nosniff} too. The pages come from Spring MVC's
 * handler mapping, filled from {@link PageFixture}, so a new page is crawled without editing this test; error pages
 * (403 for a person without the role, 404 for an unmapped path) are crawled as pages. Every page must answer 200 to
 * at least one of the four, so the crawl cannot pass by seeing only error pages.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class CspCrawlIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();

    /** BUILD_PLAN §9 "Browser security", verbatim apart from the nonce. */
    private static final Pattern CSP =
            Pattern.compile("default-src 'self'; script-src 'self' 'nonce-([A-Za-z0-9+/=]{24})'; "
                    + "style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'self'; "
                    + "frame-ancestors 'none'");

    private static final Pattern SCRIPT = Pattern.compile("<script\\b[^>]*>");
    private static final Pattern INLINE =
            Pattern.compile("<style\\b|\\sstyle=|\\son[a-z]+=|javascript:", Pattern.CASE_INSENSITIVE);

    /** The dev-realm people and their subjects. */
    private static final Map<String, String> PEOPLE = new LinkedHashMap<>();

    /** The GET routes mapped when this test was written; fewer means the enumeration broke. */
    private static final int PAGE_FLOOR = 40;

    static {
        PEOPLE.put("alpha.analyst", "00000000-0000-4000-8000-000000000002");
        PEOPLE.put("alpha.manager1", "00000000-0000-4000-8000-000000000003");
        PEOPLE.put("compliance.admin", "00000000-0000-4000-8000-000000000101");
        PEOPLE.put("system.admin", "00000000-0000-4000-8000-000000000102");
    }

    private static Playwright playwright;
    private static Browser browser;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @Autowired
    private CaseService cases;

    @Autowired
    private ClosureService closures;

    @Autowired
    private ClosureDecisionService decisions;

    @Autowired
    private JdbcTemplate jdbc;

    private @Nullable PageFixture fixture;

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
    void everyPageCarriesTheExactPolicyAndTheBrowserReportsNoViolation() {
        Map<String, Page> pages = new LinkedHashMap<>();
        Map<String, PolicyWatch> watches = new LinkedHashMap<>();
        for (String person : PEOPLE.keySet()) {
            BrowserContext context = browser.newContext();
            watches.put(person, new PolicyWatch(context));
            pages.put(person, signIn(context.newPage(), person));
        }
        PageFixture data =
                new PageFixture(jdbc, cases, closures, decisions, userId("alpha.analyst"), userId("alpha.manager1"));
        fixture = data;
        List<Route> routes = Routes.of(mapping).stream()
                .filter(route -> route.method() == RequestMethod.GET)
                .toList();
        assertThat(routes).hasSizeGreaterThanOrEqualTo(PAGE_FLOOR);

        Set<String> unseen = new TreeSet<>();
        routes.forEach(route -> unseen.add(route.pattern()));
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Page> person : pages.entrySet()) {
            Page page = person.getValue();
            PolicyWatch watch = Objects.requireNonNull(watches.get(person.getKey()));
            watch.drain(page);
            for (Route route : routes) {
                for (String path : route.expand(variable -> data.value(route.pattern(), variable))) {
                    if (visit(page, watch, path, person.getKey(), failures) == 200) {
                        unseen.remove(route.pattern());
                    }
                }
            }
            // Pages no route list names: the confirmation to lift a restriction, and the 404 page.
            visit(page, watch, "/cases/" + data.restricted + "/unrestrict", person.getKey(), failures);
            assertThat(visit(page, watch, "/no-such-page", person.getKey(), failures))
                    .isEqualTo(404);
        }

        SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(failures).isEmpty();
            soft.assertThat(unseen).as("pages that answered 200 to nobody").isEmpty();
        });
    }

    /**
     * Opens one path as one person and checks what came back. HTML is opened in the page, so the browser applies the
     * policy and reports what it blocks; anything else (a download, a CSV) is fetched through the same context.
     *
     * @return the status
     */
    private static int visit(Page page, PolicyWatch watch, String path, String person, List<String> failures) {
        String url = KEYCLOAK.app(path);
        String where = person + " " + path;
        APIResponse probe = page.request().get(url);
        String type = probe.headers().getOrDefault("content-type", "");
        int status = probe.status();
        if (!type.startsWith("text/html")) {
            headers(probe.headers(), where, failures);
            probe.dispose();
            return status;
        }
        probe.dispose();
        Response response = Objects.requireNonNull(page.navigate(url));
        page.waitForLoadState();
        Map<String, String> headers = response.headers();
        String nonce = headers(headers, where, failures);
        if (!"same-origin".equals(headers.get("referrer-policy"))) {
            failures.add(where + ": Referrer-Policy " + headers.get("referrer-policy"));
        }
        markup(response.text(), nonce, where, failures);
        report(watch.drain(page), where, failures);
        dialogs(page, watch, where, failures);
        htmxSwap(page, watch, where, failures);
        return response.status();
    }

    /** The policy and {@code nosniff}; returns the nonce, or an empty string when the policy is not exact. */
    private static String headers(Map<String, String> headers, String where, List<String> failures) {
        if (!"nosniff".equals(headers.get("x-content-type-options"))) {
            failures.add(where + ": X-Content-Type-Options " + headers.get("x-content-type-options"));
        }
        Matcher csp = CSP.matcher(headers.getOrDefault("content-security-policy", ""));
        if (!csp.matches()) {
            failures.add(where + ": Content-Security-Policy " + headers.get("content-security-policy"));
            return "";
        }
        return csp.group(1);
    }

    /** Every script carries this response's nonce, and nothing is inline that the policy would block. */
    private static void markup(String html, String nonce, String where, List<String> failures) {
        Matcher scripts = SCRIPT.matcher(html);
        while (scripts.find()) {
            if (!scripts.group().contains("nonce=\"" + nonce + "\"")) {
                failures.add(where + ": a script without this response's nonce: " + scripts.group());
            }
        }
        Matcher inline = INLINE.matcher(html);
        if (inline.find()) {
            int from = Math.max(0, inline.start() - 60);
            failures.add(where + ": inline markup the policy blocks: " + html.substring(from, inline.end() + 20));
        }
    }

    /** Opens each dialog the page offers, as its opener would, then closes it. */
    private static void dialogs(Page page, PolicyWatch watch, String where, List<String> failures) {
        List<String> ids = new ArrayList<>();
        Object found = page.evaluate("() => [...new Set([...document.querySelectorAll('[data-tk-dialog-open]')]"
                + ".map(e => e.getAttribute('data-tk-dialog-open')))]");
        if (found instanceof List<?> list) {
            list.forEach(id -> ids.add(String.valueOf(id)));
        }
        if (page.querySelector("#tk-session-dialog") != null) {
            new ShellPage(page).openSessionDialog();
            page.waitForSelector("#tk-session-dialog[open]");
            page.evaluate("() => document.getElementById('tk-session-dialog').close()");
            report(watch.drain(page), where + " (session dialog)", failures);
        }
        for (String id : ids) {
            page.evaluate("id => document.querySelector(`[data-tk-dialog-open=\"${id}\"]`).click()", id);
            page.waitForSelector("#" + id + "[open]");
            report(watch.drain(page), where + " (dialog " + id + ")", failures);
            page.evaluate("id => document.getElementById(id).close()", id);
        }
    }

    /** Follows the first htmx link in the main content, if there is one, and waits for the swap to settle. */
    private static void htmxSwap(Page page, PolicyWatch watch, String where, List<String> failures) {
        if (page.querySelector("main a[hx-get]") == null) {
            return;
        }
        page.waitForResponse(
                response -> "true".equals(response.request().headers().get("hx-request")),
                () -> page.evaluate("() => document.querySelector('main a[hx-get]').click()"));
        page.waitForFunction("() => !document.querySelector('.htmx-request, .htmx-swapping, .htmx-settling')");
        report(watch.drain(page), where + " (after an htmx swap)", failures);
    }

    private static void report(List<String> violations, String where, List<String> failures) {
        violations.forEach(violation -> failures.add(where + ": " + violation));
    }

    private static Page signIn(Page page, String person) {
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn(person, KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        return page;
    }

    private long userId(String person) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, PEOPLE.get(person)));
    }
}
