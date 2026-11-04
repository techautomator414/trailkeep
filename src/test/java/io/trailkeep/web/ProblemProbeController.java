// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.shared.CaseNotFoundException;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Test-only: throws each kind of {@link DomainException} so the advice can be observed. Never packaged. */
@TestComponent
@Controller
class ProblemProbeController {

    @GetMapping("/test-only/problem/{kind}")
    String problem(@PathVariable DomainException.Kind kind) {
        if (kind == DomainException.Kind.NOT_FOUND) {
            throw new CaseNotFoundException(CaseNumber.parse("CASE-2026-000001"));
        }
        throw new ProbeException(kind);
    }

    /** A domain exception of any kind, carrying a message that must never reach the response. */
    static final class ProbeException extends DomainException {
        private static final long serialVersionUID = 1L;

        ProbeException(Kind kind) {
            super(kind, "secret-detail-not-for-users");
        }
    }
}
