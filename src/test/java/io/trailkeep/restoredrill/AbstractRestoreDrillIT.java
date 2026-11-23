// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.restoredrill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.trailkeep.TrailkeepApplication;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.shared.persistence.DatabaseVendor;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.testsupport.WithAppUserSecurityContextFactory;
import io.trailkeep.user.GlobalRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The restore drill (WP-30 part c, BUILD_PLAN §16 "Backups/DR"), once per vendor, at the release volume: the 500k seed
 * ({@code docs/perf/release-500k.md}: 50 000 cases, 500 000 comments, 500 000 audit events) plus {@value
 * AttachmentSeed#FILES} attachments in the database, as the application stores them.
 *
 * <ol>
 *   <li>A database is migrated by the migration Job's profile as the schema owner, seeded, and used by the application
 *       as the runtime user: it writes audit events with redacted values (under a key generated for this run), and the
 *       search and some downloads are recorded.
 *   <li>Every table is fingerprinted (rows and a digest of every value) and the schema is backed up with the vendor's
 *       own logical backup tool, timed.
 *   <li>The database's container is destroyed. A new, empty one is started and the backup restored into it, timed.
 *   <li>The restored copy must hold the same rows, value for value; its search index must be in the same state; the
 *       migration Job's profile must find nothing to migrate and Flyway's validation must pass; the application must
 *       start as the runtime user, report healthy, find what the search found before, serve the same attachment
 *       bytes, confirm the redacted audit values under the retained key (and not under another), and write new rows;
 *       every attachment's chunks must add up to its recorded size and SHA-256.
 * </ol>
 *
 * <p>The table and the facts go to {@code target/restore-drill/}; {@code docs/ops/restore-drill.md} records a run.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
abstract class AbstractRestoreDrillIT {

    static final String APP_USER = "trailkeep_app";
    static final String PURGE_USER = "trailkeep_purge";
    static final String IMPORT_USER = "trailkeep_import";

    /** The Compliance Admin of the 500k perf IT, who reads every team. */
    static final long ADMIN = 900_000_999L;

    /** Seeded files downloaded through the application: single-chunk and multi-chunk ones, first and last. */
    private static final List<Integer> DOWNLOADED = List.of(1, 50, 777, 1000, 1999, 2000);

    private static final int CANARIES = 10;
    private static final String CANARY_TYPE = "RESTORE_DRILL";
    private static final Pattern HMAC = Pattern.compile("\"hmac\"\\s*:\\s*\"([0-9a-f]{64})\"");
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The schema owner's login and the schema the tables live in.
     *
     * @param username the owner
     * @param password its password, for this run only
     * @param schema the schema, as the database's metadata spells it
     */
    record Owner(String username, String password, String schema) {}

    /**
     * A backup file inside a database container.
     *
     * @param path its path in the container
     * @param bytes its size
     */
    record Dump(String path, long bytes) {}

    /** The application's view of the data, before and after: the search's rows and the downloads' SHA-256. */
    private record Observation(long searchRows, Map<Integer, String> downloads) {}

    /** A snapshot with one redacted component, as the audit trail stores a case's description. */
    private record Canary(@Redacted String value) {}

    abstract String vendorProfile();

    abstract DatabaseVendor vendor();

    abstract String title();

    abstract String fileName();

    abstract JdbcDatabaseContainer<?> newDatabase();

    abstract Owner newOwner(JdbcDatabaseContainer<?> database, TestDatabaseUsers users) throws SQLException;

    /**
     * Applies the 500k seed as the schema owner, recording its steps.
     *
     * @param database the container
     * @param owner the owner
     * @param connection a connection as the owner
     * @param report the report
     * @throws Exception if seeding fails
     */
    abstract void seed(JdbcDatabaseContainer<?> database, Owner owner, Connection connection, DrillReport report)
            throws Exception;

    /**
     * Where the backup file goes in a container of this vendor.
     *
     * @param database the container
     * @return the path
     * @throws SQLException if the database must be asked
     */
    abstract String dumpPath(JdbcDatabaseContainer<?> database) throws SQLException;

    abstract void backup(JdbcDatabaseContainer<?> database, Owner owner, String path) throws Exception;

    abstract void restore(JdbcDatabaseContainer<?> database, Owner owner, String path, DrillReport report)
            throws Exception;

    /**
     * The search index's state, which must read the same before the backup and after the restore.
     *
     * @param database the container
     * @param owner a connection as the owner
     * @return named facts
     * @throws SQLException if they cannot be read
     */
    abstract Map<String, String> indexState(JdbcDatabaseContainer<?> database, Connection owner) throws SQLException;

    /**
     * Each sequence's high-water mark; after the restore, none may be lower.
     *
     * @param owner a connection as the owner
     * @return the marks by sequence name
     * @throws SQLException if they cannot be read
     */
    abstract Map<String, Long> sequences(Connection owner) throws SQLException;

    /**
     * The space the schema takes in the database, tables, indexes and large objects together.
     *
     * @param owner a connection as the owner
     * @return bytes
     * @throws SQLException if it cannot be read
     */
    abstract long databaseBytes(Connection owner) throws SQLException;

    /** Extra connection properties for this vendor. */
    Properties connectionProperties() {
        return new Properties();
    }

    /** Carries the Compliance Admin the application's requests are made as. */
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    private static final class Admin {}

    @Test
    void aBackupRestoredIntoANewDatabaseIsCompleteAndServes() throws Exception {
        DrillReport report = new DrillReport(title(), fileName());
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        String hmacKey = Base64.getEncoder().encodeToString(key);
        List<String> canaries = new ArrayList<>();
        for (int i = 0; i < CANARIES; i++) {
            canaries.add("restore drill canary " + HexFormat.of().formatHex(RANDOM.generateSeed(8)));
        }
        Path local = Path.of("target", "restore-drill", fileName().replace(".md", ".dump"));
        try {
            Files.createDirectories(local.getParent());
            Owner owner;
            Map<String, DatabaseFingerprint.Table> before;
            Map<String, String> indexBefore;
            Map<String, Long> sequencesBefore;
            Observation observed;
            String path;
            JdbcDatabaseContainer<?> source = report.time("Source database started", this::newDatabase);
            try {
                TestDatabaseUsers users = users(source);
                owner = newOwner(source, users);
                Owner theOwner = owner;
                report.run(
                        "Schema migrated (migration Job profile)",
                        () -> migrationJob(source, theOwner, users).close());
                try (Connection connection = connect(source, owner)) {
                    seed(source, owner, connection, report);
                    insertAdmin(connection);
                    // What V20 costs an upgrade at this volume (docs/RUNBOOK.md): the same index, built and dropped.
                    report.run("V20's index built on the 500 000 audit events (a copy, then dropped)", () -> {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("CREATE INDEX IX_DRILL_AUDIT_AT ON AUDIT_EVENT (AT_TS, ID)");
                            statement.execute("DROP INDEX IX_DRILL_AUDIT_AT");
                        }
                    });
                    long bytes = report.time(
                            "Attachments written (" + AttachmentSeed.FILES + " files)",
                            () -> AttachmentSeed.write(connection));
                    report.fact("Attachments: " + AttachmentSeed.FILES + " files, " + mib(bytes)
                            + " MiB of incompressible content, in 1 MiB chunks");
                }
                try (ConfigurableApplicationContext app = report.time(
                        "Application started on the source (runtime user)",
                        () -> application(source, theOwner, users, hmacKey))) {
                    writeCanaries(app, canaries, 0);
                    observed = observe(app);
                }
                try (Connection connection = connect(source, owner)) {
                    before = report.time(
                            "Source fingerprinted (every row of every table)",
                            () -> DatabaseFingerprint.of(connection, theOwner.schema()));
                    indexBefore = indexState(source, connection);
                    sequencesBefore = sequences(connection);
                    report.fact("Database size: " + mib(databaseBytes(connection)) + " MiB");
                }
                report.fact("Source: " + before.size() + " tables, "
                        + before.values().stream()
                                .mapToLong(DatabaseFingerprint.Table::rows)
                                .sum() + " rows; search index " + indexBefore);
                path = dumpPath(source);
                String sourcePath = path;
                long backupMs = report.run("**Backup**", () -> backup(source, theOwner, sourcePath));
                long size = fileSize(source, path);
                report.fact("Backup: " + mib(size) + " MiB in " + seconds(backupMs) + " s");
                report.run(
                        "Backup copied off the database host",
                        () -> source.copyFileFromContainer(path, local.toString()));
            } finally {
                source.stop();
            }
            report.fact("Source database destroyed");

            long rto = 0;
            JdbcDatabaseContainer<?> target = report.time("New empty database started", this::newDatabase);
            try {
                TestDatabaseUsers users = users(target);
                users.createGrantees();
                String targetPath = dumpPath(target);
                Owner theOwner = owner;
                rto += report.run(
                        "Backup copied to the new database host",
                        () -> target.copyFileToContainer(MountableFile.forHostPath(local, 0644), targetPath));
                rto += report.run("**Restore**", () -> restore(target, theOwner, targetPath, report));
                try (Connection connection = connect(target, owner)) {
                    Map<String, DatabaseFingerprint.Table> after = report.time(
                            "Restored copy fingerprinted", () -> DatabaseFingerprint.of(connection, theOwner.schema()));
                    assertThat(after)
                            .as("every table, its row count and every value")
                            .isEqualTo(before);
                    report.fact("Rows and values: all " + after.size() + " tables match the source, digest for digest");
                    Map<String, String> indexAfter = indexState(target, connection);
                    assertThat(indexAfter).as("the search index's state").isEqualTo(indexBefore);
                    report.fact("Search index after the restore: " + indexAfter);
                    Map<String, Long> sequencesAfter = sequences(connection);
                    sequencesBefore.forEach((name, mark) -> assertThat(sequencesAfter.get(name))
                            .as("sequence " + name)
                            .isNotNull()
                            .isGreaterThanOrEqualTo(mark));
                    report.fact("Sequences: all " + sequencesAfter.size() + " at or above their source marks");
                    verifyCanaries(connection, canaries, key);
                    report.fact("Audit: the " + CANARIES + " redacted canary values confirm under the retained"
                            + " HMAC key and under no other");
                }
                rto += report.run("Migration Job profile: Flyway validate, nothing pending", () -> {
                    try (ConfigurableApplicationContext job = migrationJob(target, theOwner, users)) {
                        Flyway flyway = job.getBean(Flyway.class);
                        flyway.validate();
                        assertThat(flyway.info().pending())
                                .as("pending migrations")
                                .isEmpty();
                        report.fact(
                                "Flyway: validate passed, " + flyway.info().applied().length + " applied, 0 pending");
                    }
                });
                long started = System.nanoTime();
                try (ConfigurableApplicationContext app = report.time(
                        "Application started on the restored copy (runtime user)",
                        () -> application(target, theOwner, users, hmacKey))) {
                    rto += (System.nanoTime() - started) / 1_000_000;
                    Observation now =
                            report.time("Health, search and downloads through the application", () -> observe(app));
                    assertThat(now).as("what the application serves").isEqualTo(observed);
                    report.fact("Application: healthy; the search finds the same " + now.searchRows() + " cases; "
                            + DOWNLOADED.size() + " downloads give the same bytes (SHA-256)");
                    writeCanaries(app, List.of("written after the restore"), CANARIES);
                    report.fact("Writes: the application records new audit events on the restored copy");
                }
                try (Connection runtime = users.login(APP_USER)) {
                    AttachmentSeed.Check check = report.time(
                            "Every attachment read back as the runtime user",
                            () -> AttachmentSeed.check(runtime, theOwner.schema()));
                    assertThat(check.problems())
                            .as("attachments whose chunks do not match")
                            .isEmpty();
                    assertThat(check.files()).isEqualTo(AttachmentSeed.FILES);
                    report.fact("Attachments: all " + check.files() + " files, " + mib(check.bytes())
                            + " MiB, match their recorded size and SHA-256");
                }
            } finally {
                target.stop();
            }
            report.fact("Recovery time measured (copy in, restore, migration Job, application start): " + seconds(rto)
                    + " s");
        } finally {
            Files.deleteIfExists(local);
            report.write();
        }
    }

    // --- the database ------------------------------------------------------------------------------------------------

    TestDatabaseUsers users(JdbcDatabaseContainer<?> database) {
        return new TestDatabaseUsers(vendor(), details(database), APP_USER, PURGE_USER, IMPORT_USER);
    }

    Connection connect(JdbcDatabaseContainer<?> database, Owner owner) throws SQLException {
        Properties properties = connectionProperties();
        properties.setProperty("user", owner.username());
        properties.setProperty("password", owner.password());
        return DriverManager.getConnection(database.getJdbcUrl(), properties);
    }

    Connection admin(JdbcDatabaseContainer<?> database) throws SQLException {
        String admin = vendor() == DatabaseVendor.ORACLE ? "system" : database.getUsername();
        return DriverManager.getConnection(database.getJdbcUrl(), admin, database.getPassword());
    }

    /**
     * Runs a command in the database's container and fails with its output if it fails.
     *
     * @param database the container
     * @param command the command and its arguments
     * @return its standard output and error, joined
     * @throws Exception if it cannot run or exits non-zero
     */
    static String exec(JdbcDatabaseContainer<?> database, String... command) throws Exception {
        ExecResult result = database.execInContainer(command);
        String output = result.getStdout() + result.getStderr();
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(command[0] + " exited " + result.getExitCode() + ":\n" + output);
        }
        return output;
    }

    private static long fileSize(JdbcDatabaseContainer<?> database, String path) throws Exception {
        return Long.parseLong(exec(database, "stat", "-c", "%s", path).strip());
    }

    private static JdbcConnectionDetails details(JdbcDatabaseContainer<?> database) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return database.getUsername();
            }

            @Override
            public String getPassword() {
                return database.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return database.getJdbcUrl();
            }
        };
    }

    private static void insertAdmin(Connection owner) throws SQLException {
        try (PreparedStatement user = owner.prepareStatement("INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME,"
                        + " EMAIL, DISABLED, CREATED_AT) VALUES (?, 'perf-admin', 'Perf Compliance Admin',"
                        + " 'perf-admin@example.invalid', ?, CURRENT_TIMESTAMP)");
                PreparedStatement role = owner.prepareStatement(
                        "INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)")) {
            user.setLong(1, ADMIN);
            user.setBoolean(2, false);
            user.executeUpdate();
            role.setLong(1, ADMIN);
            role.setString(2, GlobalRole.COMPLIANCE_ADMIN.name());
            role.executeUpdate();
        }
    }

    // --- the application ---------------------------------------------------------------------------------------------

    /** The migration Job's profile as the schema owner, as {@code prod,migrate} runs before every rollout. */
    private ConfigurableApplicationContext migrationJob(
            JdbcDatabaseContainer<?> database, Owner owner, TestDatabaseUsers users) {
        return new SpringApplicationBuilder(TrailkeepApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(vendorProfile(), "migrate")
                .initializers(context -> context.getBeanFactory().registerSingleton("testDatabaseUsers", users))
                .run(
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.username=" + owner.username(),
                        "--spring.datasource.password=" + owner.password(),
                        "--app.db.schema=" + owner.schema());
    }

    /** The web application as the runtime user, Flyway off, on free ports, as the web Deployment runs. */
    private ConfigurableApplicationContext application(
            JdbcDatabaseContainer<?> database, Owner owner, TestDatabaseUsers users, String hmacKey)
            throws SQLException {
        return new SpringApplicationBuilder(TrailkeepApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles(vendorProfile())
                .run(
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.username=" + APP_USER,
                        "--spring.datasource.password=" + users.newPassword(APP_USER),
                        "--spring.flyway.enabled=false",
                        "--app.db.schema=" + owner.schema(),
                        "--app.audit.hmac-key=" + hmacKey,
                        "--server.port=0",
                        "--management.server.port=0");
    }

    private Observation observe(ConfigurableApplicationContext app) throws Exception {
        String port = app.getEnvironment().getProperty("local.management.port");
        HttpResponse<String> health;
        try (HttpClient client = HttpClient.newHttpClient()) {
            health = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
        assertThat(health.statusCode()).as("health: " + health.body()).isEqualTo(200);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) app)
                .apply(springSecurity())
                .build();
        MockHttpSession admin = adminSession();
        assertThat(body(mvc, "/cases?q=kestrel", admin))
                .as("the cases page with a search")
                .isNotEmpty();
        long rows = new String(body(mvc, "/cases/export.csv?q=kestrel", admin), StandardCharsets.UTF_8)
                        .lines()
                        .count()
                - 1;
        assertThat(rows).as("cases the search finds").isPositive();
        Map<Integer, String> downloads = new LinkedHashMap<>();
        for (int i : DOWNLOADED) {
            byte[] content =
                    body(mvc, "/cases/" + AttachmentSeed.caseNumber(i) + "/attachments/" + AttachmentSeed.id(i), admin);
            byte[] seeded = new byte[AttachmentSeed.size(i)];
            new SplittableRandom(AttachmentSeed.id(i)).nextBytes(seeded);
            assertThat(content).as("download of seeded file " + i).isEqualTo(seeded);
            downloads.put(i, HexFormat.of().formatHex(AttachmentSeed.sha256(content)));
        }
        return new Observation(rows, downloads);
    }

    private static byte[] body(MockMvc mvc, String url, MockHttpSession admin) throws Exception {
        MvcResult result = mvc.perform(get(url).session(admin)).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        assertThat(result.getResponse().getStatus())
                .as(url + " -> " + result.getResponse().getHeader("Location"))
                .isEqualTo(200);
        return result.getResponse().getContentAsByteArray();
    }

    /**
     * A signed-in session of the Compliance Admin, as the shared session store holds one after an OIDC login: the
     * security context and the login time. {@code SessionPermissionsFilter} reloads the person's roles from the
     * database on every request, so the restored copy's user tables are exercised too.
     */
    private static MockHttpSession adminSession() {
        WithAppUser user = Objects.requireNonNull(Admin.class.getAnnotation(WithAppUser.class));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new WithAppUserSecurityContextFactory().createSecurityContext(user));
        session.setAttribute("trailkeep.loginAt", System.currentTimeMillis());
        return session;
    }

    // --- the audit canaries ------------------------------------------------------------------------------------------

    /** Writes one case-less audit event per value through the application, each value redacted to an HMAC. */
    private static void writeCanaries(ConfigurableApplicationContext app, List<String> values, int firstId) {
        AuditRecorder recorder = app.getBean(AuditRecorder.class);
        new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            for (int i = 0; i < values.size(); i++) {
                recorder.record(new AuditEntry(
                        null,
                        "Restore drill",
                        AuditOrigin.SYSTEM,
                        null,
                        CANARY_TYPE,
                        (long) firstId + i,
                        "CANARY",
                        recorder.diff(null, new Canary(values.get(i)))));
            }
        });
    }

    private static void verifyCanaries(Connection owner, List<String> values, byte[] key) throws Exception {
        byte[] other = new byte[key.length];
        RANDOM.nextBytes(other);
        List<String> stored = new ArrayList<>();
        try (PreparedStatement statement = owner.prepareStatement(
                "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE ENTITY_TYPE = ? ORDER BY ENTITY_ID")) {
            statement.setString(1, CANARY_TYPE);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    stored.add(hmacOf(rows.getString(1)));
                }
            }
        }
        assertThat(stored).as("canary audit events").hasSize(values.size());
        for (int i = 0; i < values.size(); i++) {
            assertThat(stored.get(i)).as("canary " + i + " under the key").isEqualTo(hmac(key, values.get(i)));
            assertThat(stored.get(i)).as("canary " + i + " under another key").isNotEqualTo(hmac(other, values.get(i)));
        }
    }

    private static String hmacOf(@Nullable String json) {
        Matcher matcher = HMAC.matcher(String.valueOf(json));
        assertThat(matcher.find()).as("an HMAC in " + json).isTrue();
        return matcher.group(1);
    }

    private static String hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.1f", ms / 1000.0);
    }
}
