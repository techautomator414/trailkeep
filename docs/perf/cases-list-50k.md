<!-- SPDX-License-Identifier: Apache-2.0 -->
# The cases list at 50 000 cases

WP-15's acceptance (IMPLEMENTATION_PLAN Part C) asks for a p95 under 500 ms for `GET /cases` at 50 000 cases on
PostgreSQL. This page records how that is measured, the numbers from the run that closed WP-15, and what they say
about indexes and caches. The 500 000-comment run, pool sizing and the release evidence belong to WP-30.

## The seed

`perf/seed-cases-postgresql.sql` creates the data. It is synthetic and deterministic: every value is a function of
the row number and every name is made up. It is never applied by Flyway and never packaged.

| What | How many | Shape |
|---|---:|---|
| People | 200 | `Perf User 001` … `200`, in 20 teams of 10: 9 Analysts and 1 Manager each |
| Teams | 20 | `Perf Team 01` … `20` |
| Case types | 5 | under one 30-day SLA policy |
| Cases | 50 000 | 2 500 per team; about 70 % open, 10 % pending closure, 15 % closed, 5 % archived; 10 % restricted; 1 in 7 high priority; identified over 300 days of 2026 |
| SLA rows | 50 000 | open cases on track, at risk or overdue; closed and archived ones met or breached |
| Search rows | 50 000 | lower-cased title and description, as the application writes them |

Ids start at 900000001 and case numbers use the prefix `PERF`, so the seed never collides with rows the application
creates. It loads in about 8 s.

## How it is measured

There are two runs. Both drive the real application, with security, the scoped queries and Thymeleaf.

**`PerfCasesListIT`** (in-process, every night). It applies the seed to its own PostgreSQL 16 container, then sends
each scenario 10 times to warm up and 60 times measured, through MockMvc. It writes p50, p95 and max to
`target/perf/cases-list-50k.md` and fails if `GET /cases` reaches a p95 of 500 ms or the search reaches 1 s. It is
tagged `perf`, so it never runs in the PR build. Run it by hand with:

    ./mvnw -Pperf-it test-compile failsafe:integration-test@perf-50k failsafe:verify@perf-50k

The nightly workflow's `perf` job runs the same command and uploads the table as the `perf-50k` artifact.

**k6 smoke** (`perf/k6/cases-smoke.js`, by hand). It runs over HTTP against a running application behind the
identity provider. Five virtual users each sign in once through the Keycloak login form, as a browser does, and then
loop over the list, the title order, a deep page and a search for one minute. One more user downloads the export
once. Thresholds: the list's p95 under 500 ms and the search's under 1 s.

This needs the identity provider with real redirects, which the CI runners do not provide. That is why it is not in
the nightly run (the in-process run covers the same scenarios). To run it on the local stack:

    docker compose up -d postgres keycloak clamav-tls-init
    ./mvnw spring-boot:run -Dspring-boot.run.profiles=local \
        -Dspring-boot.run.arguments=--app.attachments.scanning=external
    # once the application has migrated the schema:
    psql -h localhost -U trailkeep -d trailkeep -f perf/seed-cases-postgresql.sql
    k6 run -e BASE_URL=http://localhost:8080 -e USERNAME=compliance.admin -e PASSWORD=dev-password \
        perf/k6/cases-smoke.js

Running k6 from its container also works: `docker run --rm --network host -v "$PWD/perf/k6:/scripts:ro" grafana/k6
run /scripts/cases-smoke.js`.

The application's session cookie is `Secure`, and k6 never sends a `Secure` cookie over plain `http`. The script
therefore copies the application's cookies into its jar as non-secure cookies for `BASE_URL`. Behind TLS this changes
nothing.

## Results (2026-11-06)

Host: a 12-core desktop CPU and 62 GB RAM, everything in Docker on the same machine. That is the application's JVM in
the build image (Temurin 21) and PostgreSQL 16 (`postgres:16-alpine`) with default settings. CI runners are slower,
and the nightly table is the reference over time.

### `PerfCasesListIT`, 60 measured requests each (ms)

