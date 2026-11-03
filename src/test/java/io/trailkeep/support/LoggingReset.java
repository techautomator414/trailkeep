// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.support;

import org.springframework.boot.logging.LoggingSystem;

/**
 * Lets the next Spring context apply its own profile-specific logging configuration.
 *
 * <p>Spring Boot initializes Logback once and skips re-initialization while the logger context is marked as
 * initialized; cached test contexts are never closed, so a test that asserts on profile-specific log output would
 * otherwise see whatever configuration the first context in the JVM applied. Pair it with a property that gives the
 * test a context cache key of its own.
 */
public final class LoggingReset {

    private LoggingReset() {}

    /** Clears the "already initialized" marker; the next context start re-reads {@code logback-spring.xml}. */
    public static void beforeNewContext() {
        LoggingSystem.get(LoggingReset.class.getClassLoader()).cleanUp();
    }
}
