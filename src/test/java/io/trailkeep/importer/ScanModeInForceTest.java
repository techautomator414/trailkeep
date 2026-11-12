// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.importer.ImportRun.ScanMode;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

/**
 * The scanning mode in force (design §4.3, R1) read as the attachment intake reads it, and the clamd stream limit
 * check (R13) that {@code dry-run} and {@code load} make in {@code clamd} mode.
 */
class ScanModeInForceTest {

    private static final DataSize CAP = DataSize.ofMegabytes(25);

    @Test
    void modeIsClamdUnlessTheDeploymentSaysOtherwise() {
        assertThat(ScanModeInForce.read(new MockEnvironment())).isEqualTo(ScanMode.CLAMD);
        assertThat(ScanModeInForce.read(new MockEnvironment().withProperty("app.attachments.scanning", "external")))
                .isEqualTo(ScanMode.EXTERNAL);
        assertThat(ScanModeInForce.read(new MockEnvironment().withProperty("app.attachments.scanning", "clamd")))
                .isEqualTo(ScanMode.CLAMD);
    }

    @Test
    void clamdModeNeedsDeclaredStreamLimitAtLeastTheImporterCap() {
        assertThatThrownBy(() -> ScanModeInForce.checkStreamMax(ScanMode.CLAMD, attachments(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("import.attachments.clamd-stream-max");
        assertThatThrownBy(() -> ScanModeInForce.checkStreamMax(
                        ScanMode.CLAMD, attachments(DataSize.ofBytes(CAP.toBytes() - 1))))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> ScanModeInForce.checkStreamMax(ScanMode.CLAMD, attachments(CAP)))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScanModeInForce.checkStreamMax(ScanMode.CLAMD, attachments(DataSize.ofMegabytes(100))))
                .doesNotThrowAnyException();
    }

    @Test
    void externalModeIgnoresTheStreamLimit() {
        assertThatCode(() -> ScanModeInForce.checkStreamMax(ScanMode.EXTERNAL, attachments(null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScanModeInForce.checkStreamMax(ScanMode.EXTERNAL, attachments(DataSize.ofBytes(1))))
                .doesNotThrowAnyException();
    }

    private static ImportProperties.Attachments attachments(@Nullable DataSize streamMax) {
        return new ImportProperties.Attachments(CAP, streamMax);
    }
}