| Scenario | p50 | p95 | max | Target |
|---|---:|---:|---:|---|
| `GET /cases`, Compliance Admin (every team, 47 500 listed), newest first | 114.1 | **132.1** | 146.4 | p95 < 500 |
| `GET /cases?sort=title` (`LOWER(TITLE)` order) | 129.3 | **139.3** | 146.2 | p95 < 500 |
| `GET /cases?sort=due` (SLA due date, left join) | 89.7 | 102.0 | 107.5 | p95 < 500 |
| `GET /cases?page=1900` (last page, offset 47 475) | 128.8 | 137.0 | 140.7 | p95 < 500 |
| `GET /cases` with status, SLA state, priority and date range | 89.0 | 95.5 | 118.3 | p95 < 500 |
| `GET /cases`, an Analyst of one team (2 500 cases) | 38.0 | 45.8 | 59.4 | p95 < 500 |
| `GET /cases?q=plover audit` (two words, every team) | 98.5 | **109.3** | 118.5 | p95 < 1000 |
| `GET /cases?q=saltmarsh` (one word, a fifth of the cases) | 114.1 | 120.0 | 121.6 | p95 < 1000 |
| `GET /cases?q=PERF-2026-0499` (case-number match) | 101.1 | 106.2 | 109.7 | p95 < 1000 |
| `GET /cases?q=plover audit`, an Analyst of one team | 40.0 | 46.8 | 48.4 | p95 < 1000 |
| `GET /cases/export.csv?type=…` (9 645 rows with details, 5 runs) | 506 | – | 536 | none set |
| Saved-view navigation query (20 views) | 0.3 | 0.4 | 0.4 | none set |
| `DISTINCT TEAM_ID, OWNER_ID` over every case (the filters' choices) | 6.5 | 10.7 | 12.4 | none set |

The planner's own timings (`EXPLAIN (ANALYZE, BUFFERS)` of hand-written equivalents, Compliance Admin scope):

| Statement | Execution time |
|---|---:|
| Count of every listed case | 6.8 ms |
| First page in `LOWER(TITLE)` order (join case type, left join SLA) | 57.1 ms |
| `DISTINCT TEAM_ID, OWNER_ID` | 11.9 ms |
| Two-word search subquery (`LIKE` on `SEARCH_TEXT`) | 16.4 ms |

### k6 smoke, 5 virtual users for 60 s plus one export (ms)

| Request | p50 | p95 | max |
|---|---:|---:|---:|
| `GET /cases` | 98.5 | **119.2** | 135.5 |
| `GET /cases?sort=title&dir=asc` | 127.3 | 149.3 | 174.9 |
| `GET /cases?page=1900` | 136.9 | 160.6 | 188.0 |
| `GET /cases?q=plover+audit` | 111.6 | **130.4** | 152.1 |
| `GET /cases/export.csv?type=…` (9 645 rows) | 576 | – | 576 |

Every check passed, and no request failed.

## What the numbers say

Every target is met with a wide margin. The list's p95 is about a quarter of 500 ms, and the search's about a tenth
of 1 s. This part therefore adds no index, no cache and no migration. The recommendations below are for the 500k
measurement in WP-30, with the evidence to weigh them against.

### Recommendations (not applied)

1. **An index for the default order, `CASE_RECORD (OPENED_AT, ID)`.** "Newest first" sorts the whole listed set,
   because `OPENED_AT` has no index. Today that is part of the 114 ms p50 for 47 500 cases.
   - With the index, PostgreSQL can read the first 25 rows in order and stop. Oracle does the same with `FETCH FIRST`.
   - It is a plain index, so its migration would be portable as it stands.
   - Worth adding when one person reads hundreds of thousands of cases.
2. **An expression index on `LOWER(TITLE)`.** The title order is the most expensive single statement measured: 57.1 ms
   for the first page, a sort of all 47 500 listed rows.
   - Both vendors support expression indexes, `CREATE INDEX … ON CASE_RECORD (LOWER(TITLE), ID)`. `MigrationLintTest`
     may need a rule for expressions, so this should be its own PR.
   - Same trigger as the first: add it when the sort, not the rendering, dominates the page.
3. **No cache for the filters' `DISTINCT TEAM_ID, OWNER_ID`.** It costs 11 ms (p95 10.7 ms), and it runs on every htmx
   swap too.
   - A per-person cache would make a just-assigned owner appear late in the Owner filter.
   - The cheaper first step, when it is ever needed: skip the query on htmx swaps. The fragment does not render the
     choices; only the out-of-band filter controls need them.
4. **The saved-view navigation query needs nothing.** It is an index range read on
   `UK_SAVED_VIEW_USER_NAME (USER_ID, NAME)` at 0.3 ms p50 and 0.4 ms max, on every full page.

### Exports and database connections

An export holds one database connection for as long as it streams. It reads its pages inside one read-only
transaction while it writes the response. The measured hold for 9 645 rows with details is about **0.5 s**:
- 506 ms p50 and 536 ms max in-process, with the whole request inside the transaction;
- 576 ms over HTTP with k6.

That is 20 page statements of 500 rows, two name lookups per page, and the CSV formatting.

**Exports do not need their own semaphore.** A cap-sized export (10 000 rows) holds a connection for about half a
second. A Hikari pool of 10 serves ten exports at once without any interactive request waiting more than that.
Attachment downloads, by contrast, stream up to 25 MB and are gated for that reason
(`app.attachments.max-concurrent-downloads`).

The one way an export can hold its connection longer is a slow client. The rows are written to the response inside
the transaction, so a client that reads slowly stretches the hold. If WP-30's load test or production shows that, the
small fix is to share the download semaphore. The larger fix is to render to a temporary file before streaming. Both
are recorded here, not built.
