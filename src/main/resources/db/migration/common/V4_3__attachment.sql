-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V4.3 attachments (WP-14 part 1), shipped with the CaseAttachment entity. Version 4.3 sorts after the comment
-- migrations and before V5__closure (WP-19, IMPLEMENTATION_PLAN A.4 #37).
--
-- An attachment is a file that was scanned before anything reached the database (IMPLEMENTATION_PLAN A.1 #3): there
-- is no PENDING state. CLEAN files are downloadable; QUARANTINED files are kept as evidence and never served; a
-- Compliance Admin may RELEASE a quarantined file with a reason, which is the only change a row ever sees. The
-- runtime user may update only the four release columns (R__grants.sql), so name, type, size, hash, detection and
-- uploader are fixed once written. CONTENT_TYPE is the type detected from the bytes, never the one the client sent.
-- SCAN_DETAIL is the scanner's signature name for a quarantined file.
--
-- Content lives in CASE_ATTACHMENT_CHUNK as rows of at most 1 MiB, numbered from 0 by SEQ, written and read only by
-- AttachmentContentJdbcDao, one row per query (ADR-0004). The number of chunks follows from SIZE_BYTES. Chunks are
-- never changed; only the purge user will delete them, with the case (WP-24).
CREATE TABLE CASE_ATTACHMENT (
  ID              ${id}         NOT NULL,
  CASE_ID         ${id}         NOT NULL,
  FILE_NAME       VARCHAR(255)  NOT NULL,
  CONTENT_TYPE    VARCHAR(100)  NOT NULL,
  SIZE_BYTES      ${id}         NOT NULL,
  SHA256          VARCHAR(64)   NOT NULL,
  SCAN_STATUS     VARCHAR(12)   NOT NULL,
  SCAN_DETAIL     VARCHAR(200),
  RELEASED_BY     ${id},
  RELEASED_AT     ${tstz},
  RELEASED_REASON VARCHAR(1000),
  UPLOADED_AT     ${tstz}       NOT NULL,
  UPLOADED_BY     ${id}         NOT NULL,
  CONSTRAINT PK_CASE_ATTACHMENT PRIMARY KEY (ID),
  CONSTRAINT FK_ATTACHMENT_CASE FOREIGN KEY (CASE_ID) REFERENCES CASE_RECORD (ID),
  CONSTRAINT FK_ATTACHMENT_UPLOADER FOREIGN KEY (UPLOADED_BY) REFERENCES APP_USER (ID),
  CONSTRAINT FK_ATTACHMENT_RELEASER FOREIGN KEY (RELEASED_BY) REFERENCES APP_USER (ID),
  CONSTRAINT CK_ATTACHMENT_SCAN CHECK (SCAN_STATUS IN ('CLEAN','QUARANTINED','RELEASED')),
  CONSTRAINT CK_ATTACHMENT_SIZE CHECK (SIZE_BYTES >= 0),
  CONSTRAINT CK_ATTACHMENT_SHA256 CHECK (LENGTH(SHA256) = 64),
  CONSTRAINT CK_ATTACHMENT_RELEASE CHECK (
    (SCAN_STATUS = 'RELEASED' AND RELEASED_BY IS NOT NULL AND RELEASED_AT IS NOT NULL
      AND RELEASED_REASON IS NOT NULL)
    OR (SCAN_STATUS <> 'RELEASED' AND RELEASED_BY IS NULL AND RELEASED_AT IS NULL AND RELEASED_REASON IS NULL))
);
CREATE INDEX IX_ATTACHMENT_CASE     ON CASE_ATTACHMENT (CASE_ID, UPLOADED_AT);
CREATE INDEX IX_ATTACHMENT_UPLOADER ON CASE_ATTACHMENT (UPLOADED_BY);
CREATE INDEX IX_ATTACHMENT_RELEASER ON CASE_ATTACHMENT (RELEASED_BY);

CREATE SEQUENCE SEQ_CASE_ATTACHMENT START WITH 1 INCREMENT BY 50;

-- The primary key's leading column indexes the foreign key.
CREATE TABLE CASE_ATTACHMENT_CHUNK (
  ATTACHMENT_ID ${id}    NOT NULL,
  SEQ           ${int}   NOT NULL,
  CHUNK         ${bytes} NOT NULL,
  CONSTRAINT PK_ATTACHMENT_CHUNK PRIMARY KEY (ATTACHMENT_ID, SEQ),
  CONSTRAINT FK_CHUNK_ATTACHMENT FOREIGN KEY (ATTACHMENT_ID) REFERENCES CASE_ATTACHMENT (ID),
  CONSTRAINT CK_CHUNK_SEQ CHECK (SEQ >= 0)
);
