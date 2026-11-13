// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * A row's written form (WP-26 part aa1, design §9): an empty text is the same as an absent one (Oracle reads it back as
 * {@code NULL}), numbers lose trailing zeros, instants are microseconds since the epoch whatever their offset, and each
 * value is length-prefixed, so no value can pass for two.
 */
class WrittenFormTest {

    @Test
    void emptyAndAbsentTextsAreOneAndTheSame() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("A")).thenReturn("");
        when(row.getString("B")).thenReturn(null);

        assertThat(WrittenForm.of(row).texts("A").toString())
                .isEqualTo(WrittenForm.of(row).texts("B").toString())
                .isEqualTo("s;");
    }

    @Test
    void lengthPrefixesKeepEveryValueApart() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("A")).thenReturn("x;s1:y");
        when(row.getString("B")).thenReturn("x");
        when(row.getString("C")).thenReturn("y");

        assertThat(WrittenForm.of(row).texts("A").toString())
                .isEqualTo("s6:x;s1:y;")
                .isNotEqualTo(WrittenForm.of(row).texts("B", "C").toString());
    }

    @Test
    void numbersInstantsAndDatesAreCanonical() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getBigDecimal("N")).thenReturn(new BigDecimal("42.000"));
        when(row.getObject("T", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("1970-01-01T01:00:00.000001+01:00"));
        when(row.getObject("D", LocalDate.class)).thenReturn(LocalDate.parse("2024-01-15"));

        assertThat(WrittenForm.of(row).numbers("N").instants("T").dates("D").toString())
                .isEqualTo("n2:42;t1:1;d10:2024-01-15;");
    }
}
