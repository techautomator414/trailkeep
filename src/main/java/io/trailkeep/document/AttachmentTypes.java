// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.document.AttachmentRejectedException.Reason;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MimeTypes;

/**
 * The upload type allow-list (BUILD_PLAN §9): PDF, OOXML DOCX/XLSX/PPTX, OLE2 MSG, PNG/JPEG, TXT/CSV and EML.
 *
 * <p>The type is detected from the bytes by {@code tika-core}'s magic database alone (no parsers). The extension is
 * only a hint Tika may use to name a subtype of what the bytes already are, such as a DOCX within an OOXML zip
 * container or an MSG within an OLE2 container; it can never override the bytes. The detected type must be on the
 * allow-list and must also be the type the extension promises, so a Windows executable named {@code .pdf} is refused
 * (its bytes say executable) and so is a real PDF named {@code .docx}. Without the hint, the bytes must already be
 * an allowed type or an OOXML or OLE2 container, so a plain zip named {@code .docx} is refused too. Core-only
 * detection cannot tell a DOCX from an XLSX inside the same OOXML container; the extension names which.
 *
 * <p>For PNG and JPEG, the dimensions are read from the image header by the {@code ImageIO} reader without decoding
 * any pixels, so a decompression bomb is refused before anything allocates its raster.
 */
final class AttachmentTypes {

    /** Extension, lower case, to the one detected type it may carry. */
    static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("msg", "application/vnd.ms-outlook"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("eml", "message/rfc822"));

    /** The detected types accepted. */
    static final Set<String> ALLOWED = Set.copyOf(BY_EXTENSION.values());

    /**
     * What the bytes alone must be: an allowed type, or the zip or OLE2 container Tika's magic names for an OOXML or
     * MSG file. A plain zip, or any other container, is not evidence of an Office document whatever its name.
     */
    private static final Set<String> BYTES_ALONE = Set.of(
            "application/x-tika-ooxml",
            "application/x-tika-msoffice",
            "application/pdf",
            "image/png",
            "image/jpeg",
            "text/plain",
            "message/rfc822");

    private static final Set<String> IMAGES = Set.of("image/png", "image/jpeg");

    private static final MimeTypes MIME_TYPES = MimeTypes.getDefaultMimeTypes();

    private AttachmentTypes() {}

    /**
     * Detects a file's type and checks it against the allow-list, the file's extension and the image cap.
     *
     * @param file the uploaded bytes
     * @param fileName the name the uploader gave; only its extension is used
     * @param maxImagePixels the largest width times height accepted for an image
     * @return the detected type, for example {@code application/pdf}
     * @throws AttachmentRejectedException if the type is not allowed or the image is too large or unreadable
     */
    static String check(Path file, String fileName, long maxImagePixels) {
        String extension = extension(fileName);
        String detected = detect(file, extension);
        if (!ALLOWED.contains(detected) || !BYTES_ALONE.contains(detect(file, ""))) {
            throw new AttachmentRejectedException(Reason.TYPE_NOT_ALLOWED, "detected type not on the allow-list");
        }
        if (!detected.equals(BY_EXTENSION.get(extension))) {
            throw new AttachmentRejectedException(Reason.EXTENSION_MISMATCH, "detected type differs from extension");
        }
        if (IMAGES.contains(detected) && pixels(file) > maxImagePixels) {
            throw new AttachmentRejectedException(Reason.IMAGE, "image dimensions exceed the cap");
        }
        return detected;
    }

    /**
     * The extension of a file name, lower case, or empty.
     *
     * @param fileName the name
     * @return the text after the last dot
     */
    static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String detect(Path file, String extension) {
        Metadata hint = new Metadata();
        if (!extension.isEmpty()) {
            // Only the extension reaches Tika, never the uploader's name.
            hint.set(TikaCoreProperties.RESOURCE_NAME_KEY, "upload." + extension);
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            return MIME_TYPES.detect(in, hint).getBaseType().toString();
        } catch (IOException e) {
            throw new UncheckedIOException("upload temp file unreadable", e);
        }
    }

    /**
     * Width times height of the first image, from its header only.
     *
     * @param file a PNG or JPEG
     * @return the pixel count
     * @throws AttachmentRejectedException if no reader understands the header
     */
    static long pixels(Path file) {
        try (ImageInputStream in = new FileImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in, true, true);
                    return (long) reader.getWidth(0) * reader.getHeight(0);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new AttachmentRejectedException(Reason.IMAGE, "image header unreadable");
        }
        throw new AttachmentRejectedException(Reason.IMAGE, "image header unreadable");
    }
}
