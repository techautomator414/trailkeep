// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

/** What initiated an audited change; stored as text ({@code CK_AUDIT_ORIGIN}). */
public enum AuditOrigin {
    /** A person acting through the web UI. */
    UI,
    /** A scheduled job, such as the SLA evaluator or the retention job. */
    SCHEDULER,
    /** The importer. */
    IMPORT,
    /** The application itself, for example at start-up or on an identity-provider claim. */
    SYSTEM
}
