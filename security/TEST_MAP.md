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
| Dependabot | `.github/dependabot.yml` | weekly | pull requests | maven, github-actions and pip only; container images are not tracked |
| Image signature, SBOM attestation, SLSA provenance | `.github/workflows/release.yml#publish`, `.github/workflows/release.yml#provenance` | release tag | the release fails | exercised once, at `v0.1.0` |
| Pre-commit hooks | not in the repository | locally | advisory | **not provided** |

## Controls (BUILD_PLAN §9)

### Identity (IA)
**Verified:**
- **SSO only, provisioning, group-to-role mapping, a disabled user refused at login:** `KeycloakLoginIT` (real Keycloak dev realm), `TrailkeepOidcUserServiceTest`, `SecurityDefaultsIT` (anonymous requests go to the provider).
- **Spring Session JDBC with JSON attributes and no JDK serialization:** `SessionJsonTest` (a type outside the allow-list is not read back).
- **Authorities not persisted; rebuilt per request; disabled user loses the session; 8-hour absolute lifetime:** `SessionPermissionsFilterTest`.
- **Back-channel logout through the custom registry, its CSRF header kept, daily cleanup, sessions shared by two instances:** `JdbcOidcSessionRegistryIT` and `OracleJdbcOidcSessionRegistryIT`, `SharedSessionsIT` (two application instances), `BackChannelLogoutUriIT`.
- **Disabling a user ends their sessions everywhere:** `SharedSessionsIT`, `UserAdminServiceIT`.
- **The idle-expiry warning dialog is accessible:** `ShellAccessibilityIT`.

**Configured only:**
- the 30-minute idle timeout (`spring.session.timeout` in `src/main/resources/application.yml`): no test waits it out;
- the `SESSION` cookie name and flags (`SessionConfig`): see Browser security;
- RP-initiated logout, which `SharedSessionsIT` passes through but which nothing asserts reaches the provider's end-session endpoint.

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
- **Per-role controller behaviour:** the `web` controller ITs, for example `CaseControllerIT`, `CaseAssignmentControllerIT`, `ClosureDecisionControllerIT`, `TeamAdminControllerIT` and `UserAdminControllerIT`.
- **Module boundaries:** `ModularityTest`, `ImportOnlyApiRulesTest`.
- **Allow-listed sort and filter values:** `CaseListQueryTest`.

**Configured only:** deny-by-default URL rules in `SecurityConfig`. They are exercised by `SecurityDefaultsIT` and the route sweeps, but no test enumerates the rules themselves.

### Audit (AU)
**Verified:**
- **The event is written in the change's transaction and rolled back with it, refused outside one, and carries the request id and client address:** `AuditRecorderIT`.
- **Redaction as HMAC-SHA-256 plus length, never plaintext; a key is required in prod:** `SnapshotDiffTest`, `AuditRecorderIT`, `AuditConfigTest`.
- **The runtime user cannot update or delete audit rows (database grants):** `PostgresDatabaseGrantsIT` and `OracleDatabaseGrantsIT`, against `GrantsMatrix`.
- **Case timelines and restricted filtering:** `AuditTrailServiceIT`.
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
- **CSV export cells defeat formula injection:** `CsvFormatTest`.
- **Content-Disposition cannot be split by a file name:** `AttachmentDispositionTest`, `CaseAttachmentDownloadIT`.
- **Malformed input is a 400, never a 500 or an echo:** `MalformedParametersIT`, `MalformedMultipartIT` (every mapped route).
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

**Configured only:**
- **Cookies `Secure; HttpOnly; SameSite=Lax`:** set in `SessionConfig`; no test asserts the `Set-Cookie` attributes.
- **HSTS:** Spring Security's default header writer, which `SecurityConfig` leaves on, sends it on requests it sees as HTTPS behind the trusted router. No test asserts it, and the route in `deploy/base/route.yaml` sets no HSTS of its own.
- **No CORS:** no CORS configuration exists, so Spring answers no cross-origin requests. No test asserts the absence.

### Secrets (SC)
**Verified:**
- **No secret in a commit:** gitleaks on every PR and push.
- **The audit key is required in prod:** `AuditConfigTest`.
- **Each workload reads only its own secret files:** `ImporterJobManifestTest` (the importer Job), `MigrateJobManifestTest` (the migration Job).

**Platform:** GitHub secret scanning and push protection. They are repository settings, not visible in the repository.

**Configured only:** the web Deployment's and purge CronJob's secret mounts (`deploy/base/deployment.yaml`, `deploy/base/purge-cronjob.yaml`), which only kube-linter reads.

### Transport
**Verified:**
- **App → clamd over TLS (the sidecar):** `ClamdClientIT` against a real clamd behind TLS.
- **Forwarded headers trusted only from the router's address pattern:** `ForwardedHeadersTrustedProxyIT`, `ForwardedHeadersSpoofIT`, `ForwardedHeadersPrivatePeerIT`.

