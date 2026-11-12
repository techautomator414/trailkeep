// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer.staging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One legacy attachment (V13 {@code IMPORT_STG_ATTACHMENT}, design §5, §4.3). The bytes are never here: the content
 * pointer names the file in the run's content area or the mounted folder, or the source's content id.
 */
@Entity
@Table(name = "IMPORT_STG_ATTACHMENT")
public class StagedAttachment extends StagedRow {

    @Column(name = "CASE_KEY", nullable = false, length = 100)
    private String caseKey = "";

    @Column(name = "FILE_NAME", length = 1000)
    private @Nullable String fileName;

    @Column(name = "SIZE_BYTES")
    private @Nullable Long sizeBytes;

    @Column(name = "SHA256", length = 64)
    private @Nullable String sha256;

    @Column(name = "CONTENT_POINTER", length = 1000)
    private @Nullable String contentPointer;

    @Column(name = "UPLOADED_AT")
    private @Nullable Instant uploadedAt;

    @Column(name = "UPLOADER_KEY", length = 100)
    private @Nullable String uploaderKey;

    @Column(name = "UPLOADER_NAME", length = 200)
    private @Nullable String uploaderName;

    /** For JPA. */
    protected StagedAttachment() {}

    /**
     * A legacy attachment's metadata and content pointer, never its bytes, as the extract staged it.
     *
     * @param key its run and canonical key
     * @param rowHash the keyed hash of its canonical row
     * @param caseKey its case's canonical key
     * @param values its mapped fields, typed
     */
    public StagedAttachment(StagingKey key, String rowHash, String caseKey, StagedValues values) {
        super(key, rowHash);
        this.caseKey = caseKey;
        this.fileName = values.text("fileName");
        this.sizeBytes = values.number("size");
        this.sha256 = values.text("sha256");
        this.contentPointer = values.text("content");
        this.uploadedAt = values.instant("uploadedAt");
        this.uploaderKey = values.text("uploaderKey");
        this.uploaderName = values.text("uploaderName");
    }
}
