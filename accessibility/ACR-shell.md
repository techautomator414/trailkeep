<!--
  SPDX-License-Identifier: Apache-2.0
  Copyright 2026 Charlie Genova and contributors
-->
# Trailkeep accessibility conformance report: application shell (DRAFT)

**Status: first draft, 2026-11-04. Not a conformance claim.** No manual test of any kind has been performed on
these screens: no keyboard-only walk-through, no screen reader, no 200 % or 400 % zoom, no reflow, no text-spacing
or forced-colors check. Everything below comes from reading the code and from the automated axe suite. The
owner's manual testing is needed to turn "needs manual confirmation" and "not evaluated" into findings. The script
for it is in section 6. Tracked in issue #30 (Phase 1 exit: "ACR draft for the shell").

This is the working draft that precedes the formal report. BUILD_PLAN §10.2 calls for an ITI VPAT 2.5 ACR and a
Trusted Tester v5 pass in Phase 6 (WP-29); this file is neither. It uses WCAG 2.2 Level A and AA success criteria
(BUILD_PLAN §10 targets 2.1 AA and checks the 2.2 additions where they apply).

## 1. Scope

| Item | Detail |
|---|---|
| Product | Trailkeep at `main` after #40, the screens listed below |
| In scope | Application shell (layout, navigation, skip link, live region, session-expiry dialog, error pages), team administration, case configuration (SLA policies, case types), the case page |
| Out of scope | Everything not built yet (case create form, case list, comments, attachments, reports, emails, and so on). The case page is included because it is on `main` and BUILD_PLAN §10.2 puts every page under the automated gate. |
| Not covered | The sign-in screen. It is rendered by the deploying organization's identity provider, not by Trailkeep (see SC 3.3.8). |
| Technology | Server-rendered Thymeleaf, Bootstrap 5.3.8 CSS only (no Bootstrap JS), htmx 2.0.11, one small script (`static/js/app.js`). JavaScript is required. |

### Screens and their templates

| Screen | Path | Template |
|---|---|---|
| Dashboard | `/` | `templates/web/dashboard.html` |
| Teams list and create | `/admin/teams` | `templates/user/teams.html` |
| Team page (details, members, grant role) | `/admin/teams/{id}` | `templates/user/team.html` |
| SLA policies list and create | `/admin/sla-policies` | `templates/casefile/sla-policies.html` |
| SLA policy page | `/admin/sla-policies/{id}` | `templates/casefile/sla-policy.html` |
| Case types list and create | `/admin/case-types` | `templates/casefile/case-types.html` |
| Case type page | `/admin/case-types/{id}` | `templates/casefile/case-type.html` |
| Case page | `/cases/{number}` | `templates/casefile/case.html` |
| Not found, other client errors, server error | any | `templates/error/404.html`, `4xx.html`, `5xx.html` |
| Session-expiry dialog (every page) | n/a | `templates/web/layout.html` |
| htmx problem fragment (403/404/409/422 swaps) | n/a | `templates/web/fragments/problem.html` |

## 2. What the automated suite actually covers

The axe-core 4.14.0 scan (`src/test/java/io/trailkeep/e2e/pages/ShellPage.java`) runs in Playwright Chromium
after a real OIDC login against the Keycloak dev realm, with rule tags `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`,
`wcag22a`, `wcag22aa`, and asserts **0 violations**. Each test also asserts no CSP violation or uncaught script
error on the console.

| Test class | Screens and states scanned (each at 0 violations) |
|---|---|
| `ShellAccessibilityIT` | Dashboard; session-expiry dialog open; 404 page; 500 page (no exception details leak). Also asserts the skip link is the first tab stop, htmx runs with the fixed configuration, no Bootstrap JavaScript, and no `<style>` elements |
| `TeamAdminAccessibilityIT` (as System Admin) | Teams list; teams list after a blank-name submit (422, `role=alert` visible); team page; team page after a blank-name submit |
| `CaseConfigAccessibilityIT` (as Compliance Admin) | SLA policies list and policy page, each plus its invalid state; case types list and case type page, each plus its invalid state |
| `CasePageAccessibilityIT` (as Compliance Admin) | Case page for a standard case and for a restricted, high-priority case (chips present). Also asserts the `<title>` starts with the case number and title |

Limits of that evidence, which govern how the table in section 4 is read:

- axe finds only a fraction of WCAG failures. A pass proves the absence of what its rules detect, not conformance.
- The assertion reads axe `violations` only. `incomplete` ("needs review") results, such as colour contrast over
  tinted or layered backgrounds, are not asserted or reported.
