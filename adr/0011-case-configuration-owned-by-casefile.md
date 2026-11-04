<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0011: Case configuration (SLA policies, case types, outcomes) is owned by `casefile`

- **Status:** Accepted
- **Date:** 2026-11-04
- **Register rows:** IMPLEMENTATION_PLAN.md A.1 #8 (SLA anchor), A.2 #20 (grants), A.4 #37 (migrations land with entities)
- **Plan sections:** BUILD_PLAN.md §4.6, §7.4 (`sla/ (SlaPolicy, …)`), §8 rule 7 (grants), Appendix A `V3__admin`

## Context and problem statement

BUILD_PLAN §7.4 places `SlaPolicy` in the `sla` module. The module rules (ADR-0002, each module's
`package-info.java`) let `sla` call `casefile`, because the evaluator reads cases, and forbid the reverse. Case
creation (WP-11) must read the case type and its SLA policy to initialise `CASE_SLA` (Part A #8: "Written by
`casefile`, `sla`, `closure` services"). With the policy in `sla`, `casefile` would have to call `sla`, a cycle that
Spring Modulith's `verify()` rejects. WP-10 has to put `SLA_POLICY`, `CASE_TYPE` and `CASE_TYPE_OUTCOME` somewhere.

## Decision drivers

- The module graph stays acyclic and `ApplicationModules.verify()` stays green.
- Case creation reads its configuration without an event round-trip or a duplicate read model.
- The SLA evaluator can still read policies.

## Considered options

1. Policies in `sla`, case types in `casefile`, and `casefile` calling `sla`: a cycle, rejected by Modulith.
2. Policies in `sla`, with `casefile` reading them through events or a copied read model: extra machinery and two
   sources of truth for one small table.
3. All case configuration in `casefile`, exported through `CaseConfigService`; `sla` reads it from there.

## Decision outcome

**Option 3.** `SlaPolicy`, `CaseType` and `CaseTypeOutcome` (V3) are package-private entities in `casefile`, and
`CaseConfigService` with its command and view records is the exported API. Compliance Admins administer them. `sla`
reads policies through `casefile`'s exported service, which its allowed dependency already permits.

`CASE_TYPE_OUTCOME` is replaced as a whole when a case type's outcome list changes, so the runtime user holds DELETE on
it. That extends the DELETE carve-outs of BUILD_PLAN §8 rule 7: the table is configuration, not case data or
evidence, and a closure request keeps its outcome as a `VARCHAR(40)` snapshot (Appendix A `CLOSURE_REQUEST.OUTCOME`),
not a foreign key, so removing an outcome from a type's list cannot alter or orphan history. Both vendor
`R__grants.sql` files record this, and `DatabaseGrantsIT` asserts it.

### Consequences

- **Good:** an acyclic graph; one source of truth; case creation reads configuration in-process.
- **Bad:** §7.4's `sla/ (SlaPolicy, …)` line is superseded by this record; `casefile` grows by three small entities.

## Confirmation

- `ModularityTest` (`ApplicationModules.verify()`) on every build.
- `CaseConfigServiceIT` and `DatabaseGrantsIT` (the `CASE_TYPE_OUTCOME` DELETE grant) on PostgreSQL and the Oracle matrix.
