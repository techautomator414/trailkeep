<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0014: Import from SQL Server, Oracle, PostgreSQL or their exports through one canonical staging model

- **Status:** Proposed
- **Date:** 2026-11-05
- **Register rows:** IMPLEMENTATION_PLAN.md A.4 #34 (importer idempotency), WP-26 (generic importer)
- **Plan sections:** BUILD_PLAN.md §15 (importing records), §5.2 (`IMPORT_RUN`), §8 (portability, grants), §9
  (security), §16 (importer `Job`)
- **Design:** [docs/design/importer.md](../docs/design/importer.md)

## Context and problem statement

BUILD_PLAN §15 specifies a generic importer fed by **CSV extracts** and a private YAML mapping file. The owner has
asked for a robust and flexible import of compliance case history from other databases: directly from **Microsoft
SQL Server, Oracle or PostgreSQL**, or from **files exported** from them. Legacy trackers differ in schema, encoding,
time handling and how they store attachments, and the importer must map any of them onto Trailkeep's model without
code changes per organization, while keeping every Trailkeep invariant (CaseScope, SLA anchor, insert-only
comments, attachment intake, audit) and losing nothing silently.

## Decision drivers

- One mapping per organization that works the same from a live database or from its exports.
- Zero silent loss: every source row is loaded, skipped by an explicit rule, or reported.
- Idempotent, resumable and delta-capable for a cut-over window.
- No new attack surface: read-only source access, TLS, no query text built from data, untrusted content handled as
  in the UI.
- Organization-specific details stay outside the public repository.

## Considered options

1. **CSV only**, as BUILD_PLAN §15 says: organizations export everything to CSV first.
2. **Direct source-to-domain adapters** per vendor, each writing Trailkeep tables.
3. **Source adapters into a vendor-neutral canonical staging model**, then one validated, service-based load
   (JDBC for SQL Server, Oracle, PostgreSQL; files for CSV/TSV and JSON Lines).
4. **A general ETL tool** (external) producing Trailkeep inserts.

## Decision outcome

Option 3. The detail is in the design document; the binding rules are:

1. **Three stages separated by tables:** *extract* (source-specific, writes only canonical staging rows),
   *validate/dry-run* (writes only issues and a reconciliation preview), *load* (writes the domain through the domain
   modules' services, with `origin = IMPORT`). Only extract knows the source.
2. **Sources:** JDBC for SQL Server (`mssql-jdbc`, MIT, added to the importer runtime), Oracle and PostgreSQL, read in
   one consistent read-only snapshot with TLS and host verification; files as CSV/TSV and JSON Lines with a
   checksummed manifest. Vendor dump formats are not parsed; they are restored into a scratch database and read over
   JDBC.
3. **Mapping:** a private YAML file validated against a shipped JSON Schema, with operator-written single `SELECT`
   queries and a **closed set of pure transforms**. No scripting language; unknown keys and unmapped required fields
   fail validation. The same mapping serves database and file sources.
4. **Identity of imported data:** `LEGACY_REF = <source id>:<legacy key>`; `IMPORT_LEDGER (SOURCE_ID, ENTITY,
   LEGACY_KEY)` unique, written in the same transaction as the domain rows. Imported cases get **new Trailkeep case
   numbers**; the legacy number is shown and searchable.
5. **Trailkeep rules hold for imported data:** closed cases are `CLOSED` with `SLA_STATE = NOT_APPLICABLE`; open cases
   get their SLA from the original dates by the one anchor rule; comments keep original timestamps and author
   snapshots; attachments go through `UploadIntake` and the deployment's scanning mode; people are matched, never
   created.
6. **Audit records the load time**, not a back-dated legacy time; legacy times live on the imported rows.
7. **A run loads only after a clean dry run and a recorded sign-off**, and is reported as `RECONCILED` only when
   counts and checksums match end to end.

### Consequences

- **Good:** any of the three databases, or exports from them, can be migrated with one reviewed mapping; source
  differences are confined to adapters; every business rule and security control applies to imported data because
  the load uses the domain services; the reconciliation is provable.
- **Bad:** more code than CSV-only (WP-26 becomes about ten PRs, design §12); a SQL Server driver and container image
  enter the build (importer runtime and tests only); SQL Server snapshot reads need `ALLOW_SNAPSHOT_ISOLATION` on the
  source or a restored copy.
- **Neutral:** BUILD_PLAN §15's CSV path remains a subset of this design. The v1.1 "mapping DSL beyond fixed YAML"
  item stays deferred; the transform set is closed and can grow by reviewed PRs.

## Links

- BUILD_PLAN.md §15; IMPLEMENTATION_PLAN.md WP-26
- [docs/design/importer.md](../docs/design/importer.md)
