// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Importer module. The generic CSV and mapping-file importer for records from an existing tracker, with
 * reconciliation reporting.
 *
 * <p>May call: {@code shared}, {@code audit}, {@code user}, {@code casefile}, {@code closure}, {@code sla}. Other
 * modules use only the
 * {@code *Service} interfaces and records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "Importer")
package io.trailkeep.importer;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
