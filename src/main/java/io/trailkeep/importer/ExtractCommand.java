// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.RowStager.Staged;
import io.trailkeep.importer.staging.RunCheckpoint;
import io.trailkeep.importer.staging.RunCheckpoint.Stage;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import io.trailkeep.importer.staging.StagingStore;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@code extract} (WP-26 part k1b, design §3 step 2, §4.1, §5): reads the source a validated mapping names at one
 * fixed point, entity by entity, stages every row through {@link RowStager}, and records each problem as an {@code
 * EXTRACT} issue: a code and the keyed hash of the row's key, never a value. Rows are written in batches of {@code
 * limits.batchSize}, each in its own transaction, and an entity ends with its checkpoint in the transaction of its last
 * rows. A crashed extract that is run again on the same fingerprint deletes the unfinished entity's rows and issues
 * and reads that entity again; on another fingerprint it starts over. The run gets its fingerprint and its staging
 * expiry ({@code import.staging-ttl}) when the extract starts, so abandoned staging expires too, and moves from {@code
 * VALIDATED} to {@code EXTRACTED} with the number of rows staged. A source that cannot be read soundly (26k2: a file
 * that differs from its manifest, a line that is not one object) is a {@code FATAL} issue with a code and at most a
 * line number, and stops the extract before that entity is checkpointed; so is a source query that fails (26n2), with
 * its code only. An extract that starts over also empties the run's content area.
 */
@Component
@Profile("importer")
class ExtractCommand {

    private final List<SourceRows.Factory> sources;
    private final StagingStore staging;
    private final ImportRunRepository runs;
    private final ImportIssueRepository issues;
    private final TransactionTemplate transactions;
    private final ImportProperties properties;
    private final InstantSource clock;
    private final YAMLMapper yaml = YAMLMapper.builder().build();

