-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V16 imported history (WP-26 part v, docs/design/importer.md §7.5), shipped with the CaseImportedEvent entity.
-- Version: V12 to V15 are queued in open pull requests ahead of this one.
--
-- CASE_IMPORTED_EVENT: one change from the legacy system's history of a case, written by the importer Job and never
-- changed (the import user has SELECT and INSERT only, the runtime user SELECT for the History tab, and the purge user
-- SELECT and DELETE, because imported history is case content that purge removes with comments). It is separate from
-- AUDIT_EVENT, which stays the record of what Trailkeep itself did. WHO_NAME_SNAPSHOT is the legacy actor as recorded,
-- with no user id, because legacy actors are mostly unmatched. A value is stored either as plain text (only the status,
-- owner, priority, team, due and title fields) or, for every other field and every NOTE, redacted as audit redacts:
-- the HMAC-SHA-256 under the audit key and the length in code points, never the text. The checks keep a value from
-- being both plain and redacted, and a hash from being stored without its length.
CREATE TABLE CASE_IMPORTED_EVENT (
  ID                ${id}         NOT NULL,
  CASE_ID           ${id}         NOT NULL,
  AT_TS             ${tstz}       NOT NULL,
  WHO_NAME_SNAPSHOT VARCHAR(200),
  KIND              VARCHAR(16)   NOT NULL,
  FIELD_NAME        VARCHAR(40),
  OLD_VALUE         VARCHAR(1000),
  NEW_VALUE         VARCHAR(1000),
  OLD_HMAC          VARCHAR(64),
  NEW_HMAC          VARCHAR(64),
  OLD_LENGTH        ${int},
  NEW_LENGTH        ${int},
  RUN_ID            ${id}         NOT NULL,
  CONSTRAINT PK_CASE_IMPORTED_EVENT PRIMARY KEY (ID),
  CONSTRAINT FK_CIE_CASE FOREIGN KEY (CASE_ID) REFERENCES CASE_RECORD (ID),
  CONSTRAINT FK_CIE_RUN FOREIGN KEY (RUN_ID) REFERENCES IMPORT_RUN (ID),
  CONSTRAINT CK_CIE_KIND CHECK (KIND IN ('FIELD_CHANGE','STATUS_CHANGE','ASSIGNMENT','NOTE','OTHER')),
  CONSTRAINT CK_CIE_OLD CHECK ((OLD_HMAC IS NULL AND OLD_LENGTH IS NULL)
    OR (OLD_VALUE IS NULL AND OLD_HMAC IS NOT NULL AND OLD_LENGTH IS NOT NULL AND OLD_LENGTH >= 0)),
  CONSTRAINT CK_CIE_NEW CHECK ((NEW_HMAC IS NULL AND NEW_LENGTH IS NULL)
    OR (NEW_VALUE IS NULL AND NEW_HMAC IS NOT NULL AND NEW_LENGTH IS NOT NULL AND NEW_LENGTH >= 0))
);
CREATE INDEX IX_CASE_IMPORTED_EVENT_CASE ON CASE_IMPORTED_EVENT (CASE_ID, AT_TS);
CREATE INDEX IX_CASE_IMPORTED_EVENT_RUN ON CASE_IMPORTED_EVENT (RUN_ID);

CREATE SEQUENCE SEQ_CASE_IMPORTED_EVENT START WITH 1 INCREMENT BY 50;
