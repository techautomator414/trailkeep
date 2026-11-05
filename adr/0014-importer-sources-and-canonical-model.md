<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0014: Import from SQL Server, Oracle, PostgreSQL or their exports through one canonical staging model

- **Status:** Proposed
- **Date:** 2026-11-05 (revised 2026-11-05 after the independent review on #56)
- **Register rows:** IMPLEMENTATION_PLAN.md A.4 #34 (importer idempotency), A.4 #37 (migrations land with their
  entities), WP-26 (generic importer)
- **Plan sections:** BUILD_PLAN.md §15 (importing records), §5.1 (`IMPORT_RUN` among the supporting tables), §6 (SLA),
  §8 (portability, rule 7 grants), §9 (security), §16 (importer `Job`); IMPLEMENTATION_PLAN.md Part B
  ("Persistence", amended below)
- **Depends on:** ADR-0013 (scanning mode, `NOT_SCANNED`), in #57, which merges first
- **Design:** [docs/design/importer.md](../docs/design/importer.md)

## Context and problem statement

BUILD_PLAN §15 specifies a generic importer fed by **CSV extracts** and a private YAML mapping file. The owner has
asked for a robust and flexible import of compliance case history from other databases: directly from **Microsoft
SQL Server, Oracle or PostgreSQL**, or from **files exported** from them. Legacy trackers differ in schema, encoding,
time handling, keys and how they store attachments, and the importer must map any of them onto Trailkeep's model
without code changes per organization, keep Trailkeep's invariants (CaseScope, SLA anchor, insert-only comments,
attachment intake, audit) and lose nothing silently.

## Decision drivers

- One mapping per organization that works the same from a live database or from its exports.
- Zero silent loss: every source row is loaded, skipped by an explicit rule, or reported.
- Idempotent, restartable and delta-capable for a cut-over window.
- No new attack surface beyond what is named here: least-privilege source access, TLS, no query text built from
  data, untrusted content handled as in the UI, staged PHI unreadable by the web pods.
- Organization-specific details stay outside the public repository.

## Considered options

1. **CSV only**, as BUILD_PLAN §15 says: organizations export everything to CSV first.
   - Pro: smallest engine; no new driver; one input format to test.
   - Con: every organization must produce correct CSV from its database, which the review showed is hard (SQL Server
     `bcp` does not write RFC 4180, SQL\*Plus truncates CLOBs, per-command exports are not mutually consistent).
     Attachments and large text fare worst. Errors surface late, in someone else's tooling.
2. **Direct source-to-domain adapters** per vendor, each writing Trailkeep tables.
   - Pro: no staging tables; fewest moving parts per vendor.
   - Con: validation, dry run, idempotency and reconciliation are re-implemented per vendor; there is no point at
     which a dry run can be reviewed and signed off before the domain changes; a fourth source means a fourth copy.
3. **Source adapters into a vendor-neutral canonical staging model**, then one validated load through import-only
   domain APIs (JDBC for SQL Server, Oracle, PostgreSQL; files for CSV/TSV and JSON Lines).
   - Pro: source differences are confined to adapters; one validation, dry run, load and reconciliation; the dry run
     is reviewable before anything is written; file and database sources provably give the same rows.
   - Con: more code (WP-26 becomes about twenty-four PRs, design §12); staging holds PHI and needs its own credential,
     TTL and purge; a SQL Server driver and container image enter the build.
4. **A general ETL tool** (external) producing Trailkeep inserts.
   - Pro: mature connectors; no engine to maintain here.
   - Con: writes around the domain rules, audit and scanning; adds a licence and an operational dependency per
     organization; reconciliation and idempotency become the tool's, not Trailkeep's, and cannot be tested here.

Sub-decisions considered within option 3:

- **Case numbers.** (a) New Trailkeep numbers, legacy number kept in its own column: one numbering scheme, no
  collision with numbers issued after go-live; con: people learn a second number. (b) Keep legacy numbers as
  Trailkeep numbers: familiar; con: they do not fit `PREFIX-YEAR-SEQUENCE` and can collide with numbers issued later.
  **Chosen: (a).**
- **Scanning of imported attachments in `external` mode** (owner decision, 2026-11-05). (a) Follow the app's mode:
  consistent with uploads, no clamd needed for `external` deployments; con: the risk below. (b) Always scan imports
  with clamd: closes the gap; con: an `external` deployment must run clamd for the import only. (c) Refuse unless the
  mapping attests an external scan: explicit; con: the attestation is unverifiable text. **Chosen by the owner: (a).**
- **Import actor** (Proposed; owner to confirm). (a) A seeded non-login import principal: honest audit, no real
  person becomes owner or closure requester; con: a user that does not come from the identity provider, which needs
  its own ADR. (b) The authenticated approver's id: no new user; con: the approver becomes owner of unmatched closed
  cases, including restricted ones, and cannot also approve the imported closure. **Proposed: (a).**
