// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.trailkeep.sla.SlaChunkWriter.Chunk;
import org.springframework.stereotype.Component;

/**
 * The evaluator on the metrics endpoint, next to the outbox's: counter {@code trailkeep.sla.transitions}, tagged
 * {@code to} = {@code at_risk} or {@code overdue}, and counter {@code trailkeep.sla.alerts}, the people told. Both
 * count committed chunks on this replica, so their sum over the cluster is the cluster's work.
 */
@Component
final class SlaMetrics {

    private final Counter atRisk;
    private final Counter overdue;
    private final Counter alerts;

    SlaMetrics(MeterRegistry registry) {
        this.atRisk = transitions(registry, "at_risk");
        this.overdue = transitions(registry, "overdue");
        this.alerts = Counter.builder("trailkeep.sla.alerts")
                .description("People told of an SLA change, one per case and person")
                .register(registry);
    }

    /**
     * Counts one committed chunk.
     *
     * @param chunk what it did
     */
    void committed(Chunk chunk) {
        atRisk.increment(chunk.atRisk());
        overdue.increment(chunk.overdue());
        alerts.increment(chunk.alerted());
    }

    private static Counter transitions(MeterRegistry registry, String to) {
        return Counter.builder("trailkeep.sla.transitions")
                .description("SLA state changes written by the evaluator on this replica")
                .tag("to", to)
                .register(registry);
    }
}
