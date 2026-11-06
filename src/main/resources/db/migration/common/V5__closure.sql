-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V5 closure requests (WP-19 part a), shipped with the ClosureRequest entity (BUILD_PLAN §4.3 "Request Closure", §5,
-- Appendix A). A request moves its case to PENDING_CLOSURE and names one approver; the decision (WP-19 part b) is
-- APPROVED (case CLOSED), REJECTED or CHANGES_REQUESTED (case back to OPEN, with REASON). OUTCOME is a text snapshot
-- of one of the case type's outcomes when it was requested (ADR-0011), so a later change to the list never rewrites
-- history. RESOLUTION_SUMMARY and FOLLOW_UP_ACTIONS are possible PHI, like comments: shown on the case, redacted in the
-- audit trail, never mailed (BUILD_PLAN §9). The requester may never approve their own request (CK_CLOSURE_SOD, also
-- enforced by the service with the rest of IMPLEMENTATION_PLAN A.2 #18). VERSION guards the decision; the runtime
-- user may SELECT, INSERT and UPDATE, never DELETE (R__grants.sql): a closure request is case history.
CREATE TABLE CLOSURE_REQUEST (
  ID                 ${id}         NOT NULL,
  CASE_ID            ${id}         NOT NULL,
  REQUESTED_BY       ${id}         NOT NULL,
  APPROVER_ID        ${id}         NOT NULL,
  REQUESTED_AT       ${tstz}       NOT NULL,
  OUTCOME            VARCHAR(40)   NOT NULL,
  RESOLUTION_SUMMARY ${clob}       NOT NULL,
  FOLLOW_UP_ACTIONS  ${clob},
  DECISION           VARCHAR(20)   DEFAULT 'PENDING' NOT NULL,
  DECIDED_BY         ${id},
  DECIDED_AT         ${tstz},
  REASON             VARCHAR(1000),
  VERSION            ${int}        DEFAULT 0 NOT NULL,
  CONSTRAINT PK_CLOSURE_REQUEST PRIMARY KEY (ID),
  CONSTRAINT FK_CLOSURE_CASE FOREIGN KEY (CASE_ID) REFERENCES CASE_RECORD (ID),
  CONSTRAINT FK_CLOSURE_REQUESTER FOREIGN KEY (REQUESTED_BY) REFERENCES APP_USER (ID),
  CONSTRAINT FK_CLOSURE_APPROVER FOREIGN KEY (APPROVER_ID) REFERENCES APP_USER (ID),
  CONSTRAINT FK_CLOSURE_DECIDER FOREIGN KEY (DECIDED_BY) REFERENCES APP_USER (ID),
  CONSTRAINT CK_CLOSURE_DECISION CHECK (DECISION IN ('PENDING','APPROVED','REJECTED','CHANGES_REQUESTED')),
  CONSTRAINT CK_CLOSURE_DECIDED CHECK ((DECISION = 'PENDING' AND DECIDED_BY IS NULL AND DECIDED_AT IS NULL)
    OR (DECISION <> 'PENDING' AND DECIDED_BY IS NOT NULL AND DECIDED_AT IS NOT NULL)),
  CONSTRAINT CK_CLOSURE_SOD CHECK (REQUESTED_BY <> APPROVER_ID)
);
CREATE INDEX IX_CLOSURE_APPROVER  ON CLOSURE_REQUEST (APPROVER_ID, DECISION);
CREATE INDEX IX_CLOSURE_CASE      ON CLOSURE_REQUEST (CASE_ID, REQUESTED_AT);
CREATE INDEX IX_CLOSURE_REQUESTER ON CLOSURE_REQUEST (REQUESTED_BY);
CREATE INDEX IX_CLOSURE_DECIDER   ON CLOSURE_REQUEST (DECIDED_BY);

CREATE SEQUENCE SEQ_CLOSURE_REQUEST START WITH 1 INCREMENT BY 50;
