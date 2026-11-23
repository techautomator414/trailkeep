// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.restoredrill;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * What a schema holds, table by table: the row count and a SHA-256 of every row's every column, read in primary-key
 * order through JDBC. Two databases with the same fingerprint hold the same rows, value for value, attachment bytes and
 * audit JSON included. Oracle Text's own tables ({@code DR$...}) are left out: an import rebuilds them, and the drill
 * proves the index by using it.
 */
final class DatabaseFingerprint {

    /**
     * One table's row count and content digest.
     *
     * @param rows the number of rows
     * @param digest the first 16 hex digits of the SHA-256 over the rows, in key order
     */
    record Table(long rows, String digest) {}

    private DatabaseFingerprint() {}

    /**
     * Fingerprints every table of {@code schema}.
     *
     * @param connection a connection that can read every table of the schema
     * @param schema the schema, as the database's metadata spells it
     * @return each table's fingerprint by name, in name order
     * @throws SQLException if a table cannot be read
     */
    static Map<String, Table> of(Connection connection, String schema) throws SQLException {
        Map<String, Table> tables = new TreeMap<>();
        for (String table : tables(connection.getMetaData(), schema)) {
            tables.put(table.toUpperCase(Locale.ROOT), table(connection, schema, table));
        }
        return tables;
    }

    private static List<String> tables(DatabaseMetaData metadata, String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet tables = metadata.getTables(null, schema, "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (!name.startsWith("DR$") && !name.startsWith("BIN$")) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static Table table(Connection connection, String schema, String table) throws SQLException {
        List<String> order = primaryKey(connection.getMetaData(), schema, table);
        String sql = "SELECT * FROM " + quote(schema) + "." + quote(table)
                + (order.isEmpty() ? "" : " ORDER BY " + String.join(", ", order));
        MessageDigest digest = sha256();
        long rows = 0;
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(1000);
            // Test-only SQL: schema, table and columns come from the database's own metadata, quoted.
            try (ResultSet result = statement.executeQuery(sql)) {
                ResultSetMetaData columns = result.getMetaData();
                int count = columns.getColumnCount();
                if (order.isEmpty() && count > 0) {
                    throw new IllegalStateException(table + " has no primary key; its rows have no stable order");
                }
                while (result.next()) {
                    rows++;
                    for (int i = 1; i <= count; i++) {
                        add(digest, binary(columns.getColumnType(i)) ? result.getBytes(i) : text(result, i));
                    }
                }
            }
        }
        return new Table(rows, HexFormat.of().formatHex(digest.digest()).substring(0, 16));
    }

    private static List<String> primaryKey(DatabaseMetaData metadata, String schema, String table) throws SQLException {
        Map<Short, String> columns = new TreeMap<>();
        try (ResultSet keys = metadata.getPrimaryKeys(null, schema, table)) {
            while (keys.next()) {
                columns.put(keys.getShort("KEY_SEQ"), quote(keys.getString("COLUMN_NAME")));
            }
        }
        return new ArrayList<>(columns.values());
    }

    private static byte @Nullable [] text(ResultSet result, int column) throws SQLException {
        String value = result.getString(column);
        return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    /** Length-prefixed, with -1 for SQL NULL, so no two different rows hash alike by running into each other. */
    private static void add(MessageDigest digest, byte @Nullable [] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value == null ? -1 : value.length)
                .array());
        if (value != null) {
            digest.update(value);
        }
    }

    private static boolean binary(int type) {
        return type == Types.BLOB || type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
