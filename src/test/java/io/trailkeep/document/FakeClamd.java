// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * An in-process clamd that speaks {@code INSTREAM} on a loopback port, for one connection at a time. It records the
 * command, each chunk's length and the reassembled bytes, then answers what the test chose; a {@code null} answer
 * keeps the connection open without replying, so the client's read times out.
 */
final class FakeClamd implements AutoCloseable {

    private final ServerSocket server;
    private final Thread thread;
    private final Function<byte[], byte @Nullable []> answer;

    /** The command each connection sent. */
    final List<String> commands = new CopyOnWriteArrayList<>();

    /** The chunk lengths received, in order, excluding the terminating zero. */
    final List<Integer> chunkLengths = new CopyOnWriteArrayList<>();

    /** The bytes each connection streamed. */
    final List<byte[]> received = new CopyOnWriteArrayList<>();

    private FakeClamd(Function<byte[], byte @Nullable []> answer) throws IOException {
        this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.answer = answer;
        this.thread = Thread.ofVirtual().start(this::serve);
    }

    /**
     * Starts a fake whose answer depends on the bytes received.
     *
     * @param answer the reply, without its terminating {@code \0}, or {@code null} for none
     * @return the running fake
     */
    static FakeClamd answering(Function<byte[], @Nullable String> answer) {
        try {
            return new FakeClamd(bytes -> {
                String reply = answer.apply(bytes);
                return reply == null ? null : (reply + "\0").getBytes(StandardCharsets.US_ASCII);
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Starts a fake that answers with raw bytes, terminator included or not.
     *
     * @param reply the exact bytes sent back
     * @return the running fake
     */
    static FakeClamd rawReply(byte[] reply) {
        try {
            return new FakeClamd(bytes -> reply);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The port the fake listens on.
     *
     * @return the port
     */
    int port() {
        return server.getLocalPort();
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                ByteArrayOutputStream command = new ByteArrayOutputStream();
                for (int b = in.read(); b > 0; b = in.read()) {
                    command.write(b);
                }
                commands.add(command.toString(StandardCharsets.US_ASCII));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                for (int length = in.readInt(); length > 0; length = in.readInt()) {
                    chunkLengths.add(length);
                    bytes.write(in.readNBytes(length));
                }
                received.add(bytes.toByteArray());
                byte[] reply = answer.apply(bytes.toByteArray());
                if (reply == null) {
                    in.read(); // Never answers: blocks until the client gives up and closes.
                } else {
                    socket.getOutputStream().write(reply);
                    socket.getOutputStream().flush();
                }
            } catch (IOException e) {
                // The client closed early or the fake is closing; wait for the next connection.
            }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        thread.interrupt();
    }
}
