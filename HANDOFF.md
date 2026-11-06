<!--
  SPDX-License-Identifier: Apache-2.0
  Copyright 2026 Charlie Genova and contributors
-->
# Trailkeep handoff: state at Phase 2 progress

_Written 2026-11-04 at 0.1.0 for the team taking over; refreshed 2026-11-06, after WP-11 to WP-14 and parts 1a, 1b,
2 and 3 of WP-15 merged. Read this first, then `README.md`, then `IMPLEMENTATION_PLAN.md` Part B (standards) and Part C
(work packages). Where this file and the plans disagree, the plans win; this file only says where the build stands._

## 1. Where the project stands

| Measure | Done | Total | Share |
|---|---|---|---|
| Work packages (IMPLEMENTATION_PLAN Part C) | 16 (WP-01 to WP-08 are 10 rows with the 03a/b and 04a/b splits, plus WP-09 to WP-14) | 33 | 48 % |
| Work packages weighted by size (S = 1, M = 2, L = 3) | 33 | 67 | 49 % |
| Phase 1 pull requests (Part D) | 12 | 12 | 100 % |
| Plan calendar (Part F) | about 7.8 weeks: Phase 1 (4) plus 13 of Phase 2's 17 weighted points, about 3.8 of its 5 | 25 weeks | about 31 % (Phase 0 discovery, 2 weeks, is not counted on either side) |

WP-15 (cases list, L) is in progress and not counted: parts 1a, 1b, 2 and 3 are merged (list, sort and paging; filters
and search; the filter drawer, chips and multi-status; saved views), and only the CSV export remains. WP-16 (history
tab) has not started.

