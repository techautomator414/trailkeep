// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.micrometer.core.instrument.MeterRegistry;
import io.trailkeep.sla.JobHealth;
import io.trailkeep.sla.JobRuns;
import java.time.Duration;
import java.time.InstantSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code reportDelivery}: the report-delivery job's health from {@code JOB_RUN}, down once its last fully successful
 * tick on any replica is older than {@code app.reports.health-grace} (two hours: the job is hourly). A member of
 * neither probe group, like the SLA jobs' indicators.
 */
@Component
final class ReportDeliveryHealthIndicator extends JobHealth {

    ReportDeliveryHealthIndicator(
            JobRuns runs,
            InstantSource clock,
            @Value("${app.reports.health-grace:PT2H}") Duration grace,
            MeterRegistry registry) {
        super(ReportDeliveryTick.JOB, runs, clock, grace, registry, "trailkeep.reports.delivery.age");
    }
}
