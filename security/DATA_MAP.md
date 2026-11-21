<!-- SPDX-License-Identifier: Apache-2.0 -->
# Data map

Where Trailkeep keeps restricted and personal data, who can read it, how long it stays and whether it leaves the
system. BUILD_PLAN §9 ("Privacy") promises this page; [`security/THREAT_MODEL.md`](THREAT_MODEL.md) relies on it, and
[`security/TEST_MAP.md`](TEST_MAP.md) names the tests behind each control quoted here.

Facts come from the migrations in `src/main/resources/db/migration`, the grants in
`src/main/resources/db/migration/postgresql/R__grants.sql` (the Oracle file grants the same matrix), and the code
named in each section. Anything not checked against the code is marked **unverified**. Nothing here describes a real
deployment: the platform parts (backups, logs, disks) are the deploying organization's.

## How to read it

**Classes of data:**
- **Case content**: free text about a matter, which may hold PHI or PII (BUILD_PLAN §3.3). It inherits the case's
  sensitivity: a restricted case's comments, attachments and requests are restricted too.
- **Personal data**: about the people who use Trailkeep (names, email addresses, identity-provider ids, addresses
  they connect from).
- **Operational**: ids, states, dates and counts.

**Who can read, in the application.** Every case-rooted read goes through `CaseScope` (BUILD_PLAN §3.3, ADR-0005). A
case is readable by:
- standard case: anyone with a team role (Reader, Analyst, Manager) in the case's team, and every Compliance Admin;
- restricted case: its owner (an Analyst or Manager of the team), the team's Managers, and every Compliance Admin;
- never by a System Admin, who holds no team role (BUILD_PLAN §3.2).

Anyone else gets a 404. Below, "case readers" means exactly this set.

**Who can read, in the database.** Four users (`deploy/README.md`, "Migrations"):

| User | Workload | Reach |
|---|---|---|
| owner (`TRAILKEEP_OWNER`) | migration Job | owns every table; the only user with DDL |
| app (`TRAILKEEP_APP`) | web pods | the grants matrix: never `UPDATE` or `DELETE` on history tables, no staging |
| purge (`TRAILKEEP_PURGE`) | purge CronJob | `SELECT` and `DELETE` on case content, the tombstone `UPDATE` of four `CASE_RECORD` columns, `INSERT` into the audit trail |
| import (`TRAILKEEP_IMPORT`) | importer Job | staging (alone), `INSERT` into the tables a load writes, `SELECT` on every mapped table |

The cells below give each table's grants as S, I, U, D (select, insert, update, delete); "(U)" is an update of named
columns only.

**Retention, in short** (`deploy/README.md` step 6; `PurgeJdbcDao`; `ArchiveJob`):
1. A closed case is **archived** once its case type's `RETENTION_YEARS` (default 7, 0 to 100) have passed, unless it
   is on legal hold. `PURGE_AFTER` becomes now plus `app.retention.purge-grace` (default 30 days).
2. The **purge** CronJob (suspended until the deployer enables it) then **tombstones** each archived case past
   `PURGE_AFTER` and not on legal hold. It deletes comments, pins, update requests, closure requests, reopens,
   imported history, attachments and their content, the search text, and the case's mail. On the case row it clears
   `DESCRIPTION` and `SUBJECT_TEXT` and sets `PURGED_AT`.
3. **Kept after a purge:** the case row (number, type, team, owner, dates, title, legacy number, owner name snapshot),
   its SLA row, the audit trail, the alert ledger, the legal hold history and in-app alerts.
4. Report runs older than `app.retention.report-runs` (default 400 days) are deleted with the mail that names them.

