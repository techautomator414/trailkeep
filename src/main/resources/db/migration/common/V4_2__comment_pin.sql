-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V4.2 comment pins (WP-13 part 3), shipped with the CaseCommentPin entity (BUILD_PLAN §4.3, §5.1: a comment has
-- zero or one pin). Pins live in their own table so CASE_COMMENT stays insert-only; the runtime user may SELECT,
-- INSERT and DELETE pins (R__grants.sql), and unpinning deletes the row.
--
-- A pin names its comment and that comment's case. The composite foreign key on (COMMENT_ID, CASE_ID) makes a pin
-- that names a comment of another case impossible in the database, not only in the service; it needs the matching
-- unique key on CASE_COMMENT, which adds a constraint and changes no column or row.
ALTER TABLE CASE_COMMENT ADD CONSTRAINT UK_CASE_COMMENT_CASE UNIQUE (ID, CASE_ID);

CREATE TABLE CASE_COMMENT_PIN (
  COMMENT_ID ${id}   NOT NULL,
  CASE_ID    ${id}   NOT NULL,
  PINNED_BY  ${id}   NOT NULL,
  PINNED_AT  ${tstz} NOT NULL,
  CONSTRAINT PK_CASE_COMMENT_PIN PRIMARY KEY (COMMENT_ID),
  CONSTRAINT FK_COMMENT_PIN_COMMENT FOREIGN KEY (COMMENT_ID, CASE_ID) REFERENCES CASE_COMMENT (ID, CASE_ID),
  CONSTRAINT FK_COMMENT_PIN_CASE FOREIGN KEY (CASE_ID) REFERENCES CASE_RECORD (ID),
  CONSTRAINT FK_COMMENT_PIN_BY FOREIGN KEY (PINNED_BY) REFERENCES APP_USER (ID)
);
CREATE INDEX IX_COMMENT_PIN_CASE ON CASE_COMMENT_PIN (CASE_ID);
CREATE INDEX IX_COMMENT_PIN_BY   ON CASE_COMMENT_PIN (PINNED_BY);
