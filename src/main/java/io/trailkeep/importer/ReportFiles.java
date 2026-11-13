// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.web.util.HtmlUtils;

/**
 * Where and how the importer writes its report files (design §9): {@code dry-run}'s preview ({@link DryRunReport}) and
 * {@code reconcile}'s final report ({@link ReconcileReport}), in {@code <import.reports-dir>/<run id>/} on the Job's
 * reports claim, for the operator out of band. The pages are static and escaped, with no script and no external
 * resource, their tables captioned with header scopes.
 *
 * <p>No symbolic link is followed: the reports directory and the run's folder must be directories themselves, and a
 * report file already there is deleted (a link is deleted, never its target) before the new one is created, which
 * fails if anything appeared meanwhile. Where the file system has POSIX permissions the folder and files are
 * owner-only.
 */
final class ReportFiles {

    /** At most this many rows of a list whose length grows with the run go on a page; the CSV holds them all. */
    static final int PAGE_ROWS = 1_000;

    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private ReportFiles() {}

    /**
     * A table's caption, saying when the page shows only the first rows.
     *
     * @param caption what the table holds
     * @param shown how many rows the page shows
     * @param total how many there are
     * @param file the CSV file that lists them all
     * @return the caption
     */
    static String caption(String caption, int shown, long total, String file) {
        return total > shown
                ? caption + ": the first " + shown + " of " + total + "; the full list is in " + file
                : caption;
    }

    /**
     * The run's report folder, checked before a reporting command reads anything and made if missing.
     *
     * @param reports {@code import.reports-dir}
     * @param runId the run, which names the folder
     * @return the folder
     * @throws IllegalStateException when {@code reports} is unset or not a directory, or the run's folder is not one
     */
    static Path folder(@Nullable Path reports, long runId) {
        if (reports == null || !Files.isDirectory(reports, NO_FOLLOW)) {
            throw new IllegalStateException("import.reports-dir must name a directory (not a symbolic link) on the run"
                    + " volume, where dry-run and reconcile write their report files");
        }
        Path folder = reports.resolve(Long.toString(runId));
        try {
            if (!Files.exists(folder, NO_FOLLOW)) {
                Files.createDirectory(folder, ownerOnly(reports, true));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot make the report folder of import run " + runId, e);
        }
        if (!Files.isDirectory(folder, NO_FOLLOW)) {
            throw new IllegalStateException("The report folder of import run " + runId + " is not a directory");
        }
        return folder;
    }

    /**
     * A static page: one heading, one paragraph, then the tables.
     *
     * @param title the page's title and heading
     * @param intro the paragraph
     * @param tables the tables, from {@link #table}
     * @return the page
     */
    static String page(String title, String intro, List<String> tables) {
        return "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'\">\n"
                + "<title>" + escape(title) + "</title>\n</head>\n<body>\n<main>\n<h1>" + escape(title) + "</h1>\n"
                + "<p>" + escape(intro) + "</p>\n" + String.join("", tables) + "</main>\n</body>\n</html>\n";
    }

    /**
     * A captioned table whose first cell in each row heads it, with an explicit "None" row when empty.
     *
     * @param caption the caption
     * @param headings the column headings
     * @param rows the rows, each as wide as {@code headings}
     * @return the table
     */
    static String table(String caption, List<String> headings, List<List<String>> rows) {
        StringBuilder table = new StringBuilder("<table>\n<caption>" + escape(caption) + "</caption>\n<thead><tr>");
        headings.forEach(heading ->
                table.append("<th scope=\"col\">").append(escape(heading)).append("</th>"));
        table.append("</tr></thead>\n<tbody>\n");
        if (rows.isEmpty()) {
            table.append("<tr><td colspan=\"").append(headings.size()).append("\">None</td></tr>\n");
        }
        for (List<String> row : rows) {
            table.append("<tr><th scope=\"row\">")
                    .append(escape(row.getFirst()))
                    .append("</th>");
            row.subList(1, row.size())
                    .forEach(cell -> table.append("<td>").append(escape(cell)).append("</td>"));
            table.append("</tr>\n");
        }
        return table.append("</tbody>\n</table>\n").toString();
    }

    /**
     * Writes one report file, replacing an earlier one.
     *
     * @param folder the run's report folder, from {@link #folder}
     * @param name the file's name
     * @param content its content
     * @throws IllegalStateException if a file appeared while the old one was replaced
     */
    static void file(Path folder, String name, String content) {
        try (Lines file = lines(folder, name)) {
            file.write(content);
        }
    }

    /**
     * Opens one report file to be written piece by piece, replacing an earlier one, so a long list never needs to be
     * held whole.
     *
     * @param folder the run's report folder, from {@link #folder}
     * @param name the file's name
     * @return the open file, to be closed
     * @throws IllegalStateException if a file appeared while the old one was replaced
     */
    static Lines lines(Path folder, String name) {
        Path file = folder.resolve(name);
        Set<OpenOption> options =
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            Files.deleteIfExists(file);
            return new Lines(name, Files.newByteChannel(file, options, ownerOnly(folder, false)));
        } catch (FileAlreadyExistsException raced) {
            throw new IllegalStateException("A report file appeared while it was replaced: " + name, raced);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the report file " + name, e);
        }
    }

    /** A report file open for writing, as UTF-8 text. */
    static final class Lines implements AutoCloseable {

        private final String name;
        private final SeekableByteChannel channel;

        private Lines(String name, SeekableByteChannel channel) {
            this.name = name;
            this.channel = channel;
        }

        /**
         * Appends text.
         *
         * @param text the text
         */
        void write(String text) {
            ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            try {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write the report file " + name, e);
            }
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write the report file " + name, e);
            }
        }
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text, StandardCharsets.UTF_8.name());
    }

    /** Owner-only permissions where the file system supports POSIX permissions; none otherwise. */
    private static FileAttribute<?>[] ownerOnly(Path near, boolean folder) {
        Set<PosixFilePermission> permissions =
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        if (folder) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
        }
        return near.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(permissions)}
                : new FileAttribute<?>[0];
    }
}
