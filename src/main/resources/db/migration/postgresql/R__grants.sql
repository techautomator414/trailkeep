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
--
-- Matrix (S = SELECT, I = INSERT, U = UPDATE, D = DELETE):
--
--   object                app_user   purge_user
--   APP_USER              S I U      -            users are disabled, never deleted
--   TEAM                  S I U      -            teams are deactivated, never deleted
--   APP_USER_TEAM         S I U D    -            membership and role changes
--   APP_USER_GLOBAL_ROLE  S I   D    -            re-synced from identity-provider claims at login
--   AUDIT_EVENT           S I        -            append-only; audit outlives purge (V2)
--   SLA_POLICY            S I U      -            edited, never deleted (V3)
--   CASE_TYPE             S I U      -            deactivated, never deleted (V3)
--   CASE_TYPE_OUTCOME     S I U D    -            configuration; an outcome list is replaced as a whole (V3)
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
--   SEQ_APP_USER          USAGE      -
--   SEQ_TEAM              USAGE      -
--   SEQ_AUDIT_EVENT       USAGE      -
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
--
-- Later migrations add their tables here. Fixed rules for them: the app user has no UPDATE or DELETE on
-- CASE_COMMENT, CASE_ATTACHMENT_CHUNK, AUDIT_EVENT and ALERT_LEDGER, and no DELETE on CASE_ATTACHMENT; it may DELETE only from CASE_COMMENT_PIN, SAVED_VIEW, USER_ALERT,
-- SPRING_SESSION*, OIDC_SESSION, SHEDLOCK and importer staging tables, besides the membership tables and
-- CASE_TYPE_OUTCOME (configuration, not case data) above.
-- DatabaseGrantsIT asserts this matrix by executing each statement as each user.

GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${app_user};
GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${purge_user};

REVOKE ALL ON TABLE APP_USER, TEAM, APP_USER_TEAM, APP_USER_GLOBAL_ROLE, AUDIT_EVENT,
  SLA_POLICY, CASE_TYPE, CASE_TYPE_OUTCOME, SPRING_SESSION, SPRING_SESSION_ATTRIBUTES, OIDC_SESSION,
  CASE_RECORD, CASE_SLA, CASE_SEARCH, CASE_COMMENT, CASE_COMMENT_PIN, CASE_ATTACHMENT, CASE_ATTACHMENT_CHUNK, SAVED_VIEW, NOTIFICATION_OUTBOX, USER_ALERT, UPDATE_REQUEST, CLOSURE_REQUEST, CASE_REOPEN,
  ALERT_LEDGER, JOB_RUN, SHEDLOCK FROM ${app_user};
REVOKE ALL ON TABLE APP_USER, TEAM, APP_USER_TEAM, APP_USER_GLOBAL_ROLE, AUDIT_EVENT,
  SLA_POLICY, CASE_TYPE, CASE_TYPE_OUTCOME, SPRING_SESSION, SPRING_SESSION_ATTRIBUTES, OIDC_SESSION,
  CASE_RECORD, CASE_SLA, CASE_SEARCH, CASE_COMMENT, CASE_COMMENT_PIN, CASE_ATTACHMENT, CASE_ATTACHMENT_CHUNK, SAVED_VIEW, NOTIFICATION_OUTBOX, USER_ALERT, UPDATE_REQUEST, CLOSURE_REQUEST, CASE_REOPEN,
  ALERT_LEDGER, JOB_RUN, SHEDLOCK FROM ${purge_user};
REVOKE ALL ON SEQUENCE SEQ_APP_USER, SEQ_TEAM, SEQ_AUDIT_EVENT, SEQ_SLA_POLICY, SEQ_CASE_TYPE, SEQ_CASE_RECORD, SEQ_CASE_NUMBER,
  SEQ_CASE_COMMENT, SEQ_CASE_ATTACHMENT, SEQ_SAVED_VIEW, SEQ_NOTIFICATION_OUTBOX, SEQ_USER_ALERT, SEQ_UPDATE_REQUEST, SEQ_CLOSURE_REQUEST, SEQ_CASE_REOPEN, SEQ_ALERT_LEDGER FROM ${app_user};
REVOKE ALL ON SEQUENCE SEQ_APP_USER, SEQ_TEAM, SEQ_AUDIT_EVENT, SEQ_SLA_POLICY, SEQ_CASE_TYPE, SEQ_CASE_RECORD, SEQ_CASE_NUMBER,
  SEQ_CASE_COMMENT, SEQ_CASE_ATTACHMENT, SEQ_SAVED_VIEW, SEQ_NOTIFICATION_OUTBOX, SEQ_USER_ALERT, SEQ_UPDATE_REQUEST, SEQ_CLOSURE_REQUEST, SEQ_CASE_REOPEN, SEQ_ALERT_LEDGER FROM ${purge_user};

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
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE USER_ALERT           TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE UPDATE_REQUEST       TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE CLOSURE_REQUEST      TO ${app_user};
GRANT SELECT, INSERT                 ON TABLE CASE_REOPEN          TO ${app_user};
GRANT SELECT, INSERT                 ON TABLE ALERT_LEDGER         TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE JOB_RUN              TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE SHEDLOCK             TO ${app_user};

-- USAGE allows nextval(), which is all Hibernate's pooled optimizer calls.
GRANT USAGE ON SEQUENCE SEQ_APP_USER, SEQ_TEAM, SEQ_AUDIT_EVENT, SEQ_SLA_POLICY, SEQ_CASE_TYPE, SEQ_CASE_RECORD, SEQ_CASE_NUMBER,
  SEQ_CASE_COMMENT, SEQ_CASE_ATTACHMENT, SEQ_SAVED_VIEW, SEQ_NOTIFICATION_OUTBOX, SEQ_USER_ALERT, SEQ_UPDATE_REQUEST, SEQ_CLOSURE_REQUEST, SEQ_CASE_REOPEN, SEQ_ALERT_LEDGER TO ${app_user};
