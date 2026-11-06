// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Closure module. Closure requests and their approval, rejection and reopening.
 *
 * <p>May call: {@code shared}, {@code audit}, {@code user}, {@code security} (the approver rules), {@code casefile}
 * (the case's status through {@code CaseStatusService}), {@code notification} (the approver's mail and alert, in the
 * same transaction). Other modules use only the {@code *Service} interfaces and records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "Closure")
package io.trailkeep.closure;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
