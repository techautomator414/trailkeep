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

## Oracle

Run one script in **one SQL\*Plus session**, as the source account of [source-accounts.md](source-accounts.md). It
pins the session's time zone and NLS formats, opens one `SET TRANSACTION READ ONLY` transaction (every query then
reads the same point in time) and spools each entity as **JSON Lines**: `JSON_OBJECT(* RETURNING CLOB)` over the
mapping's own query writes one JSON object per row on one line, with line breaks inside values escaped, dates and
timestamps in ISO-8601 whatever the NLS settings, and column names as keys (the importer reads keys
case-insensitively). SQL\*Plus's own column output is not used: it pads, wraps and truncates.

<!-- recipe: oracle/export.sql (OracleExportRecipeIT) -->
```sql
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
WHENEVER OSERROR EXIT FAILURE ROLLBACK
SET DEFINE OFF ECHO OFF TERMOUT OFF FEEDBACK OFF HEADING OFF VERIFY OFF TAB OFF
SET PAGESIZE 0 LINESIZE 32767 LONG 2000000000 LONGCHUNKSIZE 32767 TRIMSPOOL ON
ALTER SESSION SET TIME_ZONE = 'UTC';
ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD"T"HH24:MI:SS';
ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD"T"HH24:MI:SS.FF6';
ALTER SESSION SET NLS_TIMESTAMP_TZ_FORMAT = 'YYYY-MM-DD"T"HH24:MI:SS.FF6TZH:TZM';
ALTER SESSION SET NLS_NUMERIC_CHARACTERS = '.,';
SET TRANSACTION READ ONLY;
SPOOL scn.txt
SELECT TO_CHAR(TIMESTAMP_TO_SCN(SYSTIMESTAMP)) FROM DUAL;
SPOOL people.jsonl
SELECT JSON_OBJECT(* RETURNING CLOB) FROM (SELECT user_id, name, mail FROM legacy.people);
SPOOL cases.jsonl
SELECT JSON_OBJECT(* RETURNING CLOB) FROM (SELECT case_id, subject, details, state, opened, found_on, owner FROM legacy.cases);
SPOOL notes.jsonl
SELECT JSON_OBJECT(* RETURNING CLOB) FROM (SELECT note_id, case_id, author, created, body FROM legacy.notes);
SPOOL counts.csv
SELECT 'people.jsonl,person,' || COUNT(*) FROM legacy.people UNION ALL SELECT 'cases.jsonl,case,' || COUNT(*) FROM legacy.cases UNION ALL SELECT 'notes.jsonl,comment,' || COUNT(*) FROM legacy.notes;
SPOOL OFF
COMMIT;
EXIT
```

Run it in an empty folder with the client's character set pinned to UTF-8, then write the manifest:

```sh
NLS_LANG=AMERICAN_AMERICA.AL32UTF8 sqlplus -S -L trailkeep_source@//<legacy host>:<port>/<service> @export.sql
sh manifest.sh
```

<!-- recipe: oracle/manifest.sh (OracleExportRecipeIT) -->
```sh
#!/bin/sh
# Writes manifest.yaml for the files export.sql left in this folder: the SCN, and each file's entity, row count and
# SHA-256. Row counts come from the database, in the export's read-only transaction.
set -eu
{
  printf 'version: 1\n'
  printf 'fixedPoint: { kind: scn, value: "%s" }\n' "$(tr -d ' \r\n' < scn.txt)"
  printf 'files:\n'
  while IFS=, read -r file entity rows; do
    printf '  - { file: %s, entity: %s, rows: %s, sha256: %s }\n' \
      "$file" "$entity" "$(printf '%s' "$rows" | tr -d ' \r')" "$(sha256sum "$file" | cut -d ' ' -f 1)"
  done < counts.csv
} > manifest.yaml
rm scn.txt counts.csv
```

