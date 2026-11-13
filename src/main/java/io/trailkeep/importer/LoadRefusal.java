// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

/**
 * Why {@code load}, {@code relink-owners} or {@code reconcile} refused to start, before it wrote anything (WP-26 parts
 * t, u, z2 and aa2, design §3, §8.2).
 */
enum LoadRefusal {
    /** No Compliance Admin has signed the run off. */
    NOT_SIGNED_OFF,
    /** Not an import run with its staging, or past its load. */
    NOT_LOADABLE,
    /** Its dry run found a FATAL issue. */
    FATAL_ISSUES,
    /** The mapping file or a lookup file is not the one it was validated and dry-run with. */
    MAPPING_CHANGED,
    /** The attachment scanning mode in force is not the one its dry run recorded and its sign-off approved (R1). */
    SCAN_MODE_CHANGED,
    /** Another load holds it, or claimed it first. */
    RUN_LOCKED,
    /** A live load holds another run of its source (design §8.2). */
    SOURCE_LOADING,
    /** For {@code reconcile} (WP-26 part aa2): not an import run whose load finished. */
    NOT_LOADED;

    /** {@code load} refused to start. */
    static final class RefusedException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final LoadRefusal refusal;

        RefusedException(long runId, LoadRefusal refusal) {
            super("Import run " + runId + " may not be loaded: " + refusal.name());
            this.refusal = refusal;
        }

        LoadRefusal refusal() {
            return refusal;
        }
    }
}
