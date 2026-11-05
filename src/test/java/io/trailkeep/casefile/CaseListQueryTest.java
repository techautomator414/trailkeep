// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.shared.DomainException.Kind;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.util.UriComponentsBuilder;

/** Reading the cases list's query string: the sort allow-list, the other refusals, and the round trip to links. */
class CaseListQueryTest {

    @Test
    void noParametersMeanTheFirstPageNewestFirst() {
        CaseListQuery query = CaseListQuery.parse(Map.of());

        assertThat(query).isEqualTo(new CaseListQuery(CaseSort.OPENED, true, 1));
        assertThat(query.toQueryString()).isEmpty();
    }

    @Test
    void everyAllowListedColumnIsAcceptedInAnyCase() {
        for (CaseSort column : CaseSort.values()) {
            String name = column.name().toLowerCase(Locale.ROOT);
            assertThat(CaseListQuery.parse(Map.of("sort", List.of(name))).sort())
                    .isEqualTo(column);
        }
        assertThat(CaseSort.parse(" Title ")).isEqualTo(CaseSort.TITLE);
        assertThat(CaseSort.parse(null)).isEqualTo(CaseSort.OPENED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"subject", "description", "id", "status", "title;drop", "TITLE DESC", "1"})
    void columnsOutsideTheAllowListAreInvalid(String column) {
        assertInvalid(Map.of("sort", List.of(column)));
    }

    @Test
    void otherValuesNoPageOffersAreInvalid() {
        assertInvalid(Map.of("dir", List.of("sideways")));
        assertInvalid(Map.of("page", List.of("two")));
    }

    @Test
    void queriesRoundTripThroughTheirQueryStringAndUnknownParametersAreIgnored() {
        CaseListQuery query = CaseListQuery.parse(Map.of(
                "sort", List.of("title"), "dir", List.of("desc"), "page", List.of("3"), "unknown", List.of("x")));

        assertThat(query).isEqualTo(new CaseListQuery(CaseSort.TITLE, true, 3));
        assertThat(query.toQueryString()).isEqualTo("sort=title&dir=desc&page=3");
        assertThat(CaseListQuery.parse(parameters(query.toQueryString()))).isEqualTo(query);
        assertThat(CaseListQuery.parse(Map.of("dir", List.of("asc"))).toQueryString())
                .isEqualTo("sort=opened&dir=asc");
    }

    @Test
    void headerClicksToggleTheCurrentColumnAndStartOthersInTheirFirstDirectionOnPageOne() {
        CaseListQuery query = CaseListQuery.parse(Map.of("page", List.of("4")));

        assertThat(query.sortedBy(CaseSort.OPENED)).isEqualTo(new CaseListQuery(CaseSort.OPENED, false, 1));
        assertThat(query.sortedBy(CaseSort.TITLE)).isEqualTo(new CaseListQuery(CaseSort.TITLE, false, 1));
        assertThat(query.onPage(0).page()).isEqualTo(1);
        assertThat(CaseListQuery.parse(Map.of("page", List.of("99999999999"))).page())
                .isEqualTo(Integer.MAX_VALUE);
    }

    private static void assertInvalid(Map<String, List<String>> parameters) {
        assertThatThrownBy(() -> CaseListQuery.parse(parameters))
                .isInstanceOfSatisfying(
                        CaseException.class,
                        refused -> assertThat(refused.kind()).isEqualTo(Kind.INVALID));
    }

    private static Map<String, List<String>> parameters(String queryString) {
        return UriComponentsBuilder.fromUriString("/cases?" + queryString).build().getQueryParams().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
