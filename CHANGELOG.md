# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
