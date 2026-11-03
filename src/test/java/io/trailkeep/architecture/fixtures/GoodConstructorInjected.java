// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture.fixtures;

import org.springframework.beans.factory.annotation.Autowired;

/** Compliant: constructor injection, even with an explicit {@code @Autowired}. */
class GoodConstructorInjected {

    private final Runnable task;

    @Autowired
    GoodConstructorInjected(Runnable task) {
        this.task = task;
    }

    void run() {
        task.run();
    }
}
