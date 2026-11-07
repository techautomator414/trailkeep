// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Identity of the evaluator's entities: a ledger row by id only, a job run by its name. */
class SlaEntitiesTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    @Test
    void ledgerRowsAreEqualOnlyToThemselvesUntilPersisted() {
        AlertLedger row = new AlertLedger(7, AlertType.SLA_AT_RISK, 2, NOW);

        Object same = row;
        assertThat(row.equals(same)).isTrue();
        assertThat(row).isNotEqualTo(new AlertLedger(7, AlertType.SLA_AT_RISK, 2, NOW));
        assertThat(row.hashCode()).isEqualTo(new AlertLedger(8, AlertType.SLA_OVERDUE, 1, NOW).hashCode());
        assertThat(row.toString()).contains("type=SLA_AT_RISK", "cycle=2");
    }

    @Test
    void jobRunsAreIdentifiedByName() {
        JobRun run = new JobRun(SlaEvaluator.JOB);
        run.succeeded(NOW, 3);

        assertThat(run).isEqualTo(new JobRun(SlaEvaluator.JOB)).isNotEqualTo(new JobRun("another-job"));
        assertThat(run.hashCode()).isEqualTo(new JobRun(SlaEvaluator.JOB).hashCode());
    }
}
