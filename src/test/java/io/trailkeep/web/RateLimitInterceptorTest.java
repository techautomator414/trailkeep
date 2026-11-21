// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** Where rate limiting may be off, and the limits {@code application.yml} ships (#247). */
class RateLimitInterceptorTest {

    private static final RateLimitProperties.Limit LIMIT = new RateLimitProperties.Limit(1, 1, Duration.ofMinutes(1));

    @Test
    void theProdProfileRefusesToStartWithRateLimitingOff() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatIllegalStateException()
                .isThrownBy(() -> new RateLimitInterceptor(properties(false), new SimpleMeterRegistry(), prod))
                .withMessageContaining("app.rate-limit.enabled");
        new RateLimitInterceptor(properties(true), new SimpleMeterRegistry(), prod);
    }

    @Test
    void otherProfilesMayTurnItOff() {
        new RateLimitInterceptor(properties(false), new SimpleMeterRegistry(), new MockEnvironment());
    }

    @Test
    void theShippedLimitsAreOnAndGenerous() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        RateLimitProperties shipped = Binder.get(environment)
                .bind("app.rate-limit", RateLimitProperties.class)
                .get();

        assertThat(shipped.enabled()).isTrue();
        assertThat(shipped.search()).isEqualTo(new RateLimitProperties.Limit(120, 120, Duration.ofMinutes(1)));
        assertThat(shipped.upload()).isEqualTo(new RateLimitProperties.Limit(30, 30, Duration.ofMinutes(10)));
    }

    private static RateLimitProperties properties(boolean enabled) {
        return new RateLimitProperties(enabled, LIMIT, LIMIT);
    }
}