**What the settings do.** `SET LONG` and `LONGCHUNKSIZE` stop SQL\*Plus truncating a CLOB at its default 80
characters; `PAGESIZE 0`, `HEADING OFF`, `FEEDBACK OFF` and `TRIMSPOOL ON` leave nothing in the files but the rows.
`DEFINE OFF` keeps an `&` in a query from being read as a substitution variable. `NLS_LANG` makes the client write
UTF-8 whatever the database's character set. The NLS formats and the time zone do not change what `JSON_OBJECT` writes
(always ISO-8601) but keep anything else the script prints, such as the SCN, unambiguous.

**Rows longer than 32 767 bytes.** SQL\*Plus cannot write a line longer than `LINESIZE`, at most 32 767: it breaks
the row's JSON across lines. The broken lines are not JSON objects, so the extract refuses the file
(`ROW_UNREADABLE`) and nothing corrupt is loaded, but such rows cannot be exported this way: read the table through
the JDBC source.

**Attachments.** A `BLOB` is read **through the JDBC source only**: `DBMS_LOB` writes files on the database server
through a directory object, which a read-only account cannot do, and SQL\*Plus cannot spool binary data. Leave the
attachment entity out of a files export, or export the documents themselves to a folder by other means and read them
with `content: { path: ... }` under `attachments.root`.

**The mapping for these files.** As for PostgreSQL, with `.jsonl` files and no CSV dialect:

```yaml
source: { id: legacy1, kind: files, timezone: UTC, manifest: manifest.yaml }
```

**What the test verified** (`OracleExportRecipeIT`, Oracle Free 23 and XE 21, `-Poracle-it`): the script and the
manifest script run unchanged with each image's SQL\*Plus as the source account; the files pass the manifest checks;
CLOB values with commas, quotes and line breaks, non-ASCII text, `DATE` and fractional `TIMESTAMP` values and an
integer key arrive intact; the file extract and the JDBC extract of the same schema stage every person, case and
comment with the same keyed row hash and the same issues; and a row over 32 767 bytes makes the extract refuse the
file rather than stage a broken row.

## SQL Server

