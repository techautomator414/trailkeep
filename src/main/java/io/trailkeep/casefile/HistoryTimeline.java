// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseHistoryService.Entry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Pages a case's audit events and its imported legacy history as one timeline, newest first (WP-26 part x, design
 * §7.5), without reading either source whole. The order is by time, newest first; at the same instant an audit event
 * comes before an imported one; within a source, the source's own order stands. A page at an offset starts, in each
 * source, at the number of that source's events among the merged timeline's first {@code offset}: {@link #start} finds
 * it by a binary search that reads one event of each source per step, so a deep page costs a few single-row reads,
 * never the events before it.
 */
final class HistoryTimeline {

    private HistoryTimeline() {}

    /**
     * Where a page of the merged timeline starts in each source.
     *
     * @param audited the offset into the audit events
     * @param imported the offset into the imported events
     */
    record Start(int audited, int imported) {}

    /**
     * Where the page at {@code offset} starts in each source.
     *
     * @param offset the merged offset, not past the two totals together
     * @param audited how many audit events there are
     * @param imported how many imported events there are
     * @param auditedAt the time of the audit event at an index, newest first
     * @param importedAt the time of the imported event at an index, newest first
     * @return the two offsets, which add up to {@code offset}
     */
    static Start start(
            int offset, long audited, long imported, IntFunction<Instant> auditedAt, IntFunction<Instant> importedAt) {
        int low = (int) Math.max(0, offset - imported);
        int high = (int) Math.min(offset, audited);
        // The smallest split whose last imported event is newer than its next audit event; the highest split always is.
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (importedAt.apply(offset - middle - 1).isAfter(auditedAt.apply(middle))) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return new Start(low, offset - low);
    }

    /**
     * The first {@code limit} entries of two newest-first lists merged in the timeline's order.
     *
     * @param audited audit entries, newest first
     * @param imported imported entries, newest first
     * @param limit how many to keep
     * @return the merged entries
     */
    static List<Entry> merge(List<Entry> audited, List<Entry> imported, int limit) {
        List<Entry> merged = new ArrayList<>(Math.min(limit, audited.size() + imported.size()));
        int a = 0;
        int i = 0;
        while (merged.size() < limit && (a < audited.size() || i < imported.size())) {
            boolean importedNext = a == audited.size()
                    || (i < imported.size()
                            && imported.get(i).at().isAfter(audited.get(a).at()));
            merged.add(importedNext ? imported.get(i++) : audited.get(a++));
        }
        return merged;
    }
}
