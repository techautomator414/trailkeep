[![ci](https://github.com/techautomator414/trailkeep/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/techautomator414/trailkeep/actions/workflows/ci.yml)

# Trailkeep

**An open-source compliance case tracker that takes aging seriously.**

Trailkeep replaces the kind of compliance log that records items, tracks how old they are, and then does
nothing about it. Each case has an owner, a type, an SLA clock, comments, attachments and exactly one
approval moment: closure. The system emails people before deadlines pass, sends an automated aging report,
and keeps an evidence-grade history.

> **Status: 0.3.0. Every work package of Phases 1 to 5 is merged, and WP-28 of Phase 6; WP-29 and WP-30 remain
> before 1.0.0.** 0.1.0 was the walking skeleton: Phase 1 of `IMPLEMENTATION_PLAN.md` (build and scanning gates, two-vendor
> schema tooling, identity, OIDC login and shared sessions, audit, web shell, container and deploy base) with team and
> case-configuration administration (WP-09, WP-10). 0.2.0 added everything from WP-11 to WP-28:
> - **Phase 2 (WP-11 to WP-16):** case create, view, edit, reassign and restrict, comments, attachments with virus
>   scanning, a filtered, searchable cases list with saved views and a redacted CSV export (p95 132 ms at 50k cases),
>   and the case History page with audited reads of restricted cases.
> - **Phase 3 (WP-17 to WP-19):** the email outbox with its dispatcher, in-app alerts, Request update, and closure: a
>   case is closed through one approval gate with separation of duties, can be sent back with "Requires Changes", and
>   can be reopened with a reason, which starts its next SLA cycle.
> - **Phase 4 (WP-21, WP-17b, WP-20, WP-22, WP-23):** SLA states move to At risk and Overdue on their own, with one
>   alert per state and cycle; reminders go out once per period; the dashboard has KPI tiles and paged lists; the aging
>   and SLA-compliance reports come as HTML and CSV; and the aging report is delivered weekly to each team.
> - **Phase 5 (WP-24 to WP-27):** retention with archive, legal hold and a separate purge job; the System screen with
>   job, mail and scanner status and Prometheus alerts; a generic importer for legacy records from SQL Server, Oracle,
>   PostgreSQL or file exports, with a signed-off dry run and a reconciliation report; and preferences with single-key
>   shortcuts that can be turned off.
> - **WP-28 (Phase 6):** authenticated ZAP full and API scans, nightly and gating every release; the security E2E
>   suite; the Nu HTML validator; and the security test map.
>
> 0.3.0 hardens what 0.2.0 built, with no new work package:
> - **Security:** every configured-but-untested control of the security test map is now tested; a control character in
>   any request text is a 400; search and upload are rate-limited per person; the importer requires verified TLS from
>   its JDBC sources (ADR-0014 accepted); the SMTP relay's identity is checked; the DAST scan attacks form bodies last,
>   which removed two suppressions and left one narrower entry, expiring 2027-02-20.
> - **Documents:** the STRIDE threat model ([`security/THREAT_MODEL.md`](security/THREAT_MODEL.md)) and the data map
>   ([`security/DATA_MAP.md`](security/DATA_MAP.md)).
> - **Operations:** an audit trail screen for Compliance Admins with an audited CSV export; the importer's staging TTL
>   as a daily `CronJob`; the purge `CronJob`'s own egress policy and a purge alert; the published image verified
>   before its GitHub Release.
>
> Still to come for 1.0.0: the Section 508 conformance pass with a certified human tester (WP-29), and the evidence
> pack, 500k-case performance run, restore drill, runbook and 1.0.0 itself (WP-30). SonarCloud and Dependency-Check
> are intentionally off for 0.3.0: `main.yml` skips them with a notice until their repository secrets are set (#30).
> Read [`HANDOFF.md`](HANDOFF.md) for exactly what exists, what does not, the open issues, and what comes next.

## What it does

- **SLA-driven alerts** — At Risk and Overdue states computed from a per-type policy; owners and managers
  are emailed automatically; a weekly aging report goes out on its own.
- **One closure gate** — request closure, a manager approves or rejects with a reason, all audited.
- **"Request an update"** — email a colleague from inside a case and track whether they answered.
- **Restricted cases** — a sensitivity flag that hides a case from everyone except its owner, its team's
  managers and compliance admins, including in search, counts, exports and reports.
- **Evidence-grade history** — append-only comments and audit trail, scanned attachments, retention with
  legal hold.
- **Search, filters and saved views** that actually work.
- **Section 508 / WCAG 2.1 AA** conformance as a release gate, with a published conformance report.

## Technology

Java 21 · Spring Boot 4 · JPA/Hibernate 7 · Flyway · **Oracle** in production with **PostgreSQL** parity
enforced by tests · Thymeleaf + htmx + Bootstrap CSS · OIDC single sign-on · ClamAV · OpenShift-ready
container. No JavaScript build toolchain, no JSON API in v1, no Lombok.

## Documents

| Document | What it is |
|---|---|
| [`BUILD_PLAN.md`](BUILD_PLAN.md) | What we are building: scope, roles, flows, domain model, SLA engine, architecture, portability rules, security, accessibility, deployment |
| [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) | How we build it: the audit decisions register (three independent design reviews, 62 findings resolved), engineering standards, work packages, PR order, definition of done, CI placement |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | How to contribute, build, test and scan |
| [`SECURITY.md`](SECURITY.md) | How to report a vulnerability |
| [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) | Contributor Covenant 2.1 |
| [`CLAUDE.md`](CLAUDE.md) | Working agreement for AI coding sessions (much of this code is written with AI assistance; every PR passes the same gates regardless of author) |
| [`HANDOFF.md`](HANDOFF.md) | Where the build stands at 0.3.0 (written at 0.1.0, refreshed after each phase and release): what is merged, what is not, decisions taken, open issues, and the next work package |
| [`CHANGELOG.md`](CHANGELOG.md) | What each release and each merged pull request changed |
| [`adr/`](adr/README.md) | Architecture decision records, including the ones that superseded a line of the plans |

## Quick start

```
docker compose up -d                 # PostgreSQL 16 and Keycloak 26 with the dev realm
./mvnw verify                        # unit tests, PostgreSQL integration tests, every local gate
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Open `http://localhost:8080` and sign in with a dev-realm user (`deploy/local/keycloak/README.md`): `system.admin`
manages teams at `/admin/teams`, `compliance.admin` manages SLA policies and case types under `/admin`, and
`alpha.analyst` can create a case at `/cases/new` and work it from `/cases`. ClamAV (with its TLS sidecar) and Mailpit
are in the compose stack: every mail the `local` profile sends (a comment by someone other than the case's owner, for
example) is caught by Mailpit and readable at `http://localhost:8025`; nothing leaves the machine.

## Security posture

Static analysis, dependency, secret, container and authenticated dynamic scans are build gates from the
first commit, and the evidence (SBOM, scan reports, accessibility conformance report) is attached to every
release. See `IMPLEMENTATION_PLAN.md` Part G. The security test map,
[`security/TEST_MAP.md`](security/TEST_MAP.md), names the check behind each control and lists the gaps.

## License

Apache License 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