Tested by `ArchiveJobIT`, `PurgeIT`, `LegalHoldIT` and `PurgedCasePageIT` (TEST_MAP "Privacy" and "Separation of
duties").

## Case content

| Where | What |
|---|---|
| `CASE_RECORD.TITLE` | up to 200 characters; **not sensitive by policy**, which the form states (BUILD_PLAN §3.3) |
| `CASE_RECORD.DESCRIPTION`, `CASE_RECORD.SUBJECT_TEXT` | the case's free text and who or what is involved: possible PHI |
| `CASE_RECORD.LEGACY_NUMBER`, `CASE_RECORD.OWNER_NAME_SNAPSHOT` | an imported case's old number, and its legacy owner's name when unmatched (V12) |
| `CASE_COMMENT.BODY` | Markdown as typed, insert-only (V4.1) |
| `UPDATE_REQUEST.MESSAGE` | what was asked of a person (V4.10) |
| `CLOSURE_REQUEST.RESOLUTION_SUMMARY`, `FOLLOW_UP_ACTIONS`, `REASON` | closure text and the decision's reason (V5) |
| `CASE_REOPEN.REASON` | why a case was reopened (V5.1) |
| `CASE_IMPORTED_EVENT.OLD_VALUE`, `NEW_VALUE` | legacy history: plain text only for status, owner, priority, team, due and title; every other field and every note is stored as HMAC-SHA-256 and length (`OLD_HMAC`, `NEW_HMAC`, `OLD_LENGTH`, `NEW_LENGTH`) (V16) |
| `CASE_RECORD.LEGAL_HOLD_REASON`, `LEGAL_HOLD_EVENT.REASON` | why a hold was placed or lifted (V4, V8) |

- **Read by, in the application:** case readers. Legal hold reasons: Compliance Admins only.
- **Read by, in the database:**
  - `CASE_RECORD`: app S I U; purge S (U); import S I (U: owner and change stamp, for relinking owners).
  - `CASE_COMMENT`: app S I; purge S D; import S I.
  - `UPDATE_REQUEST`, `CLOSURE_REQUEST`: app S I U; purge S D. Import: S on `UPDATE_REQUEST`, S I on `CLOSURE_REQUEST`.
  - `CASE_REOPEN`: app S I; purge S D; import S.
  - `CASE_IMPORTED_EVENT`: app S; purge S D; import S I.
  - `LEGAL_HOLD_EVENT`: app S I; import S I; purge none.
- **Retention:** until the purge. Then `DESCRIPTION` and `SUBJECT_TEXT` are cleared and the other tables above are
  deleted, except `LEGAL_HOLD_EVENT`, which is kept. The title, legacy number and owner name snapshot stay on the
  tombstone. A case on legal hold is never archived or purged; lifting the hold clears `LEGAL_HOLD_REASON` on the case
  row (`CaseRecord`).
- **Leaves the system:**
  - the title goes out in mail, alerts, reports and CSV exports;
  - description and subject go out only in a CSV export with details, which only a Manager of the case's team or a
    Compliance Admin gets;
  - comments, messages, closure text, reopen and hold reasons never leave except as pages to case readers;
  - none of it is logged (see [Logs](#logs)).

## Attachments

| Where | What |
|---|---|
| `CASE_ATTACHMENT_CHUNK.CHUNK` | the bytes, in chunks of at most 1 MiB, never changed (V4.3) |
| `CASE_ATTACHMENT.FILE_NAME` | the uploader's file name: possible PHI |
| `CASE_ATTACHMENT` other columns | detected `CONTENT_TYPE`, `SIZE_BYTES`, `SHA256`, `SCAN_STATUS` (`CLEAN`, `QUARANTINED`, `RELEASED`, `NOT_SCANNED`), `SCAN_DETAIL` (the signature name), `RELEASED_REASON`, `UPLOADER_NAME_SNAPSHOT` (an unmatched legacy uploader, V14) |
| the pod's `emptyDir` at `app.attachments.temp-dir` | an upload's temp copy while it is checked and scanned. It is named by the JVM, never by the uploader, and deleted on every failure path (`UploadIntake`) or when the stored upload is closed (`ScannedUpload`) |
| Tomcat's multipart location, also on `emptyDir` | the request's part while it is received; cleaned up by the servlet container (**unverified** by a Trailkeep test) |
| the ClamAV pod | the bytes are streamed over TLS with `INSTREAM`, with no file name; clamd's own log (`/var/log/clamav/clamd.log` in that pod) records detections |

- **Read by, in the application:** case readers may download a `CLEAN`, `RELEASED` or `NOT_SCANNED` file, Readers
  included (BUILD_PLAN §3.2). A `QUARANTINED` file is never served, and only a Compliance Admin releases it, with a
  reason. Each download of a restricted case's file writes `VIEW_RESTRICTED`.
- **Read by, in the database:** app S I, with (U) of the four release columns only; purge S D; import S I.
- **Retention:** until the purge, which deletes chunks and rows.
- **Leaves the system:** downloads, sent as `Content-Disposition: attachment` with `Cache-Control: private, no-store`
  (`CaseAttachmentDownloadIT`); to clamd for scanning. The file name is never logged for a restricted case, and in the
  audit trail it is redacted for every case.

## Restricted-case markers

| Where | What |
|---|---|
| `CASE_RECORD.SENSITIVITY` | `STANDARD` or `RESTRICTED`; comments, attachments and requests inherit it |
| `AUDIT_EVENT` rows with action `VIEW_RESTRICTED` | who read a restricted case, its history or a file, and when (`RestrictedReadAudit`) |

- **Read by:** case readers see the marker; for everyone else the case does not exist (404). DB: as `CASE_RECORD`
  and `AUDIT_EVENT`.
- **Retention:** the marker stays on the tombstone; the audit rows are kept indefinitely.
- **Leaves the system:** the "Sensitivity" column of the CSV export, which lists only cases the exporter reads.

## People

| Where | What |
|---|---|
| `APP_USER` | `EXTERNAL_ID` (the identity provider's subject), `DISPLAY_NAME`, `EMAIL`, `DISABLED`, `LAST_LOGIN_AT`, `CREATED_AT`. Two seeded, disabled import principals (ids -1001 and -1002, `.invalid` addresses) name no real person (V11, ADR-0015) |
| `APP_USER_TEAM`, `APP_USER_GLOBAL_ROLE` | team roles, and the global roles synced from provider groups |
| `USER_PREFERENCE`, `SAVED_VIEW` | a person's settings, and their saved list queries (`QUERY` can hold search text; see the threat model, RR-10) |
| name snapshots | `AUDIT_EVENT.ACTOR_NAME_SNAPSHOT`, `CASE_COMMENT.AUTHOR_NAME_SNAPSHOT`, `CASE_ATTACHMENT.UPLOADER_NAME_SNAPSHOT`, `CASE_RECORD.OWNER_NAME_SNAPSHOT`, `CASE_IMPORTED_EVENT.WHO_NAME_SNAPSHOT` |
| `NOTIFICATION_OUTBOX.RECIPIENT` | the address each mail goes to |
| `AUDIT_EVENT.CLIENT_IP` | the address a change came from, as resolved behind the trusted router |
| `OIDC_SESSION.PRINCIPAL`, session attributes | the provider's subject, and the ID token's claims (see [Sessions](#sessions)) |

- **Read by, in the application:**
  - display names are shown to signed-in people wherever a case, comment or report names someone;
  - no page template reads `EMAIL`: addresses are used to send mail and to match people at import;
  - System Admins manage users, teams and team roles;
  - `CLIENT_IP` is stored but shown on no page.
- **Read by, in the database:** `APP_USER`: app S I U; import S; purge none. `SAVED_VIEW`: app S I D.
- **Retention:** people are disabled, never deleted (no `DELETE` grant on `APP_USER`); name snapshots stay as long as
  the record they label, which for the audit trail is indefinitely. Saved views last until their person deletes them.
  There is no erasure path for one person's data (threat model, RR-16).
- **Leaves the system:** addresses go to the SMTP relay as recipients; display names appear in mail, reports and CSV
  exports (the owner column). Logs carry user ids, never addresses (`LoggingRulesIT`).

## Audit

| Where | What |
|---|---|
| `AUDIT_EVENT` | `AT_TS`, `ACTOR_ID`, `ACTOR_NAME_SNAPSHOT`, `ORIGIN` (`UI`, `SCHEDULER`, `IMPORT`, `SYSTEM`), `CASE_ID`, `ENTITY_TYPE`, `ENTITY_ID`, `ACTION`, `OLD_VALUES_JSON`, `NEW_VALUES_JSON`, `REQUEST_ID`, `CLIENT_IP` (V2). No foreign keys, so rows outlive the users and cases they describe |

- **Redaction:** old and new values come from snapshot records. A component marked `@Redacted` is stored as
  `{"hmac": HMAC-SHA-256 under app.audit.hmac-key, "length": n}`, never as text (`Redacted`, `SnapshotDiff`). The
  redacted components are:
  - a case's subject and description, created, edited or imported;
  - a comment's body, created or imported;
  - an update request's message;
  - a closure request's summary and follow-up actions, and an imported closure's summary;
  - a closure decision's reason and a reopen's reason;
  - a legal hold's reason;
  - an attachment's file name, uploaded or imported, and a release reason.

  Not redacted: titles, people's names, outcomes, statuses, saved-view names, import acknowledgements, and the list
  query of a `CASES_EXPORTED` event, which includes any search text (threat model, RR-10). Verified by
  `SnapshotDiffTest`, `AuditRecorderIT` and `AuditConfigTest` (TEST_MAP "Audit").
- **Purge rows:** a `CASE_PURGED` row carries only counts of what went.
- **Read by, in the application:** the case's History tab, for case readers (`AuditTrailService`). Reading a
  restricted case's history writes `VIEW_RESTRICTED`. Events without a case (sign-ins, admin changes, exports) are
  reachable only through an unrestricted scope. **No screen or export presents them at this commit:** the Compliance
  Admin audit export of BUILD_PLAN §9 was not found in the code (threat model, RR-8).
- **Read by, in the database:** app S I; purge I; import S I; nobody U or D (`PostgresDatabaseGrantsIT`,
  `OracleDatabaseGrantsIT`).
- **Retention:** indefinite; kept through the purge. Any trimming is a DBA decision outside the application.
- **Leaves the system:** not through the application.

## Sessions

| Where | What |
|---|---|
| browser cookie `SESSION` | the session id; `Secure; HttpOnly; SameSite=Lax` |
| `SPRING_SESSION` | `SESSION_ID` (a bearer secret), `PRINCIPAL_NAME` (the `APP_USER` id, never an email address), creation, last access and expiry (V3.1) |
| `SPRING_SESSION_ATTRIBUTES.ATTRIBUTE_BYTES` | JSON, never JDK serialization: the security context with the provider's ID token and its claims, which include the email address (sign-in refuses a token without one, `TrailkeepOidcUserServiceTest`), any user-info claims, the display name and login time; the CSRF token. No authorities |
| `OIDC_SESSION` | `SID`, `PRINCIPAL` (the provider's subject) and `AUTHORITIES_JSON`, which holds the CSRF header replayed on back-channel logout, not roles |

- **Read by:** the application only; app S I U D, no other database user.
- **Retention:** 30 minutes idle, 8 hours absolute (`SessionHardeningIT`, `SessionPermissionsFilterTest`). Spring
  Session removes expired rows on its own schedule (framework behaviour, **unverified** by a Trailkeep test). A daily
  job removes `OIDC_SESSION` rows whose session has gone (`JdbcOidcSessionRegistryIT`). Disabling a person deletes their
  sessions.
- **Leaves the system:** no. The browser keeps no case text: pages are `no-store` and htmx keeps no history
  snapshots (`SecurityDefaultsIT`, `WebShellIT`).

## Mail and in-app alerts

| Where | What |
|---|---|
| `NOTIFICATION_OUTBOX` | `RECIPIENT`, `TEMPLATE_ID`, `SUBJECT`, `BODY_TEXT`, `BODY_HTML`, `CASE_ID`, `REPORT_RUN_ID`, status, attempts, `LAST_ERROR` (the relay's reply, which can name the recipient) (V4.8) |
| `USER_ALERT.MESSAGE`, `LINK` | the one-line alert for the same event, and a path inside the application (V4.9) |

- **Content:** the templates (`templates/notification/`) read the case number, title, due date, people's names, an
  outcome, a team, a day count and a link. They never read a description, subject, comment, message or closure text
  (`MailTemplatesTest`, `ClosureRequestIT`, `NotificationServiceIT`).
- **Read by, in the application:** an alert by its person; outbox content by no screen (the System screen shows
  counts).
- **Read by, in the database:** `NOTIFICATION_OUTBOX`: app S I U; purge S D; import S. `USER_ALERT`: app S I U D;
  import S.
- **Retention:** outbox rows, sent or failed, stay until the purge removes their case or the report run they deliver
  expires; a row with neither stays indefinitely. Alerts stay until their person deletes them, also after a purge.
- **Leaves the system:** to the SMTP relay over required STARTTLS (`MailStartTlsTest`), then to recipients' mailboxes,
  outside Trailkeep's control.

## Exports

| Export | What it holds |
|---|---|
| Cases list CSV (`GET /cases/export.csv`) | case number, title, status, sensitivity, priority, type, team, owner, SLA state and due day, opened day. Subject and description are replaced by a redaction marker unless details were asked for and the exporter is a Manager of that case's team or a Compliance Admin. Only cases the exporter reads; at most `app.export.max-rows` (default 10000) |
| Report CSVs | the aging and SLA-compliance reports as on screen (see [Reports](#reports)) |
| Stored report run CSV (`/reports/runs/{id}.csv`) | the run as stored |

- **Read by:** the exporter, for what their scope reads (`CaseExportIT`, `CaseExportControllerIT`).
- **Retention:** nothing is stored: exports stream with `Cache-Control: no-store`. Each export is audited:
  `CASES_EXPORTED` with row count, query and the details flag; `REPORT_EXPORTED`; `REPORT_RUN_DOWNLOADED`.
- **Leaves the system:** yes, to the person's device, outside Trailkeep's control. Cells are guarded against formula
  injection (`CsvFormatTest`).

## Reports

| Where | What |
|---|---|
| on-screen reports | counts by team, owner, age band and SLA state; overdue cases (number, title, owner, due day) and unanswered update requests (number, title, who was asked), never a description, subject or message (`ReportService`) |
| `REPORT_RUN.HTML`, `REPORT_RUN.CSV` | a team's weekly aging report as a Manager of that team sees it, restricted cases' titles included (V7) |

- **Read by, in the application:** on-screen reports follow the reader's scope (Readers and Analysts see no
  restricted cases they cannot read). Stored runs: the team's Managers and every Compliance Admin. A run's HTML is
  re-sanitized when shown (`StoredReportSanitizerTest`).
- **Read by, in the database:** `REPORT_RUN`: app S I; purge S D; import S.
- **Retention:** runs are deleted after `app.retention.report-runs` (default 400 days), independent of case purges.
- **Leaves the system:** the `report-ready` mail carries a link, never the report; CSV downloads as above.

## Search side table

| Where | What |
|---|---|
| `CASE_SEARCH.SEARCH_TEXT` | lower-cased title, an imported case's legacy number, description and every comment body; **never the subject** (`CaseSearchWriter`, V4, V4.5) |

- **Read by:** searched with `LIKE`; the candidate ids are filtered by the searcher's `CaseScope` (ADR-0006). DB: app
  S I U; purge S D; import S I and (U) of `SEARCH_TEXT` and `UPDATED_AT`.
- **Retention:** deleted at purge.
- **Leaves the system:** no. It is a second copy of restricted text, so encryption at rest must cover it like the
  case tables (same schema).

## Logs

| Where | What |
|---|---|
| each pod's standard output | JSON lines in `prod` (`logback-spring.xml`), with the request id on every line |

- **Rules** (BUILD_PLAN §3.3, §9):
  - subject and description are never logged;
  - no text of a restricted case is logged (attachment names included);
  - people are logged by user id, never by email address;
  - the encoder escapes control characters.

  `LoggingRulesIT` drives the paths that log around a restricted case with marked text and addresses and checks none
  reaches the log; `JsonLoggingIT` covers escaping.
- **What the jobs and the importer log:** the purge logs counts only; the importer logs run ids, codes, counts and
  keyed hashes, never legacy values or exception messages (`docs/design/importer.md` §10); rate-limit refusals log the
  user id and route.
- **Not logged by Trailkeep:** request URLs; no Tomcat access log is configured. The router's own access log, outside
  Trailkeep, can record URLs, including case numbers and search text (threat model, RR-10).
- **Read by, retention, leaving the system:** the platform's log pipeline (threat model, RR-17).

## Backups

Not in the repository. A database backup holds every table above, staging included if taken during an import, and
keeps purged content until the backup expires. Encryption, access and retention of backups belong to the deploying
organization (BUILD_PLAN §9 "Data at rest"; TEST_MAP gap 10).

## Importer staging and records

The importer's data handling is designed in [`docs/design/importer.md`](../docs/design/importer.md) §5 and §10; this is
where its data sits.

| Where | What | Read by (DB) | Retention |
|---|---|---|---|
| `IMPORT_STG_PERSON`, `_CASE`, `_COMMENT`, `_ATTACHMENT`, `_EVENT` (V13) | canonical legacy rows: names, addresses and provider ids; titles, descriptions, subjects (restricted included), closure summaries, comment bodies, history values, file names and content pointers | import S I D only; the web application does not map them | deleted by `purge-staging`, or by the staging TTL (`import.staging-ttl`, default 14 days from extract). The TTL is applied at the start of every importer command, so it acts only when some command runs (`ImporterProfileIT`) |
| `trailkeep-import-content` claim | each run's attachment bytes fetched at extract | the importer Job | deleted with the run's staging; must be encrypted at rest (`deploy/README.md`) |
| `trailkeep-import-data` claim (read-only) | the mapping file, lookups, extract files and attachment folders the operator supplies | the importer Job | the operator's |
| `trailkeep-import-reports` claim | per-run reports: counts, codes, keyed hashes, Trailkeep ids | the importer Job, the approver | kept for the approver; the operator's |
| `IMPORT_RUN`, `IMPORT_ISSUE`, `IMPORT_RUN_SUMMARY` (V10) | codes, counts, mapping hash, source fingerprint; issue keys as HMAC under `import.hash-key`, never values or messages | app S I on `IMPORT_RUN` with (U) of the sign-off columns, S on issues and summaries; import S I, with (U) of the status and count columns on `IMPORT_RUN` and D on issues and summaries | indefinite |
| `IMPORT_LEDGER`, `IMPORT_LEDGER_PART` (V17, V19) | legacy keys, keyed row and written hashes, Trailkeep ids | app S; import S I | indefinite (insert-only); kept through the purge |
| `IMPORT_OWNER_PENDING` (V18) | keyed hashes of an unmatched legacy owner's address and provider id | app S; import S I D | deleted when the case is relinked |
| imported rows in domain tables | cases, comments, attachments, closures, history, holds, as in the sections above | as above | as above |

The import principals (ids -1001 and -1002) stand in for unmatched people, so imported records name no real person as
creator or decider (ADR-0015). The source password is mounted only for `validate-mapping` and `extract`.
