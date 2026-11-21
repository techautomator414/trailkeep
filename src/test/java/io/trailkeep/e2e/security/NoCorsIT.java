// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import io.trailkeep.e2e.security.Routes.Route;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.TeamRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * No CORS (security test map gap 7): Trailkeep is a same-origin application, so no response grants another origin
 * anything. A request carrying {@code Origin}, and a CORS preflight for every mapped route, get no
 * {@code Access-Control-*} header, whether anonymous (refused by Spring Security before Spring MVC) or signed in
 * (passed through to Spring MVC, where a CORS mapping would answer). The browser's same-origin policy then blocks every
 * cross-origin read.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NoCorsIT {

    private static final String ORIGIN = "https://elsewhere.example";

    /** Pages a signed-in user may load with a plain GET and no case behind them. */
    private static final List<String> PAGES = List.of("/", "/cases");

    @Autowired
    private MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @Test
    void anonymousCrossOriginRequestsAndPreflightsGetNoCorsHeaders() throws Exception {
        Sweep sweep = sweep();
        assertThat(sweep.granted()).isEmpty();
        assertThat(sweep.statuses())
                .as("anonymous requests are refused before Spring MVC")
                .containsOnly(302);
    }

    /**
     * Signed in, the requests pass Spring Security: the pages load, and each preflight is answered by Spring MVC's own
     * preflight handling (200), which is where a CORS mapping would add its headers.
     */
    @Test
    @WithAppUser(team = 1, teamRole = TeamRole.ANALYST)
    void signedInCrossOriginRequestsAndPreflightsGetNoCorsHeaders() throws Exception {
        Sweep sweep = sweep();
        assertThat(sweep.granted()).isEmpty();
        assertThat(sweep.statuses()).as("signed-in requests reach Spring MVC").containsOnly(200);
    }

    /**
     * What the sweep saw.
     *
     * @param granted every request that got an {@code Access-Control-*} header, with the headers it got
     * @param statuses the distinct statuses of all the requests
     */
    private record Sweep(List<String> granted, Set<Integer> statuses) {}

    private Sweep sweep() throws Exception {
        Sweep sweep = new Sweep(new ArrayList<>(), new TreeSet<>());
        for (String page : PAGES) {
            check("GET " + page, get(page), sweep);
        }
        List<Route> routes = Routes.of(mapping);
        assertThat(routes).as("routes found in the handler mapping").isNotEmpty();
        for (Route route : routes) {
            for (String path : route.expand(variable -> "1")) {
                check(
                        "preflight " + route.method() + " " + path,
                        options(path)
                                .header(
                                        "Access-Control-Request-Method",
                                        route.method().name())
                                .header("Access-Control-Request-Headers", "X-CSRF-TOKEN"),
                        sweep);
            }
        }
        return sweep;
    }

    private void check(String label, MockHttpServletRequestBuilder request, Sweep sweep) throws Exception {
        MockHttpServletResponse response =
                mvc.perform(request.header("Origin", ORIGIN)).andReturn().getResponse();
        List<String> cors = response.getHeaderNames().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith("access-control-"))
                .map(name -> name + ": " + response.getHeader(name))
                .toList();
        sweep.statuses().add(response.getStatus());
        if (!cors.isEmpty()) {
            sweep.granted().add(label + " (" + response.getStatus() + ") " + cors);
        }
    }
}
