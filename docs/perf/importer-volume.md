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

One desktop machine: a 16-core AMD Ryzen 9 5900XT, 62 GB RAM, NVMe storage; PostgreSQL 16 (`postgres:16-alpine`,
default settings) and Oracle in Testcontainers, the application in the Failsafe JVM on Temurin 21 with `-Xmx1152m`. The
machine runs other services, so times move by some percent between runs; the timed runs were made one after the other
on the same day.

## Results on PostgreSQL, the full corpus

Each row is the stack up to and including that PR (`main` is ac75e07); (b) to (d) add their rows. Peak heap is the
highest heap sampled while the command ran, "after GC" the highest heap left after a collection, which is what the
command holds.

| Code | Dry run s | Dry-run statements | Dry-run transactions | Dry-run heap MiB (peak / after GC) | Load s | Load rows/s | Load statements | Load transactions | Load heap MiB (peak / after GC) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `main` | 426.7 | 757 038 | 756 303 | 467 / 296 | 1 826.3 | 357 | 4 871 236 | 800 787 | 467 / 288 |

The other commands: `validate-mapping` 0.5 s, `extract` 209 s (5 500 rows/s, 4 649 statements), `reconcile` 22 s
(9 047 statements), `purge-staging` 0.8 s (13 statements). Dry-run digest `a986672e6dd74d3f`, load digest
`b85f4f2d62f88af8`.

The dry run and the load are where the time is: 7 and 30 minutes of the 41 the whole import takes. Both run about one
transaction per row. The most frequent queries say why: the dry run asked the directory about each legacy person one
query at a time (499 000 email and 250 777 external-id lookups), and the load did the same (1 454 779 and 499 000),
each lookup outside a transaction being a transaction of its own (Spring Data's repository methods are transactional).
The load's 4.9 million statements are, after those, nearly all per child row. (b) to (d) take these in turn.

## Correctness

The run asserts the outcome at volume and reports the digests above; each fix compares them with the run before it.

## The nightly job

The importer is **not** added to the nightly `perf` job. That job fails on BUILD_PLAN's targets and the plan sets none
for an import, so a nightly run would add a table nothing gates on, at 41 minutes a night on this machine on `main`
(the source is seeded and extracted every time). The importer's behaviour is proven on every pull request by the
importer ITs, and on Oracle on `main`. The volume run is made by hand at release time and with any change to the dry
run or the load, like the Oracle 500k run and k6. If a nightly guard is wanted, the stable one is a ceiling on
statements and transactions per row at a tenth of the corpus (a few minutes, and blind to the runner's speed), not a
time; it would be a new gate, so it is the owner's call.

## What remains

(b), (c) and (d) take the people lookups, the memory and the load's statements in turn, each with its row above.
