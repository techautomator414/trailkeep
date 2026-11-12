// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import com.networknt.schema.Schema;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.commons.csv.CSVException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.QuoteMode;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The CSV and TSV files source (WP-26 part l, design §4.2): a {@code kind: files} mapping whose every entity file ends
 * in {@code .csv} or {@code .tsv}, parsed by Apache Commons CSV as RFC 4180 with the dialect {@code source.csv}
 * declares: delimiter (a comma, or a tab for {@code .tsv}), quote ({@code "}, or none), escape (none), header (the
 * first row names the columns; without one they are {@code column1}, {@code column2}, ...) and null token (an unquoted
 * field equal to it is null; a quoted one never is). Rows end at CR, LF or CRLF. Files are decoded in {@code
 * source.encoding} by a decoder that reports undecodable bytes, a leading BOM is consumed, and a lone {@code 0x00}
 * field is the empty string (how {@code bcp} writes one). The manifest is handled exactly as for JSON Lines: each file
 * is hashed and its rows counted while it is read, and opened by its real path under the mapping's directory. Every
 * row must have the header's number of fields; a row that does not, or that the parser cannot read, is a {@code
 * ROW_UNREADABLE} carrying the line it starts on, and an undecodable byte a {@code SOURCE_ENCODING} carrying its own
 * line; never a value or a parser message.
 */
@Component
@Profile("importer")
final class CsvSource implements SourceRows.Factory {

    private static final int BOM = 0xFEFF;

    private static final int BUFFER = 8192;

    private static final String NUL = String.valueOf((char) 0);

    private final Schema manifestSchema = FileManifest.schema();

    @Override
    public boolean supports(JsonNode mapping) {
        return "files".equals(mapping.at("/source/kind").asString(""))
                && specs(mapping).stream()
                        .allMatch(spec -> isCsv(spec.path("file").asString("")));
    }

    @Override
    public SourceRows open(JsonNode mapping, Path directory) {
        Path root;
        try {
            root = directory.toRealPath();
        } catch (IOException unreadable) {
            throw new SourceException("SOURCE_FILE_UNREADABLE", null);
        }
        Map<String, CSVFormat> formats = new HashMap<>();
        for (JsonNode spec : specs(mapping)) {
            String file = spec.path("file").asString();
            formats.put(file, format(mapping, file));
        }
        FileManifest manifest = FileManifest.read(root, mapping, manifestSchema);
        Charset charset = Charset.forName(mapping.at("/source/encoding").asString("UTF-8"));
        return new OpenFiles(
                root,
                manifest,
                charset,
                formats,
                mapping.at("/source/csv/header").asBoolean(true));
    }

    /** The people part and every entity, each naming its file. */
    private static List<JsonNode> specs(JsonNode mapping) {
        List<JsonNode> specs = new ArrayList<>(List.of(mapping.path("people")));
        mapping.path("entities").forEach(specs::add);
        return specs;
    }

    /**
     * Whether a mapped file is read as CSV.
     *
     * @param file the file name
     * @return true for {@code .csv} and {@code .tsv}
     */
    static boolean isCsv(String file) {
        String name = file.toLowerCase(Locale.ROOT);
        return name.endsWith(".csv") || name.endsWith(".tsv");
    }

    /**
     * The dialect a file is parsed with: {@code source.csv}, over RFC 4180 and the file's extension.
     *
     * @param mapping the validated mapping
     * @param file the file name
     * @return the format
     * @throws SourceException {@code CSV_DIALECT_INVALID} when the declared characters clash (a delimiter that is the
     *     quote or holds a line break, say)
     */
    static CSVFormat format(JsonNode mapping, String file) {
        JsonNode csv = mapping.at("/source/csv");
        String quote = csv.path("quote").asString("\"");
        String escape = csv.path("escape").asString("");
        JsonNode nullToken = csv.path("nullToken");
        try {
            return CSVFormat.RFC4180
                    .builder()
                    .setDelimiter(csv.path("delimiter")
                            .asString(file.toLowerCase(Locale.ROOT).endsWith(".tsv") ? "\t" : ","))
                    .setQuote(quote.isEmpty() ? null : quote.charAt(0))
                    .setEscape(escape.isEmpty() ? null : escape.charAt(0))
                    .setNullString(nullToken.isString() ? nullToken.asString() : null)
                    // When parsing, this quote mode keeps a quoted field from ever being the null token; without a
                    // token it would read every unquoted empty field as null, so it is set with one only.
                    .setQuoteMode(nullToken.isString() ? QuoteMode.ALL_NON_NULL : QuoteMode.MINIMAL)
                    .setIgnoreEmptyLines(false)
                    .get();
        } catch (IllegalArgumentException clash) {
            throw new SourceException("CSV_DIALECT_INVALID", null);
        }
    }

    /** The files of one extract, against one manifest. */
    private static final class OpenFiles implements SourceRows {

        private final Path root;
        private final FileManifest manifest;
        private final Charset charset;
        private final Map<String, CSVFormat> formats;
        private final boolean header;

