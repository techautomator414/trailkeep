// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.html;

import java.util.regex.Pattern;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

/**
 * Sanitizes a stored report's HTML ({@code REPORT_RUN.HTML}, WP-23) into {@link SafeHtml} before a page shows it. The
 * markup is the application's own rendering of the aging report, but it is read back from a table, so it is treated
 * as untrusted: the OWASP allow-list sanitizer keeps only the table structure the report uses ({@code table caption
 * thead tbody tfoot tr th td}, plus {@code a p div span}), the attributes {@code scope}, {@code colspan} and {@code
 * class} with plain values, and {@code href} only for a path to a case ({@code /cases/} and a case number). Scripts,
 * styles, event handlers, images, forms, other URLs and every other element or attribute are dropped; the text of a
 * dropped element is kept, escaped.
 *
 * <p>Thread-safe; one instance serves every request.
 */
@Component
public final class StoredReportSanitizer {

    /** A link to one case: the only target a report links to. */
    private static final Pattern CASE_LINK = Pattern.compile("/cases/[A-Za-z0-9-]{1,64}");

    private static final Pattern SCOPE = Pattern.compile("row|col|rowgroup|colgroup");

    private static final Pattern SPAN = Pattern.compile("[1-9][0-9]?");

    private static final Pattern CLASSES = Pattern.compile("[A-Za-z0-9 _-]{1,200}");

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("table", "caption", "thead", "tbody", "tfoot", "tr", "th", "td", "a", "p", "div", "span")
            .allowAttributes("scope")
            .matching(SCOPE)
            .onElements("th", "td")
            .allowAttributes("colspan")
            .matching(SPAN)
            .onElements("th", "td")
            .allowAttributes("class")
            .matching(CLASSES)
            .globally()
            .allowAttributes("href")
            .matching(CASE_LINK)
            .onElements("a")
            .toFactory();

    /**
     * Sanitizes stored report markup.
     *
     * @param html the markup as the table holds it
     * @return what the policy allows of it
     */
    public SafeHtml sanitize(String html) {
        return new SafeHtml(POLICY.sanitize(html));
    }
}
