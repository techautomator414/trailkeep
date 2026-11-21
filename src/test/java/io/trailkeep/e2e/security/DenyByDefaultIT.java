// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import io.trailkeep.e2e.security.Routes.Route;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Deny by default (security test map gap 4): an anonymous request on the application port reaches nothing but an
 * explicit public allow-list. Everything else is refused before any handler runs: a full-page request with a 302 to
 * the start of the OIDC login, an htmx request with a 401 and {@code HX-Redirect}.
 *
 * <ul>
 *   <li>The routes come from Spring MVC's handler mapping ({@link Routes}), so a new controller is checked without
 *       editing this test, and a {@code permitAll} on any route fails it until {@link #PUBLIC} lists the route and
 *       says why it is public.
 *   <li>Every request carries a valid CSRF token, so an anonymous POST meets the authorization rule rather than
 *       CSRF's 403, which would otherwise hide a {@code permitAll} on a mutating route.
 *   <li>The public endpoints are all served by Spring Security's own filters, which no handler mapping lists, so each
 *       is probed by name and must still answer anonymously: the list cannot go stale. Other requests outside the
 *       handler mapping ({@link #OUTSIDE_THE_MAPPING}: Spring Security's {@code /login} and {@code GET /logout} pages,
 *       which a custom entry point keeps it from generating, the error page, static files, unmapped paths) must
 *       refuse like any route.
 * </ul>
 *
 * <p>Not covered here: the management server on its own port (8081). Its health, liveness, readiness, info and
 * Prometheus endpoints are anonymous by design: only the kubelet and the scraper reach them, from inside the cluster,
 * and the route never exposes that port. {@code ManagementEndpointsIT} pins that nothing else is served there.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DenyByDefaultIT {

    private static final String LOGIN_START = "/oauth2/authorization/idp";

    /** The Trailkeep routes mapped when this test was written; fewer means the enumeration broke. */
    private static final int ROUTE_FLOOR = 70;

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    /**
     * One public endpoint.
     *
     * @param method the HTTP method
     * @param pattern the paths it answers; Spring Security's filters answer any registration id, not only {@code idp}
     * @param probe a concrete path of the pattern that must still answer an anonymous request
     * @param reason why it must be reachable without signing in
     */
    private record Public(String method, String pattern, String probe, String reason) {

        boolean matches(String requestMethod, String path) {
            return method.equals(requestMethod) && PATHS.match(pattern, path);
        }
    }

    /** Everything an anonymous request may reach on the application port. None is a controller route. */
    private static final List<Public> PUBLIC = List.of(
            new Public(
                    "GET",
                    "/oauth2/authorization/{registrationId}",
                    LOGIN_START,
                    "starts the authorization-code flow: every refused full-page request is sent here to sign in"),
            new Public(
                    "GET",
                    "/login/oauth2/code/{registrationId}",
                    "/login/oauth2/code/idp",
                    "the provider's redirect back with the authorization code; there is no session to sign in with"
                            + " yet"),
            new Public(
                    "POST",
                    "/logout",
                    "/logout",
                    "signing out must work after the session has expired; it still needs the CSRF token"),
            new Public(
                    "POST",
                    "/logout/connect/back-channel/{registrationId}",
                    "/logout/connect/back-channel/idp",
                    "the provider's back-channel logout (ADR-0008): a server-to-server call carrying a signed logout"
                            + " token and no session"));

    /** Requests no handler mapping lists that are not public either; each must be refused. */
    private static final List<String> OUTSIDE_THE_MAPPING = List.of(
            "GET /login",
            "GET /logout",
            "GET /error",
            "GET /css/app.css",
            "GET /js/app.js",
            "GET /vendor/htmx/htmx.min.js",
            "GET /no-such-page",
            "POST /no-such-page");

    @Autowired
    private MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @Test
    void everyMappedRouteRefusesAnAnonymousRequestUnlessItIsOnThePublicList() throws Exception {
        List<Route> routes = Routes.of(mapping);
        assertThat(routes).as("routes found in the handler mapping").hasSizeGreaterThanOrEqualTo(ROUTE_FLOOR);
        List<String> wrong = new ArrayList<>();
        for (Route route : routes) {
            String method = route.method().name();
            for (String path : route.expand(variable -> "1")) {
                if (!isPublic(method, path)) {
                    refusal(method, path)
                            .ifPresent(problem ->
                                    wrong.add(method + " " + path + " (" + route.handler() + ") " + problem));
                }
            }
        }
        assertThat(wrong)
                .as("anonymous requests to mapped routes that were not refused")
                .isEmpty();
    }

    @Test
    void everyPublicEndpointStillAnswersAnAnonymousRequest() throws Exception {
        List<String> stale = new ArrayList<>();
        for (Public endpoint : PUBLIC) {
            MockHttpServletResponse response = anonymous(endpoint.method(), endpoint.probe(), false);
            if (isLoginRedirect(response)) {
                stale.add(endpoint.method() + " " + endpoint.probe() + " was sent to sign in; it is not public");
            }
        }
        assertThat(stale)
                .as("public-list entries that no longer answer anonymously")
                .isEmpty();
    }

    @Test
    void requestsOutsideTheHandlerMappingAreRefusedToo() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String probe : OUTSIDE_THE_MAPPING) {
            String[] parts = probe.split(" ", 2);
            assertThat(isPublic(parts[0], parts[1]))
                    .as(probe + " is on the public list")
                    .isFalse();
            refusal(parts[0], parts[1]).ifPresent(problem -> wrong.add(probe + " " + problem));
        }
        assertThat(wrong)
                .as("anonymous requests outside the handler mapping that were not refused")
                .isEmpty();
    }

    private static boolean isPublic(String method, String path) {
        return PUBLIC.stream().anyMatch(endpoint -> endpoint.matches(method, path));
    }

    /**
     * Empty if both a full-page and an htmx request are refused as they should be; otherwise what happened. A request
     * that gets past Spring Security usually reaches a handler that expects a signed-in user and throws, which is
     * reported the same way rather than ending the sweep.
     */
    private Optional<String> refusal(String method, String path) {
        try {
            MockHttpServletResponse page = anonymous(method, path, false);
            if (!isLoginRedirect(page)) {
                return Optional.of("answered a full-page request with " + describe(page));
            }
            MockHttpServletResponse htmx = anonymous(method, path, true);
            if (htmx.getStatus() != 401 || !"/".equals(htmx.getHeader("HX-Redirect"))) {
                return Optional.of("answered an htmx request with " + describe(htmx));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.of("reached a handler, which threw " + e);
        }
    }

    private MockHttpServletResponse anonymous(String method, String path, boolean htmx) throws Exception {
        MockHttpServletRequestBuilder builder =
                request(HttpMethod.valueOf(method), path).with(csrf());
        if (htmx) {
            builder.header("HX-Request", "true");
        }
        return mvc.perform(builder).andReturn().getResponse();
    }

    private static boolean isLoginRedirect(MockHttpServletResponse response) {
        String location = Objects.requireNonNullElse(response.getRedirectedUrl(), "");
        return response.getStatus() == 302
                && (location.equals(LOGIN_START) || location.endsWith("://localhost" + LOGIN_START));
    }

    private static String describe(MockHttpServletResponse response) {
        return response.getStatus() + " " + Objects.requireNonNullElse(response.getRedirectedUrl(), "");
    }
}
