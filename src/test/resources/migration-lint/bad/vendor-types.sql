-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- Vendor types written directly instead of through a placeholder.
-- expect: VENDOR_TYPE BIGINT
-- expect: VENDOR_TYPE BOOLEAN
-- expect: VENDOR_TYPE TEXT
-- expect: VENDOR_TYPE BYTEA
-- expect: VENDOR_TYPE BLOB
-- expect: VENDOR_TYPE CLOB
-- expect: VENDOR_TYPE TIMESTAMPTZ
-- expect: VENDOR_TYPE VARCHAR2
-- expect: VENDOR_TYPE NUMBER
-- expect: VENDOR_TYPE TIMESTAMP
CREATE TABLE TYPED_THING (
  ID         BIGINT NOT NULL,
  FLAG       boolean,
  BODY       Text,
  CONTENT    BYTEA,
  CHUNK      BLOB,
  NOTES      CLOB,
  SEEN_AT    TIMESTAMPTZ,
  LABEL      VARCHAR2(10),
  AMOUNT     NUMBER(10,0),
  CREATED_AT TIMESTAMP WITH TIME ZONE
);
