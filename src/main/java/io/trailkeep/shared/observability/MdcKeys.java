// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.observability;

/**
 * The only MDC keys the application may set. The JSON encoder in {@code logback-spring.xml} emits exactly these keys
 * and drops any other MDC entry, so a value put under an unlisted key never reaches the log stream.
 */
public final class MdcKeys {

    /** Correlation id of the current HTTP request; populated by {@link RequestIdFilter}. */
    public static final String REQUEST_ID = "requestId";

    /** Internal numeric id of the authenticated user (never an email address); populated by the security module. */
    public static final String USER_ID = "userId";

    /** Case number the current request or job is acting on; populated by the case modules. */
    public static final String CASE_NUMBER = "caseNumber";

    /** Spring Modulith module that emitted the line, for jobs and event listeners. */
    public static final String MODULE = "module";

    private MdcKeys() {}
}
