# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Closure decision screens (WP-19 part c2): the named approver sees "Approve closure" (one button) and "Reject or
  request changes" (a native dialog; fallback page `/cases/{number}/closure/{id}/decision` without JavaScript) on a
  pending case's banner. The dialog has a required Reject / Request changes choice and a required reason of up to 1000
  characters with a visible count; a refused form is 422 with each field marked and summarized, a decision that lost
  a race is 409 with Reload, and anyone but the approver is 403. An approved case shows its closure (approver, time,
  outcome, summary and follow-ups), locked. A case sent back shows a "Requires Changes" badge and the reason until
  the next comment.
- Request closure screen (WP-19 part c1): "Request closure" on an open case's page, offered when the matrix allows
  it, opens a native dialog (fallback page `/cases/{number}/request-closure` without JavaScript) with the case type's
  outcomes, the resolution summary and optional follow-up actions (5000 characters each, with a visible count) and the
  approver as the same APG combobox as Request update, listing only the approvers the separation-of-duties rule
  allows. A refused form is 422 with each wrong field marked and summarized; a case that moved on is 409 with Reload.
  A pending case shows a "Pending closure" banner (requester, time, approver, outcome, summary and follow-ups) and no
  longer offers Request closure, Edit or Reassign.
- Reopen (WP-19 part b2, no screen yet): `CASE_REOPEN` (`V5_1__case_reopen.sql`, `CaseReopen` entity, append-only;
  the runtime user may SELECT and INSERT it only) and `ClosureDecisionService.reopen`. A Manager or Compliance Admin
  reopens a closed case with a reason of 1 to 1000 characters: the case is `OPEN` again with `CLOSED_AT` cleared, the
  SLA starts its next cycle (`SLA_CYCLE` + 1, `ON_TRACK`, the clock from the reopen instant), and one `CASE_REOPEN`
  row keeps who, when, why, the new cycle and the request whose approval closed the case. Audited as
  `CASE_REOPENED` with the reason redacted; the owner and that request's requester get `case-reopened` (once each,
  never the actor), never the reason. The case can then be requested for closure again.
- Closure decisions and approver reassignment (WP-19 part b, no screen yet): `ClosureDecisionService`. The
  request's approver approves (the case is `CLOSED`, `CLOSED_AT` stamped, the SLA finalised as `MET` or `BREACHED`),
  rejects or requests changes (a reason of 1 to 1000 characters; the case is `OPEN` again); a Manager or Compliance
  Admin reassigns a pending request's approver by the request-time rule. Each runs under the case's row lock, which
  re-reads the case and re-evaluates the permission matrix, then re-checks the request (still pending, the actor its
  approver and not the owner) and writes it with one versioned conditional update, so a concurrent approve and reject
  yield exactly one decision. Audited as `CLOSURE_APPROVED`, `CLOSURE_REJECTED`, `CLOSURE_CHANGES_REQUESTED` and
  `APPROVER_REASSIGNED` (reasons redacted); the requester and the owner get `closure-decided` (once each, never the
  approver) and a new approver `closure-requested`, never a reason or a summary.
