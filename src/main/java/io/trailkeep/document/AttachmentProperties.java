// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code app.attachments} (BUILD_PLAN §9, Input validation).
 *
 * @param tempDir where an upload is written while it is checked and scanned; an {@code emptyDir} in deployments.
 *     Only ever a parent directory: a file's name never forms a path.
 * @param maxBytes the largest file accepted; {@code spring.servlet.multipart.max-file-size} follows it
 * @param maxImagePixels the largest width times height accepted for a PNG or JPEG, read from its header
 * @param scanning who scans uploads for malware (ADR-0013): {@code clamd}, the default, or {@code external}. An unset
 *     value never turns scanning off, and an unknown one fails startup.
 * @param clamd the antivirus daemon; used, and its host required, only when {@code scanning} is {@code clamd}
 */
@Validated
@ConfigurationProperties("app.attachments")
record AttachmentProperties(
        @NotNull Path tempDir,
        @NotNull @DefaultValue("25MB") DataSize maxBytes,
        @Positive @DefaultValue("25000000") long maxImagePixels,
        @NotNull @DefaultValue("clamd") ScanMode scanning,
        @Valid @NotNull @DefaultValue Clamd clamd) {

    /** The property that selects the scan mode, read by {@link ClamdScanningCondition} too. */
    static final String SCANNING = "app.attachments.scanning";

    /**
     * In {@code clamd} mode a deployment names its scanner or does not start; in {@code external} mode it need not.
     *
     * @return whether the clamd host is set, or not needed
     */
    @AssertTrue(message = "app.attachments.clamd.host is required while app.attachments.scanning is clamd")
    public boolean isScannerNamed() {
        String host = clamd.host();
        return scanning == ScanMode.EXTERNAL || (host != null && !host.isBlank());
    }

    /**
     * The clamd {@code INSTREAM} endpoint: the TLS sidecar in front of clamd (ADR-0012). No host default: in
     * {@code clamd} mode a deployment names its scanner or does not start ({@link #isScannerNamed()}).
     *
     * @param host the scanner host; the server certificate must name it
     * @param port the sidecar's TLS port
     * @param sslBundle the {@code spring.ssl.bundle.*} bundle whose trust store authenticates the sidecar
     * @param connectTimeout how long to wait for a connection
     * @param readTimeout how long any single read may wait, including for the verdict after the last chunk
     * @param scanTimeout how long one whole exchange may take; the socket is closed after it
     */
    record Clamd(
            @Nullable String host,
            @Positive @Max(65_535) @DefaultValue("3311") int port,
            @NotBlank @DefaultValue("clamd") String sslBundle,
            @NotNull @DefaultValue("2s") Duration connectTimeout,
            @NotNull @DefaultValue("30s") Duration readTimeout,
            @NotNull @DefaultValue("2m") Duration scanTimeout) {}
}
