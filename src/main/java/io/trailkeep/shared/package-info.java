// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Shared module: identifiers and value types such as {@link io.trailkeep.shared.CaseNumber}, cross-cutting
 * exceptions, the case access contract in {@link io.trailkeep.shared.access}, and cross-cutting infrastructure every
 * module may use (time source, persistence and application configuration). Contains no domain logic.
 *
 * <p>Declared {@link ApplicationModule.Type#OPEN open}: every module may depend on any type here, and
 * {@code shared} depends on no other module.
 */
@NullMarked
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
package io.trailkeep.shared;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