- Request Closure, schema and request (WP-19 part a, no screen yet): `CLOSURE_REQUEST` (`V5__closure.sql`,
  `ClosureRequest` entity; decisions `PENDING`/`APPROVED`/`REJECTED`/`CHANGES_REQUESTED`, a check that the requester
  is never the approver; the runtime user may SELECT, INSERT and UPDATE it, never DELETE) and the `closure` module's
  `ClosureService`. A person who may `REQUEST_CLOSURE` an open case picks one of the case type's outcomes (kept as a
  text snapshot), writes a resolution summary (1 to 5000 characters) and optional follow-up actions, and names an
  approver from the team's enabled Managers minus the requester and the owner, or, when that leaves nobody,
  the Compliance Admins (IMPLEMENTATION_PLAN A.2 #18). The case moves to `PENDING_CLOSURE` under its row lock
  (`CaseStatusService`), so of two racing requests one wins and the other is refused; `CLOSURE_REQUESTED` is audited
  with summary and follow-ups redacted, and the approver gets the `closure-requested` mail and alert (number, title,
  requester, outcome and a link, never the summary). A pending case cannot be requested again or reassigned.
- Request Update screens (WP-18 part b): "Request update" on the case page opens a native dialog whose recipient is an
  ARIA APG "combobox with list autocomplete" (`app.js`, over a native `<select>` that is the control without
  JavaScript and on the fallback page `/cases/{number}/request-update`) listing only the enabled team members and
  Compliance Admins who can read the case, never the actor; 422 and 409 come back on the form page with an error
  summary. The case page lists the open requests ("Update requested from X on date by Y, awaiting an answer") with
  "Mark answered" for the requester and for Managers and Compliance Admins. The dashboard's "Needs attention" lists the
  requests addressed to the person, each linking to the case's comments.
- Request Update, model and flows (WP-18 part a, no screen yet): `UPDATE_REQUEST` (`V4_10__update_request.sql`,
  `UpdateRequest` entity; the runtime user may SELECT, INSERT and UPDATE it, never DELETE) and the `updaterequest`
  module's `UpdateRequestService`. A person who may `REQUEST_UPDATE` asks one enabled person who can read the case
  (422 otherwise, also for themselves or a message outside 1 to 2000 characters); one open request per case and
  recipient (409, held by a unique `OPEN_KEY` on both vendors). The request is audited as `UPDATE_REQUESTED` with the
  message redacted and reaches the recipient as the `update-requested` mail and alert (number, title, requester and a
  link, never the message). The recipient's comment on the case answers their open requests in the posting
  transaction (`casefile` publishes `CommentPosted`, consumed `BEFORE_COMMIT`); "Mark answered" is the requester's,
  or needs the new matrix action `ANSWER_UPDATE` (Managers and Compliance Admins); both are audited as
  `UPDATE_ANSWERED`, once. Open requests are listed for a case and for the person asked.
- In-app alerts (WP-17 part c): `USER_ALERT` (`V4_9__user_alert.sql`, `UserAlert` entity; the runtime user may
  SELECT, INSERT, UPDATE and DELETE it). `NotificationService.notifyUser` writes the person's alert (one line from the
  mail's `<id>.alert.txt`, with a link inside the application) in the same transaction as their mail; a person without
  an address still gets the alert, a disabled one gets neither. The header gains "Alerts" with an unread badge (the
  link is named "Alerts, 3 unread"); `/alerts` lists the person's alerts newest first, 50 to a page with plain links,
  and reading it marks nothing read. "Mark read" and "Delete" are CSRF-protected POSTs on the person's own alerts;
  another person's alert is 404. Alerts never carry a description or subject text.
- Outbox dispatcher and the first mail (WP-17 part b): `OutboxDispatcher` runs every minute on every web replica
  (no ShedLock: the claim's `SKIP LOCKED` and lease give each row to one replica), reaps lapsed claims, then sends due
  rows 50 at a time over one SMTP connection as multipart text + HTML with no transaction open. A sent row is `SENT`;
  a failed one retries after 1, 2, 4 … minutes (capped at four hours, spread by row) and is `FAILED` with its error at
  the tenth failure; a pass stops after a batch that sent nothing. Delivery is at-least-once. New settings:
  `app.base-url` (required; the start of every deep link), `app.mail.from` (required while sending),
  `app.mail.enabled` (`false` keeps queuing without sending), `app.mail.lease`, and `spring.mail.*` for the relay
  (STARTTLS required in `prod`). A comment by someone other than the case's owner queues the `comment-added` mail to
  the owner in the posting transaction (case number, title, commenter and a link; never the comment, description or
  subject). Metrics `trailkeep.outbox.pending` and `trailkeep.outbox.failed` (count queries, cached a minute) and
  `trailkeep.outbox.attempts{outcome}`; the mail server is not a health indicator. Mailpit joins `compose.yaml`
  (SMTP 1025, UI 8025) and the `local` profile sends to it. New dependency `spring-boot-starter-mail` (Apache-2.0).
- Notification outbox, schema and claim (WP-17 part a): `NOTIFICATION_OUTBOX` (`V4_8__notification_outbox.sql`,
  `NotificationOutbox` entity, status `PENDING`/`SENDING`/`SENT`/`FAILED`, index `(STATUS, NEXT_ATTEMPT_AT)`; the
  runtime user may SELECT, INSERT and UPDATE it, never DELETE, and the purge user may trim it).
  `NotificationService.enqueue(recipient, templateId, model, caseId)` renders a mail from its templates under
  `templates/notification/` and writes the row in the caller's transaction (`MANDATORY`), so a change and its mail
  commit together. `OutboxJdbcDao` holds the state machine: the two-step native claim (up to 50 candidate ids without a
  lock, then `FOR UPDATE SKIP LOCKED` on that `IN` list, marked `SENDING` with a lease, committed before any I/O), the
  release (sent, retry at a later time, or failed) and the reaper for lapsed leases. Proven on PostgreSQL and Oracle by
  two concurrent claimers that never share a row. No dispatcher or SMTP yet (part b).
- Cases list performance at 50 000 cases (WP-15 part 5): a deterministic synthetic seed
  (`perf/seed-cases-postgresql.sql`), `PerfCasesListIT` (tagged `perf`, run by the new `perf-it` profile's `perf-50k`
  execution and by the nightly `perf` job, which replaces the k6 placeholder) that measures the list, the sorts, a
  deep page, the search, the export and the saved-view query in-process and fails if `GET /cases` reaches a p95 of
  500 ms or the search 1 s, and a k6 smoke (`perf/k6/cases-smoke.js`) run by hand against a running stack. Measured on
  PostgreSQL 16: `GET /cases` p95 132 ms, search p95 109 ms, an export of 9 645 rows about 0.5 s
  (`docs/perf/cases-list-50k.md`, with index recommendations that are not applied).
- History page (WP-16 part 2): `GET /cases/{number}/history`, linked as "History" from the case page beside Comments
  and Attachments, lists the case's audit events newest first, 50 to a page, with who, when (in `app.timezone`, in a
  `time` element), origin (screen, scheduled job, import) and, for field changes, old to new; owners and teams are
  shown by name. A field that is possible PHI (subject, description, comment text, file name, release reason) shows
  only "(set)" or "(changed)", never its value, its HMAC or its length. A native select narrows the list to field
  changes, comments, attachments, state changes or restricted views; paging uses plain previous and next links; an
  unknown kind or page is 422 and an unreadable case is 404. Opening the history of a restricted case is itself a
  read and records `VIEW_RESTRICTED`, through the same `RestrictedReadAudit` as the case page and downloads.
- The case timeline can be paged and filtered, and a principal can read it (WP-16 part 2a, no screen yet).
  `AuditTrailService.pageForCase` returns one page of a case's events, newest first, optionally only some actions,
  with the total, which `forCase` (a limit and nothing else) cannot do. A principal's scope now reaches the events of
  a case it may read, by a subquery on the case entity with the scope's own predicate, so an unreadable or absent case
  gives an empty page with a total of zero; events with no case, and the recent-events list, stay invisible to
  principals. `CaseRootedInvisibilityIT` covers the new methods. No migration.
- CSV export of the cases list (WP-15 part 4): "Export CSV" under the results downloads
  `GET /cases/export.csv?<the list's query>`, every case the person reads that matches the filter, in the list's
  order, not just the page. It streams in pages of 500 inside one read-only transaction, with the list's own
  `WHERE`, so restricted and other-team cases are absent exactly as from the list. The file is
  `cases-<yyyyMMdd-HHmm>.csv`, UTF-8 with a byte order mark and CRLF line ends: the list's columns plus subject and
  description. Every cell is quoted, and a cell starting with `=`, `+`, `-`, `@`, a tab or a carriage return gets a
  leading apostrophe (hand-rolled `CsvFormat`, no library). Subject and description read `[redacted]` unless a
  Manager of the case's team or a Compliance Admin ticks "Include subject and description"; nobody else sees the
  box, and a forced `details=true` is silently ignored. A filter matching more than `app.export.max-rows` (default
  10000) is 422 with the reason next to the control. Every export is audited (`CASES_EXPORTED` with row count, query
  and `details`). Rows ticked on the page (a labelled box per row, and "select all on this page") export alone through
  the repeatable `ids` parameter, at most 100 and still through the scope; a click on a row opens its case, and the
  case-number link stays the keyboard path.
- Saved views (WP-15 part 3): "Save view" on a filtered or sorted cases list opens a native `<dialog>` asking for a
  name (`save=1` renders it open without JavaScript) and posts it with CSRF to `/cases/views`. The view stores the
  canonical query string of the filter and order on page 1 in the new `SAVED_VIEW` table (`V4_7__saved_view.sql`,
  `SavedView` entity; the runtime user may SELECT, INSERT and DELETE it). A blank, over-long (100 characters) or
  duplicate name, or the plain list, is 422 with the dialog open and the reason. Views are private to the person who
  saved them: the first 20 by name are listed under "Cases" in the navigation (a native disclosure) and apply as a
  plain link through the person's scope, and `/cases/views` lists them with a Delete button each (another person's
  view is 404). Saving and deleting are audited (`SAVED_VIEW_CREATED`, `SAVED_VIEW_DELETED`), by name only.
