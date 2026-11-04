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
--   SEQ_APP_USER          USAGE      -
--   SEQ_TEAM              USAGE      -
--
-- Later migrations add their tables here. Fixed rules for them: the app user has no UPDATE or DELETE on
-- CASE_COMMENT, AUDIT_EVENT and ALERT_LEDGER; it may DELETE only from CASE_COMMENT_PIN, SAVED_VIEW, USER_ALERT,
-- SPRING_SESSION*, OIDC_SESSION, SHEDLOCK and importer staging tables, besides the membership tables above.
-- DatabaseGrantsIT asserts this matrix by executing each statement as each user.

GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${app_user};
GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${purge_user};

REVOKE ALL ON TABLE APP_USER, TEAM, APP_USER_TEAM, APP_USER_GLOBAL_ROLE FROM ${app_user};
REVOKE ALL ON TABLE APP_USER, TEAM, APP_USER_TEAM, APP_USER_GLOBAL_ROLE FROM ${purge_user};
REVOKE ALL ON SEQUENCE SEQ_APP_USER, SEQ_TEAM FROM ${app_user};
REVOKE ALL ON SEQUENCE SEQ_APP_USER, SEQ_TEAM FROM ${purge_user};

GRANT SELECT, INSERT, UPDATE         ON TABLE APP_USER             TO ${app_user};
GRANT SELECT, INSERT, UPDATE         ON TABLE TEAM                 TO ${app_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE APP_USER_TEAM        TO ${app_user};
GRANT SELECT, INSERT, DELETE         ON TABLE APP_USER_GLOBAL_ROLE TO ${app_user};

-- USAGE allows nextval(), which is all Hibernate's pooled optimizer calls.
GRANT USAGE ON SEQUENCE SEQ_APP_USER, SEQ_TEAM TO ${app_user};
