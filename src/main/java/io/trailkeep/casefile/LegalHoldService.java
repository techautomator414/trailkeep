// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Legal hold (BUILD_PLAN §4.6 "Legal hold (who, when, why) stops both clocks", WP-24 part b): a Compliance Admin places
 * one on a case with a reason and lifts it with a reason, each recorded as an append-only {@code LEGAL_HOLD_EVENT}
 * (part b2) in the same transaction, which the purge job keeps. A held case is not archived ({@link
 * CaseRetentionService} skips it) and not purged. Lifting the hold of an archived case keeps it at least the purge
 * grace longer, so a case is never purged the moment its hold ends. Both are audited ({@code LEGAL_HOLD_PLACED},
 * {@code LEGAL_HOLD_LIFTED}) with the reason redacted, as every free-text reason is. Refusals follow ADR-0005:
 * {@code NOT_FOUND} through the person's {@code CaseScope}, {@code FORBIDDEN} without {@code LEGAL_HOLD} (Compliance
 * Admins, any status), then {@code INVALID} for a blank or over-long reason and {@code CONFLICT} when the case is
 * already in the state asked for (someone else got there first).
 */
public interface LegalHoldService {

    /** The longest reason accepted, in characters (the column's width). */
    int MAX_REASON = 500;

    /**
     * A case's legal hold.
     *
     * @param heldBy who placed it
     * @param heldAt when
     * @param reason why
     */
    record LegalHold(String heldBy, Instant heldAt, String reason) {}

    /**
     * One hold placed or lifted, from {@code LEGAL_HOLD_EVENT} (V8).
     *
     * @param placed {@code true} if the hold was placed, {@code false} if it was lifted
     * @param by who
     * @param at when
     * @param reason why
     */
    record HoldChange(boolean placed, String by, Instant at, String reason) {}

    /**
     * The case's holds placed and lifted, newest first, for someone who may place or lift holds; empty for anyone
     * else who reads the case, as is the reason in {@link #current}.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the changes
     */
    List<HoldChange> history(Actor actor, String caseNumber);

    /**
     * The case's hold, read through the actor's scope; empty when the case is not held. Only someone who may place or
     * lift holds is shown the reason; anyone else who reads the case sees an empty reason.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the hold, if any
     */
    Optional<LegalHold> current(Actor actor, String caseNumber);

    /**
     * Places or lifts the case's legal hold.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param hold {@code true} to place a hold, {@code false} to lift it
     * @param reason why, 1 to {@link #MAX_REASON} characters once stripped
     */
    void change(Actor actor, String caseNumber, boolean hold, String reason);
}
