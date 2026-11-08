-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V8 legal hold events (WP-24 part b2), shipped with the LegalHoldEvent entity (BUILD_PLAN §4.6 "Legal hold (who,
-- when, why)"). One row per hold placed or lifted, never changed: the case row holds only the current hold, which
-- lifting clears, so this is the readable history. REASON is what the Compliance Admin wrote, shown to Compliance
-- Admins only and redacted in the audit trail; PURGE_AFTER is the case's purge date after the change (null while the
-- case is not archived). The runtime user may SELECT and INSERT only (R__grants.sql). The purge job keeps these rows,
-- like the audit trail and the alert ledger: it tombstones the case row, so the foreign key stays valid.
CREATE TABLE LEGAL_HOLD_EVENT (
  ID          ${id}        NOT NULL,
  CASE_ID     ${id}        NOT NULL,
  ACTION      VARCHAR(8)   NOT NULL,
  CHANGED_BY  ${id}        NOT NULL,
  CHANGED_AT  ${tstz}      NOT NULL,
  REASON      VARCHAR(500) NOT NULL,
  PURGE_AFTER ${tstz},
  CONSTRAINT PK_LEGAL_HOLD_EVENT PRIMARY KEY (ID),
  CONSTRAINT FK_HOLD_EVENT_CASE FOREIGN KEY (CASE_ID) REFERENCES CASE_RECORD (ID),
  CONSTRAINT FK_HOLD_EVENT_BY FOREIGN KEY (CHANGED_BY) REFERENCES APP_USER (ID),
  CONSTRAINT CK_HOLD_EVENT_ACTION CHECK (ACTION IN ('PLACED','LIFTED'))
);
CREATE INDEX IX_HOLD_EVENT_CASE ON LEGAL_HOLD_EVENT (CASE_ID, CHANGED_AT);
CREATE INDEX IX_HOLD_EVENT_BY   ON LEGAL_HOLD_EVENT (CHANGED_BY);

CREATE SEQUENCE SEQ_LEGAL_HOLD_EVENT START WITH 1 INCREMENT BY 50;
