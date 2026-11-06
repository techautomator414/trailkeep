// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.user.UserPermissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;

/**
 * Creating, reading and editing cases (BUILD_PLAN §4.2, §4.3). Reads go through the actor's {@code CaseScope}, so a
 * case the actor may not read is "not found", exactly like an absent one (ADR-0005). Every change is audited in the
 * same transaction. Refusals are {@link CaseException}s.
 */
public interface CaseService {

    /**
     * Who is acting.
     *
     * @param permissions the actor's permissions, rebuilt for this request
     * @param displayName the actor's display name, kept in the audit trail
     */
    record Actor(UserPermissions permissions, String displayName) {}

    /**
     * Something the create form offers to pick.
     *
     * @param id the id
     * @param name the name shown
     */
    record Choice(long id, String name) {}

    /**
     * A team the actor may create cases in.
     *
     * @param id the team id
     * @param name the team name
     * @param owners the people who may own its cases, by name
     */
    record TeamChoice(long id, String name, List<Choice> owners) {
        /**
         * Copies the owners.
         *
         * @param id the team id
         * @param name the team name
         * @param owners the people who may own its cases
         */
        public TeamChoice {
            owners = List.copyOf(owners);
        }
    }

    /**
     * What the create form offers.
     *
     * @param caseTypes the active case types, by name
     * @param teams the active teams the actor may create cases in, by name; empty if none
     */
    record CreateChoices(List<Choice> caseTypes, List<TeamChoice> teams) {
        /**
         * Copies the lists.
         *
         * @param caseTypes the active case types
         * @param teams the teams
         */
        public CreateChoices {
            caseTypes = List.copyOf(caseTypes);
            teams = List.copyOf(teams);
        }
    }

    /**
     * The choices for the create form: active case types, and the actor's active teams in which the matrix lets them
     * create cases, each with its eligible owners.
     *
     * @param actor the actor
     * @return the choices
     */
    CreateChoices createChoices(Actor actor);

    /**
     * The intake heuristic for a title that may carry PHI (BUILD_PLAN §18): patterns that look like an SSN, a medical
     * record number or a date of birth. It only warns; the person may confirm and save anyway.
     *
     * @param title the title as entered
     * @return the patterns found; empty if none
     */
    Set<TitleWarning> titleWarnings(String title);

    /**
     * Opens a case: draws its number, initialises its SLA row from the case type's policy and its search row from
     * title and description, and records {@code CASE_CREATED}. {@code FORBIDDEN} unless the actor may create cases in
     * the team; {@code INVALID} for an inactive or unknown case type or team, an owner who holds neither Analyst nor
     * Manager in the team, or an identified-on date after today.
     *
     * @param actor the actor
     * @param fields what the actor entered
     * @return the new case's number
     */
    CaseNumber create(Actor actor, @Valid CaseFields fields);

    /**
     * One case, for its page. {@code NOT_FOUND} if the number is malformed, the case is absent, or the actor may not
     * read it.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the case
     */
    CaseView view(Actor actor, String caseNumber);

    /**
     * One case for a full page view: as {@link #view}, and when the case is {@code RESTRICTED} it writes a {@code
     * VIEW_RESTRICTED} audit event for the actor in the same transaction (BUILD_PLAN §3.3). This is the one read path
     * that is not read-only. A page that refuses with {@code NOT_FOUND} records nothing.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the case
     */
    CaseView open(Actor actor, String caseNumber);

    /**
     * Records that the actor started a download of an attachment of the case: a {@code VIEW_RESTRICTED} event naming
     * the attachment if the case is {@code RESTRICTED}, nothing otherwise. Called once the download is allowed.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param attachmentId the attachment
     */
    void recordDownload(Actor actor, String caseNumber, long attachmentId);

    /**
     * An open case for its edit form. {@code NOT_FOUND} as for {@link #view}; {@code FORBIDDEN} if the actor may
     * read but not edit it (a Reader, or a case that is no longer open).
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the case's editable fields and its last change
     */
    EditableCase editable(Actor actor, String caseNumber);

    /**
     * The id of a case the actor may attach a file to, for the {@code document} module: {@code NOT_FOUND} as for
     * {@link #view}; {@code FORBIDDEN} if the matrix denies {@code COMMENT}, because "add comment / attachment" is one
     * row of BUILD_PLAN §3.2. It runs in the caller's transaction if there is one, so a store re-checks the case as it
     * stands then.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the case's id
     */
    long attachableCaseId(Actor actor, String caseNumber);

    /**
     * The id of a case the actor may download attachments of, for the {@code document} module: {@code NOT_FOUND} as
     * for {@link #view}; {@code FORBIDDEN} if the matrix denies {@code DOWNLOAD_ATTACHMENT}.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the case's id
     */
    long downloadableCaseId(Actor actor, String caseNumber);

    /**
     * The id of a case on which the actor may release a quarantined attachment, for the {@code document} module:
     * {@code NOT_FOUND} as for {@link #view}; {@code FORBIDDEN} if the matrix denies {@code RELEASE_QUARANTINE}. It
     * runs in the caller's transaction if there is one.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the case's id
     */
    long releasableCaseId(Actor actor, String caseNumber);

    /**
     * Saves an edit made against {@code version} and records {@code CASE_UPDATED} with the changed fields (subject
     * and description redacted), rebuilding the search text from title, description and every comment under its row
     * lock. Saving unchanged values writes nothing. {@code NOT_FOUND} and {@code FORBIDDEN} as for {@link #editable};
     * {@code CONFLICT} if someone saved since {@code version} was read, which the edit form answers with Reload, never
     * with an overwrite.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param version the version the form was loaded with
     * @param changes what the actor entered
     */
    void edit(Actor actor, String caseNumber, int version, @Valid CaseChanges changes);
}
