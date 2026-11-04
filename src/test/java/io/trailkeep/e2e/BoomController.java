// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only: an unexpected failure, so the 5xx error page can be scanned. Never packaged. */
@TestComponent
@RestController
class BoomController {

    @GetMapping("/test-only/boom")
    String boom() {
        throw new IllegalStateException("unexpected failure for the 5xx page test");
    }
}
