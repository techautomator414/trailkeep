// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.report.ReportException;
import io.trailkeep.report.ReportScheduleService;
import io.trailkeep.report.ReportScheduleService.Schedule;
import io.trailkeep.report.ReportScheduleService.ScheduleChange;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.config.AppProperties;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The report schedules (WP-23 part b2), Compliance Admins only: {@code /admin/report-schedules} lists every team with
 * its weekly aging report, and {@code /admin/report-schedules/{team}} edits one (enabled, weekday, hour). {@link
 * ReportScheduleService} runs first on every request, so anyone else gets 403 and an unknown or malformed team 404
 * before their input is looked at; a value no form offers re-renders the form with 422, and a stale version with 409,
 * the input kept and Reload offered instead of Save.
 */
@Controller
@RequestMapping("/admin/report-schedules")
class ReportScheduleController {

    private static final String FORM = "report/schedule";

    /**
     * The form as posted; the selects offer only valid values, so anything else is a 422.
     *
     * @param enabled the checkbox, absent when unticked
     * @param weekday the day
     * @param hour the hour
     * @param version the version the form was loaded with, kept as sent
     */
    record ScheduleForm(
            @Nullable Boolean enabled,
            @NotNull DayOfWeek weekday,
            @NotNull @Min(0) @Max(23) Integer hour,
            @Nullable String version) {}

    private final ReportScheduleService schedules;
    private final CasePage page;
    private final ZoneId zone;

    ReportScheduleController(ReportScheduleService schedules, CasePage page, AppProperties app) {
        this.schedules = schedules;
        this.page = page;
        this.zone = app.timezone();
    }

    @GetMapping
    String list(@AuthenticationPrincipal TrailkeepOidcUser principal, Model model) {
        model.addAttribute("schedules", schedules.schedules(page.actor(principal)));
        model.addAttribute("zone", zone.getId());
        return "report/schedules";
    }

    @GetMapping("/{team}")
    String edit(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable String team, Model model) {
        Schedule schedule = schedules.schedule(page.actor(principal), id(team));
        ScheduleForm values = new ScheduleForm(
                !schedule.scheduled() || schedule.enabled(),
                schedule.weekday(),
                schedule.hour(),
                Integer.toString(schedule.version()));
        return form(schedule, values, false, false, model);
    }

    @PostMapping("/{team}")
    String save(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String team,
            @Valid ScheduleForm form,
            BindingResult binding,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        Schedule schedule = schedules.schedule(actor, id(team));
        if (binding.hasErrors()) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return form(schedule, form, true, false, model);
        }
        ScheduleChange change = new ScheduleChange(Boolean.TRUE.equals(form.enabled()), form.weekday(), form.hour());
        try {
            schedules.save(actor, schedule.teamId(), version(form.version()), change);
        } catch (ReportException refused) {
            if (refused.kind() != Kind.CONFLICT) {
                throw refused;
            }
            response.setStatus(HttpStatus.CONFLICT.value());
            return form(schedules.schedule(actor, schedule.teamId()), form, false, true, model);
        }
        return "redirect:/admin/report-schedules";
    }

    private String form(Schedule schedule, ScheduleForm values, boolean invalid, boolean conflict, Model model) {
        model.addAttribute("s", schedule);
        model.addAttribute("values", values);
        model.addAttribute("invalid", invalid);
        model.addAttribute("conflict", conflict);
        model.addAttribute("days", DayOfWeek.values());
        model.addAttribute("zone", zone.getId());
        return FORM;
    }

    /** A team id as sent; anything unreadable names no team. */
    private static long id(String team) {
        try {
            return Long.parseLong(team);
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }

    /** The version the form was loaded with; anything unreadable can only be stale. */
    private static int version(@Nullable String version) {
        try {
            return Integer.parseInt(String.valueOf(version));
        } catch (NumberFormatException unreadable) {
            return Integer.MIN_VALUE;
        }
    }
}
