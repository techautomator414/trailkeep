// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The structural redaction check of AuditRecorderIT (#268), shown on crafted JSON, with no database. */
class RedactedJsonAssertTest {

    private static final String SECRET = "Synthetic sensitive subject 4711";
    private static final String HEX_WITH_4711 = "cc0c6fc571a1554711d1c0ffee00112233445566778899aabbccddeeff001122";

    @Test
    void anHmacThatContainsTheFragmentByChanceIsAccepted() {
        assertThatCode(() -> RedactedJsonAssert.assertRedacted(
                        "{\"title\":\"Old\",\"subject\":{\"hmac\":\"" + HEX_WITH_4711 + "\",\"length\":32}}",
                        "subject",
                        SECRET.length(),
                        SECRET,
                        "sensitive",
                        "4711"))
                .doesNotThrowAnyException();
    }

    @Test
    void theSubjectStoredUnredactedIsRefused() {
        assertThatThrownBy(() -> RedactedJsonAssert.assertRedacted(
                        "{\"title\":\"Old\",\"subject\":\"" + SECRET + "\"}", "subject", SECRET.length(), SECRET))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void plaintextInAnotherFieldOfTheRedactedObjectOrTheDocumentIsRefused() {
        assertThatThrownBy(() -> RedactedJsonAssert.assertRedacted(
                        "{\"title\":\"Old\",\"subject\":{\"hmac\":\"" + HEX_WITH_4711 + "\",\"length\":32,\"plain\":\""
                                + SECRET + "\"}}",
                        "subject",
                        SECRET.length(),
                        SECRET))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> RedactedJsonAssert.assertRedacted(
                        "{\"title\":\"sensitive\",\"subject\":{\"hmac\":\"" + HEX_WITH_4711 + "\",\"length\":32}}",
                        "subject",
                        SECRET.length(),
                        "sensitive"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void malformedRedactionsAreRefused() {
        String upper = HEX_WITH_4711.toUpperCase(java.util.Locale.ROOT);
        for (String subject : new String[] {
            "{\"hmac\":\"" + upper + "\",\"length\":32}",
            "{\"hmac\":\"abc\",\"length\":32}",
            "{\"hmac\":\"" + HEX_WITH_4711 + "\",\"length\":31}",
            "{\"hmac\":\"" + HEX_WITH_4711 + "\"}",
        }) {
            assertThatThrownBy(() -> RedactedJsonAssert.assertRedacted(
                            "{\"subject\":" + subject + "}", "subject", SECRET.length(), SECRET))
                    .isInstanceOf(AssertionError.class);
        }
    }
}
