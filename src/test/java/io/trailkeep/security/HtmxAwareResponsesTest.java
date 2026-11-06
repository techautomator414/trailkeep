// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

/** The htmx 403 answer reloads the page the user is on, whatever servlet context path the application runs under. */
class HtmxAwareResponsesTest {

    @Test
    void redirectUnderContextPathKeepsPathOfCurrentPageOnce() throws Exception {
        MockHttpServletResponse response = denied("/app", "https://host/app/cases/1");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("HX-Redirect")).isEqualTo("/app/cases/1");
    }

    @Test
    void redirectInRootContextIsPathOfCurrentPage() throws Exception {
        MockHttpServletResponse response = denied("", "http://localhost/cases?page=2");

        assertThat(response.getHeader("HX-Redirect")).isEqualTo("/cases");
    }

    @Test
    void contextPathItselfIsKept() throws Exception {
        assertThat(denied("/app", "https://host/app").getHeader("HX-Redirect")).isEqualTo("/app");
    }

    @Test
    void currentUrlOutsideContextPathFallsBackToContextRoot() throws Exception {
        assertThat(denied("/app", "https://host/other/cases/1").getHeader("HX-Redirect"))
                .isEqualTo("/app/");
        assertThat(denied("/app", "https://host/application/cases").getHeader("HX-Redirect"))
                .isEqualTo("/app/");
    }

    @Test
    void missingOrHostileCurrentUrlFallsBackToContextRoot() throws Exception {
        assertThat(denied("/app", null).getHeader("HX-Redirect")).isEqualTo("/app/");
        assertThat(denied("/app", "https://elsewhere.example//evil").getHeader("HX-Redirect"))
                .isEqualTo("/app/");
        assertThat(denied("", "https://elsewhere.example//evil").getHeader("HX-Redirect"))
                .isEqualTo("/");
    }

    private static MockHttpServletResponse denied(String contextPath, @Nullable String currentUrl)
            throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", contextPath + "/cases/1/comments");
        request.setContextPath(contextPath);
        request.addHeader("HX-Request", "true");
        if (currentUrl != null) {
            request.addHeader("HX-Current-URL", currentUrl);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        HtmxAwareResponses.accessDenied().handle(request, response, new AccessDeniedException("csrf"));
        return response;
    }
}
