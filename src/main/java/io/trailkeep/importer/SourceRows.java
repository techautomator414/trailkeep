// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A legacy source opened for one {@code extract} (design §4): one fixed point, read entity by entity, each row as
 * column name in lower case to value. Source-neutral: the extract stages every row the same way whichever source it
 * came from. The JSON Lines source (26k2), the CSV source (26l) and the JDBC sources (26n2, 26o, 26p) implement it.
 */
interface SourceRows extends AutoCloseable {

    /** Opens the sources of one kind; {@code extract} uses the first that supports the mapping. */
    interface Factory {

        /**
         * Whether this factory reads the source a mapping names.
         *
         * @param mapping the validated mapping
         * @return true if it can open it
         */
        boolean supports(JsonNode mapping);

        /**
         * Opens the source and fixes the point every entity is read at.
         *
         * @param mapping the validated mapping
         * @param directory the mapping file's directory, which its relative paths resolve against
         * @return the open source
         */
        SourceRows open(JsonNode mapping, Path directory);
    }

    /**
     * What the source was read at (a snapshot, an SCN or a manifest hash), for {@code IMPORT_RUN.SOURCE_FINGERPRINT}.
     * An extract that restarts on the same fingerprint resumes at its first unfinished entity; on another it starts
     * over, because finished entities were read at another point (§4.1 "Restart").
     *
     * @return the fingerprint, at most 128 characters
     */
    String fingerprint();

    /**
     * Streams one entity's rows.
     *
     * @param entity the entity
     * @param spec its part of the mapping ({@code people} for persons)
     * @param rows what receives each row
     */
    void read(StagedEntity entity, JsonNode spec, Consumer<Map<String, @Nullable Object>> rows);

    @Override
    void close();
}
