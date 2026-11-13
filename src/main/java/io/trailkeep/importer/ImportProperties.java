// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The importer Job's arguments under {@code import} (design §2.1): one command per Job run, for one run id from the
 * authenticated run request, for example {@code --import.command=status --import.run=42}. Read only by the {@code
 * importer} profile.
 *
 * @param command the command, or {@code null} outside the importer Job
 * @param run the run id, or {@code null} outside the importer Job
 * @param mapping the mapping file on the Job's read-only volume, for {@code validate-mapping} and {@code extract};
 *     {@code null} otherwise
 * @param hashKey {@code import.hash-key}: Base64 of at least 32 bytes, mounted from its own Secret, never the audit
 *     key (design §5 "Hashes are keyed"); required by {@code extract}
 * @param stagingTtl how long a run's staging lives after its extract starts (design §5 "Staging TTL")
 * @param attachments the importer's attachment settings
 * @param contentArea the folder on the run volume that holds each run's content area (design §4.3); required by an
 *     extract whose attachments come from a content query
 * @param goLive the go-live day the dry run forecasts open cases' SLA states for, from its start in {@code
 *     app.timezone} (design §7.1, §9); the dry run's own time when unset or already past
 * @param allowMappingChange whether a dry run accepts that the source's rows were loaded under another mapping, and
 *     then compares no row hash with the ledger's ({@link LedgerDelta}, design §3.1)
 */
@ConfigurationProperties("import")
record ImportProperties(
        @Nullable String command,
        @Nullable Long run,
        @Nullable Path mapping,
        @Nullable String hashKey,
        @DefaultValue("14d") Duration stagingTtl,
        @DefaultValue Attachments attachments,
        @Nullable Path contentArea,
        @Nullable LocalDate goLive,
        @DefaultValue("false") boolean allowMappingChange) {

    /**
     * {@code import.attachments} (design §4.3 "Size").
     *
     * @param maxBytes the importer's cap: a larger file is never read at extract nor loaded; the importer profile sets
     *     it to {@code app.attachments.max-bytes} unless the deployment raises it
     * @param clamdStreamMax clamd's {@code StreamMaxLength} as the deployment declares it, since the importer cannot
     *     read a clamd server setting (R13): in {@code clamd} mode {@code dry-run} and {@code load} refuse to start
     *     when it is unset or below {@code maxBytes} ({@link ScanModeInForce#checkStreamMax}); unused otherwise
     */
    record Attachments(
            @DefaultValue("25MB") DataSize maxBytes,
            @Nullable DataSize clamdStreamMax) {}

    /** The same floor as the audit key's. */
    static final int MIN_HASH_KEY_BYTES = 32;

    /**
     * {@code import.hash-key} as an HMAC-SHA-256 key.
     *
     * @return the key
     * @throws IllegalStateException if it is missing, not Base64 or shorter than 32 bytes
     */
    SecretKey hashKeySpec() {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(hashKey == null ? "" : hashKey.strip());
        } catch (IllegalArgumentException malformed) {
            key = new byte[0];
        }
        if (key.length < MIN_HASH_KEY_BYTES) {
            throw new IllegalStateException(
                    "import.hash-key must be Base64 of at least " + MIN_HASH_KEY_BYTES + " bytes to extract");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Override
    public String toString() {
        // The key never reaches a log line.
        return "ImportProperties[command=" + command + ", run=" + run + ", mapping=" + mapping + "]";
    }
}
