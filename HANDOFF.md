<!--
  SPDX-License-Identifier: Apache-2.0
  Copyright 2026 Charlie Genova and contributors
-->
# Trailkeep handoff: state at Phase 3 progress

_Written 2026-11-04 at 0.1.0 for the team taking over; refreshed 2026-11-06, after WP-11 to WP-18 merged (Phase 2
closed, Phase 3 in progress). Read this first, then `README.md`, then `IMPLEMENTATION_PLAN.md` Part B (standards) and Part C
(work packages). Where this file and the plans disagree, the plans win; this file only says where the build stands._

## 1. Where the project stands

| Measure | Done | Total | Share |
|---|---|---|---|
| Work packages (IMPLEMENTATION_PLAN Part C) | 20 (WP-01 to WP-08 are 10 rows with the 03a/b and 04a/b splits, plus WP-09 to WP-18) | 33 | 61 % |
| Work packages weighted by size (S = 1, M = 2, L = 3) | 41 | 67 | 61 % |
| Phase 1 pull requests (Part D) | 12 | 12 | 100 % |
| Plan calendar (Part F) | about 11 weeks: Phase 1 (4), all 17 of Phase 2's weighted points (5) and WP-17 and WP-18 of Phase 3 | 25 weeks | about 44 % (Phase 0 discovery, 2 weeks, is not counted on either side) |

**Phase 3 is in progress.** WP-17 (email outbox, dispatcher and in-app alerts, M) and WP-18 (request update, M) are done;
the reminders tick, WP-17b, is Phase 4 and stays open. WP-19 (closure) is in flight.

**Phase 2 is complete.** WP-15 (cases list, L) is done: parts 1a, 1b, 2, 3, 4 and 5 are merged (list, sort and paging;
filters and search; the filter drawer, chips and multi-status; saved views; the CSV export with redaction, row
selection and row click; the 50k-case performance run). Its acceptance line, p95 under 500 ms at 50k cases on
PostgreSQL, is measured: 132 ms for the list and 109 ms for the two-word search (the slowest single-word search measured, 120 ms; #91,
`docs/perf/cases-list-50k.md`), and the nightly `perf` job fails if either target is missed. WP-16 (history tab, S) is done.

