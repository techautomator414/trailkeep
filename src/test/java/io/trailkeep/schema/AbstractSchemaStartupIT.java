// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.TrailkeepApplication;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import io.trailkeep.testsupport.TestDatabaseUsers.FreshOwner;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Flyway owns the schema (issue #317): starting the application, as the schema owner, must create no table and no
 * index. The first version of this test caught Hibernate's {@code HTE_*} global temporary tables on Oracle, created at
 * start-up by its multi-table mutation strategy. The schema is read once straight after Flyway, before Hibernate
 * builds its session factory, and once after the context is up; the two must be equal. A fresh owner per run keeps
 * the container's own schema out of it.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractSchemaStartupIT {

    @Autowired
    private TestDatabaseUsers users;

    /**
     * The profile that points this vendor's test contexts at their container ({@code test} or {@code oracle-test}).
     *
     * @return the profile
     */
    abstract String vendorProfile();

    @Test
    void startingTheApplicationAddsNothingToTheSchemaFlywayBuilt() throws SQLException {
        String name = "TKSUP" + Long.toHexString(System.nanoTime() & 0xffffffL);
        FreshOwner owner = users.freshOwner(name);
        AtomicReference<Set<String>> afterFlyway = new AtomicReference<>();
        try {
            FlywayMigrationStrategy snapshot = flyway -> {
                flyway.migrate();
                try {
                    afterFlyway.set(snapshot(owner));
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            };
            try (ConfigurableApplicationContext app = new SpringApplicationBuilder(TrailkeepApplication.class)
                    .web(WebApplicationType.NONE)
                    .profiles(vendorProfile())
                    .initializers(context -> {
                        context.getBeanFactory().registerSingleton("testDatabaseUsers", users);
                        context.getBeanFactory().registerSingleton("schemaSnapshotStrategy", snapshot);
                    })
                    .run(
                            "--spring.datasource.url=" + users.jdbcUrl(),
                            "--spring.datasource.username=" + owner.username(),
                            "--spring.datasource.password=" + owner.password(),
                            "--app.db.schema=" + owner.schema())) {
                assertThat(app.isRunning()).isTrue();
            }
            assertThat(afterFlyway.get()).as("the schema Flyway built").anyMatch("TABLE CASE_RECORD"::equalsIgnoreCase);
            assertThat(snapshot(owner))
                    .as("the schema after start-up equals the schema after Flyway alone")
                    .isEqualTo(afterFlyway.get());
        } finally {
            users.dropFreshOwner(name);
        }
    }

    /**
     * Every table-like object and every index of the owner's schema, as sorted, comparable lines. The index lookup
     * is approximate: the exact one makes the Oracle driver gather optimizer statistics, which is itself a write.
     */
    private Set<String> snapshot(FreshOwner owner) throws SQLException {
        Set<String> lines = new TreeSet<>();
        try (Connection connection = DriverManager.getConnection(users.jdbcUrl(), owner.username(), owner.password())) {
            DatabaseMetaData meta = connection.getMetaData();
            String schema = meta.storesUpperCaseIdentifiers()
                    ? owner.schema().toUpperCase(java.util.Locale.ROOT)
                    : owner.schema().toLowerCase(java.util.Locale.ROOT);
            try (ResultSet tables = meta.getTables(null, schema, "%", null)) {
                while (tables.next()) {
                    String table = tables.getString("TABLE_NAME");
                    lines.add(tables.getString("TABLE_TYPE") + " " + table);
                }
            }
            for (String line : Set.copyOf(lines)) {
                if (line.startsWith("INDEX ") || line.startsWith("SEQUENCE ")) {
                    continue;
                }
                String table = line.substring(line.indexOf(' ') + 1);
                try (ResultSet indexes = meta.getIndexInfo(null, schema, table, false, true)) {
                    while (indexes.next()) {
                        String index = indexes.getString("INDEX_NAME");
                        if (index != null) {
                            lines.add("INDEX " + index + " ON " + table + " col " + indexes.getString("COLUMN_NAME")
                                    + " unique=" + !indexes.getBoolean("NON_UNIQUE"));
                        }
                    }
                }
            }
        }
        return lines;
    }
}
