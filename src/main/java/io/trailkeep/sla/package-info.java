// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * SLA module. SLA policies, the SLA evaluator and SLA state transitions, driven by case and closure events.
 *
 * <p>May call: {@code shared}, {@code audit}, {@code casefile}, {@code notification} (the SLA alerts) and
 * {@code user} (who they go to). Other modules use only the {@code *Service} interfaces
 * and records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "SLA")
package io.trailkeep.sla;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
