// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

/**
 * Every download slot ({@code app.attachments.max-concurrent-downloads}) is taken. Not a client error: the web layer
 * answers 503 with {@code Retry-After}, and nothing was read.
 */
public final class DownloadsBusyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Seconds a client is asked to wait before trying again. */
    public static final int RETRY_AFTER_SECONDS = 5;

    DownloadsBusyException() {
        super("all download slots are in use");
    }
}
