// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import org.springframework.mock.env.MockEnvironment;

/**
 * The test-only escape from verified TLS ({@link SourceTls}, #259) for ITs that read a database container, which
 * serves plain TCP. Every use passes the container's own URL, in a test profile; {@code SourceUrlTest} shows the
 * escape is fatal anywhere else, and {@code PostgresSourceTlsIT} reads a container over verified TLS without it.
 */
final class PlainTcpSources {

    /** The Job argument, next to the {@code --import.source.url} of a container. */
    static final String ARG = "--" + SourceTls.PLAINTEXT + "=true";

    private PlainTcpSources() {}

    /**
     * An environment in the {@code test} profile that allows plain TCP.
     *
     * @return the environment, to which the test adds the source settings
     */
    static MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment().withProperty(SourceTls.PLAINTEXT, "true");
        environment.setActiveProfiles("test");
        return environment;
    }

    /**
     * A DAO that may connect to a container over plain TCP.
     *
     * @return the DAO
     */
    static SourceQueryJdbcDao dao() {
        return new SourceQueryJdbcDao(environment());
    }
}
