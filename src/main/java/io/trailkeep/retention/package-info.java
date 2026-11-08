// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Retention module. The archive job (WP-24 part a), legal holds and the purge job. The cases' own retention state is
 * {@code casefile}'s, reached through {@link io.trailkeep.casefile.CaseRetentionService}.
 *
 * <p>May call: {@code shared}, {@code audit} and the exported services of the case-rooted modules
 * ({@code casefile}, {@code closure}, {@code updaterequest}, {@code sla}, {@code document}, {@code notification}).
 * Other modules use only the {@code *Service} interfaces and records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "Retention")
package io.trailkeep.retention;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
