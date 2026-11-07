// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The reports' hand-computed fixture (WP-22): two teams, two case types and nineteen synthetic cases written straight
 * to the tables, ages counted back from {@code today} and closures placed on the edges of the period
 * {@link #from()} to {@link #to()} (today − 14 to today − 1, whole days in the given zone). Use it inside a
 * rolled-back transaction. What each case is, and so what each report cell must say:
 *
 * <pre>
 * team ALPHA, type ACCESS (owner ANALYST unless noted)
 *   OPEN       identified today        ON_TRACK        aging 0–30
 *   OPEN       identified today − 30   AT_RISK         aging 0–30 (edge)
 *   PENDING    identified today − 31   OVERDUE         aging 31–60 (edge)
 *   OPEN       identified today − 61   NOT_APPLICABLE  aging 61–90 (edge)
 *   OPEN       identified today − 90   ON_TRACK        aging 61–90 (edge)
 *   OPEN       identified today − 91   OVERDUE         aging over 90 (edge)
 *   OPEN       RESTRICTED, owner OTHER_OWNER, today − 5, OVERDUE: a Manager reads it, the Analyst does not
 *   CLOSED     at the period's first instant           MET       counted
 *   CLOSED     at 23:00 on the period's last day       BREACHED  counted
 *   CLOSED     one second before the period            MET       not counted
 *   ARCHIVED   inside the period                       MET       not counted (archived, not closed)
 * team ALPHA, type HANDLING
 *   OPEN       identified today − 60   AT_RISK         aging 31–60 (edge)
 *   CLOSED     inside the period                       MET       counted
 *   CLOSED     at the first instant after the period   BREACHED  not counted
 * team BETA, type ACCESS (owner BETA_ANALYST)
 *   OPEN       identified today − 10   ON_TRACK        aging 0–30
 *   CLOSED     inside the period                       MET       counted
 *   CLOSED     inside the period                       BREACHED  counted
 *   OPEN       identified today − 45   OVERDUE         aging 31–60
 *   PENDING    identified today − 95   AT_RISK         aging over 90
 * </pre>
 */
public final class ReportFixture {

    public static final long MANAGER = 984_001L;
    public static final long ANALYST = 984_002L;
    public static final long OTHER_OWNER = 984_003L;
    public static final long BETA_ANALYST = 984_004L;
    public static final long SYSTEM_ADMIN = 984_005L;
    public static final long ALPHA = 984_011L;
    public static final long BETA = 984_012L;
    public static final String ALPHA_NAME = "Alpha Reporting Team";
    public static final String BETA_NAME = "Beta Reporting Team";
    public static final String ACCESS = "Access review";
    public static final String HANDLING = "Data handling";
    private static final long ACCESS_ID = 984_021L;
    private static final long HANDLING_ID = 984_022L;
    private static final long POLICY = 984_031L;
    private static final long FIRST_CASE = 984_100L;
    private static final String OPEN = "OPEN";
    private static final String PENDING = "PENDING_CLOSURE";
    private static final String CLOSED = "CLOSED";

    private final JdbcTemplate jdbc;
    private final LocalDate today;
    private final ZoneId zone;
    private long next = FIRST_CASE;

    /**
     * A fixture to be written by {@link #seed()}.
     *
     * @param jdbc the database
     * @param today the day the ages count back from
     * @param zone the zone the period's days are in
     */
    public ReportFixture(JdbcTemplate jdbc, LocalDate today, ZoneId zone) {
        this.jdbc = jdbc;
        this.today = today;
        this.zone = zone;
    }

    /**
     * The period's first day.
     *
     * @return today − 14
     */
    public LocalDate from() {
        return today.minusDays(14);
    }

    /**
     * The period's last day.
     *
     * @return today − 1
     */
    public LocalDate to() {
        return today.minusDays(1);
    }

    /** Writes the users, teams, case types and cases in the class comment. */
    public void seed() {
        people();
        jdbc.update("INSERT INTO SLA_POLICY (ID, NAME, TARGET_DAYS) VALUES (?, ?, ?)", POLICY, "Report policy", 30);
        jdbc.update("INSERT INTO CASE_TYPE (ID, NAME, SLA_POLICY_ID) VALUES (?, ?, ?)", ACCESS_ID, ACCESS, POLICY);
        jdbc.update("INSERT INTO CASE_TYPE (ID, NAME, SLA_POLICY_ID) VALUES (?, ?, ?)", HANDLING_ID, HANDLING, POLICY);
        final Instant first = from().atStartOfDay(zone).toInstant();
        final Instant after = to().plusDays(1).atStartOfDay(zone).toInstant();
        final Instant inside = from().plusDays(3).atStartOfDay(zone).toInstant().plusSeconds(3600);
        open(ALPHA, ACCESS_ID, ANALYST, OPEN, 0, "ON_TRACK");
        open(ALPHA, ACCESS_ID, ANALYST, OPEN, 30, "AT_RISK");
        open(ALPHA, ACCESS_ID, ANALYST, PENDING, 31, "OVERDUE");
        open(ALPHA, ACCESS_ID, ANALYST, OPEN, 61, "NOT_APPLICABLE");
        open(ALPHA, ACCESS_ID, ANALYST, OPEN, 90, "ON_TRACK");
        open(ALPHA, ACCESS_ID, ANALYST, OPEN, 91, "OVERDUE");
        kase(ALPHA, ACCESS_ID, OTHER_OWNER, "RESTRICTED", OPEN, today.minusDays(5), "OVERDUE", null);
        closed(ALPHA, ACCESS_ID, ANALYST, CLOSED, first, "MET");
        closed(ALPHA, ACCESS_ID, ANALYST, CLOSED, after.minusSeconds(3600), "BREACHED");
        closed(ALPHA, ACCESS_ID, ANALYST, CLOSED, first.minusSeconds(1), "MET");
        closed(ALPHA, ACCESS_ID, ANALYST, "ARCHIVED", inside, "MET");
        open(ALPHA, HANDLING_ID, ANALYST, OPEN, 60, "AT_RISK");
        closed(ALPHA, HANDLING_ID, ANALYST, CLOSED, inside, "MET");
        closed(ALPHA, HANDLING_ID, ANALYST, CLOSED, after, "BREACHED");
        open(BETA, ACCESS_ID, BETA_ANALYST, OPEN, 10, "ON_TRACK");
        closed(BETA, ACCESS_ID, BETA_ANALYST, CLOSED, inside, "MET");
        closed(BETA, ACCESS_ID, BETA_ANALYST, CLOSED, inside.plusSeconds(60), "BREACHED");
        open(BETA, ACCESS_ID, BETA_ANALYST, OPEN, 45, "OVERDUE");
        open(BETA, ACCESS_ID, BETA_ANALYST, PENDING, 95, "AT_RISK");
    }

    /** The users, the teams and who is what in them; {@link #SYSTEM_ADMIN} holds only that global role. */
    private void people() {
        user(MANAGER, "Synthetic Report Manager");
        user(ANALYST, "Synthetic Report Analyst");
        user(OTHER_OWNER, "Synthetic Restricted Owner");
        user(BETA_ANALYST, "Synthetic Beta Analyst");
        user(SYSTEM_ADMIN, "Synthetic System Admin");
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'SYSTEM_ADMIN')", SYSTEM_ADMIN);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, ?)", ALPHA, ALPHA_NAME);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, ?)", BETA, BETA_NAME);
        member(MANAGER, ALPHA, "MANAGER");
        member(ANALYST, ALPHA, "ANALYST");
        member(OTHER_OWNER, ALPHA, "ANALYST");
        member(BETA_ANALYST, BETA, "ANALYST");
    }

    private void member(long user, long team, String role) {
        jdbc.update("INSERT INTO APP_USER_TEAM (USER_ID, TEAM_ID, ROLE_IN_TEAM) VALUES (?, ?, ?)", user, team, role);
    }

    private void open(long team, long type, long owner, String status, int age, String sla) {
        kase(team, type, owner, "STANDARD", status, today.minusDays(age), sla, null);
    }

    private void closed(long team, long type, long owner, String status, Instant at, String sla) {
        kase(team, type, owner, "STANDARD", status, from().minusDays(40), sla, at);
    }

    private void kase(
            long team,
            long type,
            long owner,
            String sensitivity,
            String status,
            LocalDate identified,
            String sla,
            @Nullable Instant closedAt) {
        long id = next++;
        Timestamp created = Timestamp.from(identified.atStartOfDay(zone).toInstant());
        jdbc.update(
                "INSERT INTO CASE_RECORD (ID, CASE_NUMBER, TITLE, DESCRIPTION, CASE_TYPE_ID, TEAM_ID, OWNER_ID,"
                        + " STATUS, SENSITIVITY, IDENTIFIED_ON, OPENED_AT, CREATED_AT, CREATED_BY, UPDATED_AT,"
                        + " UPDATED_BY) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                "RPT-2026-" + id,
                "Synthetic report case " + id,
                "Synthetic description",
                type,
                team,
                owner,
                status,
                sensitivity,
                Date.valueOf(identified),
                created,
                created,
                owner,
                created,
                owner);
        if (closedAt != null) {
            jdbc.update("UPDATE CASE_RECORD SET CLOSED_AT = ? WHERE ID = ?", Timestamp.from(closedAt), id);
        }
        Timestamp due =
                Timestamp.from(identified.plusDays(30).atStartOfDay(zone).toInstant());
        jdbc.update(
                "INSERT INTO CASE_SLA (CASE_ID, SLA_STATE, SLA_DUE_AT, AT_RISK_AT) VALUES (?, ?, ?, ?)",
                id,
                sla,
                due,
                due);
    }

    private void user(long id, String name) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, CREATED_AT) VALUES (?, ?, ?, ?, ?)",
                id,
                "report-" + id,
                name,
                "report" + id + "@example.test",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
    }
}
