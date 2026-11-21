// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

/**
 * A request parameter (a name or a value) held a control character, raised by {@link ControlCharacterInterceptor} and
 * answered 400 by {@link ErrorAdvice}. The message carries neither the parameter nor its value: both are the sender's
 * text and do not belong in a log line.
 */
final class ControlCharacterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ControlCharacterException() {
        super("a request parameter holds a control character");
    }
}
