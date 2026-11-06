[![ci](https://github.com/techautomator414/trailkeep/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/techautomator414/trailkeep/actions/workflows/ci.yml)

# Trailkeep

**An open-source compliance case tracker that takes aging seriously.**

Trailkeep replaces the kind of compliance log that records items, tracks how old they are, and then does
nothing about it. Each case has an owner, a type, an SLA clock, comments, attachments and exactly one
approval moment: closure. The system emails people before deadlines pass, sends an automated aging report,
and keeps an evidence-grade history.

> **Status: Phase 2 in progress, after the 0.1.0 walking skeleton.** Phase 1 of `IMPLEMENTATION_PLAN.md` is merged
> (build and scanning gates, two-vendor schema tooling, identity, OIDC login and shared sessions, audit, web shell,
> container and deploy base), with team and case-configuration administration (WP-09, WP-10) and, in Phase 2, case
> create, view, edit, reassign and restrict, comments, attachments with virus scanning, and a filtered, searchable
> cases list with saved views (WP-11 to WP-14, and WP-15 parts 1a, 1b, 2 and 3). About half of the planned work
> packages are done. **Cases cannot be closed yet** and there is no email or SLA alerting: those are Phases 3 and 4.
> Read [`HANDOFF.md`](HANDOFF.md) for exactly what exists, what does not, and what comes next.

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
| [`HANDOFF.md`](HANDOFF.md) | Where the build stands (refreshed after WP-11 to WP-15 part 2; written at 0.1.0): what is merged, what is not, decisions taken, open issues, and the next work package |
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
`alpha.analyst` can create a case at `/cases/new` and work it from `/cases`. ClamAV (with its TLS sidecar) is in the
compose stack; Mailpit joins it with the email work package (WP-17).

## Security posture

Static analysis, dependency, secret, container and authenticated dynamic scans are build gates from the
first commit, and the evidence (SBOM, scan reports, accessibility conformance report) is attached to every
release. See `IMPLEMENTATION_PLAN.md` Part G.

## License

Apache License 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
