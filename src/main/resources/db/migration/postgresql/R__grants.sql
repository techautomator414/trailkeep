-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- Runtime grants, PostgreSQL (BUILD_PLAN section 8 rule 7, IMPLEMENTATION_PLAN A.2 #20).
--
-- Runs as the schema owner after every versioned migration whose checksum or placeholders change. The file is
-- declarative: it revokes everything it manages and grants the matrix again. PostgreSQL runs the whole script in
-- one transaction, so a running replica never sees the privileges disappear.
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
--   SLA_POLICY            S I U      -            edited, never deleted (V3)
--   CASE_TYPE             S I U      -            deactivated, never deleted (V3)
--   CASE_TYPE_OUTCOME     S I U D    -            configuration; an outcome list is replaced as a whole (V3)
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
--   IMPORT_RUN            S I (U)    -            run requests; UPDATE of the sign-off columns only (V10)
--   IMPORT_ISSUE          S          -            codes, numbers and keyed hashes, for the screen (V10)
--   IMPORT_RUN_SUMMARY    S          -            the sign-off screen's aggregates (V10)
--   IMPORT_STG_*, IMPORT_RUN_CHECKPOINT -  -       staging: legacy values; the import user's alone (V13)
--   CASE_IMPORTED_EVENT   S          S D          legacy history, for the History tab; case content, purged (V16)
--   IMPORT_LEDGER         S          -            what each load wrote; insert-only, by the import user (V17)
--   IMPORT_OWNER_PENDING  S          -            open cases awaiting their legacy owner, for counts (V18)
--   IMPORT_LEDGER_PART    S          -            the comments a ledger row wrote besides its target (V19)
--   SEQ_APP_USER         USAGE      -
--   SEQ_TEAM              USAGE      -
--   SEQ_AUDIT_EVENT       USAGE      USAGE        the purge's CASE_PURGED rows
--   SEQ_SLA_POLICY        USAGE      -
--   SEQ_CASE_TYPE         USAGE      -
--   SEQ_CASE_RECORD       USAGE      -
--   SEQ_CASE_NUMBER       USAGE      -            case numbers, one at a time
--   SEQ_CASE_COMMENT      USAGE      -
--   SEQ_CASE_ATTACHMENT   USAGE      -
--   SEQ_SAVED_VIEW        USAGE      -
--   SEQ_NOTIFICATION_OUTBOX USAGE    -
--   SEQ_USER_ALERT        USAGE      -
--   SEQ_UPDATE_REQUEST    USAGE      -
--   SEQ_CLOSURE_REQUEST   USAGE      -
--   SEQ_CASE_REOPEN       USAGE      -
--   SEQ_ALERT_LEDGER      USAGE      -
--   SEQ_REPORT_SCHEDULE   USAGE      -
--   SEQ_REPORT_RUN        USAGE      -
--   SEQ_LEGAL_HOLD_EVENT  USAGE      -
--   SEQ_IMPORT_RUN        USAGE      -
--   SEQ_IMPORT_ISSUE      SELECT     -            no nextval: SELECT only lets Hibernate validate see it
--   SEQ_IMPORT_RUN_SUMMARY SELECT    -
--   SEQ_CASE_IMPORTED_EVENT SELECT   -
--   SEQ_IMPORT_LEDGER     SELECT     -
--   SEQ_IMPORT_OWNER_PENDING SELECT  -
--
-- The import user: S I D on the staging tables (IMPORT_STG_PERSON, _CASE, _COMMENT, _ATTACHMENT, _EVENT and
-- IMPORT_RUN_CHECKPOINT; V13), which no other user may even read, and on IMPORT_ISSUE and IMPORT_RUN_SUMMARY; S I on
-- IMPORT_RUN with UPDATE of the status, hash, scanning-mode, count and staging-expiry columns, never the sign-off;
-- S I on CASE_IMPORTED_EVENT, insert-only (V16), on IMPORT_LEDGER, insert-only (V17), with its written hashes
-- (V19), and on IMPORT_LEDGER_PART, insert-only (V19); USAGE on SEQ_IMPORT_* and
-- SEQ_CASE_IMPORTED_EVENT. The load (WP-26 part t) writes cases with their closures: S I on CASE_RECORD, CASE_SLA,
-- CASE_SEARCH, CLOSURE_REQUEST and AUDIT_EVENT, USAGE on their sequences and SEQ_CASE_NUMBER; with their comments
-- (part u) S I on CASE_COMMENT, USAGE on SEQ_CASE_COMMENT, and UPDATE of CASE_SEARCH's SEARCH_TEXT and UPDATED_AT
-- alone, which appending a comment to the search text needs (CaseSearchJdbcDao); with their attachments (part u) S I
-- on CASE_ATTACHMENT and CASE_ATTACHMENT_CHUNK, insert-only, and USAGE on SEQ_CASE_ATTACHMENT; S I D on
-- IMPORT_OWNER_PENDING, which the load writes and relink-owners deletes, and USAGE on its sequence (V18); for
-- relink-owners (part z2) UPDATE of CASE_RECORD's OWNER_ID, UPDATED_AT, UPDATED_BY and VERSION alone; for import
-- holds (part ad) S I on LEGAL_HOLD_EVENT, insert-only, and USAGE on SEQ_LEGAL_HOLD_EVENT (the hold itself is in the
-- case's INSERT, so no UPDATE of CASE_RECORD's hold columns). On every
-- other table and sequence an entity maps it has SELECT only, so its Hibernate validate sees the whole model (on
-- Oracle the dictionary lists only objects a user holds a privilege on, design §2.1 R3). The import writes to domain
-- tables come with the importer PRs that use them (design §12).
--
-- Later migrations add their tables here. Fixed rules for them: the app user has no UPDATE or DELETE on
-- CASE_COMMENT, CASE_ATTACHMENT_CHUNK, AUDIT_EVENT, ALERT_LEDGER and CASE_IMPORTED_EVENT, and no DELETE on CASE_ATTACHMENT; it may DELETE only from CASE_COMMENT_PIN, SAVED_VIEW, USER_ALERT,
-- SPRING_SESSION*, OIDC_SESSION and SHEDLOCK, besides the membership tables and CASE_TYPE_OUTCOME (configuration, not
-- case data) above. Importer staging belongs to the import user, never to the app user; the import user has no
-- UPDATE or DELETE on those five insert-only tables either.
-- DatabaseGrantsIT asserts this matrix by executing each statement as each user.

GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${app_user};
GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${purge_user};
GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${import_user};

REVOKE ALL ON TABLE APP_USER, TEAM, APP_USER_TEAM, APP_USER_GLOBAL_ROLE, AUDIT_EVENT,
  SLA_POLICY, CASE_TYPE, CASE_TYPE_OUTCOME, SPRING_SESSION, SPRING_SESSION_ATTRIBUTES, OIDC_SESSION,
  CASE_RECORD, CASE_SLA, CASE_SEARCH, CASE_COMMENT, CASE_COMMENT_PIN, CASE_ATTACHMENT, CASE_ATTACHMENT_CHUNK, SAVED_VIEW, NOTIFICATION_OUTBOX, USER_ALERT, UPDATE_REQUEST, CLOSURE_REQUEST, CASE_REOPEN,
  ALERT_LEDGER, JOB_RUN, SHEDLOCK, REPORT_SCHEDULE, REPORT_RUN, LEGAL_HOLD_EVENT, USER_PREFERENCE, IMPORT_RUN,
  IMPORT_ISSUE, IMPORT_RUN_SUMMARY, IMPORT_STG_PERSON, IMPORT_STG_CASE, IMPORT_STG_COMMENT, IMPORT_STG_ATTACHMENT,
  IMPORT_STG_EVENT, IMPORT_RUN_CHECKPOINT, CASE_IMPORTED_EVENT, IMPORT_LEDGER, IMPORT_OWNER_PENDING,
  IMPORT_LEDGER_PART FROM ${app_user},
  ${purge_user}, ${import_user};
REVOKE ALL ON SEQUENCE SEQ_APP_USER, SEQ_TEAM, SEQ_AUDIT_EVENT, SEQ_SLA_POLICY, SEQ_CASE_TYPE, SEQ_CASE_RECORD, SEQ_CASE_NUMBER,
  SEQ_CASE_COMMENT, SEQ_CASE_ATTACHMENT, SEQ_SAVED_VIEW, SEQ_NOTIFICATION_OUTBOX, SEQ_USER_ALERT, SEQ_UPDATE_REQUEST, SEQ_CLOSURE_REQUEST, SEQ_CASE_REOPEN, SEQ_ALERT_LEDGER,
  SEQ_REPORT_SCHEDULE, SEQ_REPORT_RUN, SEQ_LEGAL_HOLD_EVENT, SEQ_IMPORT_RUN, SEQ_IMPORT_ISSUE, SEQ_IMPORT_RUN_SUMMARY,
  SEQ_CASE_IMPORTED_EVENT, SEQ_IMPORT_LEDGER, SEQ_IMPORT_OWNER_PENDING FROM ${app_user}, ${purge_user}, ${import_user};

GRANT SELECT, INSERT, UPDATE         ON TABLE APP_USER             TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE TEAM                 TO ${app_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE APP_USER_TEAM        TO ${app_user};
GRANT SELECT, INSERT, DELETE         ON TABLE APP_USER_GLOBAL_ROLE TO ${app_user};
GRANT SELECT, INSERT                 ON TABLE AUDIT_EVENT          TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE SLA_POLICY           TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE CASE_TYPE            TO ${app_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE CASE_TYPE_OUTCOME    TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE CASE_RECORD          TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE CASE_SLA             TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE CASE_SEARCH          TO ${app_user};
GRANT SELECT, INSERT                 ON TABLE CASE_COMMENT         TO ${app_user};
GRANT SELECT, INSERT, DELETE         ON TABLE CASE_COMMENT_PIN     TO ${app_user};
GRANT SELECT, INSERT                 ON TABLE CASE_ATTACHMENT      TO ${app_user};
-- Release (WP-14) is the only change an attachment row sees. A column grant: REVOKE ALL above removes it as well.
GRANT UPDATE (SCAN_STATUS, RELEASED_BY, RELEASED_AT, RELEASED_REASON) ON TABLE CASE_ATTACHMENT TO ${app_user};
GRANT SELECT, INSERT                 ON TABLE CASE_ATTACHMENT_CHUNK TO ${app_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE SPRING_SESSION, SPRING_SESSION_ATTRIBUTES, OIDC_SESSION TO ${app_user};
GRANT SELECT, INSERT, DELETE         ON TABLE SAVED_VIEW           TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE NOTIFICATION_OUTBOX  TO ${app_user};
-- Trimming sent mail is the purge job's; DELETE ... WHERE needs SELECT on the columns it filters by.
GRANT SELECT, DELETE                 ON TABLE NOTIFICATION_OUTBOX  TO ${purge_user};
-- The retention purge (WP-24 part c, io.trailkeep.retention.PurgeJdbcDao): it deletes a purged case's content and old
-- report runs, and turns the case row into a tombstone, changing only those four columns. SELECT FOR UPDATE needs
-- UPDATE on a column, which the column grant gives.
GRANT SELECT, DELETE ON TABLE CASE_COMMENT_PIN, UPDATE_REQUEST, CASE_COMMENT, CASE_ATTACHMENT_CHUNK, CASE_ATTACHMENT,
  CASE_SEARCH, CASE_REOPEN, CLOSURE_REQUEST, REPORT_RUN TO ${purge_user};
GRANT SELECT ON TABLE CASE_RECORD TO ${purge_user};
GRANT UPDATE (DESCRIPTION, SUBJECT_TEXT, PURGED_AT, VERSION) ON TABLE CASE_RECORD TO ${purge_user};
-- It audits each tombstone (CASE_PURGED, ids from SEQ_AUDIT_EVENT) and records its run in JOB_RUN.
GRANT INSERT                         ON TABLE AUDIT_EVENT          TO ${purge_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE JOB_RUN              TO ${purge_user};
GRANT USAGE ON SEQUENCE SEQ_AUDIT_EVENT TO ${purge_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE USER_ALERT           TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE UPDATE_REQUEST       TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE CLOSURE_REQUEST      TO ${app_user};
GRANT SELECT, INSERT                 ON TABLE CASE_REOPEN          TO ${app_user};
GRANT SELECT, INSERT                 ON TABLE ALERT_LEDGER         TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE JOB_RUN              TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE SHEDLOCK             TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE REPORT_SCHEDULE      TO ${app_user};
GRANT SELECT, INSERT                 ON TABLE REPORT_RUN           TO ${app_user};
GRANT SELECT, INSERT                 ON TABLE LEGAL_HOLD_EVENT     TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE USER_PREFERENCE      TO ${app_user};
-- The Import runs screen requests runs and signs them off; the importer Job writes everything else (V10).
GRANT SELECT, INSERT                 ON TABLE IMPORT_RUN           TO ${app_user};
GRANT UPDATE (APPROVED_BY, APPROVED_AT, ACKNOWLEDGED_WARNINGS, UPDATED_AT, VERSION) ON TABLE IMPORT_RUN TO ${app_user};
GRANT SELECT                         ON TABLE IMPORT_ISSUE, IMPORT_RUN_SUMMARY TO ${app_user};
GRANT SELECT ON SEQUENCE SEQ_IMPORT_ISSUE, SEQ_IMPORT_RUN_SUMMARY TO ${app_user};
-- Imported legacy history (V16): the History tab reads it; only the importer writes it, and only purge deletes it.
GRANT SELECT                         ON TABLE CASE_IMPORTED_EVENT  TO ${app_user};
GRANT SELECT ON SEQUENCE SEQ_CASE_IMPORTED_EVENT TO ${app_user};
GRANT SELECT, DELETE                 ON TABLE CASE_IMPORTED_EVENT  TO ${purge_user};
-- The import ledger (V17): the screen may read what a load wrote; only the importer writes it, and nobody changes it.
GRANT SELECT                         ON TABLE IMPORT_LEDGER        TO ${app_user};
GRANT SELECT ON SEQUENCE SEQ_IMPORT_LEDGER TO ${app_user};
GRANT SELECT                         ON TABLE IMPORT_LEDGER_PART   TO ${app_user};
-- Pending owners (V18): the screen may count them; only the importer writes them, and relink-owners deletes them.
GRANT SELECT                         ON TABLE IMPORT_OWNER_PENDING TO ${app_user};
GRANT SELECT ON SEQUENCE SEQ_IMPORT_OWNER_PENDING TO ${app_user};

-- The importer Job (design §5): its own tables, and SELECT on every table and sequence an entity maps, for validate.
GRANT SELECT ON TABLE APP_USER, TEAM, APP_USER_TEAM, APP_USER_GLOBAL_ROLE, SLA_POLICY, CASE_TYPE, CASE_TYPE_OUTCOME,
  CASE_COMMENT_PIN, SAVED_VIEW, NOTIFICATION_OUTBOX, USER_ALERT, UPDATE_REQUEST,
  CASE_REOPEN, ALERT_LEDGER, JOB_RUN, REPORT_SCHEDULE, REPORT_RUN, USER_PREFERENCE TO ${import_user};
-- The load (WP-26 part t): a case with its SLA row, search row and closure, audited, in one transaction.
GRANT SELECT, INSERT ON TABLE CASE_RECORD, CASE_SLA, CASE_SEARCH, CLOSURE_REQUEST, AUDIT_EVENT TO ${import_user};
GRANT USAGE ON SEQUENCE SEQ_CASE_RECORD, SEQ_CASE_NUMBER, SEQ_CLOSURE_REQUEST, SEQ_AUDIT_EVENT TO ${import_user};
-- Its comments (part u): insert-only, each appended to the case's search text in one UPDATE of these two columns.
GRANT SELECT, INSERT                 ON TABLE CASE_COMMENT         TO ${import_user};
GRANT USAGE ON SEQUENCE SEQ_CASE_COMMENT TO ${import_user};
GRANT UPDATE (SEARCH_TEXT, UPDATED_AT) ON TABLE CASE_SEARCH TO ${import_user};
-- Its attachments (part u): each row and its chunks, insert-only, stored in the case's transaction after the scan.
GRANT SELECT, INSERT ON TABLE CASE_ATTACHMENT, CASE_ATTACHMENT_CHUNK TO ${import_user};
GRANT USAGE ON SEQUENCE SEQ_CASE_ATTACHMENT TO ${import_user};
GRANT SELECT, INSERT                 ON TABLE IMPORT_LEDGER        TO ${import_user};
GRANT SELECT, INSERT                 ON TABLE IMPORT_LEDGER_PART   TO ${import_user};
GRANT SELECT, INSERT, DELETE         ON TABLE IMPORT_OWNER_PENDING TO ${import_user};
-- relink-owners (part z2) hands a case to its legacy owner: the owner and the change stamp of a case, nothing else.
GRANT UPDATE (OWNER_ID, UPDATED_AT, UPDATED_BY, VERSION) ON TABLE CASE_RECORD TO ${import_user};
GRANT SELECT, INSERT                 ON TABLE IMPORT_RUN           TO ${import_user};
GRANT UPDATE (STATUS, MAPPING_HASH, SOURCE_FINGERPRINT, SCAN_MODE, ROWS_STAGED, ROWS_LOADED, STAGING_EXPIRES_AT,
  STAGING_PURGED_AT, UPDATED_AT, VERSION) ON TABLE IMPORT_RUN TO ${import_user};
GRANT SELECT, INSERT, DELETE         ON TABLE IMPORT_ISSUE, IMPORT_RUN_SUMMARY TO ${import_user};
-- Staging (V13): written by the extract, read by the dry run and the load, deleted by purge-staging and the TTL.
GRANT SELECT, INSERT, DELETE ON TABLE IMPORT_STG_PERSON, IMPORT_STG_CASE, IMPORT_STG_COMMENT, IMPORT_STG_ATTACHMENT,
  IMPORT_STG_EVENT, IMPORT_RUN_CHECKPOINT TO ${import_user};
GRANT USAGE ON SEQUENCE SEQ_IMPORT_RUN, SEQ_IMPORT_ISSUE, SEQ_IMPORT_RUN_SUMMARY, SEQ_IMPORT_LEDGER,
  SEQ_IMPORT_OWNER_PENDING TO ${import_user};
GRANT SELECT, INSERT                 ON TABLE CASE_IMPORTED_EVENT  TO ${import_user};
GRANT USAGE ON SEQUENCE SEQ_CASE_IMPORTED_EVENT TO ${import_user};
-- Import holds (part ad): a held imported case's hold history row, insert-only; the hold is in the case's INSERT.
GRANT SELECT, INSERT                 ON TABLE LEGAL_HOLD_EVENT     TO ${import_user};
GRANT USAGE ON SEQUENCE SEQ_LEGAL_HOLD_EVENT TO ${import_user};
GRANT SELECT ON SEQUENCE SEQ_APP_USER, SEQ_TEAM, SEQ_SLA_POLICY, SEQ_CASE_TYPE,
  SEQ_SAVED_VIEW, SEQ_NOTIFICATION_OUTBOX, SEQ_USER_ALERT, SEQ_UPDATE_REQUEST, SEQ_CASE_REOPEN, SEQ_ALERT_LEDGER,
  SEQ_REPORT_SCHEDULE, SEQ_REPORT_RUN TO ${import_user};

-- USAGE allows nextval(), which is all Hibernate's pooled optimizer calls.
GRANT USAGE ON SEQUENCE SEQ_APP_USER, SEQ_TEAM, SEQ_AUDIT_EVENT, SEQ_SLA_POLICY, SEQ_CASE_TYPE, SEQ_CASE_RECORD, SEQ_CASE_NUMBER,
  SEQ_CASE_COMMENT, SEQ_CASE_ATTACHMENT, SEQ_SAVED_VIEW, SEQ_NOTIFICATION_OUTBOX, SEQ_USER_ALERT, SEQ_UPDATE_REQUEST, SEQ_CLOSURE_REQUEST, SEQ_CASE_REOPEN, SEQ_ALERT_LEDGER,
  SEQ_REPORT_SCHEDULE, SEQ_REPORT_RUN, SEQ_LEGAL_HOLD_EVENT, SEQ_IMPORT_RUN TO ${app_user};
