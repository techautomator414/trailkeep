// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
import java.util.SortedMap;
import java.util.TreeMap;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The lookups a mapping reads from files (WP-26 part l2, design §6.1): {@code lookups.<name>: { file, key, value }},
 * a CSV file under the mapping's directory whose header row names the {@code key} and {@code value} columns. Each file
 * obeys the mapping's own rules: its real path, symlinks followed, must lie under the mapping file's directory, it is
 * opened without following links, and it is refused over {@link MappingValidator#MAX_BYTES}. It is parsed in the
 * dialect {@code source.csv} declares (a {@code .tsv} file splits at tabs) and decoded in {@code source.encoding} by a
 * decoder that reports undecodable bytes; a leading BOM is consumed. Every file is read once, and its bytes are both
 * parsed and hashed: the run's mapping hash is the SHA-256 over the mapping file and every lookup file in lookup-name
 * order, each length-prefixed, so an edited lookup file is caught exactly like an edited mapping. Problems are codes
 * at the lookup's place, never a value or a parser message (§10).
 *
 * <p>The default owners of {@code defaults.owner.byTeam} (26r, §7.3) are read the same way, with the columns {@code
 * team} and {@code owner}, and their file joins the mapping hash after the lookups.
 */
final class LookupFiles {

    private static final int BOM = 0xFEFF;

    /** Where a mapping names its default-owner file. */
    private static final String OWNERS = "/defaults/owner/byTeam";

    private final SortedMap<String, byte[]> contents = new TreeMap<>();
    private final Map<String, Map<String, String>> values = new HashMap<>();
    private final SortedMap<String, String> failures = new TreeMap<>();
    private Map<String, String> owners = Map.of();
    private byte @Nullable [] ownersContent;

    private LookupFiles() {}

    /**
     * Reads every lookup file a mapping names.
     *
     * @param file the mapping file
     * @param mapping its structurally sound content
     * @return the lookups read and the failures, by lookup name
     */
    static LookupFiles read(Path file, JsonNode mapping) {
        LookupFiles lookups = new LookupFiles();
        Charset charset = Charset.forName(mapping.at("/source/encoding").asString("UTF-8"));
        Path directory = Objects.requireNonNull(file.toAbsolutePath().getParent(), "a file has a directory");
        for (Map.Entry<String, JsonNode> lookup : mapping.path("lookups").properties()) {
            JsonNode spec = lookup.getValue();
            if (spec.has("file")) {
                lookups.load("/lookups/" + lookup.getKey() + "/file", directory, mapping, charset, (content, csv) -> {
                    lookups.contents.put(lookup.getKey(), content);
                    lookups.values.put(
                            lookup.getKey(),
                            parse(
                                    csv,
                                    spec.get("key").asString(),
                                    spec.get("value").asString()));
                });
            }
        }
        if (mapping.at(OWNERS).isString()) {
            lookups.load(OWNERS, directory, mapping, charset, (content, csv) -> {
                lookups.ownersContent = content;
                lookups.owners = parse(csv, "team", "owner");
            });
        }
        return lookups;
    }

    /** What is done with a file's bytes and its parser. */
    private interface Reader {
        void accept(byte[] content, CSVParser csv) throws IOException;
    }

    /** Reads the file a mapping names at a pointer; a failure is recorded at that pointer. */
    private void load(String pointer, Path directory, JsonNode mapping, Charset charset, Reader reader) {
        String name = mapping.at(pointer).asString();
        try {
            byte[] content = bytes(directory.toRealPath(), name);
            String text = decoded(content, charset);
            try (CSVParser csv = CsvSource.format(mapping, name)
                    .parse(new StringReader(!text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text))) {
                reader.accept(content, csv);
            } catch (UncheckedIOException malformed) {
                // The parser's message quotes the file; only the fact is kept.
                throw new SourceException("LOOKUP_FILE_INVALID", null);
            }
        } catch (IOException unreadable) {
            failures.put(pointer, "SOURCE_FILE_UNREADABLE");
        } catch (SourceException refused) {
            failures.put(pointer, refused.code());
        }
    }

    private static byte[] bytes(Path root, String name) throws IOException {
        try (InputStream in = Files.newInputStream(FileManifest.open(root, name), LinkOption.NOFOLLOW_LINKS)) {
            byte[] content = in.readNBytes(MappingValidator.MAX_BYTES + 1);
            if (content.length > MappingValidator.MAX_BYTES) {
                throw new SourceException("LOOKUP_FILE_TOO_LARGE", null);
            }
            return content;
        }
    }

    /** A file's rows as key to value: the header names both columns, and each key appears once. */
    private static Map<String, String> parse(CSVParser parser, String keyColumn, String valueColumn) {
        Map<String, String> values = new HashMap<>();
        Iterator<CSVRecord> records = parser.iterator();
        List<String> columns = new ArrayList<>();
        if (records.hasNext()) {
            records.next()
                    .forEach(name ->
                            columns.add(Objects.toString(name, "").strip().toLowerCase(Locale.ROOT)));
        }
        int key = column(columns, keyColumn);
        int value = column(columns, valueColumn);
        while (records.hasNext()) {
            CSVRecord record = records.next();
            if (record.size() != columns.size()
                    || record.get(key) == null
                    || record.get(value) == null
                    || values.putIfAbsent(record.get(key), record.get(value)) != null) {
                throw new SourceException("LOOKUP_FILE_INVALID", null);
            }
        }
        return Map.copyOf(values);
    }

    /** A named column's place in the header, compared in lower case like every source column. */
    private static int column(List<String> columns, String name) {
        int index = columns.indexOf(name.toLowerCase(Locale.ROOT));
        if (index < 0) {
            throw new SourceException("LOOKUP_FILE_INVALID", null);
        }
        return index;
    }

    /** The file as text; an undecodable byte is a {@code SOURCE_ENCODING}. */
    private static String decoded(byte[] content, Charset charset) {
        try {
            return CsvSource.reporting(charset).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException undecodable) {
            throw new SourceException("SOURCE_ENCODING", null);
        }
    }

    /**
     * The run's mapping hash: SHA-256 over the mapping file, then each lookup file in lookup-name order, then the
     * default-owner file, every part preceded by its length, so no two sets of files share a concatenation.
     *
     * @param mapping the mapping file's bytes
     * @return the hash, lower-case hex
     */
    String hash(byte[] mapping) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            List<byte[]> parts = new ArrayList<>(List.of(mapping));
            parts.addAll(contents.values());
            if (ownersContent != null) {
                parts.add(ownersContent);
            }
            for (byte[] part : parts) {
                sha256.update(
                        ByteBuffer.allocate(Long.BYTES).putLong(part.length).array());
                sha256.update(part);
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }

    /**
     * The lookups read, by name and legacy value.
     *
     * @return them
     */
    Map<String, Map<String, String>> values() {
        return Map.copyOf(values);
    }

    /**
     * The default owners of {@code defaults.owner.byTeam}: each Trailkeep team's name to its default owner's external
     * id or email address.
     *
     * @return them; empty when the mapping names no file
     */
    Map<String, String> defaultOwners() {
        return owners;
    }

    /**
     * Each failed lookup or default-owner file as a mapping problem at the place that names it.
     *
     * @return the problems
     */
    List<MappingProblem> problems() {
        return failures.entrySet().stream()
                .map(failure -> new MappingProblem(MappingProblem.Code.valueOf(failure.getValue()), failure.getKey()))
                .toList();
    }
}
