// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** The parsed first-match decision table of {@code permissions/matrix.csv}. Immutable and thread-safe. */
final class PermissionMatrix {

    static final String RESOURCE = "/permissions/matrix.csv";

    /** One row; each set holds the values the row matches. */
    record Row(
            int line,
            Set<MatrixRole> roles,
            Set<Boolean> owner,
            Set<Sensitivity> sensitivities,
            Set<CaseStatus> statuses,
            Set<CaseAction> actions,
            boolean allow) {

        boolean matches(MatrixRole role, boolean isOwner, Sensitivity sensitivity, CaseStatus status, CaseAction a) {
            return roles.contains(role)
                    && owner.contains(isOwner)
                    && sensitivities.contains(sensitivity)
                    && statuses.contains(status)
                    && actions.contains(a);
        }
    }

    private final List<Row> rows;

    private PermissionMatrix(List<Row> rows) {
        Row last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        if (last == null
                || last.allow()
                || last.roles().size() < MatrixRole.values().length
                || last.owner().size() < 2
                || last.sensitivities().size() < Sensitivity.values().length
                || last.statuses().size() < CaseStatus.values().length
                || last.actions().size() < CaseAction.values().length) {
            throw new IllegalArgumentException("the permission matrix must end with *,*,*,*,*,DENY");
        }
        this.rows = List.copyOf(rows);
    }

    static PermissionMatrix load() {
        try (InputStream in = PermissionMatrix.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }

    static PermissionMatrix parse(String csv) {
        List<String> lines = csv.lines().toList();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (!line.isEmpty() && !line.startsWith("#")) {
                rows.add(row(i + 1, line.split(",", -1)));
            }
        }
        return new PermissionMatrix(rows);
    }

    private static Row row(int line, String[] cells) {
        if (cells.length != 6) {
            throw new IllegalArgumentException("matrix line " + line + ": expected 6 columns");
        }
        String decision = cells[5].strip();
        if (!decision.equals("ALLOW") && !decision.equals("DENY")) {
            throw new IllegalArgumentException("matrix line " + line + ": decision must be ALLOW or DENY");
        }
        Set<Boolean> owner = cells[1].strip().equals("*")
                ? Set.of(true, false)
                : values(cells[1], RelationshipColumn.class).stream()
                        .map(r -> r == RelationshipColumn.OWNER)
                        .collect(Collectors.toUnmodifiableSet());
        return new Row(
                line,
                values(cells[0], MatrixRole.class),
                owner,
                values(cells[2], Sensitivity.class),
                values(cells[3], CaseStatus.class),
                values(cells[4], CaseAction.class),
                decision.equals("ALLOW"));
    }

    private static <E extends Enum<E>> Set<E> values(String cell, Class<E> type) {
        String spec = cell.strip();
        if (spec.equals("*")) {
            return EnumSet.allOf(type);
        }
        EnumSet<E> set = EnumSet.noneOf(type);
        Arrays.stream(spec.split("\\|"))
                .forEach(v -> set.add(Enum.valueOf(type, v.strip().toUpperCase(Locale.ROOT))));
        return set;
    }

    /** The relationship column's vocabulary. */
    private enum RelationshipColumn {
        OWNER,
        NOT_OWNER
    }

    /**
     * The first matching row; every combination matches at least the catch-all last row.
     *
     * @return the deciding row
     */
    Row decide(MatrixRole role, boolean isOwner, Sensitivity sensitivity, CaseStatus status, CaseAction action) {
        return rows.stream()
                .filter(row -> row.matches(role, isOwner, sensitivity, status, action))
                .findFirst()
                .orElseThrow(); // unreachable: the constructor requires a catch-all last row
    }

    List<Row> rows() {
        return rows;
    }
}
