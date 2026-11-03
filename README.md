# Trailkeep

**An open-source compliance case tracker that takes aging seriously.**

Trailkeep replaces the kind of compliance log that records items, tracks how old they are, and then does
nothing about it. Each case has an owner, a type, an SLA clock, comments, attachments and exactly one
approval moment: closure. The system emails people before deadlines pass, sends an automated aging report,
and keeps an evidence-grade history.

> **Status: planning complete, implementation starting.** There is no runnable code yet. The plans below
> are the contract for what gets built. Follow the pull requests to watch it happen.

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

## Quick start (once the first milestone lands)

```
docker compose up -d                 # PostgreSQL, Keycloak dev realm, Mailpit, ClamAV
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Open the app, sign in with a dev-realm user, and read Mailpit for every email the app would have sent.

## Security posture

Static analysis, dependency, secret, container and authenticated dynamic scans are build gates from the
first commit, and the evidence (SBOM, scan reports, accessibility conformance report) is attached to every
release. See `IMPLEMENTATION_PLAN.md` Part G.

## License

Apache License 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
