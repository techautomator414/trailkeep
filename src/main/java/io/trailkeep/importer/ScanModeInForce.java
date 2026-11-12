// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

/**
 * The attachment scanning mode in force for this importer start (design §4.3, R1), read where the attachment intake
 * reads it ({@code app.attachments.scanning}, ADR-0013), so a Job {@code env} override or a ConfigMap change between
 * the dry run and the load is seen, not assumed away; and, in {@code clamd} mode, the check that the importer's cap
 * fits clamd's declared {@code StreamMaxLength} (R13). Only {@code dry-run} and {@code load}, whose results depend on
 * them, read these, so {@code status} and {@code purge-staging} never stop on an unrelated setting.
 */
final class ScanModeInForce {

    /** The property the attachment intake reads its scanning mode from (ADR-0013). */
    private static final String SCANNING = "app.attachments.scanning";

    private ScanModeInForce() {}

    /**
     * The mode in force: {@code clamd} unless the deployment says otherwise, as for the intake.
     *
     * @param environment this start's environment
     * @return the mode
     */
    static ImportRun.ScanMode read(Environment environment) {
        return Binder.get(environment).bind(SCANNING, ImportRun.ScanMode.class).orElse(ImportRun.ScanMode.CLAMD);
    }

    /**
     * Checks that, in {@code clamd} mode, {@code import.attachments.clamd-stream-max} is declared and at least {@code
     * import.attachments.max-bytes}: otherwise clamd would refuse files the importer's cap lets through.
     *
     * @param mode the mode in force
     * @param attachments the importer's attachment settings
     * @throws IllegalStateException in {@code clamd} mode, if it is unset or below the importer's cap
     */
    static void checkStreamMax(ImportRun.ScanMode mode, ImportProperties.Attachments attachments) {
        @Nullable DataSize streamMax = attachments.clamdStreamMax();
        if (mode == ImportRun.ScanMode.CLAMD
                && (streamMax == null
                        || streamMax.toBytes() < attachments.maxBytes().toBytes())) {
            throw new IllegalStateException("In clamd scanning mode, import.attachments.clamd-stream-max must declare"
                    + " clamd's StreamMaxLength and be at least import.attachments.max-bytes");
        }
    }
}
