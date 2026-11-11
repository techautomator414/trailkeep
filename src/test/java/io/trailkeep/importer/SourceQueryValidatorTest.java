// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.MappingProblem.Code;
import io.trailkeep.importer.SourceQueryValidator.Result;
import io.trailkeep.importer.SourceQueryValidator.Vendor;
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
    void theAllowListIsPerVendor() {
        assertPasses(Vendor.ORACLE, "SELECT NVL(title, 'x') FROM cases");
        assertRefused(Vendor.POSTGRESQL, "SELECT NVL(title, 'x') FROM cases", Code.QUERY_FUNCTION_NOT_ALLOWED);
        assertPasses(Vendor.SQLSERVER, "SELECT LEN(title) FROM dbo.cases");
        assertRefused(Vendor.ORACLE, "SELECT LEN(title) FROM cases", Code.QUERY_FUNCTION_NOT_ALLOWED);
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
