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
import io.trailkeep.restoredrill.AbstractRestoreDrillIT.Owner;
import io.trailkeep.testsupport.TestDatabaseUsers;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.testsupport.WithAppUserSecurityContextFactory;
import io.trailkeep.user.GlobalRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * The application's side of the restore drill: the migration Job's profile, the web application as the runtime user,
 * what it serves (health, search, downloads), and the audit canaries it writes and the drill confirms.
 */
final class DrillApplication {

    static final String APP_USER = "trailkeep_app";

    /** The Compliance Admin of the 500k perf IT, who reads every team. */
    static final long ADMIN = 900_000_999L;

    /** Seeded files downloaded through the application: single-chunk and multi-chunk ones, first and last. */
    static final List<Integer> DOWNLOADED = List.of(1, 50, 777, 1000, 1999, 2000);

    private static final String CANARY_TYPE = "RESTORE_DRILL";
    private static final Pattern HMAC = Pattern.compile("\"hmac\"\\s*:\\s*\"([0-9a-f]{64})\"");
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The application's view of the data, compared before and after.
     *
     * @param searchRows the cases the search finds
     * @param downloads each downloaded file's SHA-256
     */
    record Observation(long searchRows, Map<Integer, String> downloads) {}

    /** A snapshot with one redacted component, as the audit trail stores a case's description. */
    private record Canary(@Redacted String value) {}

    /** Carries the Compliance Admin the application's requests are made as. */
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    private static final class Admin {}

    private DrillApplication() {}

    /** The migration Job's profile as the schema owner, as {@code prod,migrate} runs before every rollout. */
    static ConfigurableApplicationContext migrationJob(
            String profile, JdbcDatabaseContainer<?> database, Owner owner, TestDatabaseUsers users) {
        return new SpringApplicationBuilder(TrailkeepApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(profile, "migrate")
                .initializers(context -> context.getBeanFactory().registerSingleton("testDatabaseUsers", users))
                .run(
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.username=" + owner.username(),
                        "--spring.datasource.password=" + owner.password(),
                        "--app.db.schema=" + owner.schema());
    }

    /** The web application as the runtime user, Flyway off, on free ports, as the web Deployment runs. */
    static ConfigurableApplicationContext start(
            String profile, JdbcDatabaseContainer<?> database, Owner owner, TestDatabaseUsers users, String hmacKey)
            throws SQLException {
        return new SpringApplicationBuilder(TrailkeepApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles(profile)
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

    /**
     * Asks the running application for its health, a search and the downloads, as the Compliance Admin.
     *
     * @param app the application
     * @return what it served
     * @throws Exception if a request fails
     */
    static Observation observe(ConfigurableApplicationContext app) throws Exception {
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

    /**
     * Adds the Compliance Admin to the seed, as the 500k perf IT does.
     *
     * @param owner a connection as the owner
     * @throws SQLException if the rows cannot be written
     */
    static void insertAdmin(Connection owner) throws SQLException {
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

    /**
     * Writes one case-less audit event per value through the application, each value redacted to an HMAC.
     *
     * @param app the application
     * @param values the values, in order
     * @param firstId the entity id of the first event
     */
    static void writeCanaries(ConfigurableApplicationContext app, List<String> values, int firstId) {
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

    /**
     * Confirms each stored canary under the key, and that a different key does not confirm it.
     *
     * @param owner a connection as the owner
     * @param values the values written, in order
     * @param key the HMAC key they were written under
     * @throws Exception if the rows cannot be read
     */
    static void verifyCanaries(Connection owner, List<String> values, byte[] key) throws Exception {
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
}
