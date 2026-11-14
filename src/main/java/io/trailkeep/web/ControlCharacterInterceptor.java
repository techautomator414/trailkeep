// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The one place that refuses control characters in text a user sends (issue 212). PostgreSQL rejects U+0000 in a text
 * parameter ("invalid byte sequence for encoding UTF8: 0x00"), which was a 500, and databases differ in what they do
 * with such text. A person never types such a character into a form, so it is refused as a client error (400) before
 * any controller, service or SQL sees it, the same on every database.
 *
 * <p>Why here and not elsewhere. The interceptor runs for every Spring MVC handler, after Spring has resolved a
 * multipart request, so {@link HttpServletRequest#getParameterMap()} already holds all three sources of text a
 * controller can bind: the query string, form fields and the text parts of a multipart request (file parts are not
 * parameters; their bytes are the attachment pipeline's). A new controller or form is covered without being touched.
 * It sits after authentication and CSRF, so an anonymous request is answered by those first; the 400 is rendered by
 * {@link ErrorAdvice} like every other client error, as the htmx fragment or the error page. A per-field annotation
 * would miss the next form, a database-specific catch would differ per vendor, and a servlet filter in front of Spring
 * Security would have to read the parameters (and so parse an oversized upload) before the application's own handling
 * of it.
 *
 * <p>Refused: U+0000 to U+001F except tab, line feed and carriage return (the multi-line fields need those), and
 * U+007F. Everything else, including other control characters in the C1 range and any non-ASCII text, passes.
 */
@Component
class ControlCharacterInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
            if (hasControlCharacter(parameter.getKey())) {
                throw new ControlCharacterException();
            }
            for (String value : parameter.getValue()) {
                if (hasControlCharacter(value)) {
                    throw new ControlCharacterException();
                }
            }
        }
        return true;
    }

    /**
     * Whether the text holds a refused control character.
     *
     * @param text a parameter name or value
     * @return true for U+0000 to U+001F other than tab, line feed and carriage return, and for U+007F
     */
    static boolean hasControlCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0x7F || (c < 0x20 && c != '\t' && c != '\n' && c != '\r')) {
                return true;
            }
        }
        return false;
    }
}
