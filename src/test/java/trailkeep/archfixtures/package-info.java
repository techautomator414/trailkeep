// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Planted violations and compliant counterparts for {@code ArchitectureRulesTest}. Test-only, never component-scanned
 * (no stereotypes), and never part of the production evaluation. The package sits outside {@code io.trailkeep} so
 * that Hibernate entity scanning and Spring Data repository scanning, which start from the application package, never
 * pick up these planted entities and repositories in a {@code @SpringBootTest}. Classes named {@code Compliant*} or
 * {@code Good*} must pass every rule; every other class plants exactly the violation its name describes.
 */
@NullMarked
package trailkeep.archfixtures;

import org.jspecify.annotations.NullMarked;
