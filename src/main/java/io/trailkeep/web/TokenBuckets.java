// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * One token bucket per person for one route, in this process's memory (#247). Each bucket is held as a single number,
 * the instant at which it will be full again (the generic cell rate algorithm, which behaves exactly like a token
 * bucket that refills one token every {@code window / refill}). Time is the monotonic clock, so a wall-clock change
 * neither empties nor fills a bucket.
 *
 * <p>Memory is bounded by the people active within one full refill. A bucket that is full again carries no state, so
 * at most once a minute the entries whose instant has passed are dropped; a person who comes back gets a fresh, full
 * bucket, which is what the dropped entry would have said.
 */
final class TokenBuckets {

    private static final long SWEEP_NANOS = Duration.ofMinutes(1).toNanos();

    /** Nanoseconds for one token to come back. */
    private final long interval;

    /** Nanoseconds for an empty bucket to fill. */
    private final long burst;

    private final LongSupplier nanoTime;
    private final ConcurrentMap<Long, Long> fullAt = new ConcurrentHashMap<>();
    private final AtomicLong nextSweep;

    TokenBuckets(RateLimitProperties.Limit limit, LongSupplier nanoTime) {
        this.interval = limit.window().toNanos() / limit.refill();
        // RateLimitProperties bounds capacity, refill and window so this cannot overflow; fail loudly if it ever does.
        this.burst = Math.multiplyExact(interval, (long) limit.capacity());
        this.nanoTime = nanoTime;
        this.nextSweep = new AtomicLong(nanoTime.getAsLong() + SWEEP_NANOS);
    }

    /**
     * Takes a token from the person's bucket, if there is one.
     *
     * @param person the {@code APP_USER.ID} of the person asking
     * @return 0 if a token was taken, otherwise how many nanoseconds until one is back (nothing is taken)
     */
    long take(long person) {
        long now = nanoTime.getAsLong();
        sweep(now);
        long[] wait = {0};
        fullAt.compute(person, (key, full) -> {
            long from = full == null || full - now < 0 ? now : full;
            long next = from + interval;
            long over = next - now - burst;
            if (over > 0) {
                wait[0] = over;
                return full;
            }
            return next;
        });
        return wait[0];
    }

    /**
     * How many people have a bucket that is not full.
     *
     * @return the number of entries held
     */
    int size() {
        return fullAt.size();
    }

    private void sweep(long now) {
        long due = nextSweep.get();
        if (now - due >= 0 && nextSweep.compareAndSet(due, now + SWEEP_NANOS)) {
            // A conditional removal per entry: one that a concurrent take() has just moved on is kept.
            fullAt.values().removeIf(full -> full - now <= 0);
        }
    }
}
