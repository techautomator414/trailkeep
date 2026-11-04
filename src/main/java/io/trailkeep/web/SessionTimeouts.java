// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The idle timeout for the layout's expiry warning: {@code ${@sessionTimeouts.idleSeconds()}}. */
@Component("sessionTimeouts")
class SessionTimeouts {

    private final Duration idle;

    SessionTimeouts(@Value("${server.servlet.session.timeout:30m}") Duration idle) {
        this.idle = idle;
    }

    /**
     * The idle timeout.
     *
     * @return seconds
     */
    public long idleSeconds() {
        return idle.toSeconds();
    }
}
