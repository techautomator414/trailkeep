// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Scans a file with clamd's {@code INSTREAM} command over TLS (BUILD_PLAN §9, §11.1: a small client, no library;
 * ADR-0012: clamd sits behind a TLS-terminating sidecar). The socket comes from the Spring Boot SSL bundle named by
 * {@code app.attachments.clamd.ssl-bundle}, so trust material is configuration; only TLS 1.3 and 1.2 are offered and
 * the server certificate must name the configured host. There is no plaintext path.
 *
 * <p>The exchange is {@code zINSTREAM\0}, then chunks each prefixed with their length as a 4-byte big-endian
 * integer, then a zero length; clamd answers one {@code \0}-terminated line: {@code stream: OK} or
 * {@code stream: <signature> FOUND}.
 *
 * <p>It fails closed: a refused connection, a timeout, an error reply ({@code ... ERROR}, for example clamd's
 * {@code StreamMaxLength}) or any other reply is a {@link ScannerUnavailableException}, never a clean verdict. Reads
 * time out after {@code read-timeout}; the whole exchange is bounded by {@code scan-timeout}, after which the socket
 * is closed even if a write is blocked. A failed handshake (an untrusted or misnamed certificate, a plaintext peer) is
 * the scanner being unavailable too.
 *
 * <p>It exists only while {@code app.attachments.scanning} is {@code clamd} (ADR-0013): in {@code external} mode
 * neither the host nor the SSL bundle is needed, and nothing connects to a scanner.
 */
@Component
@Conditional(ClamdScanningCondition.class)
final class ClamdClient implements VirusScanner {

    /** Bytes per {@code INSTREAM} chunk; far below clamd's default {@code StreamMaxLength}. */
    static final int CHUNK_SIZE = 64 * 1024;

    /** The longest reply read; a verdict line is far shorter. */
    private static final int MAX_REPLY = 1024;

