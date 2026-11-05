# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Documentation

- Importer design (`docs/design/importer.md`) and ADR-0014 (Proposed): import compliance case history from SQL Server,
  Oracle or PostgreSQL, or from files exported from them, through a canonical staging model, a validated mapping file,
  a dry run with an authenticated sign-off, an idempotent load through import-only domain APIs that never touches the
  source, and a reconciliation report. ADR-0014 records the owner-accepted risk that imported attachments are stored
  `NOT_SCANNED` in `external` scanning mode (ADR-0013), amends Part B to allow one importer source-query DAO, and lists
  the new dependencies with their licences.

### Fixed

- ADR-0006 no longer says both drivers accept the character-stream bind; on Oracle the search append binds a CLOB.
- Oracle Free 23 `oracle-it` failures (ORA-12516) in `OracleDatabaseGrantsIT`: the grants probes share one connection per grantee and test contexts use a small Hikari pool.

### Added

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
