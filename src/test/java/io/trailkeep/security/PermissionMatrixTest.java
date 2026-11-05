// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static io.trailkeep.security.CaseAction.COMMENT;
import static io.trailkeep.security.CaseAction.CREATE;
import static io.trailkeep.security.CaseAction.DECIDE_CLOSURE;
import static io.trailkeep.security.CaseAction.DOWNLOAD_ATTACHMENT;
import static io.trailkeep.security.CaseAction.EDIT;
import static io.trailkeep.security.CaseAction.PIN;
import static io.trailkeep.security.CaseAction.REASSIGN;
import static io.trailkeep.security.CaseAction.REASSIGN_APPROVER;
import static io.trailkeep.security.CaseAction.RELEASE_QUARANTINE;
import static io.trailkeep.security.CaseAction.REOPEN;
import static io.trailkeep.security.CaseAction.REQUEST_CLOSURE;
import static io.trailkeep.security.CaseAction.REQUEST_UPDATE;
import static io.trailkeep.security.CaseAction.RESTRICT;
import static io.trailkeep.security.CaseAction.UNRESTRICT;
import static io.trailkeep.security.CaseAction.VIEW;
import static io.trailkeep.shared.CaseStatus.ARCHIVED;
import static io.trailkeep.shared.CaseStatus.CLOSED;
import static io.trailkeep.shared.CaseStatus.OPEN;
import static io.trailkeep.shared.CaseStatus.PENDING_CLOSURE;
import static io.trailkeep.shared.Sensitivity.RESTRICTED;
import static io.trailkeep.shared.Sensitivity.STANDARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The decision table against BUILD_PLAN §3.2, over every combination of role, ownership, sensitivity, status and
 * action. {@link #expected} is an independent encoding of §3.2 and its edge rules; the CSV must agree with it
 * everywhere, and every CSV row must decide at least one combination (no dead rows).
 */
class PermissionMatrixTest {

    record Combination(MatrixRole role, boolean owner, Sensitivity sensitivity, CaseStatus status, CaseAction action) {
        boolean decidedBy(PermissionMatrix matrix) {
            return matrix.decide(role, owner, sensitivity, status, action).allow();
        }
    }

    private static final PermissionMatrix MATRIX = PermissionMatrix.load();

    @Test
    void everyCombinationMatchesSectionThreeTwo() {
        List<String> disagreements = new ArrayList<>();
        forEachCombination(c -> {
            if (c.decidedBy(MATRIX) != expected(c)) {
                disagreements.add(c + " -> matrix says " + c.decidedBy(MATRIX));
            }
        });

        assertThat(disagreements).isEmpty();
    }

    @Test
    void everyRowOfTheCsvDecidesSomething() {
        Set<Integer> deciding = new HashSet<>();
        forEachCombination(c -> deciding.add(MATRIX.decide(c.role(), c.owner(), c.sensitivity(), c.status(), c.action())
                .line()));

        assertThat(MATRIX.rows())
                .allSatisfy(row -> assertThat(deciding)
                        .as("csv line %d is shadowed by earlier rows", row.line())
                        .contains(row.line()));
    }

    @Test
    void invariantsHoldForEveryCombination() {
        forEachCombination(c -> {
            boolean allowed = c.decidedBy(MATRIX);
            boolean readable =
                    new Combination(c.role(), c.owner(), c.sensitivity(), c.status(), VIEW).decidedBy(MATRIX);
            if (allowed && c.action() != CREATE) {
                assertThat(readable).as("an allowed action implies read: %s", c).isTrue();
            }
            if (allowed && c.action() == RESTRICT) {
                assertThat(new Combination(c.role(), c.owner(), RESTRICTED, c.status(), VIEW).decidedBy(MATRIX))
                        .as("whoever restricts keeps read: %s", c)
                        .isTrue();
            }
            if (c.role() == MatrixRole.SYSTEM_ADMIN || c.role() == MatrixRole.NONE) {
                assertThat(allowed).as("no case access: %s", c).isFalse();
            }
        });
    }

    @Test
    void rejectsMalformedTables() {
        assertThatThrownBy(() -> PermissionMatrix.parse("READER,*,*,*,VIEW,ALLOW\n"))
                .hasMessageContaining("must end with");
        assertThatThrownBy(() -> PermissionMatrix.parse("READER,*,*,VIEW,ALLOW\n*,*,*,*,*,DENY\n"))
                .hasMessageContaining("expected 6 columns");
        assertThatThrownBy(() -> PermissionMatrix.parse("*,*,*,*,*,MAYBE\n")).hasMessageContaining("ALLOW or DENY");
        assertThatThrownBy(() -> PermissionMatrix.parse("AUDITOR,*,*,*,*,DENY\n*,*,*,*,*,DENY\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void forEachCombination(Consumer<Combination> check) {
        for (MatrixRole role : MatrixRole.values()) {
            for (boolean owner : new boolean[] {true, false}) {
                for (Sensitivity sensitivity : Sensitivity.values()) {
                    for (CaseStatus status : CaseStatus.values()) {
                        for (CaseAction action : CaseAction.values()) {
                            check.accept(new Combination(role, owner, sensitivity, status, action));
                        }
                    }
                }
            }
        }
    }

    /** BUILD_PLAN §3.2, written independently of the CSV. */
    static boolean expected(Combination c) {
        if (c.action() == CREATE) {
            return works(c.role());
        }
        if (!readable(c)) {
            return false;
        }
        if (c.action() == VIEW || c.action() == DOWNLOAD_ATTACHMENT) {
            return true;
        }
        return works(c.role()) && c.status() != ARCHIVED && mutation(c);
    }

    private static boolean works(MatrixRole role) {
        return role == MatrixRole.ANALYST || senior(role);
    }

    private static boolean senior(MatrixRole role) {
        return role == MatrixRole.MANAGER || role == MatrixRole.COMPLIANCE_ADMIN;
    }

    private static boolean readable(Combination c) {
        return switch (c.role()) {
            case READER -> c.sensitivity() == STANDARD;
            case ANALYST -> c.sensitivity() == STANDARD || c.owner();
            case MANAGER, COMPLIANCE_ADMIN -> true;
            case SYSTEM_ADMIN, NONE -> false;
        };
    }

    private static boolean mutation(Combination c) {
        CaseStatus s = c.status();
        boolean mayManage = senior(c.role()) || c.owner();
        return switch (c.action()) {
            case EDIT, REQUEST_CLOSURE -> s == OPEN;
            case COMMENT, PIN, REQUEST_UPDATE -> s == OPEN || s == PENDING_CLOSURE;
            case RESTRICT -> c.sensitivity() == STANDARD && mayManage;
            case REASSIGN -> (s == OPEN || s == CLOSED) && mayManage;
            default -> seniorOnly(c);
        };
    }

    private static boolean seniorOnly(Combination c) {
        if (!senior(c.role())) {
            return false;
        }
        return switch (c.action()) {
            case UNRESTRICT -> c.sensitivity() == RESTRICTED;
            case DECIDE_CLOSURE -> c.status() == PENDING_CLOSURE && !c.owner();
            case REASSIGN_APPROVER -> c.status() == PENDING_CLOSURE;
            case REOPEN -> c.status() == CLOSED;
            case RELEASE_QUARANTINE -> c.role() == MatrixRole.COMPLIANCE_ADMIN;
            default -> false;
        };
    }

    @Test
    void spotChecksFromTheEdgeRules() {
        assertThat(new Combination(MatrixRole.MANAGER, true, STANDARD, PENDING_CLOSURE, DECIDE_CLOSURE)
                        .decidedBy(MATRIX))
                .as("an owner never approves their own case")
                .isFalse();
        assertThat(new Combination(MatrixRole.MANAGER, false, STANDARD, PENDING_CLOSURE, REASSIGN).decidedBy(MATRIX))
                .as("reassignment is blocked while closure is pending")
                .isFalse();
        assertThat(new Combination(MatrixRole.ANALYST, true, RESTRICTED, OPEN, UNRESTRICT).decidedBy(MATRIX))
                .as("an Analyst may restrict but never unrestrict")
                .isFalse();
        assertThat(new Combination(MatrixRole.COMPLIANCE_ADMIN, false, RESTRICTED, CLOSED, REOPEN).decidedBy(MATRIX))
                .isTrue();
        assertThat(new Combination(MatrixRole.READER, false, STANDARD, OPEN, COMMENT).decidedBy(MATRIX))
                .isFalse();
        assertThat(new Combination(MatrixRole.ANALYST, false, STANDARD, OPEN, EDIT).decidedBy(MATRIX))
                .isTrue();
        assertThat(new Combination(MatrixRole.MANAGER, false, STANDARD, ARCHIVED, REQUEST_UPDATE).decidedBy(MATRIX))
                .as("archived cases are read-only")
                .isFalse();
        assertThat(new Combination(MatrixRole.MANAGER, false, STANDARD, OPEN, RELEASE_QUARANTINE).decidedBy(MATRIX))
                .isFalse();
        assertThat(new Combination(MatrixRole.MANAGER, false, STANDARD, PENDING_CLOSURE, REASSIGN_APPROVER)
                        .decidedBy(MATRIX))
                .isTrue();
        assertThat(new Combination(MatrixRole.ANALYST, false, STANDARD, OPEN, REQUEST_CLOSURE).decidedBy(MATRIX))
                .isTrue();
        assertThat(new Combination(MatrixRole.MANAGER, false, RESTRICTED, OPEN, RESTRICT).decidedBy(MATRIX))
                .isFalse();
        assertThat(new Combination(MatrixRole.ANALYST, false, STANDARD, PENDING_CLOSURE, PIN).decidedBy(MATRIX))
                .as("whoever may comment may pin")
                .isTrue();
        assertThat(new Combination(MatrixRole.READER, false, STANDARD, OPEN, PIN).decidedBy(MATRIX))
                .as("Readers only read")
                .isFalse();
        assertThat(new Combination(MatrixRole.MANAGER, false, STANDARD, CLOSED, PIN).decidedBy(MATRIX))
                .as("a closed case's comment trail is not rearranged")
                .isFalse();
    }
}
