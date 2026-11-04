<!--
  SPDX-License-Identifier: Apache-2.0
  Copyright 2026 Charlie Genova and contributors
-->
# Trailkeep handoff: state at 0.1.0

_Written 2026-11-04 for the team taking over. Read this first, then `README.md`, then `IMPLEMENTATION_PLAN.md`
Part B (standards) and Part C (work packages). Where this file and the plans disagree, the plans win; this file
only says where the build stands._

## 1. Where the project stands

| Measure | Done | Total | Share |
|---|---|---|---|
| Work packages (IMPLEMENTATION_PLAN Part C) | 12 (WP-01 to WP-08 are 10 rows with the 03a/b and 04a/b splits, plus WP-09, WP-10) | 33 | 36 % |
| Work packages weighted by size (S = 1, M = 2, L = 3) | 23 | 67 | 34 % |
| Phase 1 pull requests (Part D) | 12 | 12 | 100 % |
| Plan calendar (Part F) | about 4.9 weeks: Phase 1 (4) plus WP-09 and WP-10, 3 of Phase 2's 17 weighted points, about 0.9 of its 5 | 25 weeks | about 20 % (Phase 0 discovery, 2 weeks, is not counted on either side) |

In plain terms: the platform is built and gated, administration works, and **no case can be created yet**. The
first end-user feature is WP-11 (case create and view), which has not started. Stopping here was deliberate: WP-11
is the architectural center of the product (the `V4__case` migration and the principal-backed `CaseScope`), and a
half-finished WP-11 would be the worst state to hand over.

