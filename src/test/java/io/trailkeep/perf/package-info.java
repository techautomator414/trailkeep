// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Performance measurements on synthetic seeds, never in the PR build: the 50 000-case seed (WP-15 part 5), tagged
 * {@code perf}, and the same cases with 500 000 comments (WP-30), tagged {@code perf-500k} on PostgreSQL and {@code
 * perf-500k-oracle} on Oracle. Each tag runs only in the {@code perf-it} Maven profile's Failsafe execution of the same
 * name ({@code perf-50k} for {@code perf}); the PostgreSQL ones every night, the Oracle one by hand. The role-conflict
 * runs ({@code perf-role-conflict}) are opt-in too.
 */
@NullMarked
package io.trailkeep.perf;

import org.jspecify.annotations.NullMarked;
