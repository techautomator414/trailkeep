-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
--
-- The 50 000-case seed of perf/seed-cases-postgresql.sql for Oracle (WP-30): the same rows, value for value, written
-- in Oracle SQL. Synthetic and deterministic; never applied by Flyway and never packaged. Statements end with a line
-- holding only "/", as in SQL*Plus, and carry no semicolon (the JDBC driver refuses one): run it with SQL*Plus or SQLcl
-- as the schema owner, on a schema the application has migrated; OracleComments500kPerfIT applies it with "/" lines as
-- the separator. See perf/seed-cases-postgresql.sql for the shape.

INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)
SELECT 900000000 + u,
       'perf-user-' || LPAD(TO_CHAR(u), 3, '0'),
       'Perf User ' || LPAD(TO_CHAR(u), 3, '0'),
       'perf-user-' || LPAD(TO_CHAR(u), 3, '0') || '@example.invalid',
       0,
       TIMESTAMP '2026-01-01 00:00:00 +00:00'
  FROM (SELECT LEVEL AS u FROM DUAL CONNECT BY LEVEL <= 200)
/

INSERT INTO TEAM (ID, NAME)
SELECT 900000000 + t, 'Perf Team ' || LPAD(TO_CHAR(t), 2, '0')
  FROM (SELECT LEVEL AS t FROM DUAL CONNECT BY LEVEL <= 20)
/

INSERT INTO APP_USER_TEAM (USER_ID, TEAM_ID, ROLE_IN_TEAM)
SELECT 900000000 + u, 900000000 + 1 + MOD(u - 1, 20), CASE WHEN u > 180 THEN 'MANAGER' ELSE 'ANALYST' END
  FROM (SELECT LEVEL AS u FROM DUAL CONNECT BY LEVEL <= 200)
/

INSERT INTO SLA_POLICY (ID, NAME, TARGET_DAYS) VALUES (900000001, 'Perf 30-day policy', 30)
/

INSERT INTO CASE_TYPE (ID, NAME, SLA_POLICY_ID)
SELECT 900000000 + k, 'Perf case type ' || k, 900000001
  FROM (SELECT LEVEL AS k FROM DUAL CONNECT BY LEVEL <= 5)
/

INSERT INTO CASE_RECORD (ID, CASE_NUMBER, TITLE, DESCRIPTION, SUBJECT_TEXT, CASE_TYPE_ID, TEAM_ID, OWNER_ID, PRIORITY,
                         STATUS, SENSITIVITY, IDENTIFIED_ON, OPENED_AT, CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
SELECT 900000000 + i,
       'PERF-2026-' || LPAD(TO_CHAR(i), 6, '0'),
       DECODE(MOD(i, 8), 0, 'Heron', 1, 'Plover', 2, 'Godwit', 3, 'Dunlin', 4, 'Curlew', 5, 'Lapwing', 6, 'Redshank',
              'Sanderling')
           || ' ' || DECODE(MOD(TRUNC(i / 8), 6), 0, 'access review', 1, 'policy exception', 2, 'vendor finding',
                            3, 'audit follow-up', 4, 'incident report', 'training gap')
           || ' ' || i,
       TO_CLOB('Synthetic description ' || i || ': ' || LOWER(RAWTOHEX(STANDARD_HASH(TO_CHAR(i), 'MD5'))) || ' '
           || DECODE(MOD(TRUNC(i / 48), 5), 0, 'estuary', 1, 'saltmarsh', 2, 'mudflat', 3, 'shingle', 'reedbed')),
       'Synthetic subject ' || i,
       900000000 + 1 + MOD(TRUNC(i / 7), 5),
       900000000 + 1 + MOD(i, 20),
       900000000 + 1 + MOD(i, 20) + 20 * MOD(TRUNC(i / 20), 10),
       CASE WHEN MOD(i, 7) = 0 THEN 'HIGH' ELSE 'NORMAL' END,
       CASE WHEN MOD(i, 20) < 14 THEN 'OPEN' WHEN MOD(i, 20) < 16 THEN 'PENDING_CLOSURE'
            WHEN MOD(i, 20) < 19 THEN 'CLOSED' ELSE 'ARCHIVED' END,
       CASE WHEN MOD(i, 10) = 3 THEN 'RESTRICTED' ELSE 'STANDARD' END,
       DATE '2026-01-01' + MOD(i, 300),
       TIMESTAMP '2026-01-02 09:00:00 +00:00' + NUMTODSINTERVAL(MOD(i, 300), 'DAY')
           + NUMTODSINTERVAL(MOD(i, 600), 'MINUTE'),
       TIMESTAMP '2026-01-02 09:00:00 +00:00' + NUMTODSINTERVAL(MOD(i, 300), 'DAY'),
       900000000 + 1 + MOD(i, 20) + 20 * MOD(TRUNC(i / 20), 10),
       TIMESTAMP '2026-01-02 09:00:00 +00:00' + NUMTODSINTERVAL(MOD(i, 300), 'DAY'),
       900000000 + 1 + MOD(i, 20) + 20 * MOD(TRUNC(i / 20), 10)
  FROM (SELECT LEVEL AS i FROM DUAL CONNECT BY LEVEL <= 50000)
/

INSERT INTO CASE_SLA (CASE_ID, SLA_STATE, SLA_DUE_AT, AT_RISK_AT)
SELECT c.ID,
       CASE WHEN c.STATUS IN ('CLOSED', 'ARCHIVED') THEN (CASE WHEN MOD(c.ID, 4) = 0 THEN 'BREACHED' ELSE 'MET' END)
            WHEN MOD(c.ID, 9) = 0 THEN 'OVERDUE' WHEN MOD(c.ID, 5) = 0 THEN 'AT_RISK' ELSE 'ON_TRACK' END,
       FROM_TZ(CAST(c.IDENTIFIED_ON + 31 AS TIMESTAMP), 'UTC'),
       FROM_TZ(CAST(c.IDENTIFIED_ON + 25 AS TIMESTAMP), 'UTC')
  FROM CASE_RECORD c
 WHERE c.ID > 900000000
/

INSERT INTO CASE_SEARCH (CASE_ID, SEARCH_TEXT, UPDATED_AT)
SELECT c.ID, LOWER(TO_CLOB(c.TITLE) || ' ' || c.DESCRIPTION), c.UPDATED_AT
  FROM CASE_RECORD c
 WHERE c.ID > 900000000
/

BEGIN
  FOR t IN (SELECT 'APP_USER' AS N FROM DUAL UNION ALL SELECT 'APP_USER_TEAM' FROM DUAL UNION ALL SELECT 'TEAM' FROM DUAL
            UNION ALL SELECT 'CASE_TYPE' FROM DUAL UNION ALL SELECT 'CASE_RECORD' FROM DUAL
            UNION ALL SELECT 'CASE_SLA' FROM DUAL UNION ALL SELECT 'CASE_SEARCH' FROM DUAL) LOOP
    DBMS_STATS.GATHER_TABLE_STATS(USER, t.N, cascade => TRUE);
  END LOOP;
END;
/
