<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0002: Build a modular monolith verified by Spring Modulith; `shared` owns cross-cutting contracts

- **Status:** Accepted
- **Date:** 2026-11-03
- **Register rows:** IMPLEMENTATION_PLAN.md Part B "Layering and modules" and "Transactions"; A.1 #6 (contract placement, amended r3); A.2 #24
- **Plan sections:** BUILD_PLAN.md §7.1, §7.4

## Context and problem statement

Trailkeep is deployed by one small team into a regulated environment. The scan surface, the operations surface and the mental model must stay small, yet the domain has distinct areas (cases, closure, SLA, documents, notifications) that must not reach into each other's persistence. Round 3 of the audit also found that the `CaseScope` contract was owned by a Phase 2 module that Phase 1 code already needed.

## Decision drivers

- One deployable, one image, one set of probes.
- Module seams must exist now so a split stays possible later.
- Boundaries must be machine-checked, not convention.
- Phase 1 code (audit) needs the access contract before the case module exists.

## Considered options

1. Microservices per domain area.
2. Single flat package layout with conventions.
3. Modular monolith with Spring Modulith verification and ArchUnit rules.

## Decision outcome

**Option 3.** Base package `io.trailkeep`; modules `shared, security, audit, user, casefile, closure, updaterequest, sla, document, notification, report, retention, importer, web`. A module exposes a `*Service` interface and record types in its root package; everything else is package-private. Modules talk through service interfaces and events, never through each other's repositories. No entity crosses a module boundary or reaches a template. `shared` owns cross-cutting contracts, notably `CaseScope`, `@CaseRooted`, `@CaseAccessPath`, `CaseScope.system()` and `all()`; `casefile` supplies the principal-backed implementation. Cross-module reactions use `@TransactionalEventListener(BEFORE_COMMIT)`; audit, outbox and ledger writes are synchronous in the originating transaction; no network I/O inside a transaction. Each module carries a `package-info.java` stating its responsibility and permitted callers.

### Consequences

- **Good:** one artifact to scan and operate; enforced seams; Phase 1 code can depend on `shared`.
- **Bad:** a single failure domain and a single release cadence. Modulith verification adds friction when a module needs a type from another and must instead export a record.

## Confirmation

- `ApplicationModules.verify()` as a test (WP-07).
- ArchUnit rules in `src/test/java/.../architecture`: repositories package-private, no entity in `web`, `@CaseRooted` injection rules.
- Javadoc and `package-info.java` presence checked at review (Part B).
