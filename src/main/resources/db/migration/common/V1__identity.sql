-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V1 identity (PR 6, WP-03b): users, teams, team membership and global roles, per BUILD_PLAN Appendix A.
-- Ships with the AppUser, Team, AppUserTeam and AppUserGlobalRole entities in module 'user'.
-- Users come from the identity provider; no password is ever stored.

CREATE TABLE APP_USER (
  ID            ${id}          NOT NULL,
  EXTERNAL_ID   VARCHAR(255)   NOT NULL,
  DISPLAY_NAME  VARCHAR(200)   NOT NULL,
  EMAIL         VARCHAR(320)   NOT NULL,
  DISABLED      ${bool}        DEFAULT ${false} NOT NULL,
  LAST_LOGIN_AT ${tstz},
  CREATED_AT    ${tstz}        NOT NULL,
  CONSTRAINT PK_APP_USER PRIMARY KEY (ID),
  CONSTRAINT UK_APP_USER_EXT UNIQUE (EXTERNAL_ID)
);

CREATE TABLE TEAM (
  ID     ${id}        NOT NULL,
  NAME   VARCHAR(120) NOT NULL,
  ACTIVE ${bool}      DEFAULT ${true} NOT NULL,
  CONSTRAINT PK_TEAM PRIMARY KEY (ID),
  CONSTRAINT UK_TEAM_NAME UNIQUE (NAME)
);

-- The primary key (USER_ID, TEAM_ID) indexes FK_AUT_USER; IX_AUT_TEAM_USER indexes FK_AUT_TEAM and serves
-- "members of a team" (Oracle takes table-level locks on parent DML when an FK column is unindexed).
CREATE TABLE APP_USER_TEAM (
  USER_ID      ${id}       NOT NULL,
  TEAM_ID      ${id}       NOT NULL,
  ROLE_IN_TEAM VARCHAR(10) NOT NULL,
  CONSTRAINT PK_APP_USER_TEAM PRIMARY KEY (USER_ID, TEAM_ID),
  CONSTRAINT FK_AUT_USER FOREIGN KEY (USER_ID) REFERENCES APP_USER (ID),
  CONSTRAINT FK_AUT_TEAM FOREIGN KEY (TEAM_ID) REFERENCES TEAM (ID),
  CONSTRAINT CK_AUT_ROLE CHECK (ROLE_IN_TEAM IN ('READER','ANALYST','MANAGER'))
);
CREATE INDEX IX_AUT_TEAM_USER ON APP_USER_TEAM (TEAM_ID, USER_ID);

-- The primary key (USER_ID, GLOBAL_ROLE) indexes FK_AUGR_USER.
CREATE TABLE APP_USER_GLOBAL_ROLE (
  USER_ID     ${id}       NOT NULL,
  GLOBAL_ROLE VARCHAR(16) NOT NULL,
  CONSTRAINT PK_APP_USER_GLOBAL_ROLE PRIMARY KEY (USER_ID, GLOBAL_ROLE),
  CONSTRAINT FK_AUGR_USER FOREIGN KEY (USER_ID) REFERENCES APP_USER (ID),
  CONSTRAINT CK_AUGR_ROLE CHECK (GLOBAL_ROLE IN ('COMPLIANCE_ADMIN','SYSTEM_ADMIN'))
);
-- "A System Admin cannot hold team roles" is a service invariant with a matrix test (cross-table CHECKs are not
-- portable).

-- Entity ids: pooled sequences; INCREMENT BY matches @SequenceGenerator(allocationSize = 50), which Hibernate
-- validate checks.
CREATE SEQUENCE SEQ_APP_USER START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE SEQ_TEAM     START WITH 1 INCREMENT BY 50;
