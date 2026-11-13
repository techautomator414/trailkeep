<!--
  SPDX-License-Identifier: Apache-2.0
  Copyright 2026 Charlie Genova and contributors
-->
# Export recipes: legacy data as files the importer reads

The importer reads a legacy tracker either live, through a read-only database account (the JDBC source,
[source-accounts.md](source-accounts.md)), or from files exported from it (docs/design/importer.md §4.2). This page
gives the export commands per database. **Every command block on this page is run by a test** against that database's
container and its output extracted: a block marked `<!-- recipe: ... -->` is read from this file by the test named
under it, so the page documents what was verified rather than what a manual says.

## What an export must be

- **One file per entity, holding exactly what that entity's query returns.** A files mapping is the JDBC mapping with
  each entity's `query:` replaced by `file:`, so the export runs the mapping's own queries. Raw per-table dumps cannot
  be joined by the importer: load them into a scratch database and use the JDBC source instead.
- **One fixed point for all files.** Every file of an export is read from one snapshot of the database (or from a
  frozen copy), so a comment written between the case export and the comment export can be neither an orphan nor
  missing. The manifest records which fixed point was used.
- **A manifest** (`manifest.yaml`, `importer/manifest-v1.schema.json`) lists each file with its entity, row count and
  SHA-256, and the fixed point. The extract refuses a file that is missing, changed or has another number of rows.
- **The same canonical rows.** Exported files and the live database, read through the same mapping, stage rows with
  the same keyed row hashes, so a trial on exports and a cut-over against the live database (or the other way round)
  compare. The recipe tests check this per database ("source equivalence", design §11).

The exports hold legacy case content. Write them to an encrypted location the importer Job mounts, never to a shared
drive, and delete them with the run's staging (`purge-staging`).

## PostgreSQL

Run one script in **one `psql` session**: every `\copy` then runs inside the one `REPEATABLE READ READ ONLY`
transaction the script opens, so all files come from one snapshot. A separate `psql -c` per file would read a
different snapshot each time. `\copy` runs on the client, so the files are written where `psql` runs, not on the
database server, and need no server-side privilege: the source account of [source-accounts.md](source-accounts.md) is
enough.

The script below exports the tables of the worked example. Replace each query with the mapping's own query for that
entity, keeping it on the `\copy` line (a `psql` meta-command is one line), and keep the counting query in step with
it.

<!-- recipe: postgresql/export.sql (PostgresExportRecipeIT) -->
```sql
\set ON_ERROR_STOP on
SET client_encoding = 'UTF8';
SET DateStyle = 'ISO, YMD';
SET TimeZone = 'UTC';
SET bytea_output = 'hex';
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
\copy (SELECT pg_current_snapshot()::text) TO 'snapshot.txt'
\copy (SELECT user_id, name, mail FROM legacy.people) TO 'people.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy (SELECT case_id, subject, details, state, opened, found_on, owner FROM legacy.cases) TO 'cases.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy (SELECT note_id, case_id, author, created, body FROM legacy.notes) TO 'notes.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy (SELECT file_id, case_id, name, bytes, uploaded, content FROM legacy.files) TO 'files.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy (SELECT 'people.csv', 'person', count(*) FROM (SELECT user_id, name, mail FROM legacy.people) q UNION ALL SELECT 'cases.csv', 'case', count(*) FROM (SELECT case_id FROM legacy.cases) q UNION ALL SELECT 'notes.csv', 'comment', count(*) FROM (SELECT note_id FROM legacy.notes) q UNION ALL SELECT 'files.csv', 'attachment', count(*) FROM (SELECT file_id FROM legacy.files) q) TO 'counts.csv' WITH (FORMAT csv)
COMMIT;
```

Run it in an empty folder, as the source account, then write the manifest from the files it left there:

```sh
psql -X -v ON_ERROR_STOP=1 "host=<legacy host> dbname=<legacy database> user=trailkeep_source sslmode=verify-full" -f export.sql
sh manifest.sh
```

<!-- recipe: postgresql/manifest.sh (PostgresExportRecipeIT) -->
```sh
#!/bin/sh
# Writes manifest.yaml for the files export.sql left in this folder: the snapshot, and each file's entity, row count
# and SHA-256. Row counts come from the database, in the export's snapshot: a CSV line count is wrong as soon as a
# value holds a line break.
set -eu
{
  printf 'version: 1\n'
  printf 'fixedPoint: { kind: snapshot, value: "%s" }\n' "$(cat snapshot.txt)"
  printf 'files:\n'
  while IFS=, read -r file entity rows; do
    printf '  - { file: %s, entity: %s, rows: %s, sha256: %s }\n' \
      "$file" "$entity" "$rows" "$(sha256sum "$file" | cut -d ' ' -f 1)"
  done < counts.csv
} > manifest.yaml
rm snapshot.txt counts.csv
```

**What the settings do.** `client_encoding` and `ENCODING 'UTF8'` make the files UTF-8 whatever the database's
encoding. `DateStyle = 'ISO, YMD'` writes dates as `2019-02-28` and timestamps as `2019-03-01 09:30:00.25`, which the
importer reads as ISO-8601 with a space for the `T`; `TimeZone = 'UTC'` writes a `timestamptz` with the offset `+00`.
`bytea_output = 'hex'` writes binary columns as `\x…`. `FORMAT csv` writes RFC 4180: a value holding a comma, a quote
or a line break is quoted, a quote doubled; a NULL is an unquoted empty field and an empty string a quoted one (`""`).

**The mapping for these files.** Change the `source` block and each entity's `query:` to `file:`; every field stays
as it was:

```yaml
source: { id: legacy1, kind: files, timezone: UTC, manifest: manifest.yaml, csv: { nullToken: "" } }
entities:
  case:
    file: cases.csv     # was: query: "SELECT case_id, subject, details, state, opened, found_on, owner FROM legacy.cases"
```

`nullToken: ""` keeps NULL and the empty string apart, as the database did. An attachment's bytes exported in the
file are read with `content: { binary: hex, column: content }`, which accepts the `\x` prefix; through JDBC the same
attachment is read with a content query instead (`content: { query: "SELECT content FROM legacy.files WHERE file_id =
?", size: bytes }`). Large attachments are better read through JDBC, one at a time, than held in a CSV field.

**What the test verified** (`PostgresExportRecipeIT`, PostgreSQL 16): the script and the manifest script run unchanged
as the source account of [source-accounts.md](source-accounts.md); the files pass the manifest checks; values with
commas, quotes and line breaks, NULL next to the empty string, non-ASCII text, fractional seconds, an integer key, and
`bytea` content all arrive intact; and the file extract and the JDBC extract of the same database stage every person,
case, comment and attachment with the same keyed row hash and the same issues.

## Oracle and SQL Server

Their recipes (SQL\*Plus with one read-only transaction and pinned NLS formats; `sqlcmd` writing JSON Lines from a
database snapshot) are added with their recipe tests. Until then, read these databases through the JDBC source.
