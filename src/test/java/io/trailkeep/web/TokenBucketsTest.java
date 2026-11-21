// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The per-person token bucket on a controlled monotonic clock (#247). */
class TokenBucketsTest {

    private static final long SECOND = Duration.ofSeconds(1).toNanos();

    /** Starts away from zero and near the top of the range, so the clock wraps during a test. */
    private final AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 30 * SECOND);

    private TokenBuckets buckets(int capacity, int refill, Duration window) {
        return new TokenBuckets(new RateLimitProperties.Limit(capacity, refill, window), clock::get);
    }

    @Test
    void aFullBucketGivesItsCapacityAtOnceThenRefusesWithTheWaitForTheNextToken() {
        TokenBuckets buckets = buckets(3, 6, Duration.ofMinutes(1)); // a token every 10 s

        assertThat(buckets.take(1)).isZero();
        assertThat(buckets.take(1)).isZero();
        assertThat(buckets.take(1)).isZero();
        assertThat(buckets.take(1)).isEqualTo(10 * SECOND);

        clock.addAndGet(4 * SECOND);
        assertThat(buckets.take(1)).as("a refusal takes nothing").isEqualTo(6 * SECOND);
        clock.addAndGet(6 * SECOND);
        assertThat(buckets.take(1)).isZero();
        assertThat(buckets.take(1)).isEqualTo(10 * SECOND);
    }

    @Test
    void eachPersonHasTheirOwnBucket() {
        TokenBuckets buckets = buckets(1, 1, Duration.ofMinutes(1));

        assertThat(buckets.take(1)).isZero();
        assertThat(buckets.take(1)).isPositive();
        assertThat(buckets.take(2)).isZero();
    }

    @Test
    void aQuietSpellRefillsToCapacityAndNoFurther() {
        TokenBuckets buckets = buckets(2, 2, Duration.ofSeconds(20)); // a token every 10 s

        assertThat(buckets.take(1)).isZero();
        assertThat(buckets.take(1)).isZero();
        clock.addAndGet(Duration.ofHours(1).toNanos());

        assertThat(buckets.take(1)).isZero();
        assertThat(buckets.take(1)).isZero();
        assertThat(buckets.take(1)).isEqualTo(10 * SECOND);
    }

    @Test
    void bucketsThatAreFullAgainAreDroppedAtTheNextSweep() {
        TokenBuckets buckets = buckets(2, 2, Duration.ofSeconds(10));
        buckets.take(1);
        buckets.take(2);
        buckets.take(2);
        assertThat(buckets.size()).isEqualTo(2);

        clock.addAndGet(Duration.ofMinutes(1).toNanos());
        buckets.take(3);

        assertThat(buckets.size())
                .as("1 and 2 are full again and dropped; 3 is new")
                .isEqualTo(1);
        assertThat(buckets.take(2)).as("a dropped bucket comes back full").isZero();
        assertThat(buckets.take(2)).isZero();
        assertThat(buckets.take(2)).isPositive();
    }

    @Test
    void theLargestAllowedSettingsDoNotOverflow() {
        TokenBuckets buckets = buckets(100_000, 1, Duration.ofDays(1));

        assertThat(buckets.take(1)).isZero();
    }
}
