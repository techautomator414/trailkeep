// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestBrowser;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A real OIDC login round-trip against Keycloak with the dev realm ({@code deploy/local/keycloak}): browser redirect
 * to the provider, form login, authorization-code exchange, provisioning, and the disabled-user refusal. The HTTP
 * client plays the browser: it keeps cookies and follows redirects itself so each hop can be asserted.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class KeycloakLoginIT {

    private static final String COMPLIANCE_ADMIN_ID = "00000000-0000-4000-8000-000000000101";
    private static final String DISABLED_USER_ID = "00000000-0000-4000-8000-000000000103";
    private static final Pattern CSRF = Pattern.compile("<meta name=\"tk-csrf-token\" content=\"([^\"]+)\">");

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        KEYCLOAK.register(registry);
    }

    @Test
    void complianceAdminLogsInIsProvisionedAndGetsTheMappedRole() throws Exception {
        TestBrowser browser = new TestBrowser();

        HttpResponse<String> landing = browser.login(app("/"), "compliance.admin");

        // Signed in: the saved request ("/") is replayed and the dashboard renders for this user.
        assertThat(landing.uri().getPath()).isEqualTo("/");
        assertThat(landing.statusCode()).isEqualTo(200);
        assertThat(landing.body()).contains("<h1>Dashboard</h1>", "Signed in as Compliance Admin.");
        assertThat(browser.get(app("/anything")).statusCode()).isEqualTo(404);

        Map<String, Object> user = jdbc.queryForMap(
                "SELECT ID, DISPLAY_NAME, EMAIL, DISABLED, LAST_LOGIN_AT FROM APP_USER WHERE EXTERNAL_ID = ?",
                COMPLIANCE_ADMIN_ID);
        assertThat(user.get("DISPLAY_NAME")).isEqualTo("Compliance Admin");
        assertThat(user.get("EMAIL")).isEqualTo("compliance.admin@example.test");
        assertThat(user.get("DISABLED")).isEqualTo(false);
        assertThat(user.get("LAST_LOGIN_AT")).isNotNull();
        assertThat(jdbc.queryForList(
                        "SELECT GLOBAL_ROLE FROM APP_USER_GLOBAL_ROLE WHERE USER_ID = ?", String.class, user.get("ID")))
                .containsExactly("COMPLIANCE_ADMIN");
    }

    @Test
    void userDisabledInTrailkeepIsRefusedAfterAuthenticatingAtTheProvider() throws Exception {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (-103, ?, 'Disabled User', 'disabled.user@example.test', TRUE, CURRENT_TIMESTAMP)"
                        + " ON CONFLICT (EXTERNAL_ID) DO UPDATE SET DISABLED = TRUE",
                DISABLED_USER_ID);
        TestBrowser browser = new TestBrowser();

        HttpResponse<String> landing = browser.login(app("/"), "disabled.user");

        assertThat(landing.uri().getPath()).isEqualTo("/login");
        assertThat(landing.uri().getQuery()).isEqualTo("error");
        HttpResponse<String> after = browser.get(app("/"));
        assertThat(after.statusCode()).isEqualTo(302);
        assertThat(after.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location).endsWith("/oauth2/authorization/idp"));
        assertThat(jdbc.queryForObject(
                        "SELECT LAST_LOGIN_AT FROM APP_USER WHERE EXTERNAL_ID = ?", Object.class, DISABLED_USER_ID))
                .isNull();
    }

    /**
     * RP-initiated logout (BUILD_PLAN §9 "Identity"; security test map gap 2): signing out ends the session here and
     * sends the browser to the provider's end-session endpoint with the ID token as the hint and this application as
     * the place to come back to.
     */
    @Test
    void signingOutSendsTheBrowserToTheProvidersEndSessionEndpointWithTheIdTokenAndTheWayBack() throws Exception {
        TestBrowser browser = new TestBrowser();
        HttpResponse<String> landing = browser.login(app("/"), "compliance.admin");
        Matcher token = CSRF.matcher(landing.body());
        assertThat(token.find()).as("the layout's CSRF token").isTrue();

        HttpResponse<String> logout = browser.post(app("/logout"), Map.of("_csrf", token.group(1)));

        assertThat(logout.statusCode()).isEqualTo(302);
        URI endSession = URI.create(logout.headers().firstValue("Location").orElseThrow());
        assertThat(endSession.toString()).startsWith(KEYCLOAK.issuer() + "/protocol/openid-connect/logout?");
        assertThat(endSession.getQuery()).contains("id_token_hint=ey").contains("post_logout_redirect_uri=" + app("/"));
        assertThat(browser.get(app("/")).headers().firstValue("Location"))
                .as("the session is gone after sign-out")
                .hasValueSatisfying(location -> assertThat(location).endsWith("/oauth2/authorization/idp"));
    }

    private static String app(String path) {
        return KEYCLOAK.app(path);
    }
}
