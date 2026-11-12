// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * A scheduled job's health as the cluster sees it (BUILD_PLAN §6, Part A #30), from {@code JOB_RUN}: up while its last
 * success on any replica is within a grace period, down after that, unknown before its first run. A subclass is a
 * member of neither probe group (application.yml enumerates their members), so a replica that did not win the job's
 * lock is never restarted for it. It also registers a gauge of the seconds since that success. Public so that a job
 * in another module (the report delivery of WP-23) reports its health the same way.
 */
public abstract class JobHealth implements HealthIndicator {

    private final String job;
    private final JobRuns runs;
    private final InstantSource clock;
    private final Duration grace;

    /**
     * A job's health indicator and age gauge.
     *
     * @param job the job's name, its {@code JOB_RUN} row
     * @param runs the job runs
     * @param clock the clock
     * @param grace how long after a success the job is still up
     * @param registry where the gauge goes
     * @param gauge the gauge's name
     */
    protected JobHealth(
            String job, JobRuns runs, InstantSource clock, Duration grace, MeterRegistry registry, String gauge) {
        this.job = job;
        this.runs = runs;
        this.clock = clock;
        this.grace = grace;
        Gauge.builder(gauge, this::age)
                .description("Seconds since the job " + job + " last succeeded on any replica")
                .baseUnit("seconds")
                .register(registry);
    }

    /**
     * The job this indicator watches, its {@code JOB_RUN} row.
     *
     * @return the job's name
     */
    public final String job() {
        return job;
    }

    /**
     * How long after a success the job is still up; the System screen marks it late after that.
     *
     * @return the grace period
     */
    public final Duration grace() {
        return grace;
    }

    @Override
    public Health health() {
        Optional<Instant> last = runs.lastSuccess(job);
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
        return runs.lastSuccess(job)
                .map(last -> (double) Duration.between(last, clock.instant()).toSeconds())
                .orElse(Double.NaN);
    }
}
