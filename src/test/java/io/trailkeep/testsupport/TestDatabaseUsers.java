// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import io.trailkeep.shared.persistence.DatabaseVendor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.io.ClassPathResource;

/**
 * Test-only grantees for the vendor {@code R__grants.sql}. As a Flyway callback it runs
 * {@code db/test-users/{vendor}.sql} before every migrate, as the container's administrator, so the roles named by
 * {@code app.db.app-user} and {@code app.db.purge-user} exist when the grants run; the roles cannot log in. For
 * {@code DatabaseGrantsIT}, {@link #login(String)} gives one of them a fresh random password and connects as it, so
 * no credential is ever written down.
 *
 * <p>Administrator: on PostgreSQL the container user (a superuser, also the schema owner); on Oracle {@code SYSTEM},
 * whose password the Testcontainers Oracle modules set to the container user's password.
 */
public final class TestDatabaseUsers implements Callback {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatabaseVendor vendor;
    private final JdbcConnectionDetails connection;
    private final String appUser;
    private final String purgeUser;

    /**
     * Creates the callback.
     *
     * @param vendor the container's vendor
     * @param connection the container's connection details
     * @param appUser {@code app.db.app-user}
     * @param purgeUser {@code app.db.purge-user}
     */
    public TestDatabaseUsers(
            DatabaseVendor vendor, JdbcConnectionDetails connection, String appUser, String purgeUser) {
        this.vendor = vendor;
        this.connection = connection;
        this.appUser = appUser;
        this.purgeUser = purgeUser;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        try (Connection admin = adminConnection();
                Statement statement = admin.createStatement()) {
            statement.execute(script());
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create the test database users", e);
        }
    }

    @Override
    public String getCallbackName() {
        return "testDatabaseUsers";
    }

    /**
     * The schema that owns the application tables, as the grantees must qualify it.
     *
     * @return {@code public} on PostgreSQL, the container user on Oracle
     */
    public String ownerSchema() {
        return vendor == DatabaseVendor.ORACLE
                ? Objects.requireNonNull(connection.getUsername(), "container user")
                        .toUpperCase(Locale.ROOT)
                : "public";
    }

    /**
     * Sets a fresh random password for {@code user} and connects as it.
     *
     * @param user {@code app.db.app-user} or {@code app.db.purge-user}
     * @return a connection authenticated as that user; the caller closes it
     * @throws SQLException if the password cannot be set or the login fails
     */
    public Connection login(String user) throws SQLException {
        if (!user.equals(appUser) && !user.equals(purgeUser)) {
            throw new IllegalArgumentException("Not a test grantee: " + user);
        }
        String password = "Tk" + HexFormat.of().formatHex(RANDOM.generateSeed(12));
        try (Connection admin = adminConnection();
                Statement statement = admin.createStatement()) {
            // Test-only DDL. The user name is one of the two configured grantees (checked above); the password is
            // hex from SecureRandom, so neither can carry SQL.
            statement.execute(
                    vendor == DatabaseVendor.ORACLE
                            ? "ALTER USER " + user + " IDENTIFIED BY \"" + password + "\" ACCOUNT UNLOCK"
                            : "ALTER ROLE " + user + " LOGIN PASSWORD '" + password + "'");
        }
        return DriverManager.getConnection(connection.getJdbcUrl(), user, password);
    }

    private Connection adminConnection() throws SQLException {
        String admin = vendor == DatabaseVendor.ORACLE ? "system" : connection.getUsername();
        return DriverManager.getConnection(connection.getJdbcUrl(), admin, connection.getPassword());
    }

    private String script() {
        ClassPathResource resource =
                new ClassPathResource("db/test-users/" + vendor.name().toLowerCase(Locale.ROOT) + ".sql");
        try {
            // One statement per file (a PL/SQL block or a DO block); comment lines are dropped so the Oracle driver
            // sees DECLARE first.
            return resource.getContentAsString(StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.startsWith("--"))
                    .collect(Collectors.joining("\n"))
                    .replace("${app_user}", appUser)
                    .replace("${purge_user}", purgeUser)
                    .strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
