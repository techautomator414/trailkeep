// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.shared.CaseNotFoundException;
import io.trailkeep.shared.CaseNumber;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Proxy;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CaseScopeTest {

    private static final CaseNumber READABLE = CaseNumber.parse("CASE-2026-000001");
    private static final CaseNumber HIDDEN = CaseNumber.parse("CASE-2026-000002");

    @Test
    void assertReadableThrowsTheSameNotFoundAsAnAbsentCase() {
        CaseScope scope = new OnlyScope(Set.of(READABLE));

        assertThatNoException().isThrownBy(() -> scope.assertReadable(READABLE));
        assertThatThrownBy(() -> scope.assertReadable(HIDDEN))
                .isExactlyInstanceOf(CaseNotFoundException.class)
                .hasMessage(new CaseNotFoundException(HIDDEN).getMessage())
                .extracting("caseNumber")
                .isEqualTo(HIDDEN);
    }

    @Test
    void systemAndAllAreUnrestrictedButDistinct() {
        assertThat(CaseScope.system()).isNotSameAs(CaseScope.all());
        assertThat(CaseScope.system().canRead(HIDDEN)).isTrue();
        assertThat(CaseScope.all().canRead(HIDDEN)).isTrue();
        assertThatNoException().isThrownBy(() -> CaseScope.system().assertReadable(HIDDEN));
        assertThat(CaseScope.system().isUnrestricted()).isTrue();
        assertThat(CaseScope.all().isUnrestricted()).isTrue();
    }

    @Test
    void scopesAreRestrictedUnlessTheySayOtherwise() {
        CaseScope principalLike = new CaseScope() {
            @Override
            public Predicate toPredicate(Root<?> caseRoot, CriteriaQuery<?> query, CriteriaBuilder cb) {
                return cb.disjunction();
            }

            @Override
            public boolean canRead(CaseNumber caseNumber) {
                return false;
            }
        };

        assertThat(principalLike.isUnrestricted()).isFalse();
    }

    @Test
    void unrestrictedScopesAddAnAlwaysTruePredicate() {
        Predicate conjunction = proxy(Predicate.class, (method, args) -> null);
        CriteriaBuilder cb = proxy(CriteriaBuilder.class, (method, args) -> {
            assertThat(method).isEqualTo("conjunction");
            return conjunction;
        });

        assertThat(CaseScope.system().toPredicate(proxy(Root.class, null), proxy(CriteriaQuery.class, null), cb))
                .isSameAs(conjunction);
        assertThat(CaseScope.all().toPredicate(proxy(Root.class, null), proxy(CriteriaQuery.class, null), cb))
                .isSameAs(conjunction);
    }

    private interface Handler {
        @Nullable
        Object handle(String method, @Nullable Object @Nullable [] args);
    }

    private static <T> T proxy(Class<T> type, @Nullable Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, method, args) -> {
            if (handler == null) {
                throw new AssertionError("unexpected call to " + method.getName());
            }
            return handler.handle(method.getName(), args);
        }));
    }

    private record OnlyScope(Set<CaseNumber> readable) implements CaseScope {
        @Override
        public Predicate toPredicate(Root<?> caseRoot, CriteriaQuery<?> query, CriteriaBuilder cb) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean canRead(CaseNumber caseNumber) {
            return readable.contains(caseNumber);
        }
    }
}
