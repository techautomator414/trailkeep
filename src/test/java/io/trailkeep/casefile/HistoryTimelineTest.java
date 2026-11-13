// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.casefile.CaseHistoryService.Entry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@link HistoryTimeline} (WP-26 part x): every page of the merged timeline, found from its offset by the binary search
 * and the two-page merge, equals the same page of the two sources sorted together (newest first, an audit event before
 * an imported one at the same instant, each source's own order kept), including ties, empty sources and pages past the
 * end; and a deep page reads only a logarithmic number of single events.
 */
class HistoryTimelineTest {

    private static final Instant BASE = Instant.parse("2020-01-01T00:00:00Z");

    @Test
    void everyPageEqualsTheSameSliceOfBothSourcesSortedTogether() {
        Random random = new Random(26);
        for (int round = 0; round < 300; round++) {
            List<Entry> audited = source(random, random.nextInt(40), null);
            List<Entry> imported = source(random, random.nextInt(40), 7L);
            List<Entry> sorted = sorted(audited, imported);
            int limit = 1 + random.nextInt(7);
            for (int offset = 0; offset <= sorted.size() + limit; offset++) {
                assertThat(page(audited, imported, offset, limit))
                        .as("round %d offset %d limit %d", round, offset, limit)
                        .isEqualTo(sorted.subList(
                                Math.min(offset, sorted.size()), Math.min(offset + limit, sorted.size())));
            }
        }
    }

    @Test
    void tiesAtTheSameInstantListTheAuditEventFirst() {
        Entry audit = entry(BASE, "audit", null);
        Entry legacy = entry(BASE, "legacy", 7L);

        assertThat(HistoryTimeline.merge(List.of(audit), List.of(legacy), 5)).containsExactly(audit, legacy);
        assertThat(page(List.of(audit), List.of(legacy), 1, 1)).containsExactly(legacy);
    }

    @Test
    void deepPagesReadLogarithmicallyManySingleEvents() {
        Random random = new Random(7);
        List<Entry> audited = source(random, 5000, null);
        List<Entry> imported = source(random, 5000, 7L);
        AtomicInteger reads = new AtomicInteger();

        HistoryTimeline.Start start = HistoryTimeline.start(
                4000,
                audited.size(),
                imported.size(),
                index -> {
                    reads.incrementAndGet();
                    return audited.get(index).at();
                },
                index -> {
                    reads.incrementAndGet();
                    return imported.get(index).at();
                });

        assertThat(start.audited() + start.imported()).isEqualTo(4000);
        assertThat(reads.get())
                .as("two reads per step of a binary search over 4000")
                .isLessThanOrEqualTo(26);
    }

    private static List<Entry> page(List<Entry> audited, List<Entry> imported, int offset, int limit) {
        int total = audited.size() + imported.size();
        if (offset >= total) {
            return HistoryTimeline.merge(List.of(), List.of(), limit);
        }
        HistoryTimeline.Start start = HistoryTimeline.start(
                offset,
                audited.size(),
                imported.size(),
                index -> audited.get(index).at(),
                index -> imported.get(index).at());
        assertThat(start.audited() + start.imported()).isEqualTo(offset);
        return HistoryTimeline.merge(
                audited.subList(start.audited(), Math.min(audited.size(), start.audited() + limit)),
                imported.subList(start.imported(), Math.min(imported.size(), start.imported() + limit)),
                limit);
    }

    /** The reference: both sources in one list, newest first, audit before imported, each source's order kept. */
    private static List<Entry> sorted(List<Entry> audited, List<Entry> imported) {
        List<Entry> all = new ArrayList<>(
                Stream.concat(audited.stream(), imported.stream()).toList());
        Map<Entry, Integer> order = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            order.put(all.get(i), i);
        }
        all.sort(Comparator.comparing(Entry::at)
                .reversed()
                .thenComparing(entry -> entry.importRun() != null)
                .thenComparing(entry -> order.getOrDefault(entry, 0)));
        return all;
    }

    /** Newest first, with times drawn from a few values so that ties within and across sources are common. */
    private static List<Entry> source(Random random, int size, @Nullable Long run) {
        List<Instant> times = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            times.add(BASE.plusSeconds(random.nextInt(25)));
        }
        times.sort(Comparator.reverseOrder());
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            entries.add(entry(times.get(i), (run == null ? "audit " : "legacy ") + i, run));
        }
        return entries;
    }

    private static Entry entry(Instant at, String who, @Nullable Long run) {
        return new Entry(
                at,
                LocalDateTime.of(2020, 1, 1, 0, 0),
                who,
                run == null ? AuditOrigin.UI : AuditOrigin.IMPORT,
                run == null ? "CASE_UPDATED" : "LEGACY_OTHER",
                List.of(),
                run);
    }
}
