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
--   ${import_user} the importer Job (docs/design/importer.md §5); owns staging, and may read every mapped table
--
-- Matrix (S = SELECT, I = INSERT, U = UPDATE, D = DELETE):
--
--   object                app_user   purge_user
--   APP_USER              S I U      -            users are disabled, never deleted
--   TEAM                  S I U      -            teams are deactivated, never deleted
--   APP_USER_TEAM         S I U D    -            membership and role changes
--   APP_USER_GLOBAL_ROLE  S I   D    -            re-synced from identity-provider claims at login
--   AUDIT_EVENT           S I        I            append-only; outlives purge, which audits each tombstone (V2)
--   CASE_RECORD           S I U      S (U)        never deleted; purge tombstones it: UPDATE of 4 columns (V4)
--   CASE_SLA              S I U      -            job-owned side table (V4)
--   CASE_SEARCH           S I U      S D          search text side table; purged (V4)
--   CASE_COMMENT          S I        S D          insert-only; a correction is a new comment; purged (V4.1)
--   CASE_COMMENT_PIN      S I   D    S D          pins; unpinning deletes the row; purged (V4.2)
--   CASE_ATTACHMENT       S I (U)    S D          UPDATE of the four release columns only; purged (V4.3)
--   CASE_ATTACHMENT_CHUNK S I        S D          content is never changed; purged (V4.3)
--   SPRING_SESSION        S I U D    -            Spring Session JDBC (V3.1)
--   SPRING_SESSION_ATTRIBUTES S I U D -
--   OIDC_SESSION          S I U D    -            back-channel logout registry (V3.1)
--   SAVED_VIEW            S I   D    -            a person's saved views; saved and deleted, never changed (V4.7)
--   NOTIFICATION_OUTBOX   S I U      S D          mail outbox; the purge user trims sent rows (V4.8)
--   USER_ALERT            S I U D    -            in-app alerts; marked read and deleted by their person (V4.9)
--   UPDATE_REQUEST        S I U      S D          update requests; answered; purged (V4.10)
--   CLOSURE_REQUEST       S I U      S D          closure requests; decided; purged (V5)
--   CASE_REOPEN           S I        S D          reopens; history, never changed; purged (V5.1)
--   ALERT_LEDGER          S I        -            alert idempotency; never changed, outlives purge (V6)
--   JOB_RUN               S I U      S I U        each job's last success, cluster truth; purge's too (V6)
--   SHEDLOCK              S I U      -            ShedLock's lock rows; its JDBC provider never deletes (V6)
--   REPORT_SCHEDULE       S I U      -            report schedules; disabled, never deleted (V7)
--   REPORT_RUN            S I        S D          report runs; never changed; purged after report-runs (V7)
--   LEGAL_HOLD_EVENT      S I        -            holds placed and lifted; history, kept by purge (V8)
--   USER_PREFERENCE       S I U      -            a person's preferences; changed, never deleted (V9)
--   SEQ_APP_USER          SELECT     -            Oracle's sequence privilege is SELECT (NEXTVAL)
--   SEQ_TEAM              SELECT     -
--   SEQ_AUDIT_EVENT       SELECT     SELECT       the purge's CASE_PURGED rows
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
--   IMPORT_RUN            S I (U)    -            run requests; UPDATE of the sign-off columns only (V10)
--   IMPORT_ISSUE          S          -            codes, numbers and keyed hashes, for the screen (V10)
--   IMPORT_RUN_SUMMARY    S          -            the sign-off screen's aggregates (V10)
--   IMPORT_STG_*, IMPORT_RUN_CHECKPOINT -  -       staging: legacy values; the import user's alone (V13)
--   SEQ_IMPORT_RUN        SELECT     -
--   SEQ_IMPORT_ISSUE      SELECT     -            needed by Hibernate validate; NEXTVAL comes with it on Oracle
--   SEQ_IMPORT_RUN_SUMMARY SELECT    -
--   CASE_IMPORTED_EVENT   S          S D          legacy history, for the History tab; case content, purged (V16)
--   SEQ_CASE_IMPORTED_EVENT SELECT   -
--
-- The import user: S I D on the staging tables (IMPORT_STG_PERSON, _CASE, _COMMENT, _ATTACHMENT, _EVENT and
-- IMPORT_RUN_CHECKPOINT; V13), which no other user may even read, and on IMPORT_ISSUE and IMPORT_RUN_SUMMARY; S I on
-- IMPORT_RUN with UPDATE of the status, hash, scanning-mode, count and staging-expiry columns, never the sign-off;
-- S I on CASE_IMPORTED_EVENT, insert-only, and SELECT (NEXTVAL) on SEQ_CASE_IMPORTED_EVENT (V16). On every other
-- table and sequence an entity maps it has SELECT only, because the dictionary views Hibernate validate reads
-- (ALL_TABLES, ALL_SEQUENCES) list only objects a user holds a privilege on (design §2.1 R3); on a sequence that
-- SELECT is also NEXTVAL. The import writes to domain tables come with the importer PRs that use them (§12).
--
-- Later migrations add their rows to the cursor below. Fixed rules for them: the app user has no UPDATE or DELETE
-- on CASE_COMMENT, CASE_ATTACHMENT_CHUNK, AUDIT_EVENT, ALERT_LEDGER and CASE_IMPORTED_EVENT, and no DELETE on CASE_ATTACHMENT; it may DELETE only from CASE_COMMENT_PIN, SAVED_VIEW, USER_ALERT,
-- SPRING_SESSION*, OIDC_SESSION and SHEDLOCK, besides the membership tables above. Importer staging belongs to the
-- import user, never to the app user; the import user has no UPDATE or DELETE on those five insert-only tables either.
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
    UNION ALL SELECT 'SEQ_LEGAL_HOLD_EVENT', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'USER_PREFERENCE', 'SELECT,INSERT,UPDATE', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'CASE_COMMENT_PIN', 'SELECT,DELETE', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'UPDATE_REQUEST', 'SELECT,DELETE', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'CASE_COMMENT', 'SELECT,DELETE', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'CASE_ATTACHMENT_CHUNK', 'SELECT,DELETE', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'CASE_ATTACHMENT', 'SELECT,DELETE', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'CASE_SEARCH', 'SELECT,DELETE', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'CASE_REOPEN', 'SELECT,DELETE', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'CLOSURE_REQUEST', 'SELECT,DELETE', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'REPORT_RUN', 'SELECT,DELETE', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'CASE_RECORD', 'SELECT', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'AUDIT_EVENT', 'INSERT', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_AUDIT_EVENT', 'SELECT', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'JOB_RUN', 'SELECT,INSERT,UPDATE', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'IMPORT_RUN', 'SELECT,INSERT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'IMPORT_ISSUE', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'IMPORT_RUN_SUMMARY', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_IMPORT_RUN', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_IMPORT_ISSUE', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_IMPORT_RUN_SUMMARY', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'IMPORT_RUN', 'SELECT,INSERT', UPPER('${import_user}') FROM DUAL
    UNION ALL SELECT 'IMPORT_ISSUE', 'SELECT,INSERT,DELETE', UPPER('${import_user}') FROM DUAL
    UNION ALL SELECT 'IMPORT_RUN_SUMMARY', 'SELECT,INSERT,DELETE', UPPER('${import_user}') FROM DUAL
    UNION ALL SELECT COLUMN_VALUE, 'SELECT,INSERT,DELETE', UPPER('${import_user}') FROM TABLE(SYS.ODCIVARCHAR2LIST(
      'IMPORT_STG_PERSON', 'IMPORT_STG_CASE', 'IMPORT_STG_COMMENT', 'IMPORT_STG_ATTACHMENT', 'IMPORT_STG_EVENT',
      'IMPORT_RUN_CHECKPOINT'))
    -- Imported legacy history (V16): the History tab reads it; only the importer writes it, and only purge deletes it.
    UNION ALL SELECT 'CASE_IMPORTED_EVENT', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_CASE_IMPORTED_EVENT', 'SELECT', UPPER('${app_user}') FROM DUAL
    UNION ALL SELECT 'CASE_IMPORTED_EVENT', 'SELECT,DELETE', UPPER('${purge_user}') FROM DUAL
    UNION ALL SELECT 'CASE_IMPORTED_EVENT', 'SELECT,INSERT', UPPER('${import_user}') FROM DUAL
    UNION ALL SELECT 'SEQ_CASE_IMPORTED_EVENT', 'SELECT', UPPER('${import_user}') FROM DUAL
    -- Every other table and sequence an entity maps: SELECT only, for validate (design §2.1 R3).
    UNION ALL SELECT COLUMN_VALUE, 'SELECT', UPPER('${import_user}') FROM TABLE(SYS.ODCIVARCHAR2LIST(
      'APP_USER', 'TEAM', 'APP_USER_TEAM', 'APP_USER_GLOBAL_ROLE', 'AUDIT_EVENT', 'SLA_POLICY', 'CASE_TYPE',
      'CASE_TYPE_OUTCOME', 'CASE_RECORD', 'CASE_SLA', 'CASE_SEARCH', 'CASE_COMMENT', 'CASE_COMMENT_PIN',
      'CASE_ATTACHMENT', 'SAVED_VIEW', 'NOTIFICATION_OUTBOX', 'USER_ALERT', 'UPDATE_REQUEST', 'CLOSURE_REQUEST',
      'CASE_REOPEN', 'ALERT_LEDGER', 'JOB_RUN', 'REPORT_SCHEDULE', 'REPORT_RUN', 'LEGAL_HOLD_EVENT', 'USER_PREFERENCE',
      'SEQ_APP_USER', 'SEQ_TEAM', 'SEQ_AUDIT_EVENT', 'SEQ_SLA_POLICY', 'SEQ_CASE_TYPE', 'SEQ_CASE_RECORD',
      'SEQ_CASE_COMMENT', 'SEQ_CASE_ATTACHMENT', 'SEQ_SAVED_VIEW', 'SEQ_NOTIFICATION_OUTBOX', 'SEQ_USER_ALERT',
      'SEQ_UPDATE_REQUEST', 'SEQ_CLOSURE_REQUEST', 'SEQ_CASE_REOPEN', 'SEQ_ALERT_LEDGER', 'SEQ_REPORT_SCHEDULE',
      'SEQ_REPORT_RUN', 'SEQ_LEGAL_HOLD_EVENT', 'SEQ_IMPORT_RUN', 'SEQ_IMPORT_ISSUE', 'SEQ_IMPORT_RUN_SUMMARY'));
  keep PLS_INTEGER;
