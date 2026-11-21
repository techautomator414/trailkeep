// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks a stored audit JSON structurally (issue 268). A substring check over the whole text is not sound: the redacted
 * field's {@code hmac} is 64 hex digits of a per-run random key, so it can contain any short digit string by chance
 * (about 1 run in 1,000 for "4711"). Instead the redacted field must be exactly an object with a lowercase 64-hex
 * {@code hmac} and the plaintext's {@code length}, and no other string in the document may contain a forbidden
 * fragment of the secret.
 */
final class RedactedJsonAssert {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RedactedJsonAssert() {}

    static void assertRedacted(String json, String field, int plaintextLength, String... forbiddenFragments) {
        JsonNode root = JSON.readTree(json);
        JsonNode redacted = root.path(field);

        assertThat(redacted.isObject()).as("%s is an object in %s", field, json).isTrue();
        assertThat(redacted.propertyNames())
                .as("only hmac and length in %s", field)
                .containsExactlyInAnyOrder("hmac", "length");
        assertThat(redacted.path("hmac").asString()).as("hmac of %s", field).matches("[0-9a-f]{64}");
        assertThat(redacted.path("length").isInt()).as("length is a number").isTrue();
        assertThat(redacted.path("length").asInt()).as("length of %s", field).isEqualTo(plaintextLength);

        List<String> strings = new ArrayList<>();
        root.properties().forEach(entry -> {
            strings.add(entry.getKey());
            if (entry.getKey().equals(field)) {
                // The redacted object: every name and value except the hmac's own value.
                entry.getValue().properties().forEach(inner -> {
                    strings.add(inner.getKey());
                    if (!inner.getKey().equals("hmac")) {
                        collectStrings(inner.getValue(), strings);
                    }
                });
            } else {
                collectStrings(entry.getValue(), strings);
            }
        });
        for (String text : strings) {
            assertThat(text).as("a stored string other than the hmac").doesNotContain(forbiddenFragments);
        }
    }

    /** Every field name and string value below the node. */
    private static void collectStrings(JsonNode node, List<String> into) {
        if (node.isString()) {
            into.add(node.asString());
        }
        node.properties().forEach(entry -> {
            into.add(entry.getKey());
            collectStrings(entry.getValue(), into);
        });
        node.forEach(element -> collectStrings(element, into));
    }
}
