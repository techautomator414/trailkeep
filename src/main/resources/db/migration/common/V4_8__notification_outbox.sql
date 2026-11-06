-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V4.8 notification outbox (WP-17 part a), shipped with the NotificationOutbox entity (BUILD_PLAN §5, §6 "Outbox
-- dispatcher"). Every email is first a row here, written in the transaction of the change it reports, so the change
-- and its email commit together; the dispatcher (WP-17 part b) claims rows with OutboxJdbcDao's two-step native claim,
-- sends outside any transaction and marks them SENT, or PENDING again with a later NEXT_ATTEMPT_AT, or FAILED after the
-- last attempt. Delivery is at-least-once (IMPLEMENTATION_PLAN A.1 #14).
--
-- SUBJECT is the mail's subject line, rendered from a template; it never holds a case's subject text, and neither body
-- holds the case's description or subject (BUILD_PLAN §9): mails carry a deep link instead. REPORT_RUN_ID names the
-- report a scheduled mail delivers; its table and foreign key arrive with WP-23. The runtime user may SELECT, INSERT
-- and UPDATE, never DELETE; trimming sent rows belongs to the purge user (R__grants.sql, BUILD_PLAN §8 rule 7).
CREATE TABLE NOTIFICATION_OUTBOX (
  ID              ${id}        NOT NULL,
  RECIPIENT       VARCHAR(320) NOT NULL,
  TEMPLATE_ID     VARCHAR(60)  NOT NULL,
  SUBJECT         VARCHAR(200) NOT NULL,
  BODY_TEXT       ${clob}      NOT NULL,
  BODY_HTML       ${clob}      NOT NULL,
  CASE_ID         ${id},
  REPORT_RUN_ID   ${id},
  STATUS          VARCHAR(10)  DEFAULT 'PENDING' NOT NULL,
  ATTEMPTS        ${int}       DEFAULT 0 NOT NULL,
  NEXT_ATTEMPT_AT ${tstz}      NOT NULL,
  LOCKED_UNTIL    ${tstz},
  SENT_AT         ${tstz},
  LAST_ERROR      VARCHAR(500),
  CREATED_AT      ${tstz}      NOT NULL,
  CONSTRAINT PK_NOTIFICATION_OUTBOX PRIMARY KEY (ID),
  CONSTRAINT FK_OUTBOX_CASE FOREIGN KEY (CASE_ID) REFERENCES CASE_RECORD (ID),
  CONSTRAINT CK_OUTBOX_STATUS CHECK (STATUS IN ('PENDING','SENDING','SENT','FAILED')),
  CONSTRAINT CK_OUTBOX_ATTEMPTS CHECK (ATTEMPTS >= 0),
  CONSTRAINT CK_OUTBOX_SENDING CHECK (STATUS <> 'SENDING' OR LOCKED_UNTIL IS NOT NULL),
  CONSTRAINT CK_OUTBOX_SENT CHECK (STATUS <> 'SENT' OR SENT_AT IS NOT NULL)
);
-- The claim's candidate query and the reaper (STATUS, NEXT_ATTEMPT_AT / LOCKED_UNTIL); the case's mails.
CREATE INDEX IX_OUTBOX_STATUS_NEXT ON NOTIFICATION_OUTBOX (STATUS, NEXT_ATTEMPT_AT);
CREATE INDEX IX_OUTBOX_CASE        ON NOTIFICATION_OUTBOX (CASE_ID);

CREATE SEQUENCE SEQ_NOTIFICATION_OUTBOX START WITH 1 INCREMENT BY 50;