    private static final byte[] INSTREAM = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] VERSION = "zVERSION\0".getBytes(StandardCharsets.US_ASCII);

    /** The whole {@code VERSION} exchange's limit, in seconds; see {@link #version()}. */
    static final int STATUS_SECONDS = 3;

    private static final Pattern FOUND = Pattern.compile("stream: (\\p{Graph}[\\p{Graph} ]*) FOUND");

    /** clamd's reply when a stream passes its {@code StreamMaxLength} (R13): a file it will not scan, not an outage. */
    static final String SIZE_LIMIT = "INSTREAM size limit exceeded. ERROR";

    /** Closes the socket of an exchange that outlives its deadline. Virtual threads, so it never holds the JVM. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("clamd-watchdog").factory());

    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private final AttachmentProperties.Clamd clamd;
    private final String host;
    private final SSLSocketFactory sockets;

    /**
     * Resolves the SSL bundle at startup, so a missing or unreadable bundle fails the application, not an upload.
     *
     * @param properties the attachment settings
     * @param sslBundles Spring Boot's SSL bundles
     * @throws IllegalStateException if no clamd host is configured
     */
    ClamdClient(AttachmentProperties properties, SslBundles sslBundles) {
        this.clamd = properties.clamd();
        String configured = clamd.host();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("app.attachments.clamd.host is required while scanning is clamd");
        }
        this.host = configured;
        this.sockets =
                sslBundles.getBundle(clamd.sslBundle()).createSslContext().getSocketFactory();
    }

    @Override
    public ScanVerdict scan(Path file) {
        return verdict(exchange(out -> stream(file, out), clamd.readTimeout(), clamd.scanTimeout()));
    }

    /**
     * The engine's version line from clamd's {@code VERSION} command, for the System screen (WP-25): the ClamAV
     * release, the signature database version and its date, such as {@code ClamAV 1.4.3/27456/Mon Nov 10 08:00:00
     * 2026}. It fails closed like a scan, but within {@value #STATUS_SECONDS} seconds for the whole exchange instead of
     * a scan's read and scan timeouts: the screen asks on every page load, and a clamd that accepts and never answers
     * must show as unavailable at once, not hold the page for the 30 s a large upload's scan is allowed.
     *
     * @return the line
     * @throws ScannerUnavailableException if clamd cannot be reached or answers anything else
     */
    String version() {
        Duration bound = Duration.ofSeconds(STATUS_SECONDS);
        String reply = exchange(
                out -> {
                    out.write(VERSION);
                    out.flush();
                },
                bound,
                bound);
        if (!reply.startsWith("ClamAV ")) {
            throw new ScannerUnavailableException("clamd gave no version");
        }
        return reply;
    }

    /** What one exchange sends after the TLS handshake. */
    @FunctionalInterface
    private interface Request {
        void write(OutputStream out) throws IOException;
    }

    /**
     * Connects over TLS, sends one request and reads clamd's one reply line.
     *
     * @param readTimeout how long one read may block
     * @param limit how long the whole exchange may take; the socket is closed after that
     */
    private String exchange(Request request, Duration readTimeout, Duration limit) {
        try (Socket unconnected = sockets.createSocket()) {
            if (!(unconnected instanceof SSLSocket socket)) {
                throw new IllegalStateException("an SSL socket factory returned a plain socket");
            }
            SSLParameters tls = socket.getSSLParameters();
            tls.setProtocols(PROTOCOLS);
            tls.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(tls);
            ScheduledFuture<?> deadline =
                    WATCHDOG.schedule(() -> closeQuietly(socket), limit.toMillis(), TimeUnit.MILLISECONDS);
            try {
                socket.connect(
                        new InetSocketAddress(host, clamd.port()),
                        Math.toIntExact(clamd.connectTimeout().toMillis()));
                socket.setSoTimeout(Math.toIntExact(readTimeout.toMillis()));
                socket.startHandshake();
                request.write(socket.getOutputStream());
                return readReply(socket.getInputStream());
            } finally {
                deadline.cancel(false);
            }
        } catch (SocketTimeoutException e) {
            throw new ScannerUnavailableException("clamd timed out", e);
        } catch (SSLException e) {
            throw new ScannerUnavailableException("TLS to clamd failed", e);
        } catch (IOException e) {
            throw new ScannerUnavailableException("clamd unreachable or the exchange failed", e);
        }
    }

    private static void stream(Path file, OutputStream socketOut) throws IOException {
        DataOutputStream out = new DataOutputStream(socketOut);
        out.write(INSTREAM);
        byte[] buffer = new byte[CHUNK_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.readNBytes(buffer, 0, CHUNK_SIZE)) > 0) {
                out.writeInt(n);
                out.write(buffer, 0, n);
            }
        }
        out.writeInt(0);
        out.flush();
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        for (int b = in.read(); b != 0; b = in.read()) {
            if (b < 0 || reply.size() == MAX_REPLY) {
                throw new ScannerUnavailableException("clamd reply unterminated or too long");
            }
            reply.write(b);
        }
        return reply.toString(StandardCharsets.US_ASCII);
    }

    /**
     * Turns clamd's reply line into a verdict.
     *
     * @param reply the line, without its terminator
     * @return clean or quarantined
     * @throws ScannerUnavailableException for an error or any other reply
     */
    static ScanVerdict verdict(String reply) {
        if ("stream: OK".equals(reply)) {
            return ScanVerdict.clean();
        }
        Matcher found = FOUND.matcher(reply);
        if (found.matches()) {
            String signature = found.group(1).strip();
            return ScanVerdict.found(signature.substring(0, Math.min(signature.length(), ScanVerdict.MAX_SIGNATURE)));
        }
        if (SIZE_LIMIT.equals(reply)) {
            throw new ScanSizeExceededException();
        }
        throw new ScannerUnavailableException("clamd gave no verdict");
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The exchange is already failing; its own IOException reports it.
        }
    }
}
