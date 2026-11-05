// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.html;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/** {@code tk:safe} writes {@link SafeHtml} unescaped and refuses every other value. */
class SafeHtmlDialectTest {

    private static final String TEMPLATE = "<div class=\"comment\" tk:safe=\"${body}\">placeholder</div>";

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void safeHtmlIsWrittenUnescapedAndTheAttributeRemoved() {
        String page = render(TEMPLATE, renderer.render("**bold** <script>alert(1)</script>"));

        assertThat(page)
                .isEqualTo("<div class=\"comment\"><p><strong>bold</strong> &lt;script&gt;alert(1)&lt;/script&gt;"
                        + "</p>\n</div>");
    }

    @Test
    void stringsAreRefusedEvenWhenTheyLookHarmless() {
        assertThatThrownBy(() -> render(TEMPLATE, "<p>plain</p>"))
                .isInstanceOf(TemplateProcessingException.class)
                .hasStackTraceContaining("tk:safe accepts only SafeHtml, not java.lang.String");
    }

    @Test
    void nullGivesAnEmptyBodyAsThTextDoes() {
        assertThat(render(TEMPLATE, null)).isEqualTo("<div class=\"comment\"></div>");
    }

    private static String render(String template, @Nullable Object body) {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.addDialect(new SafeHtmlDialect());
        Map<String, Object> variables = new HashMap<>();
        if (body != null) {
            variables.put("body", body);
        }
        return engine.process(template, new Context(Locale.ROOT, variables));
    }
}
