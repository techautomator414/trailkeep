// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Records every Trailkeep controller method a request reaches, so a sweep can tell a refusal by Spring Security's
 * filters (no handler ran) from a 403 a controller or service answered after the token was accepted. Error dispatches
 * and framework handlers (Spring Boot's error controller, static resources) are not recorded. Test-only, registered
 * with {@code @Import}.
 */
@TestConfiguration(proxyBeanMethods = false)
class HandlerCalls implements WebMvcConfigurer, HandlerInterceptor {

    private final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() == DispatcherType.REQUEST
                && handler instanceof HandlerMethod method
                && method.getBeanType().getName().startsWith("io.trailkeep.")) {
            calls.add(request.getMethod() + " " + request.getRequestURI() + " -> "
                    + method.getBeanType().getSimpleName() + "."
                    + method.getMethod().getName());
        }
        return true;
    }

    /** Forgets the calls recorded so far. */
    void clear() {
        calls.clear();
    }

    /** The calls recorded since the last {@link #clear()}. */
    List<String> calls() {
        return List.copyOf(calls);
    }
}
