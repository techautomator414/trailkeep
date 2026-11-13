<!--
  SPDX-License-Identifier: Apache-2.0
  Copyright 2026 Charlie Genova and contributors
-->
# Worked example: one import from run request to reconciliation

This page follows one import through every step of the importer's lifecycle (docs/design/importer.md §3): a small,
synthetic legacy tracker, the example mapping of design §6.1, and the commands an operator runs, with what each one
writes. Every name and value on it is invented.

**The page is tested.** Each block marked `<!-- recipe: example/... -->` is read from this file by `WorkedExampleIT`
(Trailkeep on PostgreSQL) and `OracleWorkedExampleIT` (Trailkeep on Oracle, `-Poracle-it`). The test creates the legacy
database from the SQL block below, writes the mapping and lookup files from their blocks, runs each command as the
importer `Job` runs it, and compares the dry-run figures, the loaded cases and the reconcile report with the blocks
that show them. If the importer's behaviour changes, the test fails until this page says what it now does.

## The legacy tracker

The legacy tracker keeps its data in five tables. Here it runs on PostgreSQL, so the example mapping is read with
`vendor: postgresql`; the mapping is written for a SQL Server tracker with the same tables, and its queries run
unchanged on both. Each of the five cases shows one part of the import:

| Case | What it shows |
|---|---|
| LT-1001 | An open case whose assignee matches a Trailkeep analyst by email. It has a note, an attachment and two change-history rows. Its title has stray spaces around it. |
| LT-1002 | An open, confidential case. Its assignee has left and matches no Trailkeep user. It has no description and no "found on" day. It was opened at 01:30 on the night the clocks went back, a time that happened twice. |
| LT-1003 | A closed case on legal hold in the legacy tracker. |
| LT-1004 | A case closed in 2017, past the seven-year retention of its case type. |
| LT-1005 | An open case with an access list the legacy tracker enforced and Trailkeep cannot represent. |

<!-- recipe: example/legacy.sql (WorkedExampleIT) -->
```sql
CREATE SCHEMA dbo;
CREATE TABLE dbo.users (user_id VARCHAR(10) PRIMARY KEY, display_name VARCHAR(100), email VARCHAR(200));
CREATE TABLE dbo.cases (case_id INTEGER PRIMARY KEY, case_no VARCHAR(20), subject_line VARCHAR(300), details TEXT,
  unit VARCHAR(40), assignee VARCHAR(10), state VARCHAR(20), result VARCHAR(20), opened TIMESTAMP, closed TIMESTAMP,
  found_on DATE, confidential CHAR(1), acl_entries TEXT, hold_flag CHAR(1));
CREATE TABLE dbo.notes (note_id INTEGER PRIMARY KEY, case_id INTEGER, author VARCHAR(10), created TIMESTAMP,
  body TEXT);
CREATE TABLE dbo.files (file_id INTEGER PRIMARY KEY, case_id INTEGER, file_name VARCHAR(260), size_bytes BIGINT,
  uploaded TIMESTAMP, uploader VARCHAR(10), content BYTEA);
CREATE TABLE dbo.case_audit (audit_id INTEGER PRIMARY KEY, case_id INTEGER, changed_at TIMESTAMP,
  changed_by VARCHAR(10), field_name VARCHAR(40), old_val TEXT, new_val TEXT);

INSERT INTO dbo.users VALUES
  ('u100', 'Synthetic Analyst', 'Analyst.One@Example.TEST'),
  ('u200', 'Synthetic Former Officer', 'former.officer@legacy.example.test');

INSERT INTO dbo.cases VALUES
  (1001, 'LT-1001', '  Synthetic vendor invoice query  ', 'Synthetic description of an open case.', 'Unit 1',
   'u100', 'Open', NULL, '2025-03-03 09:15:00', NULL, '2025-02-28', 'N', NULL, 'N'),
  (1002, 'LT-1002', 'Synthetic records access review', NULL, 'Unit 1',
   'u200', 'In Review', NULL, '2025-11-02 01:30:00', NULL, NULL, 'Y', NULL, 'N'),
  (1003, 'LT-1003', 'Synthetic payroll exception', 'Synthetic description of a case on legal hold.', 'Unit 2',
   'u100', 'Closed', 'Substantiated', '2026-06-01 10:00:00', '2026-09-15 16:30:00', '2026-05-29', 'N', NULL, 'Y'),
  (1004, 'LT-1004', 'Synthetic travel claim', 'Synthetic description of a case closed long ago.', 'Unit 2',
   'u200', 'Closed', 'Unfounded', '2016-02-01 08:00:00', '2017-05-10 11:00:00', '2016-01-29', 'N', NULL, 'N'),
  (1005, 'LT-1005', 'Synthetic system access concern', 'Synthetic description of a case with a legacy ACL.',
   'Unit 1', 'u100', 'Open', NULL, '2025-06-10 13:45:00', NULL, '2025-06-10', 'N', 'group:legacy-auditors', 'N');

INSERT INTO dbo.notes VALUES
  (5001, 1001, 'u100', '2025-03-04 10:00:00', 'Asked the synthetic vendor for the *original* invoice.'),
  (5002, 1003, 'u200', '2026-07-01 09:00:00', 'Synthetic interview notes.');

INSERT INTO dbo.files VALUES
  (7001, 1001, 'invoice-scan.txt', 34, '2025-03-04 10:05:00', 'u100',
   convert_to('Synthetic scanned invoice, page 1.', 'UTF8'));

INSERT INTO dbo.case_audit VALUES
  (9001, 1001, '2025-03-03 09:20:00', 'u100', 'State', 'New', 'Open'),
  (9002, 1001, '2025-03-05 08:00:00', 'u100', 'Details', 'Synthetic first draft.',
   'Synthetic description of an open case.');
```

