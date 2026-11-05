// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.nio.file.Path;
import java.time.Duration;
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
 * @param clamd the antivirus daemon
 */
@Validated
@ConfigurationProperties("app.attachments")
record AttachmentProperties(
        @NotNull Path tempDir,
        @NotNull @DefaultValue("25MB") DataSize maxBytes,
        @Positive @DefaultValue("25000000") long maxImagePixels,
        @Valid @NotNull Clamd clamd) {

    /**
     * The clamd {@code INSTREAM} endpoint. No host default: a deployment names its scanner or does not start.
     *
     * @param host the clamd host
     * @param port the clamd TCP port
     * @param connectTimeout how long to wait for a connection
     * @param readTimeout how long any single read may wait, including for the verdict after the last chunk
     * @param scanTimeout how long one whole exchange may take; the socket is closed after it
     */
    record Clamd(
            @NotBlank String host,
            @Positive @Max(65_535) @DefaultValue("3310") int port,
            @NotNull @DefaultValue("2s") Duration connectTimeout,
            @NotNull @DefaultValue("30s") Duration readTimeout,
            @NotNull @DefaultValue("2m") Duration scanTimeout) {}
}
