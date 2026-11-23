<!-- SPDX-License-Identifier: Apache-2.0 -->
# The importer at the release volume (WP-30 part b)

WP-30 inherits two importer rows from HANDOFF section 6 "Performance": the dry run's `PeopleMatcher` looked people up
one by one (#173), and the dry run, the load and the staging purge had never been measured at volume. This page records
how the importer is measured at the release volume, the numbers before and after each fix, what the importer
guarantees that the fixes had to keep, and what remains.

The work is four stacked pull requests, each measured on its own:

| PR | Change |
|---|---|
| (a) | The volume harness and this page, with the numbers on `main` |
| (b) | `PeopleMatcher` matches a page of people in one query per way of matching (#173) |
| (c) | The import principals are read once per load, and the dry run and the load hold one page of legacy people |
| (d) | A case's comments and history are written together, so the load's inserts go in JDBC batches |

## What is measured, and against what

BUILD_PLAN's performance row (§12) and its release dataset are 50 000 cases and 500 000 comments; it sets no time for
an import. The importer's own design sets the rules a faster importer must keep (docs/design/importer.md §8.2): **one
case per transaction**, so a case is fully in or absent and a crashed load resumes at the next case; the ledger's unique
key, so a key is loaded once; and **memory**: "rows are streamed; no stage holds more than one batch". The Job runs
with a 1 536 MiB limit and a 1 152 MiB heap (`deploy/jobs/importer`), so the run uses that heap.

So the targets here are relative, not absolute: no command may hold the corpus in memory, the dry run and the load
should cost a bounded number of statements and transactions per page or per case rather than per row, and the result
must be the same data.

## The corpus

`perf/seed-legacy-source-postgresql.sql` is a synthetic legacy tracker, a SOURCE database shaped like the worked
example's (docs/importer/worked-example.md), deterministic and never applied by Flyway:

| What | How many | Shape |
|---|---:|---|
| People | 500 000 | A tracker that mirrors the whole directory. 1 000 match a Trailkeep user by external id, 1 000 by email in other letter case, 50 share an address two users have (`AMBIGUOUS_PERSON`), the rest match nobody. Only persons 1 to 5 000 own, write or change anything |
| Cases | 50 000 | Ten units, one in five open, one in ten confidential, one in five hundred on hold, opened over ten years |
| Notes | 500 000 | 8 per case, 208 for every hundredth case (49 500 × 8 + 500 × 208) |
| Files | 2 000 | One per 25 cases, about 2 KiB each |
| History | 100 000 | Two rows per case: a state change and a redacted description change |

That is 1 152 000 staged rows, of which the load writes 652 000 (cases, notes, files and history; people are matched,
not written).

## How it is measured

`PostgresImportVolumePerfIT` (and `OracleImportVolumePerfIT`, Trailkeep on Oracle with the same PostgreSQL source) seeds
the source in a container of its own, gives it a read-only source account, and runs the importer as the Job does:
`validate-mapping`, `extract`, `dry-run`, the sign-off, `load`, `reconcile` and `purge-staging`, each as the import user
in an application context of its own. Each command is timed from its context's start to its end, the heap is sampled
while it runs, and Hibernate's statistics count its JPA statements and transactions and its most frequent queries. The
statements the `*JdbcDao` classes run through `JdbcTemplate` (the search append, the written-hash read-backs, the case
numbers) are not in Hibernate's count. The outcome is asserted at volume: every row staged, the 50 ambiguous people
found and nothing FATAL, every case loaded with every note and no `LOAD` issue, the run `RECONCILED`, and staging empty
after the purge. The test removes everything it wrote.

```
./mvnw -Pperf-it test-compile \
  org.apache.maven.plugins:maven-failsafe-plugin:integration-test@perf-import \
  org.apache.maven.plugins:maven-failsafe-plugin:verify@perf-import
# Trailkeep on Oracle (Free 23; -Dtrailkeep.oracle.image=oracle-xe for XE 21), redo sized as deploy/README.md asks:
./mvnw -Pperf-it -Dtrailkeep.oracle.redo-mb=512 test-compile \
  org.apache.maven.plugins:maven-failsafe-plugin:integration-test@perf-import-oracle \
  org.apache.maven.plugins:maven-failsafe-plugin:verify@perf-import-oracle
# A tenth of the corpus (5 000 cases; the 5 000 people the cases name stay): -Dtrailkeep.perf.import.scale=0.1
```

The table goes to `target/perf/importer-volume-postgresql.md` (or `-oracle.md`). The test runs under `-Xmx1152m`, the
Job's heap.

### Proving the data is the same

The report reduces what the importer decided to digests, so a change can be shown to change none of it:

- **Dry-run digest**: every dry-run issue and every figure of the run summary (the forecast day left out).
- **Load digest**: each loaded case's owner and owner snapshot, and each comment's author and snapshot.
- **Load content digest**: everything the load wrote, row by row in id order: the cases, the comments with their
  bodies, the search text, the legacy history, the audit rows with their JSON, and the ledger rows with their parts,
  canonical hashes and written hashes. Ids are in it on purpose: the sequences start afresh in the test's container and
  hand out ids in the order the load asks for them, so a change to how rows are batched must leave every id where it
  was. Every command runs with one fixed test audit HMAC key, so the redacted values and the written hashes over them
  are the same from run to run. Only the times the load stamps and the ledger's mapping hash (the mapping file names the
  run's temporary folder) are left out. `-Dtrailkeep.perf.import.content-rows=<file>` writes the rows too, so two runs
  whose digests differ can be compared line by line.

`reconcile` then recomputes every written hash from Trailkeep and compares it with the ledger, so a run that reaches
`RECONCILED` has also proven that the ledger describes what was written.

### Hardware and software

One desktop machine: a 16-core AMD Ryzen 9 5900XT, 62 GB RAM, NVMe storage; PostgreSQL 16 (`postgres:16-alpine`, default
settings) and Oracle in Testcontainers, the application in the Failsafe JVM on Temurin 21 with `-Xmx1152m`. The machine
runs other services, so times move by some percent between runs. The timed runs below were made one after the other on
the same day. Ten minutes of a niced IT run overlapped (c)'s load, whose time may therefore be a little high (a second
run of (c), under heavier load, took 1 294 s).

## Results on PostgreSQL, the full corpus

Each row is the stack up to and including that PR (`main` is ac75e07). Peak heap is the highest heap sampled while the
command ran, "after GC" the highest heap left after a collection, which is what the command holds.

| Code | Dry run s | Dry-run statements | Dry-run transactions | Dry-run heap MiB (peak / after GC) | Load s | Load rows/s | Load statements | Load transactions | Load heap MiB (peak / after GC) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `main` | 426.7 | 757 038 | 756 303 | 467 / 296 | 1 826.3 | 357 | 4 871 236 | 800 787 | 467 / 288 |
| + (b) batched people | 13.0 | 10 059 | 9 328 | 468 / 296 | 1 441.2 | 452 | 4 124 257 | 53 812 | 525 / 319 |
| + (c) principals once, one page of people | 20.3 | 12 571 | 12 634 | 252 / 126 | 1 261.9 | 517 | 2 968 326 | 51 111 | 325 / 157 |
| + (d) a case's children together | 20.6 | 12 571 | 12 634 | 227 / 126 | 390.3 | 1 671 | 1 028 326 | 51 111 | 451 / 156 |

The other commands did not change: `validate-mapping` 0.5 s, `extract` 208 to 209 s (5 500 rows/s, 4 649
statements), `reconcile` 22 to 23 s (9 047 statements), `purge-staging` 0.8 to 0.9 s (13 statements). Every run gave
the same dry-run digest (`a986672e6dd74d3f`) and load digest (`b85f4f2d62f88af8`). The content digest, measured
with the stack up to (c) and with (d) in, is `a92fb3fe56fee37c` both times (the runs that measured it are not the
timed ones above; at a fiftieth of the corpus the dumped rows are byte-identical).

On `main` the dry run and the load are where the time is: 7 and 30 minutes of the 41 the whole import takes, each
about one transaction per row, for the people lookups (b) explains. The load's 4.9 million statements are, after those,
nearly all per child row, which (c) and (d) take in turn.

From `main` to the end of the stack: the dry run is **21 times faster** (427 s to 21 s) and holds **less than half the
heap** (296 to 126 MiB after GC); the load is **4.7 times faster** (1 826 s to 390 s, 357 to 1 671 rows/s) with **a
fifth of the statements** and **a sixteenth of the transactions**. The whole import of the release corpus, extract to
purge, went from 41 minutes to 11.

### What each fix did

**(b) Batched people (#173).** `main` asked the directory about each legacy person one query at a time: 499 000
email and 250 777 external-id lookups in the dry run, and 1 454 779 and 499 000 in the load. Each lookup outside a
transaction was a transaction of its own (Spring Data's repository methods are transactional), which is where the
"about one transaction per row" came from. `PeopleMatcher.prepare` now asks, for a page of people, one query per way
of matching and 500 values, in `people.match` order, so a way is asked only for the people an earlier way did not
match. The dry run fell from 427 s to 13 s, its transactions from 756 303 to 9 328, one per page; the load from
1 826 s to 1 441 s and from 800 787 transactions to 53 812, about one per case (§8.2).

**(c) The import principals once, and one page of people.** The load still ran 1 204 804 external-id queries: every
imported comment and case asked `ImportPeopleService.principals()` for the two import principals, which read them by
external id each time. They are read once now: V11 seeds them with fixed ids that no sequence issues. The dry run and
the load also stopped holding every legacy person they had matched: they hold one page, and a page of cases or children
resolves the people it names from staging first. That costs the dry run 7 s (it reads the people a page names again) and
more than halves what the dry run holds (296 to 126 MiB after GC) and what the load holds (319 to 157 MiB): what they
hold no longer grows with the number of legacy people, as the design's memory rule asks. The load's statements fell from
4.1 to 3.0 million and its time from 1 441 s to 1 262 s.

**(d) A case's comments and history together.** After (c), the load's 2 968 326 statements were nearly all per child
row: each comment read its case again, was inserted and flushed alone, appended to the search text, audited, read back
for its written hash and given its ledger row; each history row likewise. A flush per row means no JDBC batching, even
though `hibernate.jdbc.batch_size` is 50 and the entities' sequences are pooled. `CaseImportService.importComments`
now takes a case's comments at once: one read of the case, every comment and audit row persisted with the id its
pooled sequence gives, one flush, and one search append of every body in order (the text one append per comment would
leave). The load reads a case's comments back once and its history once, then saves the ledger rows, so the inserts go
in batches of 50. The load fell from 1 262 s to 390 s.

### Transactions: by design, not by accident

The dry run's and the load's "one transaction per row" on `main` was accidental: it was the people lookups, each in a
transaction of its own (b). What remains is by design. The dry run spends a few transactions on each page of 500 rows
(12 634 for 1 152 000 rows). The load writes **one case per transaction** (50 000 of its 51 111), as §8.2 requires: a
case is fully in or absent, a refused case becomes a `LOAD` issue while the load goes on, and a crashed load resumes at
the next case. Several cases per transaction with a savepoint each was considered and not built: rolling back to a
savepoint leaves Hibernate's persistence context holding the rolled-back case's entities; the gain is bounded by the
cost of a commit, one part of the 7.8 ms a case now takes with its twelve children on average; and it would weaken the
one rule the resume logic rests on.

## Results on Oracle

Trailkeep on Oracle Free 23, its redo resized to three groups of 512 MB as `deploy/README.md` asks, the source still
PostgreSQL, at **a tenth of the corpus** (5 000 cases, 50 000 notes, 50 000 people; 115 200 rows staged, 65 200
written). A tenth, because on `main` the Oracle load writes 108 rows/s, so the full corpus would take about 100 minutes
for the load alone; every cost compared here is per row, per page or per case, and the one that dominates on Oracle
(below) is per case, so the tenth measures the same things a tenth as long.

| Code | Dry run s | Dry-run statements | Dry-run transactions | Load s | Load rows/s | Load statements | Load transactions | Load heap after GC MiB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `main` | 27.9 | 76 626 | 75 084 | 602.3 | 108 | 488 702 | 79 527 | 194 |
| + (b) and (c) | 6.0 | 2 382 | 1 638 | 596.4 | 109 | 298 614 | 5 481 | 180 |
| + (d) | 6.1 | 2 382 | 1 638 | 435.8 | 150 | 104 614 | 5 481 | 162 |

On Oracle (b) and (c) were measured together. All three runs gave the same dry-run digest (`fb13a4467224464f`), load
digest (`c529ac6a628ba8e2`) and **content digest** (`bdb9a210f10d2701`): on Oracle the whole stack writes exactly what
`main` writes, id for id.

The statements and transactions fall as on PostgreSQL, but the load's time barely moved until (d), and even after (d) a
case costs 87 ms. **The Oracle Text index is that time.** V21's `IX_CASE_SEARCH_TEXT` (ADR-0017) is `SYNC (ON COMMIT)
TRANSACTIONAL`, so every case's commit synchronises the index, 50 000 times at the full corpus. Two runs of (d)'s code
with the index changed only for the load, by the schema owner:

| Index during the load | Load s | Load rows/s | Then |
|---|---:|---:|---|
| `SYNC (ON COMMIT)`, as V21 creates it | 435.8 | 150 | – |
| dropped (to measure, not a proposal) | 63.9 | 1 020 | – |
| `REPLACE METADATA SYNC (MANUAL)` | 67.3 | 969 | `CTX_DDL.SYNC_INDEX` once: 1.8 s, nothing pending, every case found by `CONTAINS`; then `REPLACE METADATA SYNC (ON COMMIT)` |

So on Oracle about 85 % of the load is the per-commit index sync, and one sync after the load costs 2 s instead of 6
minutes at a tenth of the corpus (an hour at the full corpus). The index stays `TRANSACTIONAL` throughout, so a search
during the load still finds unsynced rows. This is not built here: the import user has no DDL right on the index, and
granting one, or making it a step of the import runbook, is the owner's decision (see "What remains").

## Correctness

The run asserts the outcome at volume and reports the digests above; each fix compares them with the run before it.

- (b): the same dry-run and load digests as `main` at the full corpus; `PeopleMatcherBatchTest`, for every order of
  `people.match`, matches pages of people batched exactly as the one-by-one matcher does, and `AbstractImportPeopleIT`
  checks the batched directory queries on both vendors; the importer ITs.
- (c): the same digests again; `DefaultImportPeopleTest` (the principals are read once and kept, and a missing one
  fails every call until its row exists); `PeopleMatcherBatchTest` matches page by page as one by one, and asking about
  a person outside the current page fails rather than reading as unmatched; the importer ITs.
- (d): the same dry-run and load digests, and the same content digest as the stack before (d), so every row the load
  writes, its id and every written hash are unchanged; `CaseCommentImportIT` (a case's comments imported together are
  written as one by one: the same order, each audited, the same search text; one refused comment, or comments of two
  cases, write nothing); the importer ITs on both vendors; and on Oracle the same content digest as `main` itself.

## The nightly job

The importer is **not** added to the nightly `perf` job. That job fails on BUILD_PLAN's targets and the plan sets none
for an import, so a nightly run would add a table nothing gates on, at 11 minutes a night on this machine (the source is
seeded and extracted every time). The importer's behaviour is proven on every pull request by the importer ITs, and on
Oracle on `main`. The volume run is made by hand at release time and with any change to the dry run or the load, like
the Oracle 500k run and k6. If a nightly guard is wanted, the stable one is a ceiling on statements and transactions per
row at a tenth of the corpus (a few minutes, and blind to the runner's speed), not a time; it would be a new gate, so it
is the owner's call.

## What remains

- **On Oracle, the Oracle Text sync per case** (above): about 85 % of the load. The measured remedy is an operator step
  around the initial load, as the schema owner: `ALTER INDEX IX_CASE_SEARCH_TEXT PARAMETERS ('REPLACE METADATA SYNC
  (MANUAL)')` before it, then `CTX_DDL.SYNC_INDEX('IX_CASE_SEARCH_TEXT')` and `ALTER INDEX IX_CASE_SEARCH_TEXT
  PARAMETERS ('REPLACE METADATA SYNC (ON COMMIT)')` after it. Decided (2026-11-23): a runbook step for the schema
  owner, not a grant to the Job (`docs/RUNBOOK.md`, "Import go-live"). The restore drill refined it: the owner needs
  `EXECUTE ON CTXSYS.CTX_DDL` for the sync, granted by a DBA for the window, and the last statement must be
  `REPLACE METADATA SYNC (ON COMMIT) OPTIMIZE (AUTO_DAILY)`, because on Oracle 21 the sync clause alone drops the
  nightly optimisation from the index's metadata (`docs/ops/restore-drill.md`).
- **The extract** is now the longest command on PostgreSQL: 209 s, 5 500 rows/s, for 1 152 000 rows. It was not part
  of #173 and was not investigated here.
- **Per-case reads in `importCase`**: the case type, the team and the SLA policy are read for every case in its own
  transaction. Caching them for the load would save a few statements a case.
- **`reWriteBatchedInserts=true`** on the PostgreSQL JDBC URL would let the driver send each batch as one multi-row
  insert. It is a deployment setting, not code, and was not measured.
- The cases-list index recommendations of `cases-list-50k.md` are WP-30 part a's (`release-500k.md`), not this page's.
