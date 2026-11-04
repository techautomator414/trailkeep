-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V2__audit (WP-06), shipped with the AuditEvent entity. BUILD_PLAN Appendix A.
-- Deliberately decoupled: no foreign keys on ACTOR_ID or CASE_ID (Part A #35), so audit rows outlive and precede the
-- users and cases they describe. Both columns are indexed. ENTITY_TYPE and ACTION are open sets by design.
CREATE TABLE AUDIT_EVENT (
  ID                  ${id}        NOT NULL,
  AT_TS               ${tstz}      NOT NULL,
  ACTOR_ID            ${id},
  ACTOR_NAME_SNAPSHOT VARCHAR(200),
  ORIGIN              VARCHAR(12)  NOT NULL,
  CASE_ID             ${id},
  ENTITY_TYPE         VARCHAR(40)  NOT NULL,
  ENTITY_ID           ${id},
  ACTION              VARCHAR(40)  NOT NULL,
  OLD_VALUES_JSON     ${clob},
  NEW_VALUES_JSON     ${clob},
  REQUEST_ID          VARCHAR(64),
  CLIENT_IP           VARCHAR(45),
  CONSTRAINT PK_AUDIT_EVENT PRIMARY KEY (ID),
  CONSTRAINT CK_AUDIT_ORIGIN CHECK (ORIGIN IN ('UI','SCHEDULER','IMPORT','SYSTEM'))
);
CREATE INDEX IX_AUDIT_CASE   ON AUDIT_EVENT (CASE_ID, AT_TS);
CREATE INDEX IX_AUDIT_ACTOR  ON AUDIT_EVENT (ACTOR_ID, AT_TS);
CREATE INDEX IX_AUDIT_ENTITY ON AUDIT_EVENT (ENTITY_TYPE, ENTITY_ID, AT_TS);

-- Pooled ids (allocationSize 50), as for every entity sequence.
CREATE SEQUENCE SEQ_AUDIT_EVENT START WITH 1 INCREMENT BY 50;
