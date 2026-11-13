-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V18 pending owners (WP-26 part z1, docs/design/importer.md §5, §7.3), shipped with the ImportOwnerPending entity.
--
-- IMPORT_OWNER_PENDING: one row per open case the load gave its team's default owner because the legacy owner matched
-- no user who may own it, written in the case's transaction, so that relink-owners (part z2) can hand the case to that
-- person once they have signed in. No legacy value is stored: EMAIL_HMAC and EXTERNAL_ID_HMAC are keyed hashes
-- (import.hash-key) of the legacy owner's normalised email address and external id, at least one of them set; a
-- legacy owner with neither could never be matched, so gets no row. One row per case (UK_IMPORT_OWNER_PENDING_CASE,
-- which also indexes the case's foreign key); the row is deleted when the case is relinked.
CREATE TABLE IMPORT_OWNER_PENDING (
  ID               ${id}         NOT NULL,
  CASE_ID          ${id}         NOT NULL,
  DEFAULT_OWNER_ID ${id}         NOT NULL,
  EMAIL_HMAC       VARCHAR(64),
  EXTERNAL_ID_HMAC VARCHAR(64),
  RUN_ID           ${id}         NOT NULL,
  CREATED_AT       ${tstz}       NOT NULL,
  CONSTRAINT PK_IMPORT_OWNER_PENDING PRIMARY KEY (ID),
  CONSTRAINT UK_IMPORT_OWNER_PENDING_CASE UNIQUE (CASE_ID),
  CONSTRAINT FK_IMPORT_OWNER_PENDING_CASE FOREIGN KEY (CASE_ID) REFERENCES CASE_RECORD (ID),
  CONSTRAINT FK_IMPORT_OWNER_PENDING_USER FOREIGN KEY (DEFAULT_OWNER_ID) REFERENCES APP_USER (ID),
  CONSTRAINT FK_IMPORT_OWNER_PENDING_RUN FOREIGN KEY (RUN_ID) REFERENCES IMPORT_RUN (ID),
  CONSTRAINT CK_IMPORT_OWNER_PENDING_HMAC CHECK (EMAIL_HMAC IS NOT NULL OR EXTERNAL_ID_HMAC IS NOT NULL)
);
CREATE INDEX IX_IMPORT_OWNER_PENDING_USER ON IMPORT_OWNER_PENDING (DEFAULT_OWNER_ID);
CREATE INDEX IX_IMPORT_OWNER_PENDING_RUN ON IMPORT_OWNER_PENDING (RUN_ID);

CREATE SEQUENCE SEQ_IMPORT_OWNER_PENDING START WITH 1 INCREMENT BY 50;
