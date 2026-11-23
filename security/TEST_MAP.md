<!-- SPDX-License-Identifier: Apache-2.0 -->
# Security test map

For each security control Trailkeep promises, this page names the automated check that verifies it, where that
check runs, and whether the control is **verified** by a test or scanner, only **configured**, or left to the
**platform**. Controls with no automated verification, or only part of it, are listed under [Gaps](#gaps).

Sources:
- every control row of `BUILD_PLAN.md` §9;
- the WP-28 acceptance items of `IMPLEMENTATION_PLAN.md` Part C;
- the "E2E security tests" row of `BUILD_PLAN.md` §12.1.

The placement of checks follows `IMPLEMENTATION_PLAN.md` Part G and the workflows as they run today (`HANDOFF.md`
§4, "Part G as it runs today").

Two companion documents use this page as their evidence:
- `security/THREAT_MODEL.md`: the STRIDE model over BUILD_PLAN §7.1, each threat with its control, the row here that
  verifies it and its residual risk;
- `security/DATA_MAP.md`: where restricted and personal data lives, who reads it, how long it stays and whether it
  leaves the system.

**This page is checked on every build.** `SecurityTestMapTest` fails if any of these does not resolve:
- a backticked test class;
- any other class name;
- a repository path;
- a Semgrep rule (`semgrep:<id>`);
- a ZAP suppression (`zap:<rule id>`);
- an enforcer rule (`enforcer:<rule>`).

It also fails if a ZAP suppression in `security/zap/suppressions.yaml` is missing from this page.

## Where checks run

The status column records what is in effect today, not what is planned.

| Check | Workflow and job | When | Gate | Status today |
|---|---|---|---|---|
| Unit, slice, PostgreSQL integration and browser tests (all `*Test` and `*IT` named below unless stated) | `.github/workflows/ci.yml#build` (`./mvnw verify`); again in `.github/workflows/main.yml#oracle-it` and `.github/workflows/release.yml#verify` | every PR and push to `main`; nightly; release tag | any failure | running |
| Oracle twins of the persistence ITs (`Oracle*IT`) | `.github/workflows/main.yml#oracle-it` (`-Poracle-it`, xe 21 and free 23) | `main`, nightly, release | any failure | running |
| Semgrep: registry rules plus `security/semgrep` | `.github/workflows/ci.yml#semgrep` | every PR and push to `main` | any finding; rule tests must pass | running |
| CodeQL `security-extended` | `.github/workflows/codeql.yml#analyze` | every PR, push to `main`, weekly | alerts in code scanning | running |
| SpotBugs with FindSecBugs (max effort, low threshold) | inside `./mvnw verify`; exclusions in `config/spotbugs/exclude.xml` | as the tests | any finding | running |
| gitleaks | `.github/workflows/ci.yml#gitleaks` | every PR and push to `main` | any leak | running |
| Enforcer and licence allow-list | inside `./mvnw verify`: `enforcer:bannedDependencies`, `enforcer:requireUpperBoundDeps`, `enforcer:dependencyConvergence`; `security/licenses-allowed.txt` | as the tests | any violation | running; the allow-list skips the test scope |
| CycloneDX SBOM and Trivy on it | `.github/workflows/main.yml#sbom`, `.github/workflows/main.yml#trivy-sbom` | `main`, nightly, release | High/Critical | running |
| Image build, Trivy, Dockle, kube-linter | `.github/workflows/main.yml#image` | `main`, nightly, release | High/Critical (`--ignore-unfixed`); Dockle warn; kube-linter errors | running; failing at its Trivy step (#210) |
| SonarCloud quality gate | `.github/workflows/main.yml#sonarcloud` | `main`, nightly, release | gate A | **skipped**: `SONAR_TOKEN` not set (#30) |
| OWASP Dependency-Check | `.github/workflows/main.yml#dependency-check` | `main`, nightly, release | CVSS ≥ 7 | **skipped**: `NVD_API_KEY` not set (#30) |
| ZAP authenticated full and API scan | `.github/workflows/zap.yml#scan`, called by `.github/workflows/nightly.yml#zap` and `.github/workflows/release.yml#zap` | nightly (reports), release tag (gates) | Medium+, partial scan fails a release | running |
| OpenSSF Scorecard | `.github/workflows/scorecard.yml#analysis` | push to `main`, weekly | reported, not a gate | running |
| Dependabot | `.github/dependabot.yml` | weekly | pull requests | maven, github-actions and pip, plus the docker and docker-compose ecosystems for the test images (#251) and the compose images (#269) |
| Image signature, SBOM attestation, SLSA provenance | `.github/workflows/release.yml#publish`, `.github/workflows/release.yml#provenance`; verified after publishing by `.github/workflows/verify-image.yml` (`release.yml#verify-published`) | release tag; `verify-image` by hand against any tag | the release fails | **shown** against v0.2.0 (`cosign verify`, `cosign verify-attestation --type cyclonedx`, `slsa-verifier verify-image`); a wrong tag and a wrong digest fail |
| Pre-commit hooks | `.pre-commit-config.yaml` (gitleaks, Spotless, SPDX header) | locally, opt-in | advisory; CI is the gate | provided |

## Controls (BUILD_PLAN §9)

### Identity (IA)
**Verified:**
- **SSO only, provisioning, group-to-role mapping, a disabled user refused at login:** `KeycloakLoginIT` (real Keycloak dev realm), `TrailkeepOidcUserServiceTest`, `SecurityDefaultsIT` (anonymous requests go to the provider).
- **Spring Session JDBC with JSON attributes and no JDK serialization:** `SessionJsonTest` (a type outside the allow-list is not read back).
- **Authorities not persisted; rebuilt per request; disabled user loses the session; 8-hour absolute lifetime:** `SessionPermissionsFilterTest`.
- **Back-channel logout through the custom registry, its CSRF header kept, daily cleanup, sessions shared by two instances:** `JdbcOidcSessionRegistryIT` and `OracleJdbcOidcSessionRegistryIT`, `SharedSessionsIT` (two application instances), `BackChannelLogoutUriIT`.
- **Disabling a user ends their sessions everywhere:** `SharedSessionsIT`, `UserAdminServiceIT`.
- **The idle-expiry warning dialog is accessible:** `ShellAccessibilityIT`.
- **The 30-minute idle timeout** (`spring.session.timeout`): `SessionHardeningIT` moves a stored session's last access back rather than waiting. A session idle 31 minutes is replaced; one idle 29 minutes is kept.
- **The session cookie's flags:** `SessionHardeningIT` (see Browser security).
- **RP-initiated logout reaches the provider's end-session endpoint** with `id_token_hint` and `post_logout_redirect_uri`, and ends the session: `KeycloakLoginIT`.

**Not built:** step-up re-authentication (cut for scope in v1; admin actions are audited instead).

### Authorization (AC)
**Verified:**
- **Every permission decision against BUILD_PLAN §3.2, exhaustively, including System Admin having no case access:** `PermissionMatrixTest` over `src/main/resources/permissions/matrix.csv`; `CasePermissionServiceTest`.
- **`CaseScope` on every case-rooted query; unreadable cases are indistinguishable from absent ones:**
  - `CaseScopeTest`;
  - `CaseRootedInvisibilityIT`;
  - `CaseScopeMatrixIT`;
  - `ArchitectureRulesTest`, whose rules are that case-rooted repositories take a scope, are injected only into access paths, and that the all-cases scope is granted only by security.
- **404, not 403, on every case route for an unreadable case:** `RouteSweepIT` (every mapped route, from the handler mapping).
- **Object-level checks on attachments:** `CaseAttachmentDownloadIT` (an attachment id of another case is a 404) and `CaseAttachmentReleaseIT`.
- **Per-role controller behaviour:** the `web` controller ITs, for example `CaseControllerIT`, `CaseAssignmentControllerIT`, `ClosureDecisionControllerIT`, `TeamAdminControllerIT`, `UserAdminControllerIT` and `AuditControllerIT` (every role).
- **Module boundaries:** `ModularityTest`, `ImportOnlyApiRulesTest`.
- **Allow-listed sort and filter values:** `CaseListQueryTest`.
- **Deny by default** (`SecurityConfig`): `DenyByDefaultIT` sends an anonymous request, with a valid CSRF token, to every route in the handler mapping, and to static files, the error page, unmapped paths and Spring Security's `/login` and `GET /logout` (not generated, because the application sets its own entry point). Each is refused: a 302 to the start of the sign-in, or a 401 with `HX-Redirect` for htmx.
  - The public list is kept in the test, each entry with its reason: `GET /oauth2/authorization/{id}`, `GET /login/oauth2/code/{id}`, `POST /logout` (CSRF token still required) and `POST /logout/connect/back-channel/{id}`. All are served by Spring Security's filters, and each must still answer anonymously, so the list cannot go stale.
  - A `permitAll` on any other route fails the test until the route is listed with a reason.
  - The management port (8081) is anonymous by design: health, liveness, readiness, info and Prometheus only, reached from inside the cluster and never routed. `ManagementEndpointsIT` pins that nothing else is served there.

### Audit (AU)
**Verified:**
- **The event is written in the change's transaction and rolled back with it, refused outside one, and carries the request id and client address:** `AuditRecorderIT`.
- **Redaction as HMAC-SHA-256 plus length, never plaintext; a key is required in prod:** `SnapshotDiffTest`, `AuditRecorderIT`, `AuditConfigTest`.
- **The runtime user cannot update or delete audit rows (database grants):** `PostgresDatabaseGrantsIT` and `OracleDatabaseGrantsIT`, against `GrantsMatrix`.
- **Case timelines and restricted filtering:** `AuditTrailServiceIT`.
- **The whole trail, case-less events included, is readable by Compliance Admins only, on a bounded, paged screen; redacted values stay redacted** (#274, BUILD_PLAN §3.2 "View audit across all cases"): `AuditControllerIT` refuses every other role with 403, a System Admin who also holds Compliance Admin included, and checks each filter, paging, the 31-day range bound, escaping, and that a redacted value reads "redacted (n characters)" with neither its plaintext nor its HMAC on the page; `CasePermissionServiceTest` (the grant), `AuditTrailServiceIT` (only an unrestricted scope sees anything; wildcards in the actor filter are text), `AuditChangesTest` (the read model carries no HMAC), `AuditAccessibilityIT` (axe and Nu).
- **The audit export is audited, bounded and redacted** (#274, BUILD_PLAN §9 "audit export for Compliance Admins"): `AuditExportIT` checks the CSV line by line (newest first, case numbers, a redacted value as its length with no HMAC, a formula cell defused), one `AUDIT_EXPORTED` event with the row count and the filter, a 422 with nothing exported or audited over `app.export.max-rows` or for a bad range, and 403 with nothing audited for a System Admin or an Analyst; `RateLimitIT` shows it draws on the search bucket and a refused export is not audited.
- **A restricted-case view and download each write one event:** `RestrictedViewAuditIT`.
- **One application clock:** `InstantSourceConfigTest`.

**Configured only:** NTP on the pods (platform).

### Input validation (SI)
**Verified:**
- **Uploads: the type allow-list from bytes with the extension cross-check:** `AttachmentTypesTest`.
- **Uploads: the intake pipeline, with the temp file removed on failure:** `UploadIntakeTest`.
- **Uploads: synchronous ClamAV scan, fail-closed, EICAR quarantined:** `ClamdClientTest`, `ClamdClientIT` (real clamd behind TLS), `CaseAttachmentUploadIT`.
- **Uploads: quarantined files never served, release with a reason:** `CaseAttachmentDownloadIT`, `CaseAttachmentReleaseIT`.
- **Uploads: size limits:** `MultipartLimitsTest`, `OversizedUploadIT`.
- **Uploads: abuse through the browser:** `UploadAbuseBrowserIT` (disguised and dangerous files refused and stored nowhere; a hostile name downloads as a plain attachment).
- **Uploads: external-scanning mode:** `ExternalScanningUploadIT`, `ScanningModeTest`.
- **Markdown rendered then sanitized:** `MarkdownRendererTest` over the XSS corpus; `StoredReportSanitizerTest`.
- **CSV export cells defeat formula injection:** `CsvFormatTest`; through the audit export, `AuditExportIT`.
- **Content-Disposition cannot be split by a file name:** `AttachmentDispositionTest`, `CaseAttachmentDownloadIT`.
- **Malformed input is a 400, never a 500 or an echo:** `MalformedParametersIT`, `MalformedMultipartIT` (every mapped route), `ControlCharactersIT` and `ControlCharacterSweepIT` (a control character in a parameter name or value, every mapped route; `UploadIntakeTest`, `CaseAttachmentUploadIT` for an upload's file name).
- **Bean Validation reaching the web layer is a 422:** `WebShellIT`.

**Configured only:** Bean Validation and size constraints on each form, which are exercised by the controller ITs of each form but not enumerated by a rule.

### Injection
**Verified:**
- **No string-built JPA or native query:** Semgrep `semgrep:jpa-dynamic-query-string` (rule tests in `security/semgrep/jpa-dynamic-query.java`).
- **Native SQL only in `*JdbcDao` classes, each statement valid as the runtime user:** `RuntimeUserIT`.
- **No `th:utext`:** Semgrep `semgrep:thymeleaf-unescaped-output`.
- **`tk:safe` accepts only sanitizer output:** `SafeHtmlDialectTest`, `WebShellIT`.
- **SAST over the whole code base:** CodeQL; SpotBugs with FindSecBugs.
- **Dynamic:** ZAP's injection rules in the active scan (see [DAST](#dast-the-zap-plan)).
- **Mail values escaped:** `MailTemplatesTest`.

### Browser security
**Verified:**
- **The exact CSP with a fresh nonce on every script:** `WebShellIT`.
- **The CSP on every page, with no violation reported by a real browser:** `CspCrawlIT`.
- **Security headers (nosniff, frame options, Referrer-Policy) on application responses and on Tomcat's own error page:** `SecurityDefaultsIT`, `TomcatErrorPageHeadersIT`.
- **The htmx configuration fixed by the meta tag:** `WebShellIT`.
- **`HX-Redirect` instead of a login page for an anonymous htmx request; a stale token reloads the page:** `WebShellIT`, `HtmxAwareResponsesTest`.
- **`hx-on*`, `js:` values and `hx-vars` banned, `data-` prefix included:** Semgrep `semgrep:htmx-inline-script`.
- **Spring Security protections never disabled:** Semgrep `semgrep:spring-security-protection-disabled`.
- **CSRF: a mutation without the token is a 403:** `SecurityDefaultsIT`, `RouteSweepIT` (every mutating route refuses a request without its own session's token, told apart from a handler refusal by `HandlerCalls`).
- **Valid HTML on every page state the accessibility tests check:** `NuHtmlCheckIT` and every `*AccessibilityIT` through the shared page check (the Nu Html Checker, #239).
- **Cookies `Secure; HttpOnly; SameSite=Lax`** (set in `SessionConfig`): `SessionHardeningIT` reads the `Set-Cookie` attributes behind the router.
- **HSTS** on responses to requests that arrive as HTTPS behind the trusted router (`max-age=31536000 ; includeSubDomains`), and not on plain HTTP: `SessionHardeningIT`. It comes from Spring Security's default header writer, which `SecurityConfig` leaves on; the route in `deploy/base/route.yaml` sets no HSTS of its own.
- **No CORS:** no CORS configuration exists. `NoCorsIT` sends a request with `Origin`, and a preflight for every mapped route, anonymous and signed in; none gets an `Access-Control-*` header.

### Secrets (SC)
**Verified:**
- **No secret in a commit:** gitleaks on every PR and push.
- **The audit key is required in prod:** `AuditConfigTest`.
- **Each workload reads only its own secret files:** `ImporterJobManifestTest` (the importer Job), `ImporterStagingTtlManifestTest` (the staging TTL CronJob: the import credential and audit key, no source password or hash key; and its own network, #276: the union of every policy selecting its pod in `deploy/base` and `deploy/jobs/importer` allows only DNS and the database, not clamd, the source or `trailkeep-egress`, for the clamd and the external-scanning variants), `MigrateJobManifestTest` (the migration Job), `PurgeCronJobManifestTest` (the purge CronJob: its own credential, no other Secret, no env injection), `WebDeploymentManifestTest` (the web Deployment: secrets only as files of `trailkeep-web`, never env literals).

**Platform:** GitHub secret scanning and push protection. They are repository settings, not visible in the repository.


### Transport
**Verified:**
- **App → clamd over TLS (the sidecar):** `ClamdClientIT` against a real clamd behind TLS.
- **Importer → JDBC sources over verified TLS** (ADR-0014 rule 2, #259): `SourceUrlTest` refuses, per vendor, a URL without it (PostgreSQL `sslmode=verify-full`, Oracle `tcps://` with `ssl_server_dn_match=true`, SQL Server `encrypt=true` or `strict`) in `validate-mapping` and in the DAO, and shows the test-only `import.source.plaintext-for-tests` fails startup outside the test profiles and with `prod`; `PostgresSourceTlsIT` reads a PostgreSQL container over `verify-full` with a throwaway CA, and is refused without the CA.
- **Forwarded headers trusted only from the router's address pattern:** `ForwardedHeadersTrustedProxyIT`, `ForwardedHeadersSpoofIT`, `ForwardedHeadersPrivatePeerIT`.
- **App → SMTP with STARTTLS required in prod** (`src/main/resources/application-prod.yml`): `MailStartTlsTest` reads `mail.smtp.starttls.enable` and `mail.smtp.starttls.required` from the session of the mail sender Spring Boot builds from the prod profile.

**Configured only:**
- edge TLS with an HTTP redirect (`deploy/base/route.yaml`);
- app → Oracle over `tcps`, which is the deployment's JDBC URL. No test connects that way.

### Data at rest
**Verified:** attachment hashes recorded and checked when stored. `CaseAttachmentUploadIT` covers this ("stored, verified and audited").

**Platform:** Oracle TDE or PostgreSQL volume encryption, and the backup schedule, storage and retention. Nothing in the repository tests them. A restore from the vendors' logical backups (`pg_dump`, Data Pump) is drilled by hand at the release volume (`PostgresRestoreDrillIT`, `OracleRestoreDrillIT`, `docs/ops/restore-drill.md`).

### Logging
**Verified:**
- **Structured JSON and plain-text output, both with the request id on every line:** `JsonLoggingIT`, `TextLoggingIT`, `RequestIdFilterTest`.
- **Control characters escaped by the JSON encoder:** `JsonLoggingIT`. A value holding line breaks, a forged JSON fragment, an ANSI escape and NUL stays one line of valid JSON, and its message is the value exactly.
- **No restricted-case text and no email address in the log; people logged by user id:** `LoggingRulesIT` runs the paths that log around a restricted case, with the output captured:
  - create, open and edit, a comment, a stored and a refused upload;
  - an update request, with its alert and queued mail, and a mail dispatch that fails;
  - a sign-in refused for a disabled user, and a role conflict at sign-in.

  Every text and address it writes carries one marker, which must never appear in the log, and each path must have logged its line with the ids.

### Errors
**Verified:**
- **Generic error pages that never echo input or show an exception:** `MalformedParametersIT`, `MalformedMultipartIT`, `ShellAccessibilityIT` (the 500 page names no exception).
- **Tomcat's own error page without server information:** `TomcatErrorPageHeadersIT`.
- **The not-found page for an unreadable case:** `WebShellIT`.
- **No error attribute in any error response** (message, stack trace, exception, binding errors, request path; `spring.web.error.include-*` in `src/main/resources/application.yml`): `ErrorPropertiesTest` asserts the values Spring Boot binds under the prod profile.
  - Until #245 these keys were `server.error.*`, which Spring Boot 4 no longer reads. The row said "configured", but nothing was configured: the attributes were off only by Boot's defaults, and the request path was on.
  - `ConfigurationKeysTest` fails on any key in a profile file that Boot's configuration metadata marks as removed (deprecated at level `error`), so a renamed key cannot sit unread again.

### Availability
**Verified:**
- **Request size limits:** `MultipartLimitsTest`, `OversizedUploadIT`.
- **Bounded concurrent downloads, 503 with `Retry-After` when full:** `CaseAttachmentDownloadIT`, `ConcurrentDownloadHeapIT`.
- **The scheduler lock:** `SlaEvaluatorIT`.
- **Management endpoints only on the management port, and only health, info and prometheus:** `ManagementEndpointsIT`.
- **Per-user rate limits on search and upload** (`RateLimitInterceptor`, #247): `RateLimitIT` drives each limit to a 429 with `Retry-After`, on the error page and as the htmx problem fragment, without echoing what was sent; shows a refused upload is never scanned, the CSV export, the audit search and the audit export (#274) share the search bucket, another person is unaffected, and each refusal is counted on `trailkeep.rate_limit.refusals` and logged with the user id and route only. `TokenBucketsTest` covers refill, the wait reported, idle-bucket eviction and a wrapping monotonic clock; `RateLimitInterceptorTest` that the prod profile refuses `app.rate-limit.enabled=false` and that the shipped limits are on. `ShellAccessibilityIT` runs axe and the Nu validator on the 429 page and the swapped htmx fragment.
  - **Platform:** the buckets are in memory per replica, so a multi-replica deployment also sets a limit at the route or ingress (`deploy/README.md`, "Runtime properties").

**Configured only:** database pool sizing.

### Supply chain
**Verified:**
- **Enforcer bans and convergence:** `enforcer:bannedDependencies`, `enforcer:requireUpperBoundDeps`, `enforcer:dependencyConvergence`.
- **The licence allow-list:** `security/licenses-allowed.txt`.
- **SBOM scanned by Trivy:** `.github/workflows/main.yml#trivy-sbom`.
- **The image scanned by Trivy and checked by Dockle; manifests by kube-linter:** `.github/workflows/main.yml#image`.
- **Actions pinned by commit SHA:** reviewed in PRs, with actionlint in `.github/workflows/ci.yml#actionlint`.
- **Release-time signature, SBOM attestation and SLSA provenance:** `.github/workflows/release.yml`, checked after publishing by `.github/workflows/verify-image.yml` (see gap 22).
- **The release evidence pack** (WP-30 part d, BUILD_PLAN §12.3): `.github/release/evidence-pack.txt` lists every file, its source and the version that first requires it; `.github/workflows/release.yml#preflight` checks the CHANGELOG section, the checked-in files and the tag (signed and verified by GitHub from 1.0.0) before anything is built; `.github/workflows/release.yml#github-release` assembles the pack with `.github/scripts/evidence-pack.py`, signs its `SHA256SUMS` keylessly and refuses a pack with a required file missing, an unlisted file or a wrong checksum. `ReleaseEvidencePackTest` pins the workflow to the list and runs the script against fixtures.
- **The published image resolves its dependencies cold; only scanned images build offline from their job's cache** (#290, after #230): `ImageBuildDependenciesTest` pins release.yml's `cold` mode and its job's lack of any Maven cache, the `offline` mode of main.yml's and zap.yml's image builds, the script's cold default (which drops any seed), the offline build's blocked Central hosts, and the resolver retry budget.

**Configured only:**
- Dependabot, weekly;
- base images pinned by digest at build time (`.github/scripts/build-image.sh`);
- OWASP Dependency-Check, which does not run until `NVD_API_KEY` is set (#30).

### Separation of duties
**Verified:**
- **System Admin has no case access:** `PermissionMatrixTest`; nor audit access, even holding Compliance Admin: `CasePermissionServiceTest`, `AuditControllerIT`.
- **Approver ≠ requester and owner:** `CasePermissionServiceTest`, `ClosureRequestIT`.
- **Purge runs as its own user, which may only turn a case into a tombstone; the purge profile starts no web application:** `PurgeIT`, `PurgeProfileIT`, `PostgresDatabaseGrantsIT`.
- **Purge skips anything under legal hold:** `PurgeIT`, `LegalHoldIT`.
- **Import sign-off and run moves split between database users:** `PostgresDatabaseGrantsIT`.
- **Importer staging is purged on a schedule, as the import user, whether or not another command runs** (#275): `StagingTtlIT` and `OracleStagingTtlIT` run `expire-staging` alone and see an expired run's staging and content area removed and a fresh run's kept, and a second TTL pass wait for the first's lock and leave the run it purged; `ImporterStagingTtlManifestTest` keeps the CronJob live, one run at a time, with the content claim and off the source route.

- **The purge CronJob has its own Secret, which the web Deployment never mounts, and its own network:** `PurgeCronJobManifestTest` and `WebDeploymentManifestTest` (#246). The pod's egress is DNS and the database only (`deploy/base/purge-networkpolicy.yaml`); `trailkeep-egress`, which reaches the identity provider and the mail relay, excludes it, as it excludes the importer and migration Jobs. The `clamav-ca` mount stays: the `purge` profile still builds the clamd client, which resolves its SSL bundle at startup, though the pod never scans.

### Privacy
**Verified:**
- **Export redaction (no restricted rows, details redacted for readers):** `CaseExportControllerIT`, `CaseExportIT`; the audit export writes a redacted value as its length only: `AuditExportIT`.
- **Mail without the closure summary:** `ClosureRequestIT`.
- **Personal mail links only into the application:** `NotificationServiceIT`.
- **Retention with legal hold:** `PurgeIT`, `LegalHoldIT`, `ArchiveJobIT`.
- **No restricted text reaches another reader:** `RestrictedLeakCrawlIT`.

**Documented:** the "where is PHI" data map of BUILD_PLAN §9 is `security/DATA_MAP.md` (#248). It is a document,
not a test; each control it quotes names its test here.

## WP-28 acceptance (IMPLEMENTATION_PLAN Part C)

| Item | Verified by | Runs | Status |
|---|---|---|---|
| CSP | `WebShellIT`, `CspCrawlIT` | every PR | verified |
| CSRF | `SecurityDefaultsIT`, `RouteSweepIT` | every PR | verified |
| 404-not-403 enumeration | `RouteSweepIT`, `CaseRootedInvisibilityIT` | every PR | verified |
| Restricted leak | `RestrictedLeakCrawlIT` (fixture `LeakFixture`) | every PR | verified |
| Upload abuse | `UploadAbuseBrowserIT`, `CaseAttachmentUploadIT` | every PR | verified |
| Forwarded-header spoof from a non-router private address | `ForwardedHeadersPrivatePeerIT`, `ForwardedHeadersSpoofIT` | every PR | verified |
| 0 Medium+ DAST | `.github/workflows/zap.yml`; plan pinned by `ZapPlanTest`, `OpenApiCoverageTest`, `DastVerdictTest` on every PR | nightly, release tag | **shown**: the release-mode run on `main` at 051c59b, [43968881937](https://github.com/techautomator414/trailkeep/actions/runs/43968881937), is green for both identities; every release fails on Medium+ or a partial scan |
| Sonar gate A | `.github/workflows/main.yml#sonarcloud` | `main`, nightly, release | **not shown**: skipped until `SONAR_TOKEN` is set (#30) |
| Dependency-Check clean | `.github/workflows/main.yml#dependency-check` | `main`, nightly, release | **not shown**: skipped until `NVD_API_KEY` is set (#30) |

## E2E security tests (BUILD_PLAN §12.1)

| Assertion | Test | Runs |
|---|---|---|
| CSP headers | `CspCrawlIT`, `WebShellIT` | every PR |
| CSRF rejection | `RouteSweepIT`, `SecurityDefaultsIT` | every PR |
| 404, not 403, for cross-team and restricted cases | `RouteSweepIT` (fixture `SweepCases`) | every PR |
| No restricted leak in search or export | `RestrictedLeakCrawlIT`, `CaseExportControllerIT` | every PR |
| Upload rejection of disguised executables | `UploadAbuseBrowserIT`, `ExternalScanningUploadIT` | every PR |
| Forwarded-header spoofing ignored | `ForwardedHeadersSpoofIT`, `ForwardedHeadersPrivatePeerIT` | every PR |

All run as Playwright or HTTP tests against the real server inside `./mvnw verify`, so they gate every PR.

## DAST: the ZAP plan

- **The plan:** `security/zap/plan.yaml`.
  - The spider (capped) and the AJAX spider run first.
  - Then the hand-written API description, `security/zap/openapi.yaml`, imported with the session's CSRF token and the seeded identifiers.
  - Then ZAP's default active-scan policy, in two active scans (#231), each with its input vectors set by the `activeScan-config` job before it:
    - first, every route of the application and the management port, with no form body attacked (query strings and the requests themselves), capped at 60 minutes;
    - last of all scanning jobs, the form bodies of every POST path, with no query string attacked, over the `trailkeep-forms` context: the application without its CSV downloads, so that every page is checked for a stored payload. Capped at 60 minutes.
  - Every page's query string is attacked before any form stores one of ZAP's payloads, so no rule there takes a stored payload, shown back escaped, as proof. Before the split, that produced a template-injection false positive on the cases list (90035), then suppressed.
  - The CSV downloads (`/cases/export.csv`, `/reports/aging.csv`, `/reports/compliance.csv`, `/reports/runs/{id}.csv`, `/admin/audit/export.csv`) are attacked by every rule in the first scan, but are not places the second scan looks for stored payloads: they are attachments with nosniff, never rendered, and carry titles and names verbatim by design. As a sink, the export produced the stored-XSS false positive 40014, then suppressed.
  - The plan fails on any alert of Medium or higher.
- **Rate limits in the scan stack:** `app.rate-limit.search.*` and `upload.*` are raised to the test-profile values (100000) in `dast-app.sh`, with `enabled` kept true. The scanner is exempted from availability throttling so the active scan reaches search and upload; at the production limits it was refused 429 almost every time, which ZAP's boolean SQL-injection rule reads as an injection, and it lost coverage (#270). `RateLimitIT` verifies the control itself; the prod profile still refuses `enabled: false`.
- **Identities:** `compliance.admin` and `alpha.analyst`, each on its own stack built from the commit. The stack is set up by `.github/scripts/dast-app.sh` and seeded by `DastSessionIT`, which runs only in the scan, not in `./mvnw verify`.
- **The verdict:** `.github/scripts/dast-zap.sh` runs the scan and `.github/scripts/dast-verdict.sh` decides.
  - In a release, a partial scan also fails: either active scan cut off at its cap or not shown completing in `zap.log`, or a plan stopped by a High insight.
  - In nightly, a partial scan only warns.
- **Pinned on every PR:**
  - `ZapPlanTest`: the gate, the crawl caps, the API import, the two active scans (order, input vectors, caps, and the `trailkeep-forms` context equal to the description's application paths without the `.csv` downloads) and every suppression's justification, review and expiry;
  - `DastVerdictTest`: the verdict policy, for each active scan;
  - `OpenApiCoverageTest`: the description against every mapped operation.
- **Suppressions** (`security/zap/suppressions.yaml`). Each needs a justification, the reviewing PR and an expiry at most 90 days out. An expired entry stops suppressing.

| Rule | Where | Reviewed in | Expires |
|---|---|---|---|
| `zap:40018` SQL injection | `/admin/sla-policies/{id}` | #225 | 2027-02-14 |
| `zap:40042` Spring Actuator information leak | `localhost:8081/actuator/health` | #233 | 2027-02-14 |
| `zap:90035` Server Side Template Injection | `POST /admin/{case-types,sla-policies,teams}[/{id}]` | #283 | 2027-02-20 |

The suppressions of rule 90035 (SSTI, `/cases`) and rule 40014 (persistent XSS, `/cases/export.csv`) were removed in #231, with the split active scan described above: their cause was ZAP's own stored payloads. A narrower 90035 entry remains for the three admin configuration forms, where the forms scan reads its own stored payload back from each form's own list page; no scan ordering can separate the two (owner-approved, 2026-11-22).

## Gaps

Controls with no automated verification, or only part of it, one line each. A closed row keeps its number and names the test that closed it. Each open row ends with either:
- **Tracked: #nnn**, the issue that will close it; or
- **Position**, a scope or platform decision that leaves it outside the repository's tests, with the reason.

1. **Idle timeout (30 min):** closed, verified by `SessionHardeningIT` (#245).
2. **RP-initiated logout:** closed, verified by `KeycloakLoginIT` (#245).
3. **Step-up re-authentication:** not built. Position: cut for scope in v1 by BUILD_PLAN §9; admin actions are audited instead.
4. **Deny-by-default URL rules:** closed, verified by `DenyByDefaultIT` (#245).
5. **Session cookie flags (`Secure; HttpOnly; SameSite=Lax`):** closed, verified by `SessionHardeningIT` (#245).
6. **HSTS:** closed, verified by `SessionHardeningIT` (#245); the route still sets none of its own.
7. **No CORS:** closed, verified by `NoCorsIT` (#245).
8. **SMTP STARTTLS required:** closed, verified by `MailStartTlsTest` (#245).
9. **App → Oracle over `tcps`:** no test. Position: the JDBC URL is a deployment value, and nothing in the repository connects to a deployed Oracle; the deploying organization verifies it.
10. **Data at rest (TDE or volume encryption, backups):** encryption and the backups themselves are not tested in the repository. Position: a platform responsibility under BUILD_PLAN §9, owned by the database and storage platform. That a logical backup restores complete and usable is drilled by hand on both vendors (`docs/ops/restore-drill.md`, WP-30), not on every build.
11. **Logging rules (no restricted text, ids not emails, control characters escaped):** closed, verified by `LoggingRulesIT` and `JsonLoggingIT` (#245).
12. **Error attributes off (`spring.web.error.include-*`):** closed, verified by `ErrorPropertiesTest` (#245). The old `server.error.*` keys were not read under Spring Boot 4; `ConfigurationKeysTest` guards against that recurring.
13. **Rate limiting on search and upload:** closed, verified by `RateLimitIT` (#247). Built in-house (a token bucket per user in memory), not with Bucket4j; per replica, with a platform limit at the route for multi-replica deployments.
14. **The purge CronJob's credential separation:** closed, verified by `PurgeCronJobManifestTest` (#246).
15. **The web Deployment's secret mounts:** closed, verified by `WebDeploymentManifestTest` (#246). No Oracle wallet is mounted today; the test requires any added volume to be a Secret.
16. **GitHub secret scanning and push protection:** not verifiable from the repository. Position: they are GitHub repository settings outside the code; gitleaks covers every commit in CI.
17. **Pre-commit hooks** (gitleaks, Spotless, Checkstyle, SPDX in BUILD_PLAN §12.1): closed (#249). `.pre-commit-config.yaml` provides gitleaks, `./mvnw spotless:check` and the SPDX header check as opt-in hooks (CONTRIBUTING.md). Checkstyle is left to `./mvnw verify`; CI remains the gate.
18. **SonarCloud quality gate:** skipped until `SONAR_TOKEN` is set, so WP-28's "Sonar gate A" is not shown. Tracked: #30.
19. **OWASP Dependency-Check:** skipped until `NVD_API_KEY` is set, so "Dependency-Check clean" is not shown. Tracked: #30.
20. **Container images:** closed. Dependabot tracks the test images (#251, closing #235) and the `compose.yaml` images (#269) through its docker and docker-compose ecosystems; `ImageSourcesTest` checks the coverage. Still bumped by hand: the `Containerfile` UBI bases and the ZAP image in `zap.yml`.
21. **Licence allow-list:** skips the test scope. Position: test-scope dependencies are never shipped, in neither the jar nor the image.
22. **Image signature, SBOM attestation and SLSA provenance:** closed (#249). `release.yml#verify-published` calls `.github/workflows/verify-image.yml` after the provenance and before the GitHub Release: `cosign verify` and `cosign verify-attestation --type cyclonedx` (cosign v3 bundles) against the identity `release.yml@refs/tags/<tag>`, and `slsa-verifier verify-image` (the generator's legacy format) with `--source-uri` and `--source-tag`; any failure fails the release. The same workflow runs by hand against an existing tag's digest. Shown green against v0.2.0, and failing for a wrong tag and a wrong digest.
23. **Security review of the importer's plain SHA-256 mapping hash** (`HANDOFF.md` §6): flagged for WP-28; not done. #169 is a merged feature PR, not a tracker: this is an open decision, owned by the project owner's security review of the importer's mapping hash.
24. **Threat model (STRIDE):** closed, written as `security/THREAT_MODEL.md` (#248). Its residual risks name the
    open gaps above; it is re-checked at each phase gate.
25. **Data map:** closed, written as `security/DATA_MAP.md` (#248).

## Missing documents

| Document | Promised by | State |
|---|---|---|
| Threat model: STRIDE over the BUILD_PLAN §7.1 diagram, re-checked at each phase gate | BUILD_PLAN §9 (a Phase 1 deliverable) | closed: `security/THREAT_MODEL.md` (#248). It links to `docs/design/importer.md` §10 for the importer's own analysis |
| Data map: where restricted and personal data lives | BUILD_PLAN §9, Privacy | closed: `security/DATA_MAP.md` (#248) |
