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
--   CASE_COMMENT          S I        -            insert-only; a correction is a new comment (V4.1)
--   CASE_COMMENT_PIN      S I   D    -            pins; unpinning deletes the row (V4.2)
--   CASE_ATTACHMENT       S I (U)    -            UPDATE of the four release columns only (V4.3)
--   CASE_ATTACHMENT_CHUNK S I        -            content is never changed (V4.3)
--   SPRING_SESSION        S I U D    -            Spring Session JDBC (V3.1)
--   SPRING_SESSION_ATTRIBUTES S I U D -
--   OIDC_SESSION          S I U D    -            back-channel logout registry (V3.1)
--   SAVED_VIEW            S I   D    -            a person's saved views; saved and deleted, never changed (V4.7)
--   NOTIFICATION_OUTBOX   S I U      S D          mail outbox; the purge user trims sent rows (V4.8)
--   USER_ALERT            S I U D    -            in-app alerts; marked read and deleted by their person (V4.9)
--   UPDATE_REQUEST        S I U      -            update requests; answered, never deleted (V4.10)
--   CLOSURE_REQUEST       S I U      -            closure requests; decided, never deleted (V5)
--   CASE_REOPEN           S I        -            reopens; history, never changed (V5.1)
--   ALERT_LEDGER          S I        -            alert idempotency; never changed, outlives purge (V6)
--   JOB_RUN               S I U      -            each job's last success, cluster truth (V6)
--   SHEDLOCK              S I U      -            ShedLock's lock rows; its JDBC provider never deletes (V6)
--   REPORT_SCHEDULE       S I U      -            report schedules; disabled, never deleted (V7)
--   REPORT_RUN            S I        -            rendered report runs; history, never changed (V7)
--   LEGAL_HOLD_EVENT      S I        -            holds placed and lifted; history, kept by purge (V8)
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
--   SEQ_CASE_COMMENT      SELECT     -
--   SEQ_CASE_ATTACHMENT   SELECT     -
--   SEQ_SAVED_VIEW        SELECT     -
--   SEQ_NOTIFICATION_OUTBOX SELECT   -
--   SEQ_USER_ALERT        SELECT     -
--   SEQ_UPDATE_REQUEST    SELECT     -
--   SEQ_CLOSURE_REQUEST   SELECT     -
--   SEQ_CASE_REOPEN       SELECT     -
--   SEQ_ALERT_LEDGER      SELECT     -
--   SEQ_REPORT_SCHEDULE   SELECT     -
--   SEQ_REPORT_RUN        SELECT     -
--   SEQ_LEGAL_HOLD_EVENT  SELECT     -
--
-- Later migrations add their rows to the cursor below. Fixed rules for them: the app user has no UPDATE or DELETE
-- on CASE_COMMENT, CASE_ATTACHMENT_CHUNK, AUDIT_EVENT and ALERT_LEDGER, and no DELETE on CASE_ATTACHMENT; it may DELETE only from CASE_COMMENT_PIN, SAVED_VIEW, USER_ALERT,
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
    UNION ALL SELECT 'CASE_COMMENT', 'SELECT,INSERT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_CASE_COMMENT', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'CASE_COMMENT_PIN', 'SELECT,INSERT,DELETE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'CASE_ATTACHMENT', 'SELECT,INSERT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'CASE_ATTACHMENT_CHUNK', 'SELECT,INSERT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_CASE_ATTACHMENT', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SPRING_SESSION', 'SELECT,INSERT,UPDATE,DELETE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SPRING_SESSION_ATTRIBUTES', 'SELECT,INSERT,UPDATE,DELETE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'OIDC_SESSION', 'SELECT,INSERT,UPDATE,DELETE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SAVED_VIEW', 'SELECT,INSERT,DELETE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_SAVED_VIEW', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'NOTIFICATION_OUTBOX', 'SELECT,INSERT,UPDATE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'NOTIFICATION_OUTBOX', 'SELECT,DELETE', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_NOTIFICATION_OUTBOX', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'USER_ALERT', 'SELECT,INSERT,UPDATE,DELETE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_USER_ALERT', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'UPDATE_REQUEST', 'SELECT,INSERT,UPDATE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_UPDATE_REQUEST', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'CLOSURE_REQUEST', 'SELECT,INSERT,UPDATE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_CLOSURE_REQUEST', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'CASE_REOPEN', 'SELECT,INSERT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_CASE_REOPEN', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'ALERT_LEDGER', 'SELECT,INSERT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_ALERT_LEDGER', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'JOB_RUN', 'SELECT,INSERT,UPDATE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SHEDLOCK', 'SELECT,INSERT,UPDATE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'REPORT_SCHEDULE', 'SELECT,INSERT,UPDATE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_REPORT_SCHEDULE', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'REPORT_RUN', 'SELECT,INSERT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_REPORT_RUN', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'LEGAL_HOLD_EVENT', 'SELECT,INSERT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_LEGAL_HOLD_EVENT', 'SELECT', UPPER('${app_user}') FROM DUAL;
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

  -- Release (WP-14) is the only change an attachment row sees. Column privileges live in USER_COL_PRIVS, not in
  -- USER_TAB_PRIVS, so the loop above neither sees nor revokes this grant; it is granted last regardless.
  EXECUTE IMMEDIATE 'GRANT UPDATE (SCAN_STATUS, RELEASED_BY, RELEASED_AT, RELEASED_REASON) ON CASE_ATTACHMENT TO '
    || UPPER('${app_user}');
END;
/
