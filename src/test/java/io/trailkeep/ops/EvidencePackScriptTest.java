// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.trailkeep.ops.EvidencePackManifest.Row;
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
 * {@code .github/scripts/evidence-pack.py}, which builds the release evidence pack (WP-30 part d), run against fixtures
 * for every artifact {@code .github/release/evidence-pack.txt} names: a complete pack assembles and checks; a missing
 * artifact, a failed accessibility test, a file deferred until 1.0.0 at 1.0.0, a tampered, missing or unlisted file in
 * the pack, a version with no CHANGELOG section and a 1.0.0 document that calls itself a draft each stop the release.
 * Needs {@code python3} (every GitHub runner has it); skipped without it.
 */
class EvidencePackScriptTest {

    private static final Path SCRIPT = Path.of(".github/scripts/evidence-pack.py");

    @Test
    void completePackAssemblesAndChecks(@TempDir Path dir) throws Exception {
        Path artifacts = fixtures(dir, "");
        Path pack = dir.resolve("pack");

        Run assembled = assemble("v0.9.0", artifacts, pack);
        assertThat(assembled.exit()).as(assembled.output()).isZero();
        Files.writeString(pack.resolve("SHA256SUMS.sigstore.json"), "{}");
        Run checked = script("check", "--tag", "v0.9.0", "--pack", pack.toString());
        assertThat(checked.exit()).as(checked.output()).isZero();

        String index = Files.readString(pack.resolve("EVIDENCE.md"));
        for (Row row : EvidencePackManifest.rows()) {
            assertThat(index).contains(row.requirement().replace("|", "\\|"));
        }
        assertThat(Files.readString(pack.resolve("accessibility-tests.md")))
                .contains("`io.trailkeep.e2e.ShellAccessibilityIT` | 2 | 0 | 0 | 0");
        assertThat(Files.readString(pack.resolve("VERIFY.md")))
                .contains(
                        "--certificate-identity 'https://github.com/example/trailkeep/.github/workflows/release.yml@refs/tags/v0.9.0'");
        assertThat(Files.readString(pack.resolve("flyway-schema.txt"))).contains("common: V");
        assertThat(index).contains("| `dependency-check-report.zip` |");
    }

    @Test
    void missingArtifactOrFailedAccessibilityTestStopsTheRelease(@TempDir Path dir) throws Exception {
        Run noPerf = assemble("v0.9.0", fixtures(dir.resolve("a"), "perf"), dir.resolve("a/pack"));
        assertThat(noPerf.exit()).isNotZero();
        assertThat(noPerf.output()).contains("perf.zip");

        Path failing = fixtures(dir.resolve("b"), "");
        Files.writeString(
                failing.resolve("test-reports/failsafe-reports/TEST-io.trailkeep.e2e.ShellAccessibilityIT.xml"),
                "<testsuite name=\"io.trailkeep.e2e.ShellAccessibilityIT\" tests=\"2\" failures=\"1\" errors=\"0\""
                        + " skipped=\"0\"/>");
        Run axe = assemble("v0.9.0", failing, dir.resolve("b/pack"));
        assertThat(axe.exit()).isNotZero();
        assertThat(axe.output()).contains("accessibility-tests.md", "1 failed");
    }

    @Test
    void fromOneZeroZeroTheDeferredFilesAreRequired(@TempDir Path dir) throws Exception {
        Path artifacts = fixtures(dir.resolve("a"), "dependency-check-report");
        assertThat(assemble("v0.9.0", artifacts, dir.resolve("a/pack")).exit()).isZero();
        assertThat(Files.readString(dir.resolve("a/pack/EVIDENCE.md")))
                .contains("not in this release (required from 1.0.0)");

        Run one = assemble("v1.0.0", fixtures(dir.resolve("b"), "dependency-check-report"), dir.resolve("b/pack"));
        assertThat(one.exit()).isNotZero();
        assertThat(one.output()).contains("dependency-check-report.zip");
    }

