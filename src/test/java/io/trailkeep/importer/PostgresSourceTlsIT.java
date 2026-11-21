// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.document.TestTls;
import io.trailkeep.importer.MappingProblem.Code;
import io.trailkeep.importer.SourceQueryJdbcDao.Session;
import io.trailkeep.importer.SourceQueryJdbcDao.Source;
import io.trailkeep.importer.SourceQueryValidator.ValidatedSourceQuery;
import io.trailkeep.importer.SourceQueryValidator.Vendor;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A PostgreSQL source over verified TLS, without the test-only escape (#259): the server presents a certificate from a
 * throwaway CA for {@code localhost} and {@code 127.0.0.1}, and the importer reaches it with {@code sslmode=verify-full} and that CA as
 * {@code sslrootcert}. The URL the importer now requires is the one that works, both for {@code validate-mapping}'s
 * test fetch and for the DAO's session, and the session really is encrypted. The same server without the CA, or
 * without TLS in the URL, is refused.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
class PostgresSourceTlsIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final TestTls TLS = TestTls.shared();

    /** The server's key must belong to the server's user, so the entrypoint copies it before starting PostgreSQL. */
    private static final PostgreSQLContainer SOURCE = new PostgreSQLContainer(PostgresTestcontainersConfiguration.IMAGE)
            .withCopyToContainer(Transferable.of(read(TLS.writePem())), "/tls/server.crt")
            .withCopyToContainer(
                    Transferable.of(read(TLS.writePem().resolveSibling("server-key.pem"))), "/tls/server.key")
            .withCreateContainerCmdModifier(command -> command.withEntrypoint(
                    "sh",
                    "-c",
                    "cp /tls/server.crt /tls/server.key /tmp/ && chown postgres /tmp/server.crt /tmp/server.key"
                            + " && chmod 600 /tmp/server.key && exec docker-entrypoint.sh postgres -c ssl=on"
                            + " -c ssl_cert_file=/tmp/server.crt -c ssl_key_file=/tmp/server.key"));

    @BeforeAll
    static void start() {
        SOURCE.start();
    }

    @AfterAll
    static void stop() {
        SOURCE.stop();
    }

    @Test
    void theVerifiedUrlConnectsOverTlsWithoutTheTestEscape() throws SQLException {
        String url = url("?sslmode=verify-full&sslrootcert=" + TLS.caCertificate());
        MockEnvironment environment = account(url);

        assertThat(SourceTls.required(environment)).isTrue();
        assertThat(new SourceTestFetch(new SourceQueryJdbcDao(environment), environment).check(mapping()))
                .as("validate-mapping's test fetch")
                .isEmpty();
        ValidatedSourceQuery query =
                Objects.requireNonNull(SourceQueryValidator.validate("SELECT 1 AS one", Vendor.POSTGRESQL)
                        .query());
        try (Session session = new SourceQueryJdbcDao(environment).open(source(url))) {
            assertThat(new SourceQueryJdbcDao(environment).testFetch(session, query, TIMEOUT))
                    .isEqualTo(1);
        }
        try (Connection connection = DriverManager.getConnection(url, SOURCE.getUsername(), SOURCE.getPassword());
                Statement statement = connection.createStatement();
                ResultSet ssl = statement.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
            assertThat(ssl.next()).isTrue();
            assertThat(ssl.getBoolean(1)).as("the session is encrypted").isTrue();
        }
    }

    @Test
    void theSameServerIsRefusedWithoutTheCaOrWithoutTlsInTheUrl() {
        // verify-full with no sslrootcert looks for ~/.postgresql/root.crt, which does not hold this CA.
        String untrusted = url("?sslmode=verify-full");
        MockEnvironment environment = account(untrusted);
        assertThat(new SourceTestFetch(new SourceQueryJdbcDao(environment), environment).check(mapping()))
                .containsExactly(new MappingProblem(Code.SOURCE_UNAVAILABLE, "/source"));

        String plain = url("");
        MockEnvironment plainEnvironment = account(plain);
        assertThat(new SourceTestFetch(new SourceQueryJdbcDao(plainEnvironment), plainEnvironment).check(mapping()))
                .containsExactly(new MappingProblem(Code.SOURCE_TLS_NOT_VERIFIED, "/source/url"));
        assertThatThrownBy(() -> new SourceQueryJdbcDao(plainEnvironment).open(source(plain)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the source URL does not require verified TLS");
    }

    private static String url(String parameters) {
        return "jdbc:postgresql://" + SOURCE.getHost() + ":" + SOURCE.getMappedPort(5432) + "/"
                + SOURCE.getDatabaseName() + parameters;
    }

    private static MockEnvironment account(String url) {
        return new MockEnvironment()
                .withProperty("import.source.url", url)
                .withProperty("import.source.user", SOURCE.getUsername())
                .withProperty("import.source.password", SOURCE.getPassword());
    }

    private static Source source(String url) {
        return new Source(url, SOURCE.getUsername(), SOURCE.getPassword(), Vendor.POSTGRESQL);
    }

    private static byte[] mapping() {
        return ("source: { kind: jdbc, vendor: postgresql, url: \"${import.source.url}\","
                        + " user: \"${import.source.user}\" }\n"
                        + "entities:\n"
                        + "  case: { query: \"SELECT 1 AS case_id\" }\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