The legacy database's administrator creates the read-only account of [source-accounts.md](source-accounts.md), with
`SELECT` on these five tables only. Its password reaches the importer as the `trailkeep-import-source` Secret.

## What Trailkeep needs first

The importer never creates teams, case types or people (design §7.3), so they exist before the run:

- the team **Intake review**, which both legacy units map to;
- the case type **general-review**, with a seven-year retention and the outcomes `SUBSTANTIATED` and
  `UNSUBSTANTIATED`;
- an analyst in that team who has signed in with the address `analyst.one@example.test`;
- a manager in that team, `manager.two@example.test`, who is the team's default owner for open cases whose legacy
  owner cannot keep them;
- a Compliance Admin, who requests the run and signs it off.

## The mapping and its lookup files

The mapping file is reviewed like code and kept private. This is the example mapping
(`src/test/resources/importer/example-mapping.yaml`) as this example uses it: the only change is `vendor: postgresql`.

<!-- recipe: example/mapping.yaml (WorkedExampleIT) -->
```yaml
version: 1
source:
  id: legacy1
  kind: jdbc
  vendor: postgresql
  url: ${import.source.url}
  user: ${import.source.user}
  timezone: America/Chicago
  dstAmbiguity: earlier
limits:
  maxErrorRate: 0.0
  acceptSkip: [ATTACHMENT_TOO_LARGE]
  batchSize: 500
lookups:
  status: { "Open": OPEN, "In Review": OPEN, "Closed": CLOSED, "Void": CLOSED }
  priority: { "1": HIGH, "2": NORMAL, "3": NORMAL }
  outcome: { "Substantiated": SUBSTANTIATED, "Unfounded": UNSUBSTANTIATED }
  team: { file: lookups/teams.csv, key: legacy_unit, value: trailkeep_team }
  historyField: { "State": status, "Assignee": owner, "Details": description, "Subject": subject }
entities:
  case:
    query: |
      SELECT c.case_id, c.case_no, c.subject_line, c.details, c.unit, c.assignee, c.state, c.result,
             c.opened, c.closed, c.found_on, c.confidential, c.acl_entries, c.hold_flag
      FROM dbo.cases c
    key: case_id
    keyCase: sensitive
    fields:
      legacyNumber: case_no
      title: { trim: subject_line, maxLength: 200, onOverflow: truncateWithIssue }
      description: { coalesce: [details, "(no description in the legacy record)"],
                     maxLength: 20000, onOverflow: continueInComment }
      teamKey: { lookup: team, from: unit, onMissing: fail }
      ownerKey: assignee
      status: { lookup: status, from: state, onMissing: fail }
      closureOutcome: { lookup: outcome, from: result, onMissing: default, default: UNSUBSTANTIATED }
      priority: { const: NORMAL }
      sensitivity: { when: { column: confidential, equals: "Y" }, then: RESTRICTED, else: STANDARD }
      legacyAcl: { when: { column: acl_entries, notBlank: true }, then: true, else: false }
      legalHold: { when: { column: hold_flag, equals: "Y" }, then: true, else: false }
      openedAt: { timestamp: opened }
      closedAt: { timestamp: closed }
      identifiedOn: { date: { coalesce: [found_on, opened] } }
      caseTypeKey: { const: general-review }
  comment:
    query: SELECT note_id, case_id, author, created, body FROM dbo.notes
    key: note_id
    parent: { entity: case, columns: [case_id] }
    fields:
      authorKey: author
      writtenAt: { timestamp: created }
      body: { text: body, format: plain }
  attachment:
    query: SELECT file_id, case_id, file_name, size_bytes, uploaded, uploader FROM dbo.files
    key: file_id
    parent: { entity: case, columns: [case_id] }
    fields:
      fileName: file_name
      uploadedAt: { timestamp: uploaded }
      uploaderKey: uploader
      content: { query: "SELECT content FROM dbo.files WHERE file_id = ?", size: size_bytes }
  event:
    query: SELECT audit_id, case_id, changed_at, changed_by, field_name, old_val, new_val FROM dbo.case_audit
    key: audit_id
    parent: { entity: case, columns: [case_id] }
    fields:
      at: { timestamp: changed_at }
      whoKey: changed_by
      kind: { const: FIELD_CHANGE }
      field: { lookup: historyField, from: field_name, onMissing: default, default: other }
      oldValue: old_val
      newValue: new_val
attachments:
  root: /var/lib/trailkeep/import/files
  stripPrefix: '\\fileshare\cases\'
people:
  query: SELECT user_id, display_name, email FROM dbo.users
  key: user_id
  match: [externalId, email]
  unmatched: snapshot
  fields: { displayName: display_name, email: email }
defaults:
  owner: { byTeam: lookups/default-owners.csv }
visibility:
  onUnrepresentableAcl: restricted
```

