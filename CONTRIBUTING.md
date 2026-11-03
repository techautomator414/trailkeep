# Contributing to Trailkeep

Thank you for considering a contribution. This project is built to be deployed in regulated environments,
so the bar for code is deliberately high and deliberately mechanical: almost everything below is enforced by
the build, not by opinion.

## Before you start

- Read `IMPLEMENTATION_PLAN.md` **Part B** (engineering standards) and the work package you intend to touch
  in **Part C**. The acceptance criteria there are what your pull request must prove.
- Open or pick an issue first for anything larger than a bug fix, so scope is agreed before code.
- By contributing you agree to the [Developer Certificate of Origin](https://developercertificate.org/).
  Sign every commit: `git commit -s`. There is no CLA.

## Development environment

Prerequisites: JDK 21, Docker (for Testcontainers and the local stack). Maven comes with the wrapper.

```
docker compose up -d                       # PostgreSQL 16, Keycloak (dev realm), Mailpit, ClamAV
./mvnw verify                              # unit + PostgreSQL integration tests + all local gates
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Oracle parity is verified in CI on `main` against two public Oracle images. To run it locally:

```
docker compose --profile oracle up -d
./mvnw verify -Poracle-it
```

## Pull requests

- Branch from `main`; one work package or one bounded fix per PR; ≤ 400 net lines of production code
  unless the plan marks the PR size-exempt.
- Fill in `.github/PULL_REQUEST_TEMPLATE.md`. Every box is a real check.
- One versioned migration per PR, shipped together with its entity change and its test. Migrations must
  pass the portability lint (identifiers ≤ 30 characters, no vendor types outside placeholders, free-text
  `VARCHAR` ≤ 1000).
- Every new mutating endpoint has tests for the allowed role, the nearest disallowed role (403) and an
  unreadable case (404). Every state change writes an audit event, with a test.
- Every new or changed screen passes axe with zero violations, and the PR description records a keyboard-only
  walk-through.
- New dependencies need one line in the PR saying why, and a license on the allow-list
  (`security/licenses-allowed.txt`).
- Conventional commit subjects: `build:`, `ci:`, `db:`, `security:`, `web:`, `audit:`, `ops:`, `feat:`,
  `fix:`, `test:`, `docs:`.
- Squash-merge. CI must be green. As this is currently a single-maintainer project, review is performed by
  an independent review pass rather than a second human approver; branch rules require status checks,
  linear history and no force-pushes.

## What the build will refuse

Spring Security defaults disabled, `th:utext`, `@Lob`, string-built SQL, `hx-on*` attributes, missing SPDX
headers, unknown or banned dependency licenses, JaCoCo below threshold, Checkstyle/Error Prone/NullAway
warnings, SpotBugs/FindSecBugs findings, Semgrep findings, secrets. If a gate is wrong, open an issue; do
not weaken it in the PR.

## Accessibility

Section 508 / WCAG 2.1 AA conformance is a release gate. The rules for every screen are in `BUILD_PLAN.md`
§10.1. If you are unsure whether a component is accessible, it is not yet; ask in the issue.

## AI-assisted contributions

Much of this codebase is authored with AI coding assistants working under `CLAUDE.md` and the standards
above. That is disclosed here deliberately. Every pull request, whoever or whatever wrote it, passes the same
gates and the same review. If you use an assistant, you remain responsible for the contribution and for the
DCO sign-off.

## Reporting problems

Bugs and feature requests: GitHub Issues. Security vulnerabilities: see `SECURITY.md` — please do not open
a public issue for those.
