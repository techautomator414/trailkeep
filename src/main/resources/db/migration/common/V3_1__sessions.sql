-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V3.1 sessions (PR 10, WP-04b): Spring Session JDBC tables and the OIDC back-channel registry (ADR-0008).
-- Spring Session's own PostgreSQL and Oracle schemas differ only in types, so they are written once with the
-- Appendix A placeholders (BIGINT/NUMBER(19,0), INT/NUMBER(10,0), BYTEA/BLOB). Version 3.1 sorts after V3__admin (WP-10) and before V4__case (WP-11).
-- Attribute bytes are JSON (Spring Security's Jackson modules), never JDK serialization; no authorities are stored.

CREATE TABLE SPRING_SESSION (
  PRIMARY_ID            CHAR(36)     NOT NULL,
  SESSION_ID            CHAR(36)     NOT NULL,
  CREATION_TIME         ${id}        NOT NULL,
  LAST_ACCESS_TIME      ${id}        NOT NULL,
  MAX_INACTIVE_INTERVAL ${int}       NOT NULL,
  EXPIRY_TIME           ${id}        NOT NULL,
  PRINCIPAL_NAME        VARCHAR(100),
  CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
  SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
  ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
  ATTRIBUTE_BYTES    ${bytes}     NOT NULL,
  CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
  CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
    REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);

-- One row per application session started by an OIDC login, so a back-channel logout token (by sid, or by sub) finds
-- the sessions to end on every replica. AUTHORITIES_JSON is the header map Spring Security's back-channel handler
-- replays on its loopback /logout call (the CSRF header captured at login), not granted authorities.
-- No FK to SPRING_SESSION: Spring Session expires rows on its own schedule; a daily job removes orphans here.
CREATE TABLE OIDC_SESSION (
  SESSION_ID       CHAR(36)     NOT NULL,
  SID              VARCHAR(255),
  PRINCIPAL        VARCHAR(255) NOT NULL,
  AUTHORITIES_JSON ${clob}      NOT NULL,
  CREATED_AT       ${tstz}      NOT NULL,
  CONSTRAINT PK_OIDC_SESSION PRIMARY KEY (SESSION_ID)
);
CREATE INDEX IX_OIDC_SESSION_SID ON OIDC_SESSION (SID);
CREATE INDEX IX_OIDC_SESSION_PRINCIPAL ON OIDC_SESSION (PRINCIPAL);
