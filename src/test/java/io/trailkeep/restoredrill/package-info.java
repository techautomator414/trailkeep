// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * The restore drill (WP-30 part c, {@code docs/ops/restore-drill.md}), never in the PR build: the 500k seed and 2 000
 * attachments are backed up with the vendor's own tool, the database is destroyed, a new one is restored from the
 * dump, and the restored copy is proven complete and usable. Tagged {@code restore-drill} on PostgreSQL and {@code
 * restore-drill-oracle} on Oracle; each tag runs only in the {@code perf-it} Maven profile's Failsafe execution of the
 * same name, by hand.
 */
@NullMarked
package io.trailkeep.restoredrill;

import org.jspecify.annotations.NullMarked;
