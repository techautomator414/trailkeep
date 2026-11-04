// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.shared.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * The product name for templates, including the error pages Spring Boot renders: {@code ${@branding.name()}}.
 */
@Component("branding")
class Branding {

    private final String name;

    Branding(AppProperties properties) {
        this.name = properties.branding().name();
    }

    /**
     * The configured product name.
     *
     * @return the name
     */
    public String name() {
        return name;
    }
}
