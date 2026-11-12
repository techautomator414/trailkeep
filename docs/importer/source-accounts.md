<!--
  SPDX-License-Identifier: Apache-2.0
  Copyright 2026 Charlie Genova and contributors
-->
# Source accounts: the read-only login the importer uses on a legacy database

The importer reads a legacy database through one account, which the legacy database's administrator creates by
following this recipe (docs/design/importer.md §4.1). The recipe is the control over which tables the importer can
read. The importer's query validator is the control over which functions a query can call. The read-only snapshot is
defence in depth, never relied on alone.

**Prefer a copy.** Run the import against a restored copy or a snapshot of the legacy database, not against
production. A long extract holds a snapshot open, which blocks vacuum on PostgreSQL, needs undo on Oracle and fills
tempdb's version store on SQL Server. On a copy the administrator can also revoke what production cannot spare.

The account's password is given to the importer Job as a Secret file, read as `import.source.password`. It never appears in the mapping file. `validate-mapping` connects as this account to test-fetch every query, so a missing grant shows up there as `SOURCE_PERMISSION_DENIED` at the query's place.

## PostgreSQL

The importer reads in one `REPEATABLE READ READ ONLY` transaction, so every query of a run sees the same snapshot.
Use TLS with `sslmode=verify-full` and `sslrootcert` pointing at the mounted CA.

Replace `legacy` with the schema the mapping reads, and `trailkeep_source` with the account name.

```sql
CREATE ROLE trailkeep_source LOGIN PASSWORD '<from your secret store>';
GRANT CONNECT ON DATABASE <legacy database> TO trailkeep_source;
GRANT USAGE ON SCHEMA legacy TO trailkeep_source;
-- Only the tables or views the mapping's queries read; never ALL TABLES, never pg_read_all_data.
GRANT SELECT ON legacy.cases, legacy.notes, legacy.files, legacy.users TO trailkeep_source;
-- PUBLIC can execute every function unless revoked. Revoke the legacy schema's functions where you can (always on
-- a copy); built-in functions stay executable, and the importer's allow-list refuses calls to any other function.
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA legacy FROM PUBLIC;
```

Do not grant `dblink`, `postgres_fdw`, `pg_execute_server_program`, `pg_read_server_files`,
`pg_write_server_files` or membership of any role that holds them.

What the recipe achieves is tested against a PostgreSQL container (`PostgresSourceQueryIT`). The account:

- can read the granted table;
- cannot insert, update or delete;
- cannot lock rows with `SELECT … FOR UPDATE`;
- cannot read a table it was not granted;
- cannot execute a legacy-schema function after the revoke.

## Oracle

The importer reads in one `SET TRANSACTION READ ONLY` transaction (transaction-level read consistency), after setting
the session's time zone to UTC and its date, timestamp and number formats to ISO ones. Undo must cover the whole
extract, or a query fails with ORA-01555 (`SOURCE_QUERY_FAILED`); a restored copy avoids both that and the load on
production. The run's fingerprint records the SCN the transaction started near. That transaction dies with the
session, so an extract that crashed starts over.

Connect with an Easy Connect URL, `jdbc:oracle:thin:@tcps://host:2484/service?ssl_server_dn_match=true` with
`javax.net.ssl.trustStore` (and `javax.net.ssl.trustStoreType=PKCS12`) pointing at the mounted CA, in a truststore
that holds certificates only and has no password: a secret never goes in the URL, so `javax.net.ssl.trustStorePassword`
is refused like any other property outside the allow-list. The importer
refuses a TNS descriptor, a TNS alias and any URL property outside its allow-list of TLS and timeout settings before
connecting, because descriptors and driver properties can name wallet and trace directories, provider classes and
configuration files. Auto-login wallets need `oraclepki`, which is not on the importer's classpath.

Replace `legacy` with the schema the mapping reads, and `trailkeep_source` with the account name.

```sql
CREATE USER trailkeep_source IDENTIFIED BY "<from your secret store>";
GRANT CREATE SESSION TO trailkeep_source;
-- READ, not SELECT: READ cannot lock rows (SELECT ... FOR UPDATE) or tables (LOCK TABLE).
-- Only the tables or views the mapping's queries read; never SELECT ANY TABLE or READ ANY TABLE.
GRANT READ ON legacy.cases TO trailkeep_source;
GRANT READ ON legacy.notes TO trailkeep_source;
GRANT READ ON legacy.files TO trailkeep_source;
GRANT READ ON legacy.users TO trailkeep_source;
```

Grant nothing else: no `EXECUTE` on the legacy schema, no database links, no `FLASHBACK`, no roles. `PUBLIC` already
holds `EXECUTE` on many `DBMS_*` and `UTL_*` packages, which a production DBA can rarely revoke. A function marked
`PRAGMA AUTONOMOUS_TRANSACTION` would run outside the read-only transaction, so calls are controlled by the importer's
validator: it refuses any function outside its allow-list of pure built-ins, any `@link`, and any qualified name that
is not a table, alias or subquery of the query, since Oracle calls a parameterless function without parentheses
(`SOME_PACKAGE.SOME_FUNCTION`).

A read-only transaction cannot read a table whose definition changed moments before it started (ORA-01466); do not
run DDL on the legacy schema while an extract runs.

