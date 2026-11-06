// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;
import java.util.List;

/**
 * Saved views of the cases list (BUILD_PLAN §4.4 "Save View"). A view is private to the person who saved it: nobody
 * else can list, apply or delete it, and another person's view answers "not found", never "forbidden", so its
 * existence stays private. A view stores criteria only; applying it is a link to the list, read through the person's
 * scope like any URL, so it can never widen what they read. Saving and deleting are audited, by name only.
 */
public interface SavedViewService {

    /** The longest view name, in characters, after stripping. */
    int MAX_NAME = 100;

    /** The longest stored query string, in characters. */
    int MAX_QUERY = 1000;

    /**
     * One saved view.
     *
     * @param id its id
     * @param name its name
     * @param query the cases-list query string it applies, without the {@code ?}; never empty
     */
    record View(long id, String name, String query) {}

    /** Why a view was not saved; each is a 422 with its own message. */
    enum Problem {
        /** The name is blank. */
        NAME_BLANK,
        /** The name is longer than {@link #MAX_NAME}. */
        NAME_TOO_LONG,
        /** The person already has a view of that name. */
        NAME_TAKEN,
        /** The query is the plain list: no criterion and the default order, so there is nothing to save. */
        NOTHING_TO_SAVE,
        /** The query string is longer than {@link #MAX_QUERY}. */
        QUERY_TOO_LONG
    }

    /** What saving a view did. */
    sealed interface Saved {}

    /**
     * The view was saved.
     *
     * @param view the view
     */
    record Created(View view) implements Saved {}

    /**
     * The view was not saved.
     *
     * @param problem why
     */
    record Refused(Problem problem) implements Saved {}

    /**
     * Saves the filter and order of a query, on page 1, under a name.
     *
     * @param actor who saves it, and owns it
     * @param name the name; stripped before it is checked and stored
     * @param query the query whose filter and order to save
     * @return the saved view, or why it was refused
     */
    Saved save(Actor actor, String name, CaseListQuery query);

    /**
     * A person's views, by name.
     *
     * @param userId the person
     * @return their views, ordered by name
     */
    List<View> views(long userId);

    /**
     * Deletes one of the actor's views.
     *
     * @param actor who deletes it
     * @param id the view
     * @throws CaseException {@code NOT_FOUND} when the view does not exist or belongs to someone else
     */
    void delete(Actor actor, long id);
}
