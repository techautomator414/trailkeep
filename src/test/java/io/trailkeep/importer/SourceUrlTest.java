// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.importer.MappingProblem.Code;
import io.trailkeep.importer.SourceQueryJdbcDao.Source;
import io.trailkeep.importer.SourceQueryValidator.Vendor;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Only the source driver's own scheme with harmless parameters is ever connected to (design §10): a URL naming a
 * class to instantiate or a file to write is a FATAL mapping problem, found without any connection attempt (the hosts
 * are {@code .invalid}, so an attempt could not succeed anyway).
 */
class SourceUrlTest {

    @Test
    void onlyThePostgresqlSchemeWithAllowListedParametersIsAllowed() {
        assertThat(SourceUrl.allowed(Vendor.POSTGRESQL, "jdbc:postgresql://db.invalid:5432/legacy"))
                .isTrue();
        assertThat(SourceUrl.allowed(
                        Vendor.POSTGRESQL,
                        "jdbc:postgresql://db.invalid/legacy?sslmode=verify-full&sslrootcert=/run/ca.pem"
                                + "&readOnly=true"))
                .isTrue();
        assertThat(SourceUrl.allowed(Vendor.POSTGRESQL, "jdbc:postgresql://db.invalid/legacy?loggerLevel=OFF"))
                .as("driver logging may be switched off, and only off")
                .isTrue();
        for (String refused : new String[] {
            "jdbc:h2:mem:legacy",
            "jdbc:postgresql:legacy",
            "jdbc:postgresql://db.invalid/legacy?socketFactory=x.Evil&socketFactoryArg=y",
            "jdbc:postgresql://db.invalid/legacy?sslmode=require&sslfactory=x.Evil",
            "jdbc:postgresql://db.invalid/legacy?sslhostnameverifier=x.Evil",
            "jdbc:postgresql://db.invalid/legacy?sslpasswordcallback=x.Evil",
            "jdbc:postgresql://db.invalid/legacy?authenticationPluginClassName=x.Evil",
            "jdbc:postgresql://db.invalid/legacy?loggerLevel=TRACE&loggerFile=/tmp/x.log",
            "jdbc:postgresql://db.invalid/legacy?SSLMODE=disable",
            "jdbc:postgresql://db.invalid/legacy?loggerLevel=TRACE"
        }) {
            assertThat(SourceUrl.allowed(Vendor.POSTGRESQL, refused))
                    .as(refused)
                    .isFalse();
        }
        assertThat(SourceUrl.allowed(Vendor.ORACLE, "jdbc:postgresql://db.invalid/legacy"))
                .isFalse();
    }

    @Test
    void validateMappingRefusesSuchUrlsAtTheirPlaceBeforeConnecting() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("import.source.url", "jdbc:postgresql://db.invalid/legacy?socketFactory=x.Evil")
                .withProperty("import.source.user", "legacy_reader")
                .withProperty("import.source.password", "unused");
        String mapping = """
                source: { id: legacy1, kind: jdbc, vendor: postgresql, url: "${import.source.url}",
                          user: "${import.source.user}", timezone: UTC }
                entities:
                  case: { query: "SELECT case_id FROM cases", key: case_id, fields: {} }
                """;

        assertThat(new SourceTestFetch(new SourceQueryJdbcDao(), environment)
                        .check(mapping.getBytes(StandardCharsets.UTF_8)))
                .containsExactly(new MappingProblem(Code.SOURCE_URL_NOT_ALLOWED, "/source/url"));
    }

    @Test
    void theDaoRefusesSuchUrlsToo() {
        assertThatThrownBy(() -> new SourceQueryJdbcDao()
                        .open(new Source(
                                "jdbc:postgresql://db.invalid/legacy?loggerFile=/tmp/x.log",
                                "legacy_reader",
                                "unused",
                                Vendor.POSTGRESQL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the source URL names another driver or a parameter outside the allow-list");
    }
}
