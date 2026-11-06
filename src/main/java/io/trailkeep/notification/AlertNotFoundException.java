// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import io.trailkeep.shared.DomainException;

/** No such alert for this person: another person's alert or an unknown id, answered as 404 alike. */
public final class AlertNotFoundException extends DomainException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public AlertNotFoundException() {
        super(Kind.NOT_FOUND, "no such alert");
    }
}
