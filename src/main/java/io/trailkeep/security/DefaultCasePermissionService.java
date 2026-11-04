// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
class DefaultCasePermissionService implements CasePermissionService {

    private final PermissionMatrix matrix;
    private final UserDirectoryService directory;

    @Autowired
    DefaultCasePermissionService(UserDirectoryService directory) {
        this(PermissionMatrix.load(), directory);
    }

    DefaultCasePermissionService(PermissionMatrix matrix, UserDirectoryService directory) {
        this.matrix = matrix;
        this.directory = directory;
    }

    @Override
    public boolean isAllowed(UserPermissions user, CaseFacts facts, CaseAction action) {
        Long ownerId = facts.ownerId();
        boolean owner = ownerId != null && ownerId == user.userId();
        return matrix.decide(roleOf(user, facts.teamId()), owner, facts.sensitivity(), facts.status(), action)
                .allow();
    }

    @Override
    public boolean mayOwn(UserPermissions user, long teamId) {
        TeamRole role = user.has(GlobalRole.SYSTEM_ADMIN) ? null : user.roleIn(teamId);
        return role == TeamRole.ANALYST || role == TeamRole.MANAGER;
    }

    @Override
    public List<Long> approverCandidates(CaseFacts facts, long requesterId) {
        List<Long> managers = excluding(directory.activeManagersOf(facts.teamId()), facts, requesterId);
        return managers.isEmpty() ? excluding(directory.activeComplianceAdmins(), facts, requesterId) : managers;
    }

    private static List<Long> excluding(List<Long> ids, CaseFacts facts, long requesterId) {
        return ids.stream()
                .filter(id -> id != requesterId && !Objects.equals(id, facts.ownerId()))
                .toList();
    }

    static MatrixRole roleOf(UserPermissions user, long teamId) {
        if (user.has(GlobalRole.SYSTEM_ADMIN)) {
            return MatrixRole.SYSTEM_ADMIN;
        }
        if (user.has(GlobalRole.COMPLIANCE_ADMIN)) {
            return MatrixRole.COMPLIANCE_ADMIN;
        }
        TeamRole role = user.roleIn(teamId);
        return role == null ? MatrixRole.NONE : MatrixRole.valueOf(role.name());
    }
}
