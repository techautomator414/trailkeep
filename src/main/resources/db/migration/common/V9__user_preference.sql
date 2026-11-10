-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V9 user preferences (WP-27), shipped with the UserPreference entity (BUILD_PLAN §3.1, §10.1 "Keyboard"). One row per
-- person, created the first time they save the preferences page; a person with no row has the defaults, so adding a
-- preference later is a new column with a default, not a data migration. SHORTCUTS_ENABLED is the single-key
-- shortcuts switch (WCAG 2.1.4): on by default. The runtime user may SELECT, INSERT and UPDATE, never DELETE
-- (R__grants.sql).
CREATE TABLE USER_PREFERENCE (
  USER_ID           ${id}   NOT NULL,
  SHORTCUTS_ENABLED ${bool} DEFAULT ${true} NOT NULL,
  UPDATED_AT        ${tstz} NOT NULL,
  CONSTRAINT PK_USER_PREFERENCE PRIMARY KEY (USER_ID),
  CONSTRAINT FK_USER_PREFERENCE_USER FOREIGN KEY (USER_ID) REFERENCES APP_USER (ID)
);
