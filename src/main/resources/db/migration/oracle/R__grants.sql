-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- Runtime grants, Oracle (BUILD_PLAN section 8 rule 7, IMPLEMENTATION_PLAN A.2 #20).
--
-- Runs as the schema owner after every versioned migration whose checksum or placeholders change. Oracle commits
-- each GRANT and REVOKE on its own, so instead of "revoke all, grant again" (which would leave running replicas
-- without privileges for a moment) the block first grants the matrix (granting a held privilege is a no-op) and
-- then revokes only what the matrix no longer lists.
--
--   ${app_user}    the runtime user of every replica
--   ${purge_user}  the retention CronJob; owns every deletion of case data
--
-- Matrix (S = SELECT, I = INSERT, U = UPDATE, D = DELETE):
--
--   object                app_user   purge_user
--   APP_USER              S I U      -            users are disabled, never deleted
--   TEAM                  S I U      -            teams are deactivated, never deleted
--   APP_USER_TEAM         S I U D    -            membership and role changes
--   APP_USER_GLOBAL_ROLE  S I   D    -            re-synced from identity-provider claims at login
--   AUDIT_EVENT           S I        -            append-only; audit outlives purge (V2)
--   CASE_RECORD           S I U      -            never deleted by the app; purge grants come with WP-24 (V4)
--   CASE_SLA              S I U      -            job-owned side table (V4)
--   CASE_SEARCH           S I U      -            search text side table (V4)
--   SPRING_SESSION        S I U D    -            Spring Session JDBC (V3.1)
--   SPRING_SESSION_ATTRIBUTES S I U D -
--   OIDC_SESSION          S I U D    -            back-channel logout registry (V3.1)
--   SEQ_APP_USER          SELECT     -            Oracle's sequence privilege is SELECT (NEXTVAL)
--   SEQ_TEAM              SELECT     -
--   SEQ_AUDIT_EVENT       SELECT     -
--   SLA_POLICY            S I U      -            edited, never deleted (V3)
--   CASE_TYPE             S I U      -            deactivated, never deleted (V3)
--   CASE_TYPE_OUTCOME     S I U D    -            configuration; an outcome list is replaced as a whole (V3)
--   SEQ_SLA_POLICY        SELECT     -
--   SEQ_CASE_TYPE         SELECT     -
--   SEQ_CASE_RECORD       SELECT     -
--   SEQ_CASE_NUMBER       SELECT     -            case numbers, one at a time
--
-- Later migrations add their rows to the cursor below. Fixed rules for them: the app user has no UPDATE or DELETE
-- on CASE_COMMENT, AUDIT_EVENT and ALERT_LEDGER; it may DELETE only from CASE_COMMENT_PIN, SAVED_VIEW, USER_ALERT,
-- SPRING_SESSION*, OIDC_SESSION, SHEDLOCK and importer staging tables, besides the membership tables above.
-- DatabaseGrantsIT asserts this matrix by executing each statement as each user.
--
-- Object names below are literals from this file only; the grantees come from deployment configuration.

DECLARE
  CURSOR wanted IS
    SELECT 'APP_USER' AS OBJ, 'SELECT,INSERT,UPDATE' AS PRIVS, UPPER('${app_user}') AS GRANTEE FROM DUAL
    UNION ALL SELECT 'TEAM', 'SELECT,INSERT,UPDATE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'APP_USER_TEAM', 'SELECT,INSERT,UPDATE,DELETE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'APP_USER_GLOBAL_ROLE', 'SELECT,INSERT,DELETE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_APP_USER', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'AUDIT_EVENT', 'SELECT,INSERT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_TEAM', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_AUDIT_EVENT', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SLA_POLICY', 'SELECT,INSERT,UPDATE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'CASE_TYPE', 'SELECT,INSERT,UPDATE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'CASE_TYPE_OUTCOME', 'SELECT,INSERT,UPDATE,DELETE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_SLA_POLICY', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_CASE_TYPE', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'CASE_RECORD', 'SELECT,INSERT,UPDATE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'CASE_SLA', 'SELECT,INSERT,UPDATE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'CASE_SEARCH', 'SELECT,INSERT,UPDATE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_CASE_RECORD', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_CASE_NUMBER', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SPRING_SESSION', 'SELECT,INSERT,UPDATE,DELETE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SPRING_SESSION_ATTRIBUTES', 'SELECT,INSERT,UPDATE,DELETE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'OIDC_SESSION', 'SELECT,INSERT,UPDATE,DELETE', UPPER('${app_user}') FROM DUAL;
  keep PLS_INTEGER;
BEGIN
  FOR w IN wanted LOOP
    EXECUTE IMMEDIATE 'GRANT ' || w.PRIVS || ' ON ' || w.OBJ || ' TO ' || w.GRANTEE;
  END LOOP;

  FOR held IN (
    SELECT TABLE_NAME, PRIVILEGE, GRANTEE
      FROM USER_TAB_PRIVS
     WHERE OWNER = USER
       AND GRANTEE IN (UPPER('${app_user}'), UPPER('${purge_user}'))
  ) LOOP
    keep := 0;
    FOR w IN wanted LOOP
      IF w.OBJ = held.TABLE_NAME AND w.GRANTEE = held.GRANTEE
         AND INSTR(',' || w.PRIVS || ',', ',' || held.PRIVILEGE || ',') > 0 THEN
        keep := 1;
      END IF;
    END LOOP;
    IF keep = 0 THEN
      EXECUTE IMMEDIATE 'REVOKE ' || held.PRIVILEGE || ' ON ' || held.TABLE_NAME || ' FROM ' || held.GRANTEE;
    END IF;
  END LOOP;
END;
/
