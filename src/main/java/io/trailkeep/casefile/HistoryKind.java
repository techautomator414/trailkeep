// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.util.Set;

/**
 * What a case's History page can be filtered to (BUILD_PLAN §4.3): each kind is a set of audit actions. {@link #ALL}
 * keeps every action, including any a later work package adds, and the case's imported legacy history (WP-26 part x,
 * design §7.5); {@link #NATIVE} leaves the imported history out and {@link #IMPORTED} shows only it. The other kinds
 * are sets of audit actions, so they show no imported history. The email kind arrives with the outbox (WP-17).
 */
public enum HistoryKind {
    /** Every event, imported legacy history included. */
    ALL,
    /** Every audit event, without the imported legacy history. */
    NATIVE,
    /** Only the imported legacy history. */
    IMPORTED,
    /** Changes to the case's fields: creation, edits and reassignment. */
    FIELDS,
    /** Comments added and pinned. */
    COMMENTS,
    /** Attachments added and released from quarantine. */
    ATTACHMENTS,
    /** State changes: marked restricted or standard, the SLA evaluator's transitions (WP-21), archiving and legal holds
     * (WP-24).
     */
    STATE,
    /** Reads of a restricted case. */
    VIEWS;

    /**
     * The audit actions of this kind.
     *
     * @return the actions; empty for {@link #ALL}, {@link #NATIVE} and {@link #IMPORTED}
     */
    public Set<String> actions() {
        return switch (this) {
            case ALL, NATIVE, IMPORTED -> Set.of();
            case FIELDS -> Set.of("CASE_CREATED", "CASE_IMPORTED", "CASE_UPDATED", "CASE_REASSIGNED");
            case COMMENTS -> Set.of("COMMENT_ADDED", "COMMENT_IMPORTED", "COMMENT_PINNED", "COMMENT_UNPINNED");
            case ATTACHMENTS -> Set.of("ATTACHMENT_ADDED", "ATTACHMENT_RELEASED");
            case STATE ->
                Set.of(
                        "CASE_RESTRICTED",
                        "CASE_UNRESTRICTED",
                        "SLA_STATE_CHANGED",
                        "CASE_ARCHIVED",
                        "LEGAL_HOLD_PLACED",
                        "LEGAL_HOLD_LIFTED",
                        "CASE_PURGED");
            case VIEWS -> Set.of(RestrictedReadAudit.ACTION);
        };
    }

    /**
     * Whether this kind shows audit events.
     *
     * @return false only for {@link #IMPORTED}
     */
    public boolean audited() {
        return this != IMPORTED;
    }

    /**
     * Whether this kind shows the imported legacy history.
     *
     * @return true for {@link #ALL} and {@link #IMPORTED}
     */
    public boolean imported() {
        return this == ALL || this == IMPORTED;
    }
}
