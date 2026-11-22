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
import io.trailkeep.report.AuditExportService;
import io.trailkeep.report.AuditExportService.Ready;
import io.trailkeep.report.AuditExportService.TooMany;
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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The audit trail for Compliance Admins (BUILD_PLAN §3.2 "View audit across all cases", §9; #274). A plain GET form
 * filters it by a required range of whole days in {@code app.timezone}, at most {@value #MAX_DAYS} (the seven days
 * ending today when none is given), and optionally by text in the actor's recorded name, the entity type, the action
 * and a case number; {@value #PAGE_SIZE} events a page, newest first, case-less events included. Only the security
 * module's audit scope opens it: anyone else, a System Admin included, gets 403, as on the other admin screens. A
 * range the form cannot prevent (backwards or too long) is the form again with the reason, 422; a value no field
 * accepts is a plain 422. A redacted value arrives as its length only. The screen reads and records nothing; the CSV
 * export of the same filter is audited by {@link AuditExportService}.
 */
@Controller
class AuditController {

    /** Events a page. */
    static final int PAGE_SIZE = 50;

    /** The highest page number accepted: six digits, so an offset never exceeds 49,999,950. */
    static final int MAX_PAGE = 999_999;

    /** The most days one search covers; {@link AuditTrailService#MAX_RANGE} leaves room for a daylight-saving hour. */
    static final int MAX_DAYS = 31;

    private static final String VIEW = "web/audit";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    private static final Pattern PAGE = Pattern.compile("[1-9][0-9]{0,5}");

    private final CasePermissionService permissions;
    private final AuditTrailService trail;
    private final AuditExportService exports;
    private final CaseService cases;
    private final CasePage page;
    private final InstantSource clock;
    private final ZoneId zone;

    AuditController(
            CasePermissionService permissions,
            AuditTrailService trail,
            AuditExportService exports,
            CaseService cases,
            CasePage page,
            InstantSource clock,
            AppProperties app) {
        this.permissions = permissions;
        this.trail = trail;
        this.exports = exports;
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
        CaseScope scope = permissions.auditScope(actor.permissions()).orElseThrow(Forbidden::new);
        AuditFilter filter = filter(actor, parameters, response, model);
        if (filter != null) {
            list(actor, scope, filter, pageNumber(parameters.getFirst("page")), model);
        }
        return VIEW;
    }

    /**
     * The same filter as CSV ({@link AuditExportService}): an attachment named after the time of export in {@code
     * app.timezone}. A filter matching more than {@code app.export.max-rows} events is the screen's first page
     * again with the reason, 422, and nothing is exported or audited.
     */
    @GetMapping("/admin/audit/export.csv")
    @RateLimited(RateLimited.Route.SEARCH)
    ModelAndView export(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletResponse response) {
        Actor actor = page.actor(principal);
        CaseScope scope = permissions.auditScope(actor.permissions()).orElseThrow(Forbidden::new);
        ExtendedModelMap model = new ExtendedModelMap();
        AuditFilter filter = filter(actor, parameters, response, model);
        if (filter == null) {
            return new ModelAndView(VIEW, model);
        }
        Form form = (Form) Objects.requireNonNull(model.getAttribute("f"));
        String query = UriComponentsBuilder.newInstance()
                .queryParam("from", form.from())
                .queryParam("to", form.to())
                .queryParam("actor", form.actor())
                .queryParam("entity", form.entity())
                .queryParam("action", form.action())
                .queryParam("case", form.caseNumber())
                .build()
                .getQuery();
        return switch (exports.export(actor, scope, filter, Objects.requireNonNull(query))) {
            case Ready ready -> new ModelAndView(csv(ready));
            case TooMany tooMany -> {
                response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
                list(actor, scope, filter, 1, model);
                model.addAttribute("exportProblem", tooMany);
                yield new ModelAndView(VIEW, model);
            }
        };
    }

    /**
     * Reads the filters into the model as the form shows them; {@code null} when the page as it stands is the answer:
     * a range problem (422) or a case number no case has.
     */
    private @Nullable AuditFilter filter(
            Actor actor, MultiValueMap<String, String> parameters, HttpServletResponse response, Model model) {
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
            return null;
        }
        Long caseId = null;
        if (!form.caseNumber().isEmpty()) {
            try {
                caseId = cases.actionable(actor, form.caseNumber(), CaseAction.VIEW)
                        .id();
            } catch (CaseException unknown) {
                model.addAttribute("caseUnknown", true);
                return null;
            }
        }
        return new AuditFilter(
                from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(),
                form.actor().isEmpty() ? null : form.actor(),
                form.entity().isEmpty() ? null : form.entity(),
                form.action().isEmpty() ? null : form.action(),
                caseId);
    }

    private void list(Actor actor, CaseScope scope, AuditFilter filter, int requested, Model model) {
        int number = requested;
        AuditPage found = trail.search(scope, filter, offset(number), PAGE_SIZE);
        int pages = (int) Math.min(Math.max(1, (found.total() + PAGE_SIZE - 1) / PAGE_SIZE), MAX_PAGE);
        if (number > pages) {
            // A page past the end, perhaps from an old link: the last page instead.
            number = pages;
            found = trail.search(scope, filter, offset(number), PAGE_SIZE);
        }
        model.addAttribute("rows", found.records());
        model.addAttribute("caseNumbers", caseNumbers(actor, found.records()));
        model.addAttribute("total", found.total());
        model.addAttribute("page", number);
        model.addAttribute("pages", pages);
    }

    /** Writes the CSV as the response body, an attachment that is never cached or sniffed. */
    private View csv(Ready export) {
        String fileName = "audit-" + STAMP.format(clock.instant().atZone(zone)) + ".csv";
        return (model, request, response) -> {
            response.setContentType("text/csv;charset=UTF-8");
            // The name is "audit-" and digits, so it needs no quoting beyond the quotes.
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            export.writeTo(response.getOutputStream());
        };
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

    /** The rows before a page, checked against {@link #MAX_PAGE} where it is computed. */
    private static int offset(int number) {
        if (number < 1 || number > MAX_PAGE) {
            throw new Invalid();
        }
        return Math.toIntExact(((long) number - 1) * PAGE_SIZE);
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
