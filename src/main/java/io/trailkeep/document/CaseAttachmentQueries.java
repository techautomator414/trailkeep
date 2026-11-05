// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import java.util.List;
import java.util.Optional;

/** The scoped attachment queries; implemented with the Criteria API in {@link CaseAttachmentQueriesImpl}. */
interface CaseAttachmentQueries {

    /**
     * A case's attachments, oldest first, if the scope reads the case. An unreadable case and an absent one give the
     * same answer.
     *
     * @param scope the caller's scope
     * @param caseNumber the case number
     * @return the attachments in the order they were uploaded, or an empty list
     */
    List<CaseAttachment> findForCase(CaseScope scope, CaseNumber caseNumber);

    /**
     * One attachment of a case, if the scope reads the case. An id of another case, readable or not, gives the same
     * answer as an absent one.
     *
     * @param scope the caller's scope
     * @param caseNumber the case number
     * @param attachmentId the attachment
     * @return the attachment, or empty
     */
    Optional<CaseAttachment> findOne(CaseScope scope, CaseNumber caseNumber, long attachmentId);
}