    @Test
    void checkRefusesTamperedOrIncompletePack(@TempDir Path dir) throws Exception {
        Path pack = dir.resolve("pack");
        assertThat(assemble("v0.9.0", fixtures(dir, ""), pack).exit()).isZero();
        Files.writeString(pack.resolve("SHA256SUMS.sigstore.json"), "{}");
        Files.writeString(pack.resolve("bom.json"), "{\"tampered\": true}");
        Files.delete(pack.resolve("THIRD-PARTY.txt"));
        Files.writeString(pack.resolve("extra.txt"), "not listed");

        Run checked = script("check", "--tag", "v0.9.0", "--pack", pack.toString());
        assertThat(checked.exit()).isNotZero();
        assertThat(checked.output())
                .contains("SHA256SUMS does not match bom.json")
                .contains("THIRD-PARTY.txt (Licence report (THIRD-PARTY.txt)) is missing from the pack")
                .contains("extra.txt is in the pack but not in");
    }

    @Test
    void preflightNeedsTheChangelogSectionAndRefusesDraftsFromOneZeroZero(@TempDir Path dir) throws Exception {
        Run released =
                script("preflight", "--tag", "v0.3.0", "--out", dir.resolve("a").toString());
        assertThat(released.exit()).as(released.output()).isZero();
        assertThat(Files.readString(dir.resolve("a/CHANGELOG-section.md"))).startsWith("## [0.3.0]");

        Run unknown =
                script("preflight", "--tag", "v0.0.9", "--out", dir.resolve("b").toString());
        assertThat(unknown.exit()).isNotZero();
        assertThat(unknown.output()).contains("CHANGELOG.md has no section '## [0.0.9]'");

        Path manifest = dir.resolve("manifest.txt");
        Files.writeString(manifest, "ACR.md | repo:accessibility/ACR-shell.md | 1.0.0 | the ACR | read it\n");
        Run draft = run(List.of(
                "--manifest",
                manifest.toString(),
                "preflight",
                "--tag",
                "v1.0.0",
                "--out",
                dir.resolve("c").toString()));
        assertThat(draft.exit()).isNotZero();
        assertThat(draft.output()).contains("accessibility/ACR-shell.md calls itself a draft");
    }

    /** One artifact directory per {@code artifact:} row, except {@code skip}, and passing accessibility reports. */
    private static Path fixtures(Path dir, String skip) throws IOException {
        Path artifacts = dir.resolve("artifacts");
        for (Row row : EvidencePackManifest.rows()) {
            if (!row.fromArtifact() || row.artifact().equals(skip)) {
                continue;
            }
            String source = row.source().substring("artifact:".length());
            Path target = source.contains("/")
                    ? artifacts.resolve(source)
                    : artifacts.resolve(source).resolve("report.txt");
            Files.createDirectories(target.getParent());
            Files.writeString(target, "fixture for " + row.file());
        }
        if (!skip.equals("test-reports")) {
            Path reports = Files.createDirectories(artifacts.resolve("test-reports/failsafe-reports"));
            Files.writeString(
                    reports.resolve("TEST-io.trailkeep.e2e.ShellAccessibilityIT.xml"),
                    "<testsuite name=\"io.trailkeep.e2e.ShellAccessibilityIT\" tests=\"2\" failures=\"0\" errors=\"0\""
                            + " skipped=\"0\"/>");
        }
        return artifacts;
    }

    private static Run assemble(String tag, Path artifacts, Path pack) throws IOException, InterruptedException {
        return script(
                "assemble",
                "--tag",
                tag,
                "--image",
                "ghcr.io/example/trailkeep",
                "--digest",
                "sha256:" + "0".repeat(64),
                "--repository",
                "example/trailkeep",
                "--run-url",
                "https://example.invalid/run",
                "--artifacts",
                artifacts.toString(),
                "--pack",
                pack.toString());
    }

    private static Run script(String... args) throws IOException, InterruptedException {
        return run(List.of(args));
    }

    private static Run run(List<String> args) throws IOException, InterruptedException {
        assumeTrue(python(), "python3 is not installed");
        List<String> command = new ArrayList<>(List.of("python3", "-I", SCRIPT.toString()));
        command.addAll(args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(process.exitValue(), output);
    }

    private static boolean python() {
        try {
            Process probe = new ProcessBuilder("python3", "--version")
                    .redirectErrorStream(true)
                    .start();
            probe.getInputStream().readAllBytes();
            return probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record Run(int exit, String output) {}
}