- **Staging credential** (Proposed; owner to confirm). (a) A fourth user, `TRAILKEEP_IMPORT`: web pods cannot read
  staged PHI; con: one more credential and grants column. (b) `TRAILKEEP_APP`, as BUILD_PLAN §8 rule 7 assumes: no
  new user; con: every web pod can read every staged restricted description outside `CaseScope`. **Proposed: (a).**

## Decision outcome

Option 3. The detail is in the design document; the binding rules are:

1. **Three stages separated by tables:** *extract* (source-specific; writes only canonical staging rows and the
   run's content area), *validate/dry-run* (writes only issues and a reconciliation preview), *load* (writes the
   domain through import-only APIs of `casefile`, `document`, `closure` and `user`, with `origin = IMPORT`). **Only
   extract opens a source connection or reads source files.** Attachment bytes are read once at extract, inside the
   snapshot, and their size and SHA-256 recorded; bytes from a source query are copied into a run-scoped content area,
   and bytes from files are hashed again at load (`CONTENT_CHANGED` on a mismatch). The load needs no source
   credential or source egress.
2. **Sources:** JDBC for SQL Server, Oracle and PostgreSQL, read in one consistent snapshot with verified TLS; files
   as CSV/TSV and JSON Lines that are the per-entity query outputs, with a checksummed manifest recording the export's
   snapshot. Vendor dump formats and raw per-table exports are not parsed; they are restored or loaded into a scratch
   database and read over JDBC.
3. **Read-only access is the source account's grants** (`SELECT` on named objects only; no `EXECUTE`, database
   links or linked servers). The read-only transaction (which SQL Server does not have) and the query parser are
   defence in depth. The security tests prove the grants recipe on each vendor.
4. **Mapping:** a private YAML file validated against a shipped JSON Schema, with operator-written single `SELECT`
   queries (parsed, never wrapped or rewritten, values only as binds) and a **closed set of pure transforms**. No
   scripting language; unknown keys and unmapped required fields fail validation. Placeholders resolve only in
   `source.url`/`source.user` and only from `import.source.*`.
5. **Identity of imported data:** `LEGACY_REF = <source id>:<canonical key>` (source id at most 16 characters, keys
   canonicalised, composite keys supported); `IMPORT_LEDGER (SOURCE_ID, ENTITY, LEGACY_KEY)` unique, insert-only,
   written in the same transaction as the domain rows, with keyed canonical, written and mapping hashes. Imported
   cases get **new Trailkeep case numbers**; the legacy number is stored in `LEGACY_NUMBER`, shown and searchable.
6. **Trailkeep rules hold for imported data, with named exceptions.** Closed cases are `CLOSED` with
   `SLA_STATE = NOT_APPLICABLE` and an imported `APPROVED` closure request; open cases get their SLA from the original
   dates by the one anchor rule; comments keep original timestamps and author snapshots; attachments go through the
   intake and the deployment's scanning mode; people are matched, never created. The import-only APIs relax exactly:
   the open instant, creation as `CLOSED`, inactive case types and the import principal as owner for closed cases,
   and the importer's attachment cap. Nothing else.
7. **Audit records the load time**, not a back-dated legacy time; legacy times live on the imported rows.
8. **A run loads only after a clean dry run and an authenticated sign-off** by a Compliance Admin other than the
   requester, who acknowledges each WARNING class, and it is reported as `RECONCILED` only when counts and both
   checksum comparisons match end to end.
9. **Keyed hashes only.** Row hashes, report checksums and the `hash` transform are HMAC-SHA-256 with a server-side
   importer key; plain hashes of short text are dictionary-reversible (BUILD_PLAN §9).

### Owner-accepted risk: unscanned imported attachments in `external` mode (2026-11-05)

In `external` scanning mode, imported attachments are stored `NOT_SCANNED`, exactly like uploads, and are
downloadable. ADR-0013 names the risk this accepts:

> In `external` mode Trailkeep relies entirely on the organization's scanning. A file that reaches the application by
> a path the upstream scanner does not cover is stored unscanned and is downloadable.

The importer is such a path: legacy files do not pass the upstream scanner on their way in. The owner accepted this
on 2026-11-05 and chose not to add an always-scan rule for imports. Mitigation: the dry run reports the number and
total size of attachments that will be stored `NOT_SCANNED` as a WARNING, and the sign-off cannot be recorded until
that warning is acknowledged. Each imported attachment's audit entry records the scan mode in force. A deployment
that wants imports scanned runs the import in `clamd` mode.

### Amendment to IMPLEMENTATION_PLAN Part B, "Persistence"

Part B allows native SQL only in `*JdbcDao` classes in `document`, `notification` and `casefile`. From this ADR on it
also allows **one** class in `importer`, `SourceQueryJdbcDao`, which runs operator-written source queries:

- It accepts only a `ValidatedSourceQuery`, a type that only the query validator can construct, and runs it on the
  **source** connection, never on the Trailkeep `DataSource`.
