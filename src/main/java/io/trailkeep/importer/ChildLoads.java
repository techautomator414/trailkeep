// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.importer.staging.StagedAttachment;
import io.trailkeep.importer.staging.StagedComment;
import io.trailkeep.importer.staging.StagedEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The children of the cases one load writes (WP-26 parts u and w): comments ({@link CommentLoad}), attachments
 * ({@link AttachmentLoad}) and legacy history ({@link EventLoad}), read for a page of cases at once and written in each
 * case's transaction.
 *
 * @param comments the comment part
 * @param attachments the attachment part
 * @param events the history part
 */
record ChildLoads(CommentLoad comments, AttachmentLoad attachments, EventLoad events) {

    /**
     * One case's staged children to load.
     *
     * @param comments its comments, in the order they were written
     * @param files its attachments with bytes staged
     * @param events its legacy history, in the order it happened
     */
    record Staged(List<StagedComment> comments, List<StagedAttachment> files, List<StagedEvent> events) {}

    /**
     * The staged children of a page's cases to load.
     *
     * @param caseKeys the cases' canonical keys
     * @return each case's children, every case present
     */
    Map<String, Staged> staged(List<String> caseKeys) {
        Map<String, List<StagedComment>> notes = comments.staged(caseKeys);
        Map<String, List<StagedAttachment>> files = attachments.staged(caseKeys);
        Map<String, List<StagedEvent>> history = events.staged(caseKeys);
        Map<String, Staged> byCase = new HashMap<>();
        for (String key : caseKeys) {
            byCase.put(
                    key,
                    new Staged(
                            notes.getOrDefault(key, List.of()),
                            files.getOrDefault(key, List.of()),
                            history.getOrDefault(key, List.of())));
        }
        return byCase;
    }

    /**
     * Writes a case's children in its transaction: its comments, its scanned attachments and its history.
     *
     * @param created the case
     * @param texts its comments as they are written
     * @param scanned its attachments, scanned
     * @param staged its staged children
     * @param people the load's matched people
     * @param importer the "Trailkeep import" principal
     * @throws io.trailkeep.document.AttachmentRejectedException if a file changed since its scan
     */
    void write(
            CaseImportService.Created created,
            List<CaseComments.Comment> texts,
            AttachmentLoad.Scanned scanned,
            Staged staged,
            PeopleMatcher people,
            CaseComments.Author importer) {
        texts.forEach(comment -> comments.write(created, comment));
        attachments.write(created, scanned, people, importer);
        events.write(created, staged.events(), people);
    }
}
