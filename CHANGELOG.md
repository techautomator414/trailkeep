# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