    ExtractCommand(
            List<SourceRows.Factory> sources,
            StagingStore staging,
            ImportRunRepository runs,
            ImportIssueRepository issues,
            TransactionTemplate transactions,
            ImportProperties properties,
            InstantSource clock) {
        this.sources = List.copyOf(sources);
        this.staging = staging;
        this.runs = runs;
        this.issues = issues;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Extracts a validated run.
     *
     * @param runId the run
     * @param file the mapping file, unchanged since {@code validate-mapping}
     * @return the number of rows staged
     * @throws IllegalStateException if the run is not validated, the file or a lookup file changed, the source is not
     *     supported, or a row had a FATAL issue; the last two are recorded on the run
     */
    long run(long runId, Path file) {
        byte[] content = ValidateMappingCommand.read(file);
        ImportRun found = load(runId);
        if (found.getStatus() != ImportRun.Status.VALIDATED || found.getStagingPurgedAt() != null) {
            throw new IllegalStateException("Import run " + runId + " is " + found.getStatus() + ", not validated");
        }
        JsonNode mapping = yaml.readTree(content);
        LookupFiles lookups = LookupFiles.read(file, mapping);
        // A lookup file that no longer reads is a changed file too: validate-mapping says why.
        if (!lookups.problems().isEmpty() || !found.hasMappingHash(lookups.hash(content))) {
            throw new IllegalStateException(
                    "The mapping file or a lookup file changed after validate-mapping; validate it again");
        }
        MappingContext context = MappingContext.of(mapping, properties.hashKeySpec(), lookups.values());
        transactions.executeWithoutResult(status ->
                issues.deleteByRunIdAndStageAndSubject(runId, ImportIssue.Stage.EXTRACT, ImportIssue.Subject.MAPPING));
        SourceRows.Factory factory = sources.stream()
                .filter(candidate -> candidate.supports(context.mapping()))
                .findFirst()
                .orElseThrow(() ->
                        refused(runId, ImportIssue.Subject.MAPPING, new SourceException("SOURCE_UNSUPPORTED", null)));
        Path directory = Objects.requireNonNull(file.toAbsolutePath().getParent(), "a file has a directory");
        try (SourceRows source = open(runId, factory, context, directory)) {
            AttachmentFiles files = attachmentFiles(runId, context, source);
            String fingerprint = source.fingerprint();
            transactions.executeWithoutResult(status -> {
                ImportRun run = load(runId);
                if (run.extractStarting(fingerprint, clock.instant().plus(properties.stagingTtl()), clock.instant())) {
                    staging.purge(runId);
                    ContentArea.delete(properties.contentArea(), runId);
                }
                runs.save(run);
            });
            for (StagedEntity entity : StagedEntity.values()) {
                extract(runId, context, source, entity, entity == StagedEntity.ATTACHMENT ? files : null);
            }
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            ImportRun run = load(runId);
            run.extracted(clock.instant());
            return runs.save(run).getRowsStaged();
        }));
    }

    private void extract(
            long runId,
            MappingContext context,
            SourceRows source,
            StagedEntity entity,
            @Nullable AttachmentFiles files) {
        RunCheckpoint.Key checkpoint = new RunCheckpoint.Key(runId, Stage.EXTRACT, entity);
        if (staging.isFinished(checkpoint)) {
            return;
        }
        ImportIssue.Subject subject = ImportIssue.Subject.valueOf(entity.name());
        transactions.executeWithoutResult(status -> {
            staging.deleteRows(entity, runId);
            issues.deleteByRunIdAndStageAndSubject(runId, ImportIssue.Stage.EXTRACT, subject);
        });
        JsonNode spec = entity == StagedEntity.PERSON
                ? context.mapping().path("people")
                : context.mapping().path("entities").path(entity.name().toLowerCase(Locale.ROOT));
        Batch batch = new Batch(runId, entity, new RowStager(context, entity, files), subject);
        int size = context.mapping().at("/limits/batchSize").asInt(500);
        try {
            if (!spec.isMissingNode()) {
                source.read(entity, spec, row -> {
                    batch.pending.add(batch.stager.stage(row));
                    if (batch.pending.size() >= size) {
                        stopOnFatal(runId, transactions.execute(status -> batch.flush()));
                    }
                });
            }
        } catch (SourceException unsound) {
            throw refused(runId, subject, unsound);
        }
        stopOnFatal(runId, transactions.execute(status -> {
            boolean fatal = batch.flush();
            if (!fatal) {
                staging.finished(new RunCheckpoint(checkpoint, clock.instant()));
                ImportRun run = load(runId);
                run.entityStaged(batch.staged, clock.instant());
                runs.save(run);
            }
            return fatal;
        }));
    }

    /** A FATAL row issue stops the extract after its batch, issue included, is committed. */
    private static void stopOnFatal(long runId, @Nullable Boolean fatal) {
        if (Boolean.TRUE.equals(fatal)) {
            throw new IllegalStateException(
                    "Import run " + runId + " has a FATAL extract issue; fix the mapping and request a new run");
        }
    }

    private SourceRows open(long runId, SourceRows.Factory factory, MappingContext context, Path directory) {
        try {
            return factory.open(context.mapping(), directory);
        } catch (SourceException unsound) {
            throw refused(runId, ImportIssue.Subject.MAPPING, unsound);
        }
    }

    private AttachmentFiles attachmentFiles(long runId, MappingContext context, SourceRows source) {
        try {
            return AttachmentFiles.of(
                    context.mapping(),
                    source,
                    properties.attachments().maxBytes().toBytes(),
                    properties.contentArea(),
                    runId);
        } catch (SourceException unsound) {
            throw refused(runId, ImportIssue.Subject.MAPPING, unsound);
        }
    }

    /**
     * Records a FATAL issue the extract cannot get past (the source, or a mapping it cannot read yet) with its numeric
     * detail, and fails the Job; the rows staged before it stay, unfinished, until the run is purged.
     */
    private IllegalStateException refused(long runId, ImportIssue.Subject subject, SourceException failure) {
        transactions.executeWithoutResult(status -> issues.save(new ImportIssue(
                runId,
                ImportIssue.Stage.EXTRACT,
                subject,
                null,
                Severity.FATAL,
                failure.code(),
                failure.detail(),
                clock.instant())));
        return new IllegalStateException("Import run " + runId + " cannot be extracted: " + failure.code());
    }

    private ImportRun load(long runId) {
        return runs.findById(runId).orElseThrow(() -> new IllegalStateException("No import run " + runId));
    }

    private ImportIssue issue(
            long runId, ImportIssue.Subject subject, @Nullable String keyHash, FieldTransformer.Issue issue) {
        return new ImportIssue(
                runId,
                ImportIssue.Stage.EXTRACT,
                subject,
                keyHash,
                issue.severity(),
                issue.code(),
                null,
                clock.instant());
    }

    /** One entity's rows not yet written. */
    private final class Batch {

        private final long runId;
        private final StagedEntity entity;
        private final RowStager stager;
        private final ImportIssue.Subject subject;
        private final List<Staged> pending = new ArrayList<>();
        private long staged;

        Batch(long runId, StagedEntity entity, RowStager stager, ImportIssue.Subject subject) {
            this.runId = runId;
            this.entity = entity;
            this.stager = stager;
            this.subject = subject;
        }

        /**
         * Writes the pending rows and their issues, in the caller's transaction. A key seen before, in this batch or
         * staged earlier, is a {@code DUPLICATE_KEY}, and the first row stays.
         *
         * @return whether any issue was FATAL
         */
        boolean flush() {
            Set<String> seen = new HashSet<>(staging.stagedKeys(
                    entity,
                    runId,
                    pending.stream().map(Staged::key).filter(Objects::nonNull).toList()));
            boolean fatal = false;
            for (Staged row : pending) {
                List<FieldTransformer.Issue> found = row.issues();
                if (row.key() != null && !seen.add(row.key())) {
                    found = List.of(new FieldTransformer.Issue("DUPLICATE_KEY", Severity.ERROR));
                } else if (row.key() != null) {
                    staging.stage(stager.row(runId, row));
                    staged++;
                }
                for (FieldTransformer.Issue problem : found) {
                    issues.save(issue(runId, subject, row.keyHash(), problem));
                    fatal |= problem.severity() == Severity.FATAL;
                }
            }
            pending.clear();
            return fatal;
        }
    }
}
