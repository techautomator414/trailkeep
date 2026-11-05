-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V4.1 comments (WP-13), shipped with the CaseComment entity. Version 4.1 sorts after V4__case (WP-11) and before
-- V5__closure (WP-19, IMPLEMENTATION_PLAN A.4 #37). CASE_COMMENT_PIN follows with the pin entity.
--
-- Insert-only: the runtime user may SELECT and INSERT, never UPDATE or DELETE (R__grants.sql); a correction is a new
-- comment. Only the purge user will delete comments, with the case (WP-24). BODY is the Markdown as typed, rendered
-- and sanitized on display. AUTHOR_NAME_SNAPSHOT keeps the author's display name at write time, so the trail reads
-- the same after a rename, and carries the name of an imported comment, whose AUTHOR_ID is NULL.
-- The 20 000-character cap is the application's (CaseComment.MAX_BODY): a CHECK on LENGTH(BODY) of a CLOB makes every
-- INSERT fail with ORA-00600 [kkdcsaccc2] on Oracle XE 21, the image closest to a 19c target.
CREATE TABLE CASE_COMMENT (
  ID                   ${id}        NOT NULL,
  CASE_ID              ${id}        NOT NULL,
  BODY                 ${clob}      NOT NULL,
  AUTHOR_ID            ${id},
  AUTHOR_NAME_SNAPSHOT VARCHAR(200) NOT NULL,
  CREATED_AT           ${tstz}      NOT NULL,
  CONSTRAINT PK_CASE_COMMENT PRIMARY KEY (ID),
  CONSTRAINT FK_CASE_COMMENT_CASE FOREIGN KEY (CASE_ID) REFERENCES CASE_RECORD (ID),
  CONSTRAINT FK_CASE_COMMENT_AUTHOR FOREIGN KEY (AUTHOR_ID) REFERENCES APP_USER (ID)
);
CREATE INDEX IX_CASE_COMMENT_CASE   ON CASE_COMMENT (CASE_ID, CREATED_AT);
CREATE INDEX IX_CASE_COMMENT_AUTHOR ON CASE_COMMENT (AUTHOR_ID);

CREATE SEQUENCE SEQ_CASE_COMMENT START WITH 1 INCREMENT BY 50;
