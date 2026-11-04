// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test-only: lets the ops ITs call {@link RemoteAddressController}'s {@code /test-only/**} probes without an OIDC
 * login, because they test forwarded headers and logging, not authentication. Every other default (CSRF, headers)
 * stays on, and the application's own chains are untouched. Imported only by those ITs; never packaged.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestOnlyEndpointsSecurity {

    @Bean
    @Order(0)
    SecurityFilterChain testOnlyEndpoints(HttpSecurity http) {
        return http.securityMatcher("/test-only/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }
}