What the recipe achieves is tested against the Oracle test containers, Free 23 and XE 21 (`OracleSourceIT`). The
account:

- can read the granted tables, every row of one read-only snapshot;
- cannot insert, update or delete;
- cannot lock rows with `SELECT … FOR UPDATE` or lock a table;
- cannot create a table;
- cannot read a table it was not granted;
- cannot call a legacy function that writes in an autonomous transaction, even unqualified through a public synonym,
  and nothing is written: an unqualified call without parentheses reads like a column, so it is the recipe's job (no
  `EXECUTE`), not the validator's;
- and the validator refuses a `DBMS_*` call with or without parentheses, a legacy-schema function and a database link.

## SQL Server

The importer reads in one `SNAPSHOT` transaction, so every query of a run sees the database as it was at the first
read. That needs row versioning, which the database's administrator turns on (on a restored copy it costs nothing):

```sql
ALTER DATABASE <legacy database> SET ALLOW_SNAPSHOT_ISOLATION ON;
```

`READ_COMMITTED_SNAPSHOT` alone is not enough: it versions each statement, not the transaction. Without
`ALLOW_SNAPSHOT_ISOLATION` every query fails (`SOURCE_QUERY_FAILED`) and nothing is read. Row versions are held in
tempdb's version store for the whole extract; a database snapshot (`CREATE DATABASE … AS SNAPSHOT OF …`), a restored
copy or a readable secondary (`ApplicationIntent=ReadOnly`) keeps that load off production. The snapshot transaction
takes no shared locks, so the extract never blocks the legacy application's writes. The run's fingerprint records the
server, database, session and start; the transaction dies with the session, so an extract that crashed starts over.

**SQL Server has no read-only transaction.** The grants below are what keep the account from writing, so grant
nothing else.

Connect with `jdbc:sqlserver://host:1433;databaseName=<legacy database>;encrypt=strict;hostNameInCertificate=host`
(TDS 8.0, SQL Server 2022 or later; older servers `encrypt=true`) and verify the server against the mounted CA with
`serverCertificate` or a password-less `trustStore`. `trustServerCertificate` is accepted only as `false`. The importer
refuses, before connecting, any property outside its allow-list of database, TLS, timeout and routing settings
(names compared without case, as the driver does) and any value in braces: driver properties can name classes to
instantiate (`socketFactoryClass`, `trustManagerClass`, `accessTokenCallbackClass`), load native or Kerberos code
(`integratedSecurity`, `authenticationScheme`), read key stores (`keyStoreLocation`) or carry a password, which never
goes in the URL. The account is therefore a SQL login.

Replace `legacy` with the database the mapping reads, `dbo` with its schema, and `trailkeep_source` with the account
name.

```sql
CREATE LOGIN trailkeep_source WITH PASSWORD = '<from your secret store>', CHECK_POLICY = ON;
USE legacy;
CREATE USER trailkeep_source FOR LOGIN trailkeep_source;
-- Only the tables or views the mapping's queries read; never db_datareader, never a schema-wide GRANT SELECT.
GRANT SELECT ON dbo.cases TO trailkeep_source;
GRANT SELECT ON dbo.notes TO trailkeep_source;
GRANT SELECT ON dbo.files TO trailkeep_source;
GRANT SELECT ON dbo.users TO trailkeep_source;
```

Grant nothing else: no `EXECUTE`, no role membership, no linked-server login mapping. Every account is a member of
`public`, which holds `EXECUTE` on some system objects and whatever the legacy database granted it. Check that nothing
of the legacy database's own is granted to `public`; this must return no rows, or revoke what it lists (on a restored
copy if production cannot spare it):

```sql
SELECT o.name, p.permission_name FROM sys.database_permissions p
JOIN sys.objects o ON o.object_id = p.major_id
WHERE p.grantee_principal_id = DATABASE_PRINCIPAL_ID('public') AND o.is_ms_shipped = 0;
```

Calls are controlled by the importer's validator: it refuses any function outside its allow-list of pure built-ins,
qualified (`dbo.fn(...)`) or not, table functions (`OPENQUERY`, `OPENROWSET`, a legacy table-valued function), a
four-part name (a linked server), `NEXT VALUE FOR` (a sequence advances outside any transaction), and table hints that
lock or step outside the snapshot (`UPDLOCK`, `XLOCK`, `HOLDLOCK`, `TABLOCKX`, `NOLOCK`, `READPAST` and the like). An
unqualified synonym reads like a table, so it is the recipe's job: SQL Server checks the grant on the object behind
it.

What the recipe achieves is tested against a SQL Server 2022 Developer edition container (`SqlServerSourceIT`, run on
`main`, nightly and release tags). The account:

- can read the granted tables, every row of one snapshot, without holding a lock that blocks a writer;
- cannot insert, update or delete, or create a table;
- cannot execute a procedure or call a legacy scalar or table-valued function, qualified or not;
- cannot read a table it was not granted, directly or through an unqualified synonym;
- cannot advance a sequence;
- and the validator refuses each locking or snapshot-escaping hint, a legacy function call, a table function, a
  linked server and `NEXT VALUE FOR`; the `public` check above finds a grant to `public`.
