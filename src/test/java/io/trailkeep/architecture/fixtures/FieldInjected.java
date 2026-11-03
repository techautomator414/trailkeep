// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture.fixtures;

import org.springframework.beans.factory.annotation.Autowired;

/** Violation: field injection. */
class FieldInjected {

    @Autowired
    private Runnable task;

    void run() {
        task.run();
    }
}
