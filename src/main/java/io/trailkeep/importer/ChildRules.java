// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.audit.ImportedHistoryService;
import io.trailkeep.importer.FieldTransformer.Issue;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.staging.StagedAttachment;
import io.trailkeep.importer.staging.StagedComment;
import io.trailkeep.importer.staging.StagedEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The dry run's rules for a case's comments, attachments and legacy history (WP-26 part q2, design §3 step 3, §4.3,
 * §6.1, §7.2, §7.5). Pure, like {@link CaseRules}: every finding is a code, a severity and at most a number, never a
 * value.
 *
 * <ul>
 *   <li>a row whose case this run did not stage and the import ledger does not hold: {@code PARENT_NOT_STAGED}
 *       (ERROR; a delta's child of a case an earlier run loaded has its parent, {@link LedgerDelta});
 *   <li>a required field that is empty, {@code <FIELD>_MISSING} (ERROR): a comment's time and body, an attachment's
 *       file name and upload time, an event's time and kind; a time in the future: {@code DATE_IN_FUTURE} (ERROR);
 *   <li>a comment longer, as a comment holds it, than one comment takes: {@code COMMENT_SPLIT} (WARNING) with the
 *       number of comments the load splits it into ({@link CaseComments});
 *   <li>an event kind or field {@code CASE_IMPORTED_EVENT} does not have: {@code KIND_INVALID}, {@code FIELD_INVALID}
 *       (ERROR); an event the load would take whose value the history stores as text ({@link EventLoad}) and that is
 *       longer than it keeps: {@code VALUE_TRUNCATED} (WARNING) with the longer value's length;
 *   <li>an attachment over {@code import.attachments.max-bytes}: {@code ATTACHMENT_TOO_LARGE} with its staged size as
 *       the detail; one with no bytes to load (no SHA-256 was staged; the extract's own issue says why, when it had
 *       one): {@code ATTACHMENT_MISSING}. Both are ERRORs unless {@code limits.acceptSkip} names them: then they are
 *       WARNINGs, "skipped by rule", which the sign-off acknowledges and the error rate does not count (§4.3).
 * </ul>
 */
final class ChildRules {

    /**
     * One problem with a staged row.
     *
     * @param code the issue code
     * @param severity how bad it is
     * @param detail a number that helps, such as a size; {@code null} when none does
     */
    record Finding(String code, Severity severity, @Nullable Long detail) {

        /**
         * A field issue as a finding without detail.
         *
         * @param issue the issue
         * @return the finding
         */
        static Finding of(Issue issue) {
            return new Finding(issue.code(), issue.severity(), null);
        }
    }

    private static final Set<String> KINDS =
            Arrays.stream(ImportedHistoryService.Kind.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final Set<String> accepted = new HashSet<>();
    private final long maxBytes;
    private final Instant now;

    /**
     * Rules for one dry run.
     *
     * @param mapping the validated mapping, whose {@code limits.acceptSkip} holds only skippable codes
     * @param maxBytes {@code import.attachments.max-bytes}
     * @param now the dry run's time
     */
    ChildRules(JsonNode mapping, long maxBytes, Instant now) {
        mapping.at("/limits/acceptSkip").forEach(code -> accepted.add(code.asString()));
        this.maxBytes = maxBytes;
        this.now = now;
    }

    /**
     * The codes the mapping accepts as skipped, which never count against {@code limits.maxErrorRate}.
     *
     * @return them, possibly none
     */
    Set<String> accepted() {
        return Set.copyOf(accepted);
    }

    /**
     * Checks one staged comment.
     *
     * @param row the comment
     * @param parentStaged whether this run staged its case or the import ledger holds it
     * @return its findings; empty when the load would take it
     */
    List<Finding> comment(StagedComment row, boolean parentStaged) {
        List<Finding> found = parent(parentStaged);
        required(found, "WRITTEN_AT", row.getWrittenAt());
        required(found, "BODY", row.getBody());
        future(found, row.getWrittenAt());
        int parts = row.getBody() == null
                ? 1
                : CaseComments.parts(CaseComments.body(row)).size();
        if (parts > 1) {
            found.add(new Finding("COMMENT_SPLIT", Severity.WARNING, (long) parts));
        }
        return List.copyOf(found);
    }

    /**
     * Checks one staged attachment.
     *
     * @param row the attachment
     * @param parentStaged whether this run staged its case or the import ledger holds it
     * @return its findings; empty when the load would take it
     */
    List<Finding> attachment(StagedAttachment row, boolean parentStaged) {
        List<Finding> found = parent(parentStaged);
        required(found, "FILE_NAME", row.getFileName());
        required(found, "UPLOADED_AT", row.getUploadedAt());
        future(found, row.getUploadedAt());
        Long size = row.getSizeBytes();
        if (size != null && size > maxBytes) {
            found.add(skippable("ATTACHMENT_TOO_LARGE", size));
        } else if (row.getSha256() == null) {
            found.add(skippable("ATTACHMENT_MISSING", null));
        }
        return List.copyOf(found);
    }

    /**
     * Checks one staged legacy history event.
     *
     * @param row the event
     * @param parentStaged whether this run staged its case or the import ledger holds it
     * @return its findings; empty when the load would take it
     */
    List<Finding> event(StagedEvent row, boolean parentStaged) {
        List<Finding> found = parent(parentStaged);
        required(found, "AT", row.getEventAt());
        required(found, "KIND", row.getKind());
        if (row.getKind() != null && !KINDS.contains(row.getKind())) {
            found.add(error("KIND_INVALID"));
        }
        if (row.getFieldName() != null && !ImportedHistoryService.FIELDS.contains(row.getFieldName())) {
            found.add(error("FIELD_INVALID"));
        }
        future(found, row.getEventAt());
        int longest = found.isEmpty() ? EventLoad.truncated(row) : 0;
        if (longest > 0) {
            found.add(new Finding("VALUE_TRUNCATED", Severity.WARNING, (long) longest));
        }
        return List.copyOf(found);
    }

    private Finding skippable(String code, @Nullable Long detail) {
        return new Finding(code, accepted.contains(code) ? Severity.WARNING : Severity.ERROR, detail);
    }

    private void future(List<Finding> found, @Nullable Instant at) {
        if (at != null && at.isAfter(now)) {
            found.add(error("DATE_IN_FUTURE"));
        }
    }

    private static List<Finding> parent(boolean staged) {
        List<Finding> found = new ArrayList<>();
        if (!staged) {
            found.add(error("PARENT_NOT_STAGED"));
        }
        return found;
    }

    private static void required(List<Finding> found, String field, @Nullable Object value) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            found.add(error(field + "_MISSING"));
        }
    }

    private static Finding error(String code) {
        return new Finding(code, Severity.ERROR, null);
    }
}
