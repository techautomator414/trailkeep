// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The evaluator's health as the cluster sees it (BUILD_PLAN §6, Part A #30), from {@code JOB_RUN}: up while its last
 * success on any replica is within {@code app.sla.health-grace} (two evaluation periods by default), down after that,
 * unknown before its first run. It is a member of neither probe group (application.yml enumerates their members), so a
 * replica that did not win the lock is never restarted for it; it shows on {@code /actuator/health} and as the gauge
 * {@code trailkeep.sla.evaluator.age}, the seconds since that success.
 */
@Component
final class SlaEvaluatorHealthIndicator implements HealthIndicator {

    private final JobRuns runs;
    private final InstantSource clock;
    private final Duration grace;

    SlaEvaluatorHealthIndicator(
            JobRuns runs,
            InstantSource clock,
            @Value("${app.sla.health-grace:PT30M}") Duration grace,
            MeterRegistry registry) {
        this.runs = runs;
        this.clock = clock;
        this.grace = grace;
        Gauge.builder("trailkeep.sla.evaluator.age", this::age)
                .description("Seconds since the SLA evaluator last succeeded on any replica")
                .baseUnit("seconds")
                .register(registry);
    }

    @Override
    public Health health() {
        Optional<Instant> last = runs.lastSuccess(SlaEvaluator.JOB);
        if (last.isEmpty()) {
            return Health.unknown().withDetail("lastSuccess", "never").build();
        }
        Duration age = Duration.between(last.get(), clock.instant());
        return (age.compareTo(grace) > 0 ? Health.down() : Health.up())
                .withDetail("lastSuccess", last.get().toString())
                .withDetail("grace", grace.toString())
                .build();
    }

    private double age() {
        return runs.lastSuccess(SlaEvaluator.JOB)
                .map(last -> (double) Duration.between(last, clock.instant()).toSeconds())
                .orElse(Double.NaN);
    }
}
