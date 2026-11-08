// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.Length;
import org.jspecify.annotations.Nullable;

/**
 * One scheduled report as it was sent (V7 {@code REPORT_RUN}): the HTML and CSV a Manager of the team would have got
 * from the report pages at that moment. Written once, never changed.
 */
@Entity
@Table(name = "REPORT_RUN")
class ReportRun {

    /** Which report a run holds. */
    enum Kind {
        AGING
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "reportRunSeq")
    @SequenceGenerator(name = "reportRunSeq", sequenceName = "SEQ_REPORT_RUN", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "SCHEDULE_ID", nullable = false, updatable = false)
    private long scheduleId;

    @Column(name = "TEAM_ID", nullable = false, updatable = false)
    private long teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "REPORT", nullable = false, updatable = false, length = 16)
    private Kind report = Kind.AGING;

    @Column(name = "RAN_AT", nullable = false, updatable = false)
    private Instant ranAt = Instant.EPOCH;

    @Column(name = "AS_OF", nullable = false, updatable = false)
    private LocalDate asOf = LocalDate.EPOCH;

    @Column(name = "LINE_COUNT", nullable = false, updatable = false)
    private int lineCount;

    @Column(name = "HTML", nullable = false, updatable = false, length = Length.LONG32)
    private String html = "";

    @Column(name = "CSV", nullable = false, updatable = false, length = Length.LONG32)
    private String csv = "";

    /** For JPA. */
    protected ReportRun() {}

    /**
     * A rendered aging report.
     *
     * @param schedule the schedule it ran for
     * @param ranAt when
     * @param asOf the day it is as of
     * @param lineCount how many lines its table holds, the total row not counted
     * @param html the report's HTML
     * @param csv the report's CSV
     */
    ReportRun(ReportSchedule schedule, Instant ranAt, LocalDate asOf, int lineCount, String html, String csv) {
        this.scheduleId = schedule.getId();
        this.teamId = schedule.getTeamId();
        this.ranAt = ranAt;
        this.asOf = asOf;
        this.lineCount = lineCount;
        this.html = html;
        this.csv = csv;
    }

    long getId() {
        return Objects.requireNonNull(id, "not persisted");
    }

    long getTeamId() {
        return teamId;
    }

    LocalDate getAsOf() {
        return asOf;
    }

    int getLineCount() {
        return lineCount;
    }

    String getHtml() {
        return html;
    }

    String getCsv() {
        return csv;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof ReportRun that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return ReportRun.class.hashCode();
    }
}