**Configured only:**
- edge TLS with an HTTP redirect (`deploy/base/route.yaml`);
- SMTP STARTTLS required in prod (`src/main/resources/application-prod.yml`);
- app → Oracle over `tcps`, which is the deployment's JDBC URL. No test connects that way.

### Data at rest
**Verified:** attachment hashes recorded and checked when stored. `CaseAttachmentUploadIT` covers this ("stored, verified and audited").

**Platform:** Oracle TDE or PostgreSQL volume encryption, and backups. Nothing in the repository tests them.

### Logging
**Verified:** structured JSON and plain-text output, both with the request id on every line: `JsonLoggingIT`, `TextLoggingIT`, `RequestIdFilterTest`.

**Configured only:**
- control characters escaped by the JSON encoder (Logback configuration);
- never logging description, subject or attachment names of restricted cases;
- user ids rather than emails.

These rules are followed in code review, but no test asserts them.

### Errors
**Verified:**
- **Generic error pages that never echo input or show an exception:** `MalformedParametersIT`, `MalformedMultipartIT`, `ShellAccessibilityIT` (the 500 page names no exception).
- **Tomcat's own error page without server information:** `TomcatErrorPageHeadersIT`.
- **The not-found page for an unreadable case:** `WebShellIT`.

**Configured only:** `server.error.include-*` all off (`src/main/resources/application.yml`). This is exercised by the tests above but not asserted key by key.

### Availability
**Verified:**
- **Request size limits:** `MultipartLimitsTest`, `OversizedUploadIT`.
- **Bounded concurrent downloads, 503 with `Retry-After` when full:** `CaseAttachmentDownloadIT`, `ConcurrentDownloadHeapIT`.
- **The scheduler lock:** `SlaEvaluatorIT`.
- **Management endpoints only on the management port, and only health, info and prometheus:** `ManagementEndpointsIT`.

**Not built:** per-user rate limiting on search and upload. There is no Bucket4j and no other limiter.

**Configured only:** database pool sizing.

### Supply chain
**Verified:**
- **Enforcer bans and convergence:** `enforcer:bannedDependencies`, `enforcer:requireUpperBoundDeps`, `enforcer:dependencyConvergence`.
- **The licence allow-list:** `security/licenses-allowed.txt`.
- **SBOM scanned by Trivy:** `.github/workflows/main.yml#trivy-sbom`.
- **The image scanned by Trivy and checked by Dockle; manifests by kube-linter:** `.github/workflows/main.yml#image`.
- **Actions pinned by commit SHA:** reviewed in PRs, with actionlint in `.github/workflows/ci.yml#actionlint`.
- **Release-time signature, SBOM attestation and SLSA provenance:** `.github/workflows/release.yml`.

