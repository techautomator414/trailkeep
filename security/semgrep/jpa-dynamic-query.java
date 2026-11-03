// SPDX-License-Identifier: Apache-2.0
// Semgrep rule test cases; never compiled.
class QueryCases {

    private static final String FIND_OPEN = "select c from CaseRecord c where c.status = :status";

    private final EntityManager em;

    QueryCases(EntityManager em) {
        this.em = em;
    }

    void dynamic(String sort, String title, String sql) {
        // ruleid: jpa-dynamic-query-string
        em.createQuery("select c from CaseRecord c order by " + sort);
        // ruleid: jpa-dynamic-query-string
        em.createQuery("select c from CaseRecord c where c.title = '" + title + "'", CaseRecord.class);
        // ruleid: jpa-dynamic-query-string
        em.createNativeQuery(sql);
        // ruleid: jpa-dynamic-query-string
        em.createNativeQuery(String.format("select * from case_record where title = '%s'", title));
        // ruleid: jpa-dynamic-query-string
        em.createQuery("select c from CaseRecord c where c.title = '%s'".formatted(title));
        String built = new StringBuilder("select c from CaseRecord c order by ").append(sort).toString();
        // ruleid: jpa-dynamic-query-string
        em.createQuery(built);
        // ruleid: jpa-dynamic-query-string
        session.createSelectionQuery(buildQuery(sort), CaseRecord.class);
        // ruleid: jpa-dynamic-query-string
        session.createMutationQuery("delete from CaseRecord c where c.id in (" + ids(title) + ")");
    }

    void constant(CriteriaQuery<CaseRecord> criteria, CriteriaBuilder cb) {
        final String local = "select count(c) from CaseRecord c";
        // ok: jpa-dynamic-query-string
        em.createQuery("select c from CaseRecord c where c.caseNumber = :n", CaseRecord.class);
        // ok: jpa-dynamic-query-string
        em.createQuery(FIND_OPEN, CaseRecord.class);
        // ok: jpa-dynamic-query-string
        em.createQuery(Queries.FIND_BY_TEAM, CaseRecord.class);
        // ok: jpa-dynamic-query-string
        em.createQuery(local);
        // ok: jpa-dynamic-query-string
        em.createQuery("select c from CaseRecord c " + "where c.status = :status");
        // ok: jpa-dynamic-query-string
        em.createNativeQuery("""
                select nextval('seq_case_number')
                """);
        // ok: jpa-dynamic-query-string
        em.createQuery(criteria);
        // ok: jpa-dynamic-query-string
        em.createQuery(cb.createQuery(CaseRecord.class));
    }
}
