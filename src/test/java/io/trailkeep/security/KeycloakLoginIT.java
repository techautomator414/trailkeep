// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    private static final String PASSWORD = KeycloakDevRealm.PASSWORD;
    private static final String COMPLIANCE_ADMIN_ID = "00000000-0000-4000-8000-000000000101";
    private static final String DISABLED_USER_ID = "00000000-0000-4000-8000-000000000103";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern LOGIN_FORM = Pattern.compile("<form id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        KEYCLOAK.register(registry);
    }

    @Test
    void complianceAdminLogsInIsProvisionedAndGetsTheMappedRole() throws Exception {
        Browser browser = new Browser();

        HttpResponse<String> landing = browser.login("compliance.admin");

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
        Browser browser = new Browser();

        HttpResponse<String> landing = browser.login("disabled.user");

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

    private static String app(String path) {
        return KEYCLOAK.app(path);
    }

    private static String issuer() {
        return KEYCLOAK.issuer();
    }

    /**
     * Browsers treat {@code http://localhost} as a secure context and send {@code Secure} cookies to it; Keycloak
     * marks its login-session cookies {@code Secure}. {@link CookieManager} would drop them over plain HTTP, so this
     * store clears the flag. Everything here talks to localhost only.
     */
    private static final class LocalhostIsSecureCookieStore implements CookieStore {

        private final CookieStore delegate = new CookieManager().getCookieStore();

        @Override
        public void add(URI uri, HttpCookie cookie) {
            cookie.setSecure(false);
            delegate.add(uri, cookie);
        }

        @Override
        public List<HttpCookie> get(URI uri) {
            return delegate.get(uri);
        }

        @Override
        public List<HttpCookie> getCookies() {
            return delegate.getCookies();
        }

        @Override
        public List<URI> getURIs() {
            return delegate.getURIs();
        }

        @Override
        public boolean remove(URI uri, HttpCookie cookie) {
            return delegate.remove(uri, cookie);
        }

        @Override
        public boolean removeAll() {
            return delegate.removeAll();
        }
    }

    /** A minimal browser: a cookie jar and hand-followed redirects. */
    private static final class Browser {

        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(new LocalhostIsSecureCookieStore(), CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build();

        HttpResponse<String> login(String username) throws IOException, InterruptedException {
            HttpResponse<String> page = follow(get(app("/")));
            assertThat(page.uri().toString()).startsWith(issuer() + "/protocol/openid-connect/auth");
            Matcher form = LOGIN_FORM.matcher(page.body());
            assertThat(form.find()).as("Keycloak login form").isTrue();
            String action = form.group(1).replace("&amp;", "&");
            return follow(post(action, Map.of("username", username, "password", PASSWORD)));
        }

        HttpResponse<String> get(String url) throws IOException, InterruptedException {
            return client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(TIMEOUT)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> post(String url, Map<String, String> form)
                throws IOException, InterruptedException {
            String body = form.entrySet().stream()
                    .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            return client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> follow(HttpResponse<String> first) throws IOException, InterruptedException {
            HttpResponse<String> response = first;
            for (int hop = 0; hop < 10 && response.statusCode() / 100 == 3; hop++) {
                String location = response.headers().firstValue("Location").orElseThrow();
                response = get(response.uri().resolve(location).toString());
            }
            return response;
        }
    }
}
