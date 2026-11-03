<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0009: Server-rendered UI with Thymeleaf, htmx and Bootstrap CSS only, under an exact CSP

- **Status:** Accepted
- **Date:** 2026-11-03
- **Register rows:** IMPLEMENTATION_PLAN.md A.1 #10; A.2 #22, #23; A.3 #26
- **Plan sections:** BUILD_PLAN.md §7.2 (UI row), §7.3, §9 (Browser security, Injection), §10.1, §10.3

## Context and problem statement

The application handles sensitive case text and must meet Section 508. A client-side SPA brings hundreds of npm transitive dependencies, a harder CSP and a custom-component accessibility burden. Bootstrap's JavaScript and Popper set inline styles that a strict `style-src` blocks, and htmx by default injects a `<style>` element and can evaluate scripts.

## Decision drivers

- Strict CSP without `unsafe-inline`.
- Smallest dependency and scan surface.
- Real HTML and real forms for assistive technology.
- A vetted, small set of interactive components that we own.

## Considered options

1. React/Angular SPA with a REST API (rejected for v1).
2. Vaadin Flow (viable alternative, heavier bundle and npm dependencies).
3. Thymeleaf + htmx + Bootstrap 5 CSS only (BUILD_PLAN §7.3).

## Decision outcome

**Option 3.**

- **Exact CSP:** `default-src 'self'; script-src 'self' 'nonce-{n}'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`. A per-request nonce filter sets the header and exposes `${cspNonce}` (Spring Security does not generate nonces).
- **htmx fixed configuration** via the `htmx-config` meta tag: `allowEval=false`, `allowScriptTags=false`, `selfRequestsOnly=true`, `includeIndicatorStyles=false` (the indicator CSS ships in `app.css`), `historyCacheSize=0`, `refreshOnHistoryMiss=true` (no case text snapshots in `localStorage`). `hx-on*` and `hx-vals="js:…"` are banned.
- **Bootstrap CSS only**, no Bootstrap JS or Popper. One vendored `app.js` on native `<dialog>` and WAI-ARIA APG patterns, no bundler.
- **`th:utext` is banned**; the only unescaped path is `tk:safe`, accepting solely the sanitizer's `SafeHtml` type.
- `HX-Redirect` on 401/403 for htmx requests; fragment contract 200/422/409/5xx.
- **JavaScript is required**, stated plainly. "Accessible by default" is not claimed; a vetted component list is.

### Consequences

- **Good:** small attack surface, strict CSP, few SCA findings, SSR speed on an intranet.
- **Bad:** the team owns the accessibility of dialog, combobox, tabs and toast; less app-like for highly dynamic screens; htmx idioms must be learned.

## Confirmation

- Test that the `htmx-config` meta tag is present with the fixed values (WP-05).
- Semgrep rules banning `th:utext`, `hx-on*` and `hx-vals="js:…"` (with `semgrep --test` cases).
- WP-05 acceptance: no CSP violation in the console on load; axe 0 violations on shell and error pages.
- Playwright E2E with axe and Nu validator on every PR (Part G); CSP check in WP-28 security E2E.
