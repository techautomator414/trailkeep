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
 * are {@code .invalid}, so an attempt could not succeed anyway). So is a URL that does not require verified TLS
 * (ADR-0014 rule 2, #259), unless a test profile allows plain TCP.
 */
class SourceUrlTest {

    @Test
    void onlyThePostgresqlSchemeWithAllowListedParametersIsAllowed() {
        assertThat(SourceUrl.allowed(Vendor.POSTGRESQL, "jdbc:postgresql://db.invalid:5432/legacy?sslmode=verify-full"))
                .isTrue();
        assertThat(SourceUrl.allowed(
                        Vendor.POSTGRESQL,
                        "jdbc:postgresql://db.invalid/legacy?sslmode=verify-full&sslrootcert=/run/ca.pem"
                                + "&readOnly=true"))
                .isTrue();
        assertThat(SourceUrl.allowed(
                        Vendor.POSTGRESQL, "jdbc:postgresql://db.invalid/legacy?sslmode=verify-full&loggerLevel=OFF"))
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

    /**
     * An Oracle URL is an Easy Connect address with TLS and timeout parameters only (26o): no TNS descriptor, whose
     * SECURITY section names wallet directories, no TNS alias, which needs a {@code tnsnames.ora}, and no property that
     * loads a provider class, reads a configuration file or writes a trace.
     */
    @Test
    void onlyOracleEasyConnectWithAllowListedParametersIsAllowed() {
        for (String allowed : new String[] {
            "jdbc:oracle:thin:@db.invalid:1521/legacy",
            "jdbc:oracle:thin:@//db.invalid/legacy.example",
            "jdbc:oracle:thin:@tcps://db.invalid:2484/legacy?ssl_server_dn_match=true"
                    + "&javax.net.ssl.trustStore=/run/ca.p12&javax.net.ssl.trustStoreType=PKCS12&connect_timeout=10"
        }) {
            assertThat(SourceUrl.allowed(Vendor.ORACLE, allowed)).as(allowed).isTrue();
        }
        for (String refused : new String[] {
            "jdbc:oracle:oci:@legacy",
            "jdbc:oracle:thin:@legacy_alias",
            "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db.invalid)(PORT=1521))"
                    + "(CONNECT_DATA=(SERVICE_NAME=legacy)))",
            "jdbc:oracle:thin:@db.invalid:1521/legacy?oracle.jdbc.provider.accessToken=x.Evil",
            "jdbc:oracle:thin:@db.invalid:1521/legacy?oracle.jdbc.config.file=/tmp/x.properties",
            "jdbc:oracle:thin:@db.invalid:1521/legacy?oracle.jdbc.Trace=true",
            "jdbc:oracle:thin:@db.invalid:1521/legacy?wallet_location=/tmp/wallet",
            "jdbc:oracle:thin:@db.invalid:1521/legacy?TNS_ADMIN=/tmp",
            "jdbc:oracle:thin:@tcps://db.invalid:2484/legacy?javax.net.ssl.trustStore=/run/ca.p12"
                    + "&javax.net.ssl.trustStorePassword=unused",
            "jdbc:oracle:thin:@db.invalid:1521/legacy/../x"
        }) {
            assertThat(SourceUrl.allowed(Vendor.ORACLE, refused)).as(refused).isFalse();
        }
    }

    /**
     * A SQL Server URL is {@code host[\instance][:port]} with database, TLS and timeout properties only (26p): no
     * property that instantiates a class, loads native or Kerberos code, reads a key store or carries a secret; {@code
     * trustServerCertificate} only as {@code false}; names compared case-insensitively, as the driver does, and no
     * braces, which could hide a {@code ;}.
     */
    @Test
    void onlySqlServerAddressesWithAllowListedPropertiesAreAllowed() {
        for (String allowed : new String[] {
            "jdbc:sqlserver://db.invalid:1433;databaseName=legacy",
            "jdbc:sqlserver://db.invalid\\LEGACY;databaseName=legacy;",
            "jdbc:sqlserver://db.invalid;databaseName=legacy;encrypt=strict;hostNameInCertificate=db.invalid"
                    + ";trustServerCertificate=false;serverCertificate=/run/ca.pem;ApplicationIntent=ReadOnly"
                    + ";loginTimeout=15",
            "jdbc:sqlserver://db.invalid;DATABASENAME=legacy;Encrypt=true;trustStore=/run/ca.p12;trustStoreType=PKCS12"
        }) {
            assertThat(SourceUrl.allowed(Vendor.SQLSERVER, allowed)).as(allowed).isTrue();
        }
        for (String refused : new String[] {
            "jdbc:sqlserver://;serverName=db.invalid",
            "jdbc:jtds:sqlserver://db.invalid/legacy",
            "jdbc:sqlserver://db.invalid;socketFactoryClass=x.Evil;socketFactoryConstructorArg=y",
            "jdbc:sqlserver://db.invalid;encrypt=true;trustManagerClass=x.Evil",
            "jdbc:sqlserver://db.invalid;accessTokenCallbackClass=x.Evil",
            "jdbc:sqlserver://db.invalid;integratedSecurity=true",
            "jdbc:sqlserver://db.invalid;authenticationScheme=JavaKerberos;jaasConfigurationName=x",
            "jdbc:sqlserver://db.invalid;columnEncryptionSetting=Enabled;keyStoreAuthentication=JavaKeyStorePassword"
                    + ";keyStoreLocation=/tmp/x.jks",
            "jdbc:sqlserver://db.invalid;trustServerCertificate=true",
            "jdbc:sqlserver://db.invalid;TRUSTSERVERCERTIFICATE=yes",
            "jdbc:sqlserver://db.invalid;trustStore=/run/ca.p12;trustStorePassword=unused",
            "jdbc:sqlserver://db.invalid;user=sa;password=unused",
            "jdbc:sqlserver://db.invalid;databaseName={legacy;socketFactoryClass=x.Evil}",
            "jdbc:sqlserver://db.invalid;=x",
            "jdbc:sqlserver://db.invalid;encrypt",
            "jdbc:sqlserver://db.invalid/legacy"
        }) {
            assertThat(SourceUrl.allowed(Vendor.SQLSERVER, refused)).as(refused).isFalse();
        }
        assertThat(SourceUrl.allowed(Vendor.POSTGRESQL, "jdbc:sqlserver://db.invalid"))
                .isFalse();
    }

    @Test
    void postgresqlMustVerifyTheCertificateAndTheHostName() {
        assertThat(SourceUrl.verifiesTls(
                        Vendor.POSTGRESQL,
                        "jdbc:postgresql://db.invalid/legacy?sslmode=verify-full&sslrootcert=/run/ca.pem"))
                .isTrue();
        assertThat(SourceUrl.verifiesTls(
                        Vendor.POSTGRESQL, "jdbc:postgresql://db.invalid/legacy?ssl=true&sslmode=verify-full"))
                .isTrue();
        for (String refused : new String[] {
            "jdbc:postgresql://db.invalid:5432/legacy",
            "jdbc:postgresql://db.invalid/legacy?readOnly=true",
            "jdbc:postgresql://db.invalid/legacy?ssl=true",
            "jdbc:postgresql://db.invalid/legacy?sslmode=disable",
            "jdbc:postgresql://db.invalid/legacy?sslmode=require",
            "jdbc:postgresql://db.invalid/legacy?sslmode=verify-ca",
            "jdbc:postgresql://db.invalid/legacy?sslmode=verify-full&sslmode=disable",
            "jdbc:postgresql://db.invalid/legacy?sslmode=verify-full&ssl=false",
            "jdbc:postgresql://db.invalid/legacy?sslmode=verify%2Dfull",
            "jdbc:postgresql://db.invalid/legacy?sslmode"
        }) {
            assertThat(SourceUrl.verifiesTls(Vendor.POSTGRESQL, refused))
                    .as(refused)
                    .isFalse();
        }
    }

    @Test
    void oracleMustUseTcpsAndMatchTheServerName() {
        for (String verified : new String[] {
            "jdbc:oracle:thin:@tcps://db.invalid:2484/legacy?ssl_server_dn_match=true",
            "jdbc:oracle:thin:@tcps://db.invalid:2484/legacy?oracle.net.ssl_server_dn_match=TRUE"
                    + "&javax.net.ssl.trustStore=/run/ca.p12&javax.net.ssl.trustStoreType=PKCS12"
        }) {
            assertThat(SourceUrl.verifiesTls(Vendor.ORACLE, verified))
                    .as(verified)
                    .isTrue();
        }
        for (String refused : new String[] {
            "jdbc:oracle:thin:@db.invalid:1521/legacy",
            "jdbc:oracle:thin:@//db.invalid:1521/legacy?ssl_server_dn_match=true",
            "jdbc:oracle:thin:@tcp://db.invalid:1521/legacy?ssl_server_dn_match=true",
            "jdbc:oracle:thin:@tcps://db.invalid:2484/legacy",
            "jdbc:oracle:thin:@tcps://db.invalid:2484/legacy?connect_timeout=10",
            "jdbc:oracle:thin:@tcps://db.invalid:2484/legacy?ssl_server_dn_match=false",
            "jdbc:oracle:thin:@tcps://db.invalid:2484/legacy?ssl_server_dn_match=true"
                    + "&oracle.net.ssl_server_dn_match=false"
        }) {
            assertThat(SourceUrl.verifiesTls(Vendor.ORACLE, refused))
                    .as(refused)
                    .isFalse();
        }
    }

    @Test
    void sqlServerMustEncryptWithoutTrustingAnyCertificate() {
        for (String verified : new String[] {
            "jdbc:sqlserver://db.invalid:1433;databaseName=legacy;encrypt=true",
            "jdbc:sqlserver://db.invalid;databaseName=legacy;Encrypt=STRICT;hostNameInCertificate=db.invalid",
            "jdbc:sqlserver://db.invalid;encrypt=true;trustServerCertificate=false;serverCertificate=/run/ca.pem"
        }) {
            assertThat(SourceUrl.verifiesTls(Vendor.SQLSERVER, verified))
                    .as(verified)
                    .isTrue();
        }
        for (String refused : new String[] {
            "jdbc:sqlserver://db.invalid:1433;databaseName=legacy",
            "jdbc:sqlserver://db.invalid;databaseName=legacy;encrypt=false",
            "jdbc:sqlserver://db.invalid;databaseName=legacy;encrypt=optional",
            "jdbc:sqlserver://db.invalid;encrypt=true;encrypt=false",
            "jdbc:sqlserver://db.invalid;encrypt"
        }) {
            assertThat(SourceUrl.verifiesTls(Vendor.SQLSERVER, refused))
                    .as(refused)
                    .isFalse();
        }
        // trustServerCertificate=true never gets as far: the allow-list refuses it.
        assertThat(SourceUrl.allowed(
                        Vendor.SQLSERVER, "jdbc:sqlserver://db.invalid;encrypt=true;trustServerCertificate=true"))
                .isFalse();
    }

    /** Each vendor's URL without verified TLS is refused at its place, before any connection (#259). */
    @Test
    void validateMappingRefusesUrlsWithoutVerifiedTlsForEachVendor() {
        String[][] cases = {
            {"postgresql", "jdbc:postgresql://db.invalid:5432/legacy?sslmode=require"},
            {"oracle", "jdbc:oracle:thin:@db.invalid:1521/legacy"},
            {"sqlserver", "jdbc:sqlserver://db.invalid:1433;databaseName=legacy;encrypt=false"}
        };
        for (String[] vendorAndUrl : cases) {
            MockEnvironment environment = account(vendorAndUrl[1]);

            assertThat(new SourceTestFetch(new SourceQueryJdbcDao(environment), environment)
                            .check(mapping(vendorAndUrl[0])))
                    .as(vendorAndUrl[0])
                    .containsExactly(new MappingProblem(Code.SOURCE_TLS_NOT_VERIFIED, "/source/url"));
        }
    }

    @Test
    void theDaoRefusesUrlsWithoutVerifiedTlsToo() {
        assertThatThrownBy(() -> new SourceQueryJdbcDao(new MockEnvironment())
                        .open(new Source(
                                "jdbc:postgresql://db.invalid/legacy", "legacy_reader", "unused", Vendor.POSTGRESQL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the source URL does not require verified TLS");
    }

    /**
     * The test-only escape for the container ITs: honoured in the test profiles, and fatal anywhere else, the {@code
     * prod} importer Job above all, where the DAO's construction (and so the context's startup) fails.
     */
    @Test
    void plainTcpIsAllowedOnlyInTestProfilesAndNeverWithProd() {
        MockEnvironment test = plaintext("test");
        assertThat(SourceTls.required(test)).isFalse();
        assertThat(SourceTls.required(plaintext("oracle-test", "importer"))).isFalse();
        assertThat(SourceTls.required(new MockEnvironment())).isTrue();

        for (MockEnvironment refused :
                new MockEnvironment[] {plaintext("prod", "importer"), plaintext("prod", "test"), plaintext("importer")
                }) {
            assertThatThrownBy(() -> new SourceQueryJdbcDao(refused))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(SourceTls.PLAINTEXT);
        }

        MockEnvironment environment = account("jdbc:postgresql://127.0.0.1:9/legacy");
        environment.setActiveProfiles("test");
        environment.setProperty(SourceTls.PLAINTEXT, "true");
        assertThat(new SourceTestFetch(new SourceQueryJdbcDao(environment), environment).check(mapping("postgresql")))
                .as("past the TLS check to the connection, which nothing on port 9 accepts")
                .containsExactly(new MappingProblem(Code.SOURCE_UNAVAILABLE, "/source"));
    }

    private static MockEnvironment plaintext(String... profiles) {
        MockEnvironment environment = new MockEnvironment().withProperty(SourceTls.PLAINTEXT, "true");
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static MockEnvironment account(String url) {
        return new MockEnvironment()
                .withProperty("import.source.url", url)
                .withProperty("import.source.user", "legacy_reader")
                .withProperty("import.source.password", "unused");
    }

    private static byte[] mapping(String vendor) {
        return ("source: { id: legacy1, kind: jdbc, vendor: " + vendor + ", url: \"${import.source.url}\",\n"
                        + "          user: \"${import.source.user}\", timezone: UTC }\n"
                        + "entities:\n"
                        + "  case: { query: \"SELECT case_id FROM cases\", key: case_id, fields: {} }\n")
                .getBytes(StandardCharsets.UTF_8);
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

        assertThat(new SourceTestFetch(new SourceQueryJdbcDao(environment), environment)
                        .check(mapping.getBytes(StandardCharsets.UTF_8)))
                .containsExactly(new MappingProblem(Code.SOURCE_URL_NOT_ALLOWED, "/source/url"));
    }

    @Test
    void theDaoRefusesSuchUrlsToo() {
        assertThatThrownBy(() -> new SourceQueryJdbcDao(new MockEnvironment())
                        .open(new Source(
                                "jdbc:postgresql://db.invalid/legacy?loggerFile=/tmp/x.log",
                                "legacy_reader",
                                "unused",
                                Vendor.POSTGRESQL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the source URL names another driver or a parameter outside the allow-list");
    }
}
