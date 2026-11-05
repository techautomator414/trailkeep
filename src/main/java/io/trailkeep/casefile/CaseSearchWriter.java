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
 * while the edit waited is in the rebuilt text, and one still being written appends to it afterwards. Subject is
 * never included.
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
        CaseSearch search = entityManager.find(CaseSearch.class, caseId, LockModeType.PESSIMISTIC_WRITE);
        StringBuilder text = new StringBuilder(record.getTitle()).append('\n').append(record.getDescription());
        for (CaseComment comment : comments.findForCase(scopes.forUser(user), record.getCaseNumber())) {
            text.append('\n').append(comment.getBody());
        }
        if (search == null) {
            entityManager.persist(new CaseSearch(caseId, text.toString(), now));
        } else {
            search.rebuild(text.toString(), now);
        }
    }
}
