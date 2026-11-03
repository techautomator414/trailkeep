// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints, registered with {@code @Import}; never component-scanned into the application. */
@TestComponent
@RestController
class RemoteAddressController {

    static final String PROBE_MESSAGE = "ops-probe-log-line";

    private static final Logger LOG = LoggerFactory.getLogger(RemoteAddressController.class);

    @GetMapping("/test-only/remote")
    String remote(HttpServletRequest request) {
        return request.getRemoteAddr() + "|" + request.getScheme();
    }

    @GetMapping("/test-only/log")
    String log() {
        LOG.info(PROBE_MESSAGE);
        return "ok";
    }
}
