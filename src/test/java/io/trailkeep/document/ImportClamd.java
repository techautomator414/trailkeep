// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A {@link FakeClamd} for tests outside this package that start their own application context, such as the importer's
 * load ITs (WP-26 part u), with the arguments that point that context's clamd host, port and TLS trust at it.
 */
public final class ImportClamd implements AutoCloseable {

    /** clamd's reply to a stream over its {@code StreamMaxLength}. */
    public static final String SIZE_LIMIT = ClamdClient.SIZE_LIMIT;

    private final FakeClamd clamd;

    private ImportClamd(FakeClamd clamd) {
        this.clamd = clamd;
    }

    /**
     * Starts a fake whose answer depends on the bytes received.
     *
     * @param answer the reply, without its terminating {@code \0}, or {@code null} for none
     * @return the running fake
     */
    public static ImportClamd answering(Function<byte[], @Nullable String> answer) {
        return new ImportClamd(FakeClamd.answering(answer));
    }

    /**
     * The command-line arguments that make a context scan with this fake.
     *
     * @return them
     */
    public List<String> arguments() {
        List<String> arguments = new ArrayList<>(TestTls.shared().trustStoreArguments());
        arguments.add("--app.attachments.clamd.host=127.0.0.1");
        arguments.add("--app.attachments.clamd.port=" + clamd.port());
        return arguments;
    }

    @Override
    public void close() throws IOException {
        clamd.close();
    }
}
