// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.TeamRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Spring Security's defaults are on: login required, CSRF on every mutation, security headers on responses. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SecurityDefaultsIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void anonymousRequestIsSentToTheIdentityProvider() throws Exception {
        mvc.perform(get("/cases")).andExpect(status().isFound()).andExpect(redirectedUrl("/oauth2/authorization/idp"));
    }

    @Test
    void theLoginStartRedirectsToTheProviderAuthorizationEndpoint() throws Exception {
        mvc.perform(get("/oauth2/authorization/idp"))
                .andExpect(status().isFound())
                .andExpect(
                        header().string(
                                        "Location",
                                        org.hamcrest.Matchers.startsWith(
                                                "http://127.0.0.1:9/realms/test/protocol/openid-connect/auth?response_type=code")));
    }

    @Test
    @WithAppUser(team = 1, teamRole = TeamRole.ANALYST)
    void signedInRequestPassesAndCarriesTheSecurityHeaders() throws Exception {
        mvc.perform(get("/no-such-page"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    @WithAppUser(team = 1, teamRole = TeamRole.ANALYST)
    void mutationWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/no-such-page")).andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(team = 1, teamRole = TeamRole.ANALYST)
    void mutationWithCsrfTokenReachesTheApplication() throws Exception {
        mvc.perform(post("/no-such-page").with(csrf())).andExpect(status().isNotFound());
    }
}
