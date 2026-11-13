// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.TrailkeepApplication;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import io.trailkeep.testsupport.TestDatabaseUsers.FreshOwner;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The migration Job (issue #205, BUILD_PLAN §8 rule 7, once per vendor): on a fresh install, the runtime user cannot
 * migrate (it has no DDL right), the {@code migrate} profile run as the schema owner builds the whole schema and its
 * grants, and then the web application starts as the runtime user, which owns nothing and still holds no DDL right,
 * with Hibernate's {@code validate} passing against the migrated schema. The schema is a fresh one per run (a new user
 * on Oracle, a new schema on PostgreSQL), because the container's own schema is migrated by the test context.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractMigrationJobIT {

    @Autowired
    private TestDatabaseUsers users;

    @Value("${app.db.app-user}")
    private String appUser = "";

    /**
     * The profile that points this vendor's test contexts at their container ({@code test} or {@code oracle-test}).
     *
     * @return the profile
     */
    abstract String vendorProfile();

    @Test
    void theRuntimeUserCannotMigrateButTheOwnerJobCanAndThenTheRuntimeUserStarts() throws SQLException {
        String name = "TKMIG" + Long.toHexString(System.nanoTime() & 0xffffffL);
        FreshOwner owner = users.freshOwner(name);
        try {
            assertThatThrownBy(() -> runtime(owner, true).close())
                    .as("the runtime profile with Flyway on, as the app user, cannot create the schema")
                    .isInstanceOf(RuntimeException.class);

            migrationJob(owner).close();

            try (Connection connection =
                            DriverManager.getConnection(users.jdbcUrl(), owner.username(), owner.password());
                    Statement statement = connection.createStatement();
                    ResultSet tables =
                            statement.executeQuery("SELECT COUNT(*) FROM " + owner.schema() + ".CASE_RECORD")) {
                assertThat(tables.next())
                        .as("the migrated schema has the case table")
                        .isTrue();
            }
            try (ConfigurableApplicationContext app = runtime(owner, false)) {
                assertThat(app.isRunning())
                        .as("started as the runtime user, Hibernate validate passed")
                        .isTrue();
            }
            assertThatThrownBy(() -> createTableAsRuntimeUser(owner))
                    .as("the runtime user has no DDL right on the migrated schema")
                    .isInstanceOf(SQLException.class);
        } finally {
            users.dropFreshOwner(name);
        }
    }

    /** Runs the migration Job's profile as the schema owner and ends, as {@code prod,migrate} does. */
    private ConfigurableApplicationContext migrationJob(FreshOwner owner) {
        return new SpringApplicationBuilder(TrailkeepApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(vendorProfile(), "migrate")
                // The test grantee callback, as the test context has it: the grantees exist, LINT_PROBE is readable.
                .initializers(context -> context.getBeanFactory().registerSingleton("testDatabaseUsers", users))
                .run(
                        "--spring.datasource.url=" + users.jdbcUrl(),
                        "--spring.datasource.username=" + owner.username(),
                        "--spring.datasource.password=" + owner.password(),
                        "--app.db.schema=" + owner.schema());
    }

    /** Starts the application as the runtime user against the fresh schema, as the web Deployment would. */
    private ConfigurableApplicationContext runtime(FreshOwner owner, boolean flyway) throws SQLException {
        return new SpringApplicationBuilder(TrailkeepApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(vendorProfile())
                .run(
                        "--spring.datasource.url=" + users.jdbcUrl(),
                        "--spring.datasource.username=" + appUser,
                        "--spring.datasource.password=" + users.newPassword(appUser),
                        "--spring.flyway.enabled=" + flyway,
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--app.db.schema=" + owner.schema());
    }

    private void createTableAsRuntimeUser(FreshOwner owner) throws SQLException {
        try (Connection connection = users.login(appUser);
                Statement statement = connection.createStatement()) {
            // Test-only DDL, expected to be refused; the schema is a generated identifier.
            statement.execute("CREATE TABLE " + owner.schema() + ".TK_NO_DDL (ID INTEGER)");
        }
    }
}
