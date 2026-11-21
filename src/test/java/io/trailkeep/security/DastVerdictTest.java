// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the DAST verdict policy of {@code .github/scripts/dast-verdict.sh} on every PR (WP-28 part e), although ZAP runs
 * only nightly and on release tags: a partial scan (any of the plan's active scans cut off at its cap or its completion
 * missing, or the plan stopped by a High insight) warns in nightly and fails in release; everything else that fails,
 * fails in both.
 */
class DastVerdictTest {

    private static final Path SCRIPT = Path.of(".github/scripts/dast-verdict.sh");
    private static final String CAP = "7200";
    private static final String FINISHED = "5015.379";
    private static final String CUT_OFF = "7200.104";
    private static final String MEMORY = "insight.memory.usage at 99 (EXCEEDED_HIGH)";

    @TempDir
    private Path dir;

    @Test
    void completeCleanScanPassesInBothModes() throws IOException, InterruptedException {
        for (String mode : List.of("release", "nightly")) {
            Verdict verdict = run(mode, "0", "0", "", FINISHED);
            assertThat(verdict.exit()).isZero();
            assertThat(verdict.output()).doesNotContain("::error", "::warning");
        }
    }

    @Test
    void activeScanCutOffAtItsCapWarnsInNightlyAndFailsInRelease() throws IOException, InterruptedException {
        Verdict nightly = run("nightly", "0", "0", "", CUT_OFF);
        assertThat(nightly.exit()).isZero();
        assertThat(nightly.output()).contains("::warning", "cut off at its 120-minute cap");
        assertThat(nightly.summary()).contains("WARNING", "cut off");

        Verdict release = run("release", "0", "0", "", CUT_OFF);
        assertThat(release.exit()).isOne();
        assertThat(release.output()).contains("::error", "cut off at its 120-minute cap", "A release needs a complete");
        assertThat(release.summary()).contains("FAILED");
    }

    @Test
    void missingActiveScanCompletionCountsAsPartial() throws IOException, InterruptedException {
        assertThat(run("nightly", "0", "0", "", "").exit()).isZero();
        Verdict release = run("release", "0", "0", "", "");
        assertThat(release.exit()).isOne();
        assertThat(release.output()).contains("does not show the active scan completing");
    }

    @Test
    void planStoppedByHighInsightWarnsInNightlyAndFailsInRelease() throws IOException, InterruptedException {
        Verdict nightly = run("nightly", "2", "0", MEMORY, FINISHED);
        assertThat(nightly.exit()).isZero();
        assertThat(nightly.output()).contains("::warning", "High insight (" + MEMORY + ")");

        Verdict release = run("release", "2", "0", MEMORY, FINISHED);
        assertThat(release.exit()).isOne();
        assertThat(release.output()).contains("::error", "High insight");
    }

    @Test
    void mediumAlertFailsInBothModesEvenWhenTheScanIsPartial() throws IOException, InterruptedException {
        for (String mode : List.of("release", "nightly")) {
            assertThat(run(mode, "1", "2", "", FINISHED).exit()).isOne();
        }
        Verdict nightly = run("nightly", "1", "2", "", CUT_OFF);
        assertThat(nightly.exit()).isOne();
        assertThat(nightly.output()).contains("::warning", "cut off", "2 alert(s) of Medium or higher");
    }

    @Test
    void theGateAndTheReportMustAgreeInBothModes() throws IOException, InterruptedException {
        for (String mode : List.of("release", "nightly")) {
            assertThat(run(mode, "1", "0", "", FINISHED).output()).contains("::error", "disagree", "exited 1");
            assertThat(run(mode, "1", "0", "", FINISHED).exit()).isOne();
            assertThat(run(mode, "2", "1", "", FINISHED).output()).contains("::error", "disagree", "but ZAP exited 2");
            assertThat(run(mode, "0", "1", "", FINISHED).exit()).isOne();
        }
    }

