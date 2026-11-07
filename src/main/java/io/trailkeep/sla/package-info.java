// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * SLA module. The SLA evaluator and its alerts, and the reminders tick (WP-21, WP-17b).
 *
 * <p>May call: {@code shared}, {@code audit}, {@code casefile}, {@code notification} (the SLA alerts and reminders),
 * {@code user} (who they go to), and {@code updaterequest} and {@code closure} (what the reminders tick reminds of).
 * Neither of those two calls {@code sla}, so the reminders tick lives here without a cycle. Other modules use only
 * the {@code *Service} interfaces and records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "SLA")
package io.trailkeep.sla;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