- One browser (Chromium), the Playwright default viewport, light mode, no zoom, no forced colors, no hover or
  keyboard-focus states, one user per role.
- Not scanned: `4xx.html` (403 and other client errors rendered as a full page), the htmx problem fragment
  after a swap, the Members table of a team with members, lists with many rows, and the empty states of the lists.
- axe's `target-size` rule (SC 2.5.8) is disabled by default in 4.14.0; this draft did not verify whether the
  tag-based run enables it, so 2.5.8 is not claimed as automated.

## 3. Findings from reading the code that need attention

These are observations, not test results. They are the places where the code does not yet follow BUILD_PLAN §10.1
or where a manual tester should look first.

1. **Form errors are not tied to fields.** A refused form (HTTP 422) re-renders the whole page with a single
   banner (`<p class="alert alert-danger" role="alert">`, for example `tk.team.invalid`: "Nothing changed."). The
   banner does not name the field, the field has no `aria-describedby` or `aria-invalid`, and focus is not moved
   to the banner. §10.1 "Forms" asks for errors tied to the field and summarized in a focusable error region.
   This bears on SC 3.3.1 and 3.3.3 and is likely to be "Partially Supports" until fixed. A `role="alert"` element
   present on initial page load is not reliably announced by screen readers; this needs a manual check.
2. **The main navigation links only to the Dashboard.** The admin screens (`/admin/teams`, `/admin/sla-policies`,
   `/admin/case-types`) are reachable only by typing the URL or from each other's pages. Relevant to SC 2.4.5
   (Multiple Ways), and to usability in general. The case page has no link in either direction yet.
3. **Bootstrap's default form-control border** is a pale grey on white. Its contrast against the page was not
   measured; this may fail SC 1.4.11 (Non-text Contrast). Measure it.
4. **Tables are not wrapped for narrow screens** (`class="table"` without a responsive wrapper). SC 1.4.10
   (Reflow) at 320 CSS px needs a manual check on the team members table and the lists.
5. **Without JavaScript the sign-out redirect to the provider is blocked** by the CSP `form-action 'self'`
   (HANDOFF §6); the application session still ends. BUILD_PLAN §10.3 states plainly that JavaScript is required.
6. **Icon characters as text** (the lock on the Restricted chip, `!` on High priority, the SLA state icon) are
   `aria-hidden` with adjacent visible text, which is the intended pattern. Check that they render sensibly in
   forced-colors mode.

## 4. Conformance table

Statuses, used exactly as defined here:

- **Supports — automated (axe)**: an axe rule in the suite really covers the criterion and the suite passes at
  0 violations. This still is not a manual confirmation.
- **Supports — by design, needs manual confirmation**: the code shows the intent (cited), but no automated check
  proves it. Treat as unverified.
- **Not evaluated — manual test required**: no evidence in the repository either way, or the code points to a
  likely gap (noted).

Where a criterion does not arise on these screens (for example, no time-based media), it is listed as "by design"
with that reason, and still needs a human to confirm that nothing was missed.

### Level A

