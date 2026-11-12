// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer.staging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.Length;
import org.jspecify.annotations.Nullable;

/** One legacy comment, naming its case by canonical key (V13 {@code IMPORT_STG_COMMENT}, design §5). */
@Entity
@Table(name = "IMPORT_STG_COMMENT")
public class StagedComment extends StagedRow {

    @Column(name = "CASE_KEY", nullable = false, length = 100)
    private String caseKey = "";

    @Column(name = "AUTHOR_KEY", length = 100)
    private @Nullable String authorKey;

    @Column(name = "AUTHOR_NAME", length = 200)
    private @Nullable String authorName;

    @Column(name = "WRITTEN_AT")
    private @Nullable Instant writtenAt;

    @Column(name = "BODY", length = Length.LONG32)
    private @Nullable String body;

    @Column(name = "BODY_FORMAT", length = 8)
    private @Nullable String bodyFormat;

    /** For JPA. */
    protected StagedComment() {}
}
