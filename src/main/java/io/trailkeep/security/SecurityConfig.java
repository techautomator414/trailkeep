// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import io.trailkeep.user.UserSignInService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * HTTP security. Spring Security's defaults stay on (CSRF, security headers, session fixation protection); every
 * application request needs an OIDC login. Only the management server is open: it runs on its own port in every
 * profile, serves nothing but the exposed actuator endpoints (anything else is 404), and is not exposed outside the
 * pod.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig {

    private static final String MANAGEMENT_NAMESPACE = "management";

    @Bean
    @Order(1)
    SecurityFilterChain managementChain(HttpSecurity http) {
        return http.securityMatcher(SecurityConfig::isManagementServer)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain applicationChain(HttpSecurity http, TrailkeepOidcUserService oidcUserService) {
        return http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2Login(login -> login.userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService)))
                .build();
    }

    /** True for a request served by the management server's context (Spring Boot's "management" namespace). */
    static boolean isManagementServer(HttpServletRequest request) {
        WebApplicationContext context =
                WebApplicationContextUtils.getWebApplicationContext(request.getServletContext());
        return context != null && WebServerApplicationContext.hasServerNamespace(context, MANAGEMENT_NAMESPACE);
    }

    @Bean
    TrailkeepOidcUserService trailkeepOidcUserService(UserSignInService signIn, SecurityProperties properties) {
        return new TrailkeepOidcUserService(signIn, properties);
    }
}
