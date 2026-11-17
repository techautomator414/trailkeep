// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;

/**
 * Session and transport controls of BUILD_PLAN §9 that were configured but asserted nowhere (security test map gaps 1,
 * 5 and 6), against the real server behind the router: the peer matches {@code app.proxy.pattern}, so its
 * {@code X-Forwarded-Proto: https} makes the request secure, as the OpenShift router's does.
 *
 * <ul>
 *   <li>the {@code SESSION} cookie is {@code Secure}, {@code HttpOnly} and {@code SameSite=Lax};
 *   <li>HTTPS responses carry HSTS, plain-HTTP ones do not;
 *   <li>a session idle for more than 30 minutes is gone and one idle for less is kept, by moving a stored session's
 *       last access into the past rather than waiting.
 * </ul>
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "app.proxy.pattern=127\\.0\\.0\\.1"})
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class SessionHardeningIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final HttpClient http =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @LocalServerPort
    private int port;

    @Autowired
    private SessionRepository<? extends Session> sessions;

    @Test
    void theSessionCookieIsSecureHttpOnlyAndSameSiteLax() throws Exception {
        // An anonymous request for a page saves it in a new session before the redirect to the provider.
        String cookie = sessionCookie(get("/cases", true, null)).orElseThrow();

        assertThat(cookie).startsWith("SESSION=");
        assertThat(List.of(cookie.split(";\\s*"))).contains("Secure", "HttpOnly", "SameSite=Lax");
    }

    @Test
    void httpsResponsesCarryHstsAndPlainHttpOnesDoNot() throws Exception {
        assertThat(get("/cases", true, null).headers().firstValue("Strict-Transport-Security"))
                .hasValue("max-age=31536000 ; includeSubDomains");
        assertThat(get("/cases", false, null).headers().firstValue("Strict-Transport-Security"))
                .isEmpty();
    }

    @Test
    void sessionsIdleForMoreThanThirtyMinutesAreGoneAndThoseIdleForLessAreKept() throws Exception {
        assertThat(sessions.createSession().getMaxInactiveInterval()).isEqualTo(IDLE_TIMEOUT);

        String recent = storedSessionLastUsed(IDLE_TIMEOUT.minusMinutes(1));
        HttpResponse<Void> kept = get("/cases", true, recent);
        assertThat(sessionCookie(kept))
                .as("no new session for one idle 29 minutes")
                .isEmpty();
        assertThat(sessions.findById(recent)).isNotNull();

        String stale = storedSessionLastUsed(IDLE_TIMEOUT.plusMinutes(1));
        HttpResponse<Void> replaced = get("/cases", true, stale);
        assertThat(sessionCookie(replaced))
                .as("a new session replaces one idle 31 minutes")
                .hasValueSatisfying(cookie -> assertThat(cookie).doesNotContain(stale));
        assertThat(sessions.findById(stale)).isNull();
    }

    /** A stored session whose last access lies {@code idle} in the past; its id is the cookie value. */
    private String storedSessionLastUsed(Duration idle) {
        return store(sessions, idle);
    }

    private static <S extends Session> String store(SessionRepository<S> repository, Duration idle) {
        S session = repository.createSession();
        session.setLastAccessedTime(Instant.now().minus(idle));
        repository.save(session);
        return session.getId();
    }

    private HttpResponse<Void> get(String path, boolean https, @Nullable String session)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Forwarded-Proto", https ? "https" : "http");
        if (session != null) {
            request.header("Cookie", "SESSION=" + session);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.discarding());
    }

    private static Optional<String> sessionCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith("SESSION="))
                .findFirst();
    }
}
