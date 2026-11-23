// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Oracle's V21 search index ({@code -Poracle-it}, both images; ADR-0017): an Oracle Text
 * {@code CONTEXT} index, valid, built from system preferences only, synchronised on commit and optimised daily, with
 * no optimizer statistics and those of {@code CASE_SEARCH} locked; its script runs again without harm; and the runtime and purge users write {@code CASE_SEARCH} with the grants they had
 * before it, the runtime user finding its own text in the index before and after the commit.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OracleSearchIndexIT {

    private static final String INDEX = "IX_CASE_SEARCH_TEXT";

    /** A line holding only "/", which ends the script's PL/SQL block. */
    private static final Pattern SLASH_LINE = Pattern.compile("(?m)^/[ \\t]*$");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestDatabaseUsers users;

    @Value("${app.db.app-user}")
    private String appUser;

    @Value("${app.db.purge-user}")
    private String purgeUser;

    private CaseSeed seed;
    private long team;
    private long owner;
    private long caseId;

    @BeforeEach
    void commitACase() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        owner = seed.user();
        CaseNumber number = seed.caseRow(team, owner, Sensitivity.STANDARD, CaseStatus.OPEN);
        caseId = seed.caseId(number);
        jdbc.update(
                "INSERT INTO CASE_SEARCH (CASE_ID, SEARCH_TEXT, UPDATED_AT) VALUES (?, 'synthetic index probe',"
                        + " CURRENT_TIMESTAMP)",
                caseId);
    }

    @AfterEach
    void removeTheCase() {
        jdbc.update("DELETE FROM CASE_SEARCH WHERE CASE_ID = ?", caseId);
        jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", caseId);
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", owner);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        long caseType = seed.caseType();
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void aValidContextIndexOfSystemPreferencesSynchronisedOnCommitAndOptimisedDaily() {
        Map<String, Object> index = jdbc.queryForMap(
                "SELECT TABLE_NAME, ITYP_OWNER, ITYP_NAME, STATUS, DOMIDX_STATUS, DOMIDX_OPSTATUS FROM USER_INDEXES"
                        + " WHERE INDEX_NAME = ?",
                INDEX);
        assertThat(index)
                .containsEntry("TABLE_NAME", "CASE_SEARCH")
                .containsEntry("ITYP_OWNER", "CTXSYS")
                .containsEntry("ITYP_NAME", "CONTEXT")
                .containsEntry("STATUS", "VALID")
                .containsEntry("DOMIDX_STATUS", "VALID")
                .containsEntry("DOMIDX_OPSTATUS", "VALID");
        Map<String, Object> text = jdbc.queryForMap(
                "SELECT IDX_STATUS, IDX_SYNC_TYPE, IDX_AUTO_OPT_TYPE FROM CTX_USER_INDEXES WHERE IDX_NAME = ?", INDEX);
        assertThat(text)
                .containsEntry("IDX_STATUS", "INDEXED")
                .containsEntry("IDX_SYNC_TYPE", "ON COMMIT")
                .containsEntry("IDX_AUTO_OPT_TYPE", "AUTO_DAILY");
        List<String> objects = jdbc.queryForList(
                "SELECT IXO_CLASS || '=' || IXO_OBJECT FROM CTX_USER_INDEX_OBJECTS WHERE IXO_INDEX_NAME = ?",
                String.class,
                INDEX);
        assertThat(objects)
                .contains(
                        "DATASTORE=DIRECT_DATASTORE",
                        "FILTER=NULL_FILTER",
                        "LEXER=BASIC_LEXER",
                        "WORDLIST=BASIC_WORDLIST",
                        "STOPLIST=BASIC_STOPLIST");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM CTX_USER_INDEX_VALUES WHERE IXV_INDEX_NAME = ?"
                                + " AND IXV_CLASS IN ('LEXER', 'WORDLIST', 'STOPLIST')",
                        Long.class,
                        INDEX))
                .as("no attribute changes Oracle's defaults, and no word is a stop word")
                .isZero();
    }

    @Test
    void theIndexHasNoOptimizerStatisticsAndCaseSearchsAreLocked() {
        assertThat(jdbc.queryForObject(
                        "SELECT STATTYPE_LOCKED FROM USER_TAB_STATISTICS WHERE TABLE_NAME = 'CASE_SEARCH'"
                                + " AND OBJECT_TYPE = 'TABLE'",
                        String.class))
                .isEqualTo("ALL");
        assertThatExceptionOfType(DataAccessException.class)
                .as("the automatic statistics job skips a locked table; a plain gather is refused")
                .isThrownBy(() -> jdbc.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'CASE_SEARCH'); END;"))
                .withMessageContaining("ORA-20005");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(LAST_ANALYZED) FROM USER_INDEXES WHERE INDEX_NAME = ?", Long.class, INDEX))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM USER_USTATS WHERE OBJECT_NAME = ?", Long.class, INDEX))
                .as("no Oracle Text statistics, so CONTAINS is never estimated by running it")
                .isZero();
    }

    @Test
    void theScriptRunsAgainWithoutHarm() throws IOException {
        String script = new ClassPathResource("db/migration/oracle/V21__search_index.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String block = script.substring(
                        0,
                        SLASH_LINE
                                .matcher(script)
                                .results()
                                .findFirst()
                                .orElseThrow()
                                .start())
                .lines()
                .filter(line -> !line.startsWith("--"))
                .reduce("", (sql, line) -> sql + line + "\n");

        jdbc.execute(block);

        assertThat(jdbc.queryForObject(
                        "SELECT IDX_STATUS FROM CTX_USER_INDEXES WHERE IDX_NAME = ?", String.class, INDEX))
                .isEqualTo("INDEXED");
        assertThat(contains("%probe%")).isEqualTo(1);
    }

    @Test
    void theRuntimeUserWritesAndFindsTextAndThePurgeUserDeletesItWithTheirOwnGrants() throws SQLException {
        try (Connection app = asUser(appUser)) {
            app.setAutoCommit(false);
            try (PreparedStatement append = app.prepareStatement(
                    "UPDATE CASE_SEARCH SET SEARCH_TEXT = SEARCH_TEXT || ' wagtail' WHERE CASE_ID = ?")) {
                append.setLong(1, caseId);
                assertThat(append.executeUpdate()).isEqualTo(1);
            }
            assertThat(contains(app, "%wagtail%"))
                    .as("its own uncommitted text")
                    .isEqualTo(1);
            app.commit();
        }
        assertThat(contains("%wagtail%")).as("committed and indexed").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM CTX_USER_PENDING WHERE PND_INDEX_NAME = ?", Long.class, INDEX))
                .as("synchronised by the commit")
                .isZero();
        try (Connection purge = asUser(purgeUser);
                PreparedStatement delete = purge.prepareStatement("DELETE FROM CASE_SEARCH WHERE CASE_ID = ?")) {
            delete.setLong(1, caseId);
            assertThat(delete.executeUpdate()).isEqualTo(1);
        }
        assertThat(contains("%wagtail%")).isZero();
    }

    private Connection asUser(String user) throws SQLException {
        Connection connection = users.login(user);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + users.ownerSchema());
        }
        return connection;
    }

    private int contains(String query) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM CASE_SEARCH WHERE CASE_ID = ? AND CONTAINS(SEARCH_TEXT, ?) > 0",
                Integer.class,
                caseId,
                query);
        return count == null ? 0 : count;
    }

    private int contains(Connection connection, String query) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT COUNT(*) FROM CASE_SEARCH WHERE CASE_ID = ? AND CONTAINS(SEARCH_TEXT, ?) > 0")) {
            select.setLong(1, caseId);
            select.setString(2, query);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
