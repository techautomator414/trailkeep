// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.MappingProblem.Code;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A mapping's patterns and formats are compiled once, not per row, and a bad one is a mapping problem found before
 * any extract (WP-26 part k1a, design §6.3).
 */
class CompiledFormatsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void patternsAndFormatsAreCompiledOncePerMapping() {
        CompiledFormats formats = new CompiledFormats();

        assertThat(formats.pattern("C-(\\d+)")).isSameAs(formats.pattern("C-(\\d+)"));
        assertThat(formats.format("dd/MM/yyyy")).isSameAs(formats.format("dd/MM/yyyy"));
    }

    @Test
    void everyPatternFormatAndTheZoneIsCompiledUpFront() {
        JsonNode mapping = JSON.readTree("""
                {"source": {"timezone": "Mars/Olympus"},
                 "entities": {"case": {"fields": {
                   "legacyNumber": {"regexExtract": "case_no", "pattern": "C-(\\\\d+"},
                   "sensitivity": {"when": {"column": "flag", "matches": "(?<=x)y"}, "then": "RESTRICTED"},
                   "openedAt": {"timestamp": "opened", "format": "dd/MM/yyyy HH:mm"},
                   "identifiedOn": {"date": "found_on", "format": "yyyy-bb-dd"},
                   "description": {"text": "details", "format": "plain"}}}}}
                """);

        assertThat(new CompiledFormats().compileAll(mapping))
                .containsExactly(
                        new MappingProblem(Code.BAD_FORMAT, "/entities/case/fields/identifiedOn/format"),
                        new MappingProblem(Code.BAD_PATTERN, "/entities/case/fields/legacyNumber/pattern"),
                        new MappingProblem(Code.BAD_PATTERN, "/entities/case/fields/sensitivity/when/matches"),
                        new MappingProblem(Code.BAD_TIMEZONE, "/source/timezone"));
    }
}