| SC | Name | Status | Basis |
|---|---|---|---|
| 1.1.1 | Non-text Content | Supports — automated (axe) | Rules `image-alt`, `input-image-alt`, `svg-img-alt`, `role-img-alt`. The screens carry no images; decorative icon characters are `aria-hidden`. |
| 1.2.1 | Audio-only and Video-only (Prerecorded) | Supports — by design, needs manual confirmation | No audio or video in v1 (BUILD_PLAN §10.1 "Media and motion"). |
| 1.2.2 | Captions (Prerecorded) | Supports — by design, needs manual confirmation | As 1.2.1. |
| 1.2.3 | Audio Description or Media Alternative (Prerecorded) | Supports — by design, needs manual confirmation | As 1.2.1. |
| 1.3.1 | Info and Relationships | Supports — by design, needs manual confirmation | Landmarks (`header`, `nav` with label, `main`, `footer`), one `h1` per page, tables with `<caption>` and `<th scope>`, labels bound with `for`/`id`, `dl` for case facts, chips as a labelled list. Several axe structure rules pass, but they do not prove the semantics are right. Heading order and reading order need a screen reader. |
| 1.3.2 | Meaningful Sequence | Supports — by design, needs manual confirmation | Source order is the reading order; no CSS reordering beyond flex wrapping in the header. |
| 1.3.3 | Sensory Characteristics | Supports — by design, needs manual confirmation | Instructions in `messages.properties` do not rely on shape, position or colour; review the copy. |
| 1.4.1 | Use of Color | Supports — by design, needs manual confirmation | SLA and priority chips carry an icon and text, never colour alone (`case.html`; `.tk-sla-*` only tints). Link distinction in running text not checked. |
| 1.4.2 | Audio Control | Supports — by design, needs manual confirmation | No audio. |
| 2.1.1 | Keyboard | Supports — by design, needs manual confirmation | Native links, buttons, inputs, selects and `<dialog>` only; no click handlers on non-interactive elements. Not walked through with a keyboard. |
| 2.1.2 | No Keyboard Trap | Supports — by design, needs manual confirmation | Native modal `<dialog>` closes with Esc (`app.js` dialog helper). Not tried. |
| 2.1.4 | Character Key Shortcuts | Supports — by design, needs manual confirmation | No single-key shortcuts exist yet; BUILD_PLAN §10.1 requires a preference to disable them when they are added. |
| 2.2.1 | Timing Adjustable | Supports — by design, needs manual confirmation | Session-expiry dialog warns 120 s before the idle timeout and offers "Stay signed in" (`POST /session/extend`). The open dialog scans clean in `ShellAccessibilityIT`, but the timer, the extension and the announcement were not exercised by a person. The 8-hour absolute lifetime has no warning; check whether that is acceptable under the criterion's exceptions. |
| 2.2.2 | Pause, Stop, Hide | Supports — by design, needs manual confirmation | No moving, blinking or auto-updating content; reduced-motion media query in `app.css`. |
| 2.3.1 | Three Flashes or Below Threshold | Supports — by design, needs manual confirmation | No animation beyond short transitions. |
| 2.4.1 | Bypass Blocks | Supports — automated (axe) | Rule `bypass` plus landmarks; `ShellAccessibilityIT` asserts the skip link (`.tk-skip-link`) is the first tab stop. |
| 2.4.2 | Page Titled | Supports — automated (axe) | Rule `document-title` on every scanned page; `CasePageAccessibilityIT` also asserts the case title text. Whether each title is descriptive needs a human glance. |
| 2.4.3 | Focus Order | Supports — by design, needs manual confirmation | DOM order; the dialog returns focus to the opener (`app.js`); after an htmx swap focus moves to the first heading. Full-page 422 re-renders do not move focus (finding 1). |
| 2.4.4 | Link Purpose (In Context) | Supports — by design, needs manual confirmation | Link text is descriptive in the templates read ("All teams", policy and type names). Axe's `link-name` only proves a name exists. |
| 2.5.1 | Pointer Gestures | Supports — by design, needs manual confirmation | Only single-pointer clicks. |
| 2.5.2 | Pointer Cancellation | Supports — by design, needs manual confirmation | Native controls; the only custom handlers are on `click`. |
| 2.5.3 | Label in Name | Supports — by design, needs manual confirmation | Visible label text is the accessible name for inputs and buttons. The `label-content-name-mismatch` rule is experimental and was not part of the run. |
| 2.5.4 | Motion Actuation | Supports — by design, needs manual confirmation | No motion-triggered functions. |
| 3.1.1 | Language of Page | Supports — automated (axe) | Rules `html-has-lang`, `html-lang-valid`; `<html lang="en">` in `layout.html`. |
| 3.2.1 | On Focus | Supports — by design, needs manual confirmation | Nothing changes context on focus. The dialog opens only on a timer, which is the 2.2.1 behaviour. |
| 3.2.2 | On Input | Supports — by design, needs manual confirmation | Selects and checkboxes do not submit on change; every submit is an explicit button. |
| 3.2.6 | Consistent Help | Supports — by design, needs manual confirmation | The shell offers no help mechanism yet; the criterion applies when one is added. |
| 3.3.1 | Error Identification | Not evaluated — manual test required | Likely gap: a generic banner, no field named (finding 1). |
| 3.3.2 | Labels or Instructions | Supports — automated (axe) | Rules `label`, `select-name`, `form-field-multiple-labels`. Required fields use the native `required` attribute; the outcomes field has a hint tied with `aria-describedby`. Whether required status is also given in text needs a human. |
| 3.3.7 | Redundant Entry | Supports — by design, needs manual confirmation | No multi-step processes on these screens. |
| 4.1.2 | Name, Role, Value | Supports — automated (axe) | Rules `button-name`, `link-name`, `aria-valid-attr`, `aria-valid-attr-value`, `aria-allowed-attr`, `aria-roles`, `select-name`, `label` and related, on every scanned page and the open dialog. Custom behaviour is limited to the native dialog. |

