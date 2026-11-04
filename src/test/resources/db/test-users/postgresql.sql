-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- TEST-ONLY (never packaged, outside db/migration so Flyway never runs it as a migration). Executed as the
-- container's superuser by TestDatabaseUsers before every migrate, so R__grants.sql has grantees. The roles get a
-- random password from DatabaseGrantsIT when it logs in as them; none is stored here.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${app_user}') THEN
    CREATE ROLE ${app_user} NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${purge_user}') THEN
    CREATE ROLE ${purge_user} NOLOGIN;
  END IF;
END
$$
