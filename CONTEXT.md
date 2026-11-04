# CONTEXT — Trailkeep (open-source compliance case tracker)
_Read this before making any changes. Last updated: 2026-11-04._

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
**0.1.0 — walking skeleton merged; case handling not started.** `IMPLEMENTATION_PLAN.md` Phase 1
(Part D PRs 1–12) and WP-09, WP-10 are on `main`, every workflow green on both database vendors.
About a third of the work packages are done; WP-11 (`V4__case`, principal `CaseScope`) is next.
`HANDOFF.md` is the authoritative state-of-the-build document; `CHANGELOG.md` lists the notable changes per PR.
The two plans remain the contract. **Where the two disagree, the implementation plan wins.** Lines of
the plans superseded during implementation are recorded by ADRs (`adr/README.md`), not by editing the plans.

## Project rules
- **Public, Apache-2.0** by the owner's explicit decision (2026-11-02). Every file carries an
  SPDX header once code exists. `LICENSE` is the verbatim Apache 2.0 text; `NOTICE` carries the
  copyright line.
- **Nothing organization-specific in this repo**: no real data, credentials, hostnames,
  stakeholder names, or the name of the legacy tool it replaces. Stakeholder input is
  paraphrased. Organization-specific import mappings live outside this repo.
- **Section 508 is a release gate**, not a polish item (BUILD_PLAN §10).
- `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, `HANDOFF.md` exist; `docs/`
  (runbook) arrives with WP-30 and `accessibility/` (ACR) with the conformance work (#30).

## Key Files
| File | Purpose |
|---|---|
| `BUILD_PLAN.md` | What we are building (v0.5) |
| `IMPLEMENTATION_PLAN.md` | How we build it: audit register, standards, work packages, PR order, DoD, schedule, CI placement (v0.3) |
| `HANDOFF.md` | State of the build at 0.1.0, decisions, open issues, next work package |
| `CHANGELOG.md` | Keep a Changelog; notable changes per PR |
| `CLAUDE.md` | Working agreement for AI coding sessions |
| `LICENSE` | Apache License 2.0 |
| `NOTICE` | Copyright + third-party notice |
