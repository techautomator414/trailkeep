// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.MappingProblem.Code;
import io.trailkeep.importer.SourceQueryValidator.Result;
import io.trailkeep.importer.SourceQueryValidator.Vendor;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** The source-query validator of design §4.1 and the security tests of §11 that need no database. */
class SourceQueryValidatorTest {

    @Test
    void plainSelectsWithAllowedFunctionsPass() {
        assertPasses(Vendor.SQLSERVER, "SELECT c.case_id, c.case_no, ISNULL(c.details, '') FROM dbo.cases c");
        assertPasses(Vendor.SQLSERVER, "SELECT content FROM dbo.files WHERE file_id = ?");
        assertPasses(Vendor.ORACLE, "SELECT n.note_id, NVL(n.body, 'none'), TO_CHAR(n.created, 'YYYY') FROM notes n");
        assertPasses(
                Vendor.POSTGRESQL,
                "WITH open_cases AS (SELECT id FROM cases WHERE state = 'Open')"
                        + " SELECT o.id, COALESCE(c.title, 'x') FROM open_cases o JOIN cases c ON c.id = o.id");
        assertPasses(Vendor.POSTGRESQL, "SELECT id FROM a UNION ALL SELECT id FROM b");
        assertPasses(
                Vendor.POSTGRESQL, "SELECT id, (SELECT COUNT(*) FROM notes n WHERE n.case_id = c.id) FROM cases c");

        SourceQueryValidator.ValidatedSourceQuery query =
                Objects.requireNonNull(SourceQueryValidator.validate("SELECT id FROM cases", Vendor.ORACLE)
                        .query());
        assertThat(query.sql()).as("never rewritten").isEqualTo("SELECT id FROM cases");
        assertThat(query.vendor()).isEqualTo(Vendor.ORACLE);
    }

    @Test
    void anythingButOneSelectIsRefused() {
        assertRefused(Vendor.POSTGRESQL, "SELECT 1; DELETE FROM cases", Code.QUERY_NOT_ONE_STATEMENT);
        assertRefused(Vendor.POSTGRESQL, "DELETE FROM cases", Code.QUERY_NOT_SELECT);
        assertRefused(Vendor.ORACLE, "UPDATE cases SET title = 'x'", Code.QUERY_NOT_SELECT);
        assertRefused(Vendor.SQLSERVER, "SELECT * INTO stolen FROM dbo.cases", Code.QUERY_INTO);
        assertRefused(Vendor.POSTGRESQL, "SELECT this is not sql", Code.QUERY_UNPARSEABLE);
    }

    @Test
    void lockingIsRefused() {
        assertRefused(Vendor.POSTGRESQL, "SELECT id FROM cases FOR UPDATE", Code.QUERY_LOCKS);
        assertRefused(Vendor.ORACLE, "SELECT id FROM cases FOR UPDATE NOWAIT", Code.QUERY_LOCKS);
        Result hinted = SourceQueryValidator.validate("SELECT id FROM dbo.cases WITH (UPDLOCK)", Vendor.SQLSERVER);
        assertThat(hinted.query()).isNull();
        assertThat(hinted.problems()).containsAnyOf(Code.QUERY_LOCKS, Code.QUERY_UNPARSEABLE);
        for (String hint : List.of("HOLDLOCK", "XLOCK", "TABLOCKX", "NOLOCK", "READPAST", "READUNCOMMITTED")) {
            String sql = "SELECT id FROM dbo.cases WITH (" + hint + ")";
            assertThat(SourceQueryValidator.validate(sql, Vendor.SQLSERVER).query())
                    .as(sql)
                    .isNull();
        }
    }

    /** A four-part name reaches a linked server; {@code NEXT VALUE FOR} advances a sequence outside any transaction. */
    @Test
    void sqlServerLinkedServersAndSequencesAreRefused() {
        assertRefused(Vendor.SQLSERVER, "SELECT id FROM remote.legacy.dbo.cases", Code.QUERY_DATABASE_LINK);
        assertRefused(Vendor.SQLSERVER, "SELECT id FROM remote.legacy..cases", Code.QUERY_DATABASE_LINK);
        assertRefused(Vendor.SQLSERVER, "SELECT NEXT VALUE FOR dbo.ids", Code.QUERY_FUNCTION_NOT_ALLOWED);
        assertPasses(Vendor.SQLSERVER, "SELECT c.id FROM legacy.dbo.cases c");
    }

