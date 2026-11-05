<!--
  SPDX-License-Identifier: Apache-2.0
  Copyright 2026 Charlie Genova and contributors
-->
# Importer design: legacy compliance case history into Trailkeep

- **Status:** Proposed design for WP-26 (decision recorded in [ADR-0014](../../adr/0014-importer-sources-and-canonical-model.md))
- **Builds on:** BUILD_PLAN.md §15, §5, §6, §8, §9; IMPLEMENTATION_PLAN.md A.4 #34, WP-26
- **Scope:** one-time migrations and repeatable delta loads from a legacy tracker held in **Microsoft SQL Server,
  Oracle or PostgreSQL**, or from **files exported** from any of them

The organization-specific parts stay outside this repository (BUILD_PLAN §15.4): the mapping file, source
connection details, extracts and reconciliation outputs live in the deploying organization's private configuration
repository. This repository ships the engine, the canonical model, the schemas, the export recipes and a fully
synthetic worked example.

## 1. Goals and non-goals

**Goals**

1. Import cases, comments, attachments, closure outcomes and (optionally) legacy change history with **zero silent
   loss**: every source row is loaded, deliberately skipped by a mapping rule, or reported as an issue.
2. Work from a live database (read-only) **or** from exported files, with the same mapping and the same result.
3. **Idempotent and resumable**: a re-run is a no-op; a crashed run resumes; a later delta run loads only what is new.
4. Map an arbitrary legacy data model onto Trailkeep's model **by configuration**, without code changes per
   organization.
5. Keep every Trailkeep invariant: CaseScope, the SLA anchor rule, insert-only comments, the attachment intake (type
   checks and scanning), audit with `origin=IMPORT`, both database vendors.

**Non-goals (v1)**

- Parsing vendor backup or dump formats (`.bak`, Data Pump `.dmp`, `pg_dump` custom format). Restore the backup into a
  scratch database of the same vendor and use the JDBC source, or export to files (§4.2).
- Two-way sync or continuous replication. Delta loads support a cut-over window, not coexistence.
- A general scripting language in the mapping (no SpEL, JavaScript or Groovy). The transform set is closed and
  reviewed (§6.3); this keeps the "importer mapping DSL beyond fixed YAML" item deferred to v1.1 as the plan says.
- Importing users' identities. People are matched to existing Trailkeep users (who arrive through the identity
  provider); unmatched people become name snapshots (§7.3).

## 2. Architecture

```
 legacy source                 importer (Job, profile "importer")                       Trailkeep database
 ─────────────                 ──────────────────────────────────────────                ──────────────────
 SQL Server ─┐                 ┌──────────┐   ┌──────────────┐   ┌──────────┐
 Oracle     ─┼─ JDBC, RO tx ──►│ Extract  │──►│  Canonical   │──►│ Validate │──► IMPORT_ISSUE
 PostgreSQL ─┘                 │ (source  │   │  staging     │   │ + dry run│
                               │ adapter) │   │  IMPORT_STG_*│   └────┬─────┘
 CSV / TSV  ─┐                 │          │   │ (vendor-     │        │ sign-off
 JSON Lines ─┼─ files, volume ►│          │   │  neutral)    │   ┌────▼─────┐   CASE_RECORD, CASE_SLA,
 attachments ┘                 └──────────┘   └──────────────┘   │   Load   │──► CASE_COMMENT, CASE_ATTACHMENT,
                                    ▲                            │ (domain  │    CASE_SEARCH, AUDIT_EVENT
                         mapping file (YAML, private)            │ services)│──► IMPORT_LEDGER
                                                                 └────┬─────┘
                                                                      ▼
                                                              Reconciliation report
```

Three stages, each independently re-runnable, separated by tables rather than by memory:

1. **Extract**: a *source adapter* reads the legacy data, applies the mapping file's field transforms, and writes
   **canonical staging rows**. This is the only stage that knows about the source. It never writes domain tables.
2. **Validate (dry run)**: checks every staged row against Trailkeep's business rules and the referential rules
   between staged rows, writes `IMPORT_ISSUE` rows and a reconciliation preview, and changes nothing in the domain.
