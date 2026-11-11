-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V13 import staging (WP-26 part j, docs/design/importer.md §5), shipped with the staging entities in
-- io.trailkeep.importer.staging, which only the importer profile maps (StagingEntityFilter, design §2.1 R3).
--
-- Staging holds canonical legacy rows, restricted descriptions included, so only the import user may read or write
-- it (R__grants.sql): the runtime and purge users hold no privilege here and the web application does not map it.
-- Each row is keyed by its run and its canonical key (LEGACY_KEY, at most 100 characters; §5 "Keys"); ROW_HASH is
-- the keyed HMAC-SHA-256 of the canonical row under import.hash-key, in hex. The child tables name their case by
-- its canonical key (CASE_KEY); the engine joins them in Java, so there is no foreign key between staging tables.
-- A run's rows go with purge-staging, or when its STAGING_EXPIRES_AT passes, whichever comes first (§5 "Staging TTL").
CREATE TABLE IMPORT_STG_PERSON (
  RUN_ID       ${id}        NOT NULL,
  LEGACY_KEY   VARCHAR(100) NOT NULL,
  ROW_HASH     VARCHAR(64)  NOT NULL,
  DISPLAY_NAME VARCHAR(200),
  EMAIL        VARCHAR(320),
  EXTERNAL_ID  VARCHAR(255),
  CONSTRAINT PK_IMPORT_STG_PERSON PRIMARY KEY (RUN_ID, LEGACY_KEY),
  CONSTRAINT FK_STG_PERSON_RUN FOREIGN KEY (RUN_ID) REFERENCES IMPORT_RUN (ID)
);

CREATE TABLE IMPORT_STG_CASE (
  RUN_ID          ${id}         NOT NULL,
  LEGACY_KEY      VARCHAR(100)  NOT NULL,
  ROW_HASH        VARCHAR(64)   NOT NULL,
  LEGACY_NUMBER   VARCHAR(100),
  TITLE           VARCHAR(1000),
  DESCRIPTION     ${clob},
  SUBJECT_TEXT    ${clob},
  CASE_TYPE_KEY   VARCHAR(100),
  TEAM_KEY        VARCHAR(100),
  OWNER_KEY       VARCHAR(100),
  PRIORITY        VARCHAR(16),
  SENSITIVITY     VARCHAR(16),
  LEGACY_ACL      ${bool},
  STATUS          VARCHAR(16),
  IDENTIFIED_ON   DATE,
  OPENED_AT       ${tstz},
  CLOSED_AT       ${tstz},
  CLOSURE_OUTCOME VARCHAR(100),
  CLOSURE_SUMMARY ${clob},
  LEGAL_HOLD      ${bool},
  CONSTRAINT PK_IMPORT_STG_CASE PRIMARY KEY (RUN_ID, LEGACY_KEY),
  CONSTRAINT FK_STG_CASE_RUN FOREIGN KEY (RUN_ID) REFERENCES IMPORT_RUN (ID)
);

CREATE TABLE IMPORT_STG_COMMENT (
  RUN_ID      ${id}        NOT NULL,
  LEGACY_KEY  VARCHAR(100) NOT NULL,
  ROW_HASH    VARCHAR(64)  NOT NULL,
  CASE_KEY    VARCHAR(100) NOT NULL,
  AUTHOR_KEY  VARCHAR(100),
  AUTHOR_NAME VARCHAR(200),
  WRITTEN_AT  ${tstz},
  BODY        ${clob},
  BODY_FORMAT VARCHAR(8),
  CONSTRAINT PK_IMPORT_STG_COMMENT PRIMARY KEY (RUN_ID, LEGACY_KEY),
  CONSTRAINT FK_STG_COMMENT_RUN FOREIGN KEY (RUN_ID) REFERENCES IMPORT_RUN (ID)
);
CREATE INDEX IX_STG_COMMENT_CASE ON IMPORT_STG_COMMENT (RUN_ID, CASE_KEY);

CREATE TABLE IMPORT_STG_ATTACHMENT (
  RUN_ID          ${id}         NOT NULL,
  LEGACY_KEY      VARCHAR(100)  NOT NULL,
  ROW_HASH        VARCHAR(64)   NOT NULL,
  CASE_KEY        VARCHAR(100)  NOT NULL,
  FILE_NAME       VARCHAR(1000),
  SIZE_BYTES      ${id},
  SHA256          VARCHAR(64),
  CONTENT_POINTER VARCHAR(1000),
  UPLOADED_AT     ${tstz},
  UPLOADER_KEY    VARCHAR(100),
  UPLOADER_NAME   VARCHAR(200),
  CONSTRAINT PK_IMPORT_STG_ATTACHMENT PRIMARY KEY (RUN_ID, LEGACY_KEY),
  CONSTRAINT FK_STG_ATTACHMENT_RUN FOREIGN KEY (RUN_ID) REFERENCES IMPORT_RUN (ID)
);
CREATE INDEX IX_STG_ATTACHMENT_CASE ON IMPORT_STG_ATTACHMENT (RUN_ID, CASE_KEY);

CREATE TABLE IMPORT_STG_EVENT (
  RUN_ID     ${id}        NOT NULL,
  LEGACY_KEY VARCHAR(100) NOT NULL,
  ROW_HASH   VARCHAR(64)  NOT NULL,
  CASE_KEY   VARCHAR(100) NOT NULL,
  EVENT_AT   ${tstz},
  WHO_KEY    VARCHAR(100),
  WHO_NAME   VARCHAR(200),
  KIND       VARCHAR(32),
  FIELD_NAME VARCHAR(64),
  OLD_VALUE  ${clob},
  NEW_VALUE  ${clob},
  CONSTRAINT PK_IMPORT_STG_EVENT PRIMARY KEY (RUN_ID, LEGACY_KEY),
  CONSTRAINT FK_STG_EVENT_RUN FOREIGN KEY (RUN_ID) REFERENCES IMPORT_RUN (ID)
);
CREATE INDEX IX_STG_EVENT_CASE ON IMPORT_STG_EVENT (RUN_ID, CASE_KEY);

-- IMPORT_RUN_CHECKPOINT: one row per entity a stage has finished for a run (§4.1 "Restart, not mid-entity resume"):
-- a crashed extract deletes the unfinished entity's staging rows and reads that entity again.
CREATE TABLE IMPORT_RUN_CHECKPOINT (
  RUN_ID      ${id}       NOT NULL,
  STAGE       VARCHAR(16) NOT NULL,
  ENTITY      VARCHAR(16) NOT NULL,
  FINISHED_AT ${tstz}     NOT NULL,
  CONSTRAINT PK_IMPORT_RUN_CHECKPOINT PRIMARY KEY (RUN_ID, STAGE, ENTITY),
  CONSTRAINT FK_CHECKPOINT_RUN FOREIGN KEY (RUN_ID) REFERENCES IMPORT_RUN (ID),
  CONSTRAINT CK_CHECKPOINT_STAGE CHECK (STAGE IN ('EXTRACT','LOAD','RELINK')),
  CONSTRAINT CK_CHECKPOINT_ENTITY CHECK (ENTITY IN ('PERSON','CASE','COMMENT','ATTACHMENT','EVENT'))
);
