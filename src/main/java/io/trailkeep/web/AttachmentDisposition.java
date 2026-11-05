// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import java.nio.charset.StandardCharsets;

/**
 * The {@code Content-Disposition} of a download (BUILD_PLAN §9): always {@code attachment}, never inline, with the
 * uploaded name twice per RFC 6266 §4.3: a quoted ASCII fallback in which every character outside printable ASCII,
 * and {@code "}, {@code \} and {@code %}, becomes {@code _}; and {@code filename*} in RFC 5987 form, the UTF-8 bytes
 * percent-encoded except the attr-chars. Neither part can carry a CR, an LF, a quote or a parameter of its own, so a
 * hostile name cannot split or extend the header.
 */
final class AttachmentDisposition {

    /** RFC 5987 attr-char: sent as is in {@code filename*}; every other byte is percent-encoded. */
    private static final String ATTR_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$&+-.^_`|~";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private AttachmentDisposition() {}

    /**
     * The header value for a file name.
     *
     * @param fileName the name as uploaded
     * @return {@code attachment; filename="..."; filename*=UTF-8''...}
     */
    static String of(String fileName) {
        StringBuilder fallback = new StringBuilder(fileName.length());
        fileName.chars()
                .forEach(c -> fallback.append(c >= 0x20 && c < 0x7F && "\"\\%".indexOf(c) < 0 ? (char) c : '_'));
        StringBuilder encoded = new StringBuilder();
        for (byte b : fileName.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (ATTR_CHARS.indexOf(c) >= 0) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return "attachment; filename=\"" + (fallback.isEmpty() ? "download" : fallback) + "\"; filename*=UTF-8''"
                + encoded;
    }
}
