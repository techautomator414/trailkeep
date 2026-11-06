-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V4.7 saved views (WP-15 part 3), shipped with the SavedView entity (BUILD_PLAN §4.4 "Save View"). Version 4.6 is
-- reserved for a fix landing in parallel.
--
-- A saved view is private to the person who saved it. QUERY holds the canonical cases-list query string of the filter
-- and the order on page 1 (CaseListQuery.toQueryString), the same contract as the URL and a bookmark, so a view is
-- re-read through CaseListQuery.parse and the person's CaseScope like any typed URL and can never widen what they
-- read. NAME is stored stripped, 1 to 100 characters, unique per person. The runtime user may SELECT, INSERT and
-- DELETE (R__grants.sql): views are saved and deleted, never changed.
CREATE TABLE SAVED_VIEW (
  ID         ${id}         NOT NULL,
  USER_ID    ${id}         NOT NULL,
  NAME       VARCHAR(100)  NOT NULL,
  QUERY      VARCHAR(1000) NOT NULL,
  CREATED_AT ${tstz}       NOT NULL,
  CONSTRAINT PK_SAVED_VIEW PRIMARY KEY (ID),
  CONSTRAINT UK_SAVED_VIEW_USER_NAME UNIQUE (USER_ID, NAME),
  CONSTRAINT FK_SAVED_VIEW_USER FOREIGN KEY (USER_ID) REFERENCES APP_USER (ID)
);

CREATE SEQUENCE SEQ_SAVED_VIEW START WITH 1 INCREMENT BY 50;
