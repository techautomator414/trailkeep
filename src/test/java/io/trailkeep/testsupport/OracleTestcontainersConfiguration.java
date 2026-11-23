// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import io.trailkeep.shared.persistence.DatabaseVendor;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
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

    /** System property: the size in MB of the three redo log groups to give the container (opt-in). */
    public static final String REDO_PROPERTY = "trailkeep.oracle.redo-mb";

    /** A plain absolute directory with a trailing slash, the only shape of redo log directory that is used in DDL. */
    private static final Pattern REDO_DIRECTORY = Pattern.compile("^/[A-Za-z0-9_./-]+/$");

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
        resizeRedo(container);
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "stop-" + name.getRepository()));
        return container;
    }

    /**
     * Opt-in, {@code -Dtrailkeep.oracle.redo-mb=512}: replaces the image's two 20 MB redo log groups with three of
     * that size, as a production database has (issue 308, {@code deploy/README.md}); a no-op without the property.
     * Runs as {@code SYSTEM} in the root container, where the redo logs are, before any test touches the database.
     */
    private static void resizeRedo(JdbcDatabaseContainer<?> container) {
        String size = System.getProperty(REDO_PROPERTY);
        if (size == null || size.isBlank() || "0".equals(size.strip())) {
            return;
        }
        int megabytes = Integer.parseInt(size.strip());
        String root = container.getJdbcUrl().replaceAll("(?i)(free|xe)pdb1$", "$1");
        try (Connection connection = DriverManager.getConnection(root, "system", container.getPassword());
                Statement st = connection.createStatement()) {
            List<Integer> old = new ArrayList<>();
            String dir;
            try (ResultSet rs = st.executeQuery("SELECT GROUP# FROM V$LOG ORDER BY GROUP#")) {
                while (rs.next()) {
                    old.add(rs.getInt(1));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT MEMBER FROM V$LOGFILE WHERE ROWNUM = 1")) {
                rs.next();
                String member = rs.getString(1);
                dir = member.substring(0, member.lastIndexOf('/') + 1);
                if (!REDO_DIRECTORY.matcher(dir).matches()) {
                    throw new IllegalStateException("Unexpected redo log directory: " + dir);
                }
            }
            int first = old.get(old.size() - 1) + 1;
            // Three groups at least: the image has two, and a group still ACTIVE (needed for crash recovery) cannot be
            // reused, so with two a switch waits for the checkpoint of the other.
            // DDL cannot bind, so it runs as constant PL/SQL that builds the statement from bound values.
            try (CallableStatement add = connection.prepareCall("BEGIN EXECUTE IMMEDIATE 'ALTER DATABASE ADD LOGFILE"
                    + " GROUP ' || TO_CHAR(?) || ' (' || DBMS_ASSERT.ENQUOTE_LITERAL(?) || ') SIZE ' || TO_CHAR(?)"
                    + " || 'M'; END;")) {
                for (int i = 0; i < Math.max(3, old.size()); i++) {
                    add.setInt(1, first + i);
                    add.setString(2, dir + "redo" + (first + i) + ".log");
                    add.setInt(3, megabytes);
                    add.execute();
                }
            }
            try (CallableStatement drop = connection.prepareCall(
                    "BEGIN EXECUTE IMMEDIATE 'ALTER DATABASE DROP LOGFILE GROUP ' || TO_CHAR(?); END;")) {
                for (int group : old) {
                    switchAwayFrom(connection, group);
                    drop.setInt(1, group);
                    drop.execute();
                }
            }
            logRedo(st, megabytes);
        } catch (SQLException failed) {
            throw new IllegalStateException("Cannot resize the redo logs to " + megabytes + " MB", failed);
        }
    }

    private static void logRedo(Statement st, int megabytes) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT GROUP#, BYTES / 1048576, STATUS FROM V$LOG ORDER BY GROUP#")) {
            StringBuilder groups = new StringBuilder();
            while (rs.next()) {
                groups.append(" [group ")
                        .append(rs.getInt(1))
                        .append(", ")
                        .append(rs.getInt(2))
                        .append(" MB, ")
                        .append(rs.getString(3))
                        .append(']');
            }
            System.out.println("Oracle redo resized to " + megabytes + " MB:" + groups);
        }
    }

    /** A group can be dropped once it is INACTIVE: switch away from it and checkpoint until it is. */
    private static void switchAwayFrom(Connection connection, int group) throws SQLException {
        try (Statement st = connection.createStatement();
                PreparedStatement query = connection.prepareStatement("SELECT STATUS FROM V$LOG WHERE GROUP# = ?")) {
            query.setInt(1, group);
            for (int attempt = 0; attempt < 20; attempt++) {
                String status;
                try (ResultSet rs = query.executeQuery()) {
                    rs.next();
                    status = rs.getString(1);
                }
                if ("INACTIVE".equals(status)) {
                    return;
                }
                st.execute("ALTER SYSTEM SWITCH LOGFILE");
                st.execute("ALTER SYSTEM CHECKPOINT");
            }
        }
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