3. **Load**: turns valid staged rows into domain rows **through the domain modules' services** (not raw inserts), in
   small transactions, recording each target id in `IMPORT_LEDGER`. Then it writes the reconciliation report.

The **canonical staging model** (§5) is the contract between the many possible legacy shapes and the one Trailkeep
model. Adding a new source kind means writing an adapter that fills staging; the validation, load and reconciliation
code does not change.

### 2.1 Modules and runtime

- Code lives in the `importer` module (already in the module list, IMPLEMENTATION_PLAN Part B). It depends on the
  public `*Service` interfaces of `casefile`, `document`, `user` and `audit`. Spring Modulith verifies that it reaches
  nothing else.
- It runs as a **Kubernetes Job** with the `importer` profile (`web-application-type=none`, no OIDC, no web
  schedulers), the same image as the web app (BUILD_PLAN §16). The mapping file and any extract files arrive on a
  volume; the source database credentials arrive as a Secret file (Spring `configtree`), never as environment text.
- Commands: `validate-mapping`, `extract`, `dry-run`, `load`, `reconcile`, `status`, `purge-staging`. Each is one Job
  run with `--import.command=…` and `--import.run=<id>` where it continues an existing run.

## 3. Lifecycle of an import

| Step | Command | What happens | Writes domain? |
|---|---|---|---|
| 1 | `validate-mapping` | Mapping file parsed against its JSON Schema; lookups resolved; connection or files checked; each source query `EXPLAIN`ed or test-fetched (1 row) | No |
| 2 | `extract` | New `IMPORT_RUN`; source read in one consistent snapshot (§4.1); transforms applied; staging filled with a row hash per row | No |
| 3 | `dry-run` | All rules checked; `IMPORT_ISSUE` filled; reconciliation preview produced | No |
| 4 | sign-off | A person reviews the preview and the issues and records approval (`IMPORT_RUN.APPROVED_BY/AT`) | No |
| 5 | `load` | Loads only rows not already in `IMPORT_LEDGER`, in batches; resumable | Yes |
| 6 | `reconcile` | Final report comparing source, staging and Trailkeep (§9) | No |
| 7 | `purge-staging` | Deletes the run's staging rows once the report is signed off (staging holds PHI) | No |

`load` refuses to start unless the run passed `dry-run` with zero fatal issues and has a sign-off. Error-severity
issues are allowed only below the mapping's `limits.maxErrorRate` and are always listed in the report.

A **delta run** (cut-over freeze) is a new `extract` against the same source with the same mapping. Rows whose
legacy key is already in `IMPORT_LEDGER` with the **same row hash** are skipped; rows with the same key but a
**different hash** are reported as `CHANGED_AFTER_IMPORT` and not overwritten (Trailkeep may already hold edits to
them; the person running the cut-over decides). New keys load normally.

## 4. Sources

### 4.1 Live database (JDBC)

| | SQL Server | Oracle | PostgreSQL |
|---|---|---|---|
| Driver | `com.microsoft.sqlserver:mssql-jdbc` (MIT), importer profile only | `ojdbc17` (Oracle-FUTC, already a dependency) | `org.postgresql:postgresql` (BSD-2, already) |
| TLS | `encrypt=strict` (or `true`), `trustServerCertificate=false`, `hostNameInCertificate` | TCPS with wallet or truststore; `oracle.net.ssl_server_dn_match=true` | `sslmode=verify-full` |
| Consistent snapshot | `SET TRANSACTION ISOLATION LEVEL SNAPSHOT` (requires `ALLOW_SNAPSHOT_ISOLATION ON` on the source; else documented fallback: read a restored copy) | `SET TRANSACTION READ ONLY` (statement-consistent across the transaction) or flashback `AS OF SCN` per query | `BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY` |
| Streaming | `selectMethod=cursor` / `responseBuffering=adaptive`, fetch size | fetch size | autocommit off + fetch size |
| Binary columns | `VARBINARY(MAX)`, `IMAGE` | `BLOB` | `bytea`, large objects |
| Text columns | `NVARCHAR(MAX)`, `NTEXT` | `CLOB`, `NCLOB` | `text` |