    @Test
    void lowAlertsPassWithWarningAndUnknownStatusesFail() throws IOException, InterruptedException {
        Verdict low = run("release", "2", "0", "", FINISHED);
        assertThat(low.exit()).isZero();
        assertThat(low.output()).contains("::warning", "ZAP exit 2");
        assertThat(run("nightly", "3", "0", "", FINISHED).exit()).isOne();
    }

    @Test
    void lostSessionMissingReportOrUnknownModeFails() throws IOException, InterruptedException {
        assertThat(verdict("release", "302", "0", "1", "0", "", FINISHED, CAP, "0")
                        .output())
                .contains("lost its session");
        assertThat(verdict("nightly", "200", "0", "0", "0", "", FINISHED, CAP, "0")
                        .output())
                .contains("wrote no JSON report");
        Verdict unknown = verdict("weekly", "200", "0", "1", "0", "", FINISHED, CAP, "0");
        assertThat(unknown.exit()).isOne();
        assertThat(unknown.output()).contains("Unknown mode");
    }

    @Test
    void rulesStoppedAtTheirLimitAreCountedButDoNotFail() throws IOException, InterruptedException {
        Verdict verdict = verdict("release", "200", "0", "1", "0", "", FINISHED, CAP, "6");
        assertThat(verdict.exit()).isZero();
        assertThat(verdict.summary()).contains("6 active scan rule(s) stopped at their 5-minute limit");
    }

    /**
     * The plan's active scan runs as two activeScan jobs (#231), the second attacking the form bodies last: each gets
     * its own time and cap, and either one cut off or missing makes the scan partial.
     */
    @Test
    void everyActiveScanOfThePlanMustCompleteInsideItsOwnCap() throws IOException, InterruptedException {
        Verdict both = scans("release", "1444.962 300.2");
        assertThat(both.exit()).isZero();
        assertThat(both.output()).doesNotContain("::error", "::warning");

        Verdict first = scans("release", "4800.071 300.2");
        assertThat(first.exit()).isOne();
        assertThat(first.output()).contains("::error", "Active scan 1 of 2 was cut off at its 80-minute cap");

        Verdict second = scans("release", "1444.962 2400.104");
        assertThat(second.exit()).isOne();
        assertThat(second.output()).contains("::error", "Active scan 2 of 2 was cut off at its 40-minute cap");
        Verdict nightly = scans("nightly", "1444.962 2400.104");
        assertThat(nightly.exit()).isZero();
        assertThat(nightly.output()).contains("::warning", "Active scan 2 of 2 was cut off");

        for (String missing : List.of("1444.962 -", "1444.962", "- 300.2", "")) {
            Verdict verdict = scans("release", missing);
            assertThat(verdict.exit()).as(missing).isOne();
            assertThat(verdict.output()).as(missing).containsPattern("does not show active scan [12] of 2 completing");
        }
    }

    @Test
    void missingActiveScanCapsFailRelease() throws IOException, InterruptedException {
        Verdict verdict = verdict("release", "200", "0", "1", "0", "", FINISHED, "", "0");
        assertThat(verdict.exit()).isOne();
        assertThat(verdict.output()).contains("No active scan cap was given");
    }

    private Verdict scans(String mode, String active) throws IOException, InterruptedException {
        return verdict(mode, "200", "0", "1", "0", "", active, "4800 2400", "0");
    }

    private Verdict run(String mode, String status, String gating, String stopped, String active)
            throws IOException, InterruptedException {
        return verdict(mode, "200", status, "1", gating, stopped, active, CAP, "0");
    }

    private Verdict verdict(
            String mode,
            String still,
            String status,
            String report,
            String gating,
            String stopped,
            String active,
            String cap,
            String skipped)
            throws IOException, InterruptedException {
        Path summary = Files.createTempFile(dir, "summary", ".md");
        List<String> command = new ArrayList<>(List.of("bash", SCRIPT.toString(), mode, "compliance.admin", still));
        command.addAll(List.of(status, report, gating, stopped, active, cap, skipped));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GITHUB_STEP_SUMMARY", summary.toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new Verdict(process.exitValue(), output, Files.readString(summary));
    }

    private record Verdict(int exit, String output, String summary) {}
}
