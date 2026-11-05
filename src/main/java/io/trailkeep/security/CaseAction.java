// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

/** An action on a case, one column of the permission matrix (BUILD_PLAN §3.2; {@code permissions/matrix.csv}). */
public enum CaseAction {
    /** Create a case in a team; decided for the team, not for a case. */
    CREATE,
    /** Open the case page. */
    VIEW,
    /** Download a (clean or released) attachment. */
    DOWNLOAD_ATTACHMENT,
    /** Edit case fields. */
    EDIT,
    /** Add a comment or an attachment. */
    COMMENT,
    /** Pin a comment or unpin it again (BUILD_PLAN §4.3); one action, as the plan gives both the same roles. */
    PIN,
    /** Mark the case restricted. */
    RESTRICT,
    /** Mark the case standard again. */
    UNRESTRICT,
    /** Ask a user for an update by email. */
    REQUEST_UPDATE,
    /** Request closure. */
    REQUEST_CLOSURE,
    /** Approve or reject a pending closure request (the requester is excluded by the closure service). */
    DECIDE_CLOSURE,
    /** Reassign the approver of a pending closure request. */
    REASSIGN_APPROVER,
    /** Reopen a closed case. */
    REOPEN,
    /** Reassign the owner or the team. */
    REASSIGN,
    /** Release a quarantined attachment. */
    RELEASE_QUARANTINE
}
