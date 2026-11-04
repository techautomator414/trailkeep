// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audit settings under {@code app.audit}.
 *
 * @param hmacKey the server key for {@link Redacted} values, Base64-encoded, at least 32 bytes. Mounted from the
 *     platform's secret store; never in the repository. Required in {@code prod} (start-up fails without it); other
 *     profiles fall back to a random per-process key, so their redacted values are not comparable across restarts.
 */
@ConfigurationProperties(prefix = "app.audit")
record AuditProperties(@Nullable String hmacKey) {}