In plain terms: the platform is built and gated, administration works, and **cases can be worked, emailed and asked
for updates, though not yet closed**: a person creates one on a single page, views it, edits it under optimistic locking, reassigns or restricts
it, comments and pins comments, attaches files (validated, virus-scanned, downloadable, with a Compliance Admin
release for quarantined ones), finds it in a filtered, searchable, sortable list, saves a view of it, exports the
list to CSV with subject and description redacted unless the person may see them, and reads a case's History page.
A comment by anyone but the owner emails the case's owner and raises an in-app alert for them, read on the Alerts page
behind a badge in the navigation; "Request update" asks a colleague for an answer by mail and alert, and a comment from that person (or
Mark answered) resolves the request, which the dashboard's "Needs attention" shows until then. All of it goes through
the principal-backed `CaseScope`, so an unreadable case answers 404, and reading a restricted case (its page, a
download, its history) is itself audited as `VIEW_RESTRICTED`. There is no closure workflow or SLA evaluator yet
(WP-19 is in flight; Phase 4). Disabling a user from `/admin/users` ends that user's sessions (#63).

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
| WP-15 parts 1a, 1b, 2, 3 | Cases list with scoped projections, sort and paging; filters and `LIKE` search through `SearchGateway`; filter drawer, chips, multi-status; saved views under Cases in the navigation | #62, #65, #71, #78 | `CaseListController`, `CaseListService`, `LikeSearchGateway`, `SavedViewService`, `SavedViewController`, ADR-0006 |
| WP-15 part 4 | CSV export of the cases list (`GET /cases/export.csv`): subject and description redacted per row unless the person manages that case's team or is a Compliance Admin and asked for details; refused (422) above `app.export.max-rows` (default 10000); hand-rolled CSV escaping with formula-injection guard, no library; one `CASES_EXPORTED` audit event per export; selected-row export (repeatable `ids`, at most 100, read through the scope); rows open the case on click (the case-number link stays the keyboard path) | #84 | `CaseExportController`, `CaseExportService`, `CsvFormat`, `ExportProperties`, `CaseRecordQueries.export`, `CaseExportIT`, `OracleCaseExportIT` |
| WP-15 part 5 | The 50k-case performance run: a deterministic synthetic seed (never applied by Flyway), `PerfCasesListIT` (tagged `perf`; list, sorts, deep page, search, export and the saved-view query, in process, failing at a p95 of 500 ms for the list or 1 s for the search), the nightly `perf` job that runs it through the `perf-it` profile and uploads the table, a k6 smoke run by hand against a running stack, and the measurements with index recommendations that are not applied. p95 at 50k cases on PostgreSQL 16: list 132 ms, search 109 ms | #91 | `perf/seed-cases-postgresql.sql`, `PerfCasesListIT`, `perf/k6/cases-smoke.js`, `docs/perf/cases-list-50k.md`, `nightly.yml` |
| WP-16 | Case history. #83: `VIEW_RESTRICTED` is written when a restricted case is viewed, an attachment is downloaded or the history is read (closes #82). #86: `AuditTrailService.pageForCase` (paged, filterable, with the total) and the principal-scope join, so a principal's scope reaches the events of a case it may read. #87: the History page, `GET /cases/{number}/history`, with an allow-listed kind filter and structural redaction | #83, #86, #87 | `RestrictedReadAudit`, `AuditTrailService`, `AuditEventQueriesImpl`, `CaseHistoryController`, `CaseHistoryService`, `HistoryKind`, `templates/casefile/history.html`, `RestrictedViewAuditIT`, `CaseHistoryAccessibilityIT` |
| WP-17 | Email outbox, dispatcher and in-app alerts. #94: `V4_8` `NOTIFICATION_OUTBOX`, enqueue in the caller's transaction and the two-step `SKIP LOCKED` claim (select the ids, then update them under a lease) on both vendors. #95: the dispatcher over SMTP (every minute on every web replica, batches of 50), the `comment-added` mail (to the owner when anyone else comments), outbox metrics, Mailpit in `compose.yaml`. #96: `V4_9` `USER_ALERT`, the Alerts page and an unread badge in the navigation | #94, #95, #96 | `io.trailkeep.notification` (`NotificationService`, `OutboxJdbcDao`, `OutboxDispatcher`, `OutboxMetrics`, `MailTemplates`, `MailProperties`, `UserAlertService`), `AlertController`, `templates/notification/`, `templates/web/alerts.html`, `OutboxClaimIT`, `OracleOutboxClaimIT`, `MailpitDispatchIT`, `AlertsAccessibilityIT` |
| WP-18 | Request update. #97: `V4_10` `UPDATE_REQUEST`, the `ANSWER_UPDATE` matrix action, auto-resolve through the `CommentPosted` Modulith event, the `update-requested` mail and alert (never the message). #98: the Request update dialog with the WAI-ARIA Authoring Practices combobox for the recipient (in `app.js`), Mark answered, and the dashboard's "Needs attention" | #97, #98 | `io.trailkeep.updaterequest` (`UpdateRequestService`, `DefaultUpdateRequestService`, `UpdateRequestCommentListener`, `UpdateRequestQueriesImpl`), `CommentPosted`, `UpdateRequestController`, `templates/casefile/request-update.html`, `templates/casefile/fragments/update-request.html`, `DashboardController`, `UpdateRequestIT`, `OracleUpdateRequestIT`, `UpdateRequestAccessibilityIT` |

