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

    /** The structural checks of 26b; their names are the issue codes. */
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
        BAD_TIMEZONE
    }

    @Override
    public int compareTo(MappingProblem other) {
        int byPointer = pointer.compareTo(other.pointer);
        return byPointer != 0 ? byPointer : code.compareTo(other.code);
    }
}
