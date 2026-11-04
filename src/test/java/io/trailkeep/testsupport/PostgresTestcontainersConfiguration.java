// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import io.trailkeep.shared.persistence.DatabaseVendor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 16 for every build. Import into a {@code @SpringBootTest} together with {@code @ActiveProfiles("test")}
 * and {@link RequiresDocker}. Reuse is opt-in per machine ({@code testcontainers.reuse.enable=true} in
 * {@code ~/.testcontainers.properties}) and ignored in CI. Also registers {@link TestDatabaseUsers}, so the grantees
 * of {@code R__grants.sql} exist before migration.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

    /** The image every PostgreSQL integration test runs against. */
    public static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE).withReuse(true);
    }

    @Bean
    TestDatabaseUsers testDatabaseUsers(
            JdbcConnectionDetails connection,
            @Value("${app.db.app-user}") String appUser,
            @Value("${app.db.purge-user}") String purgeUser) {
        return new TestDatabaseUsers(DatabaseVendor.POSTGRESQL, connection, appUser, purgeUser);
    }
}
