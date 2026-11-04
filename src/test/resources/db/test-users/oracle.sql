-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- TEST-ONLY (never packaged, outside db/migration so Flyway never runs it as a migration). Executed as SYSTEM by
-- TestDatabaseUsers before every migrate, so R__grants.sql has grantees. NO AUTHENTICATION (18c+) means nobody can
-- log in until DatabaseGrantsIT sets a random password; none is stored here.
DECLARE
  PROCEDURE ensure_user(p_name IN VARCHAR2) IS
    v_count PLS_INTEGER;
  BEGIN
    SELECT COUNT(*) INTO v_count FROM ALL_USERS WHERE USERNAME = UPPER(p_name);
    IF v_count = 0 THEN
      EXECUTE IMMEDIATE 'CREATE USER ' || p_name || ' NO AUTHENTICATION';
      EXECUTE IMMEDIATE 'GRANT CREATE SESSION TO ' || p_name;
    END IF;
  END;
BEGIN
  ensure_user('${app_user}');
  ensure_user('${purge_user}');
END;