### Level AA

| SC | Name | Status | Basis |
|---|---|---|---|
| 1.2.4 | Captions (Live) | Supports — by design, needs manual confirmation | No live media. |
| 1.2.5 | Audio Description (Prerecorded) | Supports — by design, needs manual confirmation | No video. |
| 1.3.4 | Orientation | Supports — by design, needs manual confirmation | No orientation lock in CSS or meta tags. |
| 1.3.5 | Identify Input Purpose | Supports — by design, needs manual confirmation | The administration forms collect names and numbers about teams, policies and case types, not about the user; no `autocomplete` token applies. Revisit when user-profile forms arrive. |
| 1.4.3 | Contrast (Minimum) | Supports — automated (axe) | Rule `color-contrast`. Caveats: `incomplete` results are not asserted; hover, focus and dialog-backdrop states are not scanned; the tinted chip backgrounds (`#fff4d6`, `#fdf2f1`) are covered only where axe could resolve them. |
| 1.4.4 | Resize Text | Not evaluated — manual test required | The viewport meta tag does not disable zoom (axe `meta-viewport` passes), but 200 % text resize without loss was not tried. |
| 1.4.5 | Images of Text | Supports — by design, needs manual confirmation | No images. |
| 1.4.10 | Reflow | Not evaluated — manual test required | Container, flex wrapping and the viewport meta tag are in place; tables are not wrapped (finding 4). |
| 1.4.11 | Non-text Contrast | Not evaluated — manual test required | The focus ring is designed at 3 px with a dark halo (`:focus-visible` in `app.css`), but input borders and chip borders were not measured (finding 3). |
| 1.4.12 | Text Spacing | Not evaluated — manual test required | Not tried with an overriding stylesheet or bookmarklet. |
| 1.4.13 | Content on Hover or Focus | Supports — by design, needs manual confirmation | No tooltips, popovers or menus exist. |
| 2.4.5 | Multiple Ways | Not evaluated — manual test required | Only the Dashboard link is in the navigation (finding 2); there is no search or site map yet. |
| 2.4.6 | Headings and Labels | Supports — by design, needs manual confirmation | Headings and labels come from `messages.properties`; descriptive quality needs review. |
| 2.4.7 | Focus Visible | Supports — by design, needs manual confirmation | Global `:focus-visible` outline with a forced-colors variant. No automated check of the rendered focus state. |
| 2.4.11 | Focus Not Obscured (Minimum) | Supports — by design, needs manual confirmation | The header is static, not sticky; no overlays apart from the modal dialog. Check at 400 % zoom. |
| 2.5.7 | Dragging Movements | Supports — by design, needs manual confirmation | No drag interactions. |
| 2.5.8 | Target Size (Minimum) | Supports — by design, needs manual confirmation | Navigation links `min-height: 24px`; Bootstrap buttons and inputs are larger. Axe `target-size` is not verified as part of the run (section 2). |
| 3.1.2 | Language of Parts | Supports — by design, needs manual confirmation | No content in another language. |
| 3.2.3 | Consistent Navigation | Supports — by design, needs manual confirmation | One layout fragment for every page. |
| 3.2.4 | Consistent Identification | Supports — by design, needs manual confirmation | Shared form fragments and message keys give the same labels to the same functions. |
| 3.3.3 | Error Suggestion | Not evaluated — manual test required | Likely gap: the banner does not say what to fix (finding 1). |
| 3.3.4 | Error Prevention (Legal, Financial, Data) | Not evaluated — manual test required | Applicability unclear: administration changes are audited and can be reversed by editing, but there is no review or confirm step. Decide whether the criterion applies. |
| 3.3.8 | Accessible Authentication (Minimum) | Not evaluated — manual test required | Trailkeep has no credential screens; sign-in is the identity provider's. The deploying organization's provider must be assessed on its own. |
| 4.1.3 | Status Messages | Supports — by design, needs manual confirmation | `#tk-live` (`role="status"`, polite) and `role="alert"` on problem banners, driven by `announce()` in `app.js`. Not heard in a screen reader. |

### Totals

| Status | Count |
|---|---|
| Supports — automated (axe) | 7 |
| Supports — by design, needs manual confirmation | 39 |
| Not evaluated — manual test required | 9 |
| Total criteria listed (WCAG 2.2 Level A and AA, 4.1.1 omitted) | 55 |

No criterion is claimed as supported on the strength of a human test, because none has been done.

