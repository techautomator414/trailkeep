// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.html;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.dialect.AbstractProcessorDialect;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.IProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.standard.processor.AbstractStandardExpressionAttributeTagProcessor;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * The {@code tk} dialect: one attribute, {@code tk:safe="${…}"}, which replaces the element's body with a {@link
 * SafeHtml} value unescaped. Anything else, a {@code String} included, fails the render, so the attribute cannot be
 * used as a {@code th:utext} that Semgrep does not see. A {@code null} value gives an empty body, as {@code th:text}
 * does. Spring Boot adds every dialect bean to the template engine.
 */
@Component
final class SafeHtmlDialect extends AbstractProcessorDialect {

    /** The order of {@code th:text}, so the two attributes behave alike on an element. */
    private static final int PRECEDENCE = 1300;

    SafeHtmlDialect() {
        super("Trailkeep", "tk", PRECEDENCE);
    }

    @Override
    public Set<IProcessor> getProcessors(String dialectPrefix) {
        return Set.of(new SafeAttribute(dialectPrefix));
    }

    /** {@code tk:safe}. Restricted expression mode, like {@code th:utext}: no request parameters, no static calls. */
    static final class SafeAttribute extends AbstractStandardExpressionAttributeTagProcessor {

        SafeAttribute(String dialectPrefix) {
            super(TemplateMode.HTML, dialectPrefix, "safe", PRECEDENCE, true, true);
        }

        @Override
        protected void doProcess(
                ITemplateContext context,
                IProcessableElementTag tag,
                AttributeName attributeName,
                String attributeValue,
                @Nullable Object expressionResult,
                IElementTagStructureHandler structureHandler) {
            if (expressionResult == null) {
                structureHandler.setBody("", false);
                return;
            }
            if (!(expressionResult instanceof SafeHtml safe)) {
                throw new TemplateProcessingException("tk:safe accepts only SafeHtml, not "
                        + expressionResult.getClass().getName());
            }
            structureHandler.setBody(safe.html(), false);
        }
    }
}
