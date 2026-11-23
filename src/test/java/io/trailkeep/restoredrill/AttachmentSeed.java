// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.restoredrill;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Attachments for the drill, stored as the application stores them (BUILD_PLAN §5: in the database, a {@code
 * CASE_ATTACHMENT} row and 1 MiB {@code CASE_ATTACHMENT_CHUNK} rows, as {@code AttachmentContentJdbcDao} writes), and
 * the check that every stored attachment's chunks still add up to its recorded size and SHA-256.
 *
 * <p>{@value #FILES} files, as many as the importer volume run's legacy tracker has: one case in 25 has one. Most are 4
 * to 260 KiB; every 50th is 1 to 4 MiB, so a file spans several chunks. The bytes are pseudo-random from the file's id,
 * so they do not compress, as most real attachments (PDF, images, office files) do not.
 */
final class AttachmentSeed {

    static final int FILES = 2_000;

    /** {@code AttachmentContentJdbcDao.CHUNK_SIZE}. */
    static final int CHUNK = 1024 * 1024;

    private static final long BASE = 900_000_000L;
    private static final int COMMIT_EVERY = 50;

    private AttachmentSeed() {}

    /**
     * The id of seeded file {@code i}.
     *
     * @param i 1 to {@value #FILES}
     * @return its {@code CASE_ATTACHMENT.ID}
     */
    static long id(int i) {
        return BASE + i;
    }

    /**
     * The case number seeded file {@code i} belongs to, one of the 50k seed's.
     *
     * @param i 1 to {@value #FILES}
     * @return the case number
     */
    static String caseNumber(int i) {
        return "PERF-2026-%06d".formatted(caseIndex(i));
    }

    private static int caseIndex(int i) {
        return 1 + (i - 1) * 25;
    }

    static int size(int i) {
        return i % 50 == 0 ? CHUNK + (int) ((i * 7919L) % (3L * CHUNK)) : 4096 + (int) ((i * 104_729L) % (256 * 1024));
    }

    /**
     * Writes the files, committing every {@value #COMMIT_EVERY}.
     *
     * @param owner a connection as the schema owner, in its schema; auto-commit is turned off
     * @return the number of content bytes written
     * @throws SQLException if a row cannot be written
     */
    static long write(Connection owner) throws SQLException {
        owner.setAutoCommit(false);
        long bytes = 0;
        try (PreparedStatement attachment = owner.prepareStatement("INSERT INTO CASE_ATTACHMENT (ID, CASE_ID,"
                        + " FILE_NAME, CONTENT_TYPE, SIZE_BYTES, SHA256, SCAN_STATUS, UPLOADED_AT, UPLOADED_BY)"
                        + " VALUES (?, ?, ?, 'application/pdf', ?, ?, 'CLEAN', CURRENT_TIMESTAMP, ?)");
                PreparedStatement chunk = owner.prepareStatement(
                        "INSERT INTO CASE_ATTACHMENT_CHUNK (ATTACHMENT_ID, SEQ, CHUNK) VALUES (?, ?, ?)")) {
            for (int i = 1; i <= FILES; i++) {
                byte[] content = new byte[size(i)];
                new SplittableRandom(id(i)).nextBytes(content);
                attachment.setLong(1, id(i));
                attachment.setLong(2, BASE + caseIndex(i));
                attachment.setString(3, "evidence-%04d.pdf".formatted(i));
                attachment.setLong(4, content.length);
                attachment.setString(5, HexFormat.of().formatHex(sha256(content)));
                attachment.setLong(6, BASE + 1 + i % 200);
                attachment.executeUpdate();
                for (int seq = 0, from = 0; from < content.length; seq++, from += CHUNK) {
                    chunk.setLong(1, id(i));
                    chunk.setInt(2, seq);
                    chunk.setBytes(3, Arrays.copyOfRange(content, from, Math.min(content.length, from + CHUNK)));
                    chunk.executeUpdate();
                }
                bytes += content.length;
                if (i % COMMIT_EVERY == 0) {
                    owner.commit();
                }
            }
            owner.commit();
        } finally {
            owner.setAutoCommit(true);
        }
        return bytes;
    }

    /**
     * The outcome of checking every stored attachment.
     *
     * @param files how many attachments were read
     * @param bytes their content bytes
     * @param problems one line per attachment whose chunks do not match its row (gaps, size, SHA-256)
     */
    record Check(long files, long bytes, List<String> problems) {}

    /**
     * Reads every attachment's chunks in order and compares them with the attachment's recorded size and SHA-256.
     *
     * @param connection a connection that may read both tables
     * @param schema the qualifier for the tables
     * @return the outcome
     * @throws SQLException if the tables cannot be read
     */
    static Check check(Connection connection, String schema) throws SQLException {
        List<String> problems = new ArrayList<>();
        long files = 0;
        long bytes = 0;
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(16);
            // Test-only SQL; the schema is the drill's own owner schema.
            try (ResultSet rows = statement.executeQuery("SELECT a.ID, a.SIZE_BYTES, a.SHA256, c.SEQ, c.CHUNK FROM "
                    + schema + ".CASE_ATTACHMENT a LEFT JOIN " + schema + ".CASE_ATTACHMENT_CHUNK c"
                    + " ON c.ATTACHMENT_ID = a.ID ORDER BY a.ID, c.SEQ")) {
                Running current = null;
                while (rows.next()) {
                    long id = rows.getLong(1);
                    if (current == null || current.id != id) {
                        if (current != null) {
                            current.finish(problems);
                        }
                        current = new Running(id, rows.getLong(2), rows.getString(3));
                        files++;
                    }
                    byte[] chunk = rows.getBytes(5);
                    if (chunk != null) {
                        current.add(rows.getInt(4), chunk, problems);
                        bytes += chunk.length;
                    }
                }
                if (current != null) {
                    current.finish(problems);
                }
            }
        }
        return new Check(files, bytes, problems);
    }

    static byte[] sha256(byte[] content) {
        return DatabaseFingerprint.sha256().digest(content);
    }

    /** One attachment being read: its expected size and hash, and what its chunks added up to so far. */
    private static final class Running {
        private final long id;
        private final long size;
        private final String sha256;
        private final MessageDigest digest = DatabaseFingerprint.sha256();
        private long read;
        private int nextSeq;

        Running(long id, long size, String sha256) {
            this.id = id;
            this.size = size;
            this.sha256 = sha256;
        }

        void add(int seq, byte[] chunk, List<String> problems) {
            if (seq != nextSeq) {
                problems.add(id + ": chunk " + seq + " where " + nextSeq + " was expected");
            }
            nextSeq = seq + 1;
            read += chunk.length;
            digest.update(chunk);
        }

        void finish(List<String> problems) {
            if (read != size) {
                problems.add(id + ": " + read + " bytes stored, " + size + " recorded");
            } else if (!HexFormat.of().formatHex(digest.digest()).equals(sha256)) {
                problems.add(id + ": SHA-256 of the stored bytes differs from the recorded one");
            }
        }
    }
}
