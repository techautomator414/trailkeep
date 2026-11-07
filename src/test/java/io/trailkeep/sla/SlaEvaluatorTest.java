// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.trailkeep.casefile.CaseSlaService;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** The evaluator's chunking (BUILD_PLAN §6): id-ordered chunks of 500, each written in its own call. */
class SlaEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    @Test
    void candidatesAreWrittenInChunksOfFiveHundredInIdOrder() {
        CaseSlaService slas = mock(CaseSlaService.class);
        SlaChunkWriter writer = mock(SlaChunkWriter.class);
        List<Long> ids = LongStream.rangeClosed(1, 1001).boxed().toList();
        when(slas.dueForEvaluation(NOW)).thenReturn(ids);
        List<List<Long>> chunks = new ArrayList<>();
        when(writer.write(anyList(), eq(NOW))).thenAnswer(call -> {
            List<Long> chunk = List.copyOf(call.getArgument(0));
            chunks.add(chunk);
            return chunk.size();
        });

        int moved = new SlaEvaluator(slas, writer, InstantSource.fixed(NOW)).evaluate(NOW);

        assertThat(moved).isEqualTo(1001);
        assertThat(chunks).extracting(List::size).containsExactly(500, 500, 1);
        assertThat(chunks.get(1).get(0)).isEqualTo(501L);
        verify(writer).succeeded(SlaEvaluator.JOB, NOW, 1001);
    }

    @Test
    void theScheduledTickEvaluatesAtTheApplicationClock() {
        CaseSlaService slas = mock(CaseSlaService.class);
        SlaChunkWriter writer = mock(SlaChunkWriter.class);
        when(slas.dueForEvaluation(NOW)).thenReturn(List.of());

        new SlaEvaluator(slas, writer, InstantSource.fixed(NOW)).tick();

        verify(slas).dueForEvaluation(NOW);
        verify(writer).succeeded(SlaEvaluator.JOB, NOW, 0);
    }
}
