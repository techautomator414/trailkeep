// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.util.UriComponentsBuilder;

/** Reading the cases list's query string: the sort allow-list, the other refusals, and the round trip to links. */
class CaseListQueryTest {

    @Test
    void noParametersMeanTheFirstPageNewestFirstWithoutArchivedCases() {
        CaseListQuery query = CaseListQuery.parse(Map.of());

        assertThat(query.filter()).isEqualTo(CaseFilter.none());
        assertThat(query.sort()).isEqualTo(CaseSort.OPENED);
        assertThat(query.descending()).isTrue();
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.toQueryString()).isEmpty();
    }

    @Test
    void everyAllowListedColumnIsAcceptedInAnyCase() {
        for (CaseSort column : CaseSort.values()) {
            assertThat(CaseListQuery.parse(Map.of("sort", List.of(column.name().toLowerCase(Locale.ROOT))))
                            .sort())
                    .isEqualTo(column);
        }
        assertThat(CaseSort.parse(" Title ")).isEqualTo(CaseSort.TITLE);
        assertThat(CaseSort.parse(null)).isEqualTo(CaseSort.OPENED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"subject", "description", "id", "title;drop", "TITLE DESC", "1", "status"})
    void columnsOutsideTheAllowListAreInvalid(String column) {
        assertInvalid(Map.of("sort", List.of(column)));
    }

    @Test
    void valuesNoFormOffersAreInvalid() {
        assertInvalid(Map.of("status", List.of("DELETED")));
        assertInvalid(Map.of("type", List.of("1 OR 1=1")));
        assertInvalid(Map.of("team", List.of("x")));
        assertInvalid(Map.of("owner", List.of("someone")));
        assertInvalid(Map.of("page", List.of("two")));
        assertInvalid(Map.of("dir", List.of("sideways")));
        assertInvalid(Map.of("q", List.of("x".repeat(CaseFilter.MAX_TEXT + 1))));
    }

    @Test
    void fullQueriesRoundTripThroughTheirQueryStringAndUnknownParametersAreIgnored() {
        CaseListQuery query = CaseListQuery.parse(Map.of(
                "q", List.of("  50% off_line & more  "),
                "status", List.of("closed", "OPEN"),
                "type", List.of("7"),
                "team", List.of("8"),
                "owner", List.of("me"),
                "sort", List.of("title"),
                "dir", List.of("desc"),
                "page", List.of("3"),
                "unknown", List.of("ignored")));

        assertThat(query.filter())
                .isEqualTo(new CaseFilter(
                        Set.of(CaseStatus.OPEN, CaseStatus.CLOSED), 7L, 8L, null, true, "50% off_line & more"));
        assertThat(query.toQueryString())
                .isEqualTo("q=50%25+off_line+%26+more&status=OPEN&status=CLOSED&type=7&team=8&owner=me"
                        + "&sort=title&dir=desc&page=3");
        assertThat(CaseListQuery.parse(parameters(query.toQueryString()))).isEqualTo(query);
        assertThat(CaseListQuery.parse(Map.of("dir", List.of("asc"))).toQueryString())
                .isEqualTo("sort=opened&dir=asc");
        assertThat(CaseListQuery.parse(Map.of("status", List.of(""), "owner", List.of("")))
                        .filter())
                .isEqualTo(CaseFilter.none());
    }

    @Test
    void headerClicksToggleTheCurrentColumnAndStartOthersInTheirFirstDirectionOnPageOne() {
        CaseListQuery query = CaseListQuery.parse(Map.of("page", List.of("4")));

        assertThat(query.sortedBy(CaseSort.OPENED).descending()).isFalse();
        assertThat(query.sortedBy(CaseSort.TITLE))
                .isEqualTo(new CaseListQuery(query.filter(), CaseSort.TITLE, false, 1));
        assertThat(query.onPage(0).page()).isEqualTo(1);
        assertThat(CaseListQuery.parse(Map.of("page", List.of("99999999999"))).page())
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void mineBecomesTheOwnerOnlyWhenAsked() {
        CaseFilter mine = new CaseFilter(Set.of(), null, null, 5L, true, "");

        assertThat(mine.forOwner(9)).isEqualTo(new CaseFilter(Set.of(), null, null, 9L, false, ""));
        assertThat(CaseFilter.none().forOwner(9)).isEqualTo(CaseFilter.none());
    }

    @Test
    void likePatternsMatchWildcardsAndTheEscapeLiterally() {
        assertThat(LikeSearchGateway.contains("50%_a\\b")).isEqualTo("%50\\%\\_a\\\\b%");
        assertThat(LikeSearchGateway.contains("plain")).isEqualTo("%plain%");
    }

    private static void assertInvalid(Map<String, List<String>> parameters) {
        assertThatThrownBy(() -> CaseListQuery.parse(parameters))
                .isInstanceOfSatisfying(
                        CaseException.class,
                        refused -> assertThat(refused.kind()).isEqualTo(Kind.INVALID));
    }

    private static Map<String, List<String>> parameters(String queryString) {
        return UriComponentsBuilder.fromUriString("/cases?" + queryString).build().getQueryParams().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .map(value -> URLDecoder.decode(value, StandardCharsets.UTF_8))
                                .toList()));
    }
}
