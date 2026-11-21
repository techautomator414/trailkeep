// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.autoconfigure.web.ErrorProperties.IncludeAttribute;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Error responses show none of the error attributes (security test map gap 12): the values Spring Boot actually binds
 * from the {@code prod} profile into {@link WebProperties#getError()}, which its error controller and error view
 * resolver read, rather than the text of the configuration file. Under Spring Boot 4 these keys are
 * {@code spring.web.error.*}; the old {@code server.error.*} names are not read at all, which is how they once sat in
 * {@code application.yml} doing nothing. Starts no web server, database or auto-configuration.
 */
class ErrorPropertiesTest {

    @Test
    void prodBindsEveryErrorAttributeOff() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Bound.class)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .bannerMode(Banner.Mode.OFF)
                .run()) {
            ErrorProperties error = context.getBean(WebProperties.class).getError();
            assertThat(error.getIncludeMessage()).as("message").isEqualTo(IncludeAttribute.NEVER);
            assertThat(error.getIncludeStacktrace()).as("stack trace").isEqualTo(IncludeAttribute.NEVER);
            assertThat(error.isIncludeException()).as("exception class").isFalse();
            assertThat(error.getIncludeBindingErrors()).as("binding errors").isEqualTo(IncludeAttribute.NEVER);
            assertThat(error.getIncludePath()).as("request path").isEqualTo(IncludeAttribute.NEVER);
        }
    }

    /** Binds {@link WebProperties} as Spring MVC's auto-configuration does, and nothing else. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebProperties.class)
    static class Bound {}
}
