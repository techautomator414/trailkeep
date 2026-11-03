// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Reports module. Aging, SLA compliance and workload reports as accessible HTML and CSV, report schedules
 * and report runs.
 *
 * <p>May call: {@code shared}, {@code audit}, {@code user}, {@code casefile}, {@code sla},
 * {@code notification}. Other modules use only the {@code *Service} interfaces and records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "Reports")
package io.trailkeep.report;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
