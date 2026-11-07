// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The export's hand-rolled CSV: every cell quoted with quotes doubled, CRLF line ends, and a leading apostrophe on any
 * cell a spreadsheet would read as a formula (CSV injection), over a corpus of hostile and ordinary values.
 */
class CsvFormatTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "=HYPERLINK(\"http://example.invalid\",\"x\")",
                "=1+1",
                "+1",
                "-1",
                "@SUM(A1:A2)",
                "\tleading tab",
                "\rleading carriage return"
            })
    void cellsSpreadsheetsWouldEvaluateGetLeadingApostrophe(String value) {
        String cell = CsvFormat.cell(value);

        assertThat(cell).startsWith("\"'").endsWith("\"");
        assertThat(unquote(cell)).isEqualTo("'" + value);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            value = {
                "plain|\"plain\"",
                "a, b|\"a, b\"",
                "say \"hi\"|\"say \"\"hi\"\"\"",
                "1-2|\"1-2\"",
                "a=b|\"a=b\"",
                "Zoë 名前 😀|\"Zoë 名前 😀\""
            })
    void ordinaryCellsAreQuotedWithQuotesDoubledAndNotDefused(String value, String expected) {
        assertThat(CsvFormat.cell(value)).isEqualTo(expected);
    }

    @Test
    void emptyCellsAndLineBreaksInsideCellsStayInTheirCell() {
        assertThat(CsvFormat.cell("")).isEqualTo("\"\"");
        assertThat(CsvFormat.cell("line one\r\nline two")).isEqualTo("\"line one\r\nline two\"");
        assertThat(CsvFormat.cell("line one\n=still text")).isEqualTo("\"line one\n=still text\"");
    }

    @Test
    void linesJoinQuotedCellsWithCommasAndEndInCrlf() {
        assertThat(CsvFormat.line(List.of("CASE-2026-000001", "=cmd|' /C calc'!A0", "say \"x\", y", "")))
                .isEqualTo("\"CASE-2026-000001\",\"'=cmd|' /C calc'!A0\",\"say \"\"x\"\", y\",\"\"\r\n");
        assertThat(CsvFormat.BOM).isEqualTo("\uFEFF");
    }

    private static String unquote(String cell) {
        return cell.substring(1, cell.length() - 1).replace("\"\"", "\"");
    }
}
