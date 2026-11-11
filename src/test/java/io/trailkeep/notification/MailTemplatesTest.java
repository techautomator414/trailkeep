// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * The wording of every mail, from {@code messages.properties} (WP-27 part b): for a fixed model each of the 11 mails
 * renders exactly the subject, text body, in-app alert and (as plain text) HTML body that its templates produced when
 * the wording was written in them, kept in {@code notification/mail-golden.txt}. A key missing from the bundle shows
 * as {@code ??key_en??}, which is not in that file, so this also fails on a missing or misspelt key.
 */
class MailTemplatesTest {

    private static final List<String> IDS = List.of(
            "case-reopened",
            "closure-decided",
            "closure-reminder",
            "closure-requested",
            "comment-added",
            "report-ready",
            "sla-at-risk",
            "sla-overdue",
            "sla-overdue-repeat",
            "update-reminder",
            "update-requested");

    private static final Map<String, Object> MODEL = Map.ofEntries(
            Map.entry("caseNumber", "CASE-2026-000042"),
            Map.entry("title", "Synthetic title"),
            Map.entry("link", "https://example.invalid/cases/x"),
            Map.entry("reopener", "Synthetic Reopener"),
            Map.entry("decider", "Synthetic Decider"),
            Map.entry("verbKey", "tk.mail.closure-decided.verb.APPROVED"),
            Map.entry("outcome", "Resolved"),
            Map.entry("requester", "Synthetic Requester"),
            Map.entry("commenter", "Synthetic Commenter"),
            Map.entry("team", "Synthetic Team"),
            Map.entry("asOf", "2026-11-09"),
            Map.entry("due", "2026-11-12"),
            Map.entry("days", "3"));

    @Test
    void everyMailRendersTheWordingItHadInItsTemplates() throws IOException {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        MailTemplates templates = new MailTemplates(messages);
        StringBuilder rendered = new StringBuilder();
        for (String id : IDS) {
            RenderedMail mail = templates.render(id, MODEL);
            rendered.append("#### ").append(id).append("\n--subject--\n").append(mail.subject());
            rendered.append("\n--text--\n")
                    .append(mail.text())
                    .append("\n--html--\n")
                    .append(plain(mail.html()));
            rendered.append("\n--alert--\n")
                    .append(templates.renderAlert(id, MODEL))
                    .append("\n");
        }
        assertThat(rendered.toString()).isEqualTo(golden());
    }

    @Test
    void everyClosureDecisionHasItsVerb() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        MailTemplates templates = new MailTemplates(messages);
        for (String decision : List.of("APPROVED", "REJECTED", "CHANGES_REQUESTED")) {
            Map<String, Object> model = new java.util.HashMap<>(MODEL);
            model.put("verbKey", "tk.mail.closure-decided.verb." + decision);
            assertThat(templates.render("closure-decided", model).subject()).doesNotContain("??");
        }
    }

    @Test
    void valuesAreEscapedInTheHtmlBodyAndKeptApartFromTheMessagePattern() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        Map<String, Object> model = new java.util.HashMap<>(MODEL);
        model.put("title", "<b onclick=\"x\">{0} & 'q'</b>");
        for (String id : IDS) {
            String html = new MailTemplates(messages).render(id, model).html();
            assertThat(html).doesNotContain("<b ", "</b>");
            if (html.contains("&lt;b")) {
                assertThat(html).contains("&lt;b onclick=&quot;x&quot;&gt;{0} &amp; &#39;q&#39;&lt;/b&gt;");
            }
        }
    }

    /** The HTML body as the text a reader sees: markup and comments removed, entities decoded, spaces collapsed. */
    private static String plain(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("<[^>]*>", " ")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .replaceAll(" ([,.:”])", "$1")
                .replace("“ ", "“")
                .strip();
    }

    private static String golden() throws IOException {
        try (InputStream in = MailTemplatesTest.class.getResourceAsStream("/notification/mail-golden.txt")) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
