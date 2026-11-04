// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.ActiveProfiles;

/** The shared back-channel registry over {@code OIDC_SESSION}: save, find by session, sid or subject, clean up. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
class JdbcOidcSessionRegistryIT {

    private static final Map<String, String> CSRF = Map.of("X-CSRF-TOKEN", "csrf-value");

    @Autowired
    private JdbcOidcSessionRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sessionIsSavedAndRemovedByItsIdWithTheCsrfHeaderIntact() {
        String session = UUID.randomUUID().toString();
        registry.saveSessionInformation(information(session, "sid-" + session, "sub-1"));

        OidcSessionInformation removed = Objects.requireNonNull(registry.removeSessionInformation(session));

        assertThat(removed.getSessionId()).isEqualTo(session);
        assertThat(removed.getAuthorities()).isEqualTo(CSRF);
        assertThat(removed.getPrincipal().getSubject()).isEqualTo("sub-1");
        assertThat(registry.removeSessionInformation(session)).isNull();
    }

    @Test
    void logoutTokenWithSidRemovesOnlyThatProviderSession() {
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        String subject = "sub-" + UUID.randomUUID();
        registry.saveSessionInformation(information(first, "sid-a-" + first, subject));
        registry.saveSessionInformation(information(second, "sid-b-" + second, subject));

        List<OidcSessionInformation> removed =
                list(registry.removeSessionInformation(logoutToken("sid-a-" + first, subject)));

        assertThat(removed).extracting(OidcSessionInformation::getSessionId).containsExactly(first);
        assertThat(registry.removeSessionInformation(second)).isNotNull();
    }

    @Test
    void logoutTokenWithoutSidRemovesEverySessionOfTheSubject() {
        String subject = "sub-" + UUID.randomUUID();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        registry.saveSessionInformation(information(first, "sid-1-" + first, subject));
        registry.saveSessionInformation(information(second, null, subject));

        List<OidcSessionInformation> removed = list(registry.removeSessionInformation(logoutToken(null, subject)));

        assertThat(removed).extracting(OidcSessionInformation::getSessionId).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void savingTheSameSessionAgainReplacesItsRow() {
        String session = UUID.randomUUID().toString();
        registry.saveSessionInformation(information(session, "sid-old", "sub-2"));
        registry.saveSessionInformation(information(session, "sid-new", "sub-2"));

        assertThat(jdbc.queryForObject("SELECT SID FROM OIDC_SESSION WHERE SESSION_ID = ?", String.class, session))
                .isEqualTo("sid-new");
        registry.removeSessionInformation(session);
    }

    @Test
    void dailyCleanupRemovesOldRowsWhoseSessionNoLongerExists() {
        String orphan = UUID.randomUUID().toString();
        registry.saveSessionInformation(information(orphan, "sid-orphan", "sub-3"));
        jdbc.update(
                "UPDATE OIDC_SESSION SET CREATED_AT = ? WHERE SESSION_ID = ?",
                OffsetDateTime.now(ZoneOffset.UTC)
                        .minus(JdbcOidcSessionRegistry.ORPHAN_GRACE)
                        .minusMinutes(1),
                orphan);

        registry.removeOrphans();

        assertThat(rows(orphan)).isZero();
    }

    @Test
    void dailyCleanupSparesAFreshRowWhoseLoginHasNotCommittedItsSessionYet() {
        String fresh = UUID.randomUUID().toString();
        registry.saveSessionInformation(information(fresh, "sid-fresh", "sub-4"));

        registry.removeOrphans();

        assertThat(rows(fresh)).isOne();
        registry.removeSessionInformation(fresh);
    }

    private int rows(String sessionId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM OIDC_SESSION WHERE SESSION_ID = ?", Integer.class, sessionId));
    }

    private static OidcSessionInformation information(String sessionId, @Nullable String sid, String subject) {
        OidcIdToken.Builder token = OidcIdToken.withTokenValue("id-token")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (sid != null) {
            token.claim("sid", sid);
        }
        return new OidcSessionInformation(sessionId, CSRF, new DefaultOidcUser(List.of(), token.build()));
    }

    private static OidcLogoutToken logoutToken(@Nullable String sid, String subject) {
        OidcLogoutToken.Builder token = OidcLogoutToken.withTokenValue("logout-token")
                .issuer("http://idp.example.test/realms/test")
                .audience(List.of("trailkeep"))
                .jti(UUID.randomUUID().toString())
                .events(Map.of("http://schemas.openid.net/event/backchannel-logout", Map.of()))
                .subject(subject)
                .issuedAt(Instant.now());
        if (sid != null) {
            token.sessionId(sid);
        }
        return token.build();
    }

    private static List<OidcSessionInformation> list(Iterable<OidcSessionInformation> found) {
        return StreamSupport.stream(found.spliterator(), false).toList();
    }
}