The two lookup files sit next to it, under `lookups/`. The team lookup turns each legacy unit into a Trailkeep team
name:

<!-- recipe: example/teams.csv (WorkedExampleIT) -->
```text
legacy_unit,trailkeep_team
Unit 1,Intake review
Unit 2,Intake review
```

The default-owner file names, per team, the user who gets an open case whose legacy owner cannot keep it, by email
address or external id:

<!-- recipe: example/default-owners.csv (WorkedExampleIT) -->
```text
team,owner
Intake review,manager.two@example.test
```

The three files go on the `trailkeep-import-data` claim, which the `Job` mounts read-only at
`/var/lib/trailkeep/import` (deploy/README.md, "Importer"):

```text
/var/lib/trailkeep/import/mapping.yaml                 IMPORT_MAPPING
/var/lib/trailkeep/import/lookups/teams.csv
/var/lib/trailkeep/import/lookups/default-owners.csv
/var/lib/trailkeep/import/files/                       attachments.root (empty: the content query reads the bytes)
```

The SHA-256 of the mapping and its lookup files is recorded at `validate-mapping` and checked again by every later
command, so none of the three files may change during the run. A changed file means a new run.

## Running the commands

Each command is one run of the `trailkeep-importer` `Job`. Set `IMPORT_COMMAND` and `IMPORT_RUN` in it, apply the
directory the command needs, read the outcome in the pod log and on the Import runs screen, then delete the finished
`Job` before the next command. This example runs with `app.attachments.scanning=external`, as the `external` variants
set it, so its directories are:

| Step | `IMPORT_COMMAND` | Apply |
|---|---|---|
| 1 | `validate-mapping` | `deploy/jobs/importer-external-scanning-source-access` |
| 2 | `extract` | `deploy/jobs/importer-external-scanning-source-access` |
| 3 | `dry-run` | `deploy/jobs/importer-external-scanning` |
| 5 | `load` | `deploy/jobs/importer-external-scanning` |
| 6 | `reconcile` | `deploy/jobs/importer-external-scanning` |
| 7 | `purge-staging` | `deploy/jobs/importer-external-scanning` |

With clamd scanning, use `deploy/jobs/importer-source-access` and `deploy/jobs/importer` instead: the attachment is then
scanned at the load, and the `NOT_SCANNED` warning below does not appear. Only steps 1 and 2 get the source password
and the route to the legacy database.

### Step 0: the run request

A Compliance Admin opens the Import runs screen and requests a run for the source `legacy1`, the mapping's
`source.id`. The screen shows the new run's id, here *N*. The run is `REQUESTED`, and the request is audited as
`IMPORT_RUN_REQUESTED`.

### Step 1: `validate-mapping`

The command checks the mapping against its schema and resolves its lookups and default owners. It checks every query
with the query validator, then test-fetches one row of each query as the source account. Nothing is staged. The run
becomes `VALIDATED`, with the files' hash recorded. A problem is recorded as a `FATAL` issue at its place in the file,
and the run goes back to `REQUESTED`: fix the file and run the command again.

### Step 2: `extract`

