<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0005: Enforce case visibility through a `CaseScope` contract in `shared`

- **Status:** Accepted
- **Date:** 2026-11-03
- **Register rows:** IMPLEMENTATION_PLAN.md A.1 #6 (amended r3); A.4 #31; A.5 #43; Part A #29 (case number as URL key)
- **Plan sections:** BUILD_PLAN.md §3.2, §3.3, §9 (Authorization)

## Context and problem statement

Restricted cases must be invisible to principals who cannot read them. The first design said "every query method takes a `CaseScope`", which could not hold for inherited `JpaRepository` methods or derived queries. The contract type was owned by a Phase 2 module that Phase 1 code already needed. Audit events with a null `CASE_ID` (admin, login, export) were lost under a case-scoped filter. Returning 403 for unreadable cases would also confirm that a case exists.

## Decision drivers

- No code path may bypass the scope, including ones nobody remembered to exempt.
- The rule must be checkable by reflection and by ArchUnit.
- Non-case audit events must stay reachable by the right people.
- Case existence must not leak.

## Considered options

1. Convention plus code review ("remember to pass the scope").
2. Hibernate filters or row-level database security.
3. A scope contract in `shared`; case-rooted repositories that cannot expose unscoped methods.

## Decision outcome

**Option 3.**

- **`CaseScope`, `@CaseRooted`, `@CaseAccessPath` and `CaseScope.system()` live in `shared`** (delivered in WP-07). `casefile` provides the principal-backed implementation in WP-11.
- Case-rooted repositories **extend `Repository<T, ID>`, not `JpaRepository`**. They expose only custom-fragment methods implemented with Criteria/`Specification` that apply `scope.toPredicate()`. Single-entity loads are `findOne(scope, caseNumber)`. There are no inherited or derived query methods to exempt, so a reflection test can enumerate every declared method.
- `@CaseRooted` repositories are injected **only into `@CaseAccessPath` classes**.
- Audit events with a null `CASE_ID` are reachable only through `CaseScope.all(principal)`, which exists for Compliance Admins and for `system()`.
- **Unreadable cases answer 404**, exactly like absent ones. 403 is reserved for a denied action on a readable case. The case number is the only identifier in URLs. Recipients of Request Update must satisfy `CaseScope.canRead`, filtered server-side and re-checked in the service.

### Consequences

- **Good:** unscoped access is structurally impossible rather than discouraged; enumeration yields no signal.
- **Bad:** more boilerplate than `JpaRepository`; every new query is a hand-written Specification fragment; support staff see "not found" for cases they cannot read.

## Confirmation

- Reflection-driven invisibility test over every declared method of every `@CaseRooted` repository, absent vs. unreadable indistinguishable (WP-11 acceptance).
- ArchUnit: `@CaseRooted` repositories extend `Repository` and are injected only into `@CaseAccessPath` classes (WP-07).
- Part E: every new mutating endpoint is tested for allowed role, nearest disallowed role (403) and unreadable case (404).
- WP-28 security E2E: 404-not-403 enumeration test and restricted-leak test.
