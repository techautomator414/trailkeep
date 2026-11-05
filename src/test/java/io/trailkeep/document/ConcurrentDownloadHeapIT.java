// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestBrowser;
import io.trailkeep.user.TeamRole;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * WP-14 acceptance: eight concurrent 25 MB downloads on PostgreSQL succeed with {@code -Xmx256m}. Tagged {@code heap},
 * so only Failsafe's {@code heap-256m} execution runs it, in a JVM of its own with a 256 MiB heap and
 * {@code -XX:+ExitOnOutOfMemoryError}; the test first proves the limit is in force. Eight files (200 MB in all, eight
 * times what fits next to the application in that heap) are written through the chunk DAO from generated streams,
 * never held whole. A dev-realm user signs in on a real Tomcat, eight downloads are opened at once and all eight
 * responses must have started (each holding a download slot and a database connection) before any body is read;
 * then every body is read in full and its SHA-256 must match. The pool is 12 here (the test profile's 4 cannot hold
 * eight streams), so eight streams leave four connections for sign-in, the case checks and the session store.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("heap")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"app.attachments.max-concurrent-downloads=8", "spring.datasource.hikari.maximum-pool-size=12"})
class ConcurrentDownloadHeapIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";
    private static final int DOWNLOADS = 8;
    private static final long SIZE = 25L * 1024 * 1024;
    private static final long HEAP_LIMIT = 256L * 1024 * 1024;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AttachmentContentJdbcDao content;

    private @Nullable AttachmentFixture fixture;

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        KEYCLOAK.register(registry);
    }

    @AfterEach
    void remove() {
        if (fixture != null) {
            fixture.remove();
        }
    }

    @Test
    void eightConcurrent25MegabyteDownloadsSucceedIn256Megabytes() throws Exception {
        assertThat(Runtime.getRuntime().maxMemory())
                .as("run by the heap-256m execution")
                .isLessThanOrEqualTo(HEAP_LIMIT);
        TestBrowser browser = new TestBrowser();
        browser.login(KEYCLOAK.app("/"), "alpha.analyst");
        long analyst = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_ANALYST));
        AttachmentFixture seeded = new AttachmentFixture(jdbc, content, 966_000L);
        fixture = seeded;
        new CaseSeed(jdbc).member(analyst, seeded.team, TeamRole.ANALYST);
        List<Long> ids = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < DOWNLOADS; i++) {
            String sha = sha256(generated(i));
            hashes.add(sha);
            try (InputStream bytes = generated(i)) {
                ids.add(seeded.attachment(
                        seeded.number, "synthetic-" + i + ".pdf", ScanStatus.CLEAN, SIZE, sha, bytes));
            }
        }

        String session = Objects.requireNonNull(browser.cookie("SESSION"));
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        List<CompletableFuture<HttpResponse<InputStream>>> opened = new ArrayList<>();
        for (long id : ids) {
            opened.add(client.sendAsync(
                    HttpRequest.newBuilder(URI.create(KEYCLOAK.app("/cases/" + seeded.number + "/attachments/" + id)))
                            .header("Cookie", "SESSION=" + session)
                            .timeout(Duration.ofMinutes(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream()));
        }
        // Every response has started before any body is read, so all eight streams are open at once.
        CompletableFuture.allOf(opened.toArray(CompletableFuture[]::new)).get(2, TimeUnit.MINUTES);
        ExecutorService readers = Executors.newFixedThreadPool(DOWNLOADS);
        try {
            List<Future<String>> read = new ArrayList<>();
            for (CompletableFuture<HttpResponse<InputStream>> response : opened) {
                HttpResponse<InputStream> started = response.get();
                assertThat(started.statusCode()).isEqualTo(200);
                assertThat(started.headers().firstValueAsLong("Content-Length")).hasValue(SIZE);
                read.add(readers.submit(() -> sha256(started.body())));
            }
            for (int i = 0; i < DOWNLOADS; i++) {
                assertThat(read.get(i).get(5, TimeUnit.MINUTES))
                        .as("download " + i)
                        .isEqualTo(hashes.get(i));
            }
        } finally {
            readers.shutdownNow();
        }
    }

    /** The SHA-256 of a stream, read to its end in 64 KiB steps and closed. */
    private static String sha256(InputStream in) throws IOException {
        try (DigestInputStream digesting = new DigestInputStream(in, newDigest())) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int n = digesting.read(buffer); n >= 0; n = digesting.read(buffer)) {
                total += n;
            }
            assertThat(total).isEqualTo(SIZE);
            return HexFormat.of().formatHex(digesting.getMessageDigest().digest());
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Synthetic pseudo-random content of {@link #SIZE} bytes, the same for the same index, generated as it is read. */
    private static InputStream generated(int index) {
        SplittableRandom random = new SplittableRandom(20_261_105L + index);
        return new InputStream() {
            private long left = SIZE;

            @Override
            public int read() {
                if (left == 0) {
                    return -1;
                }
                left--;
                return random.nextInt(256);
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (left == 0) {
                    return -1;
                }
                int n = (int) Math.min(length, left);
                for (int i = 0; i < n; i++) {
                    buffer[offset + i] = (byte) random.nextInt(256);
                }
                left -= n;
                return n;
            }
        };
    }
}
