// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.ChildRules.Finding;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.staging.StagedAttachment;
import io.trailkeep.importer.staging.StagedCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The dry run's aggregates for the sign-off (WP-26 part r1, design §3 R8, §7.1, §9), counted while the rows are checked
 * and written to {@code IMPORT_RUN_SUMMARY}. Numbers only: a metric, a dimension that is an entity, a code, a status,
 * a sensitivity or a Trailkeep team id, and a count; never a legacy value or key. The metrics:
 *
 * <ul>
 *   <li>{@code ROWS} per entity: the staged rows checked; {@code ISSUES} per {@code <entity>/<severity>/<code>}: the
 *       run's issues from the extract and the dry run;
 *   <li>{@code CASES} per status ({@code OPEN}, {@code CLOSED}); {@code VISIBILITY_STANDARD} and {@code
 *       VISIBILITY_RESTRICTED} per team id: the visibility each case would load with; {@code LEGACY_ACL} per {@code
 *       visibility.onUnrepresentableAcl} handling; {@code HOLDS} per reason ({@code LEGAL_HOLD}, {@code
 *       PAST_RETENTION});
 *   <li>{@code ATTACHMENT_FILES} and {@code ATTACHMENT_BYTES}: attachments the dry run found nothing against;
 *       {@code SKIPPED_FILES} and {@code SKIPPED_BYTES} per accepted code; in {@code external} scanning mode {@code
 *       NOT_SCANNED_FILES} and {@code NOT_SCANNED_BYTES}, the attachments that will be stored {@code NOT_SCANNED}
 *       (§4.3).
 * </ul>
 */
final class DryRunSummary {

    private record Key(String metric, @Nullable String dimension) {}

    private final Map<Key, Long> figures = new LinkedHashMap<>();
    private final CaseRules rules;
    private final Map<String, Long> teamIds;
    private final String aclHandling;

    /**
     * An empty summary.
     *
     * @param rules the case rules, which know a case's hold and visibility
     * @param teamIds each Trailkeep team's id by name, the key a mapped team names
     * @param aclHandling {@code visibility.onUnrepresentableAcl}
     */
    DryRunSummary(CaseRules rules, Map<String, Long> teamIds, String aclHandling) {
        this.rules = rules;
        this.teamIds = Map.copyOf(teamIds);
        this.aclHandling = aclHandling;
    }

    /**
     * Counts an entity's checked rows.
     *
     * @param subject the entity
     * @param rows how many
     */
    void rows(ImportIssue.Subject subject, long rows) {
        add("ROWS", subject.name(), rows);
    }

    /**
     * Counts one staged case: its status, the visibility it would load with in its team, a legacy ACL, a hold.
     *
     * @param row the case
     * @param findings what the dry run found against it (unused: a case with issues is still counted as staged)
     */
    void caseRow(StagedCase row, List<Finding> findings) {
        String status = row.getStatus();
        if ("OPEN".equals(status) || "CLOSED".equals(status)) {
            add("CASES", status, 1);
        }
        Long team = row.getTeamKey() == null ? null : teamIds.get(row.getTeamKey());
        if (team != null) {
            add("VISIBILITY_" + rules.visibility(row), team.toString(), 1);
        }
        if (Boolean.TRUE.equals(row.getLegacyAcl())) {
            add("LEGACY_ACL", aclHandling, 1);
        }
        String hold = rules.holdReason(row);
        if (hold != null) {
            add("HOLDS", hold, 1);
        }
    }

    /**
     * Counts one staged attachment: loadable, skipped by rule (with its size where known), or neither.
     *
     * @param row the attachment
     * @param findings what the dry run found against it
     */
    void attachment(StagedAttachment row, List<Finding> findings) {
        long size = Objects.requireNonNullElse(row.getSizeBytes(), 0L);
        if (findings.isEmpty()) {
            add("ATTACHMENT_FILES", null, 1);
            add("ATTACHMENT_BYTES", null, size);
        }
        findings.stream()
                .filter(finding -> finding.severity() == Severity.WARNING)
                .forEach(finding -> {
                    add("SKIPPED_FILES", finding.code(), 1);
                    add("SKIPPED_BYTES", finding.code(), size);
                });
    }

    /**
     * Counts issues of one entity, severity and code.
     *
     * @param subject the entity
     * @param severity the severity
     * @param code the code
     * @param count how many
     */
    void issues(ImportIssue.Subject subject, Severity severity, String code, long count) {
        add("ISSUES", subject + "/" + severity + "/" + code, count);
    }

    /**
     * How many attachments the dry run found nothing against, which the load will store.
     *
     * @return the count
     */
    long loadableFiles() {
        return figures.getOrDefault(new Key("ATTACHMENT_FILES", null), 0L);
    }

    /**
     * The figures as rows, with the {@code NOT_SCANNED} ones in {@code external} scanning mode.
     *
     * @param runId the run
     * @param mode the scanning mode in force, recorded on the run
     * @return the rows to write
     */
    List<ImportRunSummary> figures(long runId, ImportRun.ScanMode mode) {
        if (mode == ImportRun.ScanMode.EXTERNAL) {
            figures.put(new Key("NOT_SCANNED_FILES", null), loadableFiles());
            figures.put(
                    new Key("NOT_SCANNED_BYTES", null), figures.getOrDefault(new Key("ATTACHMENT_BYTES", null), 0L));
        }
        List<ImportRunSummary> rows = new ArrayList<>();
        figures.forEach((key, value) -> rows.add(new ImportRunSummary(runId, key.metric(), key.dimension(), value)));
        return rows;
    }

    private void add(String metric, @Nullable String dimension, long value) {
        figures.merge(new Key(metric, dimension), value, Long::sum);
    }
}