0.1.0 is the `v0.1.0` tag, cut from `main` as the last step of this handoff, after #25 (CI fix for #13), #33 (the
test port fix below) and #32 (this document) merged. Every workflow is green on that commit: `ci`, `main` (both Oracle images, image build and
scan), `codeql`, `scorecard`. "Green" has one caveat: `main.yml` skips SonarCloud and Dependency-Check with a notice
when their secrets are absent, and they were absent at handoff (#30).

### Phase 1 exit criteria (Part C)

| Criterion | State |
|---|---|
| WP-01 to WP-08 merged | Done |
| Every scanner green | Not quite: SonarCloud and Dependency-Check need the repository secrets `SONAR_TOKEN` and `NVD_API_KEY` (#30) |
| Login works against a real identity provider in DEV | Not done; only the Keycloak dev realm has been exercised (#30) |
| ACR draft for the shell | Not done; `accessibility/` does not exist yet (#30) |

## 2. What is on `main`

| WP | Subject | PR | Where to look |
|---|---|---|---|
| WP-01 | Build scaffold, static analysis gates, ADRs 0001 to 0010 | #2, #5, #1 | `pom.xml`, `config/`, `adr/` |
| WP-02 | CI: fork-safe `ci.yml`, CodeQL, Scorecard, Dependabot; `main.yml`, `nightly.yml`, `release.yml` | #4, #9, #25 | `.github/workflows/` |
| WP-03a | Flyway with `common` + vendor locations and placeholders; Testcontainers harness on PostgreSQL 16, Oracle xe 21 and free 23 | #8 | `src/main/resources/db/migration/`, `src/test/java/io/trailkeep/schema/` |
| WP-03b | `V1__identity` with `AppUser`, `Team`, `AppUserTeam`, `AppUserGlobalRole`; vendor `R__grants.sql`; grants matrix test | #14 | `io.trailkeep.user`, `db/migration/{oracle,postgresql}/R__grants.sql` |
| WP-04a | OIDC login, group-to-role mapping, System-Admin-vs-team-role conflict rule, disabled-user refusal, Keycloak dev realm | #16 | `io.trailkeep.security`, `deploy/local/keycloak/` |
| WP-04b | Spring Session JDBC (`V3_1__sessions`), no persisted authorities, 8 h absolute lifetime, RP-initiated and back-channel logout | #23 | `SessionConfig`, `SessionPermissionsFilter`, `JdbcOidcSessionRegistry`, ADR-0008 |
| WP-05 | Web shell: layout, exact CSP with nonce, vendored htmx 2.0.11 and Bootstrap 5.3.8 CSS, `ErrorAdvice`, Playwright + axe suite | #19 | `io.trailkeep.web`, `templates/web/`, `src/test/java/io/trailkeep/e2e/` |
| WP-06 | `V2__audit`: `AuditEvent`, `AuditRecorder` (same transaction), HMAC redaction, MDC request id | #15 | `io.trailkeep.audit` |
| WP-07 | Spring Modulith modules, `CaseScope` contract in `shared`, ArchUnit and Semgrep rules | #7 | `io.trailkeep.shared.access`, `src/test/java/io/trailkeep/architecture/`, `security/semgrep/` |
| WP-08 | JSON logging, Prometheus, probe groups, UBI 9 image, kustomize base, purge `CronJob` | #9, #10 | `Containerfile`, `deploy/` |
| WP-09 | Permission matrix (`permissions/matrix.csv`), per-request `UserPermissions`; team and team-role admin with audit | #18, #20 | `CasePermissionService`, `TeamAdminService`, `/admin/teams` |
| WP-10 | `V3__admin`: SLA policies, case types, outcomes; admin screens; `local` seed | #21, #22 | `CaseConfigService`, `/admin/sla-policies`, `/admin/case-types`, ADR-0011 |

Schema on `main`: `V1__identity`, `V2__audit`, `V3__admin`, `V3_1__sessions`, plus the repeatable `R__grants`
per vendor. Next migration is `V4__case` (WP-11).

### Not built yet (everything a user would call "the product")

Case create, view, edit, reassign, restrict (WP-11, 12); comments and search (WP-13); attachments and ClamAV
(WP-14); case list, filters, saved views, CSV (WP-15); history tab (WP-16); email outbox and dispatcher (WP-17);
reminders tick (WP-17b); request-update (WP-18); closure workflow (WP-19); dashboard (WP-20); SLA evaluator and
alerts (WP-21); reports (WP-22, 23); retention and purge (WP-24); System screen (WP-25); importer (WP-26); preferences and shortcuts
(WP-27); DAST, 508 conformance pass, performance and runbook (WP-28 to 30).

Consequences for local development today: `compose.yaml` runs PostgreSQL, Keycloak and an optional Oracle. Mailpit
arrives with WP-17 and ClamAV with WP-14; `README.md` and `CONTRIBUTING.md` were written against the target stack
and are corrected in 0.1.0.

## 3. How to run and verify

```sh
docker compose up -d                                   # PostgreSQL 16 and Keycloak 26 with the dev realm
./mvnw verify                                          # unit tests, PostgreSQL ITs, every local gate
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Sign in at `http://localhost:8080` with a dev-realm user (`deploy/local/keycloak/README.md`; password
`dev-password`). `system.admin` reaches `/admin/teams`; `compliance.admin` reaches `/admin/sla-policies` and
`/admin/case-types`. The `local` profile seeds two SLA policies and three case types on start-up (`app.seed` in
`application-local.yml`). Team memberships are not seeded yet (#29); grant them at `/admin/teams`.

Oracle parity, two separate options: `./mvnw verify -Poracle-it` starts its own Testcontainers for both public
images (xe 21 and free 23) and runs the schema and grants ITs on each; `docker compose --profile oracle up -d` starts
a long-lived Oracle Free 23 for manual work against the `oracle` profile. Testcontainers ITs skip without Docker
locally and fail in CI (`CI=true`).

Playwright: the `e2e` suite launches Chromium; locally it skips if no browser is installed, CI installs one.

## 4. CI and release

| Workflow | Trigger | What it proves |
|---|---|---|
| `ci.yml` | every PR and push to `main` | DCO, actionlint, gitleaks, Semgrep (registry rules plus `security/semgrep/`), `./mvnw verify` with Playwright |
| `codeql.yml` | every PR, push to `main`, weekly | CodeQL `security-extended` for Java |
| `scorecard.yml` | push to `main`, weekly | OpenSSF Scorecard |
| `main.yml` | push to `main`; called by nightly and release | SonarCloud, Dependency-Check, SBOM + Trivy, image build + Trivy (`--ignore-unfixed`) + Dockle + kube-linter, `oracle-it` (both images, one job) |
| `nightly.yml` | 04:41 UTC | `main.yml` again; k6 and ZAP are placeholders until WP-30 and WP-28 |
| `release.yml` | tag `v*` | `main.yml` again plus its own `./mvnw verify`, image to GHCR with keyless cosign signature, SBOM attestation, SLSA provenance, GitHub Release with the evidence pack (it does not re-run the `ci.yml` jobs) |

Secrets: `SONAR_TOKEN` and `NVD_API_KEY` are referenced but were not set at handoff (#30). The release workflow
needs no secrets beyond the job token for signing and publishing, but it forwards those two to `main.yml`, so a
release cut without them skips SonarCloud and Dependency-Check and its evidence pack has no Dependency-Check report.

Branch rules: status checks required, linear history, no force-push, zero approving reviews (single maintainer).
Every PR gets an independent review pass on a different model from its author, recorded on the PR (Part B).

## 5. Decisions taken during implementation

All are recorded where they were made; this is the index.

| Decision | Where | Why |
|---|---|---|
| Trivy image gate runs with `--ignore-unfixed` | #11, #12 | Owner chose to fail only on fixable HIGH/CRITICAL findings; unfixed base-image findings stay in the reports and evidence pack |
| Semgrep excludes `python.django.security.django-no-csrf-token.django-no-csrf-token` | #19, `ci.yml` | False positive on Thymeleaf forms whose CSRF field Spring Security injects |
| `config/spotbugs/exclude.xml` carries `SPRING_ENDPOINT` for `DashboardController`, `SessionController`, `TeamAdminController`, `CaseConfigController`, listed by class | #19, #20, #22 | The pattern reports no defect; each controller is reviewed and the approval recorded in the file; `spotbugs-annotations` is LGPL and off the license allow-list, so the file is the mechanism |
| Case configuration (SLA policies, case types, outcomes) lives in `casefile`, not `sla` | ADR-0011, #21 | Keeps the module graph acyclic; `sla` reads it through `CaseConfigService`. Supersedes BUILD_PLAN §7.4's `sla/ (SlaPolicy, …)` line |
| App user may DELETE from `CASE_TYPE_OUTCOME` | ADR-0011, both `R__grants.sql` | Configuration, not case data; closure outcomes are stored as `VARCHAR` snapshots, not foreign keys |
| `OIDC_SESSION` keyed by `SESSION_ID`; `SID` nullable and indexed; cleanup spares rows under one hour | ADR-0008 amendment, #23 | Spring removes registrations by client session id; a provider may omit `sid`; the registry row is written before Spring Session commits the session row |
| Spring Session pinned to 4.1.0 | #24, `pom.xml` | 4.1.1 and 4.0.5 declare "Broadcom Foundation License" in their POMs, which is not on `security/licenses-allowed.txt`. Owner decision: stay on 4.1.0; revisit before any upgrade |
| Audit action names are `<ENTITY>_<PAST_PARTICIPLE>` | #21 review | `TEAM_CREATED`, `ROLE_GRANTED`, `SLA_POLICY_UPDATED`, and so on. `ACTION` is an open set, so one convention matters for the history tab and export. Two names from WP-04a predate the convention and stay as they are: `LOGIN` and `ROLE_CONFLICT` (renaming them would break stored history) |
| `oracle-it` is one CI job with no image override | #13, #25 | The Maven profile's two executions already fix both images; the matrix doubled the runs |
| Test compilation uses `-Xlint:-path` | #8, `pom.xml` | `ojdbc17`'s manifest references a jar Maven Central does not ship; every other lint and `-Werror` stay on |
| PR 10 (#23) exceeded 400 net production lines | #23 review | Accepted so the `V3_1__sessions` migration shipped with the code that uses it |

## 6. Known gaps and follow-ups (all tracked)

| Issue | What |
|---|---|
| #30 | Phase 1 exit items: scanner secrets, real IdP login in DEV, shell ACR draft |
| #31 | `UserSessionService.endSessionsOf` has no caller; the user-disable action must call it |
| #26 | `JdbcOidcSessionRegistry` SQL is unexercised on Oracle; run its IT on the Oracle leg |
| #27 | `ErrorAdvice` does not map `ConstraintViolationException`; today controllers validate first by convention |
| #28 | Back-channel `logoutUri` is built from `server.port` at configuration time; wrong under `server.port=0` |
| #29 | Local seed: name the unknown SLA policy; seed teams Alpha and Beta for the dev realm |
| #24 | Spring Session license tracker (stay on 4.1.0) |

### Open owner items

Things only the repository owner can do (accounts, secrets, a real identity provider, a human tester). None is a
code change.

1. **OpenSSF Best Practices badge.** Register the project at bestpractices.dev, then add the badge to `README.md`.
   This closes Scorecard alert #8.
2. **Repository secrets `SONAR_TOKEN` and `NVD_API_KEY`**, for the `main.yml` SonarCloud and Dependency-Check steps
   (they are skipped with a notice without them; #30). Alternatively record on #30 that the two scanners are off
   for 0.1.0.
3. **One real sign-in against the organization's identity provider in DEV**, following
   [`deploy/OIDC_PROVIDER.md`](deploy/OIDC_PROVIDER.md). Record the outcome on #30, not the values.
4. **Manual accessibility testing** to finish the draft report [`accessibility/ACR-shell.md`](accessibility/ACR-shell.md):
   the script is in its section 6 (keyboard only, screen reader, 200 % and 400 % zoom, reflow, text spacing). Until it
   is done every "needs manual confirmation" row stays unconfirmed (#30).
5. **Spring Session stays pinned at 4.1.0** by owner decision on #24 (license metadata of 4.1.1 and 4.0.5). Watch
   the Spring Session security advisories; revisit before any upgrade.

Items 2 to 4 are the open Phase 1 exit items from #30; until they are done Phase 1 is not complete in the plan's own
terms.

Not defects, but worth knowing:

- Without JavaScript, the sign-out form's redirect to the identity provider is blocked by the CSP's
  `form-action 'self'`; the application session still ends. The htmx path uses `HX-Redirect`.
- The `SESSION` cookie is `Secure` in every profile, including `local`; browsers accept that on `http://localhost`.
- `SharedSessionsIT` starts a second application instance in-process; it is the slowest test in the suite.
- The five `DEFINED_PORT` ITs take their application port from `KeycloakDevRealm`, which probes 20000 to 29999
  (#33). Picking a kernel ephemeral port raced with Docker's published ports and failed one `main` run.

## 7. What comes next, in plan order

1. **WP-11** (L): `V4__case.sql` (`CASE_RECORD`, `CASE_SLA`, `CASE_SEARCH`), case numbering from
   `SEQ_CASE_NUMBER`, one-page create, case page, and the **principal-backed `CaseScope`** in `casefile`. Every
   case-rooted repository extends `Repository`, takes a scope on every method, and is injected only into
   `@CaseAccessPath` classes; the ArchUnit rules for this already exist and are exercised today by the audit module's
   `AuditEventRepository` and `DefaultAuditTrailService`. Unreadable cases answer 404. Read ADR-0005 and Part A #6 before starting.
2. **WP-13** then **WP-15** are on the critical path with WP-11; **WP-12**, **WP-14** and **WP-16** run alongside.
3. Phase 3 (WP-17, 18, 19), then Phase 4 (WP-21, 17b, 20, 22, 23), Phase 5 (WP-24 to 27), Phase 6 (WP-28 to 30).

Two conventions the receiving team inherits and should keep: one migration per PR shipped with its entity, and the
independent review on a different model recorded on the PR.

## 8. Working with the repository

- `CLAUDE.md` is the working agreement for AI coding sessions and is also a fair summary of the human rules:
  one work package per branch, `./mvnw verify` green, draft PR from the template, DCO sign-off, nothing
  organization-specific, never weaken a gate, record plan deviations in the PR and decisions in `adr/`.
- The plans are not rewritten. A superseded line is recorded by an ADR (see ADR-0011 and the ADR-0008 amendment),
  and `adr/README.md` is the index.
- `CHANGELOG.md` follows Keep a Changelog; every PR adds its entry under Unreleased.
- The release process is the `v*` tag; 0.1.0 is cut this way, as the last step of the handoff, to exercise it and
  produce the first evidence pack.
