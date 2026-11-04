// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Case settings under {@code app.cases}.
 *
 * @param numberPrefix the case number prefix, one to eight upper-case letters or digits (BUILD_PLAN §4.2)
 */
@Validated
@ConfigurationProperties(prefix = "app.cases")
record CaseProperties(
        @Pattern(regexp = "[A-Z0-9]{1,8}") @DefaultValue("CASE")
        String numberPrefix) {}
