// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The Oracle container for {@code -Poracle-it}. The image comes from the system property {@value #IMAGE_PROPERTY}
 * (set per Failsafe execution in the {@code oracle-it} profile), defaulting to {@value #DEFAULT_IMAGE}. Images named
 * {@code gvenzl/oracle-xe} use the Testcontainers {@code oracle-xe} module (PDB {@code XEPDB1}); anything else uses
 * the {@code oracle-free} module (PDB {@code FREEPDB1}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class OracleTestcontainersConfiguration {

    /** System property naming the Oracle image. */
    public static final String IMAGE_PROPERTY = "trailkeep.oracle.image";

    /** Image used when the property is absent. */
    public static final String DEFAULT_IMAGE = "gvenzl/oracle-free:23-slim";

    private static final Duration STARTUP = Duration.ofMinutes(5);

    /**
     * The configured Oracle image name.
     *
     * @return the image name
     */
    public static String imageName() {
        return System.getProperty(IMAGE_PROPERTY, DEFAULT_IMAGE);
    }

    @Bean
    @ServiceConnection
    JdbcDatabaseContainer<?> oracle() {
        String image = imageName();
        DockerImageName name = DockerImageName.parse(image);
        if (image.toLowerCase(Locale.ROOT).contains("oracle-xe")) {
            return new org.testcontainers.containers.OracleContainer(name).withStartupTimeout(STARTUP);
        }
        return new org.testcontainers.oracle.OracleContainer(name).withStartupTimeout(STARTUP);
    }
}
