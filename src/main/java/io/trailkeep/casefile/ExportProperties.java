// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * CSV export settings under {@code app.export} (BUILD_PLAN §4.4 "Export CSV").
 *
 * @param maxRows the most rows one export may hold; a filter matching more is refused, and the person narrows it
 */
@Validated
@ConfigurationProperties(prefix = "app.export")
record ExportProperties(
        @Min(1) @Max(1_000_000) @DefaultValue("10000") int maxRows) {}