- Cases list filter drawer (WP-15 part 2): every filter now sits in a native `<dialog>` opened from a "Filters" button
  (focus on its first control, Escape closes and returns focus, Apply swaps the results and focuses the count), with
  the search box still on the page. Status becomes multi-choice, and SLA state (several), priority, sensitivity (only
  for people who read other people's restricted cases) and an inclusive "identified from/to" date range join the
  filters, kept in the URL (`sla`, `priority`, `sensitivity`, `from`, `to`) and refused with 422 for any value no
  form offers or a range that ends before it starts. Active filters show above the results as chips, each a link to
  the same list without that criterion ("Remove filter: Status Open"). Without JavaScript, "Filters" links to the
  list with `filters=1`, which renders the dialog open in the page. The search hint now says "up to ten words".
- Cases list filters and search (WP-15 part 1b): status (archived only when asked for), case type, team, owner or
  "Me", and a search box, kept in the URL (`q`, `status`, `type`, `team`, `owner`) and refused with 422 for any value
  no form offers. Search goes through `SearchGateway`, whose `LIKE` adapter matches every word in the title,
  description or comments (never the subject), or the case number, as a predicate inside the scoped statement, so
  counts stay exact and unreadable cases never match. Team and owner choices come only from cases the person reads.
  The form works without JavaScript; with htmx it swaps the results, moves focus to the count and announces it.
- Cases list (WP-15 part 1a): `GET /cases` (and "Cases" in the main navigation) lists the cases the person may read,
  archived ones aside, 25 a page, from projections with no entity loaded, in four statements per page. Sort by case
  number, title, priority, SLA due date or opening date from an allow-list; any other column, and any other value no
  page offers, is 422. Unreadable cases are absent from rows and counts exactly as if they did not exist. The table
  has a caption, scoped headers and `aria-sort`; sorting and paging swap the results with htmx, keep focus on the
  control used and announce the count, and work as plain links without JavaScript. `UserDirectoryService` gains batch
  `displayNames` and `teams` lookups.
- Attachment download and quarantine Release (WP-14 part 4): `GET /cases/{number}/attachments/{id}` answers 404 for
  an unreadable case or an id not of that case, 403 for a quarantined file, and 503 with `Retry-After` when all
  `app.attachments.max-concurrent-downloads` (default 8) are streaming; otherwise it streams the file one 1 MiB chunk
  per query in its own read-only transaction, as `attachment` with an RFC 6266/5987 file name, `nosniff`, the
  detected type, `Content-Length` and `Cache-Control: private, no-store`. File names are download links only for
  downloadable files. A Compliance Admin releases a quarantined file with a reason (1 to 1000 characters) from a
  native dialog or the no-JavaScript page; it is audited as `ATTACHMENT_RELEASED` with the reason redacted, and a
  second concurrent release is a no-op. A file over the container's multipart limit now gets the case page with the
  same 422 "too large" summary, after the same 404 and 403 checks. Eight concurrent 25 MB downloads succeed with
  `-Xmx256m` (`ConcurrentDownloadHeapIT`, Failsafe execution `heap-256m`, every build).
- Attachment upload and the Attachments section of the case page (WP-14 part 3): `POST /cases/{number}/attachments`
  answers 404 for an unreadable case, then 403 unless the matrix allows `COMMENT` ("add comment / attachment"),
  before the file is read; a refused file is 422 with its reason and an unavailable scanner is 503 with "try again",
  storing nothing. The scan runs with no transaction open; the row, its chunks and `ATTACHMENT_ADDED` (file name
  redacted, scan mode recorded) are then written in one transaction. Files are listed with size, uploader, time and
  a Verified, Quarantined or "Not scanned by Trailkeep (scanned upstream)" badge; the form states the allowed types
  and maximum size. Downloads and Release follow in part 4.
- Scanning mode (ADR-0013): `app.attachments.scanning` (`APP_ATTACHMENTS_SCANNING`) is `clamd` by default, or
  `external` for organizations that scan upstream or at endpoints. It is deployment configuration read at startup,
  so no account in the application can turn scanning off. In `external` mode no clamd host, SSL bundle or ClamAV pod
  is needed (`deploy/overlays/external-scanning`), every other upload check still runs, and files are stored with
  the new status `NOT_SCANNED` (`V4_4__attachment_not_scanned` widens `CK_ATTACHMENT_SCAN`), never as clean, and
  stay downloadable; the startup log warns. An unknown value fails startup.
- Upload validation and virus scan pipeline (WP-14 part 2, no endpoint yet): an upload is copied to a temp file in
  `app.attachments.temp-dir` (the `emptyDir` in deployments) while its SHA-256 is computed and the 25 MB cap
  (`app.attachments.max-bytes`, which `spring.servlet.multipart.max-file-size` follows) stops the copy. Its type is
  detected from the bytes with `tika-core` against the allow-list and cross-checked against the extension, so a
  disguised executable is refused (422), and PNG/JPEG dimensions are capped from the header without decoding pixels.
  Then an in-house clamd `INSTREAM` client scans it synchronously over TLS (ADR-0012: server-authenticated, TLS 1.2+,
  hostname verified, trust from the `clamd` SSL bundle): `FOUND` gives a quarantined verdict with the signature, and
  an error, timeout, failed handshake or unexpected reply refuses the upload. The temp file is deleted on every
  failure path. ClamAV and a ghostunnel TLS sidecar join `compose.yaml` (with a generated dev-only CA) and
  `deploy/base/clamav.yaml`; `app.attachments.clamd.host` and the `clamd` SSL bundle are required.
- Attachment storage (WP-14 part 1): `V4_3__attachment` adds `CASE_ATTACHMENT` (detected content type, size,
  SHA-256, scan status `CLEAN | QUARANTINED | RELEASED` with the signature found, and the release's who, when and
  why, kept consistent by a CHECK) with the `CaseAttachment` entity, and `CASE_ATTACHMENT_CHUNK`, the content as rows
  of at most 1 MiB written and read one row per query by `AttachmentContentJdbcDao`, on PostgreSQL and Oracle. The
  runtime user may insert both, may update only the four release columns of an attachment (a column grant on both
  vendors), and may never change or delete content.
- Comment pins (WP-13 part 3): `V4_2__comment_pin` adds `CASE_COMMENT_PIN` with the `CaseCommentPin` entity and a
  scoped `@CaseRooted` repository; a composite foreign key ties each pin to a comment of its own case, and the runtime
  user may SELECT, INSERT and DELETE pins while `CASE_COMMENT` stays insert-only. A new `PIN` matrix action (whoever
  may comment) gates **Pin comment** / **Unpin comment** buttons on the case page, `POST
  /cases/{number}/comments/pin` and `/unpin` (404, 403, then 422 for an id naming no comment of the case), audited as
  `COMMENT_PINNED` / `COMMENT_UNPINNED`. Pinned comments are listed above all comments and marked "Pinned".
- Comments on the case page (WP-13 part 2): a Comments section lists them oldest first, rendered only through
  `tk:safe`, and `POST /cases/{number}/comments` posts one with the author's name as a snapshot (404, then 403 for
  roles without `COMMENT`, then 422 for a blank comment or one over 20 000 characters, the text kept). Each comment
  is audited as `COMMENT_ADDED` with the body redacted and appended to `CASE_SEARCH` in one atomic statement with a
  character-stream bind, so concurrent comments and comments over 4000 bytes on Oracle both land. An edit now
  rebuilds the search text from title, description and every comment under the row lock, so it no longer drops
  comment text from search. Pins follow in part 3.
