-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V5.1 case reopens (WP-19 part b2), shipped with the CaseReopen entity (BUILD_PLAN §4.3 "Reopen (Manager+) → reason*
-- → Open", §5.2: "Reopened is an event, not a state"). One row per reopen, never changed: who reopened the case, when,
-- why, and the SLA cycle it started (ADR-0007). REASON is possible PHI like a decision reason: shown on the case,
-- redacted in the audit trail, never mailed (BUILD_PLAN §9). CLOSURE_REQUEST_ID names the request whose approval
-- closed the case; it is NULL for a case closed without one (an imported closed case). The runtime user may SELECT
-- and INSERT only (R__grants.sql): a reopen is case history.
CREATE TABLE CASE_REOPEN (
  ID                 ${id}         NOT NULL,
  CASE_ID            ${id}         NOT NULL,
  REOPENED_BY        ${id}         NOT NULL,
  REOPENED_AT        ${tstz}       NOT NULL,
  REASON             VARCHAR(1000) NOT NULL,
  SLA_CYCLE          ${int}        NOT NULL,
  CLOSURE_REQUEST_ID ${id},
  CONSTRAINT PK_CASE_REOPEN PRIMARY KEY (ID),
  CONSTRAINT FK_REOPEN_CASE FOREIGN KEY (CASE_ID) REFERENCES CASE_RECORD (ID),
  CONSTRAINT FK_REOPEN_BY FOREIGN KEY (REOPENED_BY) REFERENCES APP_USER (ID),
  CONSTRAINT FK_REOPEN_CLOSURE FOREIGN KEY (CLOSURE_REQUEST_ID) REFERENCES CLOSURE_REQUEST (ID),
  CONSTRAINT CK_REOPEN_CYCLE CHECK (SLA_CYCLE >= 2)
);
CREATE INDEX IX_REOPEN_CASE    ON CASE_REOPEN (CASE_ID, REOPENED_AT);
CREATE INDEX IX_REOPEN_BY      ON CASE_REOPEN (REOPENED_BY);
CREATE INDEX IX_REOPEN_CLOSURE ON CASE_REOPEN (CLOSURE_REQUEST_ID);

CREATE SEQUENCE SEQ_CASE_REOPEN START WITH 1 INCREMENT BY 50;
