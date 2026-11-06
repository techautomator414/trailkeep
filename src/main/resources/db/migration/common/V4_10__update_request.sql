-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V4.10 update requests (WP-18 part a), shipped with the UpdateRequest entity (BUILD_PLAN §4.3 "Request Update", §5).
-- A request asks one person who can read the case for an update. It is open until that person comments on the case
-- (auto-resolve: ANSWERED_BY_COMMENT_ID names the comment) or the requester or a Manager marks it answered.
-- MESSAGE is possible PHI like a comment: it is shown on the case page only, redacted in the audit trail and never put
-- in a mail (BUILD_PLAN §9). LAST_REMINDED_AT is the reminders tick's (WP-17b, Phase 4).
--
-- OPEN_KEY holds "<case id>:<recipient id>" while the request is open and NULL once answered: its unique constraint
-- allows one open request per case and recipient on both vendors (a single-column unique key ignores NULLs on Oracle
-- and PostgreSQL alike, which a composite key with a nullable column does not on Oracle). The runtime user may
-- SELECT, INSERT and UPDATE, never DELETE (R__grants.sql): a request is case history.
CREATE TABLE UPDATE_REQUEST (
  ID                     ${id}       NOT NULL,
  CASE_ID                ${id}       NOT NULL,
  REQUESTED_BY           ${id}       NOT NULL,
  REQUESTED_FROM         ${id}       NOT NULL,
  MESSAGE                ${clob}     NOT NULL,
  REQUESTED_AT           ${tstz}     NOT NULL,
  LAST_REMINDED_AT       ${tstz},
  ANSWERED_AT            ${tstz},
  ANSWERED_BY            ${id},
  ANSWERED_BY_COMMENT_ID ${id},
  OPEN_KEY               VARCHAR(41),
  CONSTRAINT PK_UPDATE_REQUEST PRIMARY KEY (ID),
  CONSTRAINT UK_UPDATE_REQUEST_OPEN UNIQUE (OPEN_KEY),
  CONSTRAINT FK_UPDATE_REQUEST_CASE FOREIGN KEY (CASE_ID) REFERENCES CASE_RECORD (ID),
  CONSTRAINT FK_UPDATE_REQUEST_BY FOREIGN KEY (REQUESTED_BY) REFERENCES APP_USER (ID),
  CONSTRAINT FK_UPDATE_REQUEST_FROM FOREIGN KEY (REQUESTED_FROM) REFERENCES APP_USER (ID),
  CONSTRAINT FK_UPDATE_REQUEST_ANSWERER FOREIGN KEY (ANSWERED_BY) REFERENCES APP_USER (ID),
  CONSTRAINT FK_UPDATE_REQUEST_COMMENT FOREIGN KEY (ANSWERED_BY_COMMENT_ID) REFERENCES CASE_COMMENT (ID),
  CONSTRAINT CK_UPDATE_REQUEST_OPEN CHECK ((ANSWERED_AT IS NULL AND OPEN_KEY IS NOT NULL AND ANSWERED_BY IS NULL)
    OR (ANSWERED_AT IS NOT NULL AND OPEN_KEY IS NULL AND ANSWERED_BY IS NOT NULL))
);
-- A case's requests; a person's open requests (the dashboard's "Needs attention").
CREATE INDEX IX_UPDATE_REQUEST_CASE ON UPDATE_REQUEST (CASE_ID);
CREATE INDEX IX_UPDATE_REQUEST_FROM ON UPDATE_REQUEST (REQUESTED_FROM, ANSWERED_AT);

CREATE SEQUENCE SEQ_UPDATE_REQUEST START WITH 1 INCREMENT BY 50;