- Comment foundations (WP-13 part 1): `V4_1__comment` adds the insert-only `CASE_COMMENT` table (author name snapshot,
  20 000-character body cap) with the `@Immutable` `CaseComment` entity and its scoped repository; the runtime user
  may only SELECT and INSERT it. `MarkdownRenderer` turns comment Markdown into `SafeHtml` through `commonmark-java`
  and the OWASP allow-list sanitizer, and the `tk:safe` template attribute is the only way to write it unescaped.
  Posting and showing comments follow in part 2.
- Reassign, Mark restricted and Mark standard on the case page (WP-12 part 2), each in a native dialog with a
  no-JavaScript page at `/cases/{number}/reassign`, `/restrict` and `/unrestrict`. The new owner must hold Analyst or
  Manager in the team, a team change goes only into a team the actor may open cases in, and Reassign is refused
  while a closure request is pending. Analyst-owners may restrict (and keep reading) but not unrestrict. A stale
  version answers 409 with Reload. `CASE_REASSIGNED`, `CASE_RESTRICTED` and `CASE_UNRESTRICTED` hold only what
  changed.
- Edit on the case page (WP-12 part 1): `/cases/{number}/edit` changes title, priority, subject and description of an
  open case, saved against the version the form was loaded with. A stale save answers 409 with "This case was updated
  by _name_ at _time_", the person's input kept and Reload offered (no Force Save). The title's PHI heuristic applies
  to a changed title. A `CASE_UPDATED` audit event holds the changed fields with subject and description redacted,
  and the search text is rebuilt under its row lock. Confirmations after a change are one-shot session notices.
