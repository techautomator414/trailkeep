// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.TrailkeepApplication;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestBrowser;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * WP-04b acceptance (ADR-0008): two application instances sharing one database share a session, and a back-channel
 * logout from the identity provider ends it on both. Instance A is the test context (its port is the one Keycloak's
 * back-channel URL names); instance B is a second {@link TrailkeepApplication} on another port, pointed at the same
 * PostgreSQL and Keycloak. The test browser holds one {@code SESSION} cookie for localhost, as a browser behind a
 * load balancer would, so every request may land on either instance.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class SharedSessionsIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    // One dev-realm user per test: each test leaves a provider session behind, and Keycloak's admin logout of a user
    // with several provider sessions does not necessarily send a token for the newest, so tests must not share one.
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";
    private static final String BETA_READER = "00000000-0000-4000-8000-000000000005";
    private static final String BETA_ANALYST = "00000000-0000-4000-8000-000000000006";
    private static final String ALPHA_READER = "00000000-0000-4000-8000-000000000001";
    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Duration WAIT = Duration.ofSeconds(15);

    private static @Nullable ConfigurableApplicationContext instanceB;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcConnectionDetails database;

    @Autowired
    private UserSessionService userSessions;

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        KEYCLOAK.register(registry);
    }

    @BeforeEach
    void startInstanceB() {
        if (instanceB != null) {
            return;
        }
        String endpoints = KEYCLOAK.issuer() + "/protocol/openid-connect";
        instanceB = new SpringApplicationBuilder(TrailkeepApplication.class)
                .profiles("test")
                .properties(
                        "server.port=0",
                        "management.server.port=0",
                        "spring.datasource.url=" + database.getJdbcUrl(),
                        "spring.datasource.username=" + database.getUsername(),
                        "spring.datasource.password=" + database.getPassword(),
                        "spring.security.oauth2.client.provider.idp.issuer-uri=" + KEYCLOAK.issuer(),
                        "spring.security.oauth2.client.provider.idp.authorization-uri=" + endpoints + "/auth",
                        "spring.security.oauth2.client.provider.idp.token-uri=" + endpoints + "/token",
                        "spring.security.oauth2.client.provider.idp.jwk-set-uri=" + endpoints + "/certs",
                        "spring.security.oauth2.client.provider.idp.user-info-uri=" + endpoints + "/userinfo")
                .run();
    }

    @AfterAll
    static void stopInstanceB() {
        if (instanceB != null) {
            instanceB.close();
        }
    }

    @Test
    void bothInstancesShareTheSessionAndABackChannelLogoutEndsItOnBoth() throws Exception {
        TestBrowser browser = new TestBrowser();
        browser.login(KEYCLOAK.app("/"), "alpha.analyst");
        String sessionId = Objects.requireNonNull(browser.cookie("SESSION"));

        assertSignedIn(browser.get(KEYCLOAK.app("/")), "Alpha Analyst");
        assertSignedIn(browser.get(appB("/")), "Alpha Analyst");
        assertThat(count("SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?", sessionId))
                .isOne();
        assertThat(count("SELECT COUNT(*) FROM OIDC_SESSION WHERE SESSION_ID = ?", sessionId))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT AUTHORITIES_JSON FROM OIDC_SESSION WHERE SESSION_ID = ?", String.class, sessionId))
                .contains("X-CSRF-TOKEN");
        assertThat(jdbc.queryForObject("SELECT SID FROM OIDC_SESSION WHERE SESSION_ID = ?", String.class, sessionId))
                .as("Keycloak's ID token carries the provider session id")
                .isNotBlank();

        KEYCLOAK.logoutAtProvider(ALPHA_ANALYST);

        // Keycloak POSTs the logout token to instance A; A replays /logout on its loopback with the SESSION cookie
        // and the captured CSRF header. Only a 2xx/3xx answer to that call deletes the shared session row.
        awaitTrue(() -> count("SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?", sessionId) == 0);
        assertThat(count("SELECT COUNT(*) FROM OIDC_SESSION WHERE SESSION_ID = ?", sessionId))
                .isZero();
        assertSentToLogin(browser.get(KEYCLOAK.app("/")));
        assertSentToLogin(browser.get(appB("/")));
    }

    @Test
    void endingUserSessionsSignsThemOutEverywhere() throws Exception {
        TestBrowser browser = new TestBrowser();
        browser.login(KEYCLOAK.app("/"), "beta.analyst");
        assertSignedIn(browser.get(appB("/")), "Beta Analyst");
        long userId = userId(BETA_ANALYST);

        assertThat(userSessions.endSessionsOf(userId)).isPositive();

        assertSentToLogin(browser.get(appB("/")));
        assertSentToLogin(browser.get(KEYCLOAK.app("/")));
    }

    @Test
    void disablingUserEndsTheirSessionOnTheNextRequestOfEitherInstance() throws Exception {
        TestBrowser browser = new TestBrowser();
        browser.login(KEYCLOAK.app("/"), "beta.reader");
        assertSignedIn(browser.get(KEYCLOAK.app("/")), "Beta Reader");
        long userId = userId(BETA_READER);

        jdbc.update("UPDATE APP_USER SET DISABLED = TRUE WHERE ID = ?", userId);
        try {
            assertSentToLogin(browser.get(appB("/")));
            assertSentToLogin(browser.get(KEYCLOAK.app("/")));
        } finally {
            jdbc.update("UPDATE APP_USER SET DISABLED = FALSE WHERE ID = ?", userId);
        }
    }

    @Test
    void disablingAUserFromTheAdminScreenEndsTheirSessionsOnEveryInstance() throws Exception {
        TestBrowser victim = new TestBrowser();
        victim.login(KEYCLOAK.app("/"), "alpha.reader");
        assertSignedIn(victim.get(appB("/")), "Alpha Reader");
        long userId = userId(ALPHA_READER);
        TestBrowser admin = new TestBrowser();
        admin.login(KEYCLOAK.app("/admin/users"), "system.admin");

        try {
            adminPost(admin, "/admin/users/" + userId + "/disable");

            assertThat(count("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", Long.toString(userId)))
                    .as("the action deleted the sessions; the next-request check was not needed")
                    .isZero();
            assertSentToLogin(victim.get(appB("/")));
            assertSentToLogin(victim.get(KEYCLOAK.app("/")));
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'USER_DISABLED' AND ENTITY_ID = ?",
                            Integer.class,
                            userId))
                    .isOne();
        } finally {
            adminPost(admin, "/admin/users/" + userId + "/enable");
        }
        assertThat(jdbc.queryForObject("SELECT DISABLED FROM APP_USER WHERE ID = ?", Boolean.class, userId))
                .isFalse();
    }

    private static void adminPost(TestBrowser admin, String path) throws Exception {
        HttpResponse<String> page = admin.get(KEYCLOAK.app("/admin/users"));
        assertThat(page.statusCode()).isEqualTo(200);
        Matcher csrf = CSRF_FIELD.matcher(page.body());
        assertThat(csrf.find()).as("the user list carries a CSRF field").isTrue();
        HttpResponse<String> response = admin.post(KEYCLOAK.app(path), Map.of("_csrf", csrf.group(1)));
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location).endsWith("/admin/users"));
    }

    private static String appB(String path) {
        int port = Objects.requireNonNull(
                        ((WebServerApplicationContext) Objects.requireNonNull(instanceB)).getWebServer())
                .getPort();
        return "http://localhost:" + port + path;
    }

    private long userId(String subject) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, subject));
    }

    private int count(String sql, String sessionId) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, sessionId));
    }

    private static void assertSignedIn(HttpResponse<String> response, String name) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Signed in as " + name + ".");
    }

    private static void assertSentToLogin(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location).endsWith("/oauth2/authorization/idp"));
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within " + WAIT);
            }
            Thread.sleep(200);
        }
    }
}