- Its single execute method carries
  `@SuppressFBWarnings(value = {"SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING", "SQL_INJECTION_JDBC"},
  justification = "…")`, stating that the text is operator configuration validated by the parser, never built from
  data. No other suppression is added and no gate is changed.
- A new ArchUnit rule allows only the importer's extract classes to depend on it.
- Staging tables are accessed through JPA entities; staging deletes are constant JPQL.

The plan documents are not edited (CLAUDE.md rule 11); this amendment is listed under Plan deviations in #56.

### Amendment to BUILD_PLAN §8 rule 7 (Proposed; owner to confirm)

A fourth database user, `TRAILKEEP_IMPORT`, used only by the importer Job, owns the grants on staging tables and the
insert grants the load needs. `TRAILKEEP_APP` loses its implicit DELETE on "importer staging tables" and keeps only
`SELECT` on run, issue and ledger summaries and the sign-off columns. The insert-only rules for comments, chunks, audit
and the ledger apply to `TRAILKEEP_IMPORT` unchanged.

### Dependencies

Every artifact the design adds, with its SPDX licence. All runtime artifacts ship in the one image (BUILD_PLAN §16),
so they are on the web app's classpath and in its SBOM and are scanned there, although only the importer profile
calls them.

| Artifact | SPDX licence | Scope | Use |
|---|---|---|---|
| `com.microsoft.sqlserver:mssql-jdbc` | MIT | runtime | SQL Server source |
| `com.github.jsqlparser:jsqlparser` | `LGPL-2.1 OR Apache-2.0`; **Apache-2.0 elected** | runtime | Query validation |
| `com.google.re2j:re2j` | BSD-3-Clause | runtime | `regexExtract`, linear-time matching |
| `com.networknt:json-schema-validator` (candidate) | Apache-2.0 | runtime | Mapping schema validation |
| Jackson YAML data format (Boot-managed Jackson line) and its YAML parser | Apache-2.0 | runtime | Mapping parsing |
| `org.apache.commons:commons-csv` | Apache-2.0 | runtime | CSV/TSV source (not hand-rolled) |
| `org.testcontainers:testcontainers-mssqlserver` | MIT | test | SQL Server ITs |
| `mcr.microsoft.com/mssql/server` image | Microsoft EULA (Developer edition) | test only, not a Maven artifact, not redistributed | SQL Server ITs |

Already present and reused: `ojdbc17` (Oracle-FUTC), `postgresql` (BSD-2-Clause), `tika-core`, the OWASP HTML
sanitizer and `commonmark`. Every runtime licence above is on `security/licenses-allowed.txt`. The PR that adds
JSqlParser must show the licence gate accepting it on the Apache-2.0 alternative with the allow-list and merges
unchanged; if it does not, a different parser is chosen rather than the gate changed. The JSON Schema validator must
build on the Jackson line Boot 4 manages; if the candidate does not, a substitute with an allow-listed licence is
recorded by amending this table.

### Consequences

- **Good:** any of the three databases, or exports from them, can be migrated with one reviewed mapping; source
  differences are confined to adapters; the domain rules apply to imported data through import-only APIs whose
  relaxations are listed and tested; the reconciliation is provable; the load cannot touch the source.
- **Bad:** more code than CSV-only (design §12); new dependencies in the image (above); a fourth database credential
  (proposed); staging and the content area hold PHI until purged or expired; SQL Server snapshot reads need
  `ALLOW_SNAPSHOT_ISOLATION` on the source, a readable secondary, a database snapshot or a restored copy, and long
  snapshots cost tempdb, undo or vacuum on the source. The owner-accepted risk above.
- **Neutral:** BUILD_PLAN §15's CSV path remains a subset of this design. The v1.1 "mapping DSL beyond fixed YAML"
  item stays deferred; the transform set is closed and can grow by reviewed PRs.

## Confirmation

- Query-validator unit tests and the grants recipe ITs on SQL Server, Oracle and PostgreSQL (design §11).
- An ArchUnit rule: only importer extract classes depend on `SourceQueryJdbcDao`, and only `importer` calls the
  import-only APIs.
- `DatabaseGrantsIT` with the `TRAILKEEP_IMPORT` column; a negative IT that the web credential cannot read staging.
- Source-equivalence ITs (JDBC vs files per vendor), recipe tests, and the end-to-end `RECONCILED` and re-run tests.
- A dry-run test in `external` mode asserting the `NOT_SCANNED` WARNING and that sign-off is refused until it is
  acknowledged.

## More information

- Merge order: #57 (ADR-0013) before #56 (this ADR). When #57 merges, #56 takes `main` and the ADR index carries both
  rows.
- BUILD_PLAN.md §15; IMPLEMENTATION_PLAN.md WP-19, WP-21, WP-24, WP-25, WP-26
- ADR-0004, ADR-0005, ADR-0007, ADR-0012, ADR-0013
- [docs/design/importer.md](../docs/design/importer.md)
