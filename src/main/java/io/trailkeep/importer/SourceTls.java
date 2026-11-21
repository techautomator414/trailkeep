// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import org.springframework.core.env.Environment;

/**
 * Whether a JDBC source must be reached over verified TLS (ADR-0014 rule 2, #259): always, except in a test profile
 * that says otherwise. Test databases in containers serve plain TCP, so the ITs that read them set {@link #PLAINTEXT}
 * alongside the container's URL. Outside the {@code test} and {@code oracle-test} profiles, and with {@code prod}
 * active in any combination, setting it fails startup: {@link SourceQueryJdbcDao} asks at construction, and every
 * context that could connect to a source, the {@code prod} importer Job included, constructs it.
 */
final class SourceTls {

    /** The test-only setting that lets a source URL without verified TLS through. */
    static final String PLAINTEXT = "import.source.plaintext-for-tests";

    private SourceTls() {}

    /**
     * Whether source URLs must require verified TLS.
     *
     * @param environment the context's environment
     * @return false only when {@link #PLAINTEXT} is true in a test profile
     * @throws IllegalStateException if {@link #PLAINTEXT} is true outside a test profile or with {@code prod}
     */
    static boolean required(Environment environment) {
        if (!environment.getProperty(PLAINTEXT, Boolean.class, false)) {
            return true;
        }
        if (environment.matchesProfiles("prod") || !environment.matchesProfiles("test | oracle-test")) {
            throw new IllegalStateException(PLAINTEXT + " is for the test profiles only; the importer refuses to start"
                    + " with it outside them, and always with the prod profile");
        }
        return false;
    }
}
