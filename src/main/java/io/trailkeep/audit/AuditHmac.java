// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA-256 under the audit server key. Thread-safe: a {@link Mac} is created per call. */
final class AuditHmac {

    static final int MIN_KEY_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    private AuditHmac(byte[] key) {
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "app.audit.hmac-key must decode to at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(key, ALGORITHM);
    }

    static AuditHmac fromBase64(String encoded) {
        try {
            return new AuditHmac(Base64.getDecoder().decode(encoded.strip()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("app.audit.hmac-key must be Base64 of at least 32 bytes", e);
        }
    }

    static AuditHmac random() {
        byte[] key = new byte[MIN_KEY_BYTES];
        RANDOM.nextBytes(key);
        return new AuditHmac(key);
    }

    String hex(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
