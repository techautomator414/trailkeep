// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseService.TeamChoice;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import java.util.List;

/**
 * Reassigning a case and changing its sensitivity (BUILD_PLAN §3.2 edge rules, §4.3 "More"). Every method refuses in
 * the same order (ADR-0005): {@code NOT_FOUND} for a case the actor may not read, {@code FORBIDDEN} for an action the
 * matrix denies, then {@code INVALID} for input the form could not have offered, then {@code CONFLICT} for a version
 * someone has saved past. Every change is audited in the same transaction, with the changed components only.
 */
public interface CaseAssignmentService {

    /**
     * A case as the Reassign form shows it.
     *
     * @param number the case number
     * @param title the title
     * @param version the version the form saves against
     * @param teamId the current team
     * @param ownerId the current owner
     * @param teams the teams it may move to, the current one included, each with the people who may own its cases
     */
    record Reassignment(
            CaseNumber number, String title, int version, long teamId, long ownerId, List<TeamChoice> teams) {
        /**
         * Copies the teams.
         *
         * @param number the case number
         * @param title the title
         * @param version the version
         * @param teamId the current team
         * @param ownerId the current owner
         * @param teams the teams
         */
        public Reassignment {
            teams = List.copyOf(teams);
        }
    }

    /**
     * A case as the Mark restricted and Mark standard confirmations show it.
     *
     * @param number the case number
     * @param title the title
     * @param version the version the confirmation saves against
     */
    record Confirmation(CaseNumber number, String title, int version) {}

    /**
     * The Reassign form. {@code FORBIDDEN} while a closure request is pending, for a Reader, and for an Analyst who
     * does not own the case.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the case and the choices
     */
    Reassignment reassignment(Actor actor, String caseNumber);

    /**
     * Moves the case to an owner and a team and records {@code CASE_REASSIGNED}; an unchanged choice writes nothing.
     * {@code INVALID} if the team changes to one the actor may not create cases in or that is inactive, or if the
     * owner holds neither Analyst nor Manager in the team.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param version the version the form was loaded with
     * @param teamId the new team
     * @param ownerId the new owner
     * @return whether the actor can still read the case (a restricted case reassigned away from an Analyst cannot)
     */
    boolean reassign(Actor actor, String caseNumber, long version, long teamId, long ownerId);

    /**
     * The confirmation for marking a case restricted or standard. {@code FORBIDDEN} unless the matrix allows {@code
     * RESTRICT} (for a target of {@code RESTRICTED}) or {@code UNRESTRICT}; an Analyst may only restrict, their own
     * case.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param target the sensitivity asked for
     * @return the case
     */
    Confirmation sensitivityChange(Actor actor, String caseNumber, Sensitivity target);

    /**
     * Marks the case restricted or standard and records {@code CASE_RESTRICTED} or {@code CASE_UNRESTRICTED}. The
     * actor must still read the case afterwards, or nothing changes and the answer is {@code FORBIDDEN}.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param version the version the confirmation was loaded with
     * @param target the new sensitivity
     */
    void changeSensitivity(Actor actor, String caseNumber, long version, Sensitivity target);
}
