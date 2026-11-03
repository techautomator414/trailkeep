// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AppPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void failsFastWhenTheProxyPatternIsMissing() {
        runner.run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("app.proxy.pattern"));
    }

    @Test
    void failsFastWhenTheProxyPatternIsBlank() {
        runner.withPropertyValues("app.proxy.pattern= ")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("app.proxy.pattern"));
    }

    @Test
    void failsFastWhenTheProxyPatternDoesNotCompile() {
        runner.withPropertyValues("app.proxy.pattern=10\\.128\\.(")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsFastOnAnUnknownTimeZone() {
        runner.withPropertyValues("app.proxy.pattern=10\\.0\\.0\\.1", "app.timezone=Mars/Olympus")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void appliesDefaultsForTimeZoneAndBranding() {
        runner.withPropertyValues("app.proxy.pattern=10\\.0\\.0\\.1").run(context -> {
            AppProperties properties = context.getBean(AppProperties.class);
            assertThat(properties.proxy().pattern()).isEqualTo("10\\.0\\.0\\.1");
            assertThat(properties.timezone()).isEqualTo(ZoneId.of("UTC"));
            assertThat(properties.branding().name()).isEqualTo("Trailkeep");
        });
    }

    @Test
    void bindsAnIanaTimeZone() {
        runner.withPropertyValues("app.proxy.pattern=x", "app.timezone=America/Chicago")
                .run(context -> assertThat(context.getBean(AppProperties.class).timezone())
                        .isEqualTo(ZoneId.of("America/Chicago")));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class Config {}
}
