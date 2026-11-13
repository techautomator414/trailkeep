<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0016: Record an imported case's alerts already raised as sent, so go-live sends no wave

- **Status:** Proposed
- **Date:** 2026-11-13
- **Register rows:** IMPLEMENTATION_PLAN.md A.4 #37 (migrations land with their entities; no migration here, a grant
  change only)
- **Plan sections:** BUILD_PLAN.md §6 (SLA evaluator, reminders tick, "Idempotency"); docs/design/importer.md §7.1
  ("Go-live alerts", owner decision 3 of 2026-11-05), §8.1 (relaxation 10), §5 (grants, N-d); ADR-0007, ADR-0014

## Context and problem statement

An import brings years of open cases at once. The load gives each one its SLA from its original dates and its state
as of the load (design §7.1), so many arrive `AT_RISK` or `OVERDUE`. Two jobs mail about SLAs:

- the **evaluator** writes a once-per-cycle `ALERT_LEDGER` row (`SLA_AT_RISK`, `SLA_OVERDUE`) and mails on a
  *transition* into the state. An imported case already holds its state, so it makes no transition;
- the **reminders tick** repeats the overdue alert once per `OVERDUE_REMINDER_DAYS` period after the due time, keyed
  `SLA_OVERDUE_REPEAT` with period `P<n>`. Every case imported overdue for longer than one period would get a repeat
  on the first tick, which is a mail wave for cases that have been overdue for years.

The owner decided (2026-11-05) to pre-mark: record the alerts as already sent, show the true SLA state, and report the
counts in the dry run.

## Decision drivers

- No mail about the backlog on day one; later changes alert as for any case.
- No second rule for "which period are we in": the tick's own rule decides both.
- No unique-key collision with a row the evaluator writes later.
- The import user stays insert-only on the ledger, like every ledger it writes.

## Considered options

1. Pre-mark in `ALERT_LEDGER`, through an import-only `sla` API, in the case's load transaction.
2. Suppress mail for imported cases in the evaluator and the tick (a flag or a date check).
3. Do nothing and let the first tick send the repeats.

## Decision outcome

Option 1.

- **What is written.** `sla.GoLiveAlertService.preMark(caseId, at)`, called by the load for each open case right
  after `CaseImportService.importCase`, in the same transaction, writes:
  - `SLA_AT_RISK` (period `CYCLE`) for an at-risk case;
  - `SLA_OVERDUE` (period `CYCLE`) for an overdue case and, once its first repeat is due, `SLA_OVERDUE_REPEAT` for
    the period `at` falls in;
  - nothing for an on-track case or a closed one.
- **One rule for the period.** `CaseSlaService.OverdueCase.repeatPeriod(now)` is the reminders tick's rule, and both
  the tick and the pre-marking call it. So the tick finds the period already told and sends nothing. The next period
  alerts normally.
- **No collision.** The evaluator writes a once-per-cycle row only on a transition into that state. An imported case
  already holds its state in its current cycle, so the evaluator never writes the row the load pre-marked. A transition
  `AT_RISK → OVERDUE` after the load writes `SLA_OVERDUE`, which was not pre-marked, and mails as usual.
- **True state shown.** `CASE_SLA` is untouched: the case page, lists and reports show the state as computed.
- **Dry-run counts.** `PRE_MARKED` per alert type (`SLA_AT_RISK`, `SLA_OVERDUE`, `SLA_OVERDUE_REPEAT`) on the
  go-live day, from the same forecast the load's state comes from (`CaseImportService.SlaForecast`), on the Import
  runs screen and in the dry-run report.
- **Grants (N-d).** The import user gets `INSERT` on `ALERT_LEDGER` (it had `SELECT` for validate) and draws
  `SEQ_ALERT_LEDGER` (`USAGE` on PostgreSQL; the `SELECT` it already had is the draw on Oracle). No `UPDATE`, no
  `DELETE`.
- **Import-only.** Only `importer` may call `GoLiveAlertService` (`ImportOnlyApiRulesTest`).

### Consequences

- Good: go-live sends no mail about the backlog, and a case's own changes after the load alert as before.
- Good: the ledger says which alerts the system considers sent, so a later audit can see the pre-marking: its rows'
  `RECORDED_AT` is the load time.
- Bad: if the load runs long before the cut-over, later periods' repeats are mailed during the gap. The runbook loads
  just before the cut-over, as it already does for `reconcile` (design §9, N-i).
- Bad: nobody was actually told about those alerts. That is the owner's accepted trade-off (decision 3). The dry run
  shows the counts before sign-off.
- Neutral: option 2 would put an import exception into two web jobs forever. Option 3 sends the wave.

## Confirmation

- `AbstractRemindersTickIT.goLiveAlertsPreMarked` (PostgreSQL and Oracle) pre-marks an overdue, an at-risk and an
  on-track case. It then shows the tick sends no repeat for the pre-marked period and sends the next period's.
- `AbstractLoadIT` (both vendors, as the import user with `app.db.schema` set) checks that an open case loaded
  overdue gets its two rows and a closed case none, and that a second load adds none.
- `AbstractDryRunIT` checks the `PRE_MARKED` figures. `SlaClockTest` checks the forecast's repeat rule.
- `GrantsMatrix` / `AbstractDatabaseGrantsIT` (both vendors) check the import user's `ALERT_LEDGER` grants statement by
  statement: `SELECT` and `INSERT` allowed, `UPDATE` and `DELETE` denied, the sequence drawn.
- `ImportOnlyApiRulesTest` fires on a planted caller.

## More information

ADR-0007 (SLA state and evaluation), ADR-0014 (importer), ADR-0015 (import principals).
