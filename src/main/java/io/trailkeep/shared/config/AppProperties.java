// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.ZoneId;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Deployment-specific settings under {@code app.*}. Startup fails when a required value is missing or invalid.
 *
 * @param proxy the trusted reverse-proxy settings
 * @param timezone the IANA time zone used to present dates to people; storage is always UTC
 * @param branding the product name shown in the UI and in emails
 * @param baseUrl the address people open the application at, such as {@code https://trailkeep.example.org}; the
 *     start of every deep link in an email. No default outside the {@code local} and {@code test} profiles: a mail
 *     must never link to a guess
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull @DefaultValue Proxy proxy,
        @NotNull @DefaultValue("UTC") ZoneId timezone,
        @Valid @NotNull @DefaultValue Branding branding,
        @NotNull URI baseUrl) {

    /**
     * Rejects a base URL that is not an absolute {@code http} or {@code https} address without query or fragment.
     *
     * @param proxy the trusted reverse-proxy settings
     * @param timezone the presentation time zone
     * @param branding the product naming
     * @param baseUrl the application's address
     */
    public AppProperties {
        boolean web = baseUrl != null && ("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme()));
        if (baseUrl != null
                && (!web || baseUrl.getHost() == null || baseUrl.getQuery() != null || baseUrl.getFragment() != null)) {
            throw new IllegalArgumentException("app.base-url must be an absolute http or https address");
        }
    }

    /**
     * The router or ingress that terminates client connections.
     *
     * @param pattern a regular expression (not a CIDR) matching the router's peer addresses. It feeds both
     *     {@code server.tomcat.remoteip.internal-proxies} and {@code trusted-proxies}, so {@code X-Forwarded-*}
     *     headers are honored only from those peers. There is deliberately no default outside the {@code local}
     *     and {@code test} profiles: the deploying organization must set it.
     */
    public record Proxy(@NotBlank String pattern) {

        /**
         * Rejects a pattern that does not compile, so a typo fails startup instead of silently trusting nobody.
         *
         * @param pattern the regular expression
         */
        public Proxy {
            if (pattern != null && !pattern.isBlank()) {
                Pattern.compile(pattern);
            }
        }
    }

    /**
     * Product naming.
     *
     * @param name the display name
     */
    public record Branding(
            @NotBlank @DefaultValue("Trailkeep") String name) {}
}
