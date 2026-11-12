// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

/**
 * One reason a mapping file fails {@code validate-mapping} (design §6, §10): a code and where in the file, as a JSON
 * pointer of key names and indexes. Never a value from the file.
 *
 * @param code what is wrong
 * @param pointer where, for example {@code /entities/comment/parent/columns}; empty for the whole file
 */
record MappingProblem(Code code, String pointer) implements Comparable<MappingProblem> {

    /**
     * The structural checks (26b), query checks (26m), test fetch (26n) and a files source's manifest check (26n2, with
     * the extract's codes); their names are the issue codes.
     */
    enum Code {
        /** Larger than {@link MappingValidator#MAX_BYTES}. */
        MAPPING_TOO_LARGE,
        /** Not readable as YAML holding one object. */
        MAPPING_UNREADABLE,
        /** Fails the JSON Schema (unknown key, wrong type, missing required key, bad enum value). */
        MAPPING_SCHEMA,
        /** A {@code ${…}} outside {@code source.url} and {@code source.user}, or a name outside import.source.*. */
        PLACEHOLDER_NOT_ALLOWED,
        /** Something that looks like a credential ({@code password=} and the like). */
        CREDENTIAL_IN_MAPPING,
        /** A file path that is absolute or leaves the mapping file's directory. */
        PATH_ESCAPES_ROOT,
        /** A child entity's parent columns do not match the case key's number of components. */
        KEY_ARITY_MISMATCH,
        /** A field names a lookup the file does not define. */
        UNKNOWN_LOOKUP,
        /** A {@code maxLength} above what the Trailkeep column holds. */
        MAX_LENGTH_OVER_TARGET,
        /** {@code limits.acceptSkip} names a code that may not be skipped. */
        ACCEPT_SKIP_NOT_ALLOWED,
        /** A {@code regexExtract} pattern or {@code when.matches} that RE2 cannot compile. */
        BAD_PATTERN,
        /** A {@code timestamp} or {@code date} format that is not a valid pattern. */
        BAD_FORMAT,
        /** A {@code source.timezone} that is not a known zone id. */
        BAD_TIMEZONE,
        /** A source query the parser cannot read (design §4.1); it is refused, never passed. */
        QUERY_UNPARSEABLE,
        /** A source query that is not exactly one statement. */
        QUERY_NOT_ONE_STATEMENT,
        /** A source query that is not a {@code SELECT} or {@code WITH … SELECT}. */
        QUERY_NOT_SELECT,
        /** A source query with {@code INTO}. */
        QUERY_INTO,
        /** A source query that locks: {@code FOR UPDATE} or a locking table hint. */
        QUERY_LOCKS,
        /** A source query reading a table function such as {@code OPENROWSET}. */
        QUERY_TABLE_FUNCTION,
        /** A source query through an Oracle database link or a SQL Server linked server (a four-part name). */
        QUERY_DATABASE_LINK,
        /** A source query calling a function outside the vendor's allow-list of pure built-ins. */
        QUERY_FUNCTION_NOT_ALLOWED,
        /** A source that cannot serve what the mapping asks: no source for its kind, or content queries on files. */
        SOURCE_UNSUPPORTED,
        /** A source URL with another driver's scheme or a parameter outside the allow-list ({@link SourceUrl}). */
        SOURCE_URL_NOT_ALLOWED,
        /** A setting the test fetch needs is not configured: an {@code import.source.*} placeholder or the password. */
        SOURCE_SETTING_MISSING,
        /** The source could not be reached, or refused the account. */
        SOURCE_UNAVAILABLE,
        /** The source account may not read what a query reads. */
        SOURCE_PERMISSION_DENIED,
        /** A query's test fetch ran past the statement timeout. */
        SOURCE_TIMEOUT,
        /** A query's test fetch failed for any other reason: the source holds no such table or column, for example. */
        SOURCE_QUERY_FAILED,
        /** A files source names no manifest. */
        MANIFEST_MISSING,
        /** The manifest is not readable YAML, fails its schema or lists a file or content id twice. */
        MANIFEST_INVALID,
        /** The manifest does not list a mapped file for its entity, or the file's SHA-256 differs from its entry. */
        MANIFEST_MISMATCH,
        /** A mapped file or the manifest does not exist, or is not a regular file. */
        SOURCE_FILE_MISSING,
        /** A mapped file or the manifest cannot be read. */
        SOURCE_FILE_UNREADABLE,
        /** A mapped file's real path, symlinks followed, leaves the mapping file's directory. */
        PATH_OUTSIDE_ROOT,
        /** {@code attachments.root} is not a readable folder. */
        ATTACHMENTS_ROOT_UNREADABLE
    }

    @Override
    public int compareTo(MappingProblem other) {
        int byPointer = pointer.compareTo(other.pointer);
        return byPointer != 0 ? byPointer : code.compareTo(other.code);
    }
}
