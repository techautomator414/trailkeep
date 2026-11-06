-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V4.9 in-app alerts (WP-17 part c), shipped with the UserAlert entity (BUILD_PLAN §4.7 "email + in-app list", §5).
-- An alert is written by NotificationService in the same transaction as the outbox row for the same event, so a person
-- sees in the application what they were mailed. MESSAGE is rendered from the mail's alert template: like the mail it
-- carries a case number, a title and who did what, never a case's description or subject text (BUILD_PLAN §9). LINK is
-- the path of the page it is about, inside the application. READ_AT is set only by the person's "Mark read".
-- The runtime user may SELECT, INSERT, UPDATE (mark read) and DELETE (a person deletes their own alerts; BUILD_PLAN §8
-- rule 7 lists USER_ALERT among the tables it may delete from).
CREATE TABLE USER_ALERT (
  ID         ${id}        NOT NULL,
  USER_ID    ${id}        NOT NULL,
  MESSAGE    VARCHAR(300) NOT NULL,
  LINK       VARCHAR(500) NOT NULL,
  CASE_ID    ${id},
  READ_AT    ${tstz},
  CREATED_AT ${tstz}      NOT NULL,
  CONSTRAINT PK_USER_ALERT PRIMARY KEY (ID),
  CONSTRAINT FK_USER_ALERT_USER FOREIGN KEY (USER_ID) REFERENCES APP_USER (ID),
  CONSTRAINT FK_USER_ALERT_CASE FOREIGN KEY (CASE_ID) REFERENCES CASE_RECORD (ID)
);
-- A person's alerts newest first, and their unread count.
CREATE INDEX IX_USER_ALERT_USER ON USER_ALERT (USER_ID, CREATED_AT);
CREATE INDEX IX_USER_ALERT_CASE ON USER_ALERT (CASE_ID);

CREATE SEQUENCE SEQ_USER_ALERT START WITH 1 INCREMENT BY 50;