The command reads the five queries in one read-only snapshot, applies the mapping's transforms and fills the staging
tables, with a keyed row hash for each row. Here it stages 2 people, 5 cases, 2 comments, 1 attachment and 2 history
rows. The attachment's 34 bytes, fetched by the content query, are written to the run's content area
(`IMPORT_CONTENTAREA/N/<sha256>`). The run becomes `EXTRACTED`.

One `WARNING` is recorded: `DST_AMBIGUOUS` for LT-1002, whose opening time, 01:30 on 2 November 2025, happened twice
in Chicago. `dstAmbiguity: earlier` reads it as the first one, Central Daylight Time, so it is stored as 06:30 UTC.
Like every issue, it names the case by a keyed hash of its key, never by the key or a value.

### Step 3: `dry-run`

The command checks every staged row against Trailkeep's rules without writing a case. It records its issues and its
figures, and records the scanning mode in force on the run (`EXTERNAL` here). It writes `dry-run.csv` and
`dry-run.html` to the run's report folder (`IMPORT_REPORTSDIR/N/`). The run becomes `DRY_RUN`. Its one new issue is the
`WARNING` `NOT_SCANNED` for the attachment, which external scanning stores without a scan (design §4.3).

These are the figures the Import runs screen shows for the run. `team` stands for Intake review's id. The SLA forecast
day is left out, because it is the day the dry run runs (set `IMPORT_GOLIVE` to forecast another day):

<!-- recipe: example/figures.txt (WorkedExampleIT) -->
```text
ROWS PERSON 2
ROWS CASE 5
ROWS COMMENT 2
ROWS ATTACHMENT 1
ROWS EVENT 2
ISSUES CASE/WARNING/DST_AMBIGUOUS 1
ISSUES ATTACHMENT/WARNING/NOT_SCANNED 1
CASES OPEN 3
CASES CLOSED 2
VISIBILITY_STANDARD team 3
VISIBILITY_RESTRICTED team 2
LEGACY_ACL restricted 1
HOLDS LEGAL_HOLD 1
HOLDS PAST_RETENTION 1
ARCHIVED_NEXT_RUN - 0
ATTACHMENT_FILES - 1
ATTACHMENT_BYTES - 34
NOT_SCANNED_FILES - 1
NOT_SCANNED_BYTES - 34
PEOPLE_MATCHED OPEN_OWNER 2
PEOPLE_UNMATCHED OPEN_OWNER 1
PEOPLE_MATCHED CLOSED_OWNER 1
PEOPLE_UNMATCHED CLOSED_OWNER 1
PEOPLE_MATCHED AUTHOR 1
PEOPLE_UNMATCHED AUTHOR 1
PEOPLE_MATCHED UPLOADER 1
DEFAULT_OWNER_CASES team 1
SLA_FORECAST OVERDUE 3
PRE_MARKED SLA_OVERDUE 3
PRE_MARKED SLA_OVERDUE_REPEAT 3
```

What the approver reads in them:

- **Visibility.** Two cases load restricted: LT-1002, which the tracker marked confidential, and LT-1005, whose access
  list Trailkeep cannot represent (`visibility.onUnrepresentableAcl: restricted`, counted as `LEGACY_ACL`).
- **Holds.** LT-1003 loads on legal hold because the tracker held it, and LT-1004 because its retention had passed.
  `ARCHIVED_NEXT_RUN` counts the cases the load will not hold that the archive job's first run after go-live would
  archive: none here.
- **People.** The analyst matches by email (case does not matter), so LT-1001 and LT-1005 keep their owner. The former
  officer matches no one: LT-1002 goes to the default owner, and LT-1004 and the note on LT-1003 keep the officer's
  name as a snapshot.
- **SLA.** All three open cases are long overdue. The load records their overdue alerts as already sent, so go-live
  sends no mail about the backlog (design §7.1).

### Step 4: the sign-off

A Compliance Admin, here the one who requested the run, opens the run on the Import runs screen. They review the
figures and the recorded scanning mode, and acknowledge each `WARNING` class: `DST_AMBIGUOUS` and `NOT_SCANNED`. Then
they sign the run off. The run records who approved it, when, and the acknowledged classes
(`DST_AMBIGUOUS,NOT_SCANNED`), and the sign-off is audited as `IMPORT_RUN_SIGNED_OFF`. A run with a `FATAL` issue
cannot be signed off.

### Step 5: `load`

