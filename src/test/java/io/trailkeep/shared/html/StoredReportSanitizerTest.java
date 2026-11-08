// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.html;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The stored-report policy (WP-23 part b): the aging report's own markup comes through unchanged, while a hand-edited
 * run row loses its script, its event handlers, its styles and every link that does not lead to a case; and no
 * payload of the XSS corpus comes out with an element, attribute or link target outside the policy.
 */
class StoredReportSanitizerTest {

    private static final Set<String> ELEMENTS =
            Set.of("table", "caption", "thead", "tbody", "tfoot", "tr", "th", "td", "a", "p", "div", "span");

    private static final Set<String> ATTRIBUTES = Set.of("scope", "colspan", "class", "href");

    /** A tag as the sanitizer writes them: lower-case names, every attribute double-quoted. */
    private static final Pattern TAG = Pattern.compile("<(/?)([a-z0-9]+)((?: [a-z]+=\"[^\"<>]*\")*) ?(/?)>");

    private static final Pattern ATTRIBUTE = Pattern.compile(" ([a-z]+)=\"([^\"]*)\"");

    /** The empty comment the sanitizer puts between two opening braces; inert. */
    private static final String TEMPLATE_BREAK = "<!-- -->";

    private final StoredReportSanitizer sanitizer = new StoredReportSanitizer();

    @Test
    void theReportsOwnTablesCaptionsHeadersAndCaseLinksAreKeptAsTheyAre() {
        String report = "<div class=\"table-responsive\"><table class=\"table table-sm tk-report tk-report-list\">"
                + "<caption>Overdue cases, oldest due first</caption><thead><tr><th scope=\"col\">Case</th>"
                + "<th scope=\"col\">Title</th></tr></thead><tbody><tr><th scope=\"row\">"
                + "<a href=\"/cases/RPT-2026-984105\">RPT-2026-984105</a></th><td>Synthetic report case</td></tr>"
                + "<tr><td colspan=\"5\">None.</td></tr></tbody><tfoot><tr><th scope=\"row\" colspan=\"2\">Total</th>"
                + "<td>3</td></tr></tfoot></table></div><p>Only the first 100 are listed.</p>";

        assertThat(html(report)).isEqualTo(report);
    }

    @Test
    void handEditedRunRowsLoseTheirScriptsHandlersStylesAndForeignLinks() {
        String edited = "<table><tbody><tr>"
                + "<td onerror=\"alert(1)\" style=\"color:red\">cell<script>alert(2)</script></td>"
                + "<td><img src=\"x\" onerror=\"alert(3)\">image</td>"
                + "<td><a href=\"javascript:alert(4)\">script link</a></td>"
                + "<td><a href=\"https://elsewhere.example.invalid/cases/RPT-1\">external link</a></td>"
                + "<td><a href=\"//elsewhere.example.invalid/cases/RPT-1\">protocol-relative link</a></td>"
                + "<td><a href=\"/cases/../admin/teams\">climbing link</a></td>"
                + "<td><a href=\"/cases/RPT-1\" onclick=\"alert(5)\" target=\"_blank\">case link</a></td>"
                + "<td class=\"x&quot; onmouseover=&quot;alert(6)\">quoted class</td>"
                + "</tr></tbody></table>";

        assertThat(html(edited))
                .isEqualTo("<table><tbody><tr><td>cell</td><td>image</td><td>script link</td><td>external link</td>"
                        + "<td>protocol-relative link</td><td>climbing link</td>"
                        + "<td><a href=\"/cases/RPT-1\">case link</a></td><td>quoted class</td></tr></tbody></table>")
                .doesNotContain("alert", "script>", "onerror", "style", "javascript", "elsewhere", "target");
    }

    @Test
    void elementsOutsideTheReportsShapeKeepOnlyTheirText() {
        assertThat(html("<h1>heading</h1><form action=\"/x\"><input name=\"q\"><button>go</button></form>"
                        + "<iframe src=\"/\"></iframe><svg><a href=\"/cases/RPT-1\">svg</a></svg><span>kept</span>"))
                .isEqualTo("headinggo<a href=\"/cases/RPT-1\">svg</a>kept");
    }

    static Stream<String> corpus() {
        return MarkdownRendererTest.payloads().stream();
    }

    @ParameterizedTest
    @MethodSource("corpus")
    void noCorpusPayloadComesOutWithAnythingOutsideThePolicy(String payload) {
        String html = html(payload).replace(TEMPLATE_BREAK, "");
        Matcher tag = TAG.matcher(html);
        StringBuilder text = new StringBuilder();
        int from = 0;
        while (tag.find()) {
            text.append(html, from, tag.start());
            from = tag.end();
            assertThat(ELEMENTS).as("element in %s", html).contains(tag.group(2));
            Matcher attribute = ATTRIBUTE.matcher(tag.group(3));
            while (attribute.find()) {
                assertThat(ATTRIBUTES).as("attribute in %s", html).contains(attribute.group(1));
                if ("href".equals(attribute.group(1))) {
                    assertThat(attribute.group(2)).as("href in %s", html).matches("/cases/[A-Za-z0-9-]{1,64}");
                }
            }
        }
        text.append(html, from, html.length());
        assertThat(text.toString()).as("text outside tags: %s", html).doesNotContain("<", ">");
    }

    private String html(String stored) {
        return sanitizer.sanitize(stored).html();
    }
}
