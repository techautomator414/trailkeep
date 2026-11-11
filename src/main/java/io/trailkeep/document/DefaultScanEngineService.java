// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Reports the scanner: in {@code clamd} mode by asking clamd for its version, in {@code external} mode (where no
 * {@link ClamdClient} exists) without calling anything.
 */
@Service
class DefaultScanEngineService implements ScanEngineService {

    private final ObjectProvider<ClamdClient> clamd;

    DefaultScanEngineService(ObjectProvider<ClamdClient> clamd) {
        this.clamd = clamd;
    }

    @Override
    public Status status() {
        ClamdClient client = clamd.getIfAvailable();
        if (client == null) {
            return new Status(State.EXTERNAL, null);
        }
        try {
            return new Status(State.AVAILABLE, client.version());
        } catch (ScannerUnavailableException e) {
            return new Status(State.UNAVAILABLE, null);
        }
    }
}
