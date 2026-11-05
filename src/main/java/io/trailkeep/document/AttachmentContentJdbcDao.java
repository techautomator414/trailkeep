// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes and reads an attachment's content as {@code CASE_ATTACHMENT_CHUNK} rows of at most {@value #CHUNK_SIZE}
 * bytes (ADR-0004, IMPLEMENTATION_PLAN A.1 #3). Native SQL because binary content is never a JPA field: pgjdbc
 * materialises a whole {@code BYTEA} value on read and an Oracle {@code BLOB} needs its connection open while it is
 * read, so the only way to bound heap on both vendors is a row per chunk, read one row per query by {@code SEQ}. The
 * statements are constant.
 *
 * <p>Both directions bind and read a chunk as a binary stream of known length, which both drivers accept for a
 * {@code BLOB} and a {@code BYTEA} column alike; {@code OracleAttachmentContentIT} proves it on Oracle. Every method
 * runs in its caller's transaction, whose connection stays open while a chunk is copied.
 */
@Repository
class AttachmentContentJdbcDao {

    /** The largest chunk, 1 MiB. */
    static final int CHUNK_SIZE = 1024 * 1024;

    private static final String INSERT =
            "INSERT INTO CASE_ATTACHMENT_CHUNK (ATTACHMENT_ID, SEQ, CHUNK) VALUES (?, ?, ?)";
    private static final String SELECT = "SELECT CHUNK FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID = ? AND SEQ = ?";

    private final JdbcTemplate jdbc;

    AttachmentContentJdbcDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The number of chunks content of this size is stored in; an empty file has none.
     *
     * @param sizeBytes the content's size
     * @return the chunk count
     */
    static int chunkCount(long sizeBytes) {
        return Math.toIntExact((sizeBytes + CHUNK_SIZE - 1) / CHUNK_SIZE);
    }

    /**
     * Stores content as chunks numbered from 0. Only one chunk is held in memory at a time.
     *
     * @param attachmentId the attachment, already flushed to the database
     * @param content the content, read to its end; the caller closes it
     * @return the number of bytes stored
     * @throws IOException if the content cannot be read
     */
    long write(long attachmentId, InputStream content) throws IOException {
        byte[] buffer = new byte[CHUNK_SIZE];
        long total = 0;
        int seq = 0;
        for (int filled = content.readNBytes(buffer, 0, CHUNK_SIZE);
                filled > 0;
                filled = content.readNBytes(buffer, 0, CHUNK_SIZE)) {
            int length = filled;
            int index = seq;
            jdbc.update(INSERT, statement -> {
                statement.setLong(1, attachmentId);
                statement.setInt(2, index);
                statement.setBinaryStream(3, new ByteArrayInputStream(buffer, 0, length), length);
            });
            total += length;
            seq++;
        }
        return total;
    }

    /**
     * Copies stored content to a stream, one chunk per query, in order.
     *
     * @param attachmentId the attachment
     * @param sizeBytes the attachment's recorded size, which fixes the number of chunks read
     * @param out where the bytes go; not closed
     * @throws EmptyResultDataAccessException if a chunk is missing, so a damaged file is never served truncated
     * @throws UncheckedIOException if writing to {@code out} fails, for example because the client went away
     */
    void copy(long attachmentId, long sizeBytes, OutputStream out) {
        int chunks = chunkCount(sizeBytes);
        for (int seq = 0; seq < chunks; seq++) {
            Boolean found = jdbc.query(
                    SELECT,
                    rows -> {
                        if (!rows.next()) {
                            return false;
                        }
                        try (InputStream chunk = rows.getBinaryStream(1)) {
                            chunk.transferTo(out);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        return true;
                    },
                    attachmentId,
                    seq);
            if (!Boolean.TRUE.equals(found)) {
                throw new EmptyResultDataAccessException("attachment " + attachmentId + " misses chunk " + seq, 1);
            }
        }
    }
}
