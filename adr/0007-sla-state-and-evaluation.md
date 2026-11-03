<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0007: Keep SLA state in `CASE_SLA`, with one anchor rule and chunked evaluation

- **Status:** Accepted
- **Date:** 2026-11-03
- **Register rows:** IMPLEMENTATION_PLAN.md A.1 #8; A.4 #30; A.5 #47
- **Plan sections:** BUILD_PLAN.md §6, §8 rule 6

## Context and problem statement

SLA states change on a clock, not on a user action. If the evaluator wrote to the case row, users mid-edit would see optimistic-lock conflicts they did not cause. The anchor was also stated two ways in an earlier draft. And a per-pod health indicator for a lock-guarded job flaps and can restart a healthy replica that merely lost the lock.

## Decision drivers

- Editors never conflict with background jobs.
- One unambiguous anchor so aging is reproducible.
- Evaluation must scale and must not run twice per tick across replicas.
- Alerts must be sent exactly once per transition.

## Considered options

1. Compute SLA state at read time.
2. Store SLA columns on `CASE_RECORD`.
3. A side table with indexed state and due dates, written by services, evaluated in chunks under a lock.

## Decision outcome

**Option 3.**

- State lives in **`CASE_SLA`** (`CASE_ID PK, SLA_STATE, SLA_DUE_AT, AT_RISK_AT, SLA_CYCLE, LAST_EVALUATED_AT`), a side table without `@Version`, written by the `casefile`, `sla` and `closure` services and never by the case entity. Indexes: `(SLA_STATE, SLA_DUE_AT)` and `(SLA_STATE, AT_RISK_AT)`. Dates are nullable with a `CHECK` tying them to `NOT_APPLICABLE`.
- **One anchor rule.** On create, the clock starts at the policy anchor: `IDENTIFIED_ON` at end of day in `app.timezone`, or `OPENED_AT`. On reopen, the anchor is the reopen instant and **`SLA_CYCLE` increments**.
- **Chunked evaluation under ShedLock** (every 15 minutes; `lockAtMostFor=14m`, `lockAtLeastFor=1m`, `usingDbTime()`): id-ordered chunks of 500, one transaction per chunk, with history, `ALERT_LEDGER` and outbox rows written in the same chunk transaction. Pending Closure does not pause the clock.
- Evaluator health is a **cluster-level indicator** from `JOB_RUN`, exported as a metric and excluded from the Kubernetes probe groups. The scheduler pool is 4 threads so a long run never starves the dispatcher.

### Consequences

- **Good:** no editor conflicts; reproducible clock; exactly-once alert semantics through the ledger; a lost lock never restarts a pod.
- **Bad:** SLA data is denormalized into a second table that must be kept consistent by three services; calendar days only in v1 (business-day calendars are v1.1).

## Confirmation

- WP-21 acceptance: a fixture of 20 cases yields the expected transitions and exactly one email each across three runs and a restart; reopen resets per cycle.
- Migration `CHECK` constraint test and metadata-diff for `CASE_SLA`.
- Health-group test: the cluster indicator is absent from liveness and readiness (WP-08, WP-21).
- 90 % line coverage gate for the `sla` package (Part B).
