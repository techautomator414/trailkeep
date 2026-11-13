// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.shared.csv.CsvFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code reconcile}'s report files (WP-26 part aa3, design §9): only under {@code <import.reports-dir>/<run id>/},
 * which must be real directories, never through a symbolic link, owner-only; the CSV through the shared writer (BOM,
 * quoted, formula cells defused) and the HTML escaped, with no script, holding keyed hashes and Trailkeep ids, never a
 * key.
 */
class ReconcileReportTest {

    private static final long RUN = 42;

    @Test
    void anUnsetMissingOrLinkedReportsDirectoryIsRefusedClearly(@TempDir Path directory) throws Exception {
        Path file = Files.writeString(directory.resolve("file"), "x");
        Path folder = Files.createDirectory(directory.resolve("real"));
        Path link = Files.createSymbolicLink(directory.resolve("link"), folder);

        for (Path refused : new Path[] {null, directory.resolve("missing"), file, link}) {
            assertThatThrownBy(() -> ReconcileReport.folder(refused, RUN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("import.reports-dir must name a directory (not a symbolic link)");
        }
        assertThat(folder).isEmptyDirectory();
    }

    @Test
    void theRunsFolderIsMadeOwnerOnlyAndNeverFollowedWhenItIsLink(@TempDir Path directory) throws Exception {
        Path folder = ReconcileReport.folder(directory, RUN);

        assertThat(folder).isEqualTo(directory.resolve("42")).isDirectory();
        assumeThat(directory.getFileSystem().supportedFileAttributeViews()).contains("posix");
        assertThat(Files.getPosixFilePermissions(folder)).isEqualTo(PosixFilePermissions.fromString("rwx------"));

        Path elsewhere = Files.createDirectory(directory.resolve("elsewhere"));
        Files.createSymbolicLink(directory.resolve("7"), elsewhere);
        assertThatThrownBy(() -> ReconcileReport.folder(directory, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The report folder of import run 7 is not a directory");
    }

    @Test
    void reportFileThatIsLinkIsReplacedAndItsTargetLeftAlone(@TempDir Path directory) throws Exception {
        Path folder = ReconcileReport.folder(directory, RUN);
        Path target = Files.writeString(directory.resolve("target.txt"), "untouched");
        Files.createSymbolicLink(folder.resolve("reconcile.csv"), target);

        ReconcileReport.write(folder, RUN, tally(), true, false);

        assertThat(Files.readString(target)).isEqualTo("untouched");
        assertThat(Files.isSymbolicLink(folder.resolve("reconcile.csv"))).isFalse();
        assertThat(Files.readString(folder.resolve("reconcile.csv"), StandardCharsets.UTF_8))
                .startsWith(CsvFormat.BOM);
        assumeThat(directory.getFileSystem().supportedFileAttributeViews()).contains("posix");
        for (String name : new String[] {"reconcile.csv", "reconcile.html"}) {
            assertThat(Files.getPosixFilePermissions(folder.resolve(name), LinkOption.NOFOLLOW_LINKS))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void theCsvHoldsCountsTheVerdictAndKeyedDifferencesWithFormulaCellsDefused() {
        String csv = ReconcileReport.csv(tally(), true, false);

        assertThat(csv)
                .startsWith("\"row\",\"entity\",\"outcome\",\"rows\",\"key_hash\",\"trailkeep_id\"\r\n")
                .contains("\"count\",\"CASE\",\"WRITTEN_MATCH\",\"2\",\"\",\"\"\r\n")
                .contains("\"count\",\"CASE\",\"'=HYPERLINK(1)\",\"1\",\"\",\"\"\r\n")
                .contains("\"verdict\",\"\",\"NOT_RECONCILED\",\"\",\"\",\"\"\r\n")
                .contains("\"difference\",\"COMMENT\",\"WRITTEN_DIFFERS\",\"\",\"" + keyHash("n1") + "\",\"7\"\r\n")
                .doesNotContain("\"n1\"");
        assertThat(ReconcileReport.csv(tally(), false, false)).contains("NOT_RECONCILED (staging purged)");
    }

    @Test
    void thePageIsEscapedStaticAndLabelled() {
        String html = ReconcileReport.html(RUN, tally(), true, true);

        assertThat(html)
                .startsWith("<!DOCTYPE html>\n<html lang=\"en\">")
                .contains("<title>Reconciliation of import run 42</title>")
                .contains("default-src 'none'")
                .contains("<caption>Rows per entity and outcome</caption>", "<caption>Rows that differ</caption>")
                .contains("<th scope=\"col\">Keyed hash</th>", "<th scope=\"row\">COMMENT</th>")
                .contains("<td>&lt;b&gt;&amp;</td>")
                .contains("Verdict: RECONCILED.")
                .doesNotContain("<script", "<b>", ">n1<");
    }

    /** Two matching cases, a planted odd outcome and a formula, and one differing comment with id 7. */
    static Reconciliation tally() {
        Reconciliation found = new Reconciliation(
                RUN,
                new SecretKeySpec(ExtractJobSupport.HASH_KEY, "HmacSHA256"),
                Instant.parse("2026-11-13T00:00:00Z"));
        found.found(Subject.CASE, "WRITTEN_MATCH", "c1", 1L);
        found.found(Subject.CASE, "WRITTEN_MATCH", "c2", 2L);
        found.found(Subject.CASE, "<b>&", "c3", 3L);
        found.found(Subject.CASE, "=HYPERLINK(1)", "c4", 4L);
        found.found(Subject.COMMENT, "WRITTEN_DIFFERS", "n1", 7L);
        return found;
    }

    private static String keyHash(String key) {
        return KeyedHash.hex(new SecretKeySpec(ExtractJobSupport.HASH_KEY, "HmacSHA256"), key);
    }
}
