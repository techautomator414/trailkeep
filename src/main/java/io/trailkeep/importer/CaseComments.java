// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedComment;
import io.trailkeep.user.ImportPeopleDirectory.PersonMatch;
import io.trailkeep.user.UserDirectoryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The text a case's load writes besides its fields (WP-26 part u, design §6.2, §6.4, §7.2). The description is
 * formatted as a description holds it ({@link LegacyText#forDescription}) and cut to the 20 000 characters a case
 * takes; under {@code onOverflow: continueInComment} the rest continues in the case's first comment, Markdown-escaped
 * because from then on it is a comment, written at the case's opening time by the "Trailkeep import" principal; without
 * it, a longer description is {@code DESCRIPTION_TOO_LONG} ({@link CaseRules}). Each staged comment is formatted as
 * its mapping declares ({@link LegacyText#forComment}), and one longer than a comment takes is split into numbered
 * parts, each its own comment, which the dry run reports as {@code COMMENT_SPLIT} ({@link ChildRules}). A matched
 * author is named as Trailkeep knows them; an unmatched one by the name the comment carries, else the person's staged
 * name.
 */
final class CaseComments {

    /** {@code CaseFields}' description cap and {@code CaseComment.MAX_BODY}, in characters. */
    static final int MAX_TEXT = 20_000;

    /** Room each part of a split comment keeps for its number, such as "(12/34)" and a line feed. */
    private static final int NUMBER_ROOM = 24;

    /** The name of an unmatched author whom neither the comment nor the staged person names. */
    static final String UNNAMED_AUTHOR = "Unnamed legacy author";

    /**
     * Who a comment is by, as the comment import API takes it.
     *
     * @param id the matched user, or {@code null}
     * @param name the name snapshot
     */
    record Author(@Nullable Long id, String name) {

        /** The "Trailkeep import" principal, named as the directory knows it. */
        static Author importer(long id, UserDirectoryService directory) {
            return new Author(id, directory.displayName(id).orElse("Trailkeep import"));
        }
    }

    /**
     * One comment the load writes.
     *
     * @param staged the staged comment, or {@code null} for a description's continuation
     * @param parts its body, one part unless it was split
     * @param author who wrote it
     * @param writtenAt when
     */
    record Comment(@Nullable StagedComment staged, List<String> parts, Author author, Instant writtenAt) {}

    private final LegacyText.Format descriptionFormat;
    private final boolean continues;

    /**
     * The rules of one mapping.
     *
     * @param mapping the validated mapping
     */
    CaseComments(JsonNode mapping) {
        JsonNode description = mapping.at("/entities/case/fields/description");
        this.descriptionFormat = format(description.path("format").asString("plain"));
        this.continues =
                "continueInComment".equals(description.path("onOverflow").asString(""));
    }

    /**
     * Whether a staged description is longer than a case takes and the mapping does not continue it in a comment.
     *
     * @param staged the staged description
     * @return true if the case cannot be loaded with it
     */
    boolean tooLong(String staged) {
        return !continues
                && LegacyText.forDescription(staged, descriptionFormat).length() > MAX_TEXT;
    }

    /**
     * The description a case is loaded with.
     *
     * @param staged the staged description
     * @return its first 20 000 characters as a description holds them
     */
    String description(String staged) {
        String text = LegacyText.forDescription(staged, descriptionFormat);
        return text.substring(0, end(text, 0, MAX_TEXT));
    }

    /**
     * The comments a case is loaded with: its description's continuation first, then its staged comments in order.
     *
     * @param row the case
     * @param staged its staged comments to load, in the order they were written
     * @param people the matched people
     * @param importer the "Trailkeep import" principal, who writes a continuation
     * @return the comments
     */
    List<Comment> of(StagedCase row, List<StagedComment> staged, PeopleMatcher people, Author importer) {
        List<Comment> comments = new ArrayList<>();
        String description =
                LegacyText.forDescription(Objects.requireNonNull(row.getDescription(), "checked"), descriptionFormat);
        int cut = end(description, 0, MAX_TEXT);
        if (cut < description.length()) {
            String rest = LegacyText.escapeMarkdown(description.substring(cut));
            comments.add(
                    new Comment(null, parts(rest), importer, Objects.requireNonNull(row.getOpenedAt(), "checked")));
        }
        comments.addAll(of(staged, people));
        return comments;
    }

    /**
     * Staged comments as they are written, for a case loaded before (a delta's child-only load, WP-26 part y2).
     *
     * @param staged the comments to load, in the order they were written
     * @param people the matched people
     * @return the comments
     */
    List<Comment> of(List<StagedComment> staged, PeopleMatcher people) {
        List<Comment> comments = new ArrayList<>();
        for (StagedComment comment : staged) {
            comments.add(new Comment(
                    comment,
                    parts(body(comment)),
                    author(comment, people),
                    Objects.requireNonNull(comment.getWrittenAt(), "checked")));
        }
        return comments;
    }

    /**
     * A staged comment's body as a comment holds it.
     *
     * @param comment the comment, with a body
     * @return the Markdown
     */
    static String body(StagedComment comment) {
        String format = comment.getBodyFormat();
        return LegacyText.forComment(
                Objects.requireNonNull(comment.getBody(), "checked"), format(format == null ? "plain" : format));
    }

    /**
     * A comment body as the comments it is written as: itself, or numbered parts that each fit a comment.
     *
     * @param body the Markdown
     * @return one part, or several each starting with its number
     */
    static List<String> parts(String body) {
        if (body.length() <= MAX_TEXT) {
            return List.of(body);
        }
        List<String> chunks = new ArrayList<>();
        for (int from = 0; from < body.length(); ) {
            int to = end(body, from, MAX_TEXT - NUMBER_ROOM);
            chunks.add(body.substring(from, to));
            from = to;
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            parts.add("(" + (i + 1) + "/" + chunks.size() + ")\n" + chunks.get(i));
        }
        return List.copyOf(parts);
    }

    private static Author author(StagedComment comment, PeopleMatcher people) {
        PersonMatch match = people.match(comment.getAuthorKey());
        if (match != null) {
            return new Author(match.userId(), match.displayName());
        }
        String named = comment.getAuthorName();
        if (named == null || named.isBlank()) {
            named = people.name(comment.getAuthorKey());
        }
        return new Author(null, named == null || named.isBlank() ? UNNAMED_AUTHOR : named);
    }

    /**
     * Where a piece of at most {@code max} characters starting at {@code from} ends: never between the two halves of
     * a surrogate pair, nor between a Markdown escape's backslash and the character it escapes.
     */
    private static int end(String text, int from, int max) {
        int end = Math.min(text.length(), from + max);
        if (end < text.length() && (Character.isHighSurrogate(text.charAt(end - 1)) || text.charAt(end - 1) == '\\')) {
            end--;
        }
        return end;
    }

    private static LegacyText.Format format(String name) {
        return switch (name) {
            case "markdown" -> LegacyText.Format.MARKDOWN;
            case "html" -> LegacyText.Format.HTML;
            default -> LegacyText.Format.PLAIN;
        };
    }
}
