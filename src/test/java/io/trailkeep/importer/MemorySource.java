// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** A synthetic legacy source held in memory; it can fail on the third row of one entity, after one batch. */
final class MemorySource implements SourceRows.Factory, SourceRows {

    private final String fingerprint;
    private final @Nullable StagedEntity failIn;
    private final List<StagedEntity> read = new ArrayList<>();

    MemorySource(String fingerprint, @Nullable StagedEntity failIn) {
        this.fingerprint = fingerprint;
        this.failIn = failIn;
    }

    /** The entities read so far, in order. */
    List<StagedEntity> entitiesRead() {
        return List.copyOf(read);
    }

    @Override
    public boolean supports(JsonNode mapping) {
        return "memory1".equals(mapping.at("/source/id").asString(""));
    }

    @Override
    public SourceRows open(JsonNode mapping, Path directory) {
        return this;
    }

    @Override
    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public void read(StagedEntity entity, JsonNode spec, Consumer<Map<String, @Nullable Object>> rows) {
        read.add(entity);
        List<Map<String, Object>> source = switch (entity) {
            case PERSON ->
                List.of(
                        Map.of("user_id", "u1", "name", "Synthetic One", "mail", "one@example.test"),
                        Map.of("user_id", "u2", "name", "Synthetic Two", "mail", "two@example.test"));
            case CASE -> List.of(legacyCase("c1", "Open"), legacyCase("c2", "Closed"), legacyCase("c3", "Weird"));
            case COMMENT -> List.of(note("n1"), note("n2"), note("n2"));
            case ATTACHMENT ->
                List.of(Map.of(
                        "file_id", "f1", "case_id", "c1", "name", "scan.pdf", "path", "a/scan.pdf", "bytes", 2048));
            case EVENT -> List.of();
        };
        for (int i = 0; i < source.size(); i++) {
            if (entity == failIn && i == 2) {
                throw new IllegalStateException("planted source failure");
            }
            rows.accept(new HashMap<>(source.get(i)));
        }
    }

    @Override
    public void close() {
        // Nothing is held open.
    }

    private static Map<String, Object> legacyCase(String id, String state) {
        return Map.of(
                "case_id", id,
                "subject", "Synthetic case " + ("c1".equals(id) ? "one" : id),
                "details", "Synthetic details",
                "state", state,
                "opened", "2019-03-01T09:30:00",
                "found_on", "2019-02-28",
                "owner", "u1");
    }

    private static Map<String, Object> note(String id) {
        return Map.of(
                "note_id",
                id,
                "case_id",
                "c1",
                "author",
                "u2",
                "created",
                "2019-03-02T10:00:00",
                "body",
                "Synthetic note");
    }
}
