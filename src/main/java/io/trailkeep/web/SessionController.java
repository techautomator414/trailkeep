// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /session/extend}: the session-expiry warning's "Stay signed in" (WCAG 2.2.1). Any authenticated
 * request renews the idle timeout, so the endpoint only has to exist; CSRF protects it like every mutation.
 */
@RestController
class SessionController {

    @PostMapping("/session/extend")
    ResponseEntity<Void> extend() {
        return ResponseEntity.noContent().build();
    }
}
