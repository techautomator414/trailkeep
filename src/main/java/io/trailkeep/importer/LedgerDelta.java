// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.casefile.CaseImportService.Existing;
import io.trailkeep.casefile.CaseImportService.Existing.State;
import io.trailkeep.importer.ChildRules.Finding;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedRow;
import io.trailkeep.importer.staging.StagingStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;

/**
 * What a dry run reports about a delta run (WP-26 part y1, design §3.1, R10): a later run against a source an earlier
 * load wrote rows of, as the import ledger records them. Every finding is a code, a severity and the Trailkeep case id
 * or a count, never a legacy value.
 *
 * <ul>
 *   <li>a staged row the ledger holds is not checked against its entity's rules, since no load writes it again, and
 *       nothing is overwritten: an open case closed in the source is {@code CLOSED_IN_SOURCE}, for the closure
 *       worklist; a case whose status or closing time otherwise differs from those it was loaded with, or a row whose
 *       keyed hash differs from the one it was loaded with, is {@code CHANGED_AFTER_IMPORT}. Both are WARNINGs carrying
 *       the case id. Status and closing time are compared without the row hash, so they are compared under {@code
 *       import.allow-mapping-change} too (R10);
 *   <li>a ledger row of the source that this run did not stage is {@code MISSING_IN_SOURCE} (WARNING) with the case id;
 *       nothing is deleted;
 *   <li>a child whose case this run did not stage but the ledger holds has a parent: the load adds it to that case;
 *   <li>a loaded case archived or purged since takes nothing more (WP-26 part y2): its staged row is {@code
 *       CASE_ARCHIVED} or {@code CASE_PURGED} in place of any other change, and each new child of it {@code
 *       PARENT_ARCHIVED} or {@code PARENT_PURGED} in place of its rules, all WARNINGs carrying the case id;
 *   <li>ledger rows of the source loaded under another mapping make a FATAL {@code MAPPING_CHANGED_SINCE_LOAD} with
 *       their number, unless {@code import.allow-mapping-change} is set. With it, once the ledger holds any row of the
 *       source, one {@code MAPPING_CHANGE_ALLOWED} WARNING with that number tells the sign-off that no row hash is
 *       compared in this run, as a changed mapping or a rotated {@code import.hash-key} makes every row look changed.
 * </ul>
 */
final class LedgerDelta {

    /** The entities the ledger holds. */
    static final List<Subject> ENTITIES = List.of(Subject.CASE, Subject.COMMENT, Subject.ATTACHMENT, Subject.EVENT);

    /** The code of a row changed in the source after it was loaded. */
    static final String CHANGED = "CHANGED_AFTER_IMPORT";

    /** Ledger rows read per page, below Oracle's 1000 keys per {@code IN} list. */
    private static final int PAGE = 500;

    /**
     * The cases a page of children names that the load can write them onto, and the finding of each child of a loaded
     * case archived or purged since, which the load will not write.
     *
     * @param keys the case keys this run staged or the ledger holds
     * @param closed {@code PARENT_ARCHIVED} or {@code PARENT_PURGED} with the case id, by case key
     */
    record Parents(Set<String> keys, Map<String, Finding> closed) {

        /**
         * Whether a child has a parent the load can find.
         *
         * @param caseKey the child's case key, or {@code null} for a row with no parent
         * @return true for a row with no parent, or a case this run staged or the ledger holds
         */
        boolean found(@Nullable String caseKey) {
            return caseKey == null || keys.contains(caseKey);
        }

        /**
         * The finding in place of a child's rules when its case was archived or purged since it was loaded.
         *
         * @param caseKey the child's case key, or {@code null} for a row with no parent
         * @return it, or {@code null}
         */
        @Nullable
        Finding closed(@Nullable String caseKey) {
            return caseKey == null ? null : closed.get(caseKey);
        }
    }

    private final ImportLedgerRepository ledger;
    private final CaseImportService cases;
    private final String sourceId;
    private final @Nullable Finding mapping;
    private final boolean hashes;

    private LedgerDelta(
            ImportLedgerRepository ledger,
            CaseImportService cases,
            String sourceId,
            @Nullable Finding mapping,
            boolean hashes) {
        this.ledger = ledger;
        this.cases = cases;
        this.sourceId = sourceId;
        this.mapping = mapping;
        this.hashes = hashes;
    }

    /**
     * The delta rules of one dry run.
     *
     * @param ledger the import ledger
     * @param cases the import API, which tells which loaded cases were archived or purged since
     * @param sourceId the run's {@code source.id}
     * @param mappingHash the hash of the mapping and lookup files the run is dry-run with
     * @param allowMappingChange {@code import.allow-mapping-change}
     * @return the rules
     */
    static LedgerDelta of(
            ImportLedgerRepository ledger,
            CaseImportService cases,
            String sourceId,
            String mappingHash,
            boolean allowMappingChange) {
        long other = ledger.countBySourceIdAndMappingHashNot(sourceId, mappingHash);
        Finding mapping = null;
        if (!allowMappingChange && other > 0) {
            mapping = new Finding("MAPPING_CHANGED_SINCE_LOAD", Severity.FATAL, other);
        } else if (allowMappingChange && ledger.countBySourceId(sourceId) > 0) {
            mapping = new Finding("MAPPING_CHANGE_ALLOWED", Severity.WARNING, other);
        }
        return new LedgerDelta(ledger, cases, sourceId, mapping, !allowMappingChange);
    }

    /**
     * The run's finding about the mapping the ledger's rows were loaded under.
     *
     * @return it, or {@code null} when there is none
     */
    @Nullable
    Finding mapping() {
        return mapping;
    }

