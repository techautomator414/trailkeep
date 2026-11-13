// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import org.springframework.data.repository.Repository;

/**
 * The open cases waiting for their legacy owner (design §7.3): written by the load, read and deleted by {@code
 * relink-owners}. Not case-rooted: it holds ids and keyed hashes, no case content.
 */
interface ImportOwnerPendingRepository extends Repository<ImportOwnerPending, Long> {

    ImportOwnerPending save(ImportOwnerPending pending);
}