BEGIN
  FOR w IN wanted LOOP
    EXECUTE IMMEDIATE 'GRANT ' || w.PRIVS || ' ON ' || w.OBJ || ' TO ' || w.GRANTEE;
  END LOOP;

  FOR held IN (
    SELECT TABLE_NAME, PRIVILEGE, GRANTEE
      FROM USER_TAB_PRIVS
     WHERE OWNER = USER
       AND GRANTEE IN (UPPER('${app_user}'), UPPER('${purge_user}'), UPPER('${import_user}'))
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
  -- The retention purge (WP-24 part c) turns a case row into a tombstone and changes nothing else on it.
  EXECUTE IMMEDIATE 'GRANT UPDATE (DESCRIPTION, SUBJECT_TEXT, PURGED_AT, VERSION) ON CASE_RECORD TO '
    || UPPER('${purge_user}');
  -- An import run: the screen writes its sign-off, the importer Job everything else (V10).
  EXECUTE IMMEDIATE 'GRANT UPDATE (APPROVED_BY, APPROVED_AT, ACKNOWLEDGED_WARNINGS, UPDATED_AT, VERSION) ON IMPORT_RUN TO '
    || UPPER('${app_user}');
  EXECUTE IMMEDIATE 'GRANT UPDATE (STATUS, MAPPING_HASH, SOURCE_FINGERPRINT, SCAN_MODE, ROWS_STAGED, ROWS_LOADED, '
    || 'STAGING_EXPIRES_AT, STAGING_PURGED_AT, UPDATED_AT, VERSION) ON IMPORT_RUN TO ' || UPPER('${import_user}');
END;
/
