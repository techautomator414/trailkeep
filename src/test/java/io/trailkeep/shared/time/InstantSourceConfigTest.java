// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class InstantSourceConfigTest {

    private static final Instant FIXED = Instant.parse("2026-01-02T03:04:05Z");

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(InstantSourceConfig.class);

    @Test
    void applicationTimeComesFromTheUtcSystemClock() {
        runner.run(context -> {
            InstantSource source = context.getBean(InstantSource.class);
            assertThat(source).isInstanceOf(Clock.class);
            assertThat(((Clock) source).getZone()).isEqualTo(ZoneOffset.UTC);
        });
    }

    @Test
    void testsCanOverrideTheInstantSourceBean() {
        runner.withAllowBeanDefinitionOverriding(true)
                .withUserConfiguration(FixedInstantSource.class)
                .run(context -> assertThat(context.getBean(InstantSource.class).instant())
                        .isEqualTo(FIXED));
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedInstantSource {
        @Bean
        InstantSource instantSource() {
            return InstantSource.fixed(FIXED);
        }
    }
}
