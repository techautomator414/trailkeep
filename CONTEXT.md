# CONTEXT — Trailkeep (open-source compliance case tracker)
_Read this before making any changes. Last updated: 2026-11-02._

## What This Is
Trailkeep is a small, opinionated compliance case tracker: cases with an owner, type, age,
comments and attachments; SLA-driven automatic alerts; an automated aging report; tracked
"request an update" emails; a single closure-approval gate; restricted-case sensitivity;
append-only audit; Section 508 / WCAG 2.1 AA conformance; and a zero-finding security
scanning posture. Java 21 / Spring Boot, Oracle in production with PostgreSQL for local/CI
parity, Thymeleaf + htmx UI, SSO via the deploying organization's identity provider.

## Location
`~/projects/trailkeep/` — intended public repo `github.com/techautomator414/trailkeep`.

## Status
**PLAN ONLY — no code yet.** The full build plan is `BUILD_PLAN.md` v0.2 (scope decisions,
roles and permissions, flows, domain model, SLA engine, architecture, Oracle/PG portability
rules, security design, accessibility program, open-source governance, scanning strategy,
testing, deployment, import of existing records, 7-phase delivery plan, risks, open questions,
DDL and CI sketches). Phase 0 (discovery) answers the §18 questions before any code is written.

## Project rules
- **Public, Apache-2.0** by the owner's explicit decision (2026-11-02). Every file carries an
  SPDX header once code exists. `LICENSE` is the verbatim Apache 2.0 text; `NOTICE` carries the
  copyright line.
- **Nothing organization-specific in this repo**: no real data, credentials, hostnames,
  stakeholder names, or the name of the legacy tool it replaces. Stakeholder input is
  paraphrased. Organization-specific import mappings live outside this repo.
- **Section 508 is a release gate**, not a polish item (BUILD_PLAN §10).
- `README.md`, `docs/`, `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md` are still to be
  written — on this server they require a maintenance elevation (doc-gate).

## Key Files
| File | Purpose |
|---|---|
| `BUILD_PLAN.md` | The complete build plan (v0.2) |
| `LICENSE` | Apache License 2.0 |
| `NOTICE` | Copyright + third-party notice |