- `deploy/OIDC_PROVIDER.md`: vendor-neutral checklist for registering Trailkeep with a real OIDC provider (client, redirect,
  post-logout and back-channel logout URLs, claims, `app.security.*` keys, secret handling, verification) (#30).
- `HANDOFF.md`: "Open owner items" (OpenSSF badge, scanner secrets, real IdP sign-in, manual accessibility testing,
  Spring Session pin) (#30).
- `local` profile seed for teams Alpha and Beta and the dev-realm users' team roles (`app.seed.teams`), created when
  missing and audited as `SYSTEM` (#29).
- Case schema (`V4`: `CASE_RECORD`, `CASE_SLA`, `CASE_SEARCH`, case sequences) and the principal-backed `CaseScope`,
  derived from the permission matrix, with a scoped case repository (WP-11 part 1).
- Case creation service and the case page (WP-11 part 2a): numbers drawn from `SEQ_CASE_NUMBER` with the
  configurable prefix `app.cases.number-prefix` (default `CASE`) and the opening year; the `CASE_SLA` row initialised
  from the case type's policy by the one anchor rule; the `CASE_SEARCH` row from title and description; a
  `CASE_CREATED` audit event with subject and description redacted. `/cases/{number}` shows the case header (status,
  SLA, sensitivity and priority chips) and overview in one scoped query; unreadable, absent and malformed numbers all
  answer the same 404.
- The one-page create form at `/cases/new` (WP-11 part 2b), linked from the dashboard: required fields marked in
  text, a focused error summary with per-field errors, `Ctrl+S` to save, the "numbers may have gaps" help, and a
  warning (confirm to save anyway) when the title looks like it holds an SSN, a medical record number or a date of
  birth. Saving lands on the case page with an announced confirmation. Golden path 1 runs end to end in the browser.
- `accessibility/ACR-shell.md`: first draft of the accessibility conformance report for the shell, team admin, case
  configuration and case screens (WCAG 2.2 A/AA), with a manual test script; no manual testing done yet (#30).

### Changed

- `perf/k6/cases-smoke.js` now requires `PASSWORD` from the environment, with no default, and stops at once with a
  message if it is unset (`BASE_URL` and `USERNAME` keep their defaults); `docs/perf/cases-list-50k.md` says how to run
  it against the local stack and points at `deploy/local/keycloak/README.md` for the dev realm's password.
- The `main` workflow's concurrency group now includes the triggering event, so a push to `main` (which cancels the
  run before it) no longer cancels a full run requested by hand with `gh workflow run main.yml`; runs called from
  `nightly.yml` and `release.yml` keep their own groups. `main.yml` already had `workflow_dispatch`; no gate changes.
- ClamAV pod (`deploy/base/clamav.yaml`, `compose.yaml`): clamd now binds `127.0.0.1:3310` only (new
  `deploy/base/clamd.conf`, shipped as a generated ConfigMap), the TLS sidecar forwards to `127.0.0.1:3310`, and
  clamd's startup, readiness and liveness probes are `clamdscan --ping` exec probes instead of TCP-socket probes. The
  compose service runs `clamd --foreground=true` directly, as the pod does, so signatures are the image's. The
  NetworkPolicy is unchanged (it only admits 3311).
- `ErrorAdvice` maps a `ConstraintViolationException` reaching the web layer to 422 (htmx `problem` fragment or the
  error page) instead of a 5xx (#27).
- Back-channel logout's loopback `logoutUri` is set from the port the server actually bound, so an instance started
  with `server.port=0` can end sessions too (#28).
- `JdbcOidcSessionRegistryIT` now has an Oracle variant (`OracleJdbcOidcSessionRegistryIT`, tag `oracle`) sharing
  its tests through an abstract base, so the registry's SQL runs on XE 21 and Free 23 in the `-Poracle-it` run (#26).
- The local seed fails with a message naming the case type and the SLA policy when the policy is unknown (#29).
- `.gitignore` excludes `.claude/settings.local.json`, which holds machine-specific Claude Code settings.
- CI installs Semgrep from a hash-pinned lockfile (`.github/requirements/semgrep.txt`, watched by Dependabot), and
  `SECURITY.md` links the private advisory form directly, to satisfy OpenSSF Scorecard Pinned-Dependencies and
  Security-Policy.

### Fixed

- `messages.properties` defined `tk.attachment.scan.NOT_SCANNED` twice (same text, so no screen was wrong); the
  duplicate is gone, and `MessagesPropertiesTest` fails the build on any repeated key, which `Properties` would
  otherwise resolve silently to the last one.
- Reading a restricted case is now audited (#82, the first half of WP-16). A full page view of a `RESTRICTED` case
  (`GET /cases/{number}`, not an htmx request, not a 422 re-render, nothing on 404) and each download of one of its
  attachments (nothing on 404, 403 or 503) write one `VIEW_RESTRICTED` event for the actor (entity `CASE`, origin
  `UI`; a download adds the attachment id), in the transaction of the read, as BUILD_PLAN §3.3 and
  IMPLEMENTATION_PLAN A.2 #16 require and no code did. `CaseService.open` is the page view, the only case-page read
  path that is not read-only; `recordDownload` is called once a download is allowed and holds a slot.
- The size beside an attachment picks its unit from the value as it is printed (#67, item 11): 1,048,525 to 1,048,575
  bytes read "1.0 MB" instead of "1024.0 KB", and the same rounding at the top of the MB range reads "1.0 GB" (a new
  `tk.attachment.gb` message) instead of "1024.0 MB". Bytes are still whole below 1 KiB.
- A closed dialog no longer pulls focus back to the control that opened it after the person has moved on. The
  `close` event arrives after the browser has already restored focus, so a quick Escape then Tab (or Enter) could
  land back on the opener. `app.js` now returns focus only when focus is lost or still inside the dialog. This was
  what made the cases-list filter keyboard walk (`CaseListAccessibilityIT`) flaky on `main`; that walk now also waits
  for htmx to settle before each keyboard step.
- Disabling a user now ends their sessions (#31). The System Admin screen `/admin/users` lists users and disables or
  enables them; `UserAdminService.disable` writes `DISABLED = TRUE` and then calls `endSessionsOf` (through the new
  `UserSessionEnder` port, which `security`'s `UserSessionService` extends) in the same transaction, and audits
  `USER_DISABLED` with the number of ended sessions (`USER_ENABLED` for the reverse). A System Admin cannot disable
  themselves. This closes the "no caller yet" note under 0.1.0 below.
- The application refuses to start when `spring.servlet.multipart.max-request-size` leaves less than 1 MiB above
  `app.attachments.max-bytes` (#67, item 6). `max-file-size` follows `max-bytes` but `max-request-size` is a separate
  setting (26MB), so raising `max-bytes` alone made the container refuse files the form still advertised as allowed.
  `AttachmentConfig` now fails startup with a message naming both properties and their values in bytes. The shipped
  defaults (25MB and 26MB) start unchanged.
- The schema now carries the upper bounds the admin screens state (#67, item 7). `V4_6__sla_check_ceilings` adds named
  CHECK constraints `CK_SLA_TARGET_MAX` (3650 target days), `CK_SLA_REMINDER_MAX` (365 reminder days) and
  `CK_CASE_TYPE_RETENTION_MAX` (100 retention years), equal to the limits in `SlaPolicyCommand` and
  `CaseTypeCommand`; `V3__admin` is unchanged. `SlaCheckCeilingsIT` proves a value at each ceiling is accepted and one
  above is refused by PostgreSQL.
- Case search works on Oracle again (#69). Hibernate refuses `lower()` on `CASE_SEARCH.SEARCH_TEXT`, which Oracle maps
  to `CLOB`, so `LikeSearchGateway` no longer applies a function to the column: `SEARCH_TEXT` is now stored lower-cased
  (`Locale.ROOT`) by every writer (`CaseSearch` on create and rebuild, `CaseSearchJdbcDao` on comment append) and
  matched with `LIKE` against the lower-cased words. Migration `V4_5__search_text_lowercase.sql` lower-cases rows
  written before. ADR-0006's amendment records the convention.
- The htmx 403 answer no longer doubles the servlet context path (#66). `HtmxAwareResponses.accessDenied` now sends
  `HX-Redirect` to the path of `HX-Current-URL` as it is (that path already includes the context path), and to
  `<context path>/` when the header is missing, malformed or names a page outside the context. Deployments at the
  root context path were not affected.
- The `local` seed (`LocalConfigSeed`) compares names stripped, as the service stores them (#67, item 4): a seed name
  with leading or trailing whitespace no longer fails the second start with "the name is taken", and a case type's
  `slaPolicy` reference is matched stripped too.
- Two small edge cases from the bug hunt (#67, item 11): a case description whose line breaks arrive as CRLF is
  checked against the 20 000 limit with each break counted once, as the form's `maxlength` and the comment service
  count it (`CaseFields` and `CaseChanges` normalise to LF), so a full description no longer fails with 422; and the
  audit actor-name snapshot is no longer cut between the two halves of a surrogate pair.
- ADR-0006 no longer says both drivers accept the character-stream bind; on Oracle the search append binds a CLOB.
- Oracle Free 23 `oracle-it` failures (ORA-12516) in `OracleDatabaseGrantsIT`: the grants probes share one connection per grantee and test contexts use a small Hikari pool.

### Security

- CI's Semgrep lockfile resolves the Dependabot alerts for `mcp` (1.30.0) and `opentelemetry-api` (1.45.2, with the rest
  of the opentelemetry family): every Semgrep release up to 1.184.0 pins `mcp==1.29.0` and `opentelemetry-api<1.38`, so
  `.github/requirements/semgrep-overrides.in` lifts those pins when the lockfile is compiled (the job installs with
  `--no-deps` and never runs `semgrep mcp`).

### Documentation

- `HANDOFF.md` refreshed to the end of Phase 2 (WP-15 parts 1a to 4 and WP-16 merged; the p95 measurement, WP-15
  part 5, in flight): progress table, what is on `main`, four new decisions (`VIEW_RESTRICTED` through one
  `RestrictedReadAudit`, the export audit's `query`, exports and the connection cap, History as a page), #82 closed,
  and the next steps (WP-15 part 5, then WP-17 to WP-19); `README.md` status block to match.
- `HANDOFF.md` refreshed to the state after WP-11 to WP-14 and WP-15 parts 1a, 1b, 2 and 3 (progress table, what is on
  `main`, schema, decisions, known gaps, what comes next); `README.md` status and `CONTRIBUTING.md` compose line no
  longer say that no case can be created or that ClamAV is still to come.
- ADR-0006 amended (2026-11-05): the `LIKE` adapter contributes a predicate to the scoped statement instead of
  returning candidate ids; IMPLEMENTATION_PLAN A.1 #7 marked *(amended r4)* and search removed from Part B's
  native-SQL uses.
- `HANDOFF.md` open owner items 6 and 7: the bnd annotations licence metadata (pinned at 4.3.1) and how ClamAV
  signatures are kept current in a cluster.
- Importer design (`docs/design/importer.md`) and ADR-0014 (Proposed): import compliance case history from SQL Server,
  Oracle or PostgreSQL, or from files exported from them, through a canonical staging model, a validated mapping file,
  a dry run with an authenticated in-app sign-off by any Compliance Admin, bound to the recorded scanning mode, an
  idempotent load through import-only domain APIs that never touches the source, and a reconciliation report.
  ADR-0014 records the owner's decisions of 2026-11-05: two non-login import principals, a separate
  `TRAILKEEP_IMPORT` login, go-live alerts pre-marked, cases past purge or on legacy legal hold imported and held,
  legacy change history in an insert-only `CASE_IMPORTED_EVENT` table on the History tab, default owners with a
  later `relink-owners`, and delta changes reported, never overwritten, with a closure worklist. It also records the
  owner-accepted risk that imported attachments are stored `NOT_SCANNED` in `external` scanning mode (ADR-0013),
  amends Part B to allow one importer source-query DAO and profile-filtered staging entities, and lists the new
  dependencies with their licences. WP-26 is planned as 31 PRs.

## [0.1.0] - 2026-11-04

The walking skeleton: IMPLEMENTATION_PLAN Phase 1 (Part D pull requests 1 to 12) plus WP-09 and WP-10. The platform,
its gates, identity, sessions, audit, the web shell and the administration screens are in place; case handling
starts with WP-11. `HANDOFF.md` records the state, the decisions taken and the open issues.

### Fixed

- The `DEFINED_PORT` integration tests (`KeycloakLoginIT`, `SharedSessionsIT`, the accessibility ITs) chose their
  application port from the kernel's ephemeral range, which Docker also publishes container ports into, so a
  Testcontainers start could take the port before the context bound it (`PortInUseException`). The port is now
  probed in 20000 to 29999.
- The `oracle-test` profile could not start a Spring context after PR 8: the OIDC client registration pointed at a
  provider defined only for `test`. Both Testcontainers profile groups now include `application-test-common.yml`,
  which holds every setting a test context needs to start.

### Changed

- `main.yml` runs `oracle-it` as one job without an image override (issue #13, owner decision: simplest option). The
  Maven profile's two Failsafe executions already fix `gvenzl/oracle-xe:21-slim` and `gvenzl/oracle-free:23-slim`, so
  the former two-leg matrix passing `-Dtrailkeep.oracle.image` ran both executions against one image per leg: four
  Oracle runs instead of two, with misleading per-execution report directories. One `oracle-it-reports` artifact,
  which `release.yml` now zips into the evidence pack by name; the job timeout is 90 minutes for the two sequential
  container starts.
- CI image scan policy (issue #11, owner decision): the Trivy gates on the built image in `main.yml` and
  `release.yml` run with `--ignore-unfixed`, so only HIGH or CRITICAL findings that have a fixed version fail the
  build. Unfixed base-image findings still appear in the JSON and SARIF reports and in the release evidence pack.

### Added

- Case configuration screens (WP-10, part 2 of 2): `/admin/sla-policies` and `/admin/case-types` on the shell
  layout (list, create, edit; outcomes one per line), Compliance Admins only, with out-of-range input answered 422
  and refusals rendered by `ErrorAdvice`; `CaseConfigAccessibilityIT` (axe, CSP) covers every screen and its 422
  state; a `local`-profile seed of synthetic SLA policies and case types from `app.seed` in `application-local.yml`,
  inserted when missing and audited as `SYSTEM`.
- Sessions across replicas (PR 10, WP-04b): Spring Session JDBC with the session tables and `OIDC_SESSION` in
  one portable migration `V3_1__sessions.sql` (Appendix A placeholders for the vendor types), the vendor
  `JdbcIndexedSessionRepositoryCustomizer`, JSON attributes through Spring Security's Jackson modules (allow-list
  extended by `TrailkeepOidcUser` and `java.net.URL` only), `SESSION` cookie (HttpOnly, Secure, SameSite=Lax, raw
  id). No authority is persisted: the stored principal and token carry none, and `SessionPermissionsFilter`
  rebuilds them per request from `UserDirectoryService`, ending the session of a disabled user and of any session
  past the 8-hour absolute lifetime (`app.security.absolute-session-lifetime`, measured from a login-time attribute);
  30-minute idle timeout (`server.servlet.session.timeout`, also feeding the page's expiry warning). RP-initiated
  logout (`HX-Redirect` for the htmx sign-out, so the CSP's `form-action 'self'` is not hit); back-channel logout
  through `JdbcOidcSessionRegistry` over `OIDC_SESSION` with `OidcBackChannelLogoutHandler` on cookie `SESSION`
  and the loopback `logoutUri`; a daily orphan cleanup that spares rows younger than one hour (ADR-0008 amendment);
  `UserSessionService.endSessionsOf(userId)`, which has no caller yet: the WP that adds disabling a user must call
  it, and until then `SessionPermissionsFilter`'s next-request check is what ends a disabled user's sessions.
  Grants: app user S/I/U/D on `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`, `OIDC_SESSION`. Spring Session is
  pinned to 4.1.0 (Apache-2.0) because 4.1.1 is published under a license outside the allow-list.
  `SharedSessionsIT`: two application instances on one database share a session; a Keycloak back-channel logout
  ends it on both; disabling or `endSessionsOf` signs the user out on both.
- Case configuration (WP-10, part 1 of 2): `V3__admin.sql` (`SLA_POLICY` with range CHECKs and anchor/escalation
  enums, `CASE_TYPE`, `CASE_TYPE_OUTCOME`, their sequences) with the `SlaPolicy`, `CaseType` and `CaseTypeOutcome`
  entities in `casefile`; `CaseConfigService` (Compliance Admins only) creates and updates SLA policies and case
  types from Jakarta-validated command records, replaces outcome lists as a whole, and audits every change with
  old/new snapshots; vendor grants and `DatabaseGrantsIT` cover the new tables.
- Team and team-role administration (WP-09, part 2 of 2): `TeamAdminService` (System Admins only; create,
  rename, deactivate teams; grant, change and revoke team roles; a System Admin, a disabled user or an inactive team
  never receives a role) with `TEAM_CREATED`, `TEAM_UPDATED`, `ROLE_GRANTED`, `ROLE_CHANGED` and `ROLE_REVOKED`
  audit events carrying old/new snapshots; server-rendered screens under `/admin/teams` (semantic tables and labelled
  forms, CSRF on every POST) whose refusals answer 403, 404, 409 or 422.
- Web shell, CSP and htmx contract (PR 9, WP-05): one Thymeleaf layout (landmarks, skip link first, polite live
  region, `lang`, session-expiry `<dialog>`), a dashboard behind login, error pages `error/404`, `4xx`, `5xx`
  without exception details, and UI strings in `messages.properties`. `CspNonceFilter` sets the exact BUILD_PLAN §9
  policy with a per-request nonce on every script tag; the `htmx-config` meta tag fixes `allowEval=false`,
  `allowScriptTags=false`, `selfRequestsOnly=true`, `includeIndicatorStyles=false`, `historyCacheSize=0`,
  `refreshOnHistoryMiss=true`. Vendored htmx 2.0.11 (0BSD) and Bootstrap 5.3.8 CSS only (MIT), content-hashed asset
  URLs cached for a year; `app.css` and `app.js` (htmx 200/403/404/409/422/5xx contract, focus management, live
  region, native `<dialog>` helper, CSRF header on htmx requests, session warning calling `POST /session/extend`).
  `shared.DomainException` (NOT_FOUND/FORBIDDEN/CONFLICT/INVALID, `CaseNotFoundException` now one) mapped by the
  single `ErrorAdvice` to the page or the htmx `problem` fragment; htmx requests get `HX-Redirect` from the
  authentication entry point (401) and access-denied handler (403). Tests: MockMvc for CSP/nonce, htmx config,
  landmarks, asset hashing, CSRF, `HX-Redirect`, the advice; Playwright 1.63 (Chromium) + axe-core 4.14 after a
  real Keycloak login: 0 WCAG 2.x A/AA violations on the dashboard, the open session dialog, 404 and 500 pages, no
  CSP violation, skip link first tab stop, htmx config applied. CI installs the Playwright browser.
- Case permission matrix (WP-09, part 1 of 2): `CasePermissionService` in `security` evaluates
  `permissions/matrix.csv`, a first-match decision table over effective role (System Admin and Compliance Admin
  win over team roles) × ownership × sensitivity × status × action, which fails start-up unless it ends in a
  catch-all DENY; `mayOwn` (Analyst or Manager in the case's team; never a System Admin) and `approverCandidates`
  (the team's enabled Managers minus requester and owner, else enabled Compliance Admins). `UserPermissions` and
  `UserDirectoryService` in `user` rebuild a user's authority from the database on every call (disabled or unknown
  users hold nothing). `CaseStatus` and `Sensitivity` in `shared`. `PermissionMatrixTest` checks every
  combination against an independent encoding of BUILD_PLAN §3.2 and that no CSV row is shadowed.
- OIDC login and roles (PR 8, WP-04a): `spring-boot-starter-security-oauth2-client`; one client registration
  (`idp`) whose id and secret come from `app.security.oidc.*` and whose provider is found by issuer discovery in
  `local`/`prod`; every application request requires a login, Spring Security defaults (CSRF, headers) stay on,
  and only the management server is open. `TrailkeepOidcUserService` maps the groups claim to global roles via
  `app.security.group-roles`, provisions through the new `user` API (`UserSignInService`: upsert by `sub`, last
  login, global-role sync, the System-Admin-vs-team-role conflict rule with a warning log line and
  `roleConflicts()`), and refuses a disabled account before any session is authenticated. `TrailkeepOidcUser`
  principal (name = `APP_USER.ID`). Keycloak 26 dev realm (two teams x Reader, Analyst, two Managers; Compliance
  Admin; System Admin; a user disabled in Trailkeep) in `compose.yaml`; `@WithAppUser` test support;
  `KeycloakLoginIT` proves the login round-trip and the disabled-user refusal against a real Keycloak.
- Audit module (PR 11, WP-06): `V2__audit.sql` (`AUDIT_EVENT` without foreign keys by decision, indexed on
  `CASE_ID`, `ACTOR_ID` and entity; `SEQ_AUDIT_EVENT`) with the immutable `AuditEvent` entity; `AuditRecorder`
  writing synchronously in the caller's transaction (`MANDATORY` propagation) with the application clock, the
  `requestId` from the MDC and the client address; a record-snapshot diff whose `@Redacted` components are stored
  only as HMAC-SHA-256 under `app.audit.hmac-key` plus a character count (required in `prod`, random per process
  elsewhere); `AuditTrailService` over a `@CaseRooted` repository that shows case-less events only to
  `CaseScope.system()`/`all()` and fails closed for any other scope until WP-16; `CaseScope.isUnrestricted()`.
- Identity schema with entities and vendor grants (PR 6, WP-03b): `V1__identity.sql` (`APP_USER`, `TEAM`,
  `APP_USER_TEAM` with its reverse index `IX_AUT_TEAM_USER`, `APP_USER_GLOBAL_ROLE`, pooled sequences
  `SEQ_APP_USER`/`SEQ_TEAM` `INCREMENT BY 50`) shipped with the package-private `AppUser`, `Team`, `AppUserTeam`
  and `AppUserGlobalRole` entities and `Repository`-based repositories in module `user`, and the public
  `TeamRole`/`GlobalRole` enums; vendor `R__grants.sql` for PostgreSQL (declarative revoke-and-grant in one
  transaction) and Oracle (grant the matrix, then revoke only what it no longer lists) with `${app_user}` /
  `${purge_user}` bound from the new required `app.db.app-user` / `app.db.purge-user`; `DatabaseGrantsIT` on both
  vendors logs in as each grantee (test-only `TestDatabaseUsers` Flyway callback, random passwords) and executes
  every table x privilege x user cell, the sequence draws and DDL; the schema harness now asserts the identity
  columns, the only nullable column and the reverse index; local compose creates the two grantee roles.
- Migration tooling and three-image validation harness (PR 5, WP-03a): Spring Data JPA, Flyway Community with the
  Oracle and PostgreSQL database modules, `ojdbc17` and the PostgreSQL driver; Flyway locations
  `db/migration/common` plus `db/migration/{vendor}`, the Appendix A placeholders per vendor profile
  (`oracle`, `postgresql`), Oracle `initSql` for `NLS_LENGTH_SEMANTICS=CHAR`, strict migration naming; Hibernate
  `validate`, `open-in-view=false`, BIT booleans, batching and fetch settings, and the dialect pinned through the
  `jakarta.persistence.database-*` properties; `app.db.vendor`/`app.db.schema` with a start-up guard that the
  configured vendor is the connected one; a UTC `InstantSource` bean; the migration-lint unit test with good and bad
  fixtures; Testcontainers harness (`AbstractVendorSchemaIT`: Flyway, `validate`, metadata diff of nullability and
  length) on PostgreSQL 16 in every build and on `gvenzl/oracle-xe:21-slim` and `gvenzl/oracle-free:23-slim` under
  `-Poracle-it`; Failsafe for `*IT`; `compose.yaml` with PostgreSQL 16 and an `oracle` profile.
  Test compilation runs with `-Xlint:-path` (the `ojdbc17` manifest references an `oraclepki.jar` that Maven Central
  does not ship; every other lint category and `-Werror` stay on). The PR 12 full-context tests under `ops` became
  Failsafe `*IT` classes on Testcontainers PostgreSQL, since a Spring context now needs a database.
- Modulith structure, `CaseScope` contract and architecture rules (PR 7, WP-07): Spring Modulith 2.1.2 with the
  fourteen application modules as `@NullMarked` packages whose `package-info.java` states each module's
  responsibility and permitted callers (`shared` is an open module); `ModularityTest` runs
  `ApplicationModules.verify()` and writes the module documentation to `target/spring-modulith-docs`. In
  `shared`: the `CaseNumber` value record (`PREFIX-YYYY-NNNNNN`, canonical parse and format) and
  `CaseNotFoundException`; in `shared.access`: the `CaseScope` interface (`toPredicate`, `canRead`,
  `assertReadable`, `system()`, `all()`) and the `@CaseRooted` and `@CaseAccessPath` annotations. ArchUnit rules,
  each with planted-violation tests: case-rooted repositories extend only `Repository`, expose only scoped methods
  and are injected only into access paths; no `@Lob`; `@Enumerated(EnumType.STRING)` on every enum column;
  package-private repositories; no entity in `web`; no field injection; `CaseScope.all()` only from `security`.
  Custom Semgrep rules with `semgrep --test` cases for `th:utext`, disabled Spring Security protections, inline
  htmx script and non-constant JPA query strings.
- Observability, container, deploy base and main/nightly/release CI (PR 12, WP-08 and the rest of WP-02):
  JSON logs (`logstash-logback-encoder`) in `prod`/`purge`/`importer` and text elsewhere, with a `requestId`
  MDC filter that reuses a safe incoming `X-Request-Id` or generates one and echoes it; Prometheus registry on
  the management port; explicit `liveness`/`readiness` probe groups that exclude cluster-level indicators by
  construction; `native` forward headers with `internal-proxies` and `trusted-proxies` both bound to
  `app.proxy.pattern`, proven by a spoof test and a trusted-proxy control test; validated `AppProperties`
  (`app.proxy.pattern` required, `app.timezone`, `app.branding.name`); `prod`, `purge` and `importer` profile
  files. Multi-stage UBI 9 `Containerfile` (layered, UID 185, exec-form entrypoint, OCI labels), a kustomize base
  under `deploy/` (hardened `Deployment`, `Service`, `Route`, `NetworkPolicy`, `ConfigMap`, secret references,
  `PodDisruptionBudget`, suspended purge `CronJob`) and an importer `Job` template. CI: `dco` and `actionlint`
  jobs in `ci.yml`; `main.yml` (SonarCloud, Dependency-Check, CycloneDX SBOM + Trivy, image build + Trivy +
  Dockle + kube-linter, Oracle matrix), `nightly.yml` (calls `main.yml`; k6 and ZAP placeholders) and
  `release.yml` (GHCR push, cosign keyless signature and SBOM attestation, SLSA provenance, GitHub Release with
  the evidence pack).
- Fork-safe CI (PR 3): `ci.yml` runs `./mvnw -B verify`, gitleaks and Semgrep (registry rulesets plus
  `security/semgrep/` rules and their `--test` cases once that directory exists) on every pull request and on
  `main`; `codeql.yml` runs CodeQL `security-extended` for Java (build mode `none`) on PRs, `main` and weekly;
  `scorecard.yml` runs OpenSSF Scorecard on `main` and weekly; `dependabot.yml` groups weekly Maven and
  GitHub Actions updates with a 7-day cooldown. Every third-party action is pinned by commit SHA, every job
  has least-privilege permissions and a timeout. Form-based issue templates send security reports to the
  Security tab. CI badge in the README.
- Project scaffold (PR 1): single-module Maven build on Spring Boot 4.1.2 and Java 21, Maven wrapper pinned to
  Maven 3.9.16 with a SHA-256 checksum, enforcer rules for the Java and Maven versions, reproducible-build
  timestamp, `TrailkeepApplication` with a `@NullMarked` root package, `application.yml` with Actuator on a
  separate management port (8081) exposing only `health`, `info` and `prometheus`, error details hidden,
  `.editorconfig`, `.gitattributes`, and a context-load plus management-health test.
- Static analysis gates (PR 2), all build-breaking in `./mvnw verify`: Spotless with palantir-java-format
  (`./mvnw spotless:apply` to fix); Checkstyle ruleset at `config/checkstyle/checkstyle.xml` (120 columns, no
  star/unused imports, braces always, method <= 60 lines, file <= 400 lines, cyclomatic complexity <= 10, Javadoc on
  public production types and methods); Error Prone with NullAway (`io.trailkeep` annotated, errors) and
  `-Xlint:all,-processing -Werror` in a forked javac; SpotBugs with FindSecBugs at max effort and low threshold, with
  an empty `config/spotbugs/exclude.xml`; JaCoCo 80 % line / 70 % branch overall and 90 % line per package for
  `sla`, `closure` and `security`; SPDX header check (`com.mycila:license-maven-plugin`); third-party license
  report `THIRD-PARTY.txt` gated by `security/licenses-allowed.txt`; CycloneDX SBOM at `target/bom.json`; enforcer
  rules for plugin versions, upper-bound dependencies, dependency convergence and banned dependencies (Log4j core,
  Log4j 1, Lombok, Commons Logging before 1.3).

[Unreleased]: https://github.com/techautomator414/trailkeep/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/techautomator414/trailkeep/releases/tag/v0.1.0
