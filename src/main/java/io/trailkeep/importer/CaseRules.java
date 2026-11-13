// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CaseImportService.ImportCaseType;
import io.trailkeep.casefile.CaseImportService.ImportHold;
import io.trailkeep.casefile.CaseImportService.ImportedCase;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.importer.FieldTransformer.Issue;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The dry run's rules for one staged case (WP-26 part q, design §3 step 3, §6.2, §7.1), checked against Trailkeep's
 * teams and case types as they are now, so a case the load would refuse is an issue before anyone signs off. Pure:
 * the same case and reference data give the same issues. Every issue is a code and a severity, never a value:
 *
 * <ul>
 *   <li>a required field that is empty: {@code <FIELD>_MISSING} (ERROR), for title, description, team, case type,
 *       status, opening time and identification date, and for a closed case its closing time and outcome;
 *   <li>a status other than {@code OPEN} or {@code CLOSED} ({@code STATUS_INVALID}), a closing time on an open case
 *       ({@code CLOSED_AT_ON_OPEN_CASE}), a case closed before it opened ({@code CLOSED_BEFORE_OPENED}), a date in the
 *       future ({@code DATE_IN_FUTURE}), a priority or sensitivity Trailkeep does not have, a title or subject longer
 *       than the case's column holds ({@code TITLE_TOO_LONG}, {@code SUBJECT_TOO_LONG}), a description longer than a
 *       case takes that the mapping does not continue in a comment ({@code DESCRIPTION_TOO_LONG}, {@link
 *       CaseComments}): ERROR;
 *   <li>a team no Trailkeep team is named after ({@code TEAM_UNKNOWN}, FATAL: the mapping is wrong, §7.1), or an
 *       inactive one ({@code TEAM_INACTIVE}, ERROR);
 *   <li>a case type no Trailkeep type is named after ({@code CASE_TYPE_UNKNOWN}, FATAL), or an inactive one for an open
 *       case ({@code CASE_TYPE_INACTIVE}, ERROR; closed history may keep it);
 *   <li>a closed case whose outcome is not one of its case type's outcomes ({@code CLOSURE_OUTCOME_NOT_ALLOWED},
 *       FATAL: the closure could not be written, and the mapping's outcome lookup is wrong);
 *   <li>a case with a legacy ACL when {@code visibility.onUnrepresentableAcl} is {@code fail} ({@code
 *       ACL_UNREPRESENTABLE}, ERROR).
 * </ul>
 *
 * <p>A case on legal hold in the legacy system, or closed so long ago that the archive job would take it at its next
 * run, is no issue: the load creates it on legal hold ({@link #holdReason}, WP-26 part ad), so nothing is archived or
 * purged before someone reviews it.
 */
final class CaseRules {

    private static final Set<String> PRIORITIES = names(CasePriority.values());

    private static final Set<String> SENSITIVITIES = names(Sensitivity.values());

    /** {@code CASE_RECORD.TITLE} and {@code SUBJECT_TEXT} (V4), in characters, as {@code CaseFields} declares. */
    private static final int MAX_TITLE = 200;

    private static final int MAX_SUBJECT = 1000;

    private final Map<String, Boolean> teams;
    private final Map<String, ImportCaseType> caseTypes;
    private final CaseComments texts;
    private final boolean aclFails;
    private final boolean aclRestricts;
    private final double maxErrorRate;
    private final Instant now;
    private final LocalDate today;

    /**
     * Rules against the reference data of this moment.
     *
     * @param mapping the validated mapping
     * @param teams each team's name and whether it is active
     * @param caseTypes each case type by name
     * @param now the dry run's time
     * @param zone the application's time zone, in which identification dates are days
     */
    CaseRules(
            JsonNode mapping,
            Map<String, Boolean> teams,
            Map<String, ImportCaseType> caseTypes,
            Instant now,
            ZoneId zone) {
        this.teams = Map.copyOf(teams);
        this.caseTypes = Map.copyOf(caseTypes);
        this.texts = new CaseComments(mapping);
        String acl = mapping.at("/visibility/onUnrepresentableAcl").asString("restricted");
        this.aclFails = "fail".equals(acl);
        this.aclRestricts = "restricted".equals(acl);
        this.maxErrorRate = mapping.at("/limits/maxErrorRate").asDouble(0.0);
        this.now = now;
        this.today = LocalDate.ofInstant(now, zone);
    }

    /**
     * Checks one staged case.
     *
     * @param row the case
     * @return its issues, in a fixed order; empty when the load would take it
     */
    List<Issue> check(StagedCase row) {
        List<Issue> issues = new ArrayList<>();
        final boolean closed = "CLOSED".equals(row.getStatus());
        required(issues, "TITLE", row.getTitle());
        required(issues, "DESCRIPTION", row.getDescription());
        required(issues, "TEAM", row.getTeamKey());
        required(issues, "CASE_TYPE", row.getCaseTypeKey());
        required(issues, "STATUS", row.getStatus());
        required(issues, "OPENED_AT", row.getOpenedAt());
        required(issues, "IDENTIFIED_ON", row.getIdentifiedOn());
        if (closed) {
            required(issues, "CLOSED_AT", row.getClosedAt());
            required(issues, "CLOSURE_OUTCOME", row.getClosureOutcome());
        } else if (row.getStatus() != null && !"OPEN".equals(row.getStatus())) {
            issues.add(error("STATUS_INVALID"));
        } else if (row.getClosedAt() != null) {
            issues.add(error("CLOSED_AT_ON_OPEN_CASE"));
        }
        team(row.getTeamKey(), issues);
        caseType(row, closed, issues);
        dates(row, issues);
        values(row, issues);
        return List.copyOf(issues);
    }

    /**
     * {@code limits.maxErrorRate}: the share of an entity's rows that may have an ERROR (design §3).
     *
     * @return it, 0 when the mapping sets none
     */
    double maxErrorRate() {
        return maxErrorRate;
    }

    private void values(StagedCase row, List<Issue> issues) {
        if (row.getPriority() != null && !PRIORITIES.contains(row.getPriority())) {
            issues.add(error("PRIORITY_INVALID"));
        }
        if (row.getSensitivity() != null && !SENSITIVITIES.contains(row.getSensitivity())) {
            issues.add(error("SENSITIVITY_INVALID"));
        }
        if (aclFails && Boolean.TRUE.equals(row.getLegacyAcl())) {
            issues.add(error("ACL_UNREPRESENTABLE"));
        }
        lengths(row, issues);
    }

    /** The texts a case's columns or {@code CaseFields} cannot hold. */
    private void lengths(StagedCase row, List<Issue> issues) {
        if (longer(row.getTitle(), MAX_TITLE)) {
            issues.add(error("TITLE_TOO_LONG"));
        }
        if (longer(row.getSubjectText(), MAX_SUBJECT)) {
            issues.add(error("SUBJECT_TOO_LONG"));
        }
        if (row.getDescription() != null && texts.tooLong(row.getDescription())) {
            issues.add(error("DESCRIPTION_TOO_LONG"));
        }
    }

    /** Whether a text has more characters (code points, as both databases count them) than a column holds. */
    private static boolean longer(@Nullable String text, int max) {
        return text != null && text.codePointCount(0, text.length()) > max;
    }

    private void team(@Nullable String key, List<Issue> issues) {
        Boolean active = key == null ? Boolean.TRUE : teams.get(key);
        if (active == null) {
            issues.add(new Issue("TEAM_UNKNOWN", Severity.FATAL));
        } else if (!active) {
            issues.add(error("TEAM_INACTIVE"));
        }
    }

    private void caseType(StagedCase row, boolean closed, List<Issue> issues) {
        ImportCaseType type = row.getCaseTypeKey() == null ? null : caseTypes.get(row.getCaseTypeKey());
        if (row.getCaseTypeKey() != null && type == null) {
            issues.add(new Issue("CASE_TYPE_UNKNOWN", Severity.FATAL));
        }
        if (type == null) {
            return;
        }
        if (!closed && !type.active()) {
            issues.add(error("CASE_TYPE_INACTIVE"));
        }
        if (closed && row.getClosureOutcome() != null && !type.outcomes().contains(row.getClosureOutcome())) {
            issues.add(new Issue("CLOSURE_OUTCOME_NOT_ALLOWED", Severity.FATAL));
        }
    }

    /**
     * Why a case is held when loaded at {@code at} (design §7.1): on legal hold in the legacy system, or closed at or
     * before the archive job's cut-off for its type at that instant (WP-24's rule).
     *
     * @param row the case
     * @param at when it is loaded
     * @return the reason, or {@code null} when it is not held
     */
    @Nullable
    ImportHold holdReason(StagedCase row, Instant at) {
        if (Boolean.TRUE.equals(row.getLegalHold())) {
            return ImportHold.LEGAL_HOLD;
        }
        return pastRetention(row, at) ? ImportHold.PAST_RETENTION : null;
    }

    /**
     * Whether the archive job running at {@code at} would take a case were it loaded unheld: closed, at or before its
     * type's cut-off (calendar years in UTC, as {@code CaseRetentionService} computes it).
     *
     * @param row the case
     * @param at the instant
     * @return true if it is past its retention then
     */
    boolean pastRetention(StagedCase row, Instant at) {
        ImportCaseType type = row.getCaseTypeKey() == null ? null : caseTypes.get(row.getCaseTypeKey());
        Instant closedAt = row.getClosedAt();
        return type != null
                && "CLOSED".equals(row.getStatus())
                && closedAt != null
                && !closedAt.isAfter(at.atZone(ZoneOffset.UTC)
                        .minusYears(type.retentionYears())
                        .toInstant());
    }

    /**
     * The visibility a case would load with (design §7.1): its mapped sensitivity, {@code STANDARD} when it has none,
     * and {@code RESTRICTED} for a legacy ACL under {@code visibility.onUnrepresentableAcl: restricted} (the default).
     *
     * @param row the case
     * @return the sensitivity's name
     */
    String visibility(StagedCase row) {
        if (aclRestricts && Boolean.TRUE.equals(row.getLegacyAcl())) {
            return Sensitivity.RESTRICTED.name();
        }
        return row.getSensitivity() == null ? Sensitivity.STANDARD.name() : row.getSensitivity();
    }

    /**
     * The case the import API writes for a row these rules passed ({@link #check} found nothing), held as {@link
     * #holdReason} says at these rules' instant, the load's.
     *
     * @param row the case
     * @param sourceId the mapping's {@code source.id}, which prefixes its {@code LEGACY_REF}
     * @param teamId its team
     * @param owner its owner, as {@link PeopleMatcher#owner} chose them
     * @param description its description as a description holds it
     * @return the case to import
     */
    ImportedCase imported(StagedCase row, String sourceId, long teamId, PeopleMatcher.Owner owner, String description) {
        ImportCaseType type = Objects.requireNonNull(caseTypes.get(row.getCaseTypeKey()), "checked");
        CaseFields fields = new CaseFields(
                type.id(),
                Objects.requireNonNull(row.getTitle(), "checked"),
                row.getPriority() == null ? CasePriority.NORMAL : CasePriority.valueOf(row.getPriority()),
                Objects.requireNonNull(row.getIdentifiedOn(), "checked"),
                teamId,
                owner.userId(),
                row.getSubjectText(),
                description,
                Sensitivity.valueOf(visibility(row)));
        return new ImportedCase(
                fields,
                sourceId + ":" + row.getKey().legacyKey(),
                row.getLegacyNumber(),
                owner.nameSnapshot(),
                CaseStatus.valueOf(Objects.requireNonNull(row.getStatus(), "checked")),
                Objects.requireNonNull(row.getOpenedAt(), "checked"),
                row.getClosedAt(),
                holdReason(row, now));
    }

    private void dates(StagedCase row, List<Issue> issues) {
        Instant opened = row.getOpenedAt();
        Instant closedAt = row.getClosedAt();
        LocalDate identifiedOn = row.getIdentifiedOn();
        if (opened != null && closedAt != null && closedAt.isBefore(opened)) {
            issues.add(error("CLOSED_BEFORE_OPENED"));
        }
        if ((opened != null && opened.isAfter(now))
                || (closedAt != null && closedAt.isAfter(now))
                || (identifiedOn != null && identifiedOn.isAfter(today))) {
            issues.add(error("DATE_IN_FUTURE"));
        }
    }

    private static void required(List<Issue> issues, String field, @Nullable Object value) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            issues.add(error(field + "_MISSING"));
        }
    }

    private static Issue error(String code) {
        return new Issue(code, Severity.ERROR);
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
