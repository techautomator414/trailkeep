// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Import staging (WP-26 part j, docs/design/importer.md §5): the canonical legacy rows between the extract and the
 * load, and the per-entity checkpoints. Only the {@code importer} profile maps these entities
 * ({@code io.trailkeep.importer.StagingEntityFilter}) and only the import user may read or write their tables
 * ({@code R__grants.sql}), because staging holds legacy values, restricted descriptions included, outside
 * {@code CaseScope}. Internal to the {@code importer} module.
 */
@NullMarked
package io.trailkeep.importer.staging;

import org.jspecify.annotations.NullMarked;