Schema on `main`: `V1__identity`, `V2__audit`, `V3__admin`, `V3_1__sessions`, `V4__case`, `V4_1__comment`,
`V4_2__comment_pin`, `V4_3__attachment`, `V4_4__attachment_not_scanned`, `V4_5__search_text_lowercase` (#70),
`V4_6__sla_check_ceilings` (#77), `V4_7__saved_view` (#78), `V4_8__notification_outbox` (#94), `V4_9__user_alert` (#96),
`V4_10__update_request` (#97), plus the repeatable `R__grants` per vendor. WP-15 part 4, WP-16, #95 and #98 added no
migration. `V5__closure` (WP-19, in flight) is next. A later part of a work package that needs a migration takes the next `V4_n`; the number
is assigned in the PR that ships it.

### Not built yet

Closure workflow (WP-19, in flight); reminders tick (WP-17b); the rest of the dashboard (WP-20; "Needs attention"
already lists update requests); SLA evaluator and alerts (WP-21); reports (WP-22, 23); retention and purge (WP-24); System screen (WP-25); importer (WP-26, designed in
`docs/design/importer.md` and ADR-0014, not started); preferences and shortcuts (WP-27); DAST, 508 conformance pass,
performance and runbook (WP-28 to 30).

Consequences for local development today: `compose.yaml` runs PostgreSQL, Keycloak, ClamAV with its TLS sidecar (the
`local` profile trusts the dev-only CA that `clamav-tls-init` generates, so run `docker compose up -d` before
`spring-boot:run`), Mailpit (the mail catcher the outbox dispatcher sends to; read the mail at
`http://localhost:8025`) and an optional Oracle. `README.md` and `CONTRIBUTING.md` are updated to
match in the same change as this refresh.

## 3. How to run and verify

```sh
docker compose up -d                                   # PostgreSQL 16, Keycloak 26 (dev realm), ClamAV, Mailpit
./mvnw verify                                          # unit tests, PostgreSQL ITs, every local gate
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Sign in at `http://localhost:8080` with a dev-realm user (`deploy/local/keycloak/README.md`; password
`dev-password`). `system.admin` reaches `/admin/teams`; `compliance.admin` reaches `/admin/sla-policies` and
`/admin/case-types`. The `local` profile seeds two SLA policies, three case types, the teams Alpha and Beta and the
dev-realm users' team roles on start-up (`app.seed` in `application-local.yml`; #29), so `alpha.analyst` can create
a case at `/cases/new` and work it from `/cases`. Further memberships are granted at `/admin/teams`.

Mail: the `local` profile points the outbox dispatcher at the compose Mailpit (SMTP on `localhost:1025`, mail at
`http://localhost:8025`) and sets `app.base-url`, the start of every deep link in a mail. Outside `local` and `test`
nothing is guessed: `app.base-url`, `app.mail.from` and `spring.mail.host` have no default and the application
refuses to start without them (`app.base-url` always; the other two while `app.mail.enabled`, true by default; see
`application-prod.yml`). With `app.mail.enabled` false, mail keeps queuing in `NOTIFICATION_OUTBOX` and is sent once it
is true again.

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
| `main.yml` | push to `main` (each merge cancels the previous run); on demand with `gh workflow run main.yml` (its own concurrency group since #88, so a push does not cancel it); called by nightly and release | SonarCloud, Dependency-Check, SBOM + Trivy, image build + Trivy (`--ignore-unfixed`) + Dockle + kube-linter, `oracle-it` (both images, one job) |
| `nightly.yml` | 04:41 UTC | `main.yml` again, and the `perf` job (`PerfCasesListIT` at 50k cases; fails at a list p95 of 500 ms or a search p95 of 1 s); the 500k-comment k6 run and ZAP are placeholders until WP-30 and WP-28 |
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
| `config/spotbugs/exclude.xml` carries `SPRING_ENDPOINT` for every controller (`DashboardController`, `SessionController`, `TeamAdminController`, `UserAdminController`, `CaseConfigController`, `CaseController`, `CaseEditController`, `CaseAssignmentController`, `CaseAttachmentController`, `CaseListController`, `SavedViewController`, `CaseExportController`, `CaseHistoryController`), listed by class | #19, #20, #22, #49, #50, #59, #62, #63, #78, #84, #87 | The pattern reports no defect; each controller is reviewed and the approval recorded in the file; `spotbugs-annotations` is LGPL and off the license allow-list, so the file is the mechanism |
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
| `VIEW_RESTRICTED` is written in the read's own transaction, through one `RestrictedReadAudit` | #83, #87, BUILD_PLAN §3.3 | The case page, an attachment download and the History page share the one writer, so the rule lives in one place; the audit row commits or rolls back with the read, and for the History page it is written before the events are read, so the page's own entry is the newest line. Absent and unreadable cases write nothing (404) |
| The audited export's `query` carries search words as typed | #84 | `CASES_EXPORTED` stores the list's query string as sent, which can include search words; `subject` and `description` never enter the audit row. Accepted by the release manager: the audit trail is itself access-controlled |
| Exports hold a database connection while they stream, are not counted against `app.attachments.max-concurrent-downloads`, and keep no semaphore of their own | #84, #91 | `app.export.max-rows` bounds an export's length, and rows are read in pages of 500 inside one read-only transaction. WP-15 part 5 measured 0.5 s for 9 645 rows with details (about the cap), so a Hikari pool of 10 serves ten at once without an interactive request waiting more than that. A slow client stretches the hold; if WP-30's load test shows it, share the download semaphore (the small fix) or render to a temporary file (the larger one). Both are recorded in `docs/perf/cases-list-50k.md`, not built |
| No index or cache was added for the cases list; the recommendations (`CASE_RECORD (OPENED_AT, ID)`, an expression index on `LOWER(TITLE)`, no cache for the filter choices) wait for WP-30's 500k run | #91 | Every target is met with a wide margin at 50k cases, so a migration now would be speculation; `docs/perf/cases-list-50k.md` has the evidence to weigh them against |
| History is a separate page, not an ARIA tab strip | #87, IMPLEMENTATION_PLAN WP-16 | A page with plain previous and next links, a labelled native select for the kind, and a "Case sections" link list works without script and has one h1 and one region to test. A tab strip, if it ever comes, is Phase 6 polish and these links are its no-script form. A redacted field reaches the page only as "(set)" or "(changed)"; the check is structural (no value, HMAC or length is passed to the template) |
| The outbox dispatcher takes no ShedLock | #94, #95 | It runs every minute on every web replica. The claim's `FOR UPDATE SKIP LOCKED` gives each row to exactly one claimer, and its lease keeps the row there until it is released or lapses (`app.mail.lease`, 5 minutes), so replicas cannot send the same row twice and no extra lock table is needed |
| Mail delivery is at-least-once | #95 | A crash between the server accepting a batch and the release leaves the rows `SENDING`; the reaper returns them after the lease and they are sent again. Nothing is lost; that batch may arrive twice. The class Javadoc says so |
| A dispatch pass stops after a batch that sent nothing | #95 | An unreachable server costs one batch's attempts per minute instead of walking the whole backlog through connection timeouts; the rest of the backlog keeps its attempt count |
| Spring Boot's mail health indicator is off (`management.health.mail.enabled=false`) | #95 | The relay is cluster-level (Part A #30), so an unreachable relay is not this pod's health; it shows as a growing pending gauge from `OutboxMetrics` |
| `app.base-url` is required and has no default (`local` and `test` set one) | #95 | It starts every deep link in a mail; a deployment sets it or does not start, so no mail links to a guess. `app.mail.from` and `spring.mail.host` are likewise required while mail is enabled. A personal mail can only link to a single-slash path inside the application |
| `NotificationService.notifyUser` writes the alert and queues the mail in the caller's transaction | #96 | The alert, the mail and the action that caused them commit or roll back together; outside a transaction the call is refused. An unknown or disabled person, or one with no address, gets no mail |
| Alerts are filtered by the person's `CaseScope`: an alert about a case the person can no longer read is neither listed nor counted | #96, #108 (issue #101) | #96 first shipped them unfiltered: an alert belongs to a person, not a case, and carries only the case number, title and who acted. #108 hides the alert, and drops it from the badge so the two agree, once the case is restricted to others or the person leaves its team; an alert about no case stays. `notification` may not call `casefile` (a cycle), so `UserAlertQueries` reaches the case through a subquery on the case entity, as `audit` does (#86). The alert is hidden, not deleted: it returns if access does |
| `ANSWER_UPDATE` is its own action in the permission matrix | #97, `permissions/matrix.csv` | Marking someone else's request answered (Managers and Compliance Admins, on open and pending-closure cases) is a row in the matrix like every other action, not a branch in the service; the requester can always mark their own, so the service checks both |
| One open request per case and recipient is a single-column unique key, `UPDATE_REQUEST.OPEN_KEY` | #97, `V4_10` | `OPEN_KEY` holds `"<case id>:<recipient id>"` while the request is open and is NULL once answered, so `UK_UPDATE_REQUEST_OPEN` enforces the rule on both vendors: a single-column unique key ignores NULLs on Oracle and PostgreSQL alike, which a composite key with a nullable column does not on Oracle. A second open request is a 409. A comment from the recipient resolves the request through the `CommentPosted` Modulith event |
| From WP-19, JavaScript counts toward the 400-line cap | #98 review | `app.js` +127 and `app.css` +10 were not counted in WP-18 part b's 233 lines. From WP-19 they are counted with the Java, templates and messages |
| Attachment scanning is `clamd` over TLS by default, or `external` | ADR-0012, ADR-0013, #55, #57 | The scan is synchronous and nothing is stored on a scanner error; `external` stores files as `NOT_SCANNED`, never `CLEAN`. Deployment configuration, so no application account can turn scanning off |
| PR 10 (#23) exceeded 400 net production lines | #23 review | Accepted so the `V3_1__sessions` migration shipped with the code that uses it |

## 6. Known gaps and follow-ups (all tracked)

| Issue | What |
|---|---|
| #30 | Phase 1 exit items: scanner secrets, real IdP login in DEV, shell ACR manual testing |
| #92 | Owner item: the manual assistive-technology pass on the Phase 2 screens (a Phase 2 exit criterion). Still open, and it now also covers the Alerts page and the Request update dialog with its combobox (WP-17, WP-18), which pass axe and a keyboard walk but have had no screen-reader pass |
| #24 | Spring Session license tracker (stay on 4.1.0) |
| #67 | Bug-hunt triage of 2026-11-05 (report under `docs/bug-hunts/`, eleven suspicions). Rejected with evidence: 1, 2, 3, 5, 8, 9 (inactive-team memberships count toward the System Admin conflict, by design), 10, and the session-boundary part of 11. Fixed: 4 (local seed names compared stripped, #73), 6 (multipart headroom, #76), 7 (CHECK ceilings, #77), 11 (CRLF counted once in the description limit and surrogate pairs kept whole in the audit name, #74). Carried forward: 9's in-memory role-conflict list is replaced when WP-25 reads the durable `ROLE_CONFLICT` audit rows (latest per user, cleared by a later conflict-free `LOGIN`), and then `UserSignInService.roleConflicts()` goes away; item 1 is a note for WP-24 (a purge path that rewrites search text must handle a null description); the attachment size display shows 1,048,525 to 1,048,575 bytes as "1024.0 KB" (deferred, cosmetic) |

Closed since 0.1.0: #26 (the OIDC session registry IT runs on the Oracle leg, #38), #27 (`ConstraintViolationException`
maps to 422, #36), #28 (back-channel `logoutUri` follows the bound port, #37), #29 (local seed names the unknown
policy and seeds teams Alpha and Beta, #40), #31 (disabling a user ends their sessions, #63), #66 (the htmx 403
redirect no longer doubles the servlet context path, #72), #69 (the Oracle leg on `lower()` of the `CLOB`, #70) and
#82 (`VIEW_RESTRICTED` was never written; now it is, #83).

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

1. **WP-19, closure**, in flight, in three PRs: the schema (`V5__closure`) and the closure request with separation of
   duties from Part A #18; the decisions (approve, reject), reopen and the concurrency rules; then the screens and
   golden paths 2 to 4. It is where the approver rules get code, and from it JavaScript counts toward the 400-line
   cap (section 5).
2. Then Phase 4 (WP-21, the SLA evaluator and alerts; WP-17b, the reminders tick, which will use
   `UPDATE_REQUEST.LAST_REMINDED_AT` and the outbox that WP-17 built; WP-20, the rest of the dashboard; WP-22 and
   WP-23, reports), Phase 5 (WP-24 to 27) and Phase 6 (WP-28 to 30). WP-25 owns the role-conflict list described in
   section 6; WP-24 owns purge.
3. Carried forward from Phase 2: the index recommendations of `docs/perf/cases-list-50k.md` wait for WP-30's 500k
   run, and rows open on click only (the case-number link is the keyboard path, #84).

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
