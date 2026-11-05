// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.casefile.CaseScopeService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;

/**
 * Criteria implementation of {@link CaseAttachmentQueries}. An attachment holds its case as an id; {@link
 * CaseScopeService#readableCase} adds the {@code CaseRecord} root, joins it on that id and applies the scope, in one
 * statement, so no case entity crosses into this module.
 */
class CaseAttachmentQueriesImpl implements CaseAttachmentQueries {

    private final EntityManager entityManager;
    private final CaseScopeService scopes;

    CaseAttachmentQueriesImpl(EntityManager entityManager, CaseScopeService scopes) {
        this.entityManager = entityManager;
        this.scopes = scopes;
    }

    @Override
    public List<CaseAttachment> findForCase(CaseScope scope, CaseNumber caseNumber) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CaseAttachment> query = cb.createQuery(CaseAttachment.class);
        Root<CaseAttachment> attachment = query.from(CaseAttachment.class);
        query.select(attachment)
                .where(scopes.readableCase(scope, caseNumber, attachment.get("caseId"), query, cb))
                .orderBy(cb.asc(attachment.get("uploadedAt")), cb.asc(attachment.get("id")));
        return entityManager.createQuery(query).getResultList();
    }
}
