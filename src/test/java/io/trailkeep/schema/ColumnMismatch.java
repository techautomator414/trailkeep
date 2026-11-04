// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

/**
 * One difference between what an entity declares and what the database holds.
 *
 * @param table upper-case table name
 * @param column upper-case column name
 * @param kind what differs
 * @param expected the entity's value
 * @param actual the database's value
 */
public record ColumnMismatch(String table, String column, Kind kind, String expected, String actual) {

    /** The aspect that differs. */
    public enum Kind {
        /** The mapped column does not exist. */
        MISSING_COLUMN,
        /** Entity and column disagree on NULL / NOT NULL. */
        NULLABILITY,
        /** Entity and character column disagree on declared length. */
        LENGTH
    }

    @Override
    public String toString() {
        return table + "." + column + " " + kind + ": entity=" + expected + ", database=" + actual;
    }
}
