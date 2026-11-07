// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.trailkeep.casefile.CaseSlaService;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * The evaluator's chunking (BUILD_PLAN §6): id-ordered chunks of 500, each written in its own call and counted on the
 * metrics once it returns (committed).
 */
class SlaEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    private final CaseSlaService slas = mock(CaseSlaService.class);
    private final SlaChunkWriter writer = mock(SlaChunkWriter.class);
    private final JobRuns runs = mock(JobRuns.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SlaEvaluator evaluator =
            new SlaEvaluator(slas, writer, runs, new SlaMetrics(registry), InstantSource.fixed(NOW));

    @Test
    void candidatesAreWrittenInChunksOfFiveHundredInIdOrder() {
        List<Long> ids = LongStream.rangeClosed(1, 1001).boxed().toList();
        when(slas.dueForEvaluation(NOW)).thenReturn(ids);
        List<List<Long>> chunks = new ArrayList<>();
        when(writer.write(anyList(), eq(NOW))).thenAnswer(call -> {
            List<Long> chunk = List.copyOf(call.getArgument(0));
            chunks.add(chunk);
            return new SlaChunkWriter.Chunk(chunk.size(), 0, chunk.size() * 2);
        });

        int moved = evaluator.evaluate(NOW);

        assertThat(moved).isEqualTo(1001);
        assertThat(chunks).extracting(List::size).containsExactly(500, 500, 1);
        assertThat(chunks.get(1).get(0)).isEqualTo(501L);
        verify(runs).succeeded(SlaEvaluator.JOB, NOW, 1001);
        assertThat(registry.get("trailkeep.sla.transitions")
                        .tag("to", "at_risk")
                        .counter()
                        .count())
                .isEqualTo(1001);
        assertThat(registry.get("trailkeep.sla.transitions")
                        .tag("to", "overdue")
                        .counter()
                        .count())
                .isZero();
        assertThat(registry.get("trailkeep.sla.alerts").counter().count()).isEqualTo(2002);
    }

    @Test
    void theScheduledTickEvaluatesAtTheApplicationClock() {
        when(slas.dueForEvaluation(NOW)).thenReturn(List.of());

        evaluator.tick();

        verify(slas).dueForEvaluation(NOW);
        verify(runs).succeeded(SlaEvaluator.JOB, NOW, 0);
    }
}
