// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import io.trailkeep.shared.persistence.DatabaseProperties;
import io.trailkeep.shared.persistence.DatabaseVendor;
import java.time.InstantSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.OracleJdbcIndexedSessionRepositoryCustomizer;
import org.springframework.session.jdbc.PostgreSqlJdbcIndexedSessionRepositoryCustomizer;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Spring Session JDBC shared by every replica (ADR-0008): the vendor repository customizer, JSON attributes, the
 * {@code SESSION} cookie, and the back-channel logout registry with its daily cleanup.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableScheduling
class SessionConfig {

    /** Spring Session's cookie name, which the back-channel handler must send too. */
    static final String SESSION_COOKIE = "SESSION";

    /** The vendor's upsert statements for attributes (concurrent requests of one session). */
    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> vendorSessionCustomizer(DatabaseProperties database) {
        return database.vendor() == DatabaseVendor.ORACLE
                ? new OracleJdbcIndexedSessionRepositoryCustomizer()
                : new PostgreSqlJdbcIndexedSessionRepositoryCustomizer();
    }

    /** Attributes as JSON; Spring Session looks this bean up by name. */
    @Bean
    ConversionService springSessionConversionService() {
        return SessionJson.conversionService();
    }

    /**
     * {@code SESSION}; HttpOnly, Secure, SameSite=Lax (BUILD_PLAN §9). The value is the raw session id (no base64),
     * because {@code OidcBackChannelLogoutHandler} replays the registry's session id as the cookie value.
     */
    @Bean
    CookieSerializer cookieSerializer() {
        DefaultCookieSerializer cookies = new DefaultCookieSerializer();
        cookies.setCookieName(SESSION_COOKIE);
        cookies.setUseBase64Encoding(false);
        cookies.setUseHttpOnlyCookie(true);
        cookies.setUseSecureCookie(true);
        cookies.setSameSite("Lax");
        cookies.setCookiePath("/");
        return cookies;
    }

    @Bean
    JdbcOidcSessionRegistry oidcSessionRegistry(JdbcClient jdbc, InstantSource clock) {
        return new JdbcOidcSessionRegistry(jdbc, clock);
    }

    @Bean
    UserSessionService userSessionService(
            FindByIndexNameSessionRepository<? extends Session> sessions, JdbcOidcSessionRegistry registry) {
        return new JdbcUserSessionService(sessions, registry);
    }
}
