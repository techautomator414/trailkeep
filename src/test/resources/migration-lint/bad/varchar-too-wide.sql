-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- Character columns wider than 1000 (Oracle's 4000-byte VARCHAR2 ceiling at 4 bytes per character).
-- expect: VARCHAR_TOO_WIDE VARCHAR(1001)
-- expect: VARCHAR_TOO_WIDE VARCHAR(4000)
-- expect: VARCHAR_TOO_WIDE CHAR(2000)
-- expect: VARCHAR_TOO_WIDE NVARCHAR(1200)
CREATE TABLE WIDE_THING (
  ID        ${id} NOT NULL,
  AT_LIMIT  VARCHAR(1000),
  TOO_WIDE  VARCHAR(1001),
  FAR_WIDER varchar ( 4000 ),
  FIXED     CHAR(2000),
  NATIONAL  NVARCHAR(1200)
);