Rules for every JDBC source:

- **Read-only by construction.** The account the deploying organization creates for the import has `SELECT` on the
  needed objects only (recipes per vendor in `docs/importer/source-accounts.md`). The adapter also opens the
  connection read-only, runs every query inside a read-only transaction, sets a statement timeout, and rejects
  any mapping query that is not a single `SELECT` / `WITH … SELECT` (parsed, not pattern-matched, with no
  statement separators).
- **Queries are configuration, written by the operator.** Each canonical entity is filled by one query in the mapping
  file, so joins, filters and legacy oddities are expressed in the source's own SQL. No query text is ever built from
  data. Parameters (for example a delta watermark) are bind variables.
- **Ordered by legacy key**, so extraction can checkpoint and resume (`IMPORT_RUN_CHECKPOINT` per entity: last
  key extracted).
- **Time zones.** The mapping declares `source.timezone` (required). `DATETIME`/`TIMESTAMP` values without a zone are
  interpreted in it; `DATETIMEOFFSET` / `TIMESTAMP WITH TIME ZONE` keep their offset. DST gaps and overlaps follow a
  declared rule (`earlier` | `later` | `reject`), defaulting to `reject` so ambiguity becomes an issue, not a guess.

### 4.2 Exported files

Supported: **CSV/TSV** (RFC 4180 plus declared delimiter, quote, escape, header, null token, encoding) and **JSON
Lines**. Encodings: UTF-8 (with or without BOM), UTF-16LE/BE with BOM (SQL Server tools), Windows-1252 and
ISO-8859-1 when declared. A file manifest (`manifest.yaml`) lists each file, its entity, row count and SHA-256, so
the extract can prove the files are complete and unchanged.

Export recipes for each vendor (`docs/importer/export-recipes.md`):

- **SQL Server:** `bcp "<query>" queryout cases.csv -c -C 65001 -t, -r 0x0A` (or `-w` for UTF-16) and `sqlcmd` for
  JSON with `FOR JSON PATH`; attachments with `bcp ... -n` per file or the binary-export query in the recipe.
- **Oracle:** SQLcl `SET SQLFORMAT csv` / `json`, or SQL*Plus `SET MARKUP CSV ON QUOTE ON`; CLOBs over 4000 bytes
  need SQLcl or the JSON format; BLOBs through `DBMS_LOB` to files or the JDBC source.
- **PostgreSQL:** `psql -c "\copy (<query>) TO 'cases.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')"`; `bytea` as
  files through `lo_export` or the JDBC source.

File sources go through the **same mapping file**: only the `source` block changes (`kind: files` instead of
`kind: jdbc`). So an organization can trial with exports and run the real cut-over against the live database, or the
other way round, and get identical canonical rows (the test suite checks this, §11).

### 4.3 Attachments

An attachment row in the mapping points at its bytes in one of three ways: a **binary column** in the source query
(streamed through JDBC), a **file path** relative to a mounted folder (`attachments.root`; paths are normalized and
must stay inside the root, with no `..`, no absolute paths and no symlinks out), or a **content id** resolved through a
manifest. Every attachment then goes through `document`'s `UploadIntake`, exactly like a browser upload: size cap,
detected type against the allow-list, extension cross-check, image header cap, SHA-256, and the deployment's scanning
mode (ADR-0012, ADR-0013). A file that fails the type checks is not loaded and becomes an issue. An infected file is
stored `QUARANTINED` and reported (BUILD_PLAN §15.2). In `external` scanning mode it is stored `NOT_SCANNED`.

## 5. Canonical staging model

Vendor-neutral tables in the Trailkeep database, one per canonical entity, all keyed by `(RUN_ID, LEGACY_KEY)`:

