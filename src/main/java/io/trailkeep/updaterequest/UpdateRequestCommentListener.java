// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.updaterequest;

import io.trailkeep.casefile.CommentPosted;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Auto-resolve (BUILD_PLAN §4.3): a comment by the person an update was requested from answers their open requests on
 * that case. It listens {@code BEFORE_COMMIT} (Part B "Transactions"), so the answer commits, or rolls back, with the
 * comment, and {@code casefile} never depends on this module.
 */
@Component
class UpdateRequestCommentListener {

    private final DefaultUpdateRequestService requests;

    UpdateRequestCommentListener(DefaultUpdateRequestService requests) {
        this.requests = requests;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void on(CommentPosted posted) {
        requests.answerByComment(posted);
    }
}
