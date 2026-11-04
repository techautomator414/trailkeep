# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- CI image scan policy (issue #11, owner decision): the Trivy gates on the built image in `main.yml` and
  `release.yml` run with `--ignore-unfixed`, so only HIGH or CRITICAL findings that have a fixed version fail the
  build. Unfixed base-image findings still appear in the JSON and SARIF reports and in the release evidence pack.

### Added

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
