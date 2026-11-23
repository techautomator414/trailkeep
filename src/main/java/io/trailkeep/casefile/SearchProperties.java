// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Case search settings under {@code app.search} (ADR-0017).
 *
 * @param adapter which {@link SearchGateway} serves the search; the vendor profile sets it: {@code oracle-text} on
 *     Oracle, {@code like} on PostgreSQL, where the V21 trigram index serves the {@code LIKE} patterns
 */
@Validated
@ConfigurationProperties(prefix = "app.search")
record SearchProperties(@NotNull Adapter adapter) {

    /** The search adapters. */
    enum Adapter {
        /** {@link LikeSearchGateway}, on either vendor. */
        LIKE,
        /** {@link OracleTextSearchGateway}, on Oracle only: it needs the V21 {@code CONTEXT} index. */
        ORACLE_TEXT
    }
}
