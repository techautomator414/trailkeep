<!--
  SPDX-License-Identifier: Apache-2.0
  Copyright 2026 Charlie Genova and contributors
-->
# Trailkeep handoff: state at the end of Phase 5

_Written 2026-11-04 at 0.1.0 for the team taking over; refreshed 2026-11-06, after WP-11 to WP-18 merged (Phase 2
closed), 2026-11-07, after WP-19 merged (Phase 3 closed), 2026-11-08, after WP-23 merged (Phase 4 closed), and
2026-11-13, after WP-24 to WP-27 merged (Phase 5 closed). Read this first, then `README.md`, then
`IMPLEMENTATION_PLAN.md` Part B (standards) and Part C (work packages). Where this file and the plans disagree, the
plans win; this file only says where the build stands._

## 1. Where the project stands

| Measure | Done | Total | Share |
|---|---|---|---|
| Work packages (IMPLEMENTATION_PLAN Part C) | 30 (WP-01 to WP-08 are 10 rows with the 03a/b and 04a/b splits, plus WP-09 to WP-27 with WP-17b) | 33 | 91 % |
| Work packages weighted by size (S = 1, M = 2, L = 3) | 60 (53 at the end of Phase 4, plus WP-24 M, WP-25 S, WP-26 L and WP-27 S) | 67 | 90 % |
| Phase 1 pull requests (Part D) | 12 | 12 | 100 % |
| Plan calendar (Part F) | 18 weeks: Phase 1 (4), Phase 2 (5), Phase 3 (3), Phase 4 (3) and Phase 5 (3) | 25 weeks | 72 % (the 25 include Phase 0 discovery and Phase 7 go-live, 2 weeks each, neither of them build work; Phases 1 to 6 alone are 18 of 21 weeks, 86 %) |

What is left in the plan is Phase 6: WP-28 (DAST and security E2E, M), WP-29 (the Section 508 conformance pass, L,
with a named certified tester) and WP-30 (performance at 500k, restore drill, runbook and 1.0.0, M).

