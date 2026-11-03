-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- Quoted identifiers are case-sensitive on both vendors and would smuggle reserved words past the lint.
-- expect: QUOTED_IDENTIFIER "user"
-- expect: QUOTED_IDENTIFIER "Mixed_Case"
CREATE TABLE "user" (
  ID           ${id} NOT NULL,
  "Mixed_Case" VARCHAR(10)
);
