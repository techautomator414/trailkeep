// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.shared.access.CaseRooted;
import org.springframework.data.repository.Repository;

/**
 * Read-only, case-rooted access to {@code CASE_IMPORTED_EVENT} (WP-26 part x, docs/design/importer.md §7.5, ADR-0005):
 * no inherited or derived methods, only the scoped queries of {@link CaseImportedEventQueries}. Writes go through
 * {@link DefaultImportedHistoryService}, which persists directly.
 */
@CaseRooted
interface CaseImportedEventRepository extends Repository<CaseImportedEvent, Long>, CaseImportedEventQueries {}