## 5. Known limits of this draft

- No screen reader, keyboard, zoom, reflow, text-spacing, high-contrast or colour-vision testing has been done.
- Only Chromium has been used, by the automated suite.
- Section 508 Chapter 5 software criteria (platform settings, assistive-technology interoperability) are not
  assessed here.
- Documents the system produces (emails, reports, CSV) do not exist yet and are out of scope.
- WCAG 4.1.1 (Parsing) is obsolete in 2.2 and omitted.

## 6. Manual test script for the owner

Use a synthetic local environment: `docker compose up -d`, then
`./mvnw spring-boot:run -Dspring-boot.run.profiles=local`, and sign in as dev-realm users (see
`deploy/local/keycloak/README.md`). `system.admin` reaches `/admin/teams`; `compliance.admin` reaches the case
configuration screens. There is no case create form yet, so the case page needs a case created another way (the
case service is exercised by `CasePageAccessibilityIT`); test it once a case exists. Record each result next to the
criterion in section 4, with date, browser, assistive technology and version. Change a status only on evidence.

### 6.1 Keyboard only (no mouse)

1. Load `/`. Press Tab once: the skip link should appear and be clearly visible. Press Enter: focus lands in the
   main content, and the next Tab goes to the first control inside it, not back to the header.
2. Tab through the whole page, forwards and backwards (Shift+Tab). Confirm a visible focus indicator on every stop,
   a sensible order, and no trap. (2.1.1, 2.1.2, 2.4.3, 2.4.7)
3. On `/admin/teams`: create a team with the keyboard only; then submit a blank name. Note where focus is after the
   refusal and whether the error is reachable and clear. (3.3.1, 3.3.3, 2.4.3)
4. On a team page: grant a role with the selects (arrows, type-ahead), remove a member, and use the active
   checkbox with Space.
5. Repeat for an SLA policy and a case type, including the outcomes textarea.
6. Session dialog: wait for it (idle timeout minus 120 s; set `server.servlet.session.timeout` to `3m` for the
   test). Confirm focus moves into the dialog, Tab stays inside it, Esc closes it, and focus returns to where you
   were. Use "Stay signed in" and confirm the confirmation is announced. (2.2.1, 4.1.3)
7. Sign out with the keyboard.

### 6.2 Screen reader

Minimum: NVDA with Firefox on Windows. BUILD_PLAN §10.2 later adds JAWS with Chrome and VoiceOver with Safari.

1. Landmarks list: banner, navigation "Main", main, contentinfo. Headings list: one level 1, no skipped levels.
2. Each form field announces its label, its required state and its hint (the outcomes field).
3. Tables announce caption, column headers and row headers (the members table reads name, then role).
4. Submit a blank form: is the refusal announced? Is the field identified? (3.3.1)
5. Case page: the chips list is announced with its "case state" label and each chip as text; the SLA chip is
   understandable without the icon; the facts list reads as term and definition pairs.
6. Session dialog: announced as a dialog with its title and text.
7. 404 page: heading and link make sense.

### 6.3 Zoom, reflow, spacing

1. Browser zoom to 200 %: nothing is cut off, no overlap, all functions work. (1.4.4)
2. Zoom to 400 % on a 1280 px-wide window (320 CSS px): no horizontal scrolling for primary content, including the
   members table and the lists; focus is never hidden behind other content. (1.4.10, 2.4.11)
3. Apply the WCAG text-spacing values (line height 1.5, paragraph spacing 2x, letter spacing 0.12 em, word
   spacing 0.16 em) with a bookmarklet or user stylesheet: nothing is clipped. (1.4.12)
4. At 320 px wide in responsive mode, check that interactive targets are at least 24 px. (2.5.8)

### 6.4 Colour and display settings

1. Measure the contrast of input borders, chip borders and the focus ring against their backgrounds with a
   contrast tool; at least 3:1. (1.4.11)
2. Windows High Contrast or any forced-colors mode: header, buttons, chips, focus ring and the problem banner
   remain visible and distinguishable (`@media (forced-colors: active)` in `app.css`).
3. Colour-blindness simulation: SLA states still distinguishable by text and icon. (1.4.1)
4. `prefers-reduced-motion: reduce`: no animation remains.

### 6.5 Recording the result

Put each dated log in this directory (for example `accessibility/manual-logs/2026-MM-DD-nvda-firefox.md`) and update
the status and basis column of the affected rows. Findings go to issues labelled `a11y`. A PR that changes a status
must cite the log.
