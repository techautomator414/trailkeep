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
**PLAN ONLY — no code yet.** Two documents:
- `BUILD_PLAN.md` v0.3 — what we are building: scope decisions, roles and permissions, flows,
  domain model, SLA engine, architecture, Oracle/PG portability rules, security design,
  accessibility program, open-source governance, scanning strategy, testing, deployment, import
  of existing records, phases, risks, open questions (§19), DDL and CI sketches.
- `IMPLEMENTATION_PLAN.md` v0.1 — how we build it: the audit decisions register (Part A, the
  result of an author pass plus an independent hostile review), engineering standards (Part B),
  work packages WP-01…30 with sizes and dependencies (Part C), the first twelve PRs (Part D),
  definition of done (Part E) and the re-baselined schedule (Part F). **Where the two disagree,
  the implementation plan wins.**
Phase 0 (discovery) answers the BUILD_PLAN §19 questions before any code is written.

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
