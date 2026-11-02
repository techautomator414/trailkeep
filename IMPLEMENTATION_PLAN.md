# Trailkeep — Implementation Plan

_Audit of the build plan, the decisions that came out of it, the engineering standards the code will be held to, and the work packages in build order._
_Version 0.2 — 2026-11-02. Where this document and BUILD_PLAN.md disagree, this document wins; BUILD_PLAN.md v0.4 carries the same corrections in place._

The audience for the code is experienced enterprise Java developers. Every decision below was made with one question: would a senior Spring / Hibernate / Oracle engineer sign off on it without a second round?

**Audit trail.** Round 1 (author pass + independent hostile review): 29 findings → Parts A.1–A.3. Round 2 (independent review of the remediated documents): 23 findings → Part A.4, with the affected A.1–A.3 rows amended in place and marked *(amended r2)*. The loop stops at zero material findings or after round 3.

---

## Part A — Audit: findings and decisions

Each row is a decision, not a suggestion; "ADR" marks the ones that get an Architecture Decision Record in `adr/` with the first code PR.

### A.1 Blockers — the plan described mechanisms that could not work as written

| # | Finding | Decision | ADR |
|---|---|---|---|
| 1 | **Migration tool vs. Oracle version.** Flyway Community's support for older Oracle releases has narrowed since Flyway 10 and requires the separate `flyway-database-oracle` plugin; the production Oracle version is unconfirmed. | Phase 0 confirms the Oracle version and tests Flyway Community against it on day one. **Liquibase (Apache-2.0) is the pre-approved fallback**; SQL files are tool-neutral. *(amended r2)* The nightly Oracle matrix runs **both** `gvenzl/oracle-xe:21-slim` (closest public image to a 19c target: no native `BOOLEAN`, same string-size behaviour) and `gvenzl/oracle-free:23-slim`; the deploying organization runs `-Poracle-it` against its real version in Phase 1 and the result is recorded. | ADR-003 |
| 2 | **`@Lob` is not portable.** Hibernate maps `@Lob String`/`byte[]` to PostgreSQL large objects (`oid`), not `TEXT`/`BYTEA`. | No `@Lob` anywhere (ArchUnit). Long text: `@Column(length = Length.LONG32)` → `CLOB` / `TEXT`. Binary never on a JPA entity (#3). `hibernate.hbm2ddl.auto=validate` runs against the migrated schema on both vendors on `main` (Oracle) and every PR (PostgreSQL). *(amended r2)* `validate` checks column presence and type family only — not nullability, length, FKs, CHECKs or indexes — so a **metadata-diff test** (JPA metamodel vs. `DatabaseMetaData`: nullability and lengths) runs alongside it. | ADR-004 |
| 3 | **Attachment bytes on the entity.** *(amended r2)* Also: pgjdbc materialises `BYTEA` fully on `getBinaryStream` (only Oracle BLOB locators stream); an Oracle BLOB stream is valid only while its connection is open, so the read must happen inside the response-streaming callback; a two-replica scan poller had no claim semantics. | Content is stored as **1 MiB chunk rows** `CASE_ATTACHMENT_CHUNK (ATTACHMENT_ID, SEQ, CHUNK ${bytes})`, written and read by `AttachmentContentJdbcDao` (never JPA) — portable streaming with bounded heap on both vendors. Download: `StreamingResponseBody` whose `writeTo` runs the DAO inside its own `TransactionTemplate`; a semaphore (`app.attachments.max-concurrent-downloads`, default pool size − 4) bounds held connections. Upload: multipart to a temp file on an `emptyDir` (`spring.servlet.multipart.location`, `java.io.tmpdir`; the root FS is read-only), Tika + size + hash, then **synchronous ClamAV INSTREAM scan of the temp file before anything is persisted** — no poller, no network I/O inside a transaction; clamd unavailable → upload rejected with "scanning unavailable, try again" (never stored unscanned). Caps: `spring.servlet.multipart.max-file-size=25MB`, `clamd.conf` `StreamMaxLength`, `MaxFileSize`, `MaxScanSize` set deliberately. | ADR-004 |
| 4 | **Booleans and dialect detection.** | Plain `boolean`; `hibernate.type.preferred_boolean_jdbc_type=BIT`; *(amended r2)* the dialect is pinned by **`jakarta.persistence.database-product-name` + `database-major-version` + `database-minor-version`** (versioned dialect classes no longer exist in Hibernate 7), so Hibernate skips metadata detection and DDL expectations (`BOOLEAN`, fetch-first) do not change with the connected server. | ADR-004 |
| 5 | **Appendix A DDL was not valid Oracle.** | Placeholders `${id} ${int} ${bool} ${clob} ${bytes} ${tstz}`; Oracle `initSql` sets `NLS_LENGTH_SEMANTICS=CHAR`. *(amended r2)* Oracle's 4000-**byte** `VARCHAR2` limit still applies under CHAR semantics, so free-text `VARCHAR` columns are capped at **1000** characters (worst-case 4 bytes/char) and anything longer is `${clob}`. Migration-lint test: identifiers ≤ 30, reserved words on either vendor, ANSI types outside placeholders, `VARCHAR` > 1000. | — |
| 6 | **"Specification guarantees restricted-case invisibility" was false.** *(amended r2)* Also: a single gate class in `casefile` cannot be "the only path" when `CLOSURE_REQUEST`, `CASE_ATTACHMENT`, `UPDATE_REQUEST`, `CASE_SLA` and `AUDIT_EVENT` live in other modules with package-private repositories; a native-SQL search adapter bypasses Specifications; sequential numbers + 403 make restricted cases enumerable. | **Exported contract, not a choke point.** `casefile` exports `CaseScope`: for a principal it yields (a) a JPA `Specification`/subquery of readable case ids and (b) `assertReadable(principal, caseNumber)`. Every repository on a case-rooted table, in any module, is annotated `@CaseRooted` and **every query method on it takes a `CaseScope` parameter** (ArchUnit: `@CaseRooted` repositories may be injected only into `@CaseAccessPath` classes, and all their query methods declare a `CaseScope`). Single-entity reads are load-then-`assertReadable`. The LIKE search adapter returns candidate ids which the scope filters. Jobs use `CaseScope.system()`. **Unreadable cases answer 404, never 403** (403 is reserved for a denied *action* on a readable case); attachment URLs inherit this. The reflection-driven negative test enumerates the `@CaseRooted` repositories (`CaseRecord`, `CaseComment`, `CaseCommentPin`, `CaseAttachment`, `UpdateRequest`, `ClosureRequest`, `CaseSla`, `CaseSearch`, `AuditEvent`) and asserts, per method, that a non-permitted principal gets the same result as for an absent case. | ADR-005 |
| 7 | **Search design.** *(amended r2)* The version-less side table traded lock conflicts for silent lost updates under concurrent read-modify-write. | `SearchGateway` interface; `CASE_SEARCH (CASE_ID PK, SEARCH_TEXT ${clob}, UPDATED_AT)`, no `@Version`, maintained **atomically**: comments append with `UPDATE CASE_SEARCH SET SEARCH_TEXT = SEARCH_TEXT \|\| :fragment` (`\|\|` works on Oracle CLOB and PostgreSQL TEXT); a title/description edit rebuilds after `SELECT … FOR UPDATE` on the `CASE_SEARCH` row (row lock held only for the rebuild, not visible to editors of `CASE_RECORD`). **Subject is excluded; description is included** — it is already readable by the same principals under the same grants and encryption, and search without it is useless; this is stated, not hidden. Portable `LIKE` adapter in v1 with the honest expectation "scan of ~50k rows, measured at the 50k seed; vendor adapters (`pg_trgm`, Oracle Text) in v1.1 behind the same interface". | ADR-006 |
| 8 | **SLA evaluator** wrote through the versioned entity in one 50k-row transaction. *(amended r2)* The claimed `(STATUS, SLA_DUE_AT)` index spanned two tables; the AT_RISK scan had no index; `NOT NULL` due dates broke the `NOT_APPLICABLE` import path; anchor rules contradicted each other. | `CASE_SLA (CASE_ID PK, SLA_STATE, SLA_DUE_AT, AT_RISK_AT, SLA_CYCLE, LAST_EVALUATED_AT)`; indexes `(SLA_STATE, SLA_DUE_AT)` and `(SLA_STATE, AT_RISK_AT)`; `SLA_DUE_AT`/`AT_RISK_AT` nullable with `CHECK (SLA_STATE = 'NOT_APPLICABLE' OR SLA_DUE_AT IS NOT NULL)`. **One anchor rule:** on create, anchor = policy (`IDENTIFIED_ON` at end of day in `app.timezone`, or `OPENED_AT`); on reopen, anchor = the reopen instant and `SLA_CYCLE += 1`. Written by the `sla`, `closure` and `casefile` services, never by the case entity. Evaluator: chunks of 500 by id, one transaction each, bulk update, ShedLock `lockAtMostFor=14m`, `lockAtLeastFor=1m`, `usingDbTime()`. | ADR-007 |
| 9 | **Sessions + OIDC across two replicas.** *(amended r2)* The authorized-client store was justified wrongly (RP-initiated logout reads the ID token from the `OidcUser` principal, which Spring Session already shares); a "Spring-Session-backed `OidcSessionRegistry`" does not exist off the shelf; back-channel logout propagates by HTTP-calling the app's own logout endpoint; JSON session serialization needs Jackson mixins. | Spring Session JDBC with vendor schemas as migrations, the vendor `JdbcIndexedSessionRepositoryCustomizer`, **JSON attribute serialization with Spring Security 7's Jackson-3 mixins**; **authorities are not persisted** — `UserPermissions` is rebuilt per request from the database (so a role change or disable takes effect on the next request). **No `JdbcOAuth2AuthorizedClientService`** (the app never uses access/refresh tokens; one less schema and grant). RP-initiated logout via `OidcClientInitiatedLogoutSuccessHandler`. Back-channel logout: a **custom `JdbcOidcSessionRegistry`** (table `OIDC_SESSION (SID PK, SESSION_ID, PRINCIPAL)`) and `OidcBackChannelLogoutHandler.logoutUri` pointed at the pod's own loopback address (NetworkPolicy allows self). Absolute 8-hour lifetime by filter from a login-time attribute. **Disable user** ⇒ all of that user's sessions deleted via `FindByIndexNameSessionRepository` and the OIDC success handler refuses disabled users. Step-up re-authentication for admin actions is **cut for scope** (the mechanism exists: `authorizationRequestCustomizer` adding `max_age` + an `auth_time` check; about a day of work) and admin actions are audited instead. | ADR-008 |
| 10 | **CSP / htmx / Bootstrap** story was incoherent. *(amended r2)* htmx injects an indicator `<style>` at start-up and snapshots swapped pages into `localStorage`. | Exact header: `default-src 'self'; script-src 'self' 'nonce-{n}'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`. Per-request nonce filter + `th:attr="nonce=${cspNonce}"`. **Fixed htmx config** (tested for presence in WP-05): `allowEval=false`, `allowScriptTags=false`, `selfRequestsOnly=true`, `includeIndicatorStyles=false` (indicator CSS ships in `app.css`), `historyCacheSize=0`, `refreshOnHistoryMiss=true` (no PHI in `localStorage`). `hx-on*` and `hx-vals="js:"` banned by Semgrep. **Bootstrap CSS only.** Interactive behaviour in one `app.js` on native `<dialog>` and WAI-ARIA APG patterns. | ADR-009 |
| 11 | **Admin-editable email templates = template injection.** | No database template editor in v1; fixed Thymeleaf files, wording via `messages_*.properties`; v1.1 editor uses non-evaluating `{{key}}` substitution over an allow-listed map. | — |
| 12 | **Spring Boot "3.5 fallback"** is not source-compatible and is out of OSS support. | Current Spring Boot 4.x only; 4.x starter names; JSpecify and Jackson 3 as conventions. No downgrade path; an approved-software lag is handled by a documented exception. | ADR-001 |
| 13 | **PDF/UA with raw PDFBox** is a multi-week subsystem. | PDF export moves to v1.1 (`openhtmltopdf`, LGPL dynamically linked, veraPDF gate). v1: CSV + accessible HTML report with print stylesheet. | ADR-010 |

### A.2 Significant — correctness and data-model gaps

| # | Finding | Decision |
|---|---|---|
| 14 | Outbox lacked a claim pattern and an idempotency table. *(amended r2)* A JPA `PESSIMISTIC_WRITE` + `setMaxResults` claim is **broken on Oracle**: `FETCH FIRST … FOR UPDATE` is illegal, so Hibernate falls back to follow-on locking and returns rows it failed to lock. | **Native SQL only, in `OutboxJdbcDao`**, portable two-step claim: (1) `SELECT ID FROM NOTIFICATION_OUTBOX WHERE STATUS='PENDING' AND NEXT_ATTEMPT_AT <= :now ORDER BY ID` limited to 50 via `Pageable`-free vendor-neutral `FETCH FIRST 50 ROWS ONLY` (valid on both without `FOR UPDATE`); (2) `SELECT … WHERE ID IN (:ids) AND STATUS='PENDING' FOR UPDATE SKIP LOCKED`; process **only** the rows step 2 returned; set `STATUS='SENDING', LOCKED_UNTIL=:now+5m`; **commit before SMTP**; then mark `SENT`/`FAILED` in a new transaction. Reaper returns `SENDING` rows past `LOCKED_UNTIL` to `PENDING`. Index `IX_OUTBOX_CLAIM (STATUS, NEXT_ATTEMPT_AT)`. `ALERT_LEDGER (CASE_ID, ALERT_TYPE, SLA_CYCLE, PERIOD_KEY) UNIQUE`. Column `RECIPIENT`. `REPORT_RUN` referenced by outbox rows (`CASE_ID` nullable — report mails have no case). The two-instance no-double-send IT runs in the **Oracle** suite explicitly. |
| 15 | Case-number allocation. | Gaps acceptable; one sequence; `PREFIX-YYYY-NNNNNN`, year = opening year, number never resets and simply widens past 999 999; prefix ≤ 8 chars. `NEXTVAL` is obtained through Hibernate's `Dialect.getSequenceSupport()` (vendor syntax differs), the one allow-listed dynamic SQL in `casefile`. |
| 16 | Audit old/new capture, PHI in audit, missing `CASE_ID`, DB clock. *(amended r2)* SHA-256 of a short subject is dictionary-reversible. | Service-layer snapshot diff with a redaction policy: `description`/`subject` recorded as **HMAC-SHA-256 with a server-side key** + length, never text. `AUDIT_EVENT.CASE_ID` nullable + indexed; `ENTITY_ID` nullable. `VIEW_RESTRICTED` is recorded once per full page view and per download, not per htmx fragment. One `InstantSource` bean (UTC); DB time only inside ShedLock. |
| 17 | Open-in-view, fetch strategy, N+1. *(amended r2)* `@BatchSize` on a `@ManyToOne` is ignored. | `spring.jpa.open-in-view=false`; record projections for lists; `@EntityGraph` for the case page; `hibernate.default_batch_fetch_size=50` (class-level `@BatchSize` on `AppUser` where needed); `fail_on_pagination_over_collection_fetch=true`; `in_clause_parameter_padding=true`; query-count assertions (datasource-proxy) on the five hottest pages. |
| 18 | Permission model vs. schema; SoD edge cases. *(amended r2)* System Admin could read case content through "view audit for all" and grant itself team roles; disable had no effect on live sessions; owner-in-team was assumed, not enforced; reassign could violate owner ≠ approver; a departed approver left requests stuck. | Authority = `Set<TeamRole>` + global roles; one `CasePermissionService` decision table tested from a CSV. **Rules:** default approvers = the team's Managers minus the requester, else Compliance Admins; `ESCALATE_TO` decides whether escalations go to the owner only or to the owner and all of the team's Managers; only the owner or a Manager+ may mark a case restricted, and the actor must remain able to read it afterwards; **owner must hold a role in the case's team** (service invariant + matrix row); **reassign is blocked while `PENDING_CLOSURE`** (cancel the request first) and a Manager+ may **reassign the approver** of a pending request; a Manager may reassign a restricted case away from themselves; Reader/Analyst reports exclude restricted rows; **audit-for-all is Compliance Admin only**; **a user holding `SYSTEM_ADMIN` cannot hold team roles** (prevents self-grant), and team-role changes are audited; disable ⇒ sessions purged + login refused; a disabled owner's or approver's items are flagged for reassignment. |
| 19 | Data-model gaps. *(amended r2)* `RETENTION_POLICY` was in the ERD but nowhere else; no second clock for archive → purge; no import-run or comment-author snapshot. | Closure fields on `CLOSURE_REQUEST`. New: `CASE_TYPE_OUTCOME`, `USER_ALERT`, `ALERT_LEDGER`, `REPORT_RUN`, `REPORT_SCHEDULE`, `CASE_SLA`, `CASE_SEARCH`, `CASE_ATTACHMENT_CHUNK`, `CASE_COMMENT_PIN`, `OIDC_SESSION`, `IMPORT_RUN`, `APP_USER_GLOBAL_ROLE`. Columns: `UPDATE_REQUEST.ANSWERED_BY_COMMENT_ID`, `LAST_REMINDED_AT`; `CASE_RECORD.LAST_REJECTED_AT`, `ARCHIVED_AT`, `PURGE_AFTER`; attachment `RELEASED_BY/AT/REASON`; `LEGAL_HOLD_BY/AT/REASON`; `CASE_COMMENT.AUTHOR_ID` nullable + `AUTHOR_NAME_SNAPSHOT`; `UNIQUE (LEGACY_REF)`. Retention lives on `CASE_TYPE.RETENTION_YEARS` (no `RETENTION_POLICY` table). Status `ARCHIVED`; `SLA_STATE` `NOT_APPLICABLE`; CHECK on every closed-set column (`AUDIT_EVENT.ENTITY_TYPE`/`ACTION` are open sets, deliberately). Index on `CLOSURE_REQUEST (APPROVER_ID, DECISION)`. |
| 20 | Grants vs. features. *(amended r2)* Several legitimate DELETEs exist; sequence grants differ per vendor; "one migration per PR" collided with a repeatable grants file. | Grants matrix in **`oracle/R__grants.sql` and `postgresql/R__grants.sql`** with `${app_user}`/`${purge_user}` placeholders. App user: **no UPDATE/DELETE** on `CASE_COMMENT`, `AUDIT_EVENT`, `ALERT_LEDGER`; DELETE allowed on `CASE_COMMENT_PIN`, `SAVED_VIEW`, `USER_ALERT`, `SPRING_SESSION*`, `OIDC_SESSION`, `SHEDLOCK`, importer staging; sent `NOTIFICATION_OUTBOX`/`REPORT_RUN` trimming and all case-data deletion belong to the purge user. `hibernate.default_schema` / `CURRENT_SCHEMA` / `search_path` set explicitly. PR rule: "one **versioned** migration per PR; the repeatable grants file may change alongside it." |
| 21 | Tika and image handling. | `tika-core` only; container types allow-listed with extension cross-check; synchronous clamd scan (recursive, EML/MSG included); image dimensions capped before decode. |
| 22 | `th:utext` ban vs. sanitized Markdown. | `SafeHtml` value type + `tk:safe` attribute processor; Semgrep bans `th:utext`, allow-lists `tk:safe`. |
| 23 | htmx + Spring Security redirects and error bodies. | `HX-Redirect` on 401/403 for `HX-Request`; fragment contract 200 / 422 + `HX-Retarget` / 409 / 5xx banner; one `htmx:beforeSwap` handler. |
| 24 | Domain events and transaction phases. | Audit, outbox and ledger rows written synchronously in the originating transaction; cross-module reactions `BEFORE_COMMIT`; no network I/O inside a transaction (the one upload scan happens before persistence, see #3); Spring Modulith for structure only in v1. |

### A.3 Over-promises corrected

| # | Finding | Decision |
|---|---|---|
| 25 | "Build-breaking from the first commit" on a public repo. *(amended r2)* The documents disagreed on where ZAP, Sonar, veraPDF and the Oracle suite run. | **One placement table** (Part G) is the source of truth, referenced by BUILD_PLAN §12, Appendix B and Part D. Oracle IT runs on `main` and nightly, not on fork PRs (image pull and start-up add ~3 minutes per job; it needs no secrets, so this is a speed decision, not a security one). |
| 26 | Accessibility overstated. | "Vetted component list", native `<dialog>`, JavaScript required, axe is one gate; Phase 6 is a 3-week conformance window with a **named, certified Trusted Tester** on the staffing line. |
| 27 | Estimate. *(amended r2)* Phase 1's serial chain did not fit 3 weeks; WP-26 was undersized; §17 listed deliverables without a WP; Phase 7 contradicted itself. | **Re-baselined again** (Part F): ≈ **25 weeks** for two developers; Phase 1 = 4 weeks with WP-03/WP-04 split so vendor work and session work proceed in parallel; WP-26 = L; Phase 7 = 2 weeks; certified tester budgeted. |
| 28 | License statements. | ojdbc redistributed in the image under Oracle FUTC and recorded in `NOTICE`; two distinct license plugins named; Logback under EPL; only LGPL runtime dependency is `openhtmltopdf` in v1.1. |
| 29 | Small items. *(amended r2)* `framework` forward-headers strategy trusts any client header. | **`server.forward-headers-strategy=native`** with `server.tomcat.remoteip.trusted-proxies` set to the Route/ingress CIDR, plus a test that a client-supplied `X-Forwarded-For` is ignored. `Pageable` sort properties allow-listed. Case number is the URL key (with the 404 rule in #6). SLA anchor per #8. Weekly report = hourly tick over `REPORT_SCHEDULE.NEXT_RUN_AT`. Bucket4j per replica by design. Java 21, platform threads; virtual threads are a Java 25 follow-up (JEP 491 removed synchronized pinning in Java 24, so the earlier Hikari concern is moot — the remaining question is Oracle driver behaviour, to be measured). `spring.task.scheduling.pool.size=4` so a long evaluator run never starves the dispatcher. |

### A.4 Round 2 — new findings and decisions

| # | Finding | Decision |
|---|---|---|
| 30 | **Evaluator health indicator** on a per-pod basis is red on every pod that did not win the lock; in a probe group it restarts healthy pods. Default scheduler is single-threaded. | The indicator reads cluster truth (`SHEDLOCK.locked_at` / a `JOB_RUN` last-success row), is **excluded from the liveness/readiness groups**, and is exported as a metric for the Prometheus rule. Scheduler pool size 4 (see #29). |
| 31 | **Request Update to a user who cannot read the case** emails a dead link and can never auto-resolve. | Recipient must satisfy `CaseScope.canRead`; the combobox is filtered server-side and the service validates. |
| 32 | **Reminders have no owning job or configuration.** | A `reminders` tick (hourly, ShedLock) in WP-17b sends update-request reminders (`app.reminders.update-request-days`, default 3), pending-closure reminders (`app.reminders.closure-days`, default 3) and overdue repeats (`SLA_POLICY.OVERDUE_REMINDER_DAYS`), all through `ALERT_LEDGER` + outbox. |
| 33 | **Archive and purge were undefined.** | `CLOSED` → `ARCHIVED` by the retention job when `closed_at + RETENTION_YEARS` passes (sets `ARCHIVED_AT`, `PURGE_AFTER = ARCHIVED_AT + app.retention.purge-grace-days`); archived cases are read-only, hidden from default lists (filter "include archived" for Manager+/Compliance Admin), not reopenable. **Purge scope:** comments, pins, attachments + chunks, search text, update requests and closure requests are deleted; `CASE_RECORD` becomes a tombstone (description replaced by `[purged]`, subject nulled, title kept) and `AUDIT_EVENT`/`ALERT_LEDGER` are retained. The `purge` profile runs `spring.main.web-application-type=none`, no OIDC, no web schedulers, its own fail-fast list. The importer runs the same way as a Kubernetes `Job` with the `importer` profile. |
| 34 | **Importer idempotency** had no uniqueness or run table. | `UNIQUE (LEGACY_REF)` (nullable; both vendors allow multiple NULLs), `IMPORT_RUN (ID, STARTED_AT, FINISHED_AT, SOURCE_HASH, ROWS_READ, ROWS_WRITTEN, STATUS)`, comment author snapshot columns (#19). |
| 35 | **Appendix A residuals**: unindexed FKs, no range CHECKs, inconsistent FK policy, outcome not constrained to the type's list. | Indexes on every FK column (`CASE_RECORD.CASE_TYPE_ID`, `CASE_TYPE.SLA_POLICY_ID`, `APP_USER_TEAM (TEAM_ID, USER_ID)`); range CHECKs (`AT_RISK_PCT BETWEEN 1 AND 99`, `TARGET_DAYS > 0`, `RETENTION_YEARS >= 0`); **FK policy:** every `*_ID` that references a user or case has a declared FK (`CREATED_BY`, `UPDATED_BY`, `LEGAL_HOLD_BY`, `REQUESTED_BY`, `DECIDED_BY` included); outcome validity against `CASE_TYPE_OUTCOME` is a service rule (a type's outcome list may change over time; history must keep old values). |
| 36 | **Stale v1/v1.1 and vendor text** survived in BUILD_PLAN (SAML in §4.1/§7.1, per-case "Export PDF", Helm/WAR in §16, "never redistributed" in §11.2, CRLF rule, 500k-comment search target, module names in §7.1, `@MockitoBean` as a 4.x change). | All corrected in BUILD_PLAN v0.4; this register is the checklist. |
| 37 | **Work-package defects**: missing dependencies (WP-20→21, WP-16→14, WP-24→14, WP-17→09, WP-25→26); `V1__core.sql` created case tables before their entities existed; Part E demanded per-PR Oracle on fork PRs. | Dependencies fixed in Part C; first migration limited to identity/admin/audit tables, case DDL arrives with WP-11/WP-19 (Appendix A relabelled as "first three migrations"); Part E: "PostgreSQL on every PR; Oracle green on `main` after merge, and a red nightly blocks the next merge". |
| 38 | **Part B omissions.** | Added: `@Enumerated(EnumType.STRING)` mandatory; `@Immutable` on insert-only entities; id-based null-safe `equals/hashCode`, `toString` without PHI; `-Xlint:all,-processing -Werror`; Semgrep runs in CI (`semgrep ci` + `semgrep --test` for rule tests), not in Maven; `@SuppressFBWarnings` with justification for SpotBugs; `Specification.unrestricted()` (not the deprecated `where`); scaffold PRs exempt from the size rule; saved-view filters validated by binding to the typed filter record (no JSON-schema dependency); CSV formula escaping is hand-rolled (`= + - @ \t \r |` prefixes) because Commons CSV has none. |
| 39 | **Keycloak dev realm** "five users, one per role" did not match a per-team model. | Dev realm: two teams, each with Reader, Analyst, two Managers; one Compliance Admin; one System Admin; one disabled user. |

---

## Part B — Engineering standards (what reviewers will judge)

Enforced by the build where a tool exists, by the PR checklist where one does not.

**Language and framework.** Java 21 (compile target); CI also runs the suite on the current Java LTS. Spring Boot current 4.x. JSpecify: every `package-info.java` is `@NullMarked`; NullAway via Error Prone fails the build on a nullness violation. **No Lombok.** Records for commands, read models and value objects; constructor injection, no field injection. Sealed interfaces for multi-outcome results. Immutable collections returned.

**Formatting and static checks.** Spotless (palantir-java-format); Checkstyle (custom ruleset from Google's: 120 columns, no star/unused imports, braces always, method ≤ 60 lines, class ≤ 400 lines, cyclomatic complexity ≤ 10); `-Xlint:all,-processing -Werror`; Error Prone + NullAway; SpotBugs + FindSecBugs at max effort (`@SuppressFBWarnings(justification=…)` only); ArchUnit in `src/test/java/.../architecture`; Semgrep runs in CI (`semgrep ci`), its custom rules tested with `semgrep --test`.

**Layering and modules.** Spring Modulith `ApplicationModules.verify()` as a test; modules = `shared, security, audit, user, casefile, closure, updaterequest, sla, document, notification, report, retention, importer, web`. A module exposes a `*Service` interface and record types in its root package; everything else package-private. Repositories package-private. Case-rooted repositories are `@CaseRooted`, every query method takes `CaseScope`, and they are injected only into `@CaseAccessPath` classes (ArchUnit).

**Persistence.** The migration tool owns the schema; Hibernate `validate` plus the metadata-diff test run on both vendors. Entities: `Long` id from pooled sequences (`allocationSize=50`), `@Version int version` on user-edited aggregates only, `Instant` timestamps, `@Enumerated(EnumType.STRING)` always, `@Column(length=Length.LONG32)` for long text, no `@Lob`, no `byte[]`, `FetchType.LAZY` on every association, `@Immutable` on `CaseComment`/`AuditEvent`/`AlertLedger`, id-based null-safe `equals/hashCode` (no collections), `toString` never includes description/subject. `@EntityGraph` or projection per read use-case; `open-in-view=false`; `default_batch_fetch_size=50`. Native SQL only inside `*JdbcDao` classes in `document` (chunks), `notification` (claim), `casefile` (search LIKE, sequence `NEXTVAL` via `Dialect.getSequenceSupport()`), each with a comment stating why JPA cannot express it. No entity crosses a module boundary or reaches a template.

**Transactions.** `@Transactional` on service implementations only; read paths `readOnly=true`; network I/O never inside a transaction; cross-module reactions `@TransactionalEventListener(BEFORE_COMMIT)`; jobs chunked, one transaction per chunk; streaming downloads open their own `TransactionTemplate` inside the response callback.

**Web.** Thymeleaf templates under `templates/{module}/`, fragments under `templates/{module}/fragments/`, one layout; commands are records with Jakarta Validation bound with `@Valid`; one `@ControllerAdvice` maps `DomainException` → `NotFound` (404, also for unreadable cases), `Forbidden` (403, denied action on a readable case), `Conflict` (409), `Invalid` (422) to the fragment contract and to full pages. Case number is the only identifier in URLs. Sort/filter parameters parsed into allow-listed enums; saved-view filters validated by binding to the typed filter record. Static assets content-hashed (`spring.web.resources.chain.strategy.content`).

**Security conventions.** Authorization decisions live in `CasePermissionService` and `CaseScope`; controllers check only `isAuthenticated()`. All mutations are `POST` with CSRF; no `GET` has side effects. Output escaped by default; `tk:safe` is the only unescaped path. Authorities are never persisted in the session; `UserPermissions` is rebuilt per request. `server.forward-headers-strategy=native` with trusted proxies.

**Logging.** Logback + logstash JSON encoder (escapes control characters). MDC keys `requestId`, `userId` (internal id), `caseNumber`, `module`. INFO business events, DEBUG flow, WARN recoverable, ERROR once with stack trace. Never log description, subject, restricted attachment names, or email addresses. Audit is a table, not a log line.

**Configuration.** `@ConfigurationProperties` records under `app.*` with `@Validated`; `prod` fails fast on missing required properties; profiles `local`, `test`, `oracle-test`, `prod`, `purge`, `importer` (the last two are `web-application-type=none`). Hikari pool size documented per replica against the Oracle session limit; `spring.task.scheduling.pool.size=4`; dialect pinned by `jakarta.persistence.database-product-name/-major-version/-minor-version`.

**Testing.** Surefire `*Test`; Failsafe `*IT`. Behaviour-named tests (`approve_byRequester_isForbidden`). Testcontainers `@ServiceConnection`, reuse locally. JaCoCo 80 % line / 70 % branch overall, 90 % line in `sla`, `closure`, `security`, per package. Playwright page objects under `e2e/pages`. Builders, not SQL dumps. No real network.

**Pull requests.** ≤ 400 net production lines (scaffold PRs 1, 2, 6 exempt and say so); one versioned migration per PR shipped with its entity change and test (the repeatable grants file may change alongside); template checklist (scrub, authorization test, audit event, migration portable, accessibility criteria, SPDX header, ADR if a decision changed); squash-merge, conventional commit subject, DCO sign-off. ADRs in `adr/NNNN-title.md` (MADR).

**Documentation in code.** Javadoc on module API types/methods; `package-info.java` per module stating responsibility and permitted callers.

---

## Part C — Work packages

Sizes: **S** ≤ 2 developer-days, **M** 3–5, **L** 6–10. "Depends" = merged first. Every WP ends with tests green on PostgreSQL in CI; anything touching SQL is also green on the Oracle matrix on `main`.

### Phase 1 — Walking skeleton (4 weeks)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-01** | Build and repository scaffold | M | — | `pom.xml` (enforcer, Spotless, Checkstyle, Error Prone + NullAway, SpotBugs/FindSecBugs, JaCoCo, mycila headers, mojohaus third-party + allow-list, CycloneDX), `mvnw`, `.editorconfig`, `.gitattributes`, `CODEOWNERS`, PR/issue templates, `adr/0001..0010`, `TrailkeepApplication` with health on the management port. **Accept:** `./mvnw verify` green on an empty app; each Maven-wired scanner shown failing on a planted violation. |
| **WP-02** | CI pipeline per Part G | M | WP-01 | `ci.yml` (fork-safe), `main.yml`, `nightly.yml`, `codeql.yml`, `scorecard.yml`, `dependabot.yml`, Semgrep (`ci` + `--test`), SHA-pinned actions, least-privilege token. **Accept:** a fork PR passes without secrets; `main` runs the full set; nightly runs the Oracle matrix (xe 21 + free 23). |
| **WP-03a** | Migrations and two-vendor validation | M | WP-01 | Locations, placeholders, `initSql`, migration-lint test, Testcontainers PostgreSQL + Oracle (xe 21, free 23) via `@ServiceConnection`, `validate` + metadata-diff test, pinned dialect properties, Hibernate settings (`BIT` booleans, open-in-view off, batch size, padding), `InstantSource`. **Accept:** `V1__identity.sql` (users, teams, team roles, global roles) migrates and validates on all three images. |
| **WP-03b** | Admin schema and grants | S | WP-03a | `V2__admin.sql` (case types, outcomes, SLA policies), `V3__audit.sql`, vendor `R__grants.sql` with placeholders and a negative IT per forbidden statement. |
| **WP-04a** | OIDC login and roles | M | WP-03a | Keycloak compose realm (#39), login, `AppUser` upsert, global-role mapping, disabled-user refusal, test support `@WithAppUser`. |
| **WP-04b** | Sessions across replicas | M | WP-04a | Spring Session JDBC (vendor schemas, customizer, JSON + mixins, authorities not persisted), absolute-lifetime filter, idle timeout + `/session/extend`, RP-initiated logout, `JdbcOidcSessionRegistry` + back-channel logout with loopback `logoutUri`, disable ⇒ session purge. **Accept:** two app instances in an IT share a session and both honour a back-channel logout. |
| **WP-05** | Web shell, CSP, htmx contract | M | WP-04a | Layout, nonce filter, exact CSP, vendored htmx with the fixed config (presence test), `app.css`/`app.js` (beforeSwap contract, focus manager, live region, `<dialog>` helper, session warning), error pages (404 for unreadable), `@ControllerAdvice`, asset versioning, Bootstrap CSS tokens. **Accept:** axe 0 violations on shell and error pages; CSP has no `unsafe-*`; browser console shows no CSP violation on load. |
| **WP-06** | Audit module | M | WP-03b | `AuditEvent`, `AuditRecorder`, snapshot diff with HMAC redaction, MDC/request-id filter, `CaseScope.system()`. **Accept:** rollback test; redaction test. |
| **WP-07** | Modulith structure and architecture rules | S | WP-01 | `ApplicationModules.verify()`, ArchUnit rules (`@CaseRooted`/`@CaseAccessPath`/`CaseScope` parameter, no `@Lob`, repositories package-private, no entity in `web`, `@Enumerated STRING`), Semgrep rules with `--test` cases. |
| **WP-08** | Observability, container, deploy base | M | WP-01 | JSON logging + MDC, Micrometer, management port, health groups (cluster indicators excluded from probes), UBI 9 image (non-root, read-only FS, `emptyDir` for tmp/multipart/Tomcat work dir), kustomize base (Deployment, Service, Route, NetworkPolicy incl. loopback, SCC, Secrets, `CronJob`/`Job` templates), `native` forward headers + trusted-proxy test, Trivy/Dockle, SBOM + cosign + SLSA on tags. |

Phase 1 exit: WP-01…08 merged; every scanner green; login works against a real IdP in DEV; ACR draft for the shell.

### Phase 2 — Cases (5 weeks)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-09** | Users, teams, team roles (admin) | M | WP-04b, 06 | Team/membership admin, `CasePermissionService` decision table + CSV exhaustive test (incl. owner-in-team, no team roles for System Admins), per-request `UserPermissions`. |
| **WP-10** | Case types, outcomes, SLA policies (admin) | S | WP-09 | CRUD with range validation; seed YAML for `local`. |
| **WP-11** | Case create and view; `CaseScope` | L | WP-09, 10 | `V4__case.sql` (`CASE_RECORD`, `CASE_SLA`, `CASE_SEARCH`), numbering via `Dialect` sequence support, one-page create (PHI heuristic), case page, **`CaseScope` + `@CaseRooted` infrastructure**, 404 rule, SLA row initialisation, audit, `@EntityGraph`, projections. **Accept:** golden path 1; reflection-driven invisibility test over the `@CaseRooted` repositories present so far (absent and unreadable indistinguishable); query-count assertion. |
| **WP-12** | Edit, reassign, restrict | M | WP-11 | Optimistic locking (409 → Reload), reassign (blocked while pending closure; owner-in-team), restrict (actor keeps read), audit diffs. |
| **WP-13** | Comments, pins, `SafeHtml`, search text | M | WP-11 | `CASE_COMMENT` (insert-only, author snapshot), `CASE_COMMENT_PIN`, Markdown → sanitizer → `SafeHtml`, `tk:safe`, atomic `CASE_SEARCH` append/rebuild. **Accept:** XSS corpus; INSERT-only negative IT; two concurrent comments both appear in `SEARCH_TEXT`. |
| **WP-14** | Attachments | L | WP-11 | Temp-file upload on `emptyDir`, Tika + allow-list + size + hash, **synchronous ClamAV scan before persist**, `CASE_ATTACHMENT` + `CASE_ATTACHMENT_CHUNK` via JDBC DAO, streamed download inside a `TransactionTemplate`, download semaphore, release by Compliance Admin, image cap. **Accept:** EICAR rejected; disguised executable rejected; clamd down ⇒ upload refused; 8 concurrent 25 MB downloads on **PostgreSQL** with `-Xmx256m` succeed (chunking is what makes this pass). |
| **WP-15** | Cases list, filters, search, saved views, CSV | L | WP-11, 13 | Projections, allow-listed sort, filter drawer (incl. "include archived"), `SearchGateway` + LIKE adapter via `CaseScope`, `SAVED_VIEW` bound to the typed filter record, CSV export with redaction and hand-rolled formula escaping, 50k seed + k6 smoke. **Accept:** p95 < 500 ms at 50k on PostgreSQL; restricted rows absent from results, counts and CSV. |
| **WP-16** | History tab | S | WP-06, 11, 14 | Case-scoped audit timeline; `VIEW_RESTRICTED` once per page view/download. |

### Phase 3 — Closure and email (3 weeks)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-17** | Outbox and dispatcher | M | WP-03b, 06, 09 | `NOTIFICATION_OUTBOX`, `OutboxJdbcDao` two-step SKIP LOCKED claim, state machine, SMTP outside transactions, backoff, reaper, `USER_ALERT`, Mailpit, metrics. **Accept:** two dispatcher instances never double-send (**Oracle and PostgreSQL**); stale `SENDING` reclaimed; SMTP down ⇒ rows stay `PENDING`. |
| **WP-17b** | Reminders tick | S | WP-17, 21 | Hourly ShedLock job: update-request, pending-closure and overdue reminders via `ALERT_LEDGER`. (Lands in Phase 4 after WP-21.) |
| **WP-18** | Request Update | M | WP-13, 17 | `UPDATE_REQUEST`, recipient filtered by `CaseScope.canRead`, APG combobox, deep-link email, auto-resolve, "Mark answered", dashboard "Needs attention". |
| **WP-19** | Closure: request / approve / reject / reopen / reassign approver | L | WP-12, 17 | `V__closure.sql`, approver rules, SoD, transitions + audit + emails, "Requires Changes", reopen (`SLA_CYCLE`), approver reassignment, pending-closure reassign block. **Accept:** golden paths 2–4; SoD tests; concurrent approve/reject yields one decision. |
| **WP-20** | Dashboard | S | WP-15, 19, 21 | KPI tiles (Open, At Risk, Overdue, Pending my approval), My cases, Needs attention — all via `CaseScope`. (Lands after WP-21.) |

### Phase 4 — SLA and aging (3 weeks)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-21** | SLA evaluator and alerts | L | WP-11, 17 | Chunked evaluator over `CASE_SLA`, ShedLock, ledger + outbox, SLA chip/filters, anchor rules, closure finalisation, metrics, cluster-truth health indicator outside probe groups. **Accept:** fixture of 20 cases → expected transitions and exactly one email each across three runs and a restart; reopen resets per cycle. |
| **WP-22** | Aging and SLA-compliance reports (HTML + CSV) | M | WP-21 | SQL-aggregated queries, accessible HTML with print stylesheet, CSV, redaction. **Accept:** matches hand-computed fixture; axe clean. |
| **WP-23** | Scheduled report delivery | M | WP-22, 17 | `REPORT_SCHEDULE` hourly tick, `REPORT_RUN`, outbox rows referencing runs, first-cycle admins-only switch. |

### Phase 5 — Admin, retention, importer, polish (3 weeks)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-24** | Retention, archive, legal hold, purge `CronJob` | M | WP-14, 19 | Archive job, `ARCHIVED` behaviour, legal hold metadata, `PURGE_AFTER`, `purge` profile (`web-application-type=none`) as a `CronJob` with the purge credential, tombstone semantics, metadata-only log. **Accept:** legal hold stops purge; app credential cannot delete (negative IT). |
| **WP-25** | System screen and operational alerts | S | WP-17, 21, 26 | Job last-runs (cluster truth), outbox backlog/failures, clamd status, last import run; Prometheus rules. |
| **WP-26** | Generic importer | L | WP-11, 13, 14 | `IMPORT_RUN`, staging tables, YAML mapping, idempotent by `LEGACY_REF`, closed-as-`NOT_APPLICABLE`, original-anchor SLA, author snapshots, attachments through the synchronous scan, reconciliation report, `importer` profile as a `Job`. **Accept:** synthetic extract reconciles 100 %; re-run is a no-op. |
| **WP-27** | Preferences, shortcuts, i18n | S | WP-05 | Shortcut toggle, `/` and `N`, externalised strings, time-zone rendering. |

### Phase 6 — Hardening and conformance (3 weeks)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-28** | Authenticated DAST and security E2E | M | all | Playwright-exported dev-realm session → ZAP full + API scan on `main`; security E2E (CSP, CSRF, 404-not-403 enumeration test, restricted leak, upload abuse, forwarded-header spoof). **Accept:** 0 Medium+; Sonar gate A; Dependency-Check clean. |
| **WP-29** | Section 508 conformance pass and ACR | L dev + 3-week tester window | all | Trusted Tester v5 pass by the named certified tester (NVDA+Firefox, JAWS+Chrome, VoiceOver+Safari; zoom; forced-colors), developer remediation, ACR (VPAT 2.5) + manual test matrix in `accessibility/`. **Accept:** no "Does Not Support"; dated tickets for any "Partially Supports". |
| **WP-30** | Performance, restore drill, runbook, evidence pack, 1.0.0 | M | all | k6 at 50k/500k seed, pool sizing, restore drill, `docs/RUNBOOK.md`, release workflow + evidence pack, `CHANGELOG`, signed tag. |

### v1.1 backlog (explicitly deferred)
PDF export via `openhtmltopdf` + veraPDF (ADR-010) · vendor search adapters · SAML2 · WAR packaging · Helm chart · email-template editor · step-up re-authentication · importer mapping DSL beyond fixed YAML · per-case subscribe · business-day SLA calendars · structured parties · bulk reassign · Modulith event registry · additional UI languages · virtual threads on Java 25.

---

## Part D — The first twelve pull requests (Phase 1 in order)

1. **`build: project scaffold`** — `pom.xml`, wrapper, `.editorconfig`, `TrailkeepApplication`, `application.yml`, management-port health. (WP-01; size-exempt)
2. **`build: static analysis gates`** — Spotless, Checkstyle, Error Prone + NullAway, SpotBugs/FindSecBugs, JaCoCo, header check, third-party report; planted violations removed within the PR history. (WP-01; size-exempt)
3. **`ci: fork-safe pipeline`** — `ci.yml`, `codeql.yml`, `scorecard.yml`, `dependabot.yml`, Semgrep. (WP-02)
4. **`docs: ADR-001…010`**. (WP-01)
5. **`db: migration foundation and three-image validation`** — locations, placeholders, `initSql`, lint test, Testcontainers PG + Oracle xe 21 + free 23, `validate` + metadata-diff, pinned dialect. (WP-03a)
6. **`db: V1 identity, V2 admin, V3 audit, vendor grants`**. (WP-03a/b; size-exempt)
7. **`arch: Modulith structure and rules`**. (WP-07)
8. **`security: OIDC login, roles, disabled users`**. (WP-04a)
9. **`web: shell, CSP, htmx config and contract`**. (WP-05)
10. **`security: JDBC sessions, lifetime, logout propagation`**. (WP-04b)
11. **`audit: recorder, HMAC redaction, MDC`**. (WP-06)
12. **`ops: logging, metrics, health groups, UBI image, kustomize base`** and **`ci: main and nightly gates`** (`main.yml`: Sonar, Dependency-Check with NVD key, Trivy/Dockle; `nightly.yml`: Oracle matrix; ZAP arrives with WP-28). (WP-08, WP-02)

---

## Part E — Definition of Done (per PR)

- Fork-safe pipeline green; on `main` the full set green after merge; a red nightly Oracle run blocks the next merge until fixed.
- Unit and slice tests for new logic; an `*IT` for anything touching SQL, security or htmx behaviour.
- Authorization: every new mutating endpoint has a test for the allowed role and the nearest disallowed role (403), and for an unreadable case (404).
- Audit: every new state change writes an audit event, with a test.
- Accessibility: new or changed screen passes axe with 0 violations; a keyboard-only walk is recorded in the PR description.
- One versioned migration per PR, portable (lint test), shipped with its entity change; grants file may change alongside.
- No new dependency without a line explaining why and its license.
- Javadoc on module API changes; ADR if a Part A decision changed; `CHANGELOG` entry.
- SPDX header on every new file; DCO sign-off; conventional commit subject; ≤ 400 net production lines unless marked size-exempt.

---

## Part F — Re-baselined schedule

Two developers, one part-time UX/accessibility/QA, a **named certified Trusted Tester for the Phase 6 window**, security reviewer on call. Two-week sprints.

| Phase | Weeks | Work packages |
|---|---|---|
| 0 Discovery | 2 | Oracle version + Flyway/Liquibase decision, IdP, SMTP, scanner-of-record, hosting, legacy export sample (private), threat model v0 |
| 1 Skeleton | 4 | WP-01, 02, 03a/b, 04a/b, 05, 06, 07, 08 (two parallel chains: 03a→03b→06→07 and 04a→04b→05; 08 alongside) |
| 2 Cases | 5 | WP-09…16 |
| 3 Closure and email | 3 | WP-17, 18, 19 |
| 4 SLA and aging | 3 | WP-21, 17b, 20, 22, 23 |
| 5 Admin, retention, importer | 3 | WP-24…27 |
| 6 Hardening and conformance | 3 | WP-28…30 (tester window runs the full 3 weeks) |
| 7 Go-live and hypercare | 2 | cut-over, first automated report, hypercare |
| **Total** | **≈ 25 weeks** | with a third developer from Phase 2: ≈ 18 weeks |

Pilot option (Phases 0–4, one team, open items imported by hand): ≈ 17 weeks for two developers.

---

## Part G — Check placement (single source of truth for CI)

| Check | Fork PR | `main` (after merge) | Nightly | Release tag |
|---|:-:|:-:|:-:|:-:|
| Compile (`-Werror`), Spotless, Checkstyle, Error Prone/NullAway, SPDX headers, license allow-list | ✓ | ✓ | ✓ | ✓ |
| Unit + slice tests, JaCoCo gates | ✓ | ✓ | ✓ | ✓ |
| PostgreSQL integration tests (`*IT`) incl. two-vendor `validate` on PG | ✓ | ✓ | ✓ | ✓ |
| Oracle integration tests (xe 21 + free 23) | – | ✓ | ✓ | ✓ |
| CodeQL, Semgrep (`ci` + rule tests), SpotBugs/FindSecBugs, gitleaks | ✓ | ✓ | ✓ | ✓ |
| Playwright E2E + axe + Nu validator | ✓ | ✓ | ✓ | ✓ |
| SonarCloud quality gate, Dependency-Check (NVD key) | – | ✓ | ✓ | ✓ |
| Container build, Trivy, Dockle, kube-linter | – | ✓ | ✓ | ✓ |
| ZAP authenticated full + API scan | – | – | ✓ | ✓ |
| k6 performance at 50k/500k seed | – | – | ✓ | ✓ |
| OpenSSF Scorecard | – | ✓ (weekly) | – | – |
| SBOM, cosign signature, SLSA provenance, evidence pack | – | – | – | ✓ |
| veraPDF (v1.1 only) | – | – | ✓ | ✓ |

---

## Appendix — ADR index (created in PR 4)

| ADR | Decision |
|---|---|
| 0001 | Spring Boot current 4.x only; Java 21 compile target; no 3.x compatibility |
| 0002 | Modular monolith with Spring Modulith; module list and dependency rules |
| 0003 | Schema migration tool: Flyway Community with Liquibase as the pre-approved fallback; SQL kept tool-neutral; Oracle test matrix xe 21 + free 23 |
| 0004 | Two-vendor persistence rules: no `@Lob`, `Length.LONG32`, chunked binary content via JDBC DAO, `BIT` booleans, dialect pinned by persistence properties, `validate` + metadata-diff |
| 0005 | `CaseScope` as the exported access contract; `@CaseRooted` repositories take a scope; 404 for unreadable cases; `CaseScope.system()` for jobs |
| 0006 | `SearchGateway`; atomic `CASE_SEARCH` side table; LIKE adapter in v1, vendor adapters in v1.1 |
| 0007 | SLA state in `CASE_SLA`, one anchor rule, chunked evaluation under ShedLock, `SLA_CYCLE` per reopen |
| 0008 | Spring Session JDBC with JSON attributes and no persisted authorities; custom `JdbcOidcSessionRegistry`; no authorized-client store; no step-up in v1 |
| 0009 | Server-rendered UI: Thymeleaf + htmx (fixed config) + Bootstrap CSS only; exact CSP; native `<dialog>`; JavaScript required |
| 0010 | Reports: HTML + CSV in v1; tagged PDF via `openhtmltopdf` + veraPDF in v1.1 |
