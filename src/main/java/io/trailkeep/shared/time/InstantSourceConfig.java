// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.time;

import java.time.Clock;
import java.time.InstantSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the {@link InstantSource} that all application time comes from. Code never calls {@code Instant.now()}
 * or {@code Clock.systemDefaultZone()} directly; tests override the {@code instantSource} bean
 * (for example with {@code @TestBean}) to fix or advance time.
 */
@Configuration(proxyBeanMethods = false)
public class InstantSourceConfig {

    /**
     * The system clock in UTC. Display zones are applied at the edge (BUILD_PLAN §8 rule 5), never here.
     *
     * @return the application-wide instant source
     */
    @Bean
    public InstantSource instantSource() {
        return Clock.systemUTC();
    }
}
