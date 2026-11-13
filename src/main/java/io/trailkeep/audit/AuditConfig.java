// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the redaction key: required in {@code prod} (except in the {@code purge} batch profile, which audits nothing),
 * a random per-process key elsewhere.
 */
@Configuration(proxyBeanMethods = false)
class AuditConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AuditConfig.class);

    @Bean
    SnapshotDiff snapshotDiff(AuditProperties properties, Environment environment) {
        return new SnapshotDiff(hmac(properties, environment));
    }

    static AuditHmac hmac(AuditProperties properties, Environment environment) {
        String key = properties.hmacKey();
        if (key != null && !key.isBlank()) {
            return AuditHmac.fromBase64(key);
        }
        // The purge job (profile purge) writes no audit row and has no INSERT grant on AUDIT_EVENT, so its pod is not
        // given the key; every process that audits is. The migration Job (profile migrate) only migrates the schema.
        if (environment.matchesProfiles("prod & !purge & !migrate")) {
            throw new IllegalStateException("app.audit.hmac-key is required in the prod profile");
        }
        LOG.warn("app.audit.hmac-key is not set; using a random per-process key. Redacted audit values will not be"
                + " comparable across restarts. The prod profile refuses to start this way.");
        return AuditHmac.random();
    }
}
