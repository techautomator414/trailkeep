// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.trailkeep.notification.OutboxJdbcDao.Outcome;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The outbox gauges query at most once a minute however often they are read, and the attempt counter is tagged by
 * outcome.
 */
class OutboxMetricsTest {

    private final OutboxJdbcDao outbox = mock(OutboxJdbcDao.class);
    private static final Instant START = Instant.parse("2026-11-06T09:00:00Z");

    private final AtomicLong elapsedSeconds = new AtomicLong();
    private final InstantSource clock = () -> START.plusSeconds(elapsedSeconds.get());
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxMetrics metrics = new OutboxMetrics(registry, outbox, clock);

    @Test
    void gaugesAreCountQueriesCachedForOneMinute() {
        when(outbox.count(OutboxStatus.PENDING)).thenReturn(3L, 5L);
        when(outbox.count(OutboxStatus.FAILED)).thenReturn(1L);

        assertThat(gauge("trailkeep.outbox.pending")).isEqualTo(3.0);
        elapsedSeconds.set(59);
        assertThat(gauge("trailkeep.outbox.pending")).isEqualTo(3.0);
        verify(outbox, times(1)).count(OutboxStatus.PENDING);

        elapsedSeconds.set(60);
        assertThat(gauge("trailkeep.outbox.pending")).isEqualTo(5.0);
        assertThat(gauge("trailkeep.outbox.failed")).isEqualTo(1.0);
        verify(outbox, times(2)).count(OutboxStatus.PENDING);
    }

    @Test
    void attemptsAreCountedByOutcome() {
        metrics.attempted(Outcome.SENT);
        metrics.attempted(Outcome.SENT);
        metrics.attempted(Outcome.RETRY);
        metrics.attempted(Outcome.FAILED);

        assertThat(attempts("sent")).isEqualTo(2.0);
        assertThat(attempts("retry")).isEqualTo(1.0);
        assertThat(attempts("failed")).isEqualTo(1.0);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double attempts(String outcome) {
        return registry.get("trailkeep.outbox.attempts")
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
