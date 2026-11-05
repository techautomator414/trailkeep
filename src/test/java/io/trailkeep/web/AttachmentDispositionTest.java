// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The download's {@code Content-Disposition}: hostile names stay inside their two parameters (RFC 6266, 5987). */
class AttachmentDispositionTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "report.pdf|report.pdf|report.pdf",
                "notes 2026.txt|notes 2026.txt|notes%202026.txt",
                "résumé.docx|r_sum_.docx|r%C3%A9sum%C3%A9.docx",
                "a\"b.txt|a_b.txt|a%22b.txt",
                "back\\slash.txt|back_slash.txt|back%5Cslash.txt",
                "100%.csv|100_.csv|100%25.csv",
                "x;filename=evil.exe|x;filename=evil.exe|x%3Bfilename%3Devil.exe",
                "日本.pdf|__.pdf|%E6%97%A5%E6%9C%AC.pdf"
            })
    void namesAreQuotedAsAsciiAndPercentEncodedAsUtf8(String name, String fallback, String encoded) {
        assertThat(AttachmentDisposition.of(name))
                .isEqualTo("attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\r\nSet-Cookie: x=1", "a\nb", "a\rb", "tab\there", "nul\u0000", "del\u007f", " line"})
    void controlCharactersNeverReachTheHeader(String name) {
        String header = AttachmentDisposition.of(name);
        assertThat(header.chars()).allMatch(c -> c >= 0x20 && c < 0x7F);
        assertThat(header.chars().filter(c -> c == '"')).hasSize(2);
    }

    @Test
    void emptyNameStillHasFallback() {
        assertThat(AttachmentDisposition.of("")).isEqualTo("attachment; filename=\"download\"; filename*=UTF-8''");
    }
}