        OpenFiles(Path root, FileManifest manifest, Charset charset, Map<String, CSVFormat> formats, boolean header) {
            this.root = root;
            this.manifest = manifest;
            this.charset = charset;
            this.formats = formats;
            this.header = header;
        }

        @Override
        public String fingerprint() {
            return manifest.fingerprint();
        }

        @Override
        public void read(StagedEntity entity, JsonNode spec, Consumer<Map<String, @Nullable Object>> rows) {
            String name = spec.path("file").asString();
            FileManifest.Entry expected = manifest.entry(name, entity);
            MessageDigest sha256 = sha256();
            long count = 0;
            long line = 1;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            new DigestInputStream(
                                    Files.newInputStream(FileManifest.open(root, name), LinkOption.NOFOLLOW_LINKS),
                                    sha256),
                            reporting(charset)));
                    CSVParser parser = Objects.requireNonNull(formats.get(name), "every mapped file has a format")
                            .parse(withoutBom(reader))) {
                Iterator<CSVRecord> records = parser.iterator();
                List<String> columns = null;
                while (records.hasNext()) {
                    CSVRecord record = records.next();
                    if (columns == null) {
                        columns = columns(record, header, line);
                        if (header) {
                            line = parser.getCurrentLineNumber() + 1;
                            continue;
                        }
                    }
                    if (record.size() != columns.size()) {
                        throw new SourceException("ROW_UNREADABLE", line);
                    }
                    count++;
                    rows.accept(row(columns, record));
                    line = parser.getCurrentLineNumber() + 1;
                }
            } catch (UncheckedIOException failed) {
                throw unreadable(failed.getCause(), name, line);
            } catch (IOException failed) {
                throw unreadable(failed, name, line);
            }
            if (count != expected.rows()
                    || !HexFormat.of().formatHex(sha256.digest()).equals(expected.sha256())) {
                throw new SourceException("MANIFEST_MISMATCH", null);
            }
        }

        /** A failed read as a code and a line: the parser's and decoder's messages quote the data. */
        private SourceException unreadable(@Nullable Throwable failure, String name, long line) {
            return switch (failure) {
                case CSVException malformed -> new SourceException("ROW_UNREADABLE", line);
                case CharacterCodingException undecodable ->
                    new SourceException("SOURCE_ENCODING", undecodableLine(name));
                case null, default -> new SourceException("SOURCE_FILE_UNREADABLE", null);
            };
        }

        /**
         * The line of a file's first undecodable byte. The reader decodes ahead of the parser, so the parser's line
         * says little; the file is decoded again, counting line feeds up to that byte.
         */
        private long undecodableLine(String name) {
            CharsetDecoder decoder = reporting(charset);
            ByteBuffer bytes = ByteBuffer.allocate(BUFFER);
            CharBuffer chars = CharBuffer.allocate(BUFFER);
            long line = 1;
            try (InputStream in = Files.newInputStream(FileManifest.open(root, name), LinkOption.NOFOLLOW_LINKS)) {
                while (true) {
                    int read = in.read(bytes.array(), bytes.position(), bytes.remaining());
                    bytes.position(bytes.position() + Math.max(read, 0)).flip();
                    boolean failed = decoder.decode(bytes, chars, read < 0).isError();
                    for (chars.flip(); chars.hasRemaining(); ) {
                        line += chars.get() == '\n' ? 1 : 0;
                    }
                    if (failed || read < 0) {
                        return line;
                    }
                    chars.clear();
                    bytes.compact();
                }
            } catch (IOException unreadable) {
                return line;
            }
        }

        @Override
        public FileManifest.@Nullable Content content(String contentId) {
            return manifest.contents().get(contentId);
        }

        @Override
        public void close() {
            // Each file is closed when its entity has been read.
        }
    }

    /** Consumes a leading BOM, which a declared UTF-16 or UTF-8 file may start with (§4.2). */
    private static BufferedReader withoutBom(BufferedReader reader) throws IOException {
        reader.mark(1);
        if (reader.read() != BOM) {
            reader.reset();
        }
        return reader;
    }

    /** The column names: the header's, in lower case, each once and none blank; else {@code column1} onwards. */
    private static List<String> columns(CSVRecord first, boolean header, long line) {
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < first.size(); i++) {
            String column = header
                    ? Objects.requireNonNullElse(first.get(i), "").strip().toLowerCase(Locale.ROOT)
                    : "column" + (i + 1);
            if (column.isEmpty() || columns.contains(column)) {
                throw new SourceException("ROW_UNREADABLE", line);
            }
            columns.add(column);
        }
        return columns;
    }

    private static Map<String, @Nullable Object> row(List<String> columns, CSVRecord record) {
        Map<String, @Nullable Object> row = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            String value = record.get(i);
            row.put(columns.get(i), NUL.equals(value) ? "" : value);
        }
        return row;
    }

    /** A decoder that refuses bytes the encoding cannot decode instead of replacing them. */
    private static CharsetDecoder reporting(Charset charset) {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }
}
