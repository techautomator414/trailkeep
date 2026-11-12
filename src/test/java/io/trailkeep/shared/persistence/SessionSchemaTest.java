// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@code app.db.schema} as every pooled connection's default schema: the statement per vendor, nothing at all when
 * the schema is blank, a refusal for anything but a plain identifier (here and in {@link DatabaseProperties}'
 * validation, so it cannot reach the SQL), and a refusal to overwrite a connection-init statement set elsewhere.
 */
class SessionSchemaTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void eachVendorGetsItsOwnStatement() {
        assertThat(SessionSchema.initSql(DatabaseVendor.ORACLE, "TRAILKEEP_OWNER", null))
                .isEqualTo("ALTER SESSION SET CURRENT_SCHEMA = TRAILKEEP_OWNER");
        assertThat(SessionSchema.initSql(DatabaseVendor.POSTGRESQL, "trailkeep", " "))
                .isEqualTo("SET search_path TO trailkeep");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER; DROP TABLE CASE_RECORD", "a b", "\"Owner\"", "1OWNER", "OWNER--", "own.er"})
    void onlyPlainIdentifiersAreAccepted(String schema) {
        assertThatIllegalStateException()
                .isThrownBy(() -> SessionSchema.initSql(DatabaseVendor.ORACLE, schema, null))
                .withMessageContaining("plain identifier");
        assertThat(VALIDATOR.validate(properties(schema))).as(schema).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRAILKEEP_OWNER", " TRAILKEEP_OWNER ", "public", "", " "})
    void plainOrBlankSchemasPassValidation(String schema) {
        assertThat(VALIDATOR.validate(properties(schema))).as(schema).isEmpty();
    }

    @Test
    void anotherConnectionInitStatementIsNotOverwritten() {
        assertThatIllegalStateException()
                .isThrownBy(() -> SessionSchema.initSql(DatabaseVendor.ORACLE, "OWNER", "SELECT 1 FROM dual"))
                .withMessageContaining("connection-init-sql is already set");
    }

    @Test
    void thePoolGetsTheStatementOnlyWhenTheSchemaIsSet() {
        try (HikariDataSource owned = new HikariDataSource();
                HikariDataSource unset = new HikariDataSource();
                HikariDataSource blank = new HikariDataSource()) {
            processor("oracle", " TRAILKEEP_OWNER ").postProcessAfterInitialization(owned, "dataSource");
            processor("oracle", null).postProcessAfterInitialization(unset, "dataSource");
            processor("postgresql", "  ").postProcessAfterInitialization(blank, "dataSource");

            assertThat(owned.getConnectionInitSql()).isEqualTo("ALTER SESSION SET CURRENT_SCHEMA = TRAILKEEP_OWNER");
            assertThat(unset.getConnectionInitSql()).isNull();
            assertThat(blank.getConnectionInitSql()).isNull();
        }
    }

    @Test
    void otherBeansAreLeftAlone() {
        Object bean = new Object();
        assertThat(processor("oracle", "OWNER").postProcessAfterInitialization(bean, "other"))
                .isSameAs(bean);
    }

    private static SessionSchema processor(String vendor, @Nullable String schema) {
        MockEnvironment environment = new MockEnvironment().withProperty("app.db.vendor", vendor);
        if (schema != null) {
            environment.setProperty("app.db.schema", schema);
        }
        return new SessionSchema(environment);
    }

    private static DatabaseProperties properties(String schema) {
        return new DatabaseProperties(
                DatabaseVendor.ORACLE, schema, "trailkeep_app", "trailkeep_purge", "trailkeep_import");
    }
}
