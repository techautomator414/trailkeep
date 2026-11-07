// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.InstantSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The SLA evaluator's cluster-level health ({@link JobHealth}): within {@code app.sla.health-grace} of its last
 * success, two evaluation periods by default; gauge {@code trailkeep.sla.evaluator.age}.
 */
@Component
final class SlaEvaluatorHealthIndicator extends JobHealth {

    SlaEvaluatorHealthIndicator(
            JobRuns runs,
            InstantSource clock,
            @Value("${app.sla.health-grace:PT30M}") Duration grace,
            MeterRegistry registry) {
        super(SlaEvaluator.JOB, runs, clock, grace, registry, "trailkeep.sla.evaluator.age");
    }
}
