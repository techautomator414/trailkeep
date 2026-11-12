-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V17 import ledger (WP-26 part t, docs/design/importer.md §5, §8.2), shipped with the ImportLedgerEntry entity.
--
-- IMPORT_LEDGER: one row per legacy row the load wrote, in the same transaction as the domain rows, so a key is loaded
-- at most once across runs and attempts (UK_IMPORT_LEDGER_KEY) and a crashed load resumes at the first case it does
-- not hold. Insert-only: the import user has SELECT and INSERT, the runtime user SELECT, nobody UPDATE or DELETE.
-- TARGET_ID is the row written (the case, comment, attachment or event id) and has no foreign key, because its table
-- depends on ENTITY. ROW_HASH is the staged row's keyed hash and MAPPING_HASH the mapping it was loaded under, for
-- delta runs (§3.1); STATUS and CLOSED_AT are a case's canonical status and closing time as loaded (R10), null for
-- the other entities.
CREATE TABLE IMPORT_LEDGER (
  ID           ${id}         NOT NULL,
  SOURCE_ID    VARCHAR(16)   NOT NULL,
  ENTITY       VARCHAR(16)   NOT NULL,
  LEGACY_KEY   VARCHAR(100)  NOT NULL,
  CASE_ID      ${id}         NOT NULL,
  TARGET_ID    ${id}         NOT NULL,
  ROW_HASH     VARCHAR(64)   NOT NULL,
  MAPPING_HASH VARCHAR(64)   NOT NULL,
  STATUS       VARCHAR(16),
  CLOSED_AT    ${tstz},
  RUN_ID       ${id}         NOT NULL,
  LOADED_AT    ${tstz}       NOT NULL,
  CONSTRAINT PK_IMPORT_LEDGER PRIMARY KEY (ID),
  CONSTRAINT UK_IMPORT_LEDGER_KEY UNIQUE (SOURCE_ID, ENTITY, LEGACY_KEY),
  CONSTRAINT FK_IMPORT_LEDGER_CASE FOREIGN KEY (CASE_ID) REFERENCES CASE_RECORD (ID),
  CONSTRAINT FK_IMPORT_LEDGER_RUN FOREIGN KEY (RUN_ID) REFERENCES IMPORT_RUN (ID),
  CONSTRAINT CK_IMPORT_LEDGER_ENTITY CHECK (ENTITY IN ('CASE','COMMENT','ATTACHMENT','EVENT')),
  CONSTRAINT CK_IMPORT_LEDGER_STATUS CHECK ((ENTITY = 'CASE' AND STATUS IN ('OPEN','CLOSED'))
    OR (ENTITY <> 'CASE' AND STATUS IS NULL AND CLOSED_AT IS NULL))
);
CREATE INDEX IX_IMPORT_LEDGER_CASE ON IMPORT_LEDGER (CASE_ID);
CREATE INDEX IX_IMPORT_LEDGER_RUN ON IMPORT_LEDGER (RUN_ID);

CREATE SEQUENCE SEQ_IMPORT_LEDGER START WITH 1 INCREMENT BY 50;
