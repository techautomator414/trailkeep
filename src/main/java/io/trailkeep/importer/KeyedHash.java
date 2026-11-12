// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * HMAC-SHA-256 under {@code import.hash-key}, in hex (design §5 "Hashes are keyed"): the {@code hash} transform, row
 * hashes and the keys of issues. Never plain SHA-256, which short legacy text would make dictionary-reversible.
 */
final class KeyedHash {

    private KeyedHash() {}

    /**
     * The keyed hash of a text.
     *
     * @param key {@code import.hash-key}
     * @param text the text, as UTF-8
     * @return the hex HMAC
     */
    static String hex(SecretKey key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is part of every Java platform", e);
        }
    }
}