    /**
     * The ledger rows of these keys of one entity of the source.
     *
     * @param subject the entity; a person is never in the ledger
     * @param keys at most one page of canonical keys
     * @return the rows the ledger holds, by key
     */
    Map<String, ImportLedgerEntry> held(Subject subject, List<String> keys) {
        if (!ENTITIES.contains(subject) || keys.isEmpty()) {
            return Map.of();
        }
        return ledger.findBySourceIdAndEntityAndLegacyKeyIn(sourceId, subject, keys).stream()
                .collect(Collectors.toMap(ImportLedgerEntry::getLegacyKey, Function.identity()));
    }

    /**
     * The loaded cases among these that were archived or purged since, which take no imported children.
     *
     * @param subject the entity of the ledger rows; only cases are looked up
     * @param loaded ledger rows of that entity, at most a page's worth
     * @return their states, by case id; an active case is absent, and so is every row of another entity
     */
    Map<Long, State> closed(Subject subject, Collection<ImportLedgerEntry> loaded) {
        if (subject != Subject.CASE || loaded.isEmpty()) {
            return Map.of();
        }
        return cases.existing(loaded.stream().map(ImportLedgerEntry::getCaseId).toList()).values().stream()
                .filter(found -> found.state() != State.ACTIVE)
                .collect(Collectors.toMap(found -> found.created().caseId(), Existing::state));
    }

    /**
     * The parents of a page of children: the cases this run staged and those the ledger holds, a delta's children of
     * loaded cases going onto those; a child of a loaded case archived or purged since is reported instead of checked,
     * since the load will not write it.
     *
     * @param staging the run's staging
     * @param runId the run
     * @param caseKeys the case keys the page's children name, at most a page's worth
     * @return the parents
     */
    Parents parents(StagingStore staging, long runId, List<String> caseKeys) {
        Set<String> found = new HashSet<>(staging.stagedKeys(StagedEntity.CASE, runId, caseKeys));
        Map<String, ImportLedgerEntry> loaded = held(Subject.CASE, caseKeys);
        found.addAll(loaded.keySet());
        Map<Long, State> states = closed(Subject.CASE, loaded.values());
        Map<String, Finding> closed = new HashMap<>();
        for (ImportLedgerEntry entry : loaded.values()) {
            State state = states.get(entry.getCaseId());
            if (state != null) {
                closed.put(entry.getLegacyKey(), warning("PARENT_" + state.name(), entry));
            }
        }
        return new Parents(found, closed);
    }

    /**
     * What changed in the source since a row was loaded. A case archived or purged since is {@code CASE_ARCHIVED} or
     * {@code CASE_PURGED} (WARNING, with the case id), whatever else changed, since nothing is ever written to it.
     *
     * @param row the row as this run staged it
     * @param loaded its ledger row
     * @param states the loaded cases archived or purged since, by case id, from {@link #closed}
     * @return at most one finding; empty when nothing changed, or only the row hash when hashes are not compared
     */
    List<Finding> changed(StagedRow row, ImportLedgerEntry loaded, Map<Long, State> states) {
        State state = states.get(loaded.getCaseId());
        if (row instanceof StagedCase && state != null) {
            return List.of(warning("CASE_" + state.name(), loaded));
        }
        if (row instanceof StagedCase staged
                && !(Objects.equals(staged.getStatus(), loaded.getStatus())
                        && Objects.equals(staged.getClosedAt(), loaded.getClosedAt()))) {
            boolean closed = "OPEN".equals(loaded.getStatus()) && "CLOSED".equals(staged.getStatus());
            return List.of(warning(closed ? "CLOSED_IN_SOURCE" : CHANGED, loaded));
        }
        // Constant time, like every hash comparison here (SpotBugs UNSAFE_HASH_EQUALS).
        if (hashes
                && !MessageDigest.isEqual(
                        row.getRowHash().getBytes(StandardCharsets.US_ASCII),
                        loaded.getRowHash().getBytes(StandardCharsets.US_ASCII))) {
            return List.of(warning(CHANGED, loaded));
        }
        return List.of();
    }

    /**
     * Reads the ledger rows of one entity of the source in key order, a page at a time, and hands each page's rows that
     * this run did not stage to {@code each}, each with its {@code MISSING_IN_SOURCE} finding.
     *
     * @param subject the entity
     * @param staging the run's staging
     * @param runId the run
     * @param each takes the missing rows of one page, each key with its finding
     * @return how many rows were missing
     */
    long missing(Subject subject, StagingStore staging, long runId, Consumer<Map<String, Finding>> each) {
        StagedEntity entity = StagedEntity.valueOf(subject.name());
        long missing = 0;
        List<ImportLedgerEntry> page =
                ledger.findBySourceIdAndEntityAndRunIdNotOrderByLegacyKeyAsc(sourceId, subject, runId, Limit.of(PAGE));
        while (!page.isEmpty()) {
            Set<String> staged = staging.stagedKeys(
                    entity,
                    runId,
                    page.stream().map(ImportLedgerEntry::getLegacyKey).toList());
            Map<String, Finding> absent = page.stream()
                    .filter(entry -> !staged.contains(entry.getLegacyKey()))
                    .collect(Collectors.toMap(
                            ImportLedgerEntry::getLegacyKey, entry -> warning("MISSING_IN_SOURCE", entry)));
            if (!absent.isEmpty()) {
                each.accept(absent);
                missing += absent.size();
            }
            page = ledger.findBySourceIdAndEntityAndRunIdNotAndLegacyKeyGreaterThanOrderByLegacyKeyAsc(
                    sourceId, subject, runId, page.getLast().getLegacyKey(), Limit.of(PAGE));
        }
        return missing;
    }

    private static Finding warning(String code, ImportLedgerEntry loaded) {
        return new Finding(code, Severity.WARNING, loaded.getCaseId());
    }
}
