// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.web.RateLimited.Route;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Per-person rate limiting on the routes marked {@link RateLimited} (#247, BUILD_PLAN §9 Availability): the cases
 * list and its export ({@link Route#SEARCH}) and attaching a file ({@link Route#UPLOAD}). A request with an empty
 * bucket is refused with {@link RateLimitedException}, which {@link ErrorAdvice} answers 429 with {@code Retry-After}.
 *
 * <p>Keyed on the signed-in person's {@code APP_USER.ID}, never the client address, which the router rewrites and
 * which many people can share. It runs first among the interceptors, after authentication and CSRF and before the
 * handler, so a refused search runs no query and a refused upload is never read, checked, scanned or stored (the
 * container has already received the multipart body, as it must to read the form's CSRF token). Every limited route
 * requires sign-in, so a request without a Trailkeep principal never gets here; it is let through rather than refused.
 *
 * <p>Buckets live in this process ({@link TokenBuckets}): with several replicas each holds its own, so a person's
 * effective limit is the per-replica limit times the replicas their requests reach. A multi-replica deployment also
 * sets a platform limit at the route or ingress. Each refusal is counted on {@code trailkeep.rate_limit.refusals},
 * tagged {@code route}, and logged at INFO with the person's id and the route, never the request's parameters.
 */
@Component
final class RateLimitInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

    private final boolean enabled;
    private final Limiter search;
    private final Limiter upload;

    RateLimitInterceptor(RateLimitProperties properties, MeterRegistry registry, Environment environment) {
        if (!properties.enabled() && environment.matchesProfiles("prod")) {
            throw new IllegalStateException(
                    "app.rate-limit.enabled is false; only tests may turn rate limiting off, and the prod profile"
                            + " refuses to start that way");
        }
        this.enabled = properties.enabled();
        this.search = new Limiter(Route.SEARCH, properties.search(), registry);
        this.upload = new Limiter(Route.UPLOAD, properties.upload(), registry);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (enabled) {
            registry.addInterceptor(this).addPathPatterns("/**").order(Ordered.HIGHEST_PRECEDENCE);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // An async or error dispatch repeats a request that was already counted.
        if (request.getDispatcherType() != DispatcherType.REQUEST || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimited limited = method.getMethodAnnotation(RateLimited.class);
        Authentication signedIn = SecurityContextHolder.getContext().getAuthentication();
        if (limited == null || signedIn == null || !(signedIn.getPrincipal() instanceof TrailkeepOidcUser person)) {
            return true;
        }
        Route route = limited.value();
        Limiter limiter = switch (route) {
            case SEARCH -> search;
            case UPLOAD -> upload;
        };
        long wait = limiter.buckets().take(person.userId());
        if (wait == 0) {
            return true;
        }
        limiter.refusals().increment();
        long seconds = Math.max(1, (wait + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
        LOG.info("Rate limit reached: {} refused for user {}, retry after {}s", route.tag(), person.userId(), seconds);
        throw new RateLimitedException(seconds);
    }

    /** One route's buckets and its refusal counter. */
    private record Limiter(TokenBuckets buckets, Counter refusals) {

        Limiter(Route route, RateLimitProperties.Limit limit, MeterRegistry registry) {
            this(
                    new TokenBuckets(limit, System::nanoTime),
                    Counter.builder("trailkeep.rate_limit.refusals")
                            .description("Requests refused 429 because the person's rate limit was reached")
                            .tag("route", route.tag())
                            .register(registry));
        }
    }
}
