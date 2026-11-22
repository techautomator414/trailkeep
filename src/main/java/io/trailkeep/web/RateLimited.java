// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Locale;

/**
 * Marks a handler method whose requests draw on the signed-in person's token bucket for {@link #value()} (#247).
 * {@link RateLimitInterceptor} reads it; a handler without it is never limited.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface RateLimited {

    /**
     * The bucket the request draws on.
     *
     * @return the route
     */
    Route value();

    /** The limited routes, each with its own bucket per person and its own {@code app.rate-limit.*} settings. */
    enum Route {
        /** The cases list and its CSV export, which run the case search, and the audit search (#274). */
        SEARCH,
        /** Attaching a file to a case: the file is checked, scanned and stored. */
        UPLOAD;

        /**
         * The value of the {@code route} tag on the refusal counter, and the name in a log line.
         *
         * @return the lower-case name
         */
        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
