# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
