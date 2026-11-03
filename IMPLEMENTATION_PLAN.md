# Trailkeep — Implementation Plan

_Audit of the build plan, the decisions that came out of it, the engineering standards the code will be held to, and the work packages in build order._
_Version 0.3 — 2026-11-03. Where this document and BUILD_PLAN.md disagree, this document wins; BUILD_PLAN.md v0.5 carries the same corrections in place._

The audience for the code is experienced enterprise Java developers. Every decision below was made with one question: would a senior Spring / Hibernate / Oracle engineer sign off on it without a second round?

**Audit trail.** Three rounds of independent hostile review, each by a reviewer who had not seen the previous reasoning: round 1 → 29 findings (A.1–A.3), round 2 → 23 (A.4, with affected earlier rows amended and marked *(amended r2)*), round 3 → 10 material + nits (A.5, rows marked *(amended r3)*). The loop was capped at three rounds by the sponsor; round 3's findings are remediated here without a fourth review, so the next independent read of these documents is the deploying organization's own.

---

## Part A — Audit: findings and decisions

Each row is a decision, not a suggestion; "ADR" marks the ones that get an Architecture Decision Record in `adr/` with the first code PR.

### A.1 Blockers — the plan described mechanisms that could not work as written

| # | Finding | Decision | ADR |
|---|---|---|---|
| 1 | **Migration tool vs. Oracle version.** | Phase 0 confirms the Oracle version and tests Flyway Community (with `flyway-database-oracle`) against it on day one; **Liquibase is the pre-approved fallback**; SQL stays tool-neutral. The Oracle matrix runs `gvenzl/oracle-xe:21-slim` (closest public image to a 19c target) and `gvenzl/oracle-free:23-slim`; the deploying organization runs `-Poracle-it` against its real version in Phase 1. | ADR-003 |
| 2 | **`@Lob` is not portable** (PostgreSQL `oid`). | No `@Lob` (ArchUnit). Long text `@Column(length = Length.LONG32)`. Binary never on an entity (#3). `hibernate.hbm2ddl.auto=validate` on PostgreSQL every PR and on the Oracle matrix on `main`; because `validate` checks only presence and type family, a **metadata-diff test** (JPA metamodel vs. `DatabaseMetaData`: nullability, lengths) runs alongside. *(amended r3)* Both are meaningful only for tables that have entities; the foundation migration lands **with** its entities (Part D PR 6) so the check is never vacuous. | ADR-004 |
| 3 | **Attachment bytes.** pgjdbc materialises `BYTEA`; Oracle BLOB streams need an open connection; the two-replica scan poller had no claim semantics. *(amended r3)* The upload flow contradicted itself on infected files (rejected vs. quarantined). | Content as **1 MiB chunk rows** `CASE_ATTACHMENT_CHUNK (ATTACHMENT_ID, SEQ, CHUNK ${bytes})` via `AttachmentContentJdbcDao`; download = `StreamingResponseBody` running the DAO inside its own `TransactionTemplate`, **one chunk row per query by `SEQ`** (so neither driver materialises more than 1 MiB), bounded by a download semaphore. Upload = multipart to a temp file on an `emptyDir`, Tika + size + hash, then **synchronous ClamAV INSTREAM scan of the temp file before persistence**; clamd unavailable ⇒ upload refused. **Infected files are persisted as `QUARANTINED`** (bytes retained as evidence, never downloadable, never served inline) and only a Compliance Admin may **Release** them with a reason; scan states are `CLEAN | QUARANTINED | RELEASED` (no `PENDING`: nothing is ever stored unscanned). Caps: `multipart.max-file-size` and `max-request-size`, `clamd.conf` `StreamMaxLength`/`MaxFileSize`/`MaxScanSize`. | ADR-004 |
| 4 | **Booleans and dialect detection.** | Plain `boolean`; `hibernate.type.preferred_boolean_jdbc_type=BIT`; dialect fixed by `jakarta.persistence.database-product-name` + `database-major-version` + `database-minor-version`. *(amended r3)* These **override** the values Hibernate detects (they do not skip detection — that would need `hibernate.boot.allow_jdbc_metadata_access=false`); the point is that DDL expectations such as `BOOLEAN` and fetch-first do not change with the connected server. | ADR-004 |
| 5 | **Appendix A DDL was not valid Oracle.** | Placeholders `${id} ${int} ${bool} ${clob} ${bytes} ${tstz}`; `NLS_LENGTH_SEMANTICS=CHAR` via `initSql`; free-text `VARCHAR` capped at **1000** (Oracle's 4000-byte ceiling survives CHAR semantics); migration-lint test (identifiers ≤ 30, reserved words, ANSI types outside placeholders, `VARCHAR` > 1000). | — |
| 6 | **Restricted-case enforcement.** *(amended r3)* "Every query method takes a `CaseScope`" could not hold for inherited `JpaRepository` methods or derived queries; the contract type was owned by a Phase 2 module that Phase 1 code needed; non-case audit events were lost under a case-scoped filter. | **`CaseScope` is an exported contract living in `shared`** (type, `@CaseRooted`, `@CaseAccessPath`, `CaseScope.system()`), delivered in WP-07; `casefile` provides the principal-backed implementation in WP-11. Case-rooted repositories **extend `Repository<T, ID>` (not `JpaRepository`)** and expose only custom-fragment methods implemented with Criteria/`Specification` that apply `scope.toPredicate()`; single-entity loads are `findOne(scope, caseNumber)`; there are no inherited or derived query methods to exempt, so the reflection test enumerates every declared method. ArchUnit: `@CaseRooted` repositories are injected only into `@CaseAccessPath` classes. Audit events with a null `CASE_ID` (admin, login, export) are reachable only through `CaseScope.all(principal)`, which exists for Compliance Admins and `system()`. **Unreadable cases answer 404**, exactly like absent ones; 403 is reserved for a denied action on a readable case. | ADR-005 |
| 7 | **Search.** *(amended r3)* A `String` bind over 4000 bytes inside an Oracle expression raises ORA-01461. | `SearchGateway`; `CASE_SEARCH` side table (no `@Version`), maintained atomically: comment append `UPDATE … SET SEARCH_TEXT = SEARCH_TEXT \|\| :fragment` with the fragment **bound as a CLOB/character stream** (valid on both drivers), rebuild on edit after `SELECT … FOR UPDATE` on the one row. Comment body cap **20 000 characters**. Subject excluded, description included (same readers, same grants and encryption). Portable `LIKE` adapter in v1, vendor adapters v1.1. | ADR-006 |
| 8 | **SLA evaluator and anchor.** | `CASE_SLA` side table; indexes `(SLA_STATE, SLA_DUE_AT)`, `(SLA_STATE, AT_RISK_AT)`; nullable dates with a CHECK for `NOT_APPLICABLE`. **One anchor rule:** create = policy anchor (`IDENTIFIED_ON` at end of day in `app.timezone`, or `OPENED_AT`); reopen = reopen instant, `SLA_CYCLE += 1`. Written by `casefile`, `sla`, `closure` services. Chunked evaluation under ShedLock. | ADR-007 |
| 9 | **Sessions + OIDC.** *(amended r3)* The back-channel logout handler POSTs the app's own `/logout` with the session cookie **and the CSRF header captured at login**; the registry table had nowhere to keep it, and the handler's default cookie name is `JSESSIONID`. | Spring Session JDBC (vendor schemas, customizer, JSON attributes with Spring Security's Jackson mixins); **authorities not persisted**, permissions rebuilt per request. No authorized-client store. RP-initiated logout from the shared `OidcUser`. Back-channel logout through a **custom `JdbcOidcSessionRegistry`** over `OIDC_SESSION (SID PK, SESSION_ID, PRINCIPAL, AUTHORITIES_JSON)` that rebuilds `OidcSessionInformation` including the CSRF header map; `OidcBackChannelLogoutHandler` with `sessionCookieName("SESSION")` and `logoutUri` on the pod's loopback; a daily cleanup removes `OIDC_SESSION` rows whose session no longer exists. Absolute 8-hour lifetime by filter. Disable ⇒ sessions deleted, login refused. Step-up re-auth cut for scope (mechanism exists). | ADR-008 |
| 10 | **CSP / htmx / Bootstrap.** | Exact CSP; nonce filter; fixed htmx config (`allowEval=false`, `allowScriptTags=false`, `selfRequestsOnly=true`, `includeIndicatorStyles=false`, `historyCacheSize=0`, `refreshOnHistoryMiss=true`); Bootstrap CSS only; one `app.js` on native `<dialog>`. | ADR-009 |
| 11 | **Admin-editable templates.** | None in v1; fixed Thymeleaf files; v1.1 editor uses non-evaluating substitution. | — |
| 12 | **Spring Boot 3.5 fallback.** | Current 4.x only. | ADR-001 |
| 13 | **PDF/UA with raw PDFBox.** | PDF export v1.1 via `openhtmltopdf` + veraPDF; v1 = CSV + accessible HTML. | ADR-010 |

### A.2 Significant — correctness and data-model gaps

| # | Finding | Decision |
|---|---|---|
| 14 | **Outbox claim.** A row-limited JPA `PESSIMISTIC_WRITE` is broken on Oracle (follow-on locking). *(amended r3)* "Never double-send" overstated what an outbox can promise. | Native two-step claim in `OutboxJdbcDao`: candidate ids (`FETCH FIRST 50 ROWS ONLY`, no lock) then `… WHERE ID IN (:ids) AND STATUS='PENDING' FOR UPDATE SKIP LOCKED`; process only rows step 2 returned; mark `SENDING` + `LOCKED_UNTIL`; commit before SMTP; mark `SENT`/`FAILED` in a new transaction; reaper. **Delivery is at-least-once**: a crash between SMTP success and the `SENT` update lets the reaper re-send once; the test asserts two concurrent dispatchers never send the same row *concurrently* and that the normal path sends exactly once. `ALERT_LEDGER` unique key; `RECIPIENT` column; `REPORT_RUN` reference; test on both vendors. |
| 15 | **Case numbers.** | One sequence, gaps allowed, `PREFIX-YYYY-NNNNNN`, `NEXTVAL` through `Dialect.getSequenceSupport()`. |
| 16 | **Audit capture and redaction.** | Service-layer snapshot diff; `description`/`subject` as HMAC-SHA-256 + length; `CASE_ID` nullable + indexed; `VIEW_RESTRICTED` once per full page view/download; one `InstantSource`. |
| 17 | **Fetch strategy.** | `open-in-view=false`; projections; `@EntityGraph`; `default_batch_fetch_size=50`; query-count assertions. |
| 18 | **Permission model.** *(amended r3)* The approver list could still offer the owner; §3.1 still allowed a System Admin to be granted Reader; the IdP-claim path had no rule. | Authority = team roles + global roles; `CasePermissionService` decision table from a CSV. **Approver list = the team's Managers minus the requester minus the owner, else Compliance Admins.** Owner must hold **Analyst or Manager** in the case's team. Reassign blocked while `PENDING_CLOSURE`; Manager+ may reassign the approver. Only owner or Manager+ may restrict, and the actor keeps read. A System Admin **cannot hold team roles, including Reader**; when IdP claims add `SYSTEM_ADMIN` to a user who holds team roles, the global role is **not applied**, the conflict is audited and shown on the System screen, and another System Admin removes the team roles first (login itself is not refused). Audit-for-all = Compliance Admin only. Disable ⇒ sessions purged. |
| 19 | **Data model.** *(amended r3)* Purge used a sentinel string. | Tables and columns per BUILD_PLAN §5.1; `CASE_RECORD.PURGED_AT` marks a tombstone (description/subject nulled, not sentinel text). |
| 20 | **Grants.** | Vendor `R__grants.sql` with placeholders; DELETE carve-outs enumerated; purge user owns case-data deletion. |
| 21 | **Tika and images.** | `tika-core` container types + extension cross-check; image dimensions capped. |
| 22 | **`th:utext`.** | `SafeHtml` + `tk:safe`. |
| 23 | **htmx + Spring Security.** | `HX-Redirect` on 401/403; fragment contract 200/422/409/5xx. |
| 24 | **Transactions.** | Audit/outbox/ledger synchronous in the originating transaction; `BEFORE_COMMIT` for cross-module reactions; no network I/O in a transaction. |

### A.3 Over-promises corrected

| # | Finding | Decision |
|---|---|---|
| 25 | **CI placement.** | Part G is the single source of truth. Oracle IT on `main` and nightly. *(amended r3)* SBOM is generated on `main` for scanning and signed/published on release. |
| 26 | **Accessibility.** | Vetted component list; native `<dialog>`; JavaScript required; 3-week conformance window with a named certified tester. |
| 27 | **Estimate.** | ≈ 25 weeks for two developers (Part F). |
| 28 | **Licenses.** | ojdbc redistributed in the image under FUTC; two license plugins named; Logback under EPL. |
| 29 | **Small items.** *(amended r3)* `native` forward headers alone still trusts every RFC 1918 peer through the `internal-proxies` default, so a client on the intranet could spoof the audited IP. | `server.forward-headers-strategy=native`, **`server.tomcat.remoteip.internal-proxies` set to the router/ingress address pattern (not the Spring Boot default) and `trusted-proxies` to the same** — both are regular expressions, not CIDRs; the spoof test connects from a private-range address that is *not* the router and asserts the client-supplied `X-Forwarded-For` is ignored. Case number is the URL key (404 rule). Report scheduling via `REPORT_SCHEDULE`. Bucket4j per replica. Java 21, platform threads. `spring.task.scheduling.pool.size=4`. |

### A.4 Round 2 — decisions

| # | Finding | Decision |
|---|---|---|
| 30 | Per-pod evaluator health flaps and can restart healthy pods. | Cluster-level indicator from `JOB_RUN`, excluded from probe groups, exported as a metric. |
| 31 | Request Update to a user who cannot read the case. | Recipient must satisfy `CaseScope.canRead`; combobox filtered server-side, service re-checks. |
| 32 | Reminders had no owner. | Hourly `reminders` tick (WP-17b) for update requests, pending closures and overdue repeats, via ledger + outbox. |
| 33 | Archive and purge undefined. *(amended r3)* | `CLOSED → ARCHIVED` after `RETENTION_YEARS` (`ARCHIVED_AT`, `PURGE_AFTER`); archived = read-only, hidden by default, not reopenable; index `(STATUS, CLOSED_AT)` for the archive scan and `(STATUS, PURGE_AFTER)` for purge. Purge deletes comments, pins, attachments + chunks, search text, update and closure requests, nulls description/subject and sets `PURGED_AT`; audit and ledger retained. `purge`/`importer` profiles run `web-application-type=none`. |
| 34 | Importer idempotency. | `UNIQUE (LEGACY_REF)`, `IMPORT_RUN`, comment author snapshot. |
| 35 | Appendix A residuals. *(amended r3)* The FK policy was stated and then violated by `AUDIT_EVENT`. | Indexes on every FK column; range CHECKs; **FK policy: every column referencing a user or case declares a FK and is indexed, except `AUDIT_EVENT`, which is deliberately decoupled** (no FKs on `ACTOR_ID`/`CASE_ID`, both indexed) so the audit trail can outlive and precede the rows it describes; outcome validity is a service rule. |
| 36 | Stale text. | Corrected; this register is the checklist. |
| 37 | Work-package defects. *(amended r3)* | Dependencies fixed; **migrations land with their entities**: `V1__identity` ships in PR 6 with the user/team/role entities, `V2__audit` with WP-06, `V3__admin` with WP-10, `V4__case` with WP-11, `V5__closure` with WP-19. Part E: PostgreSQL per PR; Oracle green on `main`; a red nightly blocks the next merge. |
| 38 | Part B omissions. | Added (see Part B). |
| 39 | Dev realm. | Two teams × (Reader, Analyst, 2 Managers) + Compliance Admin + System Admin + disabled user. |

### A.5 Round 3 — decisions (loop closed)

| # | Finding | Decision |
|---|---|---|
| 40 | `internal-proxies` default defeats the trusted-proxy fix. | See #29 *(amended r3)*. |
| 41 | Infected-upload semantics contradicted each other. | Quarantine-and-retain, see #3 *(amended r3)*; `PENDING` removed; WP-14 acceptance rewritten. |
| 42 | `OIDC_SESSION` could not drive back-channel logout. | See #9 *(amended r3)*. |
| 43 | `CaseScope` contract not implementable or sequenced. | See #6 *(amended r3)*; stale "choke point" and "403 on cross-team" sentences removed from BUILD_PLAN. |
| 44 | CLOB append fails on Oracle for long binds. | See #7 *(amended r3)*; Oracle IT appends a > 4000-byte comment. |
| 45 | Appendix A violated its FK policy. | See #35 *(amended r3)*; `IX_AUDIT_ACTOR` added; migration numbering corrected. |
| 46 | Role-model contradictions. | See #18 *(amended r3)*. |
| 47 | SLA anchor stated two ways. | The stale `opened_at + target_days` bullet deleted; one anchor rule stands. |
| 48 | CI/phase text disagreed with Part G / Part C. | §12 intro, §13 Integration, §7.2 Tests, §7.1 diagram (no scan poller), §17 Phase 3/4 deliverables, Appendix B aligned. |
| 49 | PR 6 broke the migration-with-entity rule. | See #37 *(amended r3)*. |
| Nits | Dialect-pin wording; chunk reads per `SEQ`; at-least-once wording; `OIDC_SESSION` cleanup; loopback is not a NetworkPolicy matter; `max-request-size`; `PURGED_AT`; "no GET changes domain state"; owner = Analyst or Manager; `prod` forbids `create`/`update`; §7.4 comments; `-Xlint:all,-processing`; WP-02 depends on WP-03a; archive index; CONTEXT key files. | All applied. |

---

## Part B — Engineering standards (what reviewers will judge)

Enforced by the build where a tool exists, by the PR checklist where one does not.

**Language and framework.** Java 21 (compile target); CI also runs the suite on the current Java LTS. Spring Boot current 4.x. JSpecify: every `package-info.java` is `@NullMarked`; NullAway via Error Prone fails the build on a nullness violation. **No Lombok.** Records for commands, read models and value objects; constructor injection, no field injection. Sealed interfaces for multi-outcome results. Immutable collections returned.

**Formatting and static checks.** Spotless (palantir-java-format); Checkstyle (custom ruleset from Google's: 120 columns, no star/unused imports, braces always, method ≤ 60 lines, class ≤ 400 lines, cyclomatic complexity ≤ 10); `-Xlint:all,-processing -Werror`; Error Prone + NullAway; SpotBugs + FindSecBugs at max effort (`@SuppressFBWarnings(justification=…)` only); ArchUnit in `src/test/java/.../architecture`; Semgrep runs in CI (`semgrep ci`), its custom rules tested with `semgrep --test`.

**Layering and modules.** Spring Modulith `ApplicationModules.verify()` as a test; modules = `shared, security, audit, user, casefile, closure, updaterequest, sla, document, notification, report, retention, importer, web`. A module exposes a `*Service` interface and record types in its root package; everything else package-private. `shared` owns the `CaseScope` contract, `@CaseRooted`, `@CaseAccessPath` and `CaseScope.system()`/`all()`; `casefile` supplies the principal-backed implementation. Case-rooted repositories extend `Repository<T, ID>`, expose only custom-fragment methods that apply `scope.toPredicate()`, and are injected only into `@CaseAccessPath` classes (ArchUnit).

**Persistence.** The migration tool owns the schema; Hibernate `validate` plus the metadata-diff test run on both vendors. Entities: `Long` id from pooled sequences (`allocationSize=50`), `@Version int version` on user-edited aggregates only, `Instant` timestamps, `@Enumerated(EnumType.STRING)` always, `@Column(length=Length.LONG32)` for long text, no `@Lob`, no `byte[]`, `FetchType.LAZY` on every association, `@Immutable` on `CaseComment`/`AuditEvent`/`AlertLedger`, id-based null-safe `equals/hashCode` (no collections), `toString` never includes description/subject. `@EntityGraph` or projection per read use-case; `open-in-view=false`; `default_batch_fetch_size=50`. Native SQL only inside `*JdbcDao` classes in `document` (chunks, one row per query), `notification` (claim), `casefile` (search `LIKE`, CLOB-bound search append, sequence `NEXTVAL` via `Dialect.getSequenceSupport()`), each with a comment stating why JPA cannot express it. No entity crosses a module boundary or reaches a template.

**Transactions.** `@Transactional` on service implementations only; read paths `readOnly=true`; network I/O never inside a transaction; cross-module reactions `@TransactionalEventListener(BEFORE_COMMIT)`; jobs chunked, one transaction per chunk; streaming downloads open their own `TransactionTemplate` inside the response callback.

**Web.** Thymeleaf templates under `templates/{module}/`, fragments under `templates/{module}/fragments/`, one layout; commands are records with Jakarta Validation bound with `@Valid`; one `@ControllerAdvice` maps `DomainException` → `NotFound` (404, also for unreadable cases), `Forbidden` (403, denied action on a readable case), `Conflict` (409), `Invalid` (422) to the fragment contract and to full pages. Case number is the only identifier in URLs. Sort/filter parameters parsed into allow-listed enums; saved-view filters validated by binding to the typed filter record. Static assets content-hashed.

**Security conventions.** Authorization decisions live in `CasePermissionService` and `CaseScope`; controllers check only `isAuthenticated()`. All mutations are `POST` with CSRF; **no `GET` changes domain state** (audit rows for restricted views are the one write a `GET` makes). Output escaped by default; `tk:safe` is the only unescaped path. Authorities never persisted in the session. `server.forward-headers-strategy=native` with `internal-proxies` and `trusted-proxies` both set to the router pattern.

**Logging.** Logback + logstash JSON encoder. MDC keys `requestId`, `userId`, `caseNumber`, `module`. INFO business events, DEBUG flow, WARN recoverable, ERROR once with stack trace. Never log description, subject, restricted attachment names, or email addresses. Audit is a table, not a log line.

**Configuration.** `@ConfigurationProperties` records under `app.*` with `@Validated`; `prod` fails fast on missing required properties and forbids `ddl-auto=create|update` (the app runs `validate`); profiles `local`, `test`, `oracle-test`, `prod`, `purge`, `importer` (the last two are `web-application-type=none`). Hikari pool size documented per replica against the Oracle session limit; `spring.task.scheduling.pool.size=4`; dialect fixed by the three `jakarta.persistence.database-*` properties; `multipart.max-file-size` and `max-request-size` both set.

**Testing.** Surefire `*Test`; Failsafe `*IT`. Behaviour-named tests. Testcontainers `@ServiceConnection`, reuse locally. JaCoCo 80 % line / 70 % branch overall, 90 % line in `sla`, `closure`, `security`, per package. Playwright page objects under `e2e/pages`. Builders, not SQL dumps. No real network.

**Pull requests.** ≤ 400 net production lines (PRs 1, 2 and 6 are size-exempt and say so); one versioned migration per PR shipped with its entity change and test (the repeatable grants file may change alongside; PR 5 ships no migration, only the tooling); template checklist (scrub, authorization test, audit event, migration portable, accessibility criteria, SPDX header, ADR if a decision changed); squash-merge, conventional commit subject, DCO sign-off. ADRs in `adr/NNNN-title.md` (MADR). **Review:** every PR gets an independent review pass before merge — a separate reviewing session, on a different model from the author when AI-assisted — with findings recorded in the PR; GitHub's branch rules require status checks, linear history and no force-push but zero approving reviews, because a single maintainer cannot approve their own PR.

**Documentation in code.** Javadoc on module API types/methods; `package-info.java` per module stating responsibility and permitted callers.

---

## Part C — Work packages

Sizes: **S** ≤ 2 developer-days, **M** 3–5, **L** 6–10. "Depends" = merged first. Every WP ends with tests green on PostgreSQL in CI; anything touching SQL is also green on the Oracle matrix on `main`.

### Phase 1 — Walking skeleton (4 weeks)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-01** | Build and repository scaffold | M | — | `pom.xml` (enforcer, Spotless, Checkstyle, Error Prone + NullAway, SpotBugs/FindSecBugs, JaCoCo, mycila headers, mojohaus third-party + allow-list, CycloneDX), `mvnw`, `.editorconfig`, `.gitattributes`, `CODEOWNERS`, PR/issue templates, `adr/0001..0010`, `TrailkeepApplication` with health on the management port. **Accept:** `./mvnw verify` green on an empty app; each Maven-wired scanner shown failing on a planted violation. |
| **WP-02** | CI pipeline per Part G | M | WP-01, WP-03a (for the Oracle job) | `ci.yml` (fork-safe), `main.yml`, `nightly.yml`, `release.yml`, `codeql.yml`, `scorecard.yml`, `dependabot.yml`, Semgrep (`ci` + `--test`), SHA-pinned actions, least-privilege token. **Accept:** a fork PR passes without secrets; `main` runs the full set; nightly runs the Oracle matrix. |
| **WP-03a** | Migration tooling and two-vendor validation harness | M | WP-01 | Locations, placeholders, `initSql`, migration-lint test, Testcontainers PostgreSQL + Oracle (xe 21, free 23) via `@ServiceConnection`, `validate` + metadata-diff harness, dialect properties, Hibernate settings, `InstantSource`. No migration of its own. |
| **WP-03b** | Identity schema with entities, and grants | M | WP-03a | `V1__identity.sql` **with** `AppUser`, `Team`, `AppUserTeam`, `AppUserGlobalRole` entities; vendor `R__grants.sql` with placeholders and a negative IT per forbidden statement. **Accept:** migrates and validates (incl. metadata-diff) on all three images. |
| **WP-04a** | OIDC login and roles | M | WP-03b | Keycloak realm (#39), login, `AppUser` upsert, global-role mapping with the System-Admin-vs-team-role conflict rule, disabled-user refusal, `@WithAppUser`. |
| **WP-04b** | Sessions across replicas | M | WP-04a | Spring Session JDBC (vendor schemas, customizer, JSON + mixins, no authorities), lifetime filter, idle timeout + `/session/extend`, RP-initiated logout, `JdbcOidcSessionRegistry` (`OIDC_SESSION` incl. `AUTHORITIES_JSON`) + back-channel logout with `sessionCookieName("SESSION")` and loopback `logoutUri`, registry cleanup, disable ⇒ session purge. **Accept:** two app instances share a session; a back-channel logout POST to the loopback returns 2xx/3xx (not 403) and ends the session on both. |
| **WP-05** | Web shell, CSP, htmx contract | M | WP-04a | Layout, nonce filter, exact CSP, vendored htmx with the fixed config (presence test), `app.css`/`app.js`, error pages (404 for unreadable), `@ControllerAdvice`, asset versioning, Bootstrap CSS tokens. **Accept:** axe 0 violations on shell and error pages; no CSP violation in the console on load. |
| **WP-06** | Audit module | M | WP-03b, 07 | `V2__audit.sql` with `AuditEvent` (decoupled, indexed), `AuditRecorder`, snapshot diff with HMAC redaction, MDC/request-id filter; repository is `@CaseRooted` and honours `CaseScope.all()`/`system()`. **Accept:** rollback test; redaction test; non-case events visible through `all()`. |
| **WP-07** | Modulith structure, `CaseScope` contract, architecture rules | M | WP-01 | Module skeletons with `package-info`; **`shared.access`: `CaseScope` interface, `@CaseRooted`, `@CaseAccessPath`, `CaseScope.system()`/`all()`**; `ApplicationModules.verify()`; ArchUnit rules (`@CaseRooted` repositories extend `Repository`, are injected only into `@CaseAccessPath`, no `@Lob`, repositories package-private, no entity in `web`, `@Enumerated STRING`); Semgrep rules with `--test` cases. |
| **WP-08** | Observability, container, deploy base | M | WP-01 | JSON logging + MDC, Micrometer, management port, health groups (cluster indicators excluded from probes), UBI 9 image (non-root, read-only FS, `emptyDir` for tmp/multipart/Tomcat work dir), kustomize base (Deployment, Service, Route, NetworkPolicy, SCC, Secrets, `CronJob`/`Job` templates), `native` forward headers with `internal-proxies` + `trusted-proxies` = router pattern and the spoof test from a non-router private address, Trivy/Dockle, SBOM + cosign + SLSA on tags. |

Phase 1 exit: WP-01…08 merged; every scanner green; login works against a real IdP in DEV; ACR draft for the shell.

### Phase 2 — Cases (5 weeks)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-09** | Users, teams, team roles (admin) | M | WP-04b, 06 | Team/membership admin, `CasePermissionService` decision table + CSV exhaustive test (owner is Analyst/Manager in team; System Admin holds no team roles; approver list rule), per-request `UserPermissions`. |
| **WP-10** | Case types, outcomes, SLA policies (admin) | S | WP-09 | `V3__admin.sql` with its entities; CRUD with range validation; seed YAML for `local`. |
| **WP-11** | Case create and view; principal `CaseScope` | L | WP-09, 10 | `V4__case.sql` (`CASE_RECORD`, `CASE_SLA`, `CASE_SEARCH`), numbering, one-page create (PHI heuristic), case page, **principal-backed `CaseScope` implementation** and the first `@CaseRooted` repositories as `Repository` fragments with `findOne(scope, caseNumber)`, 404 rule, SLA row initialisation, audit, `@EntityGraph`, projections. **Accept:** golden path 1; reflection-driven invisibility test over every declared method of every `@CaseRooted` repository (absent and unreadable indistinguishable); query-count assertion. |
| **WP-12** | Edit, reassign, restrict | M | WP-11 | Optimistic locking (409 → Reload), reassign (blocked while pending closure; owner-in-team), restrict (actor keeps read), audit diffs. |
| **WP-13** | Comments, pins, `SafeHtml`, search text | M | WP-11 | `CASE_COMMENT` (insert-only, author snapshot, 20 000-char cap), `CASE_COMMENT_PIN`, Markdown → sanitizer → `SafeHtml`, `tk:safe`, atomic `CASE_SEARCH` append with a CLOB-bound fragment / `FOR UPDATE` rebuild. **Accept:** XSS corpus; INSERT-only negative IT; two concurrent comments both appear in `SEARCH_TEXT`; **Oracle IT appends a > 4000-byte comment**. |
| **WP-14** | Attachments | L | WP-11 | Temp-file upload on `emptyDir`, Tika + allow-list + size + hash, **synchronous ClamAV scan before persist**, `CASE_ATTACHMENT` + `CASE_ATTACHMENT_CHUNK` via JDBC DAO (one chunk per query), streamed download inside a `TransactionTemplate`, download semaphore, Compliance Admin **Release** with reason, image cap. **Accept:** EICAR ⇒ persisted `QUARANTINED`, download 404-for-unreadable/403-for-blocked, Release path works and is audited; disguised executable rejected (422); clamd down ⇒ upload refused; 8 concurrent 25 MB downloads on **PostgreSQL** with `-Xmx256m` succeed. |
| **WP-15** | Cases list, filters, search, saved views, CSV | L | WP-11, 13 | Projections, allow-listed sort, filter drawer (incl. "include archived"), `SearchGateway` + LIKE adapter via `CaseScope`, `SAVED_VIEW`, CSV with redaction and hand-rolled formula escaping, 50k seed + k6 smoke. **Accept:** p95 < 500 ms at 50k on PostgreSQL; restricted rows absent from results, counts and CSV. |
| **WP-16** | History tab | S | WP-06, 11, 14 | Case-scoped audit timeline; `VIEW_RESTRICTED` once per page view/download. |

### Phase 3 — Closure and email (3 weeks)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-17** | Outbox and dispatcher | M | WP-03b, 06, 09 | `NOTIFICATION_OUTBOX`, `OutboxJdbcDao` two-step claim, state machine, SMTP outside transactions, backoff, reaper, `USER_ALERT`, Mailpit, metrics. **Accept:** two dispatcher instances never process the same row concurrently and the normal path sends exactly once (**Oracle and PostgreSQL**); stale `SENDING` reclaimed; SMTP down ⇒ rows stay `PENDING`. |
| **WP-17b** | Reminders tick | S | WP-17, 21 | Hourly ShedLock job for update-request, pending-closure and overdue reminders via `ALERT_LEDGER`. (Lands in Phase 4.) |
| **WP-18** | Request Update | M | WP-13, 17 | `UPDATE_REQUEST`, recipient filtered by `CaseScope.canRead`, APG combobox, deep-link email, auto-resolve, "Mark answered", dashboard "Needs attention". |
| **WP-19** | Closure: request / approve / reject / reopen / reassign approver | L | WP-12, 17 | `V5__closure.sql` with `ClosureRequest`, approver rules, SoD, transitions + audit + emails, "Requires Changes", reopen (`SLA_CYCLE`), approver reassignment, pending-closure reassign block. **Accept:** golden paths 2–4; SoD tests incl. owner-is-Manager case; concurrent approve/reject yields one decision. |
| **WP-20** | Dashboard | S | WP-15, 19, 21 | KPI tiles, My cases, Needs attention — all via `CaseScope`. (Lands in Phase 4.) |

### Phase 4 — SLA and aging (3 weeks)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-21** | SLA evaluator and alerts | L | WP-11, 17 | Chunked evaluator over `CASE_SLA`, ShedLock, ledger + outbox, SLA chip/filters, anchor rules, closure finalisation, metrics, cluster-truth health indicator outside probe groups. **Accept:** fixture of 20 cases → expected transitions and exactly one email each across three runs and a restart; reopen resets per cycle. |
| **WP-22** | Aging and SLA-compliance reports (HTML + CSV) | M | WP-21 | SQL-aggregated queries, accessible HTML with print stylesheet, CSV, redaction. **Accept:** matches hand-computed fixture; axe clean. |
| **WP-23** | Scheduled report delivery | M | WP-22, 17 | `REPORT_SCHEDULE` hourly tick, `REPORT_RUN`, outbox rows referencing runs, first-cycle admins-only switch. |

### Phase 5 — Admin, retention, importer, polish (3 weeks)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-24** | Retention, archive, legal hold, purge `CronJob` | M | WP-14, 19 | Archive job (`(STATUS, CLOSED_AT)` index), `ARCHIVED` behaviour, legal hold metadata, `PURGE_AFTER`, `purge` profile as a `CronJob` with the purge credential, `PURGED_AT` tombstone, metadata-only log. **Accept:** legal hold stops purge; app credential cannot delete (negative IT). |
| **WP-25** | System screen and operational alerts | S | WP-17, 21, 26 | Job last-runs (cluster truth), outbox backlog/failures, clamd status, role-conflict list, last import run; Prometheus rules. |
| **WP-26** | Generic importer | L | WP-11, 13, 14 | `IMPORT_RUN`, staging tables, YAML mapping, idempotent by `LEGACY_REF`, closed-as-`NOT_APPLICABLE`, original-anchor SLA, author snapshots, attachments through the synchronous scan (infected ⇒ `QUARANTINED`, reported), reconciliation report, `importer` profile as a `Job`. **Accept:** synthetic extract reconciles 100 %; re-run is a no-op. |
| **WP-27** | Preferences, shortcuts, i18n | S | WP-05 | Shortcut toggle, `/` and `N`, externalised strings, time-zone rendering. |

### Phase 6 — Hardening and conformance (3 weeks)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-28** | Authenticated DAST and security E2E | M | all | Playwright-exported dev-realm session → ZAP full + API scan (nightly/release); security E2E (CSP, CSRF, 404-not-403 enumeration test, restricted leak, upload abuse, forwarded-header spoof from a non-router private address). **Accept:** 0 Medium+; Sonar gate A; Dependency-Check clean. |
| **WP-29** | Section 508 conformance pass and ACR | L dev + 3-week tester window | all | Trusted Tester v5 pass by the named certified tester; remediation; ACR (VPAT 2.5) + manual test matrix in `accessibility/`. **Accept:** no "Does Not Support"; dated tickets for any "Partially Supports". |
| **WP-30** | Performance, restore drill, runbook, evidence pack, 1.0.0 | M | all | k6 at 50k/500k seed, pool sizing, restore drill, `docs/RUNBOOK.md`, release workflow + evidence pack, `CHANGELOG`, signed tag. |

### v1.1 backlog (explicitly deferred)
PDF export via `openhtmltopdf` + veraPDF (ADR-010) · vendor search adapters · SAML2 · WAR packaging · Helm chart · email-template editor · step-up re-authentication · importer mapping DSL beyond fixed YAML · per-case subscribe · business-day SLA calendars · structured parties · bulk reassign · Modulith event registry · additional UI languages · virtual threads on Java 25.

---

## Part D — The first twelve pull requests (Phase 1 in order)

1. **`build: project scaffold`** — `pom.xml`, wrapper, `.editorconfig`, `TrailkeepApplication`, `application.yml`, management-port health. (WP-01; size-exempt)
2. **`build: static analysis gates`** — Spotless, Checkstyle, Error Prone + NullAway, SpotBugs/FindSecBugs, JaCoCo, header check, third-party report; planted violations removed within the PR history. (WP-01; size-exempt)
3. **`ci: fork-safe pipeline`** — `ci.yml`, `codeql.yml`, `scorecard.yml`, `dependabot.yml`, Semgrep. (WP-02)
4. **`docs: ADR-001…010`**. (WP-01)
5. **`db: migration tooling and three-image validation harness`** — locations, placeholders, `initSql`, lint test, Testcontainers PG + Oracle xe 21 + free 23, `validate` + metadata-diff harness, dialect properties. No migration file. (WP-03a)
6. **`db: V1 identity with entities, vendor grants`** — `V1__identity.sql` together with `AppUser`, `Team`, `AppUserTeam`, `AppUserGlobalRole`; `R__grants.sql` per vendor; validation now exercises real entities. (WP-03b; size-exempt)
7. **`arch: Modulith structure, CaseScope contract, rules`**. (WP-07)
8. **`security: OIDC login, roles, conflict rule, disabled users`**. (WP-04a)
9. **`web: shell, CSP, htmx config and contract`**. (WP-05)
10. **`security: JDBC sessions, lifetime, back-channel logout`**. (WP-04b)
11. **`audit: V2 audit with AuditEvent, recorder, HMAC redaction, MDC`**. (WP-06)
12. **`ops: logging, metrics, health groups, UBI image, kustomize base, forward headers`** and **`ci: main, nightly and release gates`**. (WP-08, WP-02)

---

## Part E — Definition of Done (per PR)

- Fork-safe pipeline green; on `main` the full set green after merge; a red nightly Oracle run blocks the next merge until fixed.
- Unit and slice tests for new logic; an `*IT` for anything touching SQL, security or htmx behaviour.
- Authorization: every new mutating endpoint has a test for the allowed role, the nearest disallowed role (403), and an unreadable case (404).
- Audit: every new state change writes an audit event, with a test.
- Accessibility: new or changed screen passes axe with 0 violations; a keyboard-only walk is recorded in the PR description.
- One versioned migration per PR, portable (lint test), shipped **with its entity change** (PR 5 is tooling only; the repeatable grants file may change alongside any migration).
- No new dependency without a line explaining why and its license.
- Javadoc on module API changes; ADR if a Part A decision changed; `CHANGELOG` entry.
- SPDX header on every new file; DCO sign-off; conventional commit subject; ≤ 400 net production lines unless marked size-exempt.

---

## Part F — Re-baselined schedule

Two developers, one part-time UX/accessibility/QA, a **named certified Trusted Tester for the Phase 6 window**, security reviewer on call. Two-week sprints.

| Phase | Weeks | Work packages |
|---|---|---|
| 0 Discovery | 2 | Oracle version + Flyway/Liquibase decision, IdP, SMTP, scanner-of-record, hosting, legacy export sample (private), threat model v0 |
| 1 Skeleton | 4 | WP-01, 02, 03a/b, 04a/b, 05, 06, 07, 08 (chains: 03a→03b→04a→04b; 07→06; 05 after 04a; 08 alongside) |
| 2 Cases | 5 | WP-09…16 (critical path 09→10→11→13→15 is tight against the window; WP-14 runs in parallel) |
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
| Compile (`-Xlint:all,-processing -Werror`), Spotless, Checkstyle, Error Prone/NullAway, SPDX headers, license allow-list | ✓ | ✓ | ✓ | ✓ |
| Unit + slice tests, JaCoCo gates | ✓ | ✓ | ✓ | ✓ |
| PostgreSQL integration tests (`*IT`) incl. `validate` + metadata-diff on PG | ✓ | ✓ | ✓ | ✓ |
| Oracle integration tests (xe 21 + free 23) | – | ✓ | ✓ | ✓ |
| CodeQL, Semgrep (`ci` + rule tests), SpotBugs/FindSecBugs, gitleaks | ✓ | ✓ | ✓ | ✓ |
| Playwright E2E + axe + Nu validator | ✓ | ✓ | ✓ | ✓ |
| SonarCloud quality gate, Dependency-Check (NVD key) | – | ✓ | ✓ | ✓ |
| CycloneDX SBOM generated (for Trivy/Dependency-Check) | – | ✓ | ✓ | ✓ |
| Container build, Trivy, Dockle, kube-linter | – | ✓ | ✓ | ✓ |
| ZAP authenticated full + API scan | – | – | ✓ | ✓ |
| k6 performance at 50k/500k seed | – | – | ✓ | ✓ |
| OpenSSF Scorecard | – | ✓ (weekly) | – | – |
| SBOM signed and published, cosign signature, SLSA provenance, evidence pack | – | – | – | ✓ |
| veraPDF (v1.1 only) | – | – | ✓ | ✓ |

---

## Appendix — ADR index (created in PR 4)

| ADR | Decision |
|---|---|
| 0001 | Spring Boot current 4.x only; Java 21 compile target; no 3.x compatibility |
| 0002 | Modular monolith with Spring Modulith; module list and dependency rules; `shared` owns cross-cutting contracts |
| 0003 | Schema migration tool: Flyway Community with Liquibase as the pre-approved fallback; SQL kept tool-neutral; Oracle test matrix xe 21 + free 23 |
| 0004 | Two-vendor persistence rules: no `@Lob`, `Length.LONG32`, chunked binary content via JDBC DAO, `BIT` booleans, dialect fixed by persistence properties, `validate` + metadata-diff, migrations land with entities |
| 0005 | `CaseScope` contract in `shared`; `@CaseRooted` repositories as `Repository` fragments taking a scope; 404 for unreadable cases; `system()`/`all()` scopes |
| 0006 | `SearchGateway`; atomic `CASE_SEARCH` side table with CLOB-bound appends; LIKE adapter in v1, vendor adapters in v1.1 |
| 0007 | SLA state in `CASE_SLA`, one anchor rule, chunked evaluation under ShedLock, `SLA_CYCLE` per reopen |
| 0008 | Spring Session JDBC with JSON attributes and no persisted authorities; custom `JdbcOidcSessionRegistry` carrying CSRF authorities; no authorized-client store; no step-up in v1 |
| 0009 | Server-rendered UI: Thymeleaf + htmx (fixed config) + Bootstrap CSS only; exact CSP; native `<dialog>`; JavaScript required |
| 0010 | Reports: HTML + CSV in v1; tagged PDF via `openhtmltopdf` + veraPDF in v1.1 |
