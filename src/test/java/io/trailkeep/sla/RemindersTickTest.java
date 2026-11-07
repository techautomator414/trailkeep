// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.trailkeep.sla.ReminderWriter.Page;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import org.junit.jupiter.api.Test;

/** The tick's paging: each pass reads pages until one is short, from the last id read; then {@code JOB_RUN}. */
class RemindersTickTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:07:00Z");

    private final ReminderWriter writer = mock(ReminderWriter.class);
    private final JobRuns runs = mock(JobRuns.class);
    private final RemindersTick tick = new RemindersTick(writer, runs, InstantSource.fixed(NOW), 3, 5);

    @Test
    void eachPassPagesUntilItsPageIsShortThenTheTickRecordsItsSuccess() {
        Instant updates = NOW.minus(Duration.ofDays(3));
        when(writer.updateRequests(updates, 0, NOW)).thenReturn(new Page(500, SlaEvaluator.CHUNK, 4));
        when(writer.updateRequests(updates, 500, NOW)).thenReturn(new Page(620, 120, 1));
        when(writer.closureRequests(eq(NOW.minus(Duration.ofDays(5))), anyLong(), any()))
                .thenReturn(new Page(0, 0, 0));
        when(writer.overdueCases(0, NOW)).thenReturn(new Page(9, 1, 3));

        tick.tick();

        verify(writer).updateRequests(updates, 500, NOW);
        verify(runs).succeeded(RemindersTick.JOB, NOW, 8);
        assertThat(tick.run(NOW)).isEqualTo(8);
    }
}