    @Test
    void linksTableFunctionsAndUnlistedFunctionsAreRefused() {
        assertRefused(Vendor.ORACLE, "SELECT id FROM cases@legacy_link", Code.QUERY_DATABASE_LINK);
        Result rowset = SourceQueryValidator.validate(
                "SELECT * FROM OPENROWSET('SQLNCLI', 'Server=x;', 'SELECT 1') AS r", Vendor.SQLSERVER);
        assertThat(rowset.query()).isNull();
        assertThat(rowset.problems())
                .containsAnyOf(Code.QUERY_TABLE_FUNCTION, Code.QUERY_FUNCTION_NOT_ALLOWED, Code.QUERY_UNPARSEABLE);
        assertRefused(Vendor.POSTGRESQL, "SELECT pg_read_file('/etc/passwd')", Code.QUERY_FUNCTION_NOT_ALLOWED);
        assertRefused(Vendor.ORACLE, "SELECT UTL_HTTP.REQUEST('http://x') FROM dual", Code.QUERY_FUNCTION_NOT_ALLOWED);
        assertRefused(Vendor.SQLSERVER, "SELECT dbo.fn_secret(id) FROM dbo.cases", Code.QUERY_FUNCTION_NOT_ALLOWED);
        assertRefused(
                Vendor.POSTGRESQL,
                "SELECT id FROM cases WHERE id IN (SELECT lo_import('/x'))",
                Code.QUERY_FUNCTION_NOT_ALLOWED);
    }

    @Test
    void dataModifyingCtesAreRefusedAndReadOnlyCtesPass() {
        assertRefused(
                Vendor.POSTGRESQL,
                "WITH gone AS (DELETE FROM cases RETURNING id) SELECT id FROM gone",
                Code.QUERY_NOT_SELECT);
        assertRefused(
                Vendor.POSTGRESQL,
                "WITH changed AS (UPDATE cases SET title = 'x' RETURNING id) SELECT id FROM changed",
                Code.QUERY_NOT_SELECT);
        assertRefused(
                Vendor.POSTGRESQL,
                "WITH added AS (INSERT INTO cases (id) VALUES (1) RETURNING id) SELECT id FROM added",
                Code.QUERY_NOT_SELECT);
        assertPasses(Vendor.POSTGRESQL, "WITH open AS (SELECT id FROM cases) SELECT id FROM open");
    }

    @Test
    void windowFunctionsAreNameCheckedLikeAnyCall() {
        assertPasses(
                Vendor.POSTGRESQL,
                "SELECT id, ROW_NUMBER() OVER (PARTITION BY case_id ORDER BY changed_at DESC) AS n FROM notes");
        assertPasses(Vendor.SQLSERVER, "SELECT id, SUM(amount) OVER (PARTITION BY case_id) FROM dbo.notes");
        assertRefused(
                Vendor.POSTGRESQL,
                "SELECT pg_read_file('/etc/passwd') OVER (ORDER BY id) FROM cases",
                Code.QUERY_FUNCTION_NOT_ALLOWED);
        assertRefused(
                Vendor.ORACLE,
                "SELECT secret_fn(id) OVER (PARTITION BY id) FROM cases",
                Code.QUERY_FUNCTION_NOT_ALLOWED);
    }

    @Test
    void theAllowListIsPerVendor() {
        assertPasses(Vendor.ORACLE, "SELECT NVL(title, 'x') FROM cases");
        assertRefused(Vendor.POSTGRESQL, "SELECT NVL(title, 'x') FROM cases", Code.QUERY_FUNCTION_NOT_ALLOWED);
        assertPasses(Vendor.SQLSERVER, "SELECT LEN(title) FROM dbo.cases");
        assertRefused(Vendor.ORACLE, "SELECT LEN(title) FROM cases", Code.QUERY_FUNCTION_NOT_ALLOWED);
    }

    /**
     * Oracle calls a parameterless function without parentheses, so {@code PKG.FN} reads like a qualified column (26o):
     * on Oracle every qualifier must be a table, alias, subquery or WITH item of the statement.
     */
    @Test
    void oracleQualifiersMustNameWhatTheStatementReads() {
        for (String call : new String[] {
            "SELECT DBMS_RANDOM.VALUE FROM DUAL",
            "SELECT sys.dbms_random.value FROM DUAL",
            "SELECT c.case_id FROM legacy.cases c WHERE legacy_pkg.flag = 1",
            "SELECT c.case_id FROM legacy.cases c WHERE c.case_id IN (SELECT pkg.next_id FROM DUAL)"
        }) {
            assertRefused(Vendor.ORACLE, call, Code.QUERY_FUNCTION_NOT_ALLOWED);
        }
        assertPasses(
                Vendor.ORACLE,
                "SELECT c.case_id, CASES.state, t.n, legacy.cases.title FROM legacy.cases c"
                        + " JOIN legacy.cases ON c.case_id = cases.case_id, (SELECT 1 n FROM DUAL) t");
        assertPasses(Vendor.ORACLE, "WITH w AS (SELECT case_id FROM legacy.cases) SELECT w.case_id FROM w");
        assertPasses(Vendor.ORACLE, "SELECT \"c\".case_id FROM legacy.cases \"c\"");
        assertPasses(Vendor.POSTGRESQL, "SELECT c.case_id FROM legacy.cases c");
    }

    private static void assertPasses(Vendor vendor, String sql) {
        assertThat(SourceQueryValidator.validate(sql, vendor).problems())
                .as(sql)
                .isEmpty();
    }

    private static void assertRefused(Vendor vendor, String sql, Code code) {
        Result result = SourceQueryValidator.validate(sql, vendor);
        assertThat(result.query()).as(sql).isNull();
        assertThat(result.problems()).as(sql).contains(code);
    }
}
