// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import io.trailkeep.shared.persistence.DatabaseVendor;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The Oracle container for {@code -Poracle-it}, one per JVM and image and shared by every context. The image comes
 * from the system property {@value #IMAGE_PROPERTY} (set per Failsafe execution in the {@code oracle-it} profile),
 * defaulting to {@value #DEFAULT_IMAGE}. The value is the name of an image file under {@code images/} ({@code
 * oracle-xe}, {@code oracle-free}, see {@link TestImages}), so Dependabot keeps both pinned; a deploying organization
 * may still pass a full image reference (anything with a {@code /} or a {@code :}), which is used as given. Images
 * named {@code gvenzl/oracle-xe} use the Testcontainers {@code oracle-xe} module (PDB {@code XEPDB1}); anything else
 * uses the {@code oracle-free} module (PDB {@code FREEPDB1}). Also registers {@link TestDatabaseUsers}, so the
 * grantees exist before migration.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OracleTestcontainersConfiguration {

    /** System property naming the Oracle image. */
    public static final String IMAGE_PROPERTY = "trailkeep.oracle.image";

    /** Image used when the property is absent: the name of {@code images/oracle-free}. */
    public static final String DEFAULT_IMAGE = "oracle-free";

    private static final Duration STARTUP = Duration.ofMinutes(5);

    /** The started container per image; one image per Failsafe execution, so one entry per JVM. */
    private static final Map<String, JdbcDatabaseContainer<?>> SHARED = new HashMap<>();

    /**
     * The configured Oracle image: an {@code images/} name or a full image reference.
     *
     * @return the property's value, or {@value #DEFAULT_IMAGE}
     */
    public static String imageName() {
        return System.getProperty(IMAGE_PROPERTY, DEFAULT_IMAGE);
    }

    /**
     * The one Oracle container of this JVM for the configured image, started on first use and shared by every Spring
     * test context. The bean must not be the container's owner: contexts differ for many reasons (a spy bean, a
     * property, a profile), Spring's context cache keeps every one of them, and with it its container, until the JVM
     * ends, so a container per context made a run's peak nine Oracle containers (issue 223). Sharing is safe because
     * the tests already share one database between classes (each uses its own ids or a fresh schema, see {@code
     * AbstractMigrationJobIT}), the test-user script and Flyway are idempotent, and the container is stopped by
     * Testcontainers' Ryuk or, without it, by the shutdown hook below, never by a context closing (which would pull
     * it from under the others).
     */
    @Bean(destroyMethod = "")
    @ServiceConnection
    JdbcDatabaseContainer<?> oracle() {
        return shared(imageName());
    }

    private static JdbcDatabaseContainer<?> shared(String image) {
        synchronized (SHARED) {
            return SHARED.computeIfAbsent(image, OracleTestcontainersConfiguration::start);
        }
    }

    private static JdbcDatabaseContainer<?> start(String image) {
        boolean fileName = image.indexOf('/') < 0 && image.indexOf(':') < 0;
        DockerImageName name = fileName ? TestImages.image(image) : DockerImageName.parse(image);
        JdbcDatabaseContainer<?> container;
        if (name.getRepository().toLowerCase(Locale.ROOT).contains("oracle-xe")) {
            container = new org.testcontainers.containers.OracleContainer(name).withStartupTimeout(STARTUP);
        } else {
            container = new org.testcontainers.oracle.OracleContainer(name).withStartupTimeout(STARTUP);
        }
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "stop-" + name.getRepository()));
        return container;
    }

    @Bean
    TestDatabaseUsers testDatabaseUsers(
            JdbcConnectionDetails connection,
            @Value("${app.db.app-user}") String appUser,
            @Value("${app.db.purge-user}") String purgeUser,
            @Value("${app.db.import-user}") String importUser) {
        return new TestDatabaseUsers(DatabaseVendor.ORACLE, connection, appUser, purgeUser, importUser);
    }
}
