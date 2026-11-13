-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V19 written hashes in the import ledger (WP-26 part aa1, docs/design/importer.md §5, §9), shipped with the
-- ImportLedgerEntry entity.
--
-- WRITTEN_HASH is the keyed hash of what a ledger row's load wrote, read back from the database in the load's own
-- transaction (WrittenForm): for a case its row and the comments its description continued in, for a comment every
-- part it was split into, for an attachment or event its row. CLOSURE_HASH is the same for a case's imported closure.
-- reconcile recomputes both from Trailkeep and compares. Both are null on rows loaded before V19, which reconcile
-- reports as such. Insert-only, like the rest of the ledger: the table grants are unchanged (import S I, app S).
--
-- IMPORT_LEDGER_PART: the comments a ledger row wrote besides its TARGET_ID, in the order written, so reconcile finds
-- exactly those rows again: a split comment's later parts (TARGET_ID is the first) and a case's description
-- continuation (TARGET_ID is the case). A comment row has no link to them otherwise: ids are not contiguous across
-- nodes, and a delta load or a person may add comments to the same case. COMMENT_ID has no foreign key, like
-- TARGET_ID, because the retention purge deletes comments and the ledger is kept. Insert-only: import S I, app S.
ALTER TABLE IMPORT_LEDGER ADD WRITTEN_HASH VARCHAR(64);
ALTER TABLE IMPORT_LEDGER ADD CLOSURE_HASH VARCHAR(64);

CREATE TABLE IMPORT_LEDGER_PART (
  LEDGER_ID  ${id}   NOT NULL,
  PART_NO    ${int}  NOT NULL,
  COMMENT_ID ${id}   NOT NULL,
  CONSTRAINT PK_IMPORT_LEDGER_PART PRIMARY KEY (LEDGER_ID, PART_NO),
  CONSTRAINT FK_IMPORT_LEDGER_PART_LEDGER FOREIGN KEY (LEDGER_ID) REFERENCES IMPORT_LEDGER (ID)
);
