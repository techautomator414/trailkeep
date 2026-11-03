// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Planted violations and compliant counterparts for {@code ArchitectureRulesTest}. Test-only, never component-scanned
 * (no stereotypes), and excluded from the positive rule evaluation by package. Classes named {@code Compliant*} or
 * {@code Good*} must pass every rule; every other class plants exactly the violation its name describes.
 */
@NullMarked
package io.trailkeep.architecture.fixtures;

import org.jspecify.annotations.NullMarked;
