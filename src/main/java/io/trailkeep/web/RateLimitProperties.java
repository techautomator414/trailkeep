// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code app.rate-limit} (#247, BUILD_PLAN §9 Availability). The limits live in {@code application.yml};
 * each route's bucket holds {@code capacity} requests and gains {@code refill} more per {@code window}, evenly.
 *
 * @param enabled whether requests are limited at all. Tests may turn it off; the {@code prod} profile refuses to start
 *     with it off ({@link RateLimitInterceptor}).
 * @param search the cases list and its CSV export, and the audit search
 * @param upload attaching a file to a case
 */
@Validated
@ConfigurationProperties("app.rate-limit")
record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @Valid @NotNull Limit search,
        @Valid @NotNull Limit upload) {

    /**
     * One route's bucket.
     *
     * @param capacity the most requests a person can make at once after a quiet spell
     * @param refill how many requests come back per {@code window}
     * @param window the period {@code refill} is spread over
     */
    record Limit(
            @Positive @Max(100_000) int capacity,
            @Positive @Max(1_000_000) int refill,
            @NotNull Duration window) {

        /**
         * A window of at least a second and at most a day, so one request's share of it is a whole number of
         * nanoseconds and a full bucket cannot overflow.
         *
         * @return whether the window is in range
         */
        @AssertTrue(message = "app.rate-limit.*.window must be between 1s and 1d")
        public boolean isWindowInRange() {
            return window == null
                    || (window.compareTo(Duration.ofSeconds(1)) >= 0 && window.compareTo(Duration.ofDays(1)) <= 0);
        }
    }
}
