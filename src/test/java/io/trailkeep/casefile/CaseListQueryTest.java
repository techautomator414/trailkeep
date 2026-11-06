// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Reading the cases list's query string: the sort allow-list, the other refusals (unknown SLA states, priorities and
 * sensitivities, dates that are not ISO, a range that ends before it starts), the round trip to links, and the chips
 * that remove one criterion each.
 */
class CaseListQueryTest {

    private static final LocalDate OCTOBER_1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate OCTOBER_31 = LocalDate.of(2026, 10, 31);

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
        assertInvalid(Map.of("sla", List.of("LATE")));
        assertInvalid(Map.of("sla", List.of("OVERDUE", "ON_TRACK;")));
        assertInvalid(Map.of("priority", List.of("URGENT")));
        assertInvalid(Map.of("sensitivity", List.of("SECRET")));
        assertInvalid(Map.of("from", List.of("01/10/2026")));
        assertInvalid(Map.of("to", List.of("2026-02-30")));
        assertInvalid(Map.of("from", List.of("2026-10-01T00:00")));
        assertInvalid(Map.of("from", List.of("2026-10-02"), "to", List.of("2026-10-01")));
    }

    @Test
    void fullQueriesRoundTripThroughTheirQueryStringAndUnknownParametersAreIgnored() {
        CaseListQuery query = CaseListQuery.parse(Map.ofEntries(
                Map.entry("q", List.of("  50% off_line & more  ")),
                Map.entry("status", List.of("closed", "OPEN")),
                Map.entry("sla", List.of("overdue", "AT_RISK")),
                Map.entry("priority", List.of("high")),
                Map.entry("sensitivity", List.of("Restricted")),
                Map.entry("from", List.of("2026-10-01")),
                Map.entry("to", List.of(" 2026-10-31 ")),
                Map.entry("type", List.of("7")),
                Map.entry("team", List.of("8")),
                Map.entry("owner", List.of("me")),
                Map.entry("sort", List.of("title")),
                Map.entry("dir", List.of("desc")),
                Map.entry("page", List.of("3")),
                Map.entry("filters", List.of("1")),
                Map.entry("unknown", List.of("ignored"))));

        assertThat(query.filter())
                .isEqualTo(FilterBuilder.filter()
                        .statuses(CaseStatus.OPEN, CaseStatus.CLOSED)
                        .sla(SlaState.AT_RISK, SlaState.OVERDUE)
                        .priority(CasePriority.HIGH)
                        .sensitivity(Sensitivity.RESTRICTED)
                        .identified(OCTOBER_1, OCTOBER_31)
                        .type(7L)
                        .team(8L)
                        .mine()
                        .text("50% off_line & more")
                        .build());
        assertThat(query.toQueryString())
                .isEqualTo("q=50%25+off_line+%26+more&status=OPEN&status=CLOSED&sla=AT_RISK&sla=OVERDUE"
                        + "&priority=HIGH&sensitivity=RESTRICTED&from=2026-10-01&to=2026-10-31&type=7&team=8&owner=me"
                        + "&sort=title&dir=desc&page=3");
        assertThat(CaseListQuery.parse(parameters(query.toQueryString()))).isEqualTo(query);
        assertThat(CaseListQuery.parse(Map.of("dir", List.of("asc"))).toQueryString())
                .isEqualTo("sort=opened&dir=asc");
        assertThat(CaseListQuery.parse(Map.of(
                                "status", List.of(""),
                                "sla", List.of(" "),
                                "priority", List.of(""),
                                "sensitivity", List.of(""),
                                "from", List.of(""),
                                "to", List.of(""),
                                "owner", List.of("")))
                        .filter())
                .isEqualTo(CaseFilter.none());
    }

    @Test
    void selectedCaseNumbersAreReadCanonicallyAndMalformedOnesOrTooManyAreInvalid() {
        CaseListQuery query = CaseListQuery.parse(
                Map.of("ids", List.of(" CASE-2026-000002", "CASE-2026-000001", ""), "status", List.of("OPEN")));

        assertThat(query.filter().numbers()).containsExactlyInAnyOrder("CASE-2026-000001", "CASE-2026-000002");
        assertThat(query.toQueryString()).isEqualTo("status=OPEN&ids=CASE-2026-000001&ids=CASE-2026-000002");
        assertThat(CaseListQuery.parse(parameters(query.toQueryString()))).isEqualTo(query);
        assertThat(query.criteria()).extracting(CaseListQuery.Criterion::name).containsExactly("status", "ids", "ids");
        assertInvalid(Map.of("ids", List.of("CASE-2026-1")));
        assertInvalid(Map.of("ids", List.of("1 OR 1=1")));
        assertInvalid(Map.of(
                "ids",
                IntStream.rangeClosed(1, CaseFilter.MAX_NUMBERS + 1)
                        .mapToObj(i -> String.format(Locale.ROOT, "CASE-2026-%06d", i))
                        .toList()));
    }

    @Test
    void oneDayRangesAndOpenEndedRangesAreAccepted() {
        assertThat(CaseListQuery.parse(Map.of("from", List.of("2026-10-01"), "to", List.of("2026-10-01")))
                        .filter())
                .isEqualTo(
                        FilterBuilder.filter().identified(OCTOBER_1, OCTOBER_1).build());
        assertThat(CaseListQuery.parse(Map.of("to", List.of("2026-10-31"))).toQueryString())
                .isEqualTo("to=2026-10-31");
        assertThat(CaseListQuery.parse(Map.of("from", List.of("2026-10-01")))
                        .filter()
                        .identifiedTo())
                .isNull();
    }

    @Test
    void eachCriterionBecomesChipWhoseRemovalKeepsTheRestAndTheOrderOnPageOne() {
        CaseListQuery query = CaseListQuery.parse(Map.of(
                "q", List.of("heron 50%"),
                "status", List.of("OPEN", "CLOSED"),
                "sla", List.of("OVERDUE"),
                "from", List.of("2026-10-01"),
                "owner", List.of("me"),
                "sort", List.of("title"),
                "page", List.of("4")));

        assertThat(query.criteria())
                .extracting(CaseListQuery.Criterion::name, CaseListQuery.Criterion::value)
                .containsExactly(
                        tuple("q", "heron 50%"),
                        tuple("status", "OPEN"),
                        tuple("status", "CLOSED"),
                        tuple("sla", "OVERDUE"),
                        tuple("from", "2026-10-01"),
                        tuple("owner", "me"));
        assertThat(query.criteria())
                .extracting(CaseListQuery.Criterion::without)
                .containsExactly(
                        "status=OPEN&status=CLOSED&sla=OVERDUE&from=2026-10-01&owner=me&sort=title&dir=asc",
                        "q=heron+50%25&status=CLOSED&sla=OVERDUE&from=2026-10-01&owner=me&sort=title&dir=asc",
                        "q=heron+50%25&status=OPEN&sla=OVERDUE&from=2026-10-01&owner=me&sort=title&dir=asc",
                        "q=heron+50%25&status=OPEN&status=CLOSED&from=2026-10-01&owner=me&sort=title&dir=asc",
                        "q=heron+50%25&status=OPEN&status=CLOSED&sla=OVERDUE&owner=me&sort=title&dir=asc",
                        "q=heron+50%25&status=OPEN&status=CLOSED&sla=OVERDUE&from=2026-10-01&sort=title&dir=asc");
        CaseListQuery withoutOpen =
                CaseListQuery.parse(parameters(query.criteria().get(1).without()));
        assertThat(withoutOpen.filter().statuses()).containsExactly(CaseStatus.CLOSED);
        assertThat(withoutOpen.page()).isEqualTo(1);
        assertThat(withoutOpen.sort()).isEqualTo(CaseSort.TITLE);
        assertThat(CaseListQuery.parse(Map.of("sort", List.of("title"))).criteria())
                .isEmpty();
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
        CaseFilter mine = FilterBuilder.filter()
                .sla(SlaState.MET)
                .priority(CasePriority.NORMAL)
                .sensitivity(Sensitivity.STANDARD)
                .identified(OCTOBER_1, OCTOBER_31)
                .owner(5L)
                .mine()
                .build();

        assertThat(mine.forOwner(9))
                .isEqualTo(FilterBuilder.filter()
                        .sla(SlaState.MET)
                        .priority(CasePriority.NORMAL)
                        .sensitivity(Sensitivity.STANDARD)
                        .identified(OCTOBER_1, OCTOBER_31)
                        .owner(9L)
                        .build());
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