In plain terms: the platform is built and gated, administration works, and **cases can now be worked, though not yet
closed**: a person creates one on a single page, views it, edits it under optimistic locking, reassigns or restricts
it, comments and pins comments, attaches files (validated, virus-scanned, downloadable, with a Compliance Admin
release for quarantined ones), finds it in a filtered, searchable, sortable list, and saves a view of it. All of it
goes through the principal-backed `CaseScope`, so an unreadable case answers 404. There is no closure workflow, email or SLA
evaluator yet (Phases 3 and 4). Disabling a user from `/admin/users` now ends that user's sessions (#63).

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
| ACR draft for the shell | Drafted: `accessibility/ACR-shell.md` (#43) covers the shell, admin and case screens; manual testing is not done, so its "needs manual confirmation" rows stay open (#30) |

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
| WP-11 | `V4__case`: `CASE_RECORD`, `CASE_SLA`, `CASE_SEARCH`; principal-backed `CaseScope` from the permission matrix; case numbering; case page; one-page create form (PHI warning) and golden path 1 | #35, #39, #41 | `io.trailkeep.casefile` (`PrincipalCaseScope`, `DefaultCaseScopeService`, `DefaultCaseService`), `CaseController`, `templates/casefile/` |
| WP-12 | Edit with optimistic locking (409 and Reload); reassign, mark restricted and mark standard in native dialogs | #49, #50 | `CaseEditController`, `CaseAssignmentController` |
| WP-13 | Insert-only comments, pins, `SafeHtml` and `tk:safe`, atomic `CASE_SEARCH` append | #51, #52, #53 | `shared.html` (`MarkdownRenderer`, `SafeHtml`), `CaseCommentService`, `CaseSearchWriter`, `CaseSearchJdbcDao` |
| WP-14 | Attachments: chunked content DAO, upload validation, synchronous clamd scan over TLS, deployment-chosen scanning mode, upload section, download and quarantine Release | #54, #55, #57, #58, #59 | `io.trailkeep.document`, `CaseAttachmentController`, ADR-0012, ADR-0013 |
| WP-15 (parts 1a, 1b, 2, 3) | Cases list with scoped projections, sort and paging; filters and `LIKE` search through `SearchGateway`; filter drawer, chips, multi-status; saved views under Cases in the navigation | #62, #65, #71, #78 | `CaseListController`, `CaseListService`, `LikeSearchGateway`, `SavedViewService`, `SavedViewController`, ADR-0006 |

Schema on `main`: `V1__identity`, `V2__audit`, `V3__admin`, `V3_1__sessions`, `V4__case`, `V4_1__comment`,
`V4_2__comment_pin`, `V4_3__attachment`, `V4_4__attachment_not_scanned`, `V4_5__search_text_lowercase` (#70),
`V4_6__sla_check_ceilings` (#77), `V4_7__saved_view` (#78), plus the repeatable `R__grants` per vendor.
`V5__closure` (WP-19) follows. A later part of a work package that needs a migration takes the next `V4_n`; the number
is assigned in the PR that ships it.

### Not built yet

CSV export (WP-15 part 4, the remainder); history tab (WP-16); email outbox and dispatcher (WP-17);
reminders tick (WP-17b); request-update (WP-18); closure workflow (WP-19); dashboard (WP-20); SLA evaluator and
alerts (WP-21); reports (WP-22, 23); retention and purge (WP-24); System screen (WP-25); importer (WP-26, designed in
`docs/design/importer.md` and ADR-0014, not started); preferences and shortcuts (WP-27); DAST, 508 conformance pass,
performance and runbook (WP-28 to 30).

Consequences for local development today: `compose.yaml` runs PostgreSQL, Keycloak, ClamAV with its TLS sidecar (the
`local` profile trusts the dev-only CA that `clamav-tls-init` generates, so run `docker compose up -d` before
`spring-boot:run`) and an optional Oracle. Mailpit arrives with WP-17. `README.md` and `CONTRIBUTING.md` are updated to
match in the same change as this refresh.

## 3. How to run and verify

```sh
docker compose up -d                                   # PostgreSQL 16 and Keycloak 26 with the dev realm
./mvnw verify                                          # unit tests, PostgreSQL ITs, every local gate
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Sign in at `http://localhost:8080` with a dev-realm user (`deploy/local/keycloak/README.md`; password
`dev-password`). `system.admin` reaches `/admin/teams`; `compliance.admin` reaches `/admin/sla-policies` and
`/admin/case-types`. The `local` profile seeds two SLA policies, three case types, the teams Alpha and Beta and the
dev-realm users' team roles on start-up (`app.seed` in `application-local.yml`; #29), so `alpha.analyst` can create
a case at `/cases/new` and work it from `/cases`. Further memberships are granted at `/admin/teams`.

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
| `config/spotbugs/exclude.xml` carries `SPRING_ENDPOINT` for every controller (`DashboardController`, `SessionController`, `TeamAdminController`, `UserAdminController`, `CaseConfigController`, `CaseController`, `CaseEditController`, `CaseAssignmentController`, `CaseAttachmentController`, `CaseListController`, `SavedViewController`), listed by class | #19, #20, #22, #49, #50, #59, #62, #63, #78 | The pattern reports no defect; each controller is reviewed and the approval recorded in the file; `spotbugs-annotations` is LGPL and off the license allow-list, so the file is the mechanism |
| Case configuration (SLA policies, case types, outcomes) lives in `casefile`, not `sla` | ADR-0011, #21 | Keeps the module graph acyclic; `sla` reads it through `CaseConfigService`. Supersedes BUILD_PLAN §7.4's `sla/ (SlaPolicy, …)` line |
| App user may DELETE from `CASE_TYPE_OUTCOME` | ADR-0011, both `R__grants.sql` | Configuration, not case data; closure outcomes are stored as `VARCHAR` snapshots, not foreign keys |
| `OIDC_SESSION` keyed by `SESSION_ID`; `SID` nullable and indexed; cleanup spares rows under one hour | ADR-0008 amendment, #23 | Spring removes registrations by client session id; a provider may omit `sid`; the registry row is written before Spring Session commits the session row |
| Spring Session pinned to 4.1.0 | #24, `pom.xml` | 4.1.1 and 4.0.5 declare "Broadcom Foundation License" in their POMs, which is not on `security/licenses-allowed.txt`. Owner decision: stay on 4.1.0; revisit before any upgrade |
| Audit action names are `<ENTITY>_<PAST_PARTICIPLE>` | #21 review | `TEAM_CREATED`, `ROLE_GRANTED`, `SLA_POLICY_UPDATED`, and so on. `ACTION` is an open set, so one convention matters for the history tab and export. Two names from WP-04a predate the convention and stay as they are: `LOGIN` and `ROLE_CONFLICT` (renaming them would break stored history) |
| `oracle-it` is one CI job with no image override | #13, #25 | The Maven profile's two executions already fix both images; the matrix doubled the runs |
| Test compilation uses `-Xlint:-path` | #8, `pom.xml` | `ojdbc17`'s manifest references a jar Maven Central does not ship; every other lint and `-Werror` stay on |
| Search text is stored lower-cased and searched with `LIKE` alone; ADR-0006 amendment r4 | #69, #70, ADR-0006 | Hibernate refuses `lower()` on the `CLOB`-mapped `CASE_SEARCH.SEARCH_TEXT` on Oracle, which only the `main` `oracle-it` leg caught. `CaseSearch` and `CaseSearchJdbcDao` lower-case with `Locale.ROOT` on every write, `LikeSearchGateway` applies no function to the column, and `V4_5` lower-cases older rows. The search is a predicate inside the scoped statement (r4), not a list of ids |
| Semgrep install carries a lockfile with override pins | #42, #64 | `.github/requirements/semgrep.txt` is hash-pinned and watched by Dependabot; `semgrep-overrides.in` lifts the `mcp` and `opentelemetry-api` pins every Semgrep release up to 1.184.0 carries, to clear the Dependabot alerts (the job installs with `--no-deps` and never runs `semgrep mcp`) |
| Scorecard alert 10 (Pinned-Dependencies) dismissed as won't fix | #64 | `slsa-framework/slsa-github-generator` reusable workflows must be referenced by tag, because the generator verifies its own ref to establish builder identity; pinning by SHA breaks provenance. Release-manager decision recorded on #64 |
| A closed dialog returns focus to its opener only if focus was lost or is still inside the dialog | #75, `app.js` | The `close` event arrives after the browser has restored focus; a quick Escape then Tab could be pulled back to the opener. The e2e walks wait for the focus they assert and for the dialog's close event (#79) |
| `AttachmentConfig` refuses to start unless `spring.servlet.multipart.max-request-size` is at least `app.attachments.max-bytes` plus 1 MiB | #76 | `max-file-size` follows `max-bytes` by placeholder but `max-request-size` does not; a loud startup failure is the smaller fix than deriving one from the other. Raise both together |
| The admin ceilings (3650 target days, 365 reminder days, 100 retention years) are named CHECK constraints | #77, `V4_6` | `V3__admin` carried only the lower bounds and is never edited (Flyway checksum); the upper bounds equal the command annotations |
| Saved views store the canonical query string, not `filter_json`; they are private to the person, and save and delete are audited by name only | #78, `V4_7` | `SAVED_VIEW.QUERY` holds `CaseListQuery.toQueryString()` (BUILD_PLAN §5's `filter_json` is replaced; the binding to the typed filter record is kept because the posted query is parsed into `CaseListQuery` before it is stored). Another person's or an unknown view is 404, never 403, so a view's existence stays private. Applying a view is a plain GET that is re-read through the parser and `CaseScope`, so a view never widens what the person reads. `SAVED_VIEW_CREATED` and `SAVED_VIEW_DELETED` carry the name only |
| Attachment scanning is `clamd` over TLS by default, or `external` | ADR-0012, ADR-0013, #55, #57 | The scan is synchronous and nothing is stored on a scanner error; `external` stores files as `NOT_SCANNED`, never `CLEAN`. Deployment configuration, so no application account can turn scanning off |
| PR 10 (#23) exceeded 400 net production lines | #23 review | Accepted so the `V3_1__sessions` migration shipped with the code that uses it |

## 6. Known gaps and follow-ups (all tracked)

| Issue | What |
|---|---|
| #30 | Phase 1 exit items: scanner secrets, real IdP login in DEV, shell ACR manual testing |
| #24 | Spring Session license tracker (stay on 4.1.0) |
| #67 | Bug-hunt triage of 2026-11-05 (report under `docs/bug-hunts/`, eleven suspicions). Rejected with evidence: 1, 2, 3, 5, 8, 9 (inactive-team memberships count toward the System Admin conflict, by design), 10, and the session-boundary part of 11. Fixed: 4 (local seed names compared stripped, #73), 6 (multipart headroom, #76), 7 (CHECK ceilings, #77), 11 (CRLF counted once in the description limit and surrogate pairs kept whole in the audit name, #74). Carried forward: 9's in-memory role-conflict list is replaced when WP-25 reads the durable `ROLE_CONFLICT` audit rows (latest per user, cleared by a later conflict-free `LOGIN`), and then `UserSignInService.roleConflicts()` goes away; item 1 is a note for WP-24 (a purge path that rewrites search text must handle a null description); the attachment size display shows 1,048,525 to 1,048,575 bytes as "1024.0 KB" (deferred, cosmetic) |

Closed since 0.1.0: #26 (the OIDC session registry IT runs on the Oracle leg, #38), #27 (`ConstraintViolationException`
maps to 422, #36), #28 (back-channel `logoutUri` follows the bound port, #37), #29 (local seed names the unknown
policy and seeds teams Alpha and Beta, #40), #31 (disabling a user ends their sessions, #63), #66 (the htmx 403
redirect no longer doubles the servlet context path, #72) and #69 (the Oracle leg on `lower()` of the `CLOB`, #70).

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
6. **bnd annotations licence metadata.** `pom.xml` pins `biz.aQute.bnd:biz.aQute.bnd.annotation` at 4.3.1, `provided`
   scope: compile-time only, not shipped. `tika-core`'s package-info classes carry bnd's `@Version`, and without the
   annotation type javac warns, which `-Werror` turns into an error (#55). 4.3.1 declares Apache-2.0 and EPL-2.0 as two
   separate licences, both on `security/licenses-allowed.txt`, so the third-party licence check passes. Later releases
   declare one string, `(Apache-2.0 OR EPL-2.0)`, which the check does not recognise. Decide: keep the pin as is; or
   before upgrading, add a `licenseMerge` for that string or list it in the allow-list (a gate change, so recorded in
   its own PR); or drop the dependency, which needs another way to compile `tika-core`'s annotated classes without
   the warning. Nothing is required while the pin stays.
7. **ClamAV signature updates in a cluster.** `deploy/base/clamav.yaml` runs clamd from the official image with no
   `freshclam` and no egress (ADR-0012), so signatures are those baked into the image tag (the file's header says the
   same). Decide how they stay current: roll the image tag on a fixed cadence (rebuild or re-pull with current
   databases); add a `freshclam` sidecar, with a writable volume and egress to the signature mirror in an overlay,
   which also relaxes the pod's no-egress `NetworkPolicy`; or point `freshclam` at a private mirror. Setting
   `app.attachments.scanning: external` (ADR-0013, `deploy/overlays/external-scanning`) removes the ClamAV pod and
   with it this decision, at the cost of files being stored `NOT_SCANNED`.

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

1. **WP-15, the remainder**: the CSV export with redaction (part 4; row click and Enter on a focused row and the p95
   check at 50k cases also wait there). Then **WP-16**, the history tab (case-scoped audit timeline, `VIEW_RESTRICTED` once per page
   view or download), which finishes Phase 2.
2. Phase 3: **WP-17** (outbox and dispatcher; it brings Mailpit to `compose.yaml`), **WP-18** (request update) and
   **WP-19** (closure, `V5__closure`). WP-18 filters recipients with `CaseScope.canRead`; WP-19 is where the approver
   rules and separation of duties from Part A #18 get code.
3. Phase 4 (WP-21, 17b, 20, 22, 23), Phase 5 (WP-24 to 27), Phase 6 (WP-28 to 30). WP-25 owns the role-conflict list
   described in section 6; WP-24 owns purge.

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
