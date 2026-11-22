# CONTEXT — Trailkeep (open-source compliance case tracker)
_Read this before making any changes. Last updated: 2026-11-22 (0.3.0)._

## What This Is
Trailkeep is a small, opinionated compliance case tracker: cases with an owner, type, age,
comments and attachments; SLA-driven automatic alerts; an automated aging report; tracked
"request an update" emails; a single closure-approval gate; restricted-case sensitivity;
append-only audit; Section 508 / WCAG 2.1 AA conformance; and a zero-finding security
scanning posture. Java 21 / Spring Boot, Oracle in production with PostgreSQL for local/CI
parity, Thymeleaf + htmx UI, SSO via the deploying organization's identity provider.

## Location
Public repository `github.com/techautomator414/trailkeep`.

## Status
**0.3.0 — case handling end to end, hardened; Phase 6 under way.** 0.1.0 was the walking skeleton
(`IMPLEMENTATION_PLAN.md` Phase 1, Part D PRs 1–12, with WP-09 and WP-10). 0.2.0 added Phases 2 to 5
(WP-11 to WP-27 with WP-17b: cases, comments, attachments, lists and exports, history, mail and alerts,
request update, closure, the SLA evaluator, reminders, dashboard, reports, retention and purge, the
System screen, the legacy importer, preferences) and WP-28 (authenticated DAST and the security E2E
suite). 0.3.0 adds no work package: it closes the security test map's and the threat model's follow-ups
(control characters, per-person rate limits, verified TLS for importer sources with ADR-0014 accepted, the threat
model and data map, manifest tests and the purge's own egress policy, image verification after release, the Compliance
Admin audit screen and export, the scheduled staging TTL, two concurrency fixes, the split DAST scan, CI resilience).
31 of 33 work packages are done. What remains for 1.0.0: WP-29 (the Section 508 conformance
pass with a certified human tester), WP-30 (500k performance, restore drill, runbook, evidence pack,
1.0.0) and the open issues listed in `HANDOFF.md` section 1. SonarCloud and Dependency-Check are
intentionally off for 0.3.0 until the owner sets their secrets (#30).
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
  holds the importer design and guides, performance notes and bug hunts (the runbook arrives with
  WP-30); `accessibility/ACR-shell.md` is the draft ACR, finished by WP-29; `security/TEST_MAP.md`
  maps every security control to its check; `security/THREAT_MODEL.md` and `security/DATA_MAP.md` are the STRIDE
  threat model and the data map.

## Key Files
| File | Purpose |
|---|---|
| `BUILD_PLAN.md` | What we are building (v0.5) |
| `IMPLEMENTATION_PLAN.md` | How we build it: audit register, standards, work packages, PR order, DoD, schedule, CI placement (v0.3) |
| `HANDOFF.md` | State of the build at 0.3.0, decisions, open issues, what remains for 1.0.0 |
| `CHANGELOG.md` | Keep a Changelog; notable changes per PR, cut per release (0.1.0, 0.2.0, 0.3.0) |
| `adr/README.md` | Architecture decision records, including those that superseded a line of the plans |
| `security/TEST_MAP.md` | Every security control with the check that verifies it, and the gaps |
| `security/THREAT_MODEL.md` | STRIDE threat model and residual risks; `security/DATA_MAP.md` is the data map |
| `CLAUDE.md` | Working agreement for AI coding sessions |
| `LICENSE` | Apache License 2.0 |
| `NOTICE` | Copyright + third-party notice |
