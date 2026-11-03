<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0001: Target Spring Boot 4.x only, with Java 21 as the compile target

- **Status:** Accepted
- **Date:** 2026-11-03
- **Register rows:** IMPLEMENTATION_PLAN.md A.1 #12; Part B "Language and framework"
- **Plan sections:** BUILD_PLAN.md §7.2 (Framework, Runtime rows)

## Context and problem statement

The original plan kept a Spring Boot 3.5 fallback in case an approved-software list lagged behind the current line. A fallback means two sets of starters, test slices and Jackson packages, and the 3.5 line is out of OSS support. Supporting both would double the test and scan surface for no engineering benefit.

## Decision drivers

- Security fixes only land on supported lines.
- Boot 4.0 moved the starters, the test slices and the Jackson package (Jackson 3), so one codebase cannot cleanly compile against both lines.
- Spring Security 7 defaults are what the scanners expect.
- JSpecify null-safety (Boot 4) is required by the NullAway gate.

## Considered options

1. Boot 3.5 with a planned upgrade later.
2. Support both lines behind compatibility shims.
3. Current 4.x line only.

## Decision outcome

**Option 3.** Spring Boot current 4.x (Spring Framework 7, Spring Security 7, Hibernate ORM 7, Jackson 3, JSpecify), using the 4.x starter names (`spring-boot-starter-webmvc`, `spring-boot-starter-security-oauth2-client`, per-technology test starters). **No 3.x compatibility.** Java 21 is the compile target; CI also runs the suite on the current Java LTS. Platform threads only (virtual threads on Java 25 are v1.1). If a deploying organization's approved-software list lags, it runs the published artifact under a documented exception; the project does not carry a fallback.

### Consequences

- **Good:** one dependency graph, current security defaults, no shim code, smaller SCA surface.
- **Bad:** an organization restricted to 3.x cannot adopt Trailkeep without an exception. Some third-party libraries may lag Boot 4 and need a reviewed workaround.

## Confirmation

- `maven-enforcer` rules: Java version, banned dependencies, dependency convergence (WP-01).
- Compilation with `-Xlint:all,-processing -Werror`, Error Prone and NullAway against `@NullMarked` packages.
- The Part G unit/IT matrix also runs on the current Java LTS.
