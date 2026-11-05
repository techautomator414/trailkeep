// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.document.AttachmentRejectedException.Reason;
import io.trailkeep.shared.DomainException.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The §9 allow-list: detection from bytes, the extension cross-check and the image cap. */
class AttachmentTypesTest {

    private static final long CAP = 25_000_000;

    @TempDir
    Path dir;

    private String check(String name, byte[] content) throws IOException {
        return AttachmentTypes.check(Files.write(dir.resolve("upload.tmp"), content), name, CAP);
    }

    private void assertRejected(Reason reason, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AttachmentRejectedException.class, e -> {
            assertThat(e.reason()).isEqualTo(reason);
            assertThat(e.kind()).isEqualTo(Kind.INVALID);
        });
    }

    @Test
    void everyAllowedTypeIsDetectedFromItsBytes() throws IOException {
        assertThat(check("report.pdf", UploadSamples.pdf())).isEqualTo("application/pdf");
        assertThat(check("memo.docx", UploadSamples.ooxml("word/document.xml")))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(check("sheet.xlsx", UploadSamples.ooxml("xl/workbook.xml")))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(check("deck.pptx", UploadSamples.ooxml("ppt/presentation.xml")))
                .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        assertThat(check("mail.msg", UploadSamples.ole2())).isEqualTo("application/vnd.ms-outlook");
        assertThat(check("photo.png", UploadSamples.image("png", 4, 3))).isEqualTo("image/png");
        assertThat(check("photo.JPG", UploadSamples.image("jpg", 4, 3))).isEqualTo("image/jpeg");
        assertThat(check("photo.jpeg", UploadSamples.image("jpg", 4, 3))).isEqualTo("image/jpeg");
        assertThat(check("notes.txt", UploadSamples.ascii("Plain notes.\n"))).isEqualTo("text/plain");
        assertThat(check("data.csv", UploadSamples.ascii("a,b\n1,2\n"))).isEqualTo("text/csv");
        assertThat(check("mail.eml", UploadSamples.eml())).isEqualTo("message/rfc822");
    }

    @Test
    void windowsExecutableNamedAsAnAllowedTypeIsRefused() {
        for (String name : new String[] {"invoice.pdf", "notes.txt", "memo.docx", "photo.png", "invoice.exe"}) {
            assertRejected(Reason.TYPE_NOT_ALLOWED, () -> check(name, UploadSamples.windowsExecutable()));
        }
    }

    @Test
    void typeOffTheAllowListIsRefusedWhateverItIsCalled() {
        assertRejected(Reason.TYPE_NOT_ALLOWED, () -> check("archive.zip", UploadSamples.plainZip()));
        assertRejected(Reason.TYPE_NOT_ALLOWED, () -> check("memo.docx", UploadSamples.plainZip()));
        assertRejected(Reason.TYPE_NOT_ALLOWED, () -> check("page.txt", UploadSamples.ascii("<html><body>")));
    }

    @Test
    void allowedTypeUnderAnotherTypesExtensionIsRefused() {
        assertRejected(Reason.EXTENSION_MISMATCH, () -> check("memo.docx", UploadSamples.pdf()));
        assertRejected(Reason.EXTENSION_MISMATCH, () -> check("photo.png", UploadSamples.image("jpg", 4, 3)));
        assertRejected(Reason.EXTENSION_MISMATCH, () -> check("report.pdf", UploadSamples.ascii("just text\n")));
        assertRejected(Reason.EXTENSION_MISMATCH, () -> check("report", UploadSamples.pdf()));
        assertRejected(Reason.EXTENSION_MISMATCH, () -> check("report.pdf.", UploadSamples.pdf()));
    }

    @Test
    void imageIsCappedByTheDimensionsInItsHeaderWithoutDecodingIt() throws IOException {
        // 20000 x 20000 is 400 megapixels: 1.2 GB of RGB raster if it were decoded. Only the header is read.
        Path bomb = Files.write(dir.resolve("bomb.tmp"), UploadSamples.pngHeaderOnly(20_000, 20_000));
        assertThat(AttachmentTypes.pixels(bomb)).isEqualTo(400_000_000L);
        assertRejected(Reason.IMAGE, () -> check("bomb.png", UploadSamples.pngHeaderOnly(20_000, 20_000)));

        assertThat(check("ok.png", UploadSamples.pngHeaderOnly(5_000, 5_000))).isEqualTo("image/png");
        assertRejected(Reason.IMAGE, () -> check("wide.png", UploadSamples.pngHeaderOnly(5_001, 5_000)));
    }

    @Test
    void imageWhoseHeaderCannotBeReadIsRefused() {
        byte[] truncated = new byte[12];
        System.arraycopy(UploadSamples.pngHeaderOnly(10, 10), 0, truncated, 0, truncated.length);
        assertRejected(Reason.IMAGE, () -> check("broken.png", truncated));
    }

    @Test
    void theExtensionIsTheTextAfterTheLastDotInLowerCase() {
        assertThat(AttachmentTypes.extension("a.b.PDF")).isEqualTo("pdf");
        assertThat(AttachmentTypes.extension("C:\\Users\\x\\scan.Png")).isEqualTo("png");
        assertThat(AttachmentTypes.extension("README")).isEmpty();
    }
}
