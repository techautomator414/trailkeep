// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code CASE_SEARCH} in step with a case, in the writer's transaction (BUILD_PLAN §8 rule 4, ADR-0006). A
 * comment appends atomically; an edit rebuilds the text from title, description and every comment after taking the
 * row lock ({@code SELECT … FOR UPDATE}). The comments are read after the lock is held, so a comment that committed
 * while the edit waited is in the rebuilt text, and one still being written appends to it afterwards. An imported
 * case's legacy number is part of the text too, so it is still found by it after an edit (WP-26, design §7.1 R5).
 * Subject is never included.
 */
@Component
@CaseAccessPath
class CaseSearchWriter {

    private final CaseSearchJdbcDao dao;
    private final CaseCommentRepository comments;
    private final CaseScopeService scopes;
    private final EntityManager entityManager;

    CaseSearchWriter(
            CaseSearchJdbcDao dao,
            CaseCommentRepository comments,
            CaseScopeService scopes,
            EntityManager entityManager) {
        this.dao = dao;
        this.comments = comments;
        this.scopes = scopes;
        this.entityManager = entityManager;
    }

    /**
     * Appends a comment that has been flushed; a case without a search row gets one rebuilt instead.
     *
     * @param user the writer, who reads the case
     * @param record the case
     * @param body the comment's text
     * @param now when it was written
     */
    void appendComment(UserPermissions user, CaseRecord record, String body, Instant now) {
        if (!dao.append(Objects.requireNonNull(record.getId()), "\n" + body, now)) {
            rebuild(user, record, now);
        }
    }

    /**
     * Rebuilds the search text under the row lock, after the case's own changes have been flushed.
     *
     * @param user the writer, who reads the case
     * @param record the case
     * @param now when it was written
     */
    void rebuild(UserPermissions user, CaseRecord record, Instant now) {
        long caseId = Objects.requireNonNull(record.getId());
        final CaseSearch search = entityManager.find(CaseSearch.class, caseId, LockModeType.PESSIMISTIC_WRITE);
        StringBuilder text = caseText(record);
        for (CaseComment comment : comments.findForCase(scopes.forUser(user), record.getCaseNumber())) {
            text.append('\n').append(comment.getBody());
        }
        if (search == null) {
            entityManager.persist(new CaseSearch(caseId, text.toString(), now));
        } else {
            search.rebuild(text.toString(), now);
        }
    }

    /**
     * The case's own part of its search text: title, the legacy number of an imported case, and description; the
     * comments follow it.
     *
     * @param record the case
     * @return the text, ready for comments to be appended
     */
    static StringBuilder caseText(CaseRecord record) {
        StringBuilder text = new StringBuilder(record.getTitle());
        if (record.getLegacyNumber() != null) {
            text.append('\n').append(record.getLegacyNumber());
        }
        return text.append('\n').append(record.getDescription());
    }
}
