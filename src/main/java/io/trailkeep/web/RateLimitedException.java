// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

/**
 * A person's bucket for a route was empty, raised by {@link RateLimitInterceptor} before the handler runs and answered
 * 429 with {@code Retry-After} by {@link ErrorAdvice}. The message names neither the person nor the request.
 */
final class RateLimitedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Whole seconds until the person's next request on the route is accepted; at least 1. */
    private final long retryAfterSeconds;

    RateLimitedException(long retryAfterSeconds) {
        super("rate limit reached");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
