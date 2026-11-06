// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@code app.attachments.scanning} (ADR-0013): {@code clamd} unless a deployment says {@code external}, an unknown
 * value fails startup, and {@code external} needs no clamd host, no SSL bundle and no {@link ClamdClient}.
 */
@ExtendWith(OutputCaptureExtension.class)
class ScanningModeTest {

    private static final String TEMP_DIR = "app.attachments.temp-dir=${java.io.tmpdir}";
    private static final String HOST = "app.attachments.clamd.host=clamav";
    private static final String BUNDLE =
            "spring.ssl.bundle.jks.clamd.truststore.location=file:${java.home}/lib/security/cacerts";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class))
            .withUserConfiguration(AttachmentConfig.class, ClamdClient.class, UploadIntake.class)
            // application.yml is not loaded here; its request limit is what AttachmentConfig checks the 25 MB cap
            // against.
            .withPropertyValues(TEMP_DIR, "spring.servlet.multipart.max-request-size=26MB");

    @Test
    void unsetModeIsClamdAndScansWithTheClamdClient(CapturedOutput output) {
        runner.withPropertyValues(HOST, BUNDLE).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AttachmentProperties.class).scanning()).isEqualTo(ScanMode.CLAMD);
            assertThat(context).hasSingleBean(ClamdClient.class).hasSingleBean(UploadIntake.class);
        });
        assertThat(output).contains("INFO", "Attachment scanning is clamd").doesNotContain("scanning is external");
    }

    @Test
    void externalBindsInAnyCase() {
        for (String value : new String[] {"external", "EXTERNAL"}) {
            runner.withPropertyValues("app.attachments.scanning=" + value)
                    .run(context -> assertThat(
                                    context.getBean(AttachmentProperties.class).scanning())
                            .isEqualTo(ScanMode.EXTERNAL));
        }
    }

    @Test
    void unknownModeFailsStartup() {
        runner.withPropertyValues("app.attachments.scanning=off", HOST, BUNDLE)
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.attachments.scanning=none", HOST, BUNDLE)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void externalStartsWithoutClamdHostBundleOrClient(CapturedOutput output) {
        runner.withPropertyValues("app.attachments.scanning=external").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ClamdClient.class).doesNotHaveBean(VirusScanner.class);
            assertThat(context).hasSingleBean(UploadIntake.class);
        });
        assertThat(output).contains("WARN", "Attachment scanning is external", "does not scan uploads", "NOT_SCANNED");
    }

    @Test
    void clamdStillRequiresTheHost() {
        runner.withPropertyValues(BUNDLE)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("app.attachments.clamd.host"));
        runner.withPropertyValues("app.attachments.scanning=clamd", "app.attachments.clamd.host= ", BUNDLE)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void clamdStillRequiresTheSslBundle() {
        runner.withPropertyValues("app.attachments.scanning=clamd", HOST)
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("clamd"));
    }
}
