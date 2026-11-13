// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.Reconciliation.Difference;
import io.trailkeep.shared.csv.CsvFormat;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code reconcile}'s report files (WP-26 part aa3, design §9): {@code reconcile.csv} and {@code reconcile.html} in
 * {@code <import.reports-dir>/<run id>/}, on the Job's run volume, for the operator out of band. They hold what the
 * run's figures and issues hold, the counts per entity and outcome, the verdict and each difference, and nothing else:
 * codes, numbers, keyed hashes and Trailkeep ids, never a legacy key or value. The CSV is written through {@link
 * CsvFormat} (quoted, formula cells defused, BOM); the HTML is one static, escaped page with no script and no external
 * resource, its tables captioned with header scopes.
 *
 * <p>No symbolic link is followed: the reports directory and the run's folder must be directories themselves, and a
 * report file already there is deleted (a link is deleted, never its target) before the new one is created, which
 * fails if anything appeared meanwhile. Where the file system has POSIX permissions the folder and files are
 * owner-only.
 */
final class ReconcileReport {

    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private ReconcileReport() {}

    /**
     * The run's report folder, checked before {@code reconcile} reads anything and made if missing.
     *
     * @param reports {@code import.reports-dir}
     * @param runId the run, which names the folder
     * @return the folder
     * @throws IllegalStateException when {@code reports} is unset or not a directory, or the run's folder is not one
     */
    static Path folder(@Nullable Path reports, long runId) {
        if (reports == null || !Files.isDirectory(reports, NO_FOLLOW)) {
            throw new IllegalStateException("import.reports-dir must name a directory (not a symbolic link) on the run"
                    + " volume, where reconcile writes its report files");
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
     * Writes both files, replacing those of an earlier reconcile of the run.
     *
     * @param folder the run's report folder, from {@link #folder}
     * @param runId the run
     * @param found what the reconcile found
     * @param staged whether staging was there to compare with
     * @param reconciled the verdict
     */
    static void write(Path folder, long runId, Reconciliation found, boolean staged, boolean reconciled) {
        file(folder, "reconcile.csv", CsvFormat.BOM + csv(found, staged, reconciled));
        file(folder, "reconcile.html", html(runId, found, staged, reconciled));
    }

    /** One row per count, the verdict, then one row per difference. */
    static String csv(Reconciliation found, boolean staged, boolean reconciled) {
        StringBuilder csv = new StringBuilder(
                CsvFormat.line(List.of("row", "entity", "outcome", "rows", "key_hash", "trailkeep_id")));
        found.counts()
                .forEach((entity, byOutcome) -> byOutcome.forEach((outcome, count) -> csv.append(
                        CsvFormat.line(List.of("count", entity.name(), outcome, Long.toString(count), "", "")))));
        csv.append(CsvFormat.line(List.of("verdict", "", verdict(staged, reconciled), "", "", "")));
        for (Difference difference : found.differing()) {
            csv.append(CsvFormat.line(List.of(
                    "difference",
                    difference.entity().name(),
                    difference.code(),
                    "",
                    difference.keyHash(),
                    id(difference.trailkeepId()))));
        }
        return csv.toString();
    }

    /** The page: the verdict, a table of counts and a table of differences. */
    static String html(long runId, Reconciliation found, boolean staged, boolean reconciled) {
        String title = "Reconciliation of import run " + runId;
        List<List<String>> counts = new ArrayList<>();
        found.counts()
                .forEach((entity, byOutcome) -> byOutcome.forEach(
                        (outcome, count) -> counts.add(List.of(entity.name(), outcome, Long.toString(count)))));
        List<List<String>> differences = found.differing().stream()
                .map(row -> List.of(row.entity().name(), row.code(), row.keyHash(), id(row.trailkeepId())))
                .toList();
        return "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'\">\n"
                + "<title>" + escape(title) + "</title>\n</head>\n<body>\n<main>\n<h1>" + escape(title) + "</h1>\n"
                + "<p>Verdict: " + escape(verdict(staged, reconciled)) + ". Keyed hashes and Trailkeep ids only; no"
                + " legacy key or value.</p>\n"
                + table("Rows per entity and outcome", List.of("Entity", "Outcome", "Rows"), counts)
                + table("Rows that differ", List.of("Entity", "Code", "Keyed hash", "Trailkeep id"), differences)
                + "</main>\n</body>\n</html>\n";
    }

    private static String table(String caption, List<String> headings, List<List<String>> rows) {
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

    private static String verdict(boolean staged, boolean reconciled) {
        return reconciled ? "RECONCILED" : staged ? "NOT_RECONCILED" : "NOT_RECONCILED (staging purged)";
    }

    private static String id(@Nullable Long trailkeepId) {
        return trailkeepId == null ? "" : Long.toString(trailkeepId);
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text, StandardCharsets.UTF_8.name());
    }

    private static void file(Path folder, String name, String content) {
        Path file = folder.resolve(name);
        Set<OpenOption> options =
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            Files.deleteIfExists(file);
            try (SeekableByteChannel channel = Files.newByteChannel(file, options, ownerOnly(folder, false))) {
                ByteBuffer bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            }
        } catch (FileAlreadyExistsException raced) {
            throw new IllegalStateException("A report file appeared while reconcile replaced it: " + name, raced);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the report file " + name, e);
        }
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