| Table | Holds | Notes |
|---|---|---|
| `IMPORT_RUN` | One row per run: command history, mapping hash, source fingerprint, counts, status, sign-off | Already in BUILD_PLAN §5.2 |
| `IMPORT_STG_PERSON` | Legacy people: key, display name, email, external id hint | Only to resolve owners and authors |
| `IMPORT_STG_CASE` | Title, description, subject, case type key, team key, owner key, priority, sensitivity, status, identified on, opened at, closed at, closure outcome, legacy number | One row per legacy case |
| `IMPORT_STG_COMMENT` | Case key, author key or name, written at, body (text or Markdown flag) | Insert-only target |
| `IMPORT_STG_ATTACHMENT` | Case key, file name, declared type, size, source pointer (column, path or content id), uploaded at, uploader key | Bytes are not staged; they are streamed at load |
| `IMPORT_STG_EVENT` | Optional legacy history: case key, at, who, kind, old value, new value | §7.5 |
| `IMPORT_ISSUE` | Run, stage, entity, legacy key, severity (`FATAL`/`ERROR`/`WARNING`), code, message (no PHI) | Drives the report |
| `IMPORT_LEDGER` | `(SOURCE_ID, ENTITY, LEGACY_KEY)` unique → target id, row hash, run id, loaded at | Idempotency across runs |
| `IMPORT_RUN_CHECKPOINT` | Run, stage, entity, last legacy key done | Resume |

