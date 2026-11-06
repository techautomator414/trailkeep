// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders a mail from its three templates under {@code templates/notification/} (BUILD_PLAN §7.4): {@code
 * <id>.subject.txt} and {@code <id>.txt} in Thymeleaf's text mode, {@code <id>.html} in HTML mode, all with the same
 * model. A template id is a stable lower-case name such as {@code comment-added}; anything else is refused before it
 * can name a path. The engine is the application's own kind (Spring EL) but separate from the page engine, so mail
 * templates never resolve page fragments and pages never resolve mail templates.
 */
@Component
class MailTemplates {

    /** A template id: a lower-case letter, then letters, digits or hyphens; at most 60 characters. */
    static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,59}");

    private static final int MAX_SUBJECT = 200;

    private final SpringTemplateEngine text = engine(TemplateMode.TEXT, ".txt");
    private final SpringTemplateEngine html = engine(TemplateMode.HTML, ".html");

    /**
     * Renders one mail.
     *
     * @param templateId the template's id
     * @param model the values the templates read
     * @return the subject, plain text and HTML
     * @throws IllegalArgumentException for an id that is not a template name, or a subject over 200 characters
     */
    RenderedMail render(String templateId, Map<String, ?> model) {
        if (!ID.matcher(templateId).matches()) {
            throw new IllegalArgumentException("not a mail template id");
        }
        Context context = new Context();
        context.setVariables(new HashMap<String, Object>(model));
        String subject = text.process(templateId + ".subject", context).strip();
        if (subject.isEmpty()
                || subject.length() > MAX_SUBJECT
                || subject.indexOf('\n') >= 0
                || subject.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("a mail subject must be one line of 1 to 200 characters");
        }
        // The text body is stripped of the blank lines its header comment leaves behind.
        return new RenderedMail(subject, text.process(templateId, context).strip(), html.process(templateId, context));
    }

    private static SpringTemplateEngine engine(TemplateMode mode, String suffix) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/notification/");
        resolver.setSuffix(suffix);
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCheckExistence(true);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
