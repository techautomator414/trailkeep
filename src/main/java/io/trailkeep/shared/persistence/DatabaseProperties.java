// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.persistence;

import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Database settings under {@code app.db}.
 *
 * @param vendor the database vendor; set by the {@code oracle} or {@code postgresql} profile and required
 * @param schema the schema that owns the tables, used as {@code hibernate.default_schema} when the runtime user
 *     does not own the objects (BUILD_PLAN §8 rule 7); {@code null} or blank means the connection's own schema
 */
@Validated
@ConfigurationProperties("app.db")
public record DatabaseProperties(
        @NotNull DatabaseVendor vendor, @Nullable String schema) {}