Each staging row stores a **row hash** (SHA-256 of its canonical field values in a fixed order), used for delta
detection and the reconciliation checksum. CLOB columns follow BUILD_PLAN §8 (`Length.LONG32`, the Oracle CLOB bind
learned in #52).

Grants: the runtime user has `SELECT, INSERT, DELETE` on staging tables (BUILD_PLAN §8 rule 7 already allows DELETE on
"importer staging tables"); `IMPORT_LEDGER` and `IMPORT_RUN` are insert-only plus status-column updates, like the
attachment release columns.

`LEGACY_REF` on `CASE_RECORD` (unique, BUILD_PLAN Appendix A) is set to `<source id>:<legacy key>`, so two legacy
systems imported into one Trailkeep cannot collide. `source.id` is a short stable name in the mapping file.

## 6. The mapping file

A private YAML file, validated against a JSON Schema shipped in the repository
(`src/main/resources/importer/mapping-v1.schema.json`). Unknown keys fail validation. A synthetic, complete example
lives in `src/test/resources/importer/example-mapping.yaml` against a synthetic legacy schema (§11).

### 6.1 Shape

```yaml
version: 1
source:
  id: legacy1                      # becomes the LEGACY_REF prefix; never change it between runs
  kind: jdbc                       # or: files
  vendor: sqlserver                # sqlserver | oracle | postgresql (jdbc only)
  url: ${IMPORT_SOURCE_URL}        # from the Job's environment; credentials from the Secret file
  timezone: America/Chicago        # how zone-less timestamps are read
  dstAmbiguity: reject
limits:
  maxErrorRate: 0.0                # fraction of rows per entity allowed to fail validation
  batchSize: 500
lookups:                           # value tables, inline or from a private CSV
  status:   { "Open": OPEN, "In Review": OPEN, "Closed": CLOSED, "Void": CLOSED }
  priority: { "1": HIGH, "2": NORMAL, "3": NORMAL }
  team:     { file: lookups/teams.csv, key: legacy_unit, value: trailkeep_team }
entities:
  case:
    query: |
      SELECT c.case_id, c.case_no, c.subject_line, c.details, c.unit, c.assignee, c.state,
             c.opened, c.closed, c.found_on, c.confidential
      FROM dbo.cases c
      ORDER BY c.case_id
    key: case_id
    fields:
      legacyNumber: case_no
      title:        { trim: subject_line, maxLength: 200, onOverflow: truncateWithIssue }
      description:  { coalesce: [details, "(no description in the legacy record)"] }
      teamKey:      { lookup: team, from: unit, onMissing: fail }
      ownerKey:     assignee
      status:       { lookup: status, from: state, onMissing: fail }
      priority:     { const: NORMAL }
      sensitivity:  { when: { column: confidential, equals: "Y" }, then: RESTRICTED, else: STANDARD }
      openedAt:     { timestamp: opened }
      closedAt:     { timestamp: closed }
      identifiedOn: { date: { coalesce: [found_on, opened] } }
      caseTypeKey:  { const: general-review }
  comment:
    query: SELECT note_id, case_id, author, created, body FROM dbo.notes ORDER BY note_id
    key: note_id
    parent: { entity: case, column: case_id }
    fields:
      authorKey: author
      writtenAt: { timestamp: created }
      body:      { text: body, format: plain }   # plain is escaped; markdown goes through the sanitizer
  attachment:
    query: SELECT file_id, case_id, file_name, uploaded, uploader, content FROM dbo.files ORDER BY file_id
    key: file_id
    parent: { entity: case, column: case_id }
    fields:
      fileName:   file_name
      uploadedAt: { timestamp: uploaded }
      uploaderKey: uploader
      content:    { column: content }          # or { path: rel_path } under attachments.root
people:
  query: SELECT user_id, display_name, email FROM dbo.users ORDER BY user_id
  key: user_id
  match: [ email, externalId ]                 # how legacy people find Trailkeep users, in order
  unmatched: snapshot                          # authors become name snapshots; owners use defaults.owner
defaults:
  owner: { team: auto, rule: teamManager }     # owner for open cases whose legacy owner has no Trailkeep account
```

### 6.2 Field rules

Every target field of the canonical entity must be either mapped or covered by a declared default. A missing mapping
fails `validate-mapping`, so "forgot a field" cannot become "silently null". Required Trailkeep fields
(`title`, `description`, `teamKey`, `caseTypeKey`, `status`, `openedAt`, `identifiedOn`) have no implicit defaults.

### 6.3 Transforms (closed set)

`column` · `const` · `trim` · `upper`/`lower` · `coalesce` · `concat` (with separator) · `lookup` (with `onMissing:
fail | default | issue`) · `when/equals/in/matches → then/else` · `regexExtract` (RE2-style, linear time; the
pattern is validated at load) · `timestamp` / `date` (with `format` for text sources, in the declared zone) ·
`number` · `maxLength` (with `onOverflow: fail | truncateWithIssue`) · `text` (with `format: plain | markdown | html`;
HTML is reduced to text, never stored as markup) · `hash` (for pseudonymous keys).

Transforms are pure functions over one source row plus lookups, so they are unit-tested exhaustively and the same
mapping produces the same canonical row from a database or from its exported file.

## 7. Mapping onto the Trailkeep model

### 7.1 Cases

- **Numbering.** Imported cases get **new Trailkeep numbers** from `SEQ_CASE_NUMBER` (one numbering scheme, no gaps
  in meaning). The legacy number is kept in `LEGACY_REF` and shown on the case page as "Legacy reference", and it is
  searchable. (Keeping legacy numbers as Trailkeep numbers was considered and rejected in ADR-0014: it collides with
  the `PREFIX-YEAR-SEQUENCE` format and with numbers issued after go-live.)
- **Status.** `OPEN` and `CLOSED` only. A legacy "pending approval" maps to `OPEN`; the closure flow restarts in
  Trailkeep. `ARCHIVED` is never imported; the retention job archives closed cases by their real `CLOSED_AT`.
- **Closed cases** import as `CLOSED` with `CASE_SLA.SLA_STATE = NOT_APPLICABLE` and null due dates (BUILD_PLAN §6).
  The closure outcome maps through a lookup to the case type's outcomes, or a declared default outcome.
- **Open cases** get their SLA from the case type's policy computed from the **original** dates with the one anchor
  rule (ADR-0007), so the first aging report is honest. The dry-run report forecasts how many will be At Risk or
  Overdue on day one, so management can be briefed (BUILD_PLAN §15.2).
- **Sensitivity and team.** Mapped explicitly; an unmapped team is a fatal issue unless `onMissing: default` names a
  team. Restricted cases stay restricted; the importer never widens access.
- **Search.** `CASE_SEARCH` is built once per case from title, description and all imported comments, through the
  same writer the app uses.

### 7.2 Comments

Imported through `casefile`'s comment service with their **original timestamp** and **author snapshot**
(`AUTHOR_ID` null when the author has no Trailkeep account). Plain-text bodies are escaped; Markdown bodies render
through the existing sanitizer (`SafeHtml`). The 20 000-character cap applies: longer bodies are split into numbered
parts with an issue, or fail, as the mapping declares.

### 7.3 People

People are **never created** by the importer. `people.match` resolves a legacy person to a Trailkeep user by email
or external id. An unmatched *author* becomes a name snapshot. An unmatched *owner* of an open case is replaced by
`defaults.owner` (for example the team manager) and reported, because an open case must have an owner who can act on
it. Closed cases keep the original owner's name in a snapshot field shown on the case page.

### 7.4 Audit

Every created case, comment and attachment writes one `AUDIT_EVENT` with `ORIGIN = IMPORT`, the run id in the
details, the system actor, and the **load time** as its timestamp. The original legacy timestamps live on the
imported rows themselves (`OPENED_AT`, comment `CREATED_AT`). The audit trail therefore stays true: it records when
Trailkeep learned each fact, not a back-dated claim.

### 7.5 Legacy history (optional)

If the legacy system kept a change history, the mapping can fill `IMPORT_STG_EVENT`. In v1 these load as a single
read-only **"Imported history"** comment per case (a compact, dated list), so nothing is lost and the History tab
stays a record of Trailkeep's own audit. A structured imported-history table is a v1.1 candidate if users need to
filter it.

## 8. Load: correctness under failure

- **Small transactions.** Each case is loaded with its SLA row, search row, comments, attachments and audit events in
  **one transaction**, so a case is either fully in or absent; a crash between cases loses nothing, and the next
  `load` resumes at the first case not in `IMPORT_LEDGER`.
- **Idempotency.** `IMPORT_LEDGER (SOURCE_ID, ENTITY, LEGACY_KEY)` is unique and written in the same transaction as
  the domain rows, so a key is loaded at most once even if two loads run by mistake (the second fails on the unique
  key and that case is skipped; a ShedLock-style run lock also refuses a second concurrent `load` for the same
  source).
- **Attachments outside the transaction.** Bytes are streamed and scanned into a temp file before the case's
  transaction opens (scanning can take seconds); the transaction then writes the chunks. A scanner outage stops the
  load cleanly with the run resumable, exactly as uploads fail closed.
- **Through services, with a system actor.** The load calls the same domain services as the UI, with an
  `ImportActor` (origin `IMPORT`, no permission bypass beyond what `CaseScope.system()` already allows for
  non-request work). Every business rule therefore holds for imported data.
- **Memory.** Rows are streamed; no stage holds more than one batch. Attachments use the 1 MiB chunk DAO.

## 9. Reconciliation report

Produced by `dry-run` (preview) and `reconcile` (final), as CSV and an accessible HTML page on the Job's output
volume, holding counts and hashes only, never field values:

- **Per entity:** rows in source (from the query count or the file manifest) · staged · loaded · skipped by rule ·
  failed (by issue code) · already loaded in an earlier run.
- **Per case status and team:** source counts vs Trailkeep counts.
- **Dates:** min and max `openedAt` / `closedAt` / comment `writtenAt`, source vs Trailkeep.
- **Checksums:** SHA-256 over the ordered canonical row hashes per entity, computed from staging and recomputed from
  Trailkeep after load; the title checksum from BUILD_PLAN §15.1.
- **Attachments:** count and total bytes per scan status; SHA-256 of each file compared with the source when the
  source supplies one.
- **SLA forecast:** open cases by expected SLA state on go-live day.
- **Verdict:** `RECONCILED` only when every source row is loaded, skipped by an explicit rule or listed as an issue
  and the checksums match. WP-26's acceptance ("synthetic extract reconciles 100 %; re-run is a no-op") is this
  verdict on the synthetic dataset, plus a second `load` reporting zero rows loaded.

## 10. Security and privacy

- **Least privilege at the source:** a read-only account; recipes grant `SELECT` on named objects only, never
  `db_datareader` on everything or `SELECT ANY TABLE`.
- **Encrypted in transit:** TLS with certificate and host verification for every JDBC source (§4.1); no
  `trustServerCertificate=true`, no `sslmode=require` without verification.
- **No SQL injection surface:** queries come only from the reviewed mapping file, are restricted to one
  `SELECT`, and take values only as bind parameters; identifiers are never built from data.
- **Untrusted content stays untrusted:** comment and description text is escaped or sanitized exactly as in the UI;
  attachments go through `UploadIntake`; file paths cannot leave `attachments.root`.
- **PHI minimization:** staging holds PHI only until sign-off (`purge-staging`); issues and reports carry keys, codes
  and counts, never field values; logs carry ids only; `IMPORT_RUN` records hashes, not content.
- **Separation of duties:** the person who signs off a run is recorded; the Job's service account can reach the source
  and the Trailkeep database only (NetworkPolicy), and is not used by the web pods.
- **Secrets:** the source password is a Secret file; the mapping file references `${…}` names, never values, and
  `validate-mapping` fails if it finds anything that looks like a credential.

## 11. Testing

- **Synthetic legacy schema** (`src/test/resources/importer/legacy-schema/`): one small tracker shape with
  deliberate awkwardness (zone-less timestamps across a DST change, Windows-1252 text, a status no one documented,
  orphan comments, a 40 MB attachment, an EICAR file assembled at runtime, a disguised executable, duplicate people
  with different email case). DDL and seed data for **SQL Server, Oracle and PostgreSQL**, plus the same data exported
  as CSV and JSON Lines with each vendor's recipe.
- **Source equivalence:** for each vendor, JDBC extract and file extract of the same data produce identical canonical
  row hashes. SQL Server runs via Testcontainers (`mcr.microsoft.com/mssql/server`, Developer edition, EULA accepted
  through the container's documented variable for testing only), Oracle via the existing `-Poracle-it` images,
  PostgreSQL as today.
- **Transform unit tests:** every transform and every `onMissing`/`onOverflow` branch; property tests that transforms
  are deterministic.
- **End to end:** extract → dry-run → load → reconcile gives `RECONCILED`; a second load is a no-op; a load killed
  mid-run resumes to the same final state; a delta run loads only new keys and reports changed ones.
- **Security tests:** a mapping with two statements, a DML statement or a path escaping the attachment root is
  rejected; imported XSS payloads (the existing corpus) render inert; restricted cases are invisible to the people
  who could not see them in the legacy model's mapped teams.

## 12. Delivery plan (replaces the single WP-26 row; each item ≤ 400 net lines)

| PR | Content |
|---|---|
| 26a | `V5_x` staging, ledger, issue, checkpoint and `IMPORT_RUN` tables with grants; canonical record types |
| 26b | Mapping file JSON Schema, parser, validator, closed transform set, with unit tests |
| 26c | File sources (CSV/TSV, JSON Lines, manifest, encodings, attachment folder) |
| 26d | JDBC sources for PostgreSQL and Oracle (snapshot, streaming, read-only guard, query validation) |
| 26e | JDBC source for SQL Server (`mssql-jdbc` in the importer runtime, Testcontainers IT) |
| 26f | Dry run: validation rules, issues, reconciliation preview, SLA forecast |
| 26g | Load: cases, SLA, search, comments, audit; ledger; resume; run lock |
| 26h | Load: attachments through `UploadIntake`; scanning-mode behaviour |
| 26i | Reconciliation report (CSV + HTML), `purge-staging`, Job manifest, NetworkPolicy, System screen "last import run" data for WP-25 |
| 26j | Docs: export recipes per vendor, source-account recipes, the synthetic worked example end to end |

Migration numbering follows plan row A.4 #37 and the `V4_n` precedent; the importer's tables come after `V5__closure`.

## 13. Open questions for the owner

1. **Legacy history:** is the single "Imported history" comment per case enough for v1 (§7.5), or is a filterable
   structured history needed at go-live?
2. **Unmatched owners of open cases:** default to the team manager (proposed), or to a named import owner per team?
3. **Delta conflicts** (`CHANGED_AFTER_IMPORT`): report-only (proposed), or overwrite when the Trailkeep copy has no
   edits since import?
