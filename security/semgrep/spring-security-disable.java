// SPDX-License-Identifier: Apache-2.0
// Semgrep rule test cases; never compiled.
class SecurityConfigCases {

    SecurityFilterChain lambdas(HttpSecurity http) throws Exception {
        // ruleid: spring-security-protection-disabled
        http.csrf(csrf -> csrf.disable());
        // ruleid: spring-security-protection-disabled
        http.csrf(AbstractHttpConfigurer::disable);
        // ruleid: spring-security-protection-disabled
        http.headers(AbstractHttpConfigurer::disable);
        // ruleid: spring-security-protection-disabled
        http.headers(headers -> headers.frameOptions(frame -> frame.disable()));
        // ruleid: spring-security-protection-disabled
        http.headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable));
        // ruleid: spring-security-protection-disabled
        http.headers(headers -> headers.contentTypeOptions(options -> options.disable()));
        http.headers(headers -> {
            headers.referrerPolicy(policy -> policy.policy(ReferrerPolicy.SAME_ORIGIN));
            // ruleid: spring-security-protection-disabled
            headers.httpStrictTransportSecurity(hsts -> {
                hsts.disable();
            });
        });
        return http.build();
    }

    void legacyChains(HttpSecurity http) throws Exception {
        // ruleid: spring-security-protection-disabled
        http.csrf().disable();
        // ruleid: spring-security-protection-disabled
        http.headers().frameOptions().disable();
        // ruleid: spring-security-protection-disabled
        http.headers().cacheControl().disable();
    }

    SecurityFilterChain allowed(HttpSecurity http) throws Exception {
        // ok: spring-security-protection-disabled
        http.formLogin(AbstractHttpConfigurer::disable);
        // ok: spring-security-protection-disabled
        http.httpBasic(basic -> basic.disable());
        // ok: spring-security-protection-disabled
        http.csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()));
        // ok: spring-security-protection-disabled
        http.headers(headers -> headers.frameOptions(frame -> frame.deny()));
        // ok: spring-security-protection-disabled
        http.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'")));
        return http.build();
    }
}