The command refuses to start unless the run is signed off, has no `FATAL` issue, its files' hash is unchanged and the
scanning mode in force is the recorded one. Then it loads one case per transaction: the case with its SLA row and
search entry, its closure, comments, attachments and imported history, and a ledger row for each, recording what it
wrote. The run becomes `LOADED`. Each line below is one loaded case: legacy number, status, visibility, owner, the
legacy owner's name kept as a snapshot, the opening time in UTC, the identified-on day and the legal hold reason.

<!-- recipe: example/cases.txt (WorkedExampleIT) -->
```text
LT-1001 OPEN   STANDARD   analyst snapshot=-                        opened=2025-03-03T15:15:00Z identified=2025-02-28 hold=-
LT-1002 OPEN   RESTRICTED manager snapshot=Synthetic Former Officer opened=2025-11-02T06:30:00Z identified=2025-11-02 hold=-
LT-1003 CLOSED STANDARD   analyst snapshot=-                        opened=2026-06-01T15:00:00Z identified=2026-05-29 hold=Imported: on legal hold in the legacy system (import run N)
LT-1004 CLOSED STANDARD   import  snapshot=Synthetic Former Officer opened=2016-02-01T14:00:00Z identified=2016-01-29 hold=Imported: past its retention period at import; review before release (import run N)
LT-1005 OPEN   RESTRICTED analyst snapshot=-                        opened=2025-06-10T18:45:00Z identified=2025-06-10 hold=-
```

- **LT-1001** keeps its analyst. Its note is stored Markdown-escaped (`\*original\*`), because the mapping declares
  notes as plain text. Its attachment is stored `NOT_SCANNED`. Its two legacy history rows are stored as imported
  history (`CASE_IMPORTED_EVENT`, design §7.5): the state change in clear (`New` to `Open`), the description change
  redacted, because a description may hold personal data. The case's History tab shows both rows among its own
  events, each marked "Imported" with "From the legacy tracker, import run N" (#200).
- **LT-1002** goes to the manager, the team's default owner, with the former officer's name kept. `relink-owners`
  hands it back if that person ever signs in. Its missing description became the mapping's placeholder, and its
  identified-on day is its opening day in Chicago.
- **LT-1003** and **LT-1004** are created on legal hold by the "Trailkeep import" principal, with a fixed reason that
  names the run. LT-1004's legacy owner matched no one, so it is owned by that principal, with the officer's name kept.
  Neither will be archived or purged until a Compliance Admin releases the hold.

Running `load` again changes nothing: every row is already in the ledger. A load that stopped half-way resumes the same
way.

### Step 6: `reconcile`

The command compares the source rows, the staging and what Trailkeep now holds. It recomputes each written row's hash
from Trailkeep and checks it against the hash the load recorded (design §9). Here every row matches, so the run becomes
`RECONCILED`. The report, `reconcile.csv` (with `reconcile.html` beside it), holds counts, the verdict and, when there
are any, the differences by keyed hash and Trailkeep id:

<!-- recipe: example/reconcile.csv (WorkedExampleIT) -->
```text
"row","entity","outcome","rows","key_hash","trailkeep_id"
"count","CASE","LOADED","5","",""
"count","CASE","WRITTEN_MATCH","5","",""
"count","CASE","CLOSURE_MATCH","2","",""
"count","COMMENT","LOADED","2","",""
"count","COMMENT","WRITTEN_MATCH","2","",""
"count","ATTACHMENT","LOADED","1","",""
"count","ATTACHMENT","WRITTEN_MATCH","1","",""
"count","EVENT","LOADED","2","",""
"count","EVENT","WRITTEN_MATCH","2","",""
"verdict","","RECONCILED","","",""
```

`LOADED` counts the source rows that are in the ledger, `WRITTEN_MATCH` those whose rows in Trailkeep still hash to
what the load wrote, and `CLOSURE_MATCH` the closed cases whose closure does too.

### Step 7: `purge-staging`

The staging rows and the content area hold legacy content, so the last command deletes the run's staging rows and its
content-area folder. The staging TTL would delete them later anyway. The report files stay for the approver; they
hold counts, codes, keyed hashes and Trailkeep ids, never a legacy value.

## Adapting the example

- Write each entity's query against your tracker, and keep `source.id` the same for every run of that source: it is
  the prefix of every case's legacy reference.
- Read [export-recipes.md](export-recipes.md) if the import must run from exported files instead of the live
  database. The mapping stays the same, with each `query:` replaced by `file:`.
- Put every team that has open cases in the default-owner file. A team without a default owner is a `FATAL`
  `DEFAULT_OWNER_MISSING` at the dry run.
- Choose `dstAmbiguity` deliberately. With `reject`, the schema's default, LT-1002 would be an error to fix in the
  source rather than a warning to acknowledge.
