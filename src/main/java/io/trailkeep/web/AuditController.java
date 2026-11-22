// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.audit.AuditRecord;
import io.trailkeep.audit.AuditTrailService;
import io.trailkeep.audit.AuditTrailService.AuditFilter;
import io.trailkeep.audit.AuditTrailService.AuditPage;
import io.trailkeep.casefile.CaseException;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.security.CaseAction;
import io.trailkeep.security.CasePermissionService;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.config.AppProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The audit trail for Compliance Admins (BUILD_PLAN §3.2 "View audit across all cases", §9; #274). A plain GET form
 * filters it by a required range of whole days in {@code app.timezone}, at most {@value #MAX_DAYS} (the seven days
 * ending today when none is given), and optionally by text in the actor's recorded name, the entity type, the action
 * and a case number; {@value #PAGE_SIZE} events a page, newest first, case-less events included. Only the security
 * module's audit scope opens it: anyone else, a System Admin included, gets 403, as on the other admin screens. A
 * range the form cannot prevent (backwards or too long) is the form again with the reason, 422; a value no field
 * accepts is a plain 422. A redacted value arrives as its length only. The screen reads and records nothing.
 */
@Controller
class AuditController {

    /** Events a page. */
    static final int PAGE_SIZE = 50;

    /** The most days one search covers; {@link AuditTrailService#MAX_RANGE} leaves room for a daylight-saving hour. */
    static final int MAX_DAYS = 31;

    private static final String VIEW = "web/audit";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    private static final Pattern PAGE = Pattern.compile("[1-9][0-9]{0,5}");

    private final CasePermissionService permissions;
    private final AuditTrailService trail;
    private final CaseService cases;
    private final CasePage page;
    private final InstantSource clock;
    private final ZoneId zone;

    AuditController(
            CasePermissionService permissions,
            AuditTrailService trail,
            CaseService cases,
            CasePage page,
            InstantSource clock,
            AppProperties app) {
        this.permissions = permissions;
        this.trail = trail;
        this.cases = cases;
        this.page = page;
        this.clock = clock;
        this.zone = app.timezone();
    }

    /**
     * The filters as the form shows them again; blank for a filter not given.
     *
     * @param from the first day
     * @param to the last day
     * @param actor text in the actor's name
     * @param entity the entity type
     * @param action the action
     * @param caseNumber the case number
     */
    record Form(String from, String to, String actor, String entity, String action, String caseNumber) {}

    @GetMapping("/admin/audit")
    @RateLimited(RateLimited.Route.SEARCH)
    String search(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        final CaseScope scope = permissions.auditScope(actor.permissions()).orElseThrow(Forbidden::new);
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate from = date(parameters.getFirst("from"), today.minusDays(6));
        LocalDate to = date(parameters.getFirst("to"), today);
        Form form = new Form(
                from.toString(),
                to.toString(),
                text(parameters.getFirst("actor")),
                code(parameters.getFirst("entity")),
                code(parameters.getFirst("action")),
                text(parameters.getFirst("case")).toUpperCase(Locale.ROOT));
        model.addAttribute("f", form);
        model.addAttribute("maxDays", MAX_DAYS);
        model.addAttribute("zone", zone.getId());
        model.addAttribute("rows", List.of());
        model.addAttribute("total", 0L);
        model.addAttribute("page", 1);
        model.addAttribute("pages", 1);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("rangeProblem", true);
            return VIEW;
        }
        Long caseId = null;
        if (!form.caseNumber().isEmpty()) {
            try {
                caseId = cases.actionable(actor, form.caseNumber(), CaseAction.VIEW)
                        .id();
            } catch (CaseException unknown) {
                model.addAttribute("caseUnknown", true);
                return VIEW;
            }
        }
        AuditFilter filter = new AuditFilter(
                from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(),
                blankToNull(form.actor()),
                blankToNull(form.entity()),
                blankToNull(form.action()),
                caseId);
        int number = pageNumber(parameters.getFirst("page"));
        AuditPage found = trail.search(scope, filter, (number - 1) * PAGE_SIZE, PAGE_SIZE);
        int pages = (int) Math.min(Math.max(1, (found.total() + PAGE_SIZE - 1) / PAGE_SIZE), Integer.MAX_VALUE);
        if (number > pages) {
            // A page past the end, perhaps from an old link: the last page instead.
            number = pages;
            found = trail.search(scope, filter, (number - 1) * PAGE_SIZE, PAGE_SIZE);
        }
        model.addAttribute("rows", found.records());
        model.addAttribute("caseNumbers", caseNumbers(actor, found.records()));
        model.addAttribute("total", found.total());
        model.addAttribute("page", number);
        model.addAttribute("pages", pages);
        return VIEW;
    }

    /** The numbers of the page's cases, by id, read through the viewer's scope. */
    private Map<Long, String> caseNumbers(Actor actor, List<AuditRecord> records) {
        Map<Long, String> numbers = new HashMap<>();
        cases.refs(
                        actor,
                        records.stream()
                                .map(AuditRecord::caseId)
                                .filter(Objects::nonNull)
                                .toList())
                .forEach((id, ref) -> numbers.put(id, ref.number().toString()));
        return numbers;
    }

    private static int pageNumber(@Nullable String requested) {
        if (requested != null && !PAGE.matcher(requested).matches()) {
            throw new Invalid();
        }
        return requested == null ? 1 : Integer.parseInt(requested);
    }

    private static LocalDate date(@Nullable String value, LocalDate otherwise) {
        if (value == null || value.isBlank()) {
            return otherwise;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException malformed) {
            throw new Invalid();
        }
    }

    /** An entity type or action: upper case letters, digits and underscores, as every recorded one is. */
    private static String code(@Nullable String value) {
        String code = text(value).toUpperCase(Locale.ROOT);
        if (!code.isEmpty() && !CODE.matcher(code).matches()) {
            throw new Invalid();
        }
        return code;
    }

    private static String text(@Nullable String value) {
        String text = value == null ? "" : value.strip();
        if (text.length() > 200) {
            throw new Invalid();
        }
        return text;
    }

    private static @Nullable String blankToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    /** Anyone without the audit scope. */
    static final class Forbidden extends DomainException {

        private static final long serialVersionUID = 1L;

        Forbidden() {
            super(Kind.FORBIDDEN, "the audit trail is for Compliance Admins");
        }
    }

    /** A value no field of the form accepts. */
    static final class Invalid extends DomainException {

        private static final long serialVersionUID = 1L;

        Invalid() {
            super(Kind.INVALID, "an audit filter no field accepts");
        }
    }
}
