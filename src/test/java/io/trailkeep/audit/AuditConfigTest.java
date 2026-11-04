// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AuditConfigTest {

    @Test
    void prodRefusesToStartWithoutKey() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod", "postgresql");

        assertThatThrownBy(() -> AuditConfig.hmac(new AuditProperties(null), prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.audit.hmac-key");
        assertThatThrownBy(() -> AuditConfig.hmac(new AuditProperties("  "), prod))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void otherProfilesFallBackToRandomPerProcessKey() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        AuditHmac first = AuditConfig.hmac(new AuditProperties(null), local);
        AuditHmac second = AuditConfig.hmac(new AuditProperties(null), local);

        assertThat(first.hex("x")).hasSize(64).isNotEqualTo(second.hex("x"));
    }

    @Test
    void configuredKeyIsUsedInEveryProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        String key = Base64.getEncoder().encodeToString(new byte[AuditHmac.MIN_KEY_BYTES]);

        assertThat(AuditConfig.hmac(new AuditProperties(key), prod).hex("x"))
                .isEqualTo(AuditHmac.fromBase64(key).hex("x"));
    }

    @Test
    void shortOrMalformedKeysAreRejected() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[AuditHmac.MIN_KEY_BYTES - 1]);

        assertThatThrownBy(() -> AuditHmac.fromBase64(shortKey)).hasMessageContaining("at least 32 bytes");
        assertThatThrownBy(() -> AuditHmac.fromBase64("not base64 !")).hasMessageContaining("Base64");
    }
}
