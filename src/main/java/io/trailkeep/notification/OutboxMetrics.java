// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.trailkeep.notification.OutboxJdbcDao.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The outbox on the metrics endpoint. Gauges {@code trailkeep.outbox.pending} and {@code trailkeep.outbox.failed} are
 * count queries on the status index, read at most once a minute per replica however often they are scraped; a growing
 * pending count is how an unreachable mail server shows (its health is not a probe, Part A #30). Counter {@code
 * trailkeep.outbox.attempts}, tagged {@code outcome} = {@code sent}, {@code retry} or {@code failed}, counts this
 * replica's attempts.
 */
@Component
final class OutboxMetrics {

    private static final Duration FRESH = Duration.ofMinutes(1);

    private final OutboxJdbcDao outbox;
    private final InstantSource clock;
    private final CachedCount pending = new CachedCount(OutboxStatus.PENDING);
    private final CachedCount failed = new CachedCount(OutboxStatus.FAILED);
    private final Map<Outcome, Counter> attempts = new EnumMap<>(Outcome.class);

    OutboxMetrics(MeterRegistry registry, OutboxJdbcDao outbox, InstantSource clock) {
        this.outbox = outbox;
        this.clock = clock;
        Gauge.builder("trailkeep.outbox.pending", pending::get)
                .description("Mails waiting to be sent, including those waiting for a retry")
                .register(registry);
        Gauge.builder("trailkeep.outbox.failed", failed::get)
                .description("Mails that failed for the last time")
                .register(registry);
        for (Outcome outcome : Outcome.values()) {
            attempts.put(
                    outcome,
                    Counter.builder("trailkeep.outbox.attempts")
                            .description("Send attempts by this replica")
                            .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                            .register(registry));
        }
    }

    /**
     * Counts one attempt.
     *
     * @param outcome what it ended in
     */
    void attempted(Outcome outcome) {
        Objects.requireNonNull(attempts.get(outcome)).increment();
    }

    /** One status's count, queried again once it is a minute old. */
    private final class CachedCount {

        private final OutboxStatus status;
        private long value;
        private @Nullable Instant readAt;

        CachedCount(OutboxStatus status) {
            this.status = status;
        }

        synchronized double get() {
            Instant now = clock.instant();
            if (readAt == null || !now.isBefore(readAt.plus(FRESH))) {
                value = outbox.count(status);
                readAt = now;
            }
            return value;
        }
    }
}
