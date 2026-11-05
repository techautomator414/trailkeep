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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Scans a file with clamd's {@code INSTREAM} command over TCP (BUILD_PLAN §9, §11.1: a small client, no library).
 * The exchange is {@code zINSTREAM\0}, then chunks each prefixed with their length as a 4-byte big-endian integer,
 * then a zero length; clamd answers one {@code \0}-terminated line: {@code stream: OK} or
 * {@code stream: <signature> FOUND}.
 *
 * <p>It fails closed: a refused connection, a timeout, an error reply ({@code ... ERROR}, for example clamd's
 * {@code StreamMaxLength}) or any other reply is a {@link ScannerUnavailableException}, never a clean verdict. Reads
 * time out after {@code read-timeout}; the whole exchange is bounded by {@code scan-timeout}, after which the socket
 * is closed even if a write is blocked.
 */
@Component
class ClamdClient implements VirusScanner {

    /** Bytes per {@code INSTREAM} chunk; far below clamd's default {@code StreamMaxLength}. */
    static final int CHUNK_SIZE = 64 * 1024;

    /** The longest reply read; a verdict line is far shorter. */
    private static final int MAX_REPLY = 1024;

    private static final byte[] INSTREAM = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);

    private static final Pattern FOUND = Pattern.compile("stream: (\\p{Graph}[\\p{Graph} ]*) FOUND");

    /** Closes the socket of an exchange that outlives its deadline. Virtual threads, so it never holds the JVM. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("clamd-watchdog").factory());

    private final AttachmentProperties.Clamd clamd;

    ClamdClient(AttachmentProperties properties) {
        this.clamd = properties.clamd();
    }

    @Override
    public ScanVerdict scan(Path file) {
        try (Socket socket = new Socket()) {
            ScheduledFuture<?> deadline = WATCHDOG.schedule(
                    () -> closeQuietly(socket), clamd.scanTimeout().toMillis(), TimeUnit.MILLISECONDS);
            try {
                socket.connect(
                        new InetSocketAddress(clamd.host(), clamd.port()),
                        Math.toIntExact(clamd.connectTimeout().toMillis()));
                socket.setSoTimeout(Math.toIntExact(clamd.readTimeout().toMillis()));
                stream(file, socket.getOutputStream());
                return verdict(readReply(socket.getInputStream()));
            } finally {
                deadline.cancel(false);
            }
        } catch (SocketTimeoutException e) {
            throw new ScannerUnavailableException("clamd timed out", e);
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
