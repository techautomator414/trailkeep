// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code messages.properties} defines every key once. {@link java.util.Properties} keeps the last duplicate silently,
 * so a key pasted into two places (as {@code tk.attachment.scan.NOT_SCANNED} once was) goes unnoticed until the two
 * texts differ.
 */
class MessagesPropertiesTest {

    @Test
    void noKeyIsDefinedTwice() throws IOException {
        Set<String> seen = new HashSet<>();
        List<String> repeated = new ArrayList<>();
        for (String key : keys()) {
            if (!seen.add(key)) {
                repeated.add(key);
            }
        }
        assertThat(repeated).as("keys defined more than once").isEmpty();
    }

    @Test
    void theFileIsRead() throws IOException {
        assertThat(keys()).contains("tk.attachment.scan.NOT_SCANNED").hasSizeGreaterThan(100);
    }

    /** The key of every logical line, in file order; comments, blank lines and continuation lines are skipped. */
    private static List<String> keys() throws IOException {
        List<String> keys = new ArrayList<>();
        InputStream in = Objects.requireNonNull(
                MessagesPropertiesTest.class.getResourceAsStream("/messages.properties"), "messages.properties");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            boolean continued = false;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.stripLeading();
                if (!continued && (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!"))) {
                    continue;
                }
                boolean continuation = continued;
                continued = endsWithOddBackslashes(line);
                if (continuation) {
                    continue;
                }
                int end = 0;
                while (end < trimmed.length() && "=: \t".indexOf(trimmed.charAt(end)) < 0) {
                    end += trimmed.charAt(end) == '\\' ? 2 : 1;
                }
                keys.add(trimmed.substring(0, Math.min(end, trimmed.length())));
            }
        }
        return keys;
    }

    private static boolean endsWithOddBackslashes(String line) {
        int count = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            count++;
        }
        return count % 2 == 1;
    }
}
