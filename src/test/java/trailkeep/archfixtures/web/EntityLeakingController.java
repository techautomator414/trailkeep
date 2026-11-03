// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures.web;

import trailkeep.archfixtures.FixtureEntity;

/** Violation: the web layer touches an entity. */
class EntityLeakingController {

    String render(FixtureEntity entity) {
        return String.valueOf(entity);
    }
}
