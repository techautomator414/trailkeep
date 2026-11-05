// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/** Small synthetic files of each allowed type, plus hostile ones, built in memory so none is committed. */
final class UploadSamples {

    private UploadSamples() {}

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] pdf() {
        return ascii("%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n");
    }

    /** The start of a Windows PE: the MZ header pointing at a {@code PE\0\0} signature. */
    static byte[] windowsExecutable() {
        ByteBuffer pe = ByteBuffer.allocate(512);
        pe.put(ascii("MZ"));
        pe.putInt(0x3C, Integer.reverseBytes(0x80));
        pe.position(0x80);
        pe.put(ascii("PE")).put((byte) 0).put((byte) 0);
        pe.putShort((short) 0x4C01); // IMAGE_FILE_MACHINE_I386, little-endian
        return pe.array();
    }

    /** An OOXML package: a zip whose first entry is {@code [Content_Types].xml}. */
    static byte[] ooxml(String mainPart) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(ascii("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                    + "content-types\"></Types>"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(mainPart));
            zip.write(ascii("<?xml version=\"1.0\"?><root/>"));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static byte[] plainZip() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("notes.txt"));
            zip.write(ascii("hello"));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** The start of an OLE2 compound file, the container of an Outlook MSG. */
    static byte[] ole2() {
        byte[] ole = new byte[1024];
        byte[] magic = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(magic, 0, ole, 0, magic.length);
        return ole;
    }

    static byte[] image(String format, int width, int height) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * A PNG whose header claims the given dimensions but which carries no pixel data at all: decoding it would try to
     * allocate a raster of {@code width * height} pixels, reading its header must not.
     */
    static byte[] pngHeaderOnly(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13)
                .putInt(width)
                .putInt(height)
                .put((byte) 8) // bit depth
                .put((byte) 2) // truecolour
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0);
        ByteBuffer png = ByteBuffer.allocate(8 + 12 + 13 + 12);
        png.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        chunk(png, "IHDR", ihdr.array());
        chunk(png, "IEND", new byte[0]);
        return png.array();
    }

    private static void chunk(ByteBuffer png, String type, byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(ascii(type));
        crc.update(data);
        png.putInt(data.length).put(ascii(type)).put(data).putInt((int) crc.getValue());
    }

    static byte[] eml() {
        return ascii(String.join(
                "\r\n",
                "From: sender@example.org",
                "To: recipient@example.org",
                "Subject: Synthetic",
                "Date: Thu, 5 Nov 2026 10:00:00 +0000",
                "Message-ID: <1@example.org>",
                "",
                "Synthetic body.",
                ""));
    }

    /** The EICAR test file, assembled at run time so the repository never contains it. */
    static byte[] eicar() {
        return ascii("X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*");
    }
}
