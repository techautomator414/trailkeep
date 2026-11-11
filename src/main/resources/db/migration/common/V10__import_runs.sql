-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V10 import runs (WP-26 part a, docs/design/importer.md §5), shipped with the ImportRun, ImportIssue and
-- ImportRunSummary entities. None of these tables holds a legacy field value: a run holds codes, hashes and counts, an
-- issue a code, a severity, a number and a keyed hash, a summary row a metric and a number. Staging (IMPORT_STG_*)
-- arrives with 26j and is readable only by the import user.
--
-- IMPORT_RUN: one import or owner-relink run. A Compliance or System Admin requests it on the Import runs screen (the
-- runtime user INSERTs it) and a Compliance Admin signs it off (the runtime user UPDATEs APPROVED_BY, APPROVED_AT and
-- ACKNOWLEDGED_WARNINGS only). The importer Job, as the import user, moves STATUS on and records the mapping hash, the
-- source fingerprint (snapshot, SCN or manifest hash), the scanning mode in force at the dry run (SCAN_MODE, which a
-- load must match, design §3 R1), the counts and the staging expiry; it never touches the approval. Both writers
-- change UPDATED_AT and VERSION, which guards a sign-off against a concurrent status change.
CREATE TABLE IMPORT_RUN (
  ID                    ${id}         NOT NULL,
  KIND                  VARCHAR(8)    NOT NULL,
  SOURCE_ID             VARCHAR(16)   NOT NULL,
  STATUS                VARCHAR(16)   NOT NULL,
  REQUESTED_BY          ${id}         NOT NULL,
  REQUESTED_AT          ${tstz}       NOT NULL,
  APPROVED_BY           ${id},
  APPROVED_AT           ${tstz},
  ACKNOWLEDGED_WARNINGS VARCHAR(1000),
  MAPPING_HASH          VARCHAR(64),
  SOURCE_FINGERPRINT    VARCHAR(128),
  SCAN_MODE             VARCHAR(8),
  ROWS_STAGED           ${id}         DEFAULT 0 NOT NULL,
  ROWS_LOADED           ${id}         DEFAULT 0 NOT NULL,
  STAGING_EXPIRES_AT    ${tstz},
  STAGING_PURGED_AT     ${tstz},
  UPDATED_AT            ${tstz}       NOT NULL,
  VERSION               ${int}        DEFAULT 0 NOT NULL,
  CONSTRAINT PK_IMPORT_RUN PRIMARY KEY (ID),
  CONSTRAINT FK_IMPORT_RUN_REQUESTED_BY FOREIGN KEY (REQUESTED_BY) REFERENCES APP_USER (ID),
  CONSTRAINT FK_IMPORT_RUN_APPROVED_BY FOREIGN KEY (APPROVED_BY) REFERENCES APP_USER (ID),
  CONSTRAINT CK_IMPORT_RUN_KIND CHECK (KIND IN ('IMPORT','RELINK')),
  CONSTRAINT CK_IMPORT_RUN_STATUS CHECK (STATUS IN ('REQUESTED','VALIDATED','EXTRACTED','DRY_RUN','APPROVED',
    'LOADING','LOADED','RECONCILED','FAILED')),
  CONSTRAINT CK_IMPORT_RUN_SCAN_MODE CHECK (SCAN_MODE IN ('CLAMD','EXTERNAL')),
  CONSTRAINT CK_IMPORT_RUN_APPROVAL CHECK ((APPROVED_BY IS NULL AND APPROVED_AT IS NULL)
    OR (APPROVED_BY IS NOT NULL AND APPROVED_AT IS NOT NULL)),
  CONSTRAINT CK_IMPORT_RUN_ROWS CHECK (ROWS_STAGED >= 0 AND ROWS_LOADED >= 0)
);
CREATE INDEX IX_IMPORT_RUN_SOURCE ON IMPORT_RUN (SOURCE_ID, STATUS);
CREATE INDEX IX_IMPORT_RUN_REQUESTED_BY ON IMPORT_RUN (REQUESTED_BY);
CREATE INDEX IX_IMPORT_RUN_APPROVED_BY ON IMPORT_RUN (APPROVED_BY);

CREATE SEQUENCE SEQ_IMPORT_RUN START WITH 1 INCREMENT BY 50;

-- IMPORT_ISSUE: one finding of a run's stage about one entity row (or the mapping). KEY_HASH is the HMAC-SHA-256 of the
-- canonical key under import.hash-key, in hex, never the plain key (design §5 N-h): the runtime user reads this table
-- for the screen. DETAIL is an optional number (for example a size in bytes); messages are never stored (§10).
CREATE TABLE IMPORT_ISSUE (
  ID         ${id}       NOT NULL,
  RUN_ID     ${id}       NOT NULL,
  STAGE      VARCHAR(16) NOT NULL,
  ENTITY     VARCHAR(16) NOT NULL,
  KEY_HASH   VARCHAR(64),
  SEVERITY   VARCHAR(8)  NOT NULL,
  CODE       VARCHAR(40) NOT NULL,
  DETAIL     ${id},
  CREATED_AT ${tstz}     NOT NULL,
  CONSTRAINT PK_IMPORT_ISSUE PRIMARY KEY (ID),
  CONSTRAINT FK_IMPORT_ISSUE_RUN FOREIGN KEY (RUN_ID) REFERENCES IMPORT_RUN (ID),
  CONSTRAINT CK_IMPORT_ISSUE_STAGE CHECK (STAGE IN ('VALIDATE','EXTRACT','DRY_RUN','LOAD','RECONCILE','RELINK')),
  CONSTRAINT CK_IMPORT_ISSUE_ENTITY CHECK (ENTITY IN ('MAPPING','PERSON','CASE','COMMENT','ATTACHMENT','EVENT')),
  CONSTRAINT CK_IMPORT_ISSUE_SEVERITY CHECK (SEVERITY IN ('FATAL','ERROR','WARNING'))
);
CREATE INDEX IX_IMPORT_ISSUE_RUN ON IMPORT_ISSUE (RUN_ID, SEVERITY, CODE);

CREATE SEQUENCE SEQ_IMPORT_ISSUE START WITH 1 INCREMENT BY 50;

-- IMPORT_RUN_SUMMARY: the aggregates the sign-off screen renders (design §3 R8): a metric, an optional dimension (a
-- team id, an SLA state, a scan status or a role) and a whole number. Numbers only, never field values. The importer
-- rewrites a run's rows on each dry run (S I D); the runtime user only reads them.
CREATE TABLE IMPORT_RUN_SUMMARY (
  ID           ${id}       NOT NULL,
  RUN_ID       ${id}       NOT NULL,
  METRIC       VARCHAR(64) NOT NULL,
  DIMENSION    VARCHAR(64),
  METRIC_VALUE ${id}       NOT NULL,
  CONSTRAINT PK_IMPORT_RUN_SUMMARY PRIMARY KEY (ID),
  CONSTRAINT FK_IMPORT_SUMMARY_RUN FOREIGN KEY (RUN_ID) REFERENCES IMPORT_RUN (ID)
);
CREATE INDEX IX_IMPORT_SUMMARY_RUN ON IMPORT_RUN_SUMMARY (RUN_ID, METRIC);

CREATE SEQUENCE SEQ_IMPORT_RUN_SUMMARY START WITH 1 INCREMENT BY 50;
