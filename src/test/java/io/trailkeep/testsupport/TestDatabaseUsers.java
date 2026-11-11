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
 * {@code app.db.app-user}, {@code app.db.purge-user} and {@code app.db.import-user} exist when the grants run; the
 * roles cannot log in. For {@code DatabaseGrantsIT}, {@link #login(String)} gives one of them a fresh random password
 * and connects as it, and {@link #newPassword(String)} gives one to a test that starts an application as it, so no
 * credential is ever written down.
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
    private final String importUser;

    /**
     * Creates the callback.
     *
     * @param vendor the container's vendor
     * @param connection the container's connection details
     * @param appUser {@code app.db.app-user}
     * @param purgeUser {@code app.db.purge-user}
     * @param importUser {@code app.db.import-user}
     */
    public TestDatabaseUsers(
            DatabaseVendor vendor,
            JdbcConnectionDetails connection,
            String appUser,
            String purgeUser,
            String importUser) {
        this.vendor = vendor;
        this.connection = connection;
        this.appUser = appUser;
        this.purgeUser = purgeUser;
        this.importUser = importUser;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_MIGRATE || event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        if (event == Event.AFTER_MIGRATE) {
            readableProbe(context.getConnection());
            return;
        }
        try (Connection admin = adminConnection();
                Statement statement = admin.createStatement()) {
            statement.execute(script());
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create the test database users", e);
        }
    }

    /**
     * Lets the users whose applications validate the schema read the test-only {@code LINT_PROBE}, which the test
     * classpath's {@code LintProbe} entity maps: on Oracle, Hibernate {@code validate} sees only granted tables. Done
     * after every migrate, as the owner, because the Oracle {@code R__grants.sql} revokes what its matrix does not
     * list.
     */
    private void readableProbe(Connection owner) {
        try (Statement statement = owner.createStatement()) {
            // Test-only DDL on the test-only table; the grantees are the configured names, not input.
            statement.execute("GRANT SELECT ON LINT_PROBE TO " + appUser + ", " + importUser);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot grant the test probe table", e);
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
     * @param user one of the three configured grantees
     * @return a connection authenticated as that user; the caller closes it
     * @throws SQLException if the password cannot be set or the login fails
     */
    public Connection login(String user) throws SQLException {
        return DriverManager.getConnection(connection.getJdbcUrl(), user, newPassword(user));
    }

    /**
     * Sets a fresh random password for {@code user}, which may then log in.
     *
     * @param user one of the three configured grantees
     * @return the password, for this test run only
     * @throws SQLException if the password cannot be set
     */
    public String newPassword(String user) throws SQLException {
        if (!user.equals(appUser) && !user.equals(purgeUser) && !user.equals(importUser)) {
            throw new IllegalArgumentException("Not a test grantee: " + user);
        }
        String password = "Tk" + HexFormat.of().formatHex(RANDOM.generateSeed(12));
        try (Connection admin = adminConnection();
                Statement statement = admin.createStatement()) {
            // Test-only DDL. The user name is one of the configured grantees (checked above); the password is
            // hex from SecureRandom, so neither can carry SQL.
            statement.execute(
                    vendor == DatabaseVendor.ORACLE
                            ? "ALTER USER " + user + " IDENTIFIED BY \"" + password + "\" ACCOUNT UNLOCK"
                            : "ALTER ROLE " + user + " LOGIN PASSWORD '" + password + "'");
        }
        return password;
    }

    /**
     * The container's JDBC URL, for a test that starts an application as one of the grantees.
     *
     * @return the URL
     */
    public String jdbcUrl() {
        return connection.getJdbcUrl();
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
                    .replace("${import_user}", importUser)
                    .strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
