// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import java.time.Instant;
import java.time.InstantSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The back-channel logout registry over {@code OIDC_SESSION}, shared by every replica (ADR-0008). Spring Security
 * only ships an in-memory registry, which a logout token reaching one replica cannot use for a session another
 * replica created. Plain JDBC because the table has no entity: rows are written once at login, read only by sid or
 * subject, and removed by logout or the daily orphan cleanup.
 */
class JdbcOidcSessionRegistry implements OidcSessionRegistry {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

    private static final String COLUMNS =
            "SELECT SESSION_ID, SID, PRINCIPAL, AUTHORITIES_JSON, CREATED_AT" + " FROM OIDC_SESSION WHERE ";
    private static final String BY_SESSION = COLUMNS + "SESSION_ID = ?";
    private static final String BY_SID = COLUMNS + "SID = ?";
    private static final String BY_PRINCIPAL = COLUMNS + "PRINCIPAL = ?";

    private final JdbcClient jdbc;
    private final InstantSource clock;
    private final JsonMapper json = JsonMapper.builder().build();

    JdbcOidcSessionRegistry(JdbcClient jdbc, InstantSource clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void saveSessionInformation(OidcSessionInformation info) {
        jdbc.sql("DELETE FROM OIDC_SESSION WHERE SESSION_ID = ?")
                .param(info.getSessionId())
                .update();
        jdbc.sql("INSERT INTO OIDC_SESSION (SESSION_ID, SID, PRINCIPAL, AUTHORITIES_JSON, CREATED_AT)"
                        + " VALUES (?, ?, ?, ?, ?)")
                .param(info.getSessionId())
                .param(info.getPrincipal().getIdToken().getClaimAsString("sid"))
                .param(info.getPrincipal().getSubject())
                .param(json.writeValueAsString(info.getAuthorities()))
                .param(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }

    @Override
    public @Nullable OidcSessionInformation removeSessionInformation(String clientSessionId) {
        List<OidcSessionInformation> found = select(BY_SESSION, clientSessionId);
        jdbc.sql("DELETE FROM OIDC_SESSION WHERE SESSION_ID = ?")
                .param(clientSessionId)
                .update();
        return found.isEmpty() ? null : found.getFirst();
    }

    @Override
    public Iterable<OidcSessionInformation> removeSessionInformation(OidcLogoutToken token) {
        // A logout token names the provider session (sid), or only the subject when it ends all of them.
        String sid = token.getSessionId();
        String subject = token.getSubject();
        List<OidcSessionInformation> found;
        if (sid != null) {
            found = select(BY_SID, sid);
        } else if (subject != null) {
            found = select(BY_PRINCIPAL, subject);
        } else {
            found = List.of();
        }
        found.forEach(info -> jdbc.sql("DELETE FROM OIDC_SESSION WHERE SESSION_ID = ?")
                .param(info.getSessionId())
                .update());
        return found;
    }

    /**
     * Ends the registry rows of the given application sessions (a disabled user's).
     *
     * @param sessionIds Spring Session ids
     */
    void removeSessions(List<String> sessionIds) {
        sessionIds.forEach(id -> jdbc.sql("DELETE FROM OIDC_SESSION WHERE SESSION_ID = ?")
                .param(id)
                .update());
    }

    /** Daily: drop rows whose session expired or was deleted without a logout (ADR-0008). */
    @Scheduled(cron = "${app.security.oidc-session-cleanup-cron:0 23 3 * * *}")
    void removeOrphans() {
        jdbc.sql("DELETE FROM OIDC_SESSION WHERE SESSION_ID NOT IN (SELECT SESSION_ID FROM SPRING_SESSION)")
                .update();
    }

    private List<OidcSessionInformation> select(String query, String value) {
        return jdbc.sql(query)
                .param(value)
                .query((rows, index) -> {
                    Instant created =
                            rows.getObject("CREATED_AT", OffsetDateTime.class).toInstant();
                    OidcIdToken.Builder idToken = OidcIdToken.withTokenValue("restored-from-registry")
                            .subject(rows.getString("PRINCIPAL"))
                            .issuedAt(created)
                            .expiresAt(created.plusSeconds(1));
                    String sid = rows.getString("SID");
                    if (sid != null) {
                        idToken.claim("sid", sid);
                    }
                    Map<String, String> headers = json.readValue(rows.getString("AUTHORITIES_JSON"), HEADERS);
                    return new OidcSessionInformation(
                            rows.getString("SESSION_ID"), headers, new DefaultOidcUser(List.of(), idToken.build()));
                })
                .list();
    }
}
