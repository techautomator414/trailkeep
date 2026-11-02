# Trailkeep — Implementation Plan

_Audit of BUILD_PLAN v0.2, the decisions that came out of it, the engineering standards the code will be held to, and the work packages in build order._
_Version 0.1 — 2026-11-02. Where this document and BUILD_PLAN.md disagree, this document wins; BUILD_PLAN.md v0.3 carries the same corrections in place._

The audience for the code is experienced enterprise Java developers. Every decision below was made with one question: would a senior Spring / Hibernate / Oracle engineer sign off on it without a second round?

---

## Part A — Audit: findings and decisions

Two reviews fed this: the author's own pass and an independent hostile review by a second reviewer who had not seen the author's reasoning. Findings are grouped by severity. Each row is a decision, not a suggestion; "ADR" marks the ones that get an Architecture Decision Record in `adr/` with the first code PR.

### A.1 Blockers — the plan described mechanisms that cannot work as written

| # | Finding | Decision | ADR |
|---|---|---|---|
| 1 | **Migration tool vs. Oracle version.** Flyway Community's support for older Oracle releases has narrowed since Flyway 10 and requires the separate `flyway-database-oracle` plugin; the production Oracle version is unconfirmed. | Phase 0 confirms the Oracle version and tests Flyway Community against it on day one. **Liquibase (Apache-2.0) is the pre-approved fallback**; SQL files are written as plain SQL so either tool runs them. CI runs migrations against Oracle Free 23 (the only version we can test publicly); the deploying organization runs `-Poracle-it` against its real version in Phase 1 and that result is recorded. The plan says "tested on 23ai Free, supported on the version the organization verifies". | ADR-003 |
| 2 | **`@Lob` is not portable.** Hibernate maps `@Lob String`/`byte[]` to PostgreSQL large objects (`oid`), not `TEXT`/`BYTEA`; schema validation fails on one vendor. | No `@Lob` anywhere (ArchUnit rule). Long text: `@Column(length = Length.LONG32)` → `CLOB` / `TEXT`. Binary: never on a JPA entity (see #3). A Testcontainers test runs `hibernate.hbm2ddl.auto=validate` against the migrated schema on **both** vendors in every build. | ADR-004 |
| 3 | **Attachment bytes on the entity.** Loads every BLOB when listing attachments; no streaming story; heap risk at 25 MB × concurrent downloads. | Content lives in `CASE_ATTACHMENT_CONTENT (ATTACHMENT_ID PK, CONTENT)`, written and read by a JDBC DAO (`JdbcTemplate`, `InputStream`), never by JPA. Downloads use `StreamingResponseBody`; a semaphore bounds concurrent downloads per replica; the 25 MB cap is set in both `spring.servlet.multipart.max-file-size` and `clamd.conf StreamMaxLength`. | ADR-004 |
| 4 | **Booleans.** An `AttributeConverter<Boolean,Integer>` breaks PostgreSQL `BOOLEAN`; Hibernate's Oracle dialect switches to native `BOOLEAN` on Oracle 23, so a schema validated against 23 Free may not validate against 19c. | Plain `boolean`; pin `hibernate.type.preferred_boolean_jdbc_type=BIT` and an explicit dialect version so behaviour does not depend on the detected server. DDL uses `${bool}` → `NUMBER(1,0)` / `BOOLEAN`. | ADR-004 |
| 5 | **Appendix A DDL was not valid Oracle.** `BIGINT` does not exist in Oracle; `VARCHAR(n)` is byte-semantic by default; `VARCHAR(4000)` sits on the hard limit. | Placeholders `${id}` → `NUMBER(19,0)` / `BIGINT`; Flyway `initSql` sets `NLS_LENGTH_SEMANTICS=CHAR` on Oracle; no column wider than `VARCHAR(2000)`; long text is `${clob}`. A unit test over the migration files rejects ANSI types Oracle lacks and identifiers over 30 characters (30 kept as a convention; Oracle 12.2+ allows 128). Appendix A rewritten. | — |
| 6 | **"Specification guarantees restricted-case invisibility" is false.** `findById`, derived queries, child repositories, lazy navigation, reports and scheduler code all bypass a Specification. | **One read path.** `CaseAccessGate` in the `casefile` module is the only component allowed to touch case-rooted repositories (ArchUnit forbids injection elsewhere). Single-entity reads are `load → policy.assertReadable(principal, case)` (post-filtering one row is correct and stated as such). Lists, counts and reports go through one `Specification` factory. Child tables always join through the case with the same predicate. Jobs run as an explicit `SystemPrincipal` with its own policy branch. A reflection-driven negative test enumerates every repository method and proves restricted rows are invisible to a non-permitted user. | ADR-005 |
| 7 | **Search design.** `LOWER(col) LIKE '%x%'` is not indexable on either vendor; `SEARCH_TEXT` on the versioned case row makes a colleague's comment fail your Save with an optimistic-lock error; it copies PHI into a second column; embedded Lucene does not work across replicas. | `SearchGateway` interface. v1 ships `CASE_SEARCH (CASE_ID PK, SEARCH_TEXT ${clob}, UPDATED_AT)` — a 1:1 side table with no `@Version`, maintained in the same transaction as the write, subject text **excluded** — and a portable `LIKE` adapter with the honest expectation "full scan of ~50k rows, fine; not fine at 500k comments". Vendor adapters (PostgreSQL `pg_trgm` + `ILIKE`; Oracle Text `CONTAINS`) are v1.1 behind the same interface, in the vendor migration folders that already exist. | ADR-006 |
| 8 | **SLA evaluator** writes `sla_state` through the versioned entity (spurious conflicts for users mid-edit) in one 50k-row transaction. | `CASE_SLA (CASE_ID PK, SLA_STATE, SLA_DUE_AT, AT_RISK_AT, SLA_CYCLE, LAST_EVALUATED_AT)` owned by the evaluator; candidates selected by the `(STATUS, SLA_DUE_AT)` index; processed in id-ordered chunks of 500, one transaction per chunk; bulk JPQL update; ShedLock `lockAtMostFor=14m`, `lockAtLeastFor=1m`, `usingDbTime()`. `SLA_CYCLE` increments on reopen so "At Risk once" is per cycle. | ADR-007 |
| 9 | **Sessions + OIDC across two replicas** were a one-liner hiding five known production incidents. | Spring Session JDBC with vendor schemas shipped as migrations; `JdbcOAuth2AuthorizedClientService` (+ its schema) so RP-initiated logout works on any replica; Spring-Session-backed `OidcSessionRegistry` for back-channel logout; a filter enforcing the 8-hour absolute lifetime from a login-time attribute; **JSON session-attribute serialization** (no JDK serialization); grants carve-out for `SPRING_SESSION*`, `OAUTH2_AUTHORIZED_CLIENT`, `SHEDLOCK` (they need `UPDATE`/`DELETE`). **Step-up re-authentication for admin actions is cut from v1** (no portable OIDC `max_age` support in Spring Security); admin actions are audited instead. | ADR-008 |
| 10 | **CSP / htmx / Bootstrap** story was incoherent: nonces apply to elements not attributes; Bootstrap JS sets inline styles; Spring Security does not generate nonces. | Exact header fixed: `default-src 'self'; script-src 'self' 'nonce-{n}'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`. A per-request nonce filter + `th:attr="nonce=${cspNonce}"`. htmx config: `allowEval=false, allowScriptTags=false, selfRequestsOnly=true, inlineScriptNonce` set; `hx-on*` and `hx-vals="js:"` banned by Semgrep. **Bootstrap CSS only — no Bootstrap JS, no Popper.** The few behaviours we need (dialog, combobox, toast, tabs, session warning) live in one `app.js` built on native `<dialog>` and WAI-ARIA APG patterns. | ADR-009 |
| 11 | **Admin-editable email templates rendered by Thymeleaf = server-side template injection.** | **No database template editor in v1.** Templates are fixed Thymeleaf files; organizations override wording through `messages_*.properties`. Variables are substituted into subject lines with a 20-line `{{key}}` replacer over an allow-listed map, HTML-escaped. | — |
| 12 | **Spring Boot "3.5 fallback"** is not source-compatible with 4.x (Jackson 3, Jakarta EE 11, Hibernate 7, Security 7, renamed starters, `@MockitoBean`) and 3.5 is out of OSS support. | **Current Spring Boot 4.x line only.** Starters by their 4.x names (`spring-boot-starter-webmvc`, `spring-boot-starter-security-oauth2-client`, per-technology test starters). JSpecify and Jackson 3 are conventions. If an approved-software list lags, the organization runs the published artifact under a documented exception. | ADR-001 |
| 13 | **PDF/UA with raw PDFBox** is a multi-week subsystem budgeted as a line item. | **PDF export moves to v1.1.** v1 contract: CSV + an accessible HTML report view with a print stylesheet. v1.1 uses the maintained `openhtmltopdf` fork (LGPL 2.1, dynamically linked — the one LGPL runtime dependency, recorded as such) so the accessible HTML renders once into tagged PDF, gated by veraPDF. | ADR-010 |

### A.2 Significant — correctness and data-model gaps

| # | Finding | Decision |
|---|---|---|
| 14 | Outbox had no claim pattern, no idempotency table, a reserved column name (`TO`), and nowhere to put the weekly report artifact. | Dispatcher claims rows with `SELECT … FOR UPDATE SKIP LOCKED` (both vendors; Hibernate `PESSIMISTIC_WRITE` + `jakarta.persistence.lock.timeout=-2`), state machine `PENDING → SENDING(locked_until) → SENT | FAILED`, SMTP I/O outside the DB transaction, stale-claim reaper. `ALERT_LEDGER (CASE_ID, ALERT_TYPE, SLA_CYCLE, PERIOD_KEY) UNIQUE`. Column is `RECIPIENT`. `REPORT_RUN (ID, REPORT_TYPE, PARAMS_JSON, RENDERED_AT, FORMAT, CONTENT)` referenced by outbox rows. |
| 15 | Case-number allocation serialized every create on a hot row with a new-year race, and gaplessness was never decided. | **Gaps are acceptable** (stated in the plan and in the UI help). One database sequence, formatted `PREFIX-YYYY-NNNNNN` where the year is the opening year and the number does not reset. Prefix validated to ≤ 8 chars. No lock, no hot row. |
| 16 | Audit old/new capture unspecified; PHI would land in `AUDIT_EVENT`; child events had no `CASE_ID`; `ENTITY_ID NOT NULL` excluded login/export events; DB `now()` contradicted the injectable clock. | Service-layer snapshot diff (`CaseSnapshot` record → JSON) with a redaction policy: `description`/`subject` recorded as SHA-256 + length, never text. `AUDIT_EVENT.CASE_ID` nullable + indexed; `ENTITY_ID` nullable. One `InstantSource` bean (UTC) for all application time; DB time only inside ShedLock. |
| 17 | Open-in-view, fetch strategy and N+1 were absent. | `spring.jpa.open-in-view=false`. Entities never reach templates: list pages use record projections; the case page uses an `@EntityGraph`; `@BatchSize(50)` on comment authors; `hibernate.query.fail_on_pagination_over_collection_fetch=true`; `in_clause_parameter_padding=true`; a query-count assertion (datasource-proxy) on the five hottest pages. |
| 18 | Permission model said global roles; schema said per-team roles; SoD edge cases undefined. | Authority = `Set<TeamRole>` + global roles. One `CasePermissionService` with a decision table (team role × relationship-to-case × status × action), tested exhaustively from a CSV that is the single source of truth for the matrix in BUILD_PLAN §3.2. Edge rules: default approver = the team's Managers minus the requester; none left → Compliance Admins. All Managers of a team receive escalations. Only the owner or Manager+ may mark a case restricted. A Manager may reassign a restricted case away from themselves. Reader reports exclude restricted rows automatically. Team roles are owned by the local admin screen; IdP groups supply only the global roles (BUILD_PLAN §3.1/§4.6 aligned). |
| 19 | Data-model gaps for described flows. | Closure fields move to `CLOSURE_REQUEST`. New: `CASE_TYPE_OUTCOME`, `USER_ALERT`, `ALERT_LEDGER`, `REPORT_RUN`, `CASE_SLA`, `CASE_SEARCH`, `CASE_ATTACHMENT_CONTENT`. Columns: `UPDATE_REQUEST.ANSWERED_BY_COMMENT_ID`, `LAST_REMINDED_AT`; `CASE_RECORD.LAST_REJECTED_AT`; attachment `RELEASED_BY/AT/REASON`; `LEGAL_HOLD_BY/AT/REASON`. Status gains `ARCHIVED`. `SLA_STATE` gains `NOT_APPLICABLE` and a CHECK. Every enum column has a CHECK. `purge_after` is recomputed by the retention job, not stored at close. Index on `CLOSURE_REQUEST (APPROVER_ID, DECISION)`. |
| 20 | Grant rules contradicted shipped features (pin needs `UPDATE` on comments; sessions and locks need `DELETE`; schema resolution unmentioned; purge "as another DB user" from the same JVM is nominal). | Grants written as a matrix (table × SELECT/INSERT/UPDATE/DELETE × role) in `db/migration/common/R__grants.sql`. Pin moves to `CASE_COMMENT_PIN (COMMENT_ID PK, PINNED_BY, PINNED_AT)` so `CASE_COMMENT` stays INSERT-only. `hibernate.default_schema` / `CURRENT_SCHEMA` / `search_path` set explicitly. Retention purge runs as a separate Kubernetes `CronJob` using the same image with a `purge` profile and the `TRAILKEEP_PURGE` credential. |
| 21 | `tika-core` alone cannot distinguish Office types; image decoding has no bomb limit. | Keep `tika-core` only; allow-list the container types it does detect (`application/x-tika-ooxml`, `application/x-tika-msoffice`) cross-checked against the extension; ClamAV scans recursively (EML/MSG included); image dimensions are read from the header and capped before decode. |
| 22 | Banning `th:utext` blocked rendering sanitized Markdown. | `SafeHtml` value type produced only by the sanitizer; a custom Thymeleaf attribute processor `tk:safe` accepts only that type; Semgrep bans `th:utext` and allow-lists `tk:safe`. |
| 23 | htmx + Spring Security redirects and error bodies unaddressed. | `AuthenticationEntryPoint`/`AccessDeniedHandler` emit `HX-Redirect` (401/403) when `HX-Request` is present. Fragment contract: 200 swap; 422 + `HX-Retarget`/`HX-Reswap` for validation errors; 409 for optimistic-lock conflict (renders the Reload banner); 5xx renders the generic banner. One `htmx:beforeSwap` handler in `app.js` implements the contract and the focus/announce rules. |
| 24 | Domain events and transaction phases hand-waved. | Audit rows, outbox rows and ledger rows are written **synchronously inside the originating transaction**. Cross-module reactions use `@TransactionalEventListener(BEFORE_COMMIT)`. Nothing network-bound (SMTP, clamd) runs inside a transaction. Spring Modulith is used for module structure and verification; its event publication registry is **not** used in v1 (one less vendor schema). |

### A.3 Over-promises corrected

| # | Finding | Decision |
|---|---|---|
| 25 | "Build-breaking from the first commit" on a public repo: fork PRs get no secrets (SonarCloud, NVD key); ZAP baseline against an SSO redirect scans nothing. | Required checks split: **runs on forks** (compile, unit, PostgreSQL IT, CodeQL, Semgrep, SpotBugs/FindSecBugs, license check, axe, gitleaks) and **runs on `main` and maintainers' branches** (SonarCloud, Dependency-Check with NVD key, authenticated ZAP via a Playwright-exported dev-realm session, Oracle IT). Pre-commit hooks are advisory; CI is the gate. |
| 26 | Accessibility: "accessible by default", "0 violations", "no Does Not Support" overstated; dialog mechanism undecided; JS-optional flows implied. | Wording replaced with "vetted component list". **Native `<dialog>` everywhere.** JavaScript is required (stated; the app is an intranet tool). axe is one gate among several, not the proof. Conformance gets its own **3-week window** (Phase 6) with the tester's certification named in the ACR; the manual test matrix ships with the ACR. |
| 27 | 15–16 weeks for two developers was roughly half of what the scope implied. | **Re-baselined** (Part F): v1 core ≈ 22 weeks for 2 developers (≈ 16 with a third), after moving PDF export, SAML, WAR packaging, the email-template editor, vendor search adapters, step-up auth and the importer mapping DSL to v1.1. |
| 28 | License statements were wrong: ojdbc ships in the image (that is redistribution); two different "license-maven-plugin"s conflated; Logback is dual EPL/LGPL. | "ojdbc is redistributed inside the container image under Oracle's Free Use Terms and recorded in NOTICE." Headers: `com.mycila:license-maven-plugin`; third-party report and allow-list: `org.codehaus.mojo:license-maven-plugin`. Logback taken under EPL-1.0; the only LGPL runtime dependency is `openhtmltopdf` in v1.1. |
| 29 | Small items. | `server.forward-headers-strategy=framework` with the Route as trusted proxy. `Pageable` sort properties allow-listed per page. **Case number is the URL key**, never the numeric id. SLA anchor is `identified_on` at end of day in `app.timezone`, policy-configurable to `opened_at`. Weekly report scheduling is an hourly tick over `REPORT_SCHEDULE.NEXT_RUN_AT`, not dynamic `@Scheduled`. Bucket4j is per replica, by design. Java 21 with platform threads (virtual threads revisited on Java 25 once Hikari pinning is confirmed resolved). |

---

## Part B — Engineering standards (what reviewers will judge)

These are enforced by the build where a tool exists, and by the PR checklist where one does not.

**Language and framework.** Java 21 (compile target), CI also runs the suite on the current Java LTS. Spring Boot current 4.x. JSpecify: every `package-info.java` is `@NullMarked`; NullAway via Error Prone fails the build on a nullness violation. **No Lombok.** Records for commands, read models and value objects; explicit constructors and constructor injection everywhere; no field injection; no `@Autowired` on constructors (implicit). Sealed interfaces for domain results where a method has several outcomes. `final` fields; immutable collections returned.

**Formatting and static checks.** Spotless with palantir-java-format; Checkstyle (custom ruleset derived from Google's: 120 columns, no star imports, no unused imports, braces always, method length ≤ 60 lines, class length ≤ 400 lines, cyclomatic complexity ≤ 10); Error Prone + NullAway with `-Werror`; SpotBugs + FindSecBugs at max effort; ArchUnit rules in `src/test/java/.../architecture`.

**Layering and modules.** Spring Modulith `ApplicationModules.verify()` as a test; modules = `shared, security, audit, user, casefile, closure, updaterequest, sla, document, notification, report, retention, importer, web`. A module exposes a `*Service` interface and record types in its root package; everything else is package-private. Repositories are package-private. No module imports another module's internal package (Modulith verifies). Web controllers depend on module APIs only.

**Persistence.** Flyway (or Liquibase, per ADR-003) owns the schema; Hibernate validates it on both vendors in CI. Entities: `Long` id from pooled sequences (`allocationSize=50`), `@Version int version`, `Instant` timestamps, `@Column(length=Length.LONG32)` for long text, no `@Lob`, no `byte[]`, `FetchType.LAZY` on every association, `@EntityGraph` or projection per read use-case, `open-in-view=false`. Native SQL only inside `*JdbcDao` classes in the `document`, `notification` (SKIP LOCKED) and `search` packages, with `@SuppressWarnings` justified in a comment. No entity crosses a module boundary or reaches a template.

**Transactions.** `@Transactional` on service implementations only; read paths `readOnly=true`; network I/O never inside a transaction; cross-module reactions via `@TransactionalEventListener(BEFORE_COMMIT)`; jobs chunked with one transaction per chunk.

**Web.** Thymeleaf templates under `templates/{module}/`; fragments under `templates/{module}/fragments/`; one layout; commands are records annotated with Jakarta Validation and bound with `@Valid`; one `@ControllerAdvice` maps the domain exception hierarchy (`DomainException` → `NotFound`, `Forbidden`, `Conflict`, `Invalid`) to the fragment contract (A.2 #23) and to full error pages. Case number is the only identifier in URLs. Sort/filter parameters are parsed into allow-listed enums, never passed through.

**Security conventions.** Authorization decisions live in `CasePermissionService` and `CaseAccessGate`; controllers never check roles themselves beyond `@PreAuthorize("isAuthenticated()")`. All request handlers that mutate are `POST` with CSRF; no `GET` has side effects. Output is escaped by default; `tk:safe` is the only unescaped path.

**Logging.** Logback + logstash JSON encoder (which escapes control characters, so no separate CRLF rule). MDC keys: `requestId`, `userId` (internal id, never email), `caseNumber`, `module`. `INFO` for business events, `DEBUG` for flow, `WARN` for recoverable anomalies, `ERROR` only with a stack trace and only once per failure. Never log description, subject, attachment names of restricted cases, or email addresses. Audit is a table, not a log line.

**Configuration.** `@ConfigurationProperties` records under `app.*` with `@Validated`; the `prod` profile fails fast if a required property is missing; profiles: `local`, `test`, `oracle-test`, `prod`, `purge`. Hikari pool size documented per replica against the Oracle session limit.

**Testing.** Surefire runs `*Test` (unit, slice); Failsafe runs `*IT` (Testcontainers, Playwright). Test names describe behaviour: `approve_byRequester_isRejectedWithForbidden()`. Testcontainers `@ServiceConnection` with reuse enabled locally. JaCoCo: 80 % line / 70 % branch overall, 90 % line in `sla`, `closure`, `security`, measured per package in the report and gated in the build. Playwright page objects under `src/test/java/.../e2e/pages`. Fixtures via builders, not SQL dumps. No test touches a real network.

**Pull requests.** ≤ 400 net lines of production code; one migration per PR, and the migration, its entity change and its test ship together; every PR fills the template checklist (scrub, authorization test, audit event, migration portable, accessibility criteria, SPDX header, ADR if a decision changed). Squash-merge, conventional commit subject, DCO sign-off. ADRs live in `adr/NNNN-title.md` using the MADR template.

**Documentation in code.** Javadoc on every public type and method of a module API (not on internals); a `package-info.java` per module stating its responsibility and the modules it may call.

---

## Part C — Work packages

Sizes: **S** ≤ 2 developer-days, **M** 3–5, **L** 6–10. "Depends" lists work packages that must be merged first. Every WP ends with its tests green on PostgreSQL in CI and, for anything touching SQL, on Oracle Free nightly.

### Phase 1 — Walking skeleton (everything scanners, nothing features)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-01** | Build and repository scaffold | M | — | `pom.xml` with enforcer (Java 21, banned deps, upper-bound), Spotless, Checkstyle, Error Prone + NullAway, SpotBugs/FindSecBugs, JaCoCo, mycila header check, mojohaus third-party report + allow-list, CycloneDX. `mvnw`, `.editorconfig`, `.gitattributes`, `CODEOWNERS`, PR/issue templates, `adr/0001..0010`. `TrailkeepApplication` boots with `/actuator/health` on the management port. **Accept:** `./mvnw verify` green on an empty app; every scanner wired and failing on a deliberately planted violation (then removed). |
| **WP-02** | CI pipeline (fork-safe and main) | M | WP-01 | GitHub Actions: `ci.yml` (fork-safe jobs), `main.yml` (Sonar, Dependency-Check, Oracle IT, authenticated ZAP placeholder), `codeql.yml`, `scorecard.yml`, `dependabot.yml`. Actions pinned by SHA, least-privilege token, concurrency groups, caching. **Accept:** a fork PR runs and passes without secrets; `main` runs the full set. |
| **WP-03** | Database foundation | L | WP-01 | Flyway with `common/oracle/postgresql` locations, placeholders (`${id} ${bool} ${clob} ${bytes} ${tstz}`), `initSql` for Oracle length semantics; migration-lint unit test (identifier length, banned types, reserved words); Testcontainers PostgreSQL + Oracle Free via `@ServiceConnection`; **two-vendor `validate` test**; Hibernate settings (boolean type, dialect pin, open-in-view off, batch size, padding); `InstantSource` bean; `R__grants.sql` matrix for `OWNER/APP/PURGE` users. Liquibase spike recorded in ADR-003 if Phase 0 requires it. **Accept:** `V1__core.sql` (users, teams, roles, case types, SLA policies, audit) migrates and validates on both vendors. |
| **WP-04** | Identity and session | L | WP-03 | OIDC login against Keycloak dev realm (`compose.yaml`), `AppUser` upsert on login, global-role mapping from group claims, Spring Session JDBC (vendor schemas as migrations, JSON attribute serializer, customizers), `JdbcOAuth2AuthorizedClientService`, Session-backed `OidcSessionRegistry`, RP-initiated + back-channel logout, absolute-lifetime filter, idle timeout, `/session/extend`. Test support: `@WithAppUser(role=…, team=…)`. **Accept:** login/logout round-trip in Playwright; session survives a replica swap (two app instances in the IT); all security headers present. |
| **WP-05** | Web shell and CSP | M | WP-04 | Layout (landmarks, skip link, nav, breadcrumb), nonce filter + Thymeleaf attribute, exact CSP header, htmx vendored and configured, `app.js` (beforeSwap contract, focus manager, live region, `<dialog>` helper, session-expiry warning), error pages, `@ControllerAdvice` + fragment contract, static asset content-hash versioning. Bootstrap **CSS** only with WCAG-checked tokens. **Accept:** axe 0 violations on shell and error pages; CSP has no `unsafe-*`; ZAP baseline clean against the shell. |
| **WP-06** | Audit module | M | WP-03 | `AuditEvent` entity (nullable `caseId`, `entityId`), `AuditRecorder` API, snapshot-diff helper with redaction policy, request-id/MDC filter, `SystemPrincipal`. **Accept:** recorder writes in the caller's transaction (rollback test); redaction test proves `description` never appears in JSON. |
| **WP-07** | Modulith structure and architecture tests | S | WP-01 | `ApplicationModules.verify()`, ArchUnit rules (no `@Lob`, repositories package-private, no entity in `web`, no `findById` outside `casefile.access`, controllers use module APIs only), Semgrep rules (`th:utext`, `.disable()`, `hx-on`, string-built queries). **Accept:** each rule has a negative test that fails on a planted violation. |
| **WP-08** | Observability and container | M | WP-01 | JSON logging + MDC, Micrometer/Prometheus, management port, UBI 9 image (non-root, read-only FS), `deploy/` kustomize base (Deployment, Service, Route, NetworkPolicy, SCC, Secrets), Trivy/Dockle in CI, SBOM + cosign + SLSA on release tags. **Accept:** image builds in CI and passes scans; `kube-linter` clean; app runs read-only. |

Phase 1 exit: WP-01…08 merged; every scanner green; login works against a real IdP in DEV; ACR draft for the shell.

### Phase 2 — Cases

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-09** | Users, teams, team roles (admin) | M | WP-04, 06 | `Team`, `AppUserTeam(role)`, admin screens (list/edit teams, membership, disable user), `CasePermissionService` decision table + CSV-driven exhaustive test, `UserPermissions` per-request value. **Accept:** matrix test passes for every (role × relationship × status × action) row. |
| **WP-10** | Case types and SLA policies (admin) | S | WP-09 | `CaseType`, `CaseTypeOutcome`, `SlaPolicy` (anchor, target, at-risk %, reminder cadence, escalate-to), admin CRUD, seed YAML for `local`. |
| **WP-11** | Case create and view | L | WP-09, 10 | `CaseRecord`, sequence-based numbering, one-page create form (command record, validation, PHI heuristic warning), case page header + Overview, `CaseAccessGate` + Specification factory, `CASE_SLA` row initialised, audit events, `@EntityGraph` for the page, projection for list. **Accept:** golden path create → view in Playwright; reflection-driven restricted-invisibility test for every repository method; query-count assertion on the case page. |
| **WP-12** | Case edit, reassign, restrict | M | WP-11 | Edit mode with optimistic locking (409 → Reload banner), reassign owner/team, mark restricted (owner/Manager+), audit diffs. **Accept:** concurrent-edit IT reproduces and renders the conflict; restricted toggle audited and enforced. |
| **WP-13** | Comments and SafeHtml | M | WP-11 | `CaseComment` (append-only), `CaseCommentPin`, Markdown → OWASP sanitizer → `SafeHtml`, `tk:safe` processor, post via htmx with live-region announcement, `CASE_SEARCH` maintenance. **Accept:** XSS corpus test through the sanitizer; INSERT-only grant verified by a negative IT. |
| **WP-14** | Attachments: upload, scan, download | L | WP-11 | Multipart upload to temp file, Tika detection + allow-list + extension cross-check, size cap, SHA-256, `CaseAttachment` metadata + `CASE_ATTACHMENT_CONTENT` via JDBC DAO (streamed), ClamAV INSTREAM client (own, ~100 lines) with contract test against the real `clamav` container, `PENDING → CLEAN | QUARANTINED` poller, release by Compliance Admin with reason, streamed download with `Content-Disposition: attachment`, download semaphore, image-dimension cap. **Accept:** EICAR file quarantines; disguised executable rejected; 25 MB file round-trips without heap growth (IT with `-Xmx256m`). |
| **WP-15** | Cases list, filters, search, saved views | L | WP-11, 13 | List page with projections and allow-listed sort, filter drawer, `SearchGateway` + `LikeSearchAdapter` over `CASE_SEARCH`, `SavedView` (filter JSON schema-validated), CSV export with redaction and formula-escaping, 50k seed + k6 smoke. **Accept:** p95 list < 500 ms at 50k seed on PostgreSQL; restricted rows absent from results, counts and CSV for a non-permitted user. |
| **WP-16** | History tab | S | WP-06, 11 | Case-scoped audit timeline with filters; restricted-view events recorded on page view and download. |

### Phase 3 — Closure and email

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-17** | Notification outbox and dispatcher | M | WP-03, 06 | `NotificationOutbox` (`RECIPIENT`), SKIP LOCKED claim, state machine, SMTP sender (Spring Mail, STARTTLS required), multipart templates (text + simple HTML), backoff, reaper, `USER_ALERT`, Mailpit in compose, metrics. **Accept:** two dispatcher instances in an IT never double-send; a stale `SENDING` row is reclaimed; SMTP down → rows stay `PENDING` and the System screen shows it. |
| **WP-18** | Request Update | M | WP-13, 17 | `UpdateRequest`, user combobox (APG pattern), email with deep link, auto-resolve on comment by the requested user, "Mark answered", reminders via `LAST_REMINDED_AT`, dashboard "Needs attention". |
| **WP-19** | Request Closure / Approve / Reject / Reopen | L | WP-12, 17 | `ClosureRequest` holding outcome/summary/follow-ups, approver selection rules (A.2 #18), SoD enforcement, state transitions with audit, emails to approver/requester, "Requires Changes" badge, reopen with reason and `SLA_CYCLE` increment, "Pending my approval" tile and list. **Accept:** golden paths 2–4 in Playwright; requester-cannot-approve and owner≠approver tests; concurrent approve/reject resolves to exactly one decision. |
| **WP-20** | Dashboard | S | WP-15, 19 | KPI tiles, My cases, Needs attention, Pending my approval; all counts via the access gate. |

### Phase 4 — SLA and aging

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-21** | SLA evaluator and alerts | L | WP-11, 17 | Chunked evaluator over `CASE_SLA`, ShedLock, transitions → `ALERT_LEDGER` + outbox, SLA chip and filters, end-of-day anchor in `app.timezone`, closure finalises `MET/BREACHED`, metrics `cases_by_sla_state`, health check "evaluator ran recently". **Accept:** fixture of 20 cases at crafted ages produces the expected transitions and exactly one email each across three evaluator runs and a restart; reopen resets per cycle. |
| **WP-22** | Aging and SLA-compliance reports (HTML + CSV) | M | WP-21 | SQL-aggregated report queries (no in-memory grouping), accessible HTML report view with print stylesheet, CSV export, parameters, redaction. **Accept:** report matches a hand-computed fixture; axe clean. |
| **WP-23** | Scheduled report delivery | M | WP-22, 17 | `REPORT_SCHEDULE` + hourly tick, `REPORT_RUN` persisted, outbox rows referencing the run, recipients by role/team, first-cycle "Compliance Admins only" switch. |

### Phase 5 — Admin, retention, importer, polish

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-24** | Retention, legal hold, purge CronJob | M | WP-19 | Retention per case type, `ARCHIVED` status, legal hold with who/when/reason, `purge_after` recomputation, `purge` profile run as a Kubernetes `CronJob` with the `PURGE` credential, metadata-only purge log. **Accept:** legal hold stops purge; purge IT proves app credential cannot delete. |
| **WP-25** | System screen and operational alerts | S | WP-17, 21 | Scheduler last-run, outbox backlog/failures, clamd status, last import run; Prometheus alert rules in `deploy/`. |
| **WP-26** | Generic importer (CSV + fixed mapping file) | M | WP-11, 13, 14 | Staging tables, YAML mapping (column map, value translations, date formats, defaults), idempotent run id, `legacy_ref`, closed-as-`NOT_APPLICABLE`, open-with-original-anchor, attachment folder import through the scan pipeline, reconciliation report. **Accept:** synthetic extract round-trips with 100 % reconciliation; re-run is a no-op. |
| **WP-27** | Preferences, shortcuts, i18n polish | S | WP-05 | User preferences (shortcuts on/off), `/` and `N`, externalised strings reviewed, time-zone rendering. |

### Phase 6 — Hardening and conformance (3 weeks)

| WP | Title | Size | Depends | Scope and acceptance |
|---|---|---|---|---|
| **WP-28** | Authenticated DAST and security E2E | M | all | Playwright-exported dev-realm session handed to ZAP full + API scan on `main`; security E2E suite (CSP, CSRF, cross-team 403, restricted leak, upload abuse); fix everything. **Accept:** 0 Medium+ in ZAP; SonarCloud gate A; Dependency-Check clean. |
| **WP-29** | Section 508 conformance pass and ACR | L | all | Trusted Tester v5 pass with NVDA+Firefox, JAWS+Chrome, VoiceOver+Safari; 200 %/400 % zoom; forced-colors; remediation; ACR (VPAT 2.5) + manual test matrix in `accessibility/`. **Accept:** no "Does Not Support" for any A/AA criterion; remediation tickets dated for any "Partially Supports". |
| **WP-30** | Performance, restore drill, runbook, evidence pack, 1.0.0 | M | all | k6 at 50k/500k seed, pool sizing, restore drill, `docs/RUNBOOK.md`, release workflow producing the evidence pack, `CHANGELOG`, signed tag. |

### v1.1 backlog (explicitly deferred)
PDF export via `openhtmltopdf` + veraPDF (ADR-010) · vendor search adapters (`pg_trgm`, Oracle Text) · SAML2 login · WAR packaging profile · email-template editor with safe substitution · step-up re-authentication for admin actions · importer mapping DSL beyond the fixed YAML · per-case subscribe · business-day SLA calendars · structured parties · bulk reassign for managers · Spring Modulith event publication registry · additional UI languages.

---

## Part D — The first twelve pull requests (Phase 1 in order)

These are what reviewers see first, so each is small, complete and self-explanatory.

1. **`build: project scaffold`** — `pom.xml`, wrapper, `.editorconfig`, `TrailkeepApplication`, `application.yml`, health on the management port, README stub. (WP-01, part)
2. **`build: static analysis gates`** — Spotless, Checkstyle, Error Prone + NullAway, SpotBugs/FindSecBugs, JaCoCo thresholds, header check, third-party report; one planted violation per tool removed in the same PR's history to prove the gate. (WP-01, part)
3. **`ci: fork-safe pipeline`** — `ci.yml`, `codeql.yml`, `scorecard.yml`, `dependabot.yml`, SHA-pinned actions. (WP-02)
4. **`docs: ADR-001…010`** — the decisions in Part A that shape all later code. (WP-01, part)
5. **`db: Flyway foundation and two-vendor validation`** — locations, placeholders, `initSql`, migration-lint test, Testcontainers PostgreSQL + Oracle Free, `validate` test, `InstantSource`. (WP-03, part)
6. **`db: V1 core schema and grants`** — users, teams, roles, case types, SLA policies, audit; `R__grants.sql`. (WP-03, part)
7. **`arch: Modulith structure and rules`** — module skeletons with `package-info`, `ApplicationModules.verify()`, ArchUnit and Semgrep rules with negative tests. (WP-07)
8. **`security: OIDC login and JDBC sessions`** — Keycloak compose, login/logout, role mapping, Spring Session JDBC + authorized-client store, lifetime filter, test support annotation. (WP-04)
9. **`web: shell, CSP and htmx contract`** — layout, nonce filter, exact header, `app.js`, error pages, controller advice, asset versioning, axe test. (WP-05)
10. **`audit: recorder and redaction`** — entity, API, snapshot diff, MDC filter, rollback and redaction tests. (WP-06)
11. **`ops: JSON logging, metrics, UBI image, kustomize base`** — plus Trivy/Dockle in `main.yml`. (WP-08)
12. **`ci: main-branch gates`** — SonarCloud, Dependency-Check with NVD key, Oracle IT job, ZAP baseline against the shell, release workflow skeleton. (WP-02, part)

---

## Part E — Definition of Done (per PR)

- Builds green on the fork-safe pipeline; on `main` also the full set.
- Unit and slice tests for new logic; an `*IT` for anything touching SQL, security or htmx behaviour; both vendors if SQL changed.
- Authorization: every new mutating endpoint has a test proving the matrix row (allowed role passes, nearest disallowed role gets 403).
- Audit: every new state change writes an audit event, with a test.
- Accessibility: new or changed screen passes axe with 0 violations and a keyboard-only walk is recorded in the PR description.
- Migration portable (lint test), one per PR, shipped with its entity change.
- No new dependency without a line in the PR explaining why and its license.
- Docs: Javadoc on module API changes; ADR if a Part A decision changed; `CHANGELOG` entry under Unreleased.
- SPDX header on every new file; DCO sign-off; conventional commit subject; ≤ 400 net production lines.

---

## Part F — Re-baselined schedule

Two developers, one part-time UX/accessibility/QA, security reviewer on call. Two-week sprints.

| Phase | Weeks | Work packages |
|---|---|---|
| 0 Discovery | 2 | Oracle version + Flyway/Liquibase decision, IdP, SMTP, scanner-of-record, hosting, legacy export sample (private), threat model v0 |
| 1 Skeleton | 3 | WP-01…08 |
| 2 Cases | 5 | WP-09…16 |
| 3 Closure and email | 3 | WP-17…20 |
| 4 SLA and aging | 3 | WP-21…23 |
| 5 Admin, retention, importer | 3 | WP-24…27 |
| 6 Hardening and conformance | 3 | WP-28…30 |
| 7 Go-live and hypercare | 1 | cut-over, first automated report |
| **Total** | **≈ 23 weeks** | with a third developer from Phase 2: ≈ 16–17 weeks |

Pilot option (Phases 0–4, one team, open items imported by hand): ≈ 16 weeks for two developers.

---

## Appendix — ADR index (created in PR 4)

| ADR | Decision |
|---|---|
| 0001 | Spring Boot current 4.x only; Java 21 compile target; no 3.x compatibility |
| 0002 | Modular monolith with Spring Modulith; module list and dependency rules |
| 0003 | Schema migration tool: Flyway Community with Liquibase as the pre-approved fallback; SQL kept tool-neutral |
| 0004 | Two-vendor persistence rules: no `@Lob`, `Length.LONG32`, JDBC DAO for binary content, pinned boolean type and dialect, two-vendor validate test |
| 0005 | `CaseAccessGate` as the single read path for case-rooted data; `SystemPrincipal` for jobs |
| 0006 | `SearchGateway` with a portable side-table `LIKE` adapter in v1; vendor adapters in v1.1 |
| 0007 | SLA state in `CASE_SLA`, evaluated in chunks under ShedLock; `SLA_CYCLE` per reopen |
| 0008 | Spring Session JDBC + JDBC authorized-client store, JSON attributes, absolute lifetime; no step-up auth in v1 |
| 0009 | Server-rendered UI: Thymeleaf + htmx + Bootstrap CSS only; exact CSP; native `<dialog>`; JavaScript required |
| 0010 | Reports: HTML + CSV in v1; tagged PDF via `openhtmltopdf` (LGPL, dynamically linked) gated by veraPDF in v1.1 |
