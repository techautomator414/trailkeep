// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Case file module. The case aggregate: {@code CaseRecord}, case numbering, comments, create, edit and
 * search, and the principal-backed {@code CaseScope} implementation (WP-11). Also owns case configuration
 * ({@code V3__admin}: SLA policies, case types and their outcomes) through {@link
 * io.trailkeep.casefile.CaseConfigService}, because case creation needs it and {@code casefile} may not call
 * {@code sla}; {@code sla} reads it from here.
 *
 * <p>May call: {@code shared}, {@code audit}, {@code user}, {@code security}. Other modules use only the {@code
 * *Service} interfaces and records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "Case file")
package io.trailkeep.casefile;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
