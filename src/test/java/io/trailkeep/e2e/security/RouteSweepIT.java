// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseService;
import io.trailkeep.e2e.security.Routes.Route;
import io.trailkeep.e2e.security.SweepCases.Target;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestBrowser;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
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
 * The security E2E sweeps of BUILD_PLAN §12.1 over every route the application maps (WP-28), against the real server
 * after a real sign-in at the dev realm, so Spring Security's whole filter chain and the Spring Session cookie are in
 * the path. The routes come from Spring MVC's handler mapping, so a new controller is swept without editing this test,
 * and a pattern variable the sweep cannot fill fails it until {@link SweepCases.Target#value} learns it.
 *
 * <ul>
 *   <li><b>CSRF:</b> every mutating route refuses a request with no token, or with the token of another session of the
 *       same person, with 403, before any controller runs ({@link HandlerCalls} records none), so a 403 a service
 *       would give an Analyst anyway cannot pass for the token check; nothing is written (the audit trail does not
 *       grow). Spring Security's {@code /logout} is swept with them.
 *   <li><b>404, not 403:</b> every route that names a case answers the case of another team and a restricted case the
 *       person may not read exactly as it answers a case number nobody has: 404, the same body once the number itself
 *       and the page's nonce and token are set aside, for plain and htmx requests. The person's own case gets
 *       something else on every GET, which proves the paths are the routes' real ones.
 * </ul>
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import({PostgresTestcontainersConfiguration.class, HandlerCalls.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class RouteSweepIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";

    /** The mutating routes mapped when this test was written; fewer means the enumeration broke. */
    private static final int MUTATING_FLOOR = 31;

    /** The routes naming a case when this test was written, counted per method. */
    private static final int CASE_ROUTE_FLOOR = 27;

    private static final Pattern CSRF_TOKEN = Pattern.compile("<meta name=\"tk-csrf-token\" content=\"([^\"]+)\">");
    private static final Map<String, String> HTMX = Map.of("HX-Request", "true");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @Autowired
    private CaseService cases;

    @Autowired
    private HandlerCalls handlerCalls;

    @Autowired
    private JdbcTemplate jdbc;

    private @Nullable SweepCases fixture;

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        KEYCLOAK.register(registry);
    }

    @AfterEach
    void removeCommittedRows() {
        if (fixture != null) {
            fixture.remove();
        }
    }

    @Test
    void everyMutatingRouteRefusesARequestWithoutItsOwnSessionsCsrfToken() throws Exception {
        TestBrowser browser = signedIn();
        SweepCases data = fixture();
        final String ownToken = csrfToken(browser);
        String otherSessionsToken = csrfToken(signedIn());
        List<Route> mutating =
                Routes.of(mapping).stream().filter(Route::mutating).toList();
        assertThat(mutating).hasSizeGreaterThanOrEqualTo(MUTATING_FLOOR);
        final long auditBefore = data.auditRows();

        handlerCalls.clear();
        List<String> failures = new ArrayList<>();
        for (Route route : mutating) {
            // The token check runs before any handler, so a route that names no case takes any id.
            for (String path :
                    route.expand(variable -> route.caseRooted() ? data.mine.value(route.pattern(), variable) : "1")) {
                refused(browser, route, path, Map.of(), Map.of(), failures);
                refused(browser, route, path, Map.of("_csrf", otherSessionsToken), Map.of(), failures);
                refused(browser, route, path, Map.of(), HTMX, failures);
            }
        }
        refused(
                browser,
                new Route(RequestMethod.POST, "/logout", "Spring Security"),
                "/logout",
                Map.of(),
                Map.of(),
                failures);

        assertThat(failures).isEmpty();
        // A controller may answer 403 too (an Analyst on an admin route); none ran, so every 403 was the token check.
        assertThat(handlerCalls.calls())
                .as("controller methods reached without a valid token")
                .isEmpty();
        assertThat(data.auditRows()).as("audit rows after the sweep").isEqualTo(auditBefore);
        // Controls: the session survived the refusals, and its own token is accepted.
        assertThat(browser.get(KEYCLOAK.app("/")).statusCode()).isEqualTo(200);
        assertThat(browser.send("POST", KEYCLOAK.app("/session/extend"), Map.of("_csrf", ownToken), Map.of())
                        .statusCode())
                .isEqualTo(204);
        // And the recorder sees a controller a valid token reaches, so its empty list above means something.
        assertThat(handlerCalls.calls()).anyMatch(call -> call.endsWith("SessionController.extend"));
    }

    @Test
    void everyCaseRouteAnswersAnUnreadableCaseExactlyAsAnAbsentOne() throws Exception {
        TestBrowser browser = signedIn();
        SweepCases data = fixture();
        String token = csrfToken(browser);
        List<Route> caseRoutes =
                Routes.of(mapping).stream().filter(Route::caseRooted).toList();
        assertThat(caseRoutes).hasSizeGreaterThanOrEqualTo(CASE_ROUTE_FLOOR);
        long auditBefore = data.auditRows();

        List<String> failures = new ArrayList<>();
        for (Route route : caseRoutes) {
            Map<String, String> form = route.mutating() ? Map.of("_csrf", token) : Map.of();
            sweep(browser, data, route, form, Map.of(), failures);
            sweep(browser, data, route, form, HTMX, failures);
            if (!route.mutating()) {
                ownCaseIsFound(browser, data, route, failures);
            }
        }

        assertThat(failures).isEmpty();
        assertThat(data.auditRows()).as("audit rows after the sweep").isEqualTo(auditBefore);
    }

    private void sweep(
            TestBrowser browser,
            SweepCases data,
            Route route,
            Map<String, String> form,
            Map<String, String> headers,
            List<String> failures)
            throws IOException, InterruptedException {
        Answer expected = answer(browser, data.absent, route, form, headers);
        String kind = headers.isEmpty() ? "" : " (htmx)";
        if (expected.status() != 404) {
            failures.add(route + kind + ": an absent case answers " + expected.status() + ", not 404");
        }
        for (Target target : data.unreadable()) {
            Answer actual = answer(browser, target, route, form, headers);
            if (!actual.equals(expected)) {
                failures.add(route + kind + ": the " + target.label() + " answers " + actual.status()
                        + " where an absent case answers " + expected.status()
                        + (actual.status() == expected.status() ? ", with a different body" : ""));
            }
        }
    }

    private void ownCaseIsFound(TestBrowser browser, SweepCases data, Route route, List<String> failures)
            throws IOException, InterruptedException {
        int status = answer(browser, data.mine, route, Map.of(), Map.of()).status();
        if (status == 404) {
            failures.add(route + ": the person's own case answers 404 too, so the path is not the route's");
        }
    }

    /**
     * Sends the route for one case (each alternative of a constrained variable in turn) and keeps what a client could
     * compare: the status, the content type, the redirect target and the body, with everything that names the case
     * or changes per response replaced by placeholders.
     */
    private static Answer answer(
            TestBrowser browser, Target target, Route route, Map<String, String> form, Map<String, String> headers)
            throws IOException, InterruptedException {
        List<Answer> answers = new ArrayList<>();
        for (String path : route.expand(variable -> target.value(route.pattern(), variable))) {
            HttpResponse<String> response = browser.send(route.method().name(), KEYCLOAK.app(path), form, headers);
            answers.add(new Answer(
                    response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(""),
                    normalise(response.headers().firstValue("Location").orElse(""), target),
                    normalise(response.body(), target)));
        }
        Answer first = answers.getFirst();
        return answers.stream().allMatch(first::equals) ? first : new Answer(-1, "", "", "alternatives differ");
    }

    private static String normalise(String text, Target target) {
        return text.replace(target.number(), "{number}")
                .replaceAll("\\b" + target.attachment() + "\\b", "{attachment}")
                .replaceAll("\\b" + target.updateRequest() + "\\b", "{updateRequest}")
                .replaceAll("\\b" + target.closure() + "\\b", "{closure}")
                .replaceAll("nonce=\"[^\"]*\"", "nonce=\"\"")
                .replaceAll("(name=\"tk-csrf-token\" content=)\"[^\"]*\"", "$1\"\"")
                .replaceAll("(name=\"_csrf\" value=)\"[^\"]*\"", "$1\"\"");
    }

    private static void refused(
            TestBrowser browser,
            Route route,
            String path,
            Map<String, String> form,
            Map<String, String> headers,
            List<String> failures)
            throws IOException, InterruptedException {
        int status = browser.send(route.method().name(), KEYCLOAK.app(path), form, headers)
                .statusCode();
        if (status != 403) {
            String how = form.isEmpty() ? "no token" : "another session's token";
            failures.add(route + " " + path + " with " + how + (headers.isEmpty() ? "" : " (htmx)") + ": " + status);
        }
    }

    private TestBrowser signedIn() throws IOException, InterruptedException {
        TestBrowser browser = new TestBrowser();
        HttpResponse<String> landing = browser.login(KEYCLOAK.app("/"), "alpha.analyst");
        assertThat(landing.statusCode()).isEqualTo(200);
        return browser;
    }

    private SweepCases fixture() {
        long analyst = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_ANALYST));
        SweepCases data = new SweepCases(jdbc, cases, analyst);
        fixture = data;
        return data;
    }

    private static String csrfToken(TestBrowser browser) throws IOException, InterruptedException {
        Matcher token = CSRF_TOKEN.matcher(browser.get(KEYCLOAK.app("/")).body());
        assertThat(token.find()).as("the layout's CSRF token").isTrue();
        return token.group(1);
    }

    /** What a client sees of a response, made comparable across cases. */
    private record Answer(int status, String contentType, String location, String body) {}
}
