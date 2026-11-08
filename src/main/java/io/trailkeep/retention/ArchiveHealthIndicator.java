// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.retention;

import io.micrometer.core.instrument.MeterRegistry;
import io.trailkeep.sla.JobHealth;
import io.trailkeep.sla.JobRuns;
import java.time.Duration;
import java.time.InstantSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code archive}: the archive job's health from {@code JOB_RUN}, down once its last fully successful run on any
 * replica is older than {@code app.retention.health-grace} (two hours: the job is hourly). A member of neither probe
 * group, like the other jobs' indicators.
 */
@Component
final class ArchiveHealthIndicator extends JobHealth {

    ArchiveHealthIndicator(
            JobRuns runs,
            InstantSource clock,
            @Value("${app.retention.health-grace:PT2H}") Duration grace,
            MeterRegistry registry) {
        super(ArchiveJob.JOB, runs, clock, grace, registry, "trailkeep.retention.archive.age");
    }
}
