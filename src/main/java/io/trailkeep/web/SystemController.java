// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.document.ScanEngineService;
import io.trailkeep.notification.OutboxStatusService;
import io.trailkeep.notification.OutboxStatusService.Backlog;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.sla.JobHealth;
import io.trailkeep.sla.JobRuns;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.RoleConflict;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The System screen (WP-25, BUILD_PLAN §3.1 "System health, scheduler, scan engine", §4.6): System Admins and
 * Compliance Admins see each scheduled job's last success on any replica ({@code JOB_RUN}, the cluster truth), whether
 * it is late against its health indicator's grace, the outbox's backlog and failures, whether clamd answers, with its
 * version, and the users whose System Admin claim is withheld because they hold team roles (the durable list from the
 * audit trail). Anyone else gets 403. It reads, never writes, and shows no case content. Times are in
 * {@code app.timezone}.
 */
@Controller
class SystemController {

    private final JobRuns runs;
    private final Map<String, JobHealth> checks;
    private final OutboxStatusService outbox;
    private final ScanEngineService scanner;
    private final UserDirectoryService directory;
    private final InstantSource clock;
    private final ZoneId zone;

    SystemController(
            JobRuns runs,
            List<JobHealth> checks,
            OutboxStatusService outbox,
            ScanEngineService scanner,
            UserDirectoryService directory,
            InstantSource clock,
            AppProperties app) {
        this.runs = runs;
        this.checks = checks.stream().collect(Collectors.toMap(JobHealth::job, Function.identity()));
        this.outbox = outbox;
        this.scanner = scanner;
        this.directory = directory;
        this.clock = clock;
        this.zone = app.timezone();
    }

    /** What the screen says about a job against its health indicator. */
    enum JobState {
        /** Last success within the grace period. */
        ON_TIME,
        /** Last success longer ago than the grace period: the job's health indicator is down. */
        LATE,
        /** It has never succeeded. */
        NEVER,
        /** No health indicator watches it (the purge, which runs as its own CronJob). */
        UNCHECKED
    }

    /**
     * One job line.
     *
     * @param job the job's name
     * @param lastSuccess its last success in {@code app.timezone}, to the minute, or {@code null} if it never ran
     * @param items how many items that run handled
     * @param state on time, late, never or unchecked
     */
    record JobLine(String job, @Nullable LocalDateTime lastSuccess, int items, JobState state) {}

    /**
     * A user whose System Admin claim is withheld because they hold team roles.
     *
     * @param name their display name
     * @param seen when their latest sign-in recorded the conflict, in {@code app.timezone}, to the minute
     */
    record ConflictLine(String name, LocalDateTime seen) {}

    @GetMapping("/admin/system")
    String show(@AuthenticationPrincipal TrailkeepOidcUser principal, Model model) {
        UserPermissions user = directory.permissionsOf(principal.userId());
        if (Collections.disjoint(user.globalRoles(), List.of(GlobalRole.SYSTEM_ADMIN, GlobalRole.COMPLIANCE_ADMIN))) {
            throw new Forbidden();
        }
        Instant now = clock.instant();
        Map<String, JobLine> jobs = new TreeMap<>();
        checks.keySet().forEach(job -> jobs.put(job, new JobLine(job, null, 0, JobState.NEVER)));
        for (JobRuns.Run run : runs.all()) {
            JobHealth check = checks.get(run.job());
            JobState state = check == null
                    ? JobState.UNCHECKED
                    : Duration.between(run.lastSuccessAt(), now).compareTo(check.grace()) > 0
                            ? JobState.LATE
                            : JobState.ON_TIME;
            jobs.put(run.job(), new JobLine(run.job(), local(run.lastSuccessAt()), run.items(), state));
        }
        Backlog backlog = outbox.backlog();
        Instant oldest = backlog.oldestPendingAt();
        model.addAttribute("jobs", List.copyOf(jobs.values()));
        model.addAttribute("outbox", backlog);
        model.addAttribute("oldestPending", oldest == null ? null : local(oldest));
        model.addAttribute("scanner", scanner.status());
        List<RoleConflict> conflicts = directory.roleConflicts();
        Map<Long, String> names = directory.displayNames(
                conflicts.stream().map(RoleConflict::userId).toList());
        model.addAttribute(
                "conflicts",
                conflicts.stream()
                        .map(c -> new ConflictLine(names.getOrDefault(c.userId(), ""), local(c.detectedAt())))
                        .toList());
        return "web/system";
    }

    private LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, zone).truncatedTo(ChronoUnit.MINUTES);
    }

    /** Anyone but a System Admin or a Compliance Admin. */
    static final class Forbidden extends DomainException {

        private static final long serialVersionUID = 1L;

        Forbidden() {
            super(Kind.FORBIDDEN, "the System screen is for System Admins and Compliance Admins");
        }
    }
}