**Phase 5 is complete.** WP-24 (retention, archive, legal hold and purge, M; #125 to #128), WP-25 (the System screen
and operational alerts, S; #139, #140, #144, #153, #159, #160), WP-26 (the generic importer, L; 53 pull requests from
#134 to #202) and WP-27 (preferences, shortcuts and i18n, S; #132, #135, #138) are done. WP-26 was planned as the 31
rows of `docs/design/importer.md` §12 and landed as 53 PRs; section 2 maps each row to its PRs.

**Phase 4 is complete.** WP-21 (SLA evaluator and alerts, L; #111, #112, #113), WP-17b (the reminders tick, S;
#114), WP-20 (the dashboard, S; #115), WP-22 (aging and SLA-compliance reports, M; #116, #118, #119) and WP-23
(scheduled report delivery, M; #120, #121, #123) are done. Part F counts WP-17b and WP-20 in Phase 4, which is where
they landed.

**Phase 3 is complete.** WP-17 (email outbox, dispatcher and in-app alerts, M), WP-18 (request update, M) and WP-19
(closure, L) are done. WP-17b (the reminders tick, S) and WP-20 (the dashboard, S) sit in Phase 3's table in Part C but
are marked "Lands in Phase 4", and Part F's calendar counts them in Phase 4, where they are now done.

**Phase 2 is complete.** WP-15 (cases list, L) is done: parts 1a, 1b, 2, 3, 4 and 5 are merged (list, sort and paging;
filters and search; the filter drawer, chips and multi-status; saved views; the CSV export with redaction, row
selection and row click; the 50k-case performance run). Its acceptance line, p95 under 500 ms at 50k cases on
PostgreSQL, is measured: 132 ms for the list and 109 ms for the two-word search (the slowest single-word search measured, 120 ms; #91,
`docs/perf/cases-list-50k.md`), and the nightly `perf` job fails if either target is missed. WP-16 (history tab, S) is done.

In plain terms: the platform is built and gated, administration works, and **cases can be worked, emailed, asked for
updates, closed through the one approval gate and reopened**: a person creates one on a single page, views it, edits it under optimistic locking, reassigns or restricts
it, comments and pins comments, attaches files (validated, virus-scanned, downloadable, with a Compliance Admin
release for quarantined ones), finds it in a filtered, searchable, sortable list, saves a view of it, exports the
list to CSV with subject and description redacted unless the person may see them, and reads a case's History page.
A comment by anyone but the owner emails the case's owner and raises an in-app alert for them, read on the Alerts page
behind a badge in the navigation; "Request update" asks a colleague for an answer by mail and alert, and a comment from that person (or
Mark answered) resolves the request, which the dashboard's "Needs attention" shows until then. Anyone who works the
case (an Analyst, Manager or Compliance Admin of its team) requests closure with an outcome, a resolution summary and one approver whom the separation-of-duties rule allows; the
case is then pending and cannot be edited or reassigned. Only that approver decides, from the case or from the
dashboard's "Pending my approval": approving closes the case, finalises its SLA and locks the closure; rejecting or
requesting changes, with a reason, sends it back to Open with a "Requires Changes" badge until the next comment. A
Manager or Compliance Admin may reassign the approver while the request is pending, and may reopen a closed case with a reason, which
starts the next SLA cycle; the case page then shows who reopened it, when and why. All of it goes through the
principal-backed `CaseScope`, so an unreadable case answers 404, and reading a restricted case (its page, a download,
its history) is itself audited as `VIEW_RESTRICTED`. Disabling a user from `/admin/users` ends that user's sessions
(#63).

Phase 4 makes the clock run on its own. **SLA states now move as time passes**: every 15 minutes the evaluator moves
open and pending cases to At risk and Overdue (one replica at a time, in chunks, never touching the case row), and the
owner (and the team's Managers when the owner is disabled or an overdue case's policy escalates to them) get one alert
and mail per case, state and cycle; the case's SLA chip, the cases list's SLA filter and the History page follow. An
hourly **reminders tick** nudges the recipients of unanswered update requests and the approvers of pending closures, and repeats the overdue
alert every policy period, each **once per period** through the same alert ledger. **The dashboard** has KPI tiles (my
open cases, at risk, overdue, pending my approval, update requests for me), My cases, and paged lists under "Needs
attention". **Reports**: the aging report (open cases by team and owner, by age band and SLA state, with the overdue
cases and unanswered update requests to chase) and the SLA-compliance report (cases closed in a period by team and
case type, with the share within the SLA), each as accessible, printable HTML and as an audited CSV. **The aging
report is delivered weekly per team**: an hourly job stores each due team's report as a run and tells the team's
Managers and every Compliance Admin by mail and alert, linking to the run; the runs have their own pages and CSV
download, and Compliance Admins set each team's day and hour at `/admin/report-schedules`.

Phase 5 adds the end of a case's life, the operator's view and the way in for old records. **Retention**: an hourly
archive job moves each closed case to `ARCHIVED` once its case type's `RETENTION_YEARS` have passed since closure
(archived cases are read-only, cannot be reopened and are left out of the lists unless a Manager or Compliance Admin
asks for them), and a Compliance Admin places and lifts a **legal hold** with a reason, which stops both archive and
purge and leaves a readable hold history. The **purge** is a separate `CronJob` (`prod,purge`, its own database user,
no web server) that tombstones archived cases past `PURGE_AFTER`: their content is deleted in foreign-key order, the
case row keeps its number, type, team, dates and title, and the audit trail, alert ledger and hold history stay. **The
System screen** (`/admin/system`, System and Compliance Admins) shows every job's last run from `JOB_RUN`, the outbox
backlog, the scanner, the durable role-conflict list, the last import run, and sends a person any of the 11 mails as
a test; Prometheus rules alert on late jobs, mail, the database pool and the scanner. **The importer** brings a legacy
tracker's cases, comments, attachments, closures and history in from SQL Server, Oracle or PostgreSQL (live, read-only)
or from CSV, TSV or JSON Lines exports, through staging, a dry run a Compliance Admin signs off on the Import runs
screen (`/admin/import-runs`), an idempotent load under its own database user, delta runs, owner relinking and a
reconciliation report; it runs as a Kubernetes `Job`, one command per run. **Preferences**: `/preferences` turns the
single-key shortcuts (`/` for the cases search, `N` for a new case) on or off, all mail wording lives in
`messages.properties`, and every time a page shows is in `app.timezone`.

What Phase 6 adds: authenticated DAST with ZAP and the security E2E suite (WP-28), the Section 508 conformance pass
and ACR by the named certified tester (WP-29), and performance at the 500k seed, the restore drill, the runbook, the
evidence pack and 1.0.0 (WP-30).

0.1.0 is the `v0.1.0` tag, cut from `main` as the last step of this handoff, after #25 (CI fix for #13), #33 (the
test port fix below) and #32 (this document) merged. Every workflow is green on that commit: `ci`, `main` (both Oracle images, image build and
scan), `codeql`, `scorecard`. "Green" has one caveat: `main.yml` skips SonarCloud and Dependency-Check with a notice
when their secrets are absent, and they were absent at handoff (#30). No tag has been cut since; `v0.2.0` is an owner
item (section 6, item 8).

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
| WP-19 | Closure. a, #100: `V5__closure` (`CLOSURE_REQUEST`), Request Closure with the A.2 #18 approver rule and the move to `PENDING_CLOSURE` through `CaseStatusService`. b, #102: approve, reject and request changes, and approver reassignment; each takes the case row lock, re-checks the status and the matrix on what the lock holds, and updates the request conditionally at its version. b2, #103: `V5_1__case_reopen` (`CASE_REOPEN`) and reopen, which starts the next SLA cycle (`CaseSla.restart`). c1, #104: the Request closure dialog and the pending-closure banner. c2a, #105: Approve, the Reject or request changes dialog, the "Requires Changes" badge and the locked closure of a closed case. c2b, #106: Reassign approver and the dashboard's "Pending my approval". c3, #107: the Reopen dialog and the "Reopened" chip. c4, #109: who reopened a case, when and why, read from `CASE_REOPEN` | #100, #102, #103, #104, #105, #106, #107, #109 | `io.trailkeep.closure` (`ClosureRequest`, `ClosureService`, `DefaultClosureService`, `ClosureDecisionService`, `DefaultClosureDecisionService`, `CaseReopen`, `ClosureRequestQueriesImpl`, `CaseReopenQueriesImpl`), `CaseStatusService.move`, `CaseSla.restart`, `ClosureController`, `ClosureDecisionController`, `ClosureApproverController`, `CaseReopenController`, `ClosureService.lastReopen`, `templates/casefile/fragments/closure-request.html`, `templates/casefile/fragments/closure-decision.html`, `ClosureRequestIT`, `ClosureDecisionIT`, `CaseReopenIT` and their `Oracle*` twins, `RequestClosureAccessibilityIT`, `ClosureDecisionAccessibilityIT`, `ClosureApproverAccessibilityIT`, `CaseReopenAccessibilityIT` |
| WP-21 | SLA evaluator and alerts. a, #111: `V6__sla_alerts` (`ALERT_LEDGER` with its unique key, `JOB_RUN`), the evaluator every 15 minutes under ShedLock, candidates by `(SLA_STATE, AT_RISK_AT)` and `(SLA_STATE, SLA_DUE_AT)`, id-ordered chunks of 500 in one transaction each, a JPQL row lock and bulk transitions, `SLA_STATE_CHANGED` history. b, #112: `sla-at-risk` and `sla-overdue` mail and alert once per case, type and cycle through the ledger, metrics, the cluster-level health indicator. c, #113: the SLA chip, the cases list's SLA filter and the History label once states move | #111, #112, #113 | `io.trailkeep.sla` (`SlaEvaluator`, `SlaChunkWriter`, `AlertLedger`, `AlertType`, `JobRun`, `JobRuns`, `JobHealth`, `SlaEvaluatorHealthIndicator`, `SlaMetrics`), `CaseSlaService`, `DefaultCaseSlaService`, `SchedulerLockConfig`, `AbstractSlaEvaluatorIT` and its Oracle twin |
| WP-17b | Reminders tick, hourly under ShedLock in pages of 500: update-request reminders (`app.reminders.update-request-days`), pending-closure reminders (`app.reminders.closure-days`) and overdue repeats every `OVERDUE_REMINDER_DAYS`, each once per period through the ledger | #114 | `RemindersTick`, `ReminderWriter`, `RemindersTickHealthIndicator`, `UpdateRequestQueriesImpl`, `ClosureRequestQueriesImpl`, `templates/notification/` (`update-reminder`, `closure-reminder`, `sla-overdue-repeat`) |
| WP-20 | Dashboard: KPI tiles (each a count in text and a link), My cases (ten, by SLA due date), "Needs attention" paged by 20 with `Paged<T>`; and the CI fix that stops each test class's Keycloak container before the next one starts (c54f2b5) | #115 | `DashboardController`, `templates/web/dashboard.html`, `shared.Paged`, `KeycloakDevRealm.start`, `DashboardAccessibilityIT` |
| WP-22 | Reports. a, #116: aging (team and owner, age bands and SLA states, a total row) and SLA compliance (team and case type, a period of up to 366 days) as accessible HTML with a print stylesheet, counted in one grouped Criteria query each under `CaseScope`. b, #118: the same as CSV through `shared.csv.CsvFormat`, audited `REPORT_EXPORTED`. c, #119: the aging report's overdue cases and unanswered update requests (at most 100 each) | #116, #118, #119 | `io.trailkeep.report` (`ReportService`, `DefaultReportService`, `ReportExportService`, `DefaultReportExportService`), `CaseReportService`, `CaseReportQueriesImpl`, `ReportController`, `templates/report/`, `AbstractReportIT` and its Oracle twin, `ReportsAccessibilityIT` |
| WP-23 | Scheduled report delivery. a, #120: `V7__report_delivery` (`REPORT_SCHEDULE`, `REPORT_RUN`, the outbox's `REPORT_RUN_ID` foreign key), the hourly `report-delivery` job that stores each due team's aging report (the page's own fragment and the export's own CSV, read as a Manager of that team) and sends `report-ready` to the team's enabled Managers and every enabled Compliance Admin, `app.reports.admins-only` for the first cycle, its health indicator. b1, #121: `/reports/runs`, `/reports/runs/{id}` (the stored HTML through `StoredReportSanitizer`) and the audited `/reports/runs/{id}.csv`. b2, #123 (reviewed and approved as #122, which closed when its stacked base was deleted): the schedule admin at `/admin/report-schedules` | #120, #121, #123 | `ReportDeliveryTick`, `ReportDeliveryWriter`, `ReportHtml`, `ReportSchedule`, `ReportRun`, `ReportDeliveryHealthIndicator`, `ReportRunService`, `ReportScheduleService` and their `Default*` implementations, `shared.html.StoredReportSanitizer`, `ReportRunController`, `ReportScheduleController`, `NotificationService.notifyOfReport`, `AbstractReportDeliveryIT`, `AbstractReportRunIT`, `AbstractReportScheduleIT` and their Oracle twins, `ReportRunsAccessibilityIT`, `ReportSchedulesAccessibilityIT` |
| WP-24 | Retention, archive, legal hold and purge. a, #125: the hourly `archive` job (minute 37, ShedLock, chunks of 500) moves `CLOSED` cases past their type's `RETENTION_YEARS` to `ARCHIVED` with `ARCHIVED_AT` and `PURGE_AFTER` (`app.retention.purge-grace`, 30 days), audited `CASE_ARCHIVED`; archived cases are read-only, not reopenable, and listed only when a Manager or Compliance Admin filters for them; no migration (the columns and both indexes date from V4). b, #126: the `LEGAL_HOLD` matrix action for Compliance Admins in every status, a reason of 1 to 500 characters, row lock and re-check (409), lifting pushes `PURGE_AFTER` to at least now plus the grace; audited `LEGAL_HOLD_PLACED` and `LEGAL_HOLD_LIFTED`. b2, #127: `V8__legal_hold_events`, the readable hold history shown to Compliance Admins. c, #128: the purge under the `purge` profile as `TRAILKEEP_PURGE`: chunks of 100 re-read `FOR UPDATE` (a hold placed meanwhile wins), content deleted in foreign-key order, the case tombstoned (`PURGED_AT`, description and subject `NULL`), one `CASE_PURGED` audit row of counts per case, report runs older than `app.retention.report-runs` deleted after the mail that names them, a `JOB_RUN` row, counts-only log; the purged case still opens with a notice | #125, #126, #127, #128 | `io.trailkeep.retention` (`ArchiveJob`, `ArchiveHealthIndicator`, `PurgeJdbcDao`, `PurgeJob`, `PurgeRunner`), `CaseRetentionService`, `LegalHoldService`, `LegalHoldController`, `application-purge.yml`, `deploy/base/purge-cronjob.yaml`, `deploy/README.md` step 6, `ArchiveJobIT`, `LegalHoldIT`, `PurgeIT` and their Oracle twins, `PurgeProfileIT`, `PurgedCasePageIT`, `LegalHoldAccessibilityIT`, both `DatabaseGrantsIT`s (the negative ITs) |
| WP-25 | System screen and operational alerts. a, #139: `/admin/system` for System and Compliance Admins (everyone else 403, no case content): each job's last success from `JOB_RUN` (on time, late against its health grace, not run yet, or not watched for the purge), the outbox's waiting and failed counts and oldest waiting mail, the scanner (clamd `VERSION` over the scan's TLS, bounded to 3 s, or "external scanning"). b, #140: the durable role-conflict list read from the audit trail (latest `LOGIN` or `ROLE_CONFLICT` per user), replacing the in-memory list and `UserSignInService.roleConflicts()`. c, #144: the optional `PrometheusRule` component (`TrailkeepSlaEvaluatorLate`, `TrailkeepRemindersLate`, `TrailkeepReportDeliveryLate`, `TrailkeepArchiveLate`, `TrailkeepMailNotSending`, `TrailkeepMailBacklog`, `TrailkeepMailFailed`, `TrailkeepDbPoolExhausted`), each with a runbook anchor. f, #153: a per-replica clamd probe every minute (`trailkeep_scanner_up`) and `TrailkeepScannerDown`. e, #159 (superseding #145): "send me a test" of any of the 11 mails to the person's own address through the outbox, audited `TEST_MAIL_QUEUED`, one per 5 minutes per person. d, #160 (superseding #156): the last import run, through `ImportRunService`, the importer's public read API | #139, #140, #144, #153, #159, #160 | `SystemController`, `TestMailController`, `templates/web/system.html`, `ScannerProbe`, `deploy/components/prometheus-rules/`, `deploy/README.md` "Alerts", `ManagementEndpointsIT`, `SystemAccessibilityIT` |
| WP-26 | The generic importer, designed in `docs/design/importer.md` and ADR-0014: the `importer` profile as a `Job` under `TRAILKEEP_IMPORT`; commands `status`, `validate-mapping`, `extract`, `dry-run`, `load`, `relink-owners`, `reconcile`, `purge-staging`; sources SQL Server, Oracle and PostgreSQL (read-only snapshot, query validation with JSqlParser and per-vendor function allow-lists, URL allow-list) and CSV, TSV and JSON Lines exports with a manifest; a YAML mapping checked against a JSON Schema, a closed transform set and keyed (HMAC) hashes; staging with checkpoints and a TTL; the dry run's issues, figures, people matching, default owners and go-live SLA forecast, signed off by a Compliance Admin; a load idempotent through `IMPORT_LEDGER`, with attachments through the synchronous scan, comments, closures by the two import principals (ADR-0015), legacy history in `CASE_IMPORTED_EVENT`, go-live alerts pre-marked (ADR-0016) and holds for legacy-held or past-retention cases; delta runs and the closure worklist; `relink-owners`; reconcile from written hashes to a `RECONCILED` verdict; owner-only CSV and HTML report files; the Import runs screen; the History tab's imported rows; export recipes and a worked example, each run by a test | 53 PRs, #134 to #202 (table below) | `io.trailkeep.importer`, `CaseImportService`, `ClosureImportService`, `AttachmentImportService`, `ImportRunController`, `application-importer.yml`, `src/main/resources/importer/mapping-v1.schema.json`, `deploy/jobs/`, `deploy/README.md` "Importer", `docs/importer/` (`export-recipes.md`, `source-accounts.md`, `worked-example.md`), ADR-0014, ADR-0015, ADR-0016, `WorkedExampleIT` and `OracleWorkedExampleIT` |
| WP-27 | Preferences, shortcuts and i18n. a, #132: `V9__user_preference` and `/preferences` with the single-key shortcuts switch (on by default), audited `PREFERENCES_CHANGED`; `/` focuses the cases search (from any page), `N` opens `/cases/new`, both ignored while typing, in a dialog or with a modifier. b, #135: the subject, alert, text and HTML of all 11 mails as 91 `tk.mail.*` messages, compared against a golden file, and `TemplateTextTest`, a guard against literal wording in any template (its allow-list is empty). c, #138: `app.js` takes its announcements from the layout only. The time-zone audit's one finding, legal hold times rendered in the JVM zone, was fixed by #133 | #132, #135, #138 (and #133) | `UserPreferenceService`, `PreferencesController`, `PreferenceNav`, `app.js` (`wireShortcuts`), `MailTemplates`, `messages.properties`, `MailTemplatesTest`, `TemplateTextTest`, `ShortcutsAccessibilityIT` |

WP-26 by design row (`docs/design/importer.md` §12; every split and its deviations are in the PR):

| Row | PRs | Row | PRs |
|---|---|---|---|
| 26a import runs, `TRAILKEEP_IMPORT`, `status` (V10) | #134 | 26p SQL Server source, `-Psqlserver-it` | #167 |
| 26b mapping JSON Schema, `validate-mapping` | #146 | 26q dry run: case and child rules | #170, #171 |
| 26c the two import principals (V11) | #137 | 26r dry-run figures, people and default owners, SLA forecast | #172, #173, #174 |
| 26d legacy number and owner on cases (V12) | #142 | 26s Import runs screen, run request and sign-off | #175, #176 |
| 26e `CaseImportService` | #157 | 26t `IMPORT_LEDGER` (V17), load of cases and closures, run lock | #177, #179 |
| 26f imported comments | #163 | 26u load of comments and attachments, scan-mode check | #180, #181, #182 |
| 26g imported attachments, uploader snapshot (V14) | #149 | 26v `CASE_IMPORTED_EVENT` (V16) | #152 |
| 26h imported closure marker (V15) | #150 | 26w load of legacy history | #183 |
| 26i transforms, text normalisation | #154 | 26x History tab's imported rows | #200 |
| 26j staging tables (V13) | #147 | 26y delta runs, closure worklist | #184, #185, #186 |
| 26k JSON Lines source, `extract` | #158, #161, #164 | 26z owners pending (V18), `relink-owners` | #187, #188, #189 |
| 26l CSV and TSV source, lookup files | #168, #169 | 26aa written hashes (V19), reconcile, report files | #190, #191, #192, #193 |
| 26m query validation | #155 | 26ab importer `Job`, dry-run report files | #194, #195 |
| 26n JDBC source query, PostgreSQL source | #162, #165 | 26ac go-live alerts pre-marked | #196 |
| 26o Oracle source | #166 | 26ad import holds | #198 |
| | | 26ae export recipes and the worked example | #197, #199, #201, #202 |

Fixes and CI changes merged during Phase 5, outside the work packages: #133 (legal hold times in `app.timezone`),
#143 (published UBI errata applied in the runtime image, CVE-2026-53613), #148 (every merge to `main` gets its own
complete `main.yml` run, never cancelled by the next), #151 (the at-risk SLA walk kept off the day boundary), #178
(`app.db.schema` becomes every pooled connection's default schema, found by the importer's Oracle load IT), #136
(Dependabot ignore rules for the `tika-core` 3.x and bnd annotation 4.3.1 pins) and #129 (commonmark 0.32).

Schema on `main`: `V1__identity`, `V2__audit`, `V3__admin`, `V3_1__sessions`, `V4__case`, `V4_1__comment`,
`V4_2__comment_pin`, `V4_3__attachment`, `V4_4__attachment_not_scanned`, `V4_5__search_text_lowercase` (#70),
`V4_6__sla_check_ceilings` (#77), `V4_7__saved_view` (#78), `V4_8__notification_outbox` (#94), `V4_9__user_alert` (#96),
`V4_10__update_request` (#97), `V5__closure` (#100), `V5_1__case_reopen` (#103), `V6__sla_alerts` (#111),
`V7__report_delivery` (#120), `V8__legal_hold_events` (#127), `V9__user_preference` (#132), `V10__import_runs` (#134),
`V11__import_principals` (#137), `V12__case_legacy_number` (#142), `V13__import_staging` (#147),
`V14__attachment_uploader_snapshot` (#149), `V15__closure_imported` (#150), `V16__case_imported_event` (#152),
`V17__import_ledger` (#177), `V18__import_owner_pending` (#187), `V19__import_ledger_written` (#190), plus the
repeatable `R__grants` per vendor. Every migration is in `db/migration/common`; the vendor folders hold only
`R__grants.sql`. Other PRs added no migration. The next migration is `V20`, assigned in the PR that ships it.

### Database users, profiles and Jobs

Four database users, named per deployment (BUILD_PLAN §8 rule 7; the vendor `R__grants.sql` is the matrix and both
`DatabaseGrantsIT`s execute every statement as every user):

| User (example name) | Used by | May |
|---|---|---|
| schema owner | Flyway | own every table and run the migrations; nothing at run time connects as it |
| `TRAILKEEP_APP` (`app.db.app-user`) | the web `Deployment` | the application's tables, with no UPDATE or DELETE on comments, attachment content, the audit trail, the alert ledger or imported history and no DELETE of case data (it deletes only pins, saved views, alerts, sessions, ShedLock rows, memberships and outcome lists); SELECT only on the importer's tables, except INSERT of run requests and UPDATE of the sign-off columns on `IMPORT_RUN`; nothing on staging |
| `TRAILKEEP_PURGE` (`app.db.purge-user`) | the purge `CronJob` | SELECT and DELETE on the content it purges (comments, pins, attachments and chunks, search text, update and closure requests, reopens, imported history, the case's mail) and on `REPORT_RUN`; on `CASE_RECORD` SELECT and an UPDATE of `DESCRIPTION`, `SUBJECT_TEXT`, `PURGED_AT` and `VERSION` only; INSERT on `AUDIT_EVENT` and `JOB_RUN`; nothing on people, configuration, sessions, the alert ledger or the hold history |
| `TRAILKEEP_IMPORT` (`app.db.import-user`) | the importer `Job` | SELECT, INSERT and DELETE on the staging tables, which no other user can read; SELECT on every mapped table (so Hibernate validates); INSERT of what a load writes (cases, SLA rows, search text, comments, attachments, closures, imported history, ledger, alert ledger, hold events, audit); UPDATE only of `IMPORT_RUN`'s progress columns (never the sign-off), the search text append and, for `relink-owners`, a case's owner. It may stay locked while nothing is imported |

The three run-time users own no table, so on Oracle `app.db.schema` names the owner: it is Hibernate's default schema
and, since #178, every pooled connection's default schema (`ALTER SESSION SET CURRENT_SCHEMA` on Oracle,
`SET search_path` on PostgreSQL), which covers native SQL, ShedLock and Spring Session. Native SQL stays in `*JdbcDao`
classes, each saying why JPA cannot express it: `casefile` (`CaseNumberJdbcDao`, `CaseSearchJdbcDao`,
`CaseWrittenJdbcDao`), `document` (`AttachmentContentJdbcDao`, `AttachmentWrittenJdbcDao`), `notification`
(`OutboxJdbcDao`), `closure` (`ClosureWrittenJdbcDao`), `audit` (`ImportedEventWrittenJdbcDao`), `retention`
(`PurgeJdbcDao`, every statement the purge user runs) and `importer` (`SourceQueryJdbcDao`, the legacy source). The
`*WrittenJdbcDao` classes read back what a load wrote, as the database holds it, for reconcile's written hashes.

Profiles: `local`, `test`, `oracle-test` and `prod` as before; the vendor profiles `oracle` and `postgresql`
(through the profile groups in `application.yml`); and two batch profiles, combined with `prod`, both
`web-application-type: none`, with no OIDC client and Flyway off: **`purge`** (`PurgeRunner` runs once and the process
exits with its code; Hibernate does not validate, since the purge user sees only what it purges) and **`importer`**
(`ImportCommandRunner` runs the one `--import.command` given; Hibernate validates, and only this profile maps the
staging entities).

Kubernetes objects beside the web `Deployment`: the purge `CronJob` (`deploy/base/purge-cronjob.yaml`, `suspend: true`
until an overlay enables retention; Secret `trailkeep-purge`) and the importer `Job` (`deploy/jobs/importer`, or
`importer-external-scanning`), applied by hand per command, with its own `NetworkPolicy` (DNS, database and clamd only),
three claims (`trailkeep-import-data` read-only, `trailkeep-import-content` and `trailkeep-import-reports` writable) and
the Secrets `trailkeep-import` and `trailkeep-import-hash-key`, plus `app.audit.hmac-key` alone from `trailkeep-web`. Only `validate-mapping` and `extract` read the source:
for those the `jobs/components/source-access` component adds the source password (`trailkeep-import-source`) and the
egress label in one step (`jobs/importer-source-access`, `jobs/importer-external-scanning-source-access`).
`deploy/README.md` says migrations run as a separate pre-deploy `Job` with the owner credential; `deploy/` ships no
manifest for that step yet (section 6).

### Not built yet

Authenticated DAST and the security E2E suite (WP-28), the Section 508 conformance pass and ACR (WP-29), and
performance at 500k, the restore drill, the runbook, the evidence pack and 1.0.0 (WP-30). Two Part G checks have no
job yet: the ZAP scan (a placeholder in `nightly.yml` until WP-28) and the Nu HTML validator (section 4).

Consequences for local development today: `compose.yaml` runs PostgreSQL, Keycloak, ClamAV with its TLS sidecar (the
`local` profile trusts the dev-only CA that `clamav-tls-init` generates, so run `docker compose up -d` before
`spring-boot:run`), Mailpit (the mail catcher the outbox dispatcher sends to; read the mail at
`http://localhost:8025`) and an optional Oracle. Phase 5 added nothing to it. `README.md`'s status is updated in the
same change as this refresh.

## 3. How to run and verify

```sh
docker compose up -d                                   # PostgreSQL 16, Keycloak 26 (dev realm), ClamAV, Mailpit
./mvnw verify                                          # unit tests, PostgreSQL ITs, every local gate
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Sign in at `http://localhost:8080` with a dev-realm user (`deploy/local/keycloak/README.md`; password
`dev-password`). `system.admin` reaches `/admin/teams`; `compliance.admin` reaches `/admin/sla-policies`,
`/admin/case-types` and `/admin/report-schedules`. The `local` profile seeds two SLA policies, three case types, the teams Alpha and Beta and the
dev-realm users' team roles on start-up (`app.seed` in `application-local.yml`; #29), so `alpha.analyst` can create
a case at `/cases/new` and work it from `/cases`. Further memberships are granted at `/admin/teams`.
`system.admin` and `compliance.admin` both reach `/admin/system` and `/admin/import-runs`; only `compliance.admin` places
a legal hold (from the case page) or signs off a dry run. Everyone signed in has `/preferences`.

The batch profiles are exercised by tests rather than by hand: `PurgeProfileIT` and `AbstractPurgeIT` start `purge`
(the latter as the purge user), `AbstractImporterProfileIT` starts `importer` and the web application as their own
users, and `WorkedExampleIT` runs every importer command of `docs/importer/worked-example.md` against a synthetic
legacy database. Locally the compose PostgreSQL creates the three run-time roles without a login
(`deploy/local/postgres/01-roles.sql`), so `spring-boot:run` connects as the owner.

Mail: the `local` profile points the outbox dispatcher at the compose Mailpit (SMTP on `localhost:1025`, mail at
`http://localhost:8025`) and sets `app.base-url`, the start of every deep link in a mail. Outside `local` and `test`
nothing is guessed: `app.base-url`, `app.mail.from` and `spring.mail.host` have no default and the application
refuses to start without them (`app.base-url` always; the other two while `app.mail.enabled`, true by default; see
`application-prod.yml`). With `app.mail.enabled` false, mail keeps queuing in `NOTIFICATION_OUTBOX` and is sent once it
is true again.

Oracle parity, two separate options: `./mvnw verify -Poracle-it` starts its own Testcontainers for both public
images (xe 21 and free 23) and runs every IT tagged `oracle` (the schema and grants ITs and each `Oracle*` twin) on
each; `docker compose --profile oracle up -d` starts a long-lived Oracle Free 23 for manual work against the `oracle`
profile. The importer's SQL Server source ITs run with
`./mvnw -Psqlserver-it test-compile failsafe:integration-test@sqlserver failsafe:verify@sqlserver` (a SQL Server
Developer edition container as the legacy database). Testcontainers ITs skip without Docker locally and fail in CI
(`CI=true`).

Playwright: the `e2e` suite launches Chromium; locally it skips if no browser is installed, CI installs one.

## 4. CI and release

| Workflow | Trigger | What it proves |
|---|---|---|
| `ci.yml` | every PR and push to `main` | DCO, actionlint, gitleaks, Semgrep (registry rules plus `security/semgrep/`), `./mvnw verify` with Playwright |
| `codeql.yml` | every PR, push to `main`, weekly | CodeQL `security-extended` for Java |
| `scorecard.yml` | push to `main`, weekly | OpenSSF Scorecard |
| `main.yml` | push to `main` (since #148 each merge has its own concurrency group, so every code merge gets a complete run and none is cancelled); on demand with `gh workflow run main.yml` (its own group since #88); called by nightly and release | SonarCloud, Dependency-Check, SBOM + Trivy, image build + Trivy (`--ignore-unfixed`) + Dockle + kube-linter, `oracle-it` (`./mvnw verify -Poracle-it`, both images, one job), `sqlserver-it` (the importer's SQL Server source ITs, `-Psqlserver-it`, WP-26 part p) |
| `nightly.yml` | 04:41 UTC | `main.yml` again, and the `perf` job (`PerfCasesListIT` at 50k cases; fails at a list p95 of 500 ms or a search p95 of 1 s); the 500k-comment k6 run and ZAP are placeholders until WP-30 and WP-28 |
| `release.yml` | tag `v*` | `main.yml` again plus its own `./mvnw verify`, image to GHCR with keyless cosign signature, SBOM attestation, SLSA provenance, GitHub Release with the evidence pack (it does not re-run the `ci.yml` jobs) |

Secrets: `SONAR_TOKEN` and `NVD_API_KEY` are referenced but were not set at handoff (#30). The release workflow
needs no secrets beyond the job token for signing and publishing, but it forwards those two to `main.yml`, so a
release cut without them skips SonarCloud and Dependency-Check and its evidence pack has no Dependency-Check report.
They were still absent on 2026-11-13: the `sonarcloud` job of the latest `main` run reports "Secret SONAR_TOKEN is not
configured for this repository; the quality gate did not run." WP-28's acceptance ("Sonar gate A; Dependency-Check
clean") cannot be shown until they are set.

### Part G as it runs today

| Part G check | Fork PR | `main` | Nightly | Release tag | Notes |
|---|:-:|:-:|:-:|:-:|---|
| Compile, Spotless, Checkstyle, Error Prone/NullAway, SPDX, licence allow-list | ✓ | ✓ | ✓ | ✓ | `ci.yml` `build` on PRs and pushes; `main.yml` `oracle-it` runs the same `verify` for nightly; `release.yml` `verify` |
| Unit and slice tests, JaCoCo | ✓ | ✓ | ✓ | ✓ | as above |
| PostgreSQL ITs incl. `validate` and metadata-diff | ✓ | ✓ | ✓ | ✓ | as above |
| Oracle ITs (xe 21 and free 23) | – | ✓ | ✓ | ✓ | `main.yml` `oracle-it` |
| SQL Server source ITs (importer) | – | ✓ | ✓ | ✓ | `main.yml` `sqlserver-it`. **Not a Part G row**: proposed in #167 (WP-26 part p) and run since, but not written into `IMPLEMENTATION_PLAN.md`; owner decision (section 6) |
| CodeQL, Semgrep, SpotBugs/FindSecBugs, gitleaks | ✓ | ✓ | partly | partly | `codeql.yml` (PRs, pushes, weekly on Mondays) and `ci.yml` (Semgrep, gitleaks; PRs and pushes). Nightly and release get SpotBugs through `verify`, but neither runs Semgrep, gitleaks or CodeQL; a release tag is cut from a `main` commit those already passed |
| Playwright E2E + axe + Nu validator | ✓ | ✓ | ✓ | ✓ | Playwright and axe run inside `verify`. **The Nu HTML validator is not wired anywhere** (BUILD_PLAN §10.2, §12.1); see section 6 |
| SonarCloud, Dependency-Check | – | ✓ | ✓ | ✓ | `main.yml`; skipped with a notice while `SONAR_TOKEN` and `NVD_API_KEY` are absent (#30) |
| CycloneDX SBOM generated | – | ✓ | ✓ | ✓ | `main.yml` `sbom`, scanned by `trivy-sbom` |
| Container build, Trivy, Dockle, kube-linter | – | ✓ | ✓ | ✓ | `main.yml` `image` |
| ZAP authenticated full + API scan | – | – | placeholder | – | `nightly.yml` `zap` prints a notice until WP-28 |
| k6 at the 50k/500k seed | – | – | 50k, in process | – | `nightly.yml` `perf` runs `PerfCasesListIT` at 50k (not k6); the k6 smoke runs by hand; 500k and the release run arrive with WP-30 |
| OpenSSF Scorecard | – | push and weekly | – | – | `scorecard.yml` |
| SBOM signed, cosign, SLSA, evidence pack | – | – | – | ✓ | `release.yml`; exercised once, at `v0.1.0` |
| veraPDF | – | – | – | – | v1.1 only (no PDF in v1) |

Branch rules: status checks required, linear history, no force-push, zero approving reviews (single maintainer).
Every PR gets an independent review pass on a different model from its author, recorded on the PR (Part B).

## 5. Decisions taken during implementation

All are recorded where they were made; this is the index.

| Decision | Where | Why |
|---|---|---|
| Trivy image gate runs with `--ignore-unfixed` | #11, #12 | Owner chose to fail only on fixable HIGH/CRITICAL findings; unfixed base-image findings stay in the reports and evidence pack |
| Semgrep excludes `python.django.security.django-no-csrf-token.django-no-csrf-token` | #19, `ci.yml` | False positive on Thymeleaf forms whose CSRF field Spring Security injects |
| `config/spotbugs/exclude.xml` carries `SPRING_ENDPOINT` for every controller (`DashboardController`, `SessionController`, `TeamAdminController`, `UserAdminController`, `CaseConfigController`, `CaseController`, `CaseEditController`, `CaseAssignmentController`, `CaseAttachmentController`, `CaseListController`, `SavedViewController`, `CaseExportController`, `CaseHistoryController`, `AlertController`, `UpdateRequestController`, `ClosureController`, `ClosureDecisionController`, `ClosureApproverController`, `CaseReopenController`, `ReportController`, `ReportRunController`, `ReportScheduleController`, `LegalHoldController`, `PreferencesController`, `SystemController`, `TestMailController`, `ImportRunController`), listed by class | #19, #20, #22, #49, #50, #59, #62, #63, #78, #84, #87, #96, #98, #104, #105, #106, #107, #116, #118, #121, #122, #126, #132, #139, #145, #175, #176, #186, #188 | The pattern reports no defect; each controller is reviewed and the approval recorded in the file; `spotbugs-annotations` is LGPL and off the license allow-list, so the file is the mechanism |
| `SourceQueryJdbcDao` runs non-constant SQL, excluded from FindSecBugs' two SQL-injection patterns by name | #162, `config/spotbugs/exclude.xml` | The importer runs the operator's own source queries from the reviewed mapping, so the SQL is not a constant by design; `run` accepts only a `ValidatedSourceQuery` (exactly one `SELECT`, no locking, `INTO`, database link or function outside the vendor allow-list), values are bound, and the session is a read-only snapshot under a read-only account (`docs/importer/source-accounts.md`) |
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
| Every closure transition locks, then re-checks | #102 | `CaseStatusService.move` takes the case row lock (`refresh` with `PESSIMISTIC_WRITE`), then checks the status and evaluates the matrix again on what the lock holds: 409 if the case left the expected status, 403 if the matrix now denies the action. The closure request is re-read under the same lock and changed by a conditional update `WHERE DECISION = 'PENDING' AND VERSION = :v`, so two decisions racing each other yield exactly one on both vendors |
| Only the named approver decides; another Manager who should decide reassigns the request to themselves first | #102, #105 | The approver is chosen when closure is requested and named on the request; the decision screen and the service both refuse anyone else (403), and the case owner never decides (the matrix denies it). Reassign approver offers the rule's choices for the original requester minus the current approver, which can include the Manager reassigning |
| Approval may carry an optional reason; reject and request changes require one (1 to 1000 characters) | #102 | The service takes a reason for every decision. The screens ask for one only when sending a request back; Approve is one button. The reason is redacted in the audit trail and never mailed |
| A reopen's reason lives in the append-only `CASE_REOPEN` table, not only in the audit trail | #103, #109 | The audit copy is redacted by design (possible PHI), so `CASE_REOPEN` (who, when, reason, the cycle it started, the request whose approval closed the case) is the one readable record. The case page reads it through a scoped Criteria query and shows the latest reopen under the "Reopened" chip |
| A status move without a `CASE_SLA` row is an error | #103 | Every case gets its row when it is created (or imported as `NOT_APPLICABLE`), and closing finalises it and reopening restarts it, so a missing row means a broken invariant; `CaseStatusService.move` throws rather than invent one. Test seeds that insert cases by hand insert the row too |
| Closure actions sit on the pending banner or the case's action row; there is no "⋯ More" menu | #104, #105, #106, #107 | BUILD_PLAN §4.3 puts Reassign approver and Reopen under "⋯ More", but there is no menu component yet (Bootstrap CSS only, no Bootstrap JS). Each action is offered only where the matrix allows it, next to what it changes. A menu, if it comes, is Phase 6 polish |
| The dashboard's "Pending my approval" shows at most 100 requests, oldest first | #106 | Enough until WP-20 builds the rest of the dashboard with paging; it is read through `CaseScope` like every other list |
| Character counts are visual aids only | #104 | The count under a textarea ("37 of 5000 characters") is `aria-hidden`; `maxlength` and the field's hint carry the limit for assistive technology, so typing is not announced character by character. Announcing it after a pause, as GOV.UK does, needs more script than the line cap allowed |
| Attachment scanning is `clamd` over TLS by default, or `external` | ADR-0012, ADR-0013, #55, #57 | The scan is synchronous and nothing is stored on a scanner error; `external` stores files as `NOT_SCANNED`, never `CLEAN`. Deployment configuration, so no application account can turn scanning off |
| PR 10 (#23) exceeded 400 net production lines | #23 review | Accepted so the `V3_1__sessions` migration shipped with the code that uses it |
| The SLA evaluator's queries are JPQL in `casefile`, not a `*JdbcDao` | #111 | Everything it needs is expressible in JPA: the candidate query, a `PESSIMISTIC_WRITE` lock over an id list with no row limit (the form Oracle can lock) and bulk `UPDATE CaseSla … WHERE caseId IN :ids AND state = :from`. `CaseSla` is `casefile`'s entity, so the queries sit behind `CaseSlaService` and `sla` drives them |
| `ALERT_LEDGER.PERIOD_KEY` has three shapes: `CYCLE` for the at-risk and overdue alerts, `<request id>:<yyyy-MM-dd>` for the two request reminders, `P<n>` for overdue repeats | #111, #114 | The unique key `(CASE_ID, ALERT_TYPE, SLA_CYCLE, PERIOD_KEY)` makes each alert once per its period. A case can have several open update requests, so a bare day would collide; repeat `n` is `floor(days overdue / OVERDUE_REMINDER_DAYS)` from 1 (0 is the breach itself) |
| A disabled owner's SLA alerts go to the team's enabled Managers | #112 | The schema makes an ownerless case impossible, so the plan's "no owner" case is read as a disabled owner; with an active owner, the Managers are told as well only of an overdue case whose policy escalates to `OWNER_AND_MANAGERS` (`SlaChunkWriter.recipients`) |
| Each job's health indicator reads its `JOB_RUN` row and stays outside the probe groups | #112, #114, #120 | Cluster truth: the replica that did not win the ShedLock lock must not be restarted for it. `slaEvaluator`, `remindersTick` and `reportDelivery` are UP within their grace, DOWN after it, UNKNOWN before the first run; `JobRuns` and `JobHealth` are public in `sla` so `report` reuses them |
| Readers and Analysts may run the reports, for what they read | #116, BUILD_PLAN §3.2 | §3.2's role table gives "Run reports / export" to every team role for its own team and to Compliance Admins for all; counts are taken under `CaseScope`, so restricted cases count only for those who may read them. Only someone with neither (a System Admin alone) gets 403. The matrix has no REPORT action; the check is on roles |
| Aging is grouped by team and owner, SLA compliance by team and case type | #116 | BUILD_PLAN §4.5 for aging ("grouped by team and owner"); compliance by team and case type, settled in #116's review (§4.5 says "by type/team"). Each total row carries the overall figures, the share within the SLA included |
| Report delivery keeps no ledger: the run, its mails and alerts and the schedule's new `NEXT_RUN_AT` commit in one transaction | #120 | The job runs under ShedLock and re-checks `ENABLED` and `NEXT_RUN_AT` inside the transaction, so a delivered schedule is no longer due and a crash leaves nothing behind; the schedule's `VERSION` turns a concurrent admin edit into a retried failure (and the admin's save into a 409), never a double send |
| A schedule's next run is the next weekday and hour after the run, not "plus a week" | #120 | Exactly a week when on time, but never a time already past after an outage, so there is no burst of catch-up mails. Schedules are hour-only (`RUN_HOUR`), since the tick is hourly |
| A stored run's HTML reaches a page only through `StoredReportSanitizer` into `SafeHtml` | #121 | `REPORT_RUN.HTML` is application output read back from a table, so it is treated as untrusted: an OWASP policy allows only the report's table elements, `a p div span`, `scope colspan class` with plain values, and `href` to `/cases/{number}`. Re-rendering from data was rejected: a run stores HTML and CSV, not the report's numbers, and should show what was sent. `shared.html` now has two `SafeHtml` producers |
| A run is read by role: an enabled Manager of its team or a Compliance Admin; anyone else, and any malformed or unknown id, gets 404 | #121 | Runs are not case-rooted, so access is decided from the per-request `UserPermissions`, which grants what a Manager's `CaseScope` would over the team's cases; 404 keeps run ids from being probed (ADR-0005 style). Each download is audited `REPORT_RUN_DOWNLOADED` |
| Test Keycloak containers are stopped between test classes | #115, c54f2b5 | Each `KeycloakDevRealm.start()` left its container (about 0.9 GB) running until the JVM exited; two dozen of them exhausted the 16 GB CI runner, which then died with "received a shutdown signal" at the same test every time. `start()` now stops the previous class's realm. One shared container per JVM is #117 |
| The retention cut-off is calendar years in UTC, and archiving is a conditional bulk update, not `CaseStatusService.move` | #125 | The rule is "years after closure", where hours of zone shift do not matter. The job has no actor and the matrix does not apply to it; the update moves the version on, so an open edit form gets 409, never a lost update. A reopen clears `CLOSED_AT`, which restarts the clock |
| "Archived" in the status filter is offered to a Manager of some team or a Compliance Admin; anyone else naming it gets 422 | #125 | The export-details rule; the list still reads through `CaseScope`. Filtering archived rows per team would need a team list in `CaseFilter` (used in URLs and saved views), so it was left out |
| The current hold lives on the case row; its readable history is the append-only `LEGAL_HOLD_EVENT`; only Compliance Admins see reasons | #126, #127 (`V8`) | The audit copy of a reason is redacted, so it cannot be the readable record (as for `CASE_REOPEN`). Everyone who reads the case sees the "Legal hold" chip, not why. Lifting a hold does not restart the retention clock; it pushes `PURGE_AFTER` to at least now plus `app.retention.purge-grace`, so a case is never purged the moment its hold ends |
| The purge deletes `CASE_REOPEN` **before** `CLOSURE_REQUEST`, and the mail naming a report run before the run | #128 | `FK_REOPEN_CLOSURE` and `FK_OUTBOX_REPORT_RUN`. The full order per chunk: pins, update requests, comments, attachment chunks, attachments, search text, reopens, closure requests, the case's mail, then the tombstone |
| `REPORT_RUN` is kept 400 days (`app.retention.report-runs`) | #128 | A run is a weekly snapshot with case numbers, titles and names; 400 days keeps last year's run for the same week. The live aging report answers "as of today". A deployment under a longer records schedule raises it |
| The purge's native SQL lives in `retention.PurgeJdbcDao`, outside Part B's three `*JdbcDao` modules | #128 | It deletes across modules under its own user, without Hibernate validation, from tables whose entities are `@Immutable`; one class mirrored statement for statement by the grants is what makes "the purge user can do exactly this" reviewable. The class says why JPA cannot express it |
| The purge keeps the case row, `CASE_SLA`, the audit trail, the alert ledger, the hold history and `USER_ALERT`; it deletes the case's mail; it writes one `CASE_PURGED` row of counts per tombstone and a `JOB_RUN` row | #128 | Every kept table references the case, which is never deleted. An alert carries only what the tombstone keeps; a mail body may carry more. The audit row has counts only, so the purge pod needs no HMAC key. The tombstone also keeps `OWNER_NAME_SNAPSHOT` (#142), the stand-in for an imported case's legacy owner. A purged case still opens, with "Content removed on (day) under the retention policy." |
| Four database users (owner, app, purge, import), and `app.db.schema` as every pooled connection's default schema | BUILD_PLAN §8 rule 7, #134, #178 | The import user joined in WP-26 (design §5). The run-time users own nothing, and every statement Hibernate does not write (the DAOs, ShedLock, Spring Session) used to resolve in the connecting user's own schema on Oracle; one `connectionInitSql` covers them all, and a later native statement cannot forget it. The schema must be a plain identifier, and a second `connection-init-sql` fails the start |
| The batch profiles (`purge`, `importer`) run once and exit, with no web server, scheduler or OIDC client and Flyway off | #128, #134 | `TrailkeepApplication.main` exits with the context's exit code, so a failed run fails the pod. Spring Modulith's "moments" were the only thing starting a scheduler outside the servlet-only configuration |
| The role-conflict list and the test-mail throttle are read from the audit trail | #140, #159 | Cluster truth without a new table: the latest `LOGIN` or `ROLE_CONFLICT` per user (closes #67 item 9), and at most one `TEST_MAIL_QUEUED` per person per 5 minutes. The role-conflict query at multi-year audit volume is #141 |
| No cases-per-SLA-state gauge, no scan-queue metric, no "never ran" alert | WP-25 close-out | Not in BUILD_PLAN §16's list. Age gauges are `NaN` before a job's first success and the System screen shows "Has not run yet". Candidates for WP-30 |
| Shortcuts can be turned off, not remapped; `/` goes to the cases list's search box | #132 | Off is what WCAG 2.1.4 requires; a later preference column can add remapping. No layout has a search field, so `/` opens `/cases` and focuses `#tk-q` |
| Mail is rendered in English from `messages.properties`, one `th:text` message per line | #135 | No per-person locale is planned for v1. Keeping a `<span>` round each value needed `th:utext`, which the Semgrep gate forbids, so the HTML is simpler and the visible text identical (golden test) |
| WP-26's 31 design rows became 53 PRs; `extract` landed with the first source (26k) | #134 to #202 | Each PR stayed under 400 net lines; every split is stated in its PR |
| SQL Server source ITs run on `main`, nightly and release tags, not fork PRs (`-Psqlserver-it`) | #167 | The image is large, like Oracle's. Proposed as a Part G row, not written into `IMPLEMENTATION_PLAN.md` (owner decision, section 6) |
| The mapping hash is a plain, length-prefixed SHA-256 over the mapping file and each lookup file | #169 | Lookup files can hold legacy values, but the hash covers whole files, mapping first, so guessing a value means guessing every byte of both. Row and key hashes are keyed (HMAC under `import.hash-key`) because short legacy text would be dictionary-reversible. Flagged for the Phase 6 security review |
| `visibility.onUnrepresentableAcl: standard` keeps the mapped sensitivity (`STANDARD` when none) | #172, `CaseRules.visibility` | `restricted` (the default) restricts every case with a legacy access list; `fail` makes it an `ACL_UNREPRESENTABLE` error; `standard` leaves the case as mapped |
| The CSV dialect is declared in the mapping; no custom row terminator; a files mapping is all CSV/TSV or all JSON Lines | #168 | Rows end at CR, LF or CRLF, the only terminators Commons CSV parses, so the `bcp` fallback with a custom row terminator is unsupported (a custom field terminator is). A lookup file follows the same dialect and encoding (#169) |
| `import.allow-mapping-change` is also the `import.hash-key` rotation path | #184 | Without it, a delta against ledger rows loaded under another mapping is a FATAL `MAPPING_CHANGED_SINCE_LOAD`. With it, one `MAPPING_CHANGE_ALLOWED` WARNING tells the sign-off that no row hash is compared in this run, since a changed mapping or a rotated key makes every row look changed; status and closing time are still compared |
| The importer `Job` lives under `deploy/jobs/`, with the source password and the source egress in one `source-access` component | #194 | Applying a `Job` runs it, so it is not in `base/`. Two writable claims (content area, reports) instead of one run volume, because subPath folders are root-owned. Only `validate-mapping` and `extract` mount the source password |
| Report files go to `<import.reports-dir>/<run id>/`, owner-only, with counts, codes, keyed hashes and Trailkeep ids, never legacy keys | #193, #195 | They outlive the staging, for the approver. Reconcile and dry run share the report code and `shared.csv.CsvFormat` |
| An imported open case's alerts already raised are recorded as sent, so go-live mails nothing about the backlog | #196, ADR-0016 (Proposed) | Imported cases arrive in their state without a transition, but the reminders tick would repeat every overdue alert at once |
| A case on legal hold in the legacy system, or past its retention at the load, is created already on legal hold | #198 | "Past its retention" is closed at or before the load minus the type's `RETENTION_YEARS`, the archive job's boundary, inclusive; nothing imported is archived or purged unreviewed. The hold is set in the case's INSERT (the import user has no UPDATE of the hold columns), with its hold event and audit row; a Compliance Admin lifts it as usual |
| A signed-off run keeps `STATUS = DRY_RUN` with `APPROVED_BY` set; LOAD issues are always ERROR | #176, #179 | The screen reads an approved dry run from the sign-off columns, the app user's only UPDATE on the table. A FATAL load issue would trip the start gate of a resumed load |
| clamd's stream limit is declared (`import.attachments.clamd-stream-max`) and checked only by dry-run and load in clamd mode; the scanning mode in force must equal the dry run's | #180 | Status and staging purge never block on it. A run dry-run under `external` cannot be loaded under `clamd` or the other way round |
| Export recipes: `sqlcmd -y 0` without `-h -1`; a SQL*Plus line over 32 767 bytes fails closed (`ROW_UNREADABLE`) and goes through JDBC; Oracle BLOBs and SQL Server attachments are JDBC-only | #199, #201 | What the tools allow, each recipe run by a test. A value over 1 MB is exported whole by mssql-tools18, which settles design §4.2.2's open question |
| Each importer IT seeds its own synthetic legacy fixture | WP-26 | Design §11's single shared `legacy-schema/` folder was not built; not audited item by item against §11's list of awkward values |
| New runtime dependencies for the importer: `jackson-dataformat-yaml` and `json-schema-validator` (Apache-2.0, #146), JSqlParser (LGPL-2.1 OR Apache-2.0, Apache-2.0 elected, #155), `re2j` (BSD-3-Clause published as "Go License", #154), `commons-csv` (Apache-2.0, #168), `mssql-jdbc` (MIT, #167) | those PRs, `pom.xml` | The allow-list was not widened: the "Go License" name was added to the BSD-3-Clause `licenseMerge`, after checking its text. `re2j` gives linear-time matching, so a mapping's pattern cannot hang an extract on a crafted value |

## 6. Known gaps and follow-ups

| Issue | What |
|---|---|
| #30 | Phase 1 exit items: scanner secrets, real IdP login in DEV, shell ACR manual testing |
| #92 | Owner item: the manual assistive-technology pass on the Phase 2 screens (a Phase 2 exit criterion). Still open, and it now also covers the Alerts page and the Request update dialog with its combobox (WP-17, WP-18), the WP-19 screens (the Request closure, Reject or request changes, Reassign approver and Reopen dialogs and their fallback pages, the pending banner and the dashboard's "Pending my approval"), the WP-20 to WP-23 screens (the dashboard's tiles, My cases and paged lists, the Aging and SLA compliance reports and their print view, the scheduled report runs and the report schedules), and the Phase 5 screens (the legal hold dialog, its fallback page and the hold history; the purged case page; the System screen and "send me a test"; the Import runs list, run page, run request and sign-off; the History tab's imported rows; `/preferences` and the `/` and `N` shortcuts), which pass axe and a keyboard walk but have had no screen-reader pass |
| #24 | Spring Session license tracker (stay on 4.1.0) |
| #117 | One shared Keycloak container per test JVM (about 5.5 minutes off CI); deferred to Phase 6 |
| #141 | Phase 6 performance: measure the System screen's role-conflict query (latest `LOGIN` or `ROLE_CONFLICT` per user over the whole audit trail) at multi-year audit volume |

Follow-ups without an issue yet, each recorded in the PR or close-out that found it:

| Area | What | For |
|---|---|---|
| Performance | The dry run's `PeopleMatcher` looks people up one by one (#173); batch it before a 500k-person source | WP-30 |
| Performance | The cases-list index recommendations of `docs/perf/cases-list-50k.md` (`CASE_RECORD (OPENED_AT, ID)`, an expression index on `LOWER(TITLE)`) still wait for the 500k run | WP-30 |
| Performance | The importer's dry run, load and staging purge have not been measured at volume | WP-30 |
| Tests | Some tests that place times relative to "now" leave a window of seconds around midnight in which they can fail (#151 moved one, the at-risk SLA walk, off the day boundary); the others are not yet swept | Phase 6 |
| Security review | The plain SHA-256 mapping hash (section 5, #169) is flagged for the Phase 6 security review | WP-28 |
| CI | The Nu HTML validator that BUILD_PLAN §10.2 and §12.1 and Part G list beside axe is not wired into any job | WP-28 or WP-29 |
| Deploy | `deploy/` ships no migration `Job`: `deploy/README.md` "Runtime properties" says migrations run as a separate pre-deploy `Job` with the owner credential, and the web profile does not turn Flyway off. `trailkeep-config` has no `APP_DB_SCHEMA` entry and "What an overlay must set" does not name it, although the importer `Job`'s header says it comes from `trailkeep-config` and every run-time user on Oracle needs it (#178) | WP-30 (runbook) |
| Operations | After an import: lock `TRAILKEEP_IMPORT` again, and keep `trailkeep-import-hash-key` while a delta may still compare with the ledger (`deploy/README.md` "Importer" step 5) | the deploying team |
| Archive filter | "Include archived" lists archived cases from every team the person's scope reads, not per team (#125, deviation 3) | if wanted |
| Design text | `docs/design/importer.md` has drifted from what was built, and is not edited by builders (CLAUDE.md rule 11): §6.1's example `people` block lacks `fields` (display name, email), without which email matching and name snapshots have nothing (the worked example's mapping has them, #202); §4.2.2 gives `sqlcmd -y 0 -h -1`, which `sqlcmd` refuses (`-y 0` alone, #201), and leaves the 1 MB question open; §2.1 still names `deploy/jobs/importer-job.yaml` (it is `deploy/jobs/importer/importer-job.yaml` with its kustomizations, #194); §8.1 says the hold lives in `retention` (it is in `casefile`, #198); §9 says no CSV writer exists (`shared.csv.CsvFormat` is reused, #193); §11's shared legacy-schema folder was not built | a docs PR |
| Export recipes | `docs/importer/export-recipes.md` should say that the Oracle recipe's `TIMESTAMP_TO_SCN(SYSTIMESTAMP)` is approximate (Oracle maps timestamps to SCNs at a granularity of seconds), so the recorded SCN marks the snapshot only roughly | a docs PR |
| ADRs | ADR-0014, ADR-0015 and ADR-0016 are still "Proposed" | owner (item 11 below) |

Closed since 0.1.0: #26 (the OIDC session registry IT runs on the Oracle leg, #38), #27 (`ConstraintViolationException`
maps to 422, #36), #28 (back-channel `logoutUri` follows the bound port, #37), #29 (local seed names the unknown
policy and seeds teams Alpha and Beta, #40), #31 (disabling a user ends their sessions, #63), #66 (the htmx 403
redirect no longer doubles the servlet context path, #72), #69 (the Oracle leg on `lower()` of the `CLOB`, #70) and
#82 (`VIEW_RESTRICTED` was never written; now it is, #83). #67, the bug-hunt triage of 2026-11-05, was closed early by
GitHub's keyword parser when #73 merged; its last carried-forward items are now done: item 9's durable role-conflict
list is WP-25's (#140, and `UserSignInService.roleConflicts()` is gone), item 1's null description is what the purge
writes on purpose (#128, which deletes the search text rather than rewriting it), and item 11d's size display was
fixed by #81. The WP-24 notes of the Phase 4 refresh (the `CASE_REOPEN` and report-run purge order, a retention rule for
`REPORT_RUN`) are done in #128; the compliance report still counts `CLOSED` cases only, not `ARCHIVED` ones (#116).

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
   the warning. Nothing is required while the pin stays. Since #136, `dependabot.yml` ignores bnd annotation releases
   from 5 on (after #131, 7.4, was declined for this reason) and `tika-core` from 4 on (#130 was declined: 4.x breaks
   `MimeTypes.detect`, and the 3.x line is a deliberate pin), so neither is proposed again until the rule is removed.
7. **ClamAV signature updates in a cluster.** `deploy/base/clamav.yaml` runs clamd from the official image with no
   `freshclam` and no egress (ADR-0012), so signatures are those baked into the image tag (the file's header says the
   same). Decide how they stay current: roll the image tag on a fixed cadence (rebuild or re-pull with current
   databases); add a `freshclam` sidecar, with a writable volume and egress to the signature mirror in an overlay,
   which also relaxes the pod's no-egress `NetworkPolicy`; or point `freshclam` at a private mirror. Setting
   `app.attachments.scanning: external` (ADR-0013, `deploy/overlays/external-scanning`) removes the ClamAV pod and
   with it this decision, at the cost of files being stored `NOT_SCANNED`.
8. **Tag 0.2.0.** `main` carries Phases 2 to 5 since `v0.1.0`. A `v0.2.0` tag runs `release.yml` again: the image
   to GHCR with its signature, SBOM attestation and provenance, and a GitHub Release with the evidence pack (without
   the Dependency-Check report while #30's secrets are absent). `CHANGELOG.md`'s Unreleased block becomes 0.2.0.
9. **The named certified tester for WP-29.** The Section 508 conformance pass needs a Trusted Tester v5 (or the
   deploying organization's equivalent) for a 3-week window (Part F); the remediation and the ACR depend on it. #92's
   screen-reader pass is the same kind of work and can be folded into it.
10. **The SQL Server row in Part G.** `-Psqlserver-it` has run on `main`, nightly and release tags since #167, but
    `IMPLEMENTATION_PLAN.md` Part G has no row for it. Decide whether to add the row (the plans are changed only by
    their owner) or record that the importer's third source stays outside the plan's CI table.
11. **ADR-0014, ADR-0015 and ADR-0016** (the importer's sources and canonical model, the two import principals, the
    go-live alerts pre-marked) are "Proposed". They describe what is built; accept them, or say what should change.

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

1. **Phase 6, hardening and conformance** (3 weeks in Part F; the tester's window runs all three):
   - **WP-28, authenticated DAST and security E2E** (M): Playwright signs in to the dev realm and hands the session to
     ZAP for a full scan and an API scan against an ephemeral stack, nightly and on release tags (replacing the
     `nightly.yml` placeholder), and a security E2E suite: CSP, CSRF, 404-not-403 enumeration, no restricted leak,
     upload abuse, and forwarded-header spoofing from a private address that is not the router. Acceptance: 0 Medium+
     findings, Sonar gate A and Dependency-Check clean; the last two need owner item 2. The plain SHA-256 mapping hash
     (#169) is on its review list, and the Nu validator gap (section 6) sits beside it;
   - **WP-29, the Section 508 conformance pass and ACR** (L, plus the 3-week tester window): Trusted Tester v5 by the
     named certified tester (owner item 9), remediation, and the ACR (VPAT 2.5) with the manual test matrix in
     `accessibility/`. Acceptance: no "Does Not Support", and dated tickets for any "Partially Supports";
   - **WP-30, performance, restore drill, runbook, evidence pack and 1.0.0** (M): k6 at the 50k and 500k seeds, pool
     sizing, a restore drill, `docs/RUNBOOK.md`, the release workflow and evidence pack, `CHANGELOG`, a signed tag.
     It inherits the performance follow-ups of section 6 (#141, `PeopleMatcher`, the importer at volume, the cases-list
     indexes, the export's connection hold of section 5) and the deploy gaps (the migration `Job`, `APP_DB_SCHEMA`).
2. **Owner-only items** that gate Phase 6 or the release, none of them code: the scanner secrets for WP-28's
   acceptance (item 2, #30), the named certified tester for WP-29 (item 9), the screen-reader pass of #92, the
   `v0.2.0` tag (item 8), the Part G SQL Server row (item 10), the ADRs (item 11), and the open Phase 1 items of #30
   (real IdP sign-in, shell ACR manual testing). #24 (Spring Session pin) needs no action while the pin stays.
3. Carried forward: rows of the cases list open on click only (the case-number link is the keyboard path, #84), and
   #117 (one shared Keycloak container per test JVM, about 5.5 minutes off CI) is Phase 6 housekeeping.

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
