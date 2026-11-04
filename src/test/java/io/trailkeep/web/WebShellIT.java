// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The web shell's server-side contract: CSP and nonce, fixed htmx config, landmarks, assets, htmx redirects. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import({PostgresTestcontainersConfiguration.class, ProblemProbeController.class})
@SpringBootTest
@AutoConfigureMockMvc
class WebShellIT {

    /** BUILD_PLAN §9 "Browser security", verbatim apart from the nonce. */
    private static final Pattern CSP =
            Pattern.compile("default-src 'self'; script-src 'self' 'nonce-([A-Za-z0-9+/=]{24})'; "
                    + "style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'self'; "
                    + "frame-ancestors 'none'");

    private static final String HTMX_CONFIG = "{\"allowEval\":false,\"allowScriptTags\":false,"
            + "\"selfRequestsOnly\":true,\"includeIndicatorStyles\":false,\"historyCacheSize\":0,"
            + "\"refreshOnHistoryMiss\":true}";

    @Autowired
    private MockMvc mvc;

    @Test
    @WithAppUser(name = "Sample Analyst")
    void theDashboardCarriesTheExactCspAndEveryScriptCarriesItsNonce() throws Exception {
        MvcResult result = mvc.perform(get("/")).andExpect(status().isOk()).andReturn();

        Matcher csp = CSP.matcher(String.valueOf(result.getResponse().getHeader("Content-Security-Policy")));
        assertThat(csp.matches()).as("exact CSP").isTrue();
        String body = result.getResponse().getContentAsString();
        Matcher scripts = Pattern.compile("<script[^>]*>").matcher(body);
        int count = 0;
        while (scripts.find()) {
            assertThat(scripts.group()).contains("nonce=\"" + csp.group(1) + "\"");
            count++;
        }
        assertThat(count).isEqualTo(2);
        assertThat(body).doesNotContain("<style").doesNotContain(" style=").doesNotContainPattern("\\son[a-z]+=");
    }

    @Test
    @WithAppUser
    void eachResponseGetsAFreshNonce() throws Exception {
        String first = mvc.perform(get("/")).andReturn().getResponse().getHeader("Content-Security-Policy");
        String second = mvc.perform(get("/")).andReturn().getResponse().getHeader("Content-Security-Policy");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @WithAppUser
    void theHtmxConfigurationIsFixedByTheMetaTag() throws Exception {
        String body = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        Matcher meta = Pattern.compile("<meta name=\"htmx-config\"\\s+content='([^']*)'>")
                .matcher(body);

        assertThat(meta.find()).as("htmx-config meta tag").isTrue();
        assertThat(meta.group(1)).isEqualTo(HTMX_CONFIG);
    }

    @Test
    @WithAppUser(name = "Sample Analyst")
    void theShellHasTheLandmarksSkipLinkLiveRegionAndCsrfMeta() throws Exception {
        String body = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("<html lang=\"en\">");
        assertThat(body.indexOf("class=\"tk-skip-link\" href=\"#main\"")).isLessThan(body.indexOf("<header"));
        assertThat(body).contains("<header", "<nav aria-label=\"Main\">", "<main id=\"main\"", "<footer");
        assertThat(body).containsOnlyOnce("<h1>Dashboard</h1>");
        assertThat(body).contains("Signed in as Sample Analyst.");
        assertThat(body).contains("id=\"tk-live\"", "aria-live=\"polite\"", "<dialog id=\"tk-session-dialog\"");
        assertThat(body).contains("<meta name=\"tk-csrf-header\" content=\"X-CSRF-TOKEN\">", "name=\"tk-csrf-token\"");
        assertThat(body).doesNotContain("bootstrap.bundle", "popper");
    }

    @Test
    @WithAppUser
    void assetUrlsAreContentHashedAndServedForAYear() throws Exception {
        String body = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        Matcher css = Pattern.compile("href=\"(/css/app-[0-9a-f]{32}\\.css)\"").matcher(body);
        assertThat(css.find()).as("hashed app.css URL").isTrue();
        assertThat(body).containsPattern("src=\"/js/app-[0-9a-f]{32}\\.js\"");
        assertThat(body).containsPattern("src=\"/vendor/htmx/htmx\\.min-[0-9a-f]{32}\\.js\"");
        mvc.perform(get(css.group(1)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=31536000"));
    }

    @Test
    void anAnonymousHtmxRequestGetsHxRedirectNotALoginPage() throws Exception {
        mvc.perform(get("/").header("HX-Request", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("HX-Redirect", "/"));
        mvc.perform(get("/")).andExpect(status().isFound());
    }

    @Test
    @WithAppUser
    void anHtmxMutationWithAStaleCsrfTokenReloadsTheCurrentPage() throws Exception {
        mvc.perform(post("/session/extend")
                        .header("HX-Request", "true")
                        .header("HX-Current-URL", "http://localhost/cases?page=2"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("HX-Redirect", "/cases"));
        mvc.perform(post("/session/extend")
                        .header("HX-Request", "true")
                        .header("HX-Current-URL", "https://elsewhere.example//evil"))
                .andExpect(header().string("HX-Redirect", "/"));
    }

    @Test
    @WithAppUser
    void sessionExtendNeedsCsrfAndAnswersNoContent() throws Exception {
        mvc.perform(post("/session/extend").with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/session/extend")).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({
        "NOT_FOUND,404,Page not found",
        "FORBIDDEN,403,Not allowed",
        "CONFLICT,409,This changed while you",
        "INVALID,422,Please correct the highlighted fields"
    })
    @WithAppUser
    void domainExceptionsBecomeTheirStatusAndAFragmentForHtmx(String kind, int code, String title) throws Exception {
        String fragment = mvc.perform(get("/test-only/problem/" + kind).header("HX-Request", "true"))
                .andExpect(status().is(code))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(fragment).startsWith("<section").contains(title).doesNotContain("<html", "secret-detail");
    }

    @Test
    @WithAppUser
    void constraintViolationsReachingTheWebLayerAreUnprocessable() throws Exception {
        String fragment = mvc.perform(get("/test-only/constraint-violation").header("HX-Request", "true"))
                .andExpect(status().isUnprocessableContent())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(fragment)
                .startsWith("<section")
                .contains("Please correct the highlighted fields")
                .doesNotContain("secret-detail");

        mvc.perform(get("/test-only/constraint-violation")).andExpect(status().isUnprocessableContent());
    }

    @Test
    @WithAppUser
    void anUnreadableCaseIsTheFullNotFoundPage() throws Exception {
        String page = mvc.perform(get("/test-only/problem/NOT_FOUND"))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(page)
                .contains("<h1>Page not found</h1>", "<main id=\"main\"")
                .doesNotContain("CASE-2026");
    }
}