**Configured only:**
- Dependabot, weekly;
- base images pinned by digest at build time (`.github/scripts/build-image.sh`);
- OWASP Dependency-Check, which does not run until `NVD_API_KEY` is set (#30).

### Separation of duties
**Verified:**
- **System Admin has no case access:** `PermissionMatrixTest`.
- **Approver ≠ requester and owner:** `CasePermissionServiceTest`, `ClosureRequestIT`.
- **Purge runs as its own user, which may only turn a case into a tombstone; the purge profile starts no web application:** `PurgeIT`, `PurgeProfileIT`, `PostgresDatabaseGrantsIT`.
- **Purge skips anything under legal hold:** `PurgeIT`, `LegalHoldIT`.
- **Import sign-off and run moves split between database users:** `PostgresDatabaseGrantsIT`.

**Configured only:** the purge CronJob's own Secret, which the web Deployment never mounts (`deploy/base/purge-cronjob.yaml`). No manifest test asserts it, unlike the importer and migration Jobs.

### Privacy
**Verified:**
- **Export redaction (no restricted rows, details redacted for readers):** `CaseExportControllerIT`, `CaseExportIT`.
- **Mail without the closure summary:** `ClosureRequestIT`.
- **Personal mail links only into the application:** `NotificationServiceIT`.
- **Retention with legal hold:** `PurgeIT`, `LegalHoldIT`, `ArchiveJobIT`.
- **No restricted text reaches another reader:** `RestrictedLeakCrawlIT`.

**Missing:** the "where is PHI" data map (security/DATA_MAP.md in BUILD_PLAN §9) does not exist.

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
  - Then ZAP's default active-scan policy over the application and the management port.
  - The plan fails on any alert of Medium or higher.
- **Identities:** `compliance.admin` and `alpha.analyst`, each on its own stack built from the commit. The stack is set up by `.github/scripts/dast-app.sh` and seeded by `DastSessionIT`, which runs only in the scan, not in `./mvnw verify`.
- **The verdict:** `.github/scripts/dast-zap.sh` runs the scan and `.github/scripts/dast-verdict.sh` decides.
  - In a release, a partial scan also fails: an active scan cut off at its cap, or a plan stopped by a High insight.
  - In nightly, a partial scan only warns.
- **Pinned on every PR:**
  - `ZapPlanTest`: the gate, the crawl caps, the API import and every suppression's justification, review and expiry;
  - `DastVerdictTest`: the verdict policy;
  - `OpenApiCoverageTest`: the description against every mapped operation.
- **Suppressions** (`security/zap/suppressions.yaml`). Each needs a justification, the reviewing PR and an expiry at most 90 days out. An expired entry stops suppressing.

| Rule | Where | Reviewed in | Expires |
|---|---|---|---|
| `zap:90035` SSTI | `/cases` | #225 | 2027-02-14 |
| `zap:40014` persistent XSS | `/cases/export.csv` | #225 | 2027-02-14 |
| `zap:40018` SQL injection | `/admin/sla-policies/{id}` | #225 | 2027-02-14 |
| `zap:40042` Spring Actuator information leak | `localhost:8081/actuator/health` | #233 | 2027-02-14 |

90035 and 40014 share a root cause, ZAP's own stored payloads (#231). Its fix is due before their expiry.

## Gaps

Controls with no automated verification, or only part of it, one line each. Each row ends with either:
- **Tracked: #nnn**, the issue that will close it; or
- **Position**, a scope or platform decision that leaves it outside the repository's tests, with the reason.

1. **Idle timeout (30 min):** configured; no test lets a session idle out. Tracked: #245.
2. **RP-initiated logout:** nothing asserts the redirect reaches the provider's end-session endpoint. Tracked: #245.
3. **Step-up re-authentication:** not built. Position: cut for scope in v1 by BUILD_PLAN §9; admin actions are audited instead.
4. **Deny-by-default URL rules:** exercised, but not enumerated by a test. Tracked: #245.
5. **Session cookie flags (`Secure; HttpOnly; SameSite=Lax`):** configured in `SessionConfig`; no test asserts the `Set-Cookie` attributes. Tracked: #245.
6. **HSTS:** left to Spring Security's default on HTTPS requests; no test asserts it, and the route sets none. Tracked: #245.
7. **No CORS:** no test asserts that cross-origin requests get no CORS headers. Tracked: #245.
8. **SMTP STARTTLS required:** configured in the prod profile; no test. Tracked: #245.
9. **App → Oracle over `tcps`:** no test. Position: the JDBC URL is a deployment value, and nothing in the repository connects to a deployed Oracle; the deploying organization verifies it.
10. **Data at rest (TDE or volume encryption, backups):** nothing tested in the repository. Position: a platform responsibility under BUILD_PLAN §9, owned by the database and storage platform.
11. **Logging rules (no restricted text, ids not emails, control characters escaped):** no test asserts them. Tracked: #245.
12. **`server.error.include-*`:** behaviour tested, keys not asserted. Tracked: #245.
13. **Rate limiting on search and upload (Bucket4j):** not built. Tracked: #247.
14. **The purge CronJob's credential separation:** no manifest test, unlike the importer and migration Jobs. Tracked: #246.
15. **The web Deployment's secret mounts:** no manifest test. Tracked: #246.
16. **GitHub secret scanning and push protection:** not verifiable from the repository. Position: they are GitHub repository settings outside the code; gitleaks covers every commit in CI.
17. **Pre-commit hooks** (gitleaks, Spotless, Checkstyle, SPDX in BUILD_PLAN §12.1): not provided; CI is the only gate. Tracked: #249.
18. **SonarCloud quality gate:** skipped until `SONAR_TOKEN` is set, so WP-28's "Sonar gate A" is not shown. Tracked: #30.
19. **OWASP Dependency-Check:** skipped until `NVD_API_KEY` is set, so "Dependency-Check clean" is not shown. Tracked: #30.
20. **Dependabot does not track container images** (test containers, the ZAP and vnu images, the UBI bases); they are bumped by hand. Tracked: #235.
21. **Licence allow-list:** skips the test scope. Position: test-scope dependencies are never shipped, in neither the jar nor the image.
22. **Image signature, SBOM attestation and SLSA provenance:** exercised once, at `v0.1.0`; nothing verifies a signature after publishing. Tracked: #249.
23. **Security review of the importer's plain SHA-256 mapping hash** (`HANDOFF.md` §6): flagged for WP-28; not done. Tracked: #169.
24. **Threat model (STRIDE):** BUILD_PLAN §9 lists it as a Phase 1 deliverable; it does not exist (see below). Tracked: #248.
25. **Data map (security/DATA_MAP.md):** BUILD_PLAN §9 promises it; it does not exist (see below). Tracked: #248.

## Missing documents

| Document | Promised by | State |
|---|---|---|
| Threat model: STRIDE over the BUILD_PLAN §7.1 diagram, re-checked at each phase gate | BUILD_PLAN §9 (a Phase 1 deliverable) | not in the repository. `docs/design/importer.md` discusses the importer's own risks, but there is no application-wide model. Tracked: #248 |
| security/DATA_MAP.md: where restricted and personal data lives | BUILD_PLAN §9, Privacy | not in the repository. Tracked: #248 |