Export **from a database snapshot** (or a restored copy): every `sqlcmd` connection then reads the same state, so the
files need no common transaction. Creating the snapshot is a step for the database administrator, not for the source
account, which only reads it (a snapshot keeps the source database's users and grants):

```sql
CREATE DATABASE legacy_export ON (NAME = <logical data file name>, FILENAME = '<path>/legacy_export.ss')
  AS SNAPSHOT OF <legacy database>;
-- ... export ...
DROP DATABASE legacy_export;
```

`bcp -c` does not write RFC 4180 CSV (no quoting, no header, an empty string as a lone `0x00`), so the recipe writes
**JSON Lines**: `FOR JSON PATH, WITHOUT_ARRAY_WRAPPER, INCLUDE_NULL_VALUES` over the mapping's own query turns each
row into one JSON object on one line, with line breaks inside values escaped, `NULL` and the empty string kept apart,
and dates and times in ISO-8601. `sqlcmd -y 0` stops `sqlcmd` cutting long values at its default 256 characters
and leaves out the column headings, so it cannot be combined with `-h` (`sqlcmd` refuses `-h -1 -y 0`).

<!-- recipe: sqlserver/export.sh (SqlServerExportRecipeIT) -->
```sh
#!/bin/sh
# Exports each entity of the snapshot database as JSON Lines, with the row counts and the snapshot's identity for
# manifest.sh. The connection comes from sqlcmd's own variables: SQLCMDSERVER, SQLCMDDBNAME (the snapshot),
# SQLCMDUSER (the source account) and SQLCMDPASSWORD. SQLCMD_TLS holds TLS options; leave it unset to verify the
# server's certificate (-C, trusting any certificate, is for a test server only).
set -eu
run() {
  sqlcmd ${SQLCMD_TLS:-} -b -y 0 -f o:65001 -Q "SET NOCOUNT ON; $1" -o "$2"
}
json() {
  run "SELECT (SELECT r.* FOR JSON PATH, WITHOUT_ARRAY_WRAPPER, INCLUDE_NULL_VALUES) FROM ($1) AS r" "$2"
}
json "SELECT user_id, name, mail FROM dbo.people" people.jsonl
json "SELECT case_id, subject, details, state, opened, found_on, owner FROM dbo.cases" cases.jsonl
json "SELECT note_id, case_id, author, created, body FROM dbo.notes" notes.jsonl
run "SELECT 'people.jsonl,person,' + CAST(COUNT(*) AS VARCHAR(20)) FROM dbo.people
  UNION ALL SELECT 'cases.jsonl,case,' + CAST(COUNT(*) AS VARCHAR(20)) FROM dbo.cases
  UNION ALL SELECT 'notes.jsonl,comment,' + CAST(COUNT(*) AS VARCHAR(20)) FROM dbo.notes" counts.csv
run "SELECT name + '@' + CONVERT(VARCHAR(33), create_date, 126) FROM sys.databases WHERE name = DB_NAME()
  AND source_database_id IS NOT NULL" snapshot.txt
test -s snapshot.txt || { echo "SQLCMDDBNAME is not a database snapshot" >&2; exit 1; }
```

<!-- recipe: sqlserver/manifest.sh (SqlServerExportRecipeIT) -->
```sh
#!/bin/sh
# Writes manifest.yaml for the files export.sh left in this folder: the snapshot, and each file's entity, row count
# and SHA-256. Row counts come from the database snapshot.
set -eu
{
  printf 'version: 1\n'
  printf 'fixedPoint: { kind: snapshot, value: "%s" }\n' "$(tr -d ' \r\n' < snapshot.txt)"
  printf 'files:\n'
  while IFS=, read -r file entity rows; do
    printf '  - { file: %s, entity: %s, rows: %s, sha256: %s }\n' \
      "$file" "$entity" "$(printf '%s' "$rows" | tr -d ' \r')" "$(sha256sum "$file" | cut -d ' ' -f 1)"
  done < counts.csv
} > manifest.yaml
rm snapshot.txt counts.csv
```

Run both in an empty folder:

```sh
SQLCMDSERVER=<legacy host> SQLCMDDBNAME=legacy_export SQLCMDUSER=trailkeep_source SQLCMDPASSWORD=<from your secret store> sh export.sh
sh manifest.sh
```

**Long values.** Design §4.2.2 left open whether `-y 0` still cuts a value at 1 MB. With the `sqlcmd` of
mssql-tools18 it does not: the test exports a 1.1 MB description whole. Should another `sqlcmd` build cut a value,
its JSON line ends unclosed and the extract refuses the file (`ROW_UNREADABLE`), as for Oracle's long rows, rather
than stage a shortened row.

**Attachments** are read through the JDBC source (a content query): `bcp -n` writes a length prefix before each value,
and binary data has no place in JSON Lines. Leave the attachment entity out of a files export, as for Oracle.

**The mapping for these files** is the same as for Oracle: `.jsonl` files, `source: { ..., kind: files, manifest:
manifest.yaml }`. A `DATETIME` keeps its 1/300-second precision in both sources, so file and JDBC rows still hash
alike.

**What the test verified** (`SqlServerExportRecipeIT`, SQL Server 2022 Developer edition, `-Psqlserver-it`): the export
and manifest scripts run unchanged with the image's `sqlcmd` (mssql-tools18) as the source account, against a snapshot
the administrator created; the files pass the manifest checks; values with commas, quotes and line breaks, `NULL` next
to the empty string, non-ASCII text, `DATETIME` and seven-digit `DATETIME2` values and an integer key arrive intact;
the file extract and the JDBC extract of the same snapshot stage every person, case and comment with the same keyed
row hash and the same issues; and a 1.1 MB description is exported and staged whole.
