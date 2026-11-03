-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- TEST-ONLY migration (src/test, never packaged). Backs the LintProbe entity so that Hibernate validate and the
-- metadata-diff harness run against a real table before V1 ships in PR 6. Version 0.1 sorts before V1.
CREATE TABLE LINT_PROBE (
  ID     ${id}        NOT NULL,
  NAME   VARCHAR(50)  NOT NULL,
  ACTIVE ${bool}      DEFAULT ${false} NOT NULL,
  NOTE   VARCHAR(200),
  CONSTRAINT PK_LINT_PROBE PRIMARY KEY (ID)
);
