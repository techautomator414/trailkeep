// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the export recipe tests share (WP-26 part ae, design §4.2.2, §11): the command blocks of
 * docs/importer/export-recipes.md, read from the page itself so it cannot drift from what the tests run, and the keyed
 * row hashes two runs staged, which source equivalence compares.
 */
final class ExportRecipes {

    /** The page, relative to the project, where Failsafe runs. */
    static final Path PAGE = Path.of("docs/importer/export-recipes.md");

    private ExportRecipes() {}

    /**
     * A command block of the page: the fenced block under the {@code <!-- recipe: name ... -->} marker.
     *
     * @param name the block's name, such as {@code postgresql/export.sql}
     * @return its text
     * @throws Exception if the page cannot be read
     */
    static String block(String name) throws Exception {
        return block(PAGE, name);
    }

    /**
     * A block of another page that marks its blocks the same way, such as docs/importer/worked-example.md.
     *
     * @param path the page, relative to the project
     * @param name the block's name
     * @return its text
     * @throws Exception if the page cannot be read
     */
    static String block(Path path, String name) throws Exception {
        String page = Files.readString(path);
        int marker = page.indexOf("<!-- recipe: " + name + " ");
        assertThat(marker).as("recipe " + name + " on " + path).isNotNegative();
        int open = page.indexOf('\n', page.indexOf("```", marker)) + 1;
        return page.substring(open, page.indexOf("```", open));
    }

    /**
     * A run's staged rows of one staging table: each key with its keyed row hash, in key order.
     *
     * @param jdbc the schema owner's template
     * @param table a staging table's name, from the test
     * @param run the run
     * @return the hashes by key
     */
    static Map<String, String> rowHashes(JdbcTemplate jdbc, String table, long run) {
        Map<String, String> hashes = new LinkedHashMap<>();
        // Test-only read over the fixed table names the tests pass.
        jdbc.query(
                "SELECT LEGACY_KEY, ROW_HASH FROM " + table + " WHERE RUN_ID = ? ORDER BY LEGACY_KEY",
                (row, index) -> hashes.put(row.getString(1), row.getString(2)),
                run);
        return hashes;
    }
}
