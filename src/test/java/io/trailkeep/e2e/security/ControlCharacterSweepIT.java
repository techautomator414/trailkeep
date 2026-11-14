// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import io.trailkeep.e2e.security.Routes.Route;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The control-character sweep of issue 212, in the style of the WP-28a route sweeps: every route the application maps
 * (read from Spring MVC's handler mapping, so a new controller is swept without being told about it) answers a request
 * whose query string or form carries U+0000, in a value or in a name, with 400 before any handler or the database is
 * reached (the interceptor is the first thing after routing). The parameter's name does not matter to the boundary, so
 * a probe name stands for every mapped filter and field; the named filters of the case list are in
 * {@code ControlCharactersIT}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ControlCharacterSweepIT {

    /** The routes mapped when this test was written; fewer means the enumeration broke. */
    private static final int ROUTE_FLOOR = 60;

    private static final String NUL = "a" + (char) 0 + "b";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @Autowired
    private MockMvc mvc;

    @Test
    @WithAppUser(id = 1L, globalRole = GlobalRole.SYSTEM_ADMIN)
    void everyRouteRefusesAControlCharacterInAValueOrAName() throws Exception {
        List<Route> routes = Routes.of(mapping);
        assertThat(routes).hasSizeGreaterThanOrEqualTo(ROUTE_FLOOR);
        List<String> wrong = new ArrayList<>();
        for (Route route : routes) {
            for (String path : route.expand(variable -> "number".equals(variable) ? "CASE-2026-000001" : "1")) {
                for (boolean inName : new boolean[] {false, true}) {
                    MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(
                            HttpMethod.valueOf(route.method().name()), path);
                    if (route.mutating()) {
                        request.with(csrf());
                    }
                    request.param(inName ? NUL : "probe", inName ? "x" : NUL);
                    int status = mvc.perform(request).andReturn().getResponse().getStatus();
                    if (status != 400) {
                        wrong.add(route.method() + " " + path + (inName ? " (name)" : " (value)") + " -> " + status
                                + " " + route.handler());
                    }
                }
            }
        }
        assertThat(wrong).as("routes that did not answer 400").isEmpty();
    }
}
