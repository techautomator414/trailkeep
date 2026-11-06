// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The CSV the export writes (RFC 4180, hand-rolled on purpose: no library, IMPLEMENTATION_PLAN WP-15). Every cell is
 * quoted, with quotes doubled, so commas, quotes and line breaks inside a value stay in its cell; lines end in CRLF.
 * A cell that a spreadsheet would read as a formula (it starts with {@code =}, {@code +}, {@code -}, {@code @}, a tab
 * or a carriage return) gets a leading apostrophe, so it shows as text and never runs (CSV injection).
 */
final class CsvFormat {

    /** The UTF-8 byte order mark, written first so spreadsheets read the file as UTF-8. */
    static final String BOM = "\uFEFF";

    private CsvFormat() {}

    /**
     * One line of cells.
     *
     * @param cells the values, in column order
     * @return the line, ending in CRLF
     */
    static String line(List<String> cells) {
        return cells.stream().map(CsvFormat::cell).collect(Collectors.joining(",", "", "\r\n"));
    }

    /**
     * One cell, quoted and defused.
     *
     * @param value the value as it is
     * @return the quoted cell
     */
    static String cell(String value) {
        boolean formula = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0;
        return "\"" + (formula ? "'" : "") + value.replace("\"", "\"\"") + "\"";
    }
}
