package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real V17 finance-only transactions in a random dedicated kernel QA schema. */
class FinanceCounterpartyPostgresTest {
    static FinanceCounterparties service;
    static FinanceStore db;

    @BeforeAll
    static void setup() throws Exception {
        FinanceJournalPersonPostgresTest.setup();
        db = FinanceJournalPersonPostgresTest.db;
        try (var c = FinancePostgresTest.source.getConnection()) {
            c.setAutoCommit(false);
            c.createStatement()
                    .execute(
                            Files.readString(
                                    Path.of("../migrations/V17__counterparty_master_data.sql")));
            c.commit();
        }
        AuthorizationContextProjectionPort a =
                p ->
                        Optional.of(
                                new AuthorizationContextProjectionPort.Snapshot(
                                        FinanceJournalPersonPostgresTest.ALL,
                                        FinanceJournalPersonPostgresTest.ALL,
                                        false,
                                        null,
                                        0));
        service =
                new FinanceCounterparties(
                        db,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        a);
    }

    @AfterAll
    static void cleanup() {
        FinanceJournalPersonPostgresTest.cleanup();
    }

    @BeforeEach
    void reset() {
        FinanceJournalPersonPostgresTest.denied.clear();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Object v) {
        return (List<Map<String, Object>>) v;
    }

    static UUID id(Object x) {
        return UUID.fromString(x.toString());
    }

    static Map<String, Object> map(Object... p) {
        return FinanceJournalPersonGate.map(p);
    }

    static Map<String, Object> create(String registration) {
        var n = FinanceCounterpartyInputTest.create();
        var i = FinanceCounterpartyInputTest.identity();
        i.put("identifier", registration);
        n.put("identity", i);
        return n;
    }

    static Map<String, Object> write(
            String actor,
            UUID book,
            String op,
            UUID target,
            UUID revision,
            Map<String, Object> body) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () ->
                        obj(
                                service.write(
                                        actor,
                                        book,
                                        op,
                                        target,
                                        revision,
                                        body,
                                        FinanceJournalPersonPostgresTest.request())));
    }

    static Map<String, Object> read(
            String actor,
            UUID book,
            String op,
            UUID target,
            UUID revision,
            Map<String, String> query) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () -> {
                    var request = new MockHttpServletRequest();
                    query.forEach(request::addParameter);
                    return obj(service.read(actor, book, op, target, revision, request));
                });
    }

    static Map<String, Object> detail(UUID book, UUID party) {
        return read("maker", book, "detail", party, null, Map.of());
    }

    static Map<String, Object> revision(UUID book, UUID party, UUID revision) {
        return read("maker", book, "revision", party, revision, Map.of());
    }

    static Map<String, Object> action(Map<String, Object> d) {
        return map(
                "schemaVersion",
                FinanceCounterpartyInput.SCHEMA,
                "idempotencyKey",
                UUID.randomUUID().toString(),
                "reason",
                "独立组织主数据决定\n不代表政府验真",
                "expectedRegistryVersion",
                obj(d.get("counterparty")).get("registryVersion"),
                "expectedRevisionVersion",
                obj(d.get("revision")).get("version"),
                "contentDigest",
                obj(d.get("revision")).get("contentDigest"));
    }

    static Map<String, Object> pending(UUID book, Map<String, Object> created) {
        var p = obj(created.get("counterparty"));
        return revision(book, id(p.get("id")), id(p.get("pendingRevisionId")));
    }

    static Map<String, Object> decide(String actor, UUID book, String op, Map<String, Object> d) {
        return write(
                actor,
                book,
                op,
                id(d.get("counterpartyId")),
                id(obj(d.get("revision")).get("id")),
                action(d));
    }

    static Map<String, Object> proposal(Map<String, Object> d, String status, String name) {
        var p = obj(d.get("counterparty"));
        var a = p.get("effectiveRevision") == null ? null : obj(p.get("effectiveRevision"));
        var content = FinanceCounterpartyInputTest.content();
        content.put("displayName", name);
        return map(
                "schemaVersion",
                FinanceCounterpartyInput.SCHEMA,
                "idempotencyKey",
                UUID.randomUUID().toString(),
                "reason",
                "完整版本修订\r\n\t原文保留",
                "expectedRegistryVersion",
                p.get("registryVersion"),
                "baseApprovedRevisionId",
                a == null ? null : a.get("id").toString(),
                "baseApprovedContentDigest",
                a == null ? null : a.get("contentDigest"),
                "previousRevisionId",
                p.get("latestRevisionId").toString(),
                "desiredStatus",
                status,
                "content",
                content);
    }

    static Map<String, Object> propose(UUID book, UUID party, String status, String name) {
        return write(
                "maker", book, "propose", party, null, proposal(detail(book, party), status, name));
    }

    static void code(String expected, Runnable run) {
        assertEquals(expected, assertThrows(FinanceException.class, run::run).code);
    }

    static Map<String, String> financial(UUID book) {
        var result = new LinkedHashMap<String, String>();
        for (var table :
                List.of(
                        "plugin_finance_journal",
                        "plugin_finance_line",
                        "plugin_finance_posting",
                        "plugin_finance_opening",
                        "plugin_finance_audit",
                        "plugin_finance_outbox",
                        "plugin_finance_year_guard",
                        "plugin_finance_period"))
            result.put(
                    table,
                    db.one(
                                    "SELECT md5(COALESCE(string_agg(row_to_json(x)::text,'' ORDER"
                                        + " BY row_to_json(x)::text),'')) digest FROM(SELECT * FROM"
                                        + " "
                                            + table
                                            + " WHERE book_id=?) x",
                                    book)
                            .get("digest")
                            .toString());
        return result;
    }

    @Test
    void
            bothStandardsPublishFreezeRecoverThenInactivateWithoutRevivingOldReplayOrFinancialEffects() {
        for (String standard : List.of("CAS", "SMALL")) {
            var c = FinanceJournalPersonPostgresTest.fixture(standard);
            var before = financial(c.f().book());
            var input = create("LEGAL-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
            var created = write("maker", c.f().book(), "create", null, null, input);
            UUID party = id(obj(created.get("counterparty")).get("id"));
            var first = pending(c.f().book(), created);
            var approve = action(first);
            var approved =
                    write(
                            "reviewer",
                            c.f().book(),
                            "approve",
                            party,
                            id(obj(first.get("revision")).get("id")),
                            approve);
            assertEquals("ACTIVE", obj(approved.get("counterparty")).get("effectiveStatus"));
            var frozen =
                    decide(
                            "reviewer",
                            c.f().book(),
                            "approve",
                            propose(c.f().book(), party, "FROZEN", "同名组织改名"));
            assertEquals(false, obj(frozen.get("counterparty")).get("newUseEligible"));
            var recovered =
                    decide(
                            "reviewer",
                            c.f().book(),
                            "approve",
                            propose(c.f().book(), party, "ACTIVE", "恢复后名称"));
            assertEquals(true, obj(recovered.get("counterparty")).get("newUseEligible"));
            decide(
                    "reviewer",
                    c.f().book(),
                    "approve",
                    propose(c.f().book(), party, "INACTIVE", "最终停用"));
            var replay =
                    write(
                            "reviewer",
                            c.f().book(),
                            "approve",
                            party,
                            id(obj(first.get("revision")).get("id")),
                            approve);
            assertEquals("APPROVED", obj(replay.get("revision")).get("status"));
            assertEquals("INACTIVE", obj(replay.get("counterparty")).get("effectiveStatus"));
            assertEquals("同名组织", obj(replay.get("content")).get("displayName"));
            assertEquals(
                    "INACTIVE",
                    obj(write("maker", c.f().book(), "create", null, null, input)
                                    .get("counterparty"))
                            .get("effectiveStatus"));
            var newKey = new LinkedHashMap<>(input);
            newKey.put("idempotencyKey", UUID.randomUUID().toString());
            assertEquals(
                    party,
                    id(
                            obj(write("maker", c.f().book(), "create", null, null, newKey)
                                            .get("counterparty"))
                                    .get("id")));
            code(
                    "COUNTERPARTY_STATE_CONFLICT",
                    () -> propose(c.f().book(), party, "ACTIVE", "不能恢复"));
            var changed = new LinkedHashMap<>(input);
            changed.put("reason", "变更原operation内容");
            changed.put("idempotencyKey", UUID.randomUUID().toString());
            code(
                    "IDEMPOTENCY_CONFLICT",
                    () -> write("maker", c.f().book(), "create", null, null, changed));
            assertEquals(before, financial(c.f().book()));
        }
    }

    @Test
    void initialRejectAndCancelRetainIdentityAndAllowExplicitSameUuidRevision() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        for (String exit : List.of("reject", "cancel")) {
            var created =
                    write(
                            "maker",
                            c.f().book(),
                            "create",
                            null,
                            null,
                            create(
                                    "INITIAL-"
                                            + UUID.randomUUID()
                                                    .toString()
                                                    .toUpperCase(Locale.ROOT)));
            UUID party = id(obj(created.get("counterparty")).get("id"));
            var initial = pending(c.f().book(), created);
            decide(exit.equals("reject") ? "reviewer" : "maker", c.f().book(), exit, initial);
            var next = propose(c.f().book(), party, "ACTIVE", "同UUID后续提案");
            assertEquals("2", obj(next.get("revision")).get("revision"));
            assertNull(next.get("baseApprovedRevisionId"));
            var active = decide("reviewer", c.f().book(), "approve", next);
            assertEquals(party, id(active.get("counterpartyId")));
            assertEquals("ACTIVE", obj(active.get("counterparty")).get("effectiveStatus"));
            assertEquals(
                    exit.equals("reject") ? "REJECTED" : "CANCELLED",
                    obj(revision(c.f().book(), party, id(obj(initial.get("revision")).get("id")))
                                    .get("revision"))
                            .get("status"));
        }
    }

    @Test
    void aliasCannotSelfApproveAndDepartedMakerDoesNotPreventIndependentRejection() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        var d =
                pending(
                        c.f().book(),
                        write(
                                "maker",
                                c.f().book(),
                                "create",
                                null,
                                null,
                                create(
                                        "SOD-"
                                                + UUID.randomUUID()
                                                        .toString()
                                                        .toUpperCase(Locale.ROOT))));
        code("SELF_APPROVAL_FORBIDDEN", () -> decide("alias", c.f().book(), "approve", d));
        FinanceJournalPersonPostgresTest.denied.add("maker:platform:finance:manage");
        code("COUNTERPARTY_APPROVAL_STALE", () -> decide("reviewer", c.f().book(), "approve", d));
        var rejected = decide("reviewer", c.f().book(), "reject", d);
        assertEquals("REJECTED", obj(rejected.get("revision")).get("status"));
    }

    static UUID sameEntityBook(FinanceJournalPersonPostgresTest.Case c) {
        UUID entity =
                (UUID)
                        db.one(
                                        "SELECT legal_entity_id FROM plugin_finance_book WHERE"
                                                + " id=?",
                                        c.f().book())
                                .get("legal_entity_id");
        return id(
                FinanceJournalPersonPostgresTest.as(
                                "maker",
                                () ->
                                        FinancePostgresTest.write(
                                                "maker",
                                                null,
                                                null,
                                                "book",
                                                null,
                                                map(
                                                        "name",
                                                        "另一治理账套",
                                                        "legalEntityName",
                                                        "同法人",
                                                        "registrationId",
                                                        "既有法人",
                                                        "tenantId",
                                                        c.tenant().toString(),
                                                        "legalEntityId",
                                                        entity.toString(),
                                                        "standard",
                                                        "CAS",
                                                        "baseCurrency",
                                                        "CNY",
                                                        "year",
                                                        2027)))
                        .get("id"));
    }

    @Test
    void crossBookDualReadAndEntityIdentityPermanenceAreNotImplicitGovernance() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        UUID other = sameEntityBook(c);
        var input = create("CROSS-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
        var created = write("maker", c.f().book(), "create", null, null, input);
        UUID party = id(obj(created.get("counterparty")).get("id"));
        assertEquals(
                party,
                id(
                        obj(read("maker", other, "detail", party, null, Map.of())
                                        .get("counterparty"))
                                .get("id")));
        assertTrue(rows(read("maker", other, "list", null, null, Map.of()).get("items")).isEmpty());
        FinancePostgresTest.write(
                "maker",
                other,
                null,
                "members",
                null,
                map("username", "outsider", "role", "OWNER", "enabled", true));
        code("SCOPE_DENIED", () -> read("outsider", other, "detail", party, null, Map.of()));
        FinanceJournalPersonPostgresTest.bind(c, "outsider");
        var duplicate = new LinkedHashMap<>(input);
        duplicate.put("operationId", UUID.randomUUID().toString());
        duplicate.put("idempotencyKey", UUID.randomUUID().toString());
        code("SCOPE_DENIED", () -> write("outsider", other, "create", null, null, duplicate));
        code(
                "COUNTERPARTY_IDENTITY_EXISTS",
                () -> write("maker", other, "create", null, null, duplicate));
        assertEquals(
                1L,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM"
                                                    + " plugin_finance_counterparty_registry WHERE"
                                                    + " tenant_id=? AND legal_entity_id=(SELECT"
                                                    + " legal_entity_id FROM plugin_finance_book"
                                                    + " WHERE id=?)",
                                                c.tenant(),
                                                other)
                                        .get("n"))
                        .longValue());
    }

    @Test
    void twoOverlappingDeliveriesSharePermanentOperationAndOneInitialEffect() throws Exception {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        var input = create("RACE-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tasks = new ArrayList<Future<Map<String, Object>>>();
            for (int i = 0; i < 2; i++) {
                var n = new LinkedHashMap<>(input);
                n.put("idempotencyKey", UUID.randomUUID().toString());
                tasks.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return write("maker", c.f().book(), "create", null, null, n);
                                }));
            }
            start.countDown();
            assertEquals(
                    obj(tasks.get(0).get(15, TimeUnit.SECONDS).get("counterparty")).get("id"),
                    obj(tasks.get(1).get(15, TimeUnit.SECONDS).get("counterparty")).get("id"));
            assertEquals(
                    1L,
                    ((Number)
                                    db.one(
                                                    "SELECT count(*) n FROM"
                                                        + " plugin_finance_counterparty_audit WHERE"
                                                        + " tenant_id=?",
                                                    c.tenant())
                                            .get("n"))
                            .longValue());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void auditFailureRollsBackRegistryRevisionGuardsAndKeyThenSameKeyCanRecover() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        var input = create("ROLLBACK-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
        var before =
                db.one(
                                "SELECT generation FROM plugin_finance_counterparty_home_guard"
                                        + " WHERE book_id=?",
                                c.f().book())
                        .get("generation");
        db.update(
                "CREATE FUNCTION counterparty_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN RAISE EXCEPTION 'COUNTERPARTY_TEST_ATOMIC_AUDIT'; END $$");
        db.update(
                "CREATE TRIGGER counterparty_test_failure BEFORE INSERT ON"
                        + " plugin_finance_counterparty_audit FOR EACH ROW EXECUTE FUNCTION"
                        + " counterparty_test_failure()");
        try {
            Throwable failure =
                    assertThrows(
                            RuntimeException.class,
                            () -> write("maker", c.f().book(), "create", null, null, input));
            StringBuilder causes = new StringBuilder();
            for (Throwable x = failure; x != null; x = x.getCause()) causes.append(x.getMessage());
            assertTrue(causes.toString().contains("COUNTERPARTY_TEST_ATOMIC_AUDIT"));
            assertEquals(
                    0L,
                    ((Number)
                                    db.one(
                                                    "SELECT count(*) n FROM"
                                                        + " plugin_finance_counterparty_registry"
                                                        + " WHERE tenant_id=?",
                                                    c.tenant())
                                            .get("n"))
                            .longValue());
            assertEquals(
                    before,
                    db.one(
                                    "SELECT generation FROM plugin_finance_counterparty_home_guard"
                                            + " WHERE book_id=?",
                                    c.f().book())
                            .get("generation"));
            assertTrue(
                    db.list(
                                    "SELECT * FROM plugin_finance_counterparty_request WHERE"
                                            + " request_key=?",
                                    input.get("idempotencyKey"))
                            .isEmpty());
        } finally {
            db.update(
                    "DROP TRIGGER counterparty_test_failure ON plugin_finance_counterparty_audit");
            db.update("DROP FUNCTION counterparty_test_failure()");
        }
        assertNotNull(
                write("maker", c.f().book(), "create", null, null, input).get("counterparty"));
    }

    static void commitReject(String sql) throws Exception {
        boolean completed = false, committed = false;
        java.sql.SQLException rejection = null;
        try (var c = FinancePostgresTest.source.getConnection()) {
            c.setAutoCommit(false);
            try {
                c.createStatement().execute(sql);
                completed = true;
                c.commit();
                committed = true;
            } catch (java.sql.SQLException failure) {
                rejection = failure;
                c.rollback();
            }
        }
        System.out.println(
                "MD02 direct-proof completed="
                        + completed
                        + " committed="
                        + committed
                        + " SQLSTATE="
                        + (rejection == null ? null : rejection.getSQLState()));
        assertTrue(
                completed,
                "The negative must reach real COMMIT, not fail from a fixture statement");
        assertFalse(committed, "Orphan proof must not commit");
        assertNotNull(rejection);
        assertEquals("P0001", rejection.getSQLState());
        assertTrue(rejection.getMessage().contains("COUNTERPARTY_STATE_CONFLICT"));
    }

    @Test
    void realCommitRejectsAnOrphanDecisionAndFakeApprovedCurrentPointer() throws Exception {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        var d =
                pending(
                        c.f().book(),
                        write(
                                "maker",
                                c.f().book(),
                                "create",
                                null,
                                null,
                                create(
                                        "COMMIT-"
                                                + UUID.randomUUID()
                                                        .toString()
                                                        .toUpperCase(Locale.ROOT))));
        UUID p = id(d.get("counterpartyId")), r = id(obj(d.get("revision")).get("id"));
        String scoped = "tenant_id='" + c.tenant() + "'::uuid AND id='" + r + "'::uuid";
        commitReject(
                "INSERT INTO"
                    + " plugin_finance_counterparty_decision(tenant_id,legal_entity_id,counterparty_id,revision_id,id,kind,content_digest,actor,reason)"
                    + " SELECT"
                    + " tenant_id,legal_entity_id,counterparty_id,id,gen_random_uuid(),'APPROVE',content_digest,plugin_finance_journal_person_identity(tenant_id,'"
                        + FinancePostgresTest.users.get("reviewer")
                        + "'::uuid,true),'Orphan independent decision' FROM"
                        + " plugin_finance_counterparty_revision WHERE "
                        + scoped);
        commitReject(
                "UPDATE plugin_finance_counterparty_registry SET"
                    + " approved_revision_id=latest_revision_id,version=version+1,updated_at=CURRENT_TIMESTAMP"
                    + " WHERE tenant_id='"
                        + c.tenant()
                        + "'::uuid AND id='"
                        + p
                        + "'::uuid");
        assertEquals("PENDING", obj(revision(c.f().book(), p, r).get("revision")).get("status"));
        assertEquals(
                "ACTIVE",
                obj(decide("reviewer", c.f().book(), "approve", d).get("counterparty"))
                        .get("effectiveStatus"));
    }

    @Test
    void homePagesAreBoundedAndDoNotLeakOtherHomeGenerationWhileLocalDecisionsStaleThem() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        UUID other = sameEntityBook(c);
        Map<String, Object> firstCreated = null;
        for (int i = 0; i < 3; i++) {
            var made =
                    write(
                            "maker",
                            c.f().book(),
                            "create",
                            null,
                            null,
                            create(
                                    "PAGE-"
                                            + UUID.randomUUID()
                                                    .toString()
                                                    .toUpperCase(Locale.ROOT)));
            if (firstCreated == null) firstCreated = made;
        }
        var first = read("maker", c.f().book(), "list", null, null, Map.of("limit", "1"));
        String token = first.get("nextCursor").toString();
        var seen = new HashSet<String>();
        var page = first;
        while (true) {
            for (var item : rows(page.get("items")))
                assertTrue(seen.add(item.get("id").toString()));
            if (page.get("nextCursor") == null) break;
            page =
                    read(
                            "maker",
                            c.f().book(),
                            "list",
                            null,
                            null,
                            Map.of("limit", "1", "cursor", page.get("nextCursor").toString()));
        }
        assertEquals(3, seen.size());
        write(
                "maker",
                other,
                "create",
                null,
                null,
                create("OTHERHOME-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT)));
        assertEquals(
                first.get("generation"),
                read(
                                "maker",
                                c.f().book(),
                                "list",
                                null,
                                null,
                                Map.of("limit", "1", "cursor", token))
                        .get("generation"));
        var forged = service.untoken(token);
        forged.put("anchorId", UUID.randomUUID().toString());
        code(
                "INVALID_INPUT",
                () ->
                        read(
                                "maker",
                                c.f().book(),
                                "list",
                                null,
                                null,
                                Map.of("limit", "1", "cursor", service.token(forged))));
        decide("reviewer", c.f().book(), "reject", pending(c.f().book(), firstCreated));
        code(
                "COUNTERPARTY_PAGE_STALE",
                () ->
                        read(
                                "maker",
                                c.f().book(),
                                "list",
                                null,
                                null,
                                Map.of("limit", "1", "cursor", token)));
        code(
                "INVALID_INPUT",
                () ->
                        read(
                                "maker",
                                c.f().book(),
                                "list",
                                null,
                                null,
                                Map.of("limit", "2", "cursor", token)));
        FinanceJournalPersonPostgresTest.denied.add("maker:platform:finance:read");
        code(
                "SCOPE_DENIED",
                () ->
                        read(
                                "maker",
                                c.f().book(),
                                "list",
                                null,
                                null,
                                Map.of("limit", "1", "cursor", token)));
        System.out.println(
                "MD02 home-page EXPLAIN "
                        + db.list(
                                "EXPLAIN SELECT id FROM plugin_finance_counterparty_registry WHERE"
                                        + " tenant_id=? AND legal_entity_id=(SELECT legal_entity_id"
                                        + " FROM plugin_finance_book WHERE id=?) AND home_book_id=?"
                                        + " ORDER BY created_at DESC,id DESC LIMIT 101",
                                c.tenant(),
                                c.f().book(),
                                c.f().book()));
    }

    @Test
    void realEntityLockTimeoutRollsBackAndSameKeyRecoversThenRealStatementBudgetIsTyped()
            throws Exception {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        UUID entity =
                (UUID)
                        db.one(
                                        "SELECT legal_entity_id FROM plugin_finance_book WHERE"
                                                + " id=?",
                                        c.f().book())
                                .get("legal_entity_id");
        var input = create("RESOURCE-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
        try (var holder = FinancePostgresTest.source.getConnection()) {
            holder.setAutoCommit(false);
            try (var s =
                    holder.prepareStatement(
                            "SELECT generation FROM plugin_finance_counterparty_entity_guard WHERE"
                                    + " tenant_id=? AND legal_entity_id=? FOR UPDATE")) {
                s.setObject(1, c.tenant());
                s.setObject(2, entity);
                s.executeQuery();
            }
            code(
                    "COUNTERPARTY_BUSY",
                    () -> write("maker", c.f().book(), "create", null, null, input));
            holder.rollback();
        }
        assertTrue(
                db.list(
                                "SELECT request_key FROM plugin_finance_counterparty_request WHERE"
                                        + " request_key=?",
                                input.get("idempotencyKey"))
                        .isEmpty());
        assertNotNull(
                write("maker", c.f().book(), "create", null, null, input).get("counterparty"));
        var delayed =
                new FinanceStore(FinancePostgresTest.jdbc) {
                    boolean delay = true;

                    @Override
                    List<Map<String, Object>> list(String sql, Object... args) {
                        if (delay && sql.startsWith("SELECT p.tenant_id")) {
                            delay = false;
                            super.list("SELECT pg_sleep(4)");
                        }
                        return super.list(sql, args);
                    }
                };
        AuthorizationContextProjectionPort auth =
                p ->
                        Optional.of(
                                new AuthorizationContextProjectionPort.Snapshot(
                                        FinanceJournalPersonPostgresTest.ALL,
                                        FinanceJournalPersonPostgresTest.ALL,
                                        false,
                                        null,
                                        0));
        var slow =
                new FinanceCounterparties(
                        delayed,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        auth);
        FinanceJournalPersonPostgresTest.as(
                "maker",
                () -> {
                    code(
                            "COUNTERPARTY_QUERY_BUDGET_EXCEEDED",
                            () ->
                                    slow.read(
                                            "maker",
                                            c.f().book(),
                                            "list",
                                            null,
                                            null,
                                            new MockHttpServletRequest()));
                    return null;
                });
    }

    @Test
    void authorizedIdentityConflictProvidesStablePartyAndHomeAfterRequestReplayChecks() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        var input = create("REFERENCE-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
        var created = write("maker", c.f().book(), "create", null, null, input);
        UUID party = id(obj(created.get("counterparty")).get("id"));
        var freshOperation = new LinkedHashMap<>(input);
        freshOperation.put("operationId", UUID.randomUUID().toString());
        freshOperation.put("idempotencyKey", UUID.randomUUID().toString());
        var conflict =
                assertThrows(
                        FinanceException.class,
                        () -> write("maker", c.f().book(), "create", null, null, freshOperation));
        assertEquals("COUNTERPARTY_IDENTITY_EXISTS", conflict.code);
        assertEquals(409, conflict.getStatusCode().value());
        assertTrue(conflict.getReason().contains(party.toString()));
        assertTrue(conflict.getReason().contains(c.f().book().toString()));
        assertEquals(
                List.of(
                        map("row", 1, "field", "counterpartyId", "reason", party.toString()),
                        map(
                                "row",
                                1,
                                "field",
                                "governanceHomeBookId",
                                "reason",
                                c.f().book().toString())),
                conflict.issues);
        var reusedHttpKey = new LinkedHashMap<>(freshOperation);
        reusedHttpKey.put("idempotencyKey", input.get("idempotencyKey"));
        code(
                "IDEMPOTENCY_CONFLICT",
                () -> write("maker", c.f().book(), "create", null, null, reusedHttpKey));
        var sameOperation = new LinkedHashMap<>(input);
        sameOperation.put("idempotencyKey", UUID.randomUUID().toString());
        assertEquals(
                party,
                id(
                        obj(write("maker", c.f().book(), "create", null, null, sameOperation)
                                        .get("counterparty"))
                                .get("id")));
        assertEquals(
                1L,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM"
                                                    + " plugin_finance_counterparty_registry WHERE"
                                                    + " tenant_id=?",
                                                c.tenant())
                                        .get("n"))
                        .longValue());
        var otherInput = create("OTHER-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
        write("maker", c.f().book(), "create", null, null, otherInput);
        var sameOperationDifferentIdentity = new LinkedHashMap<>(input);
        sameOperationDifferentIdentity.put("idempotencyKey", UUID.randomUUID().toString());
        sameOperationDifferentIdentity.put("identity", otherInput.get("identity"));
        code(
                "IDEMPOTENCY_CONFLICT",
                () ->
                        write(
                                "maker",
                                c.f().book(),
                                "create",
                                null,
                                null,
                                sameOperationDifferentIdentity));
    }

    @Test
    void revokedHomeReadIdentityConflictDoesNotRevealStablePartyOrHomeReference() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        UUID other = sameEntityBook(c);
        FinancePostgresTest.write(
                "maker",
                other,
                null,
                "members",
                null,
                map("username", "alias", "role", "OWNER", "enabled", true));
        var input = create("HIDDEN-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
        UUID party =
                id(
                        obj(write("maker", c.f().book(), "create", null, null, input)
                                        .get("counterparty"))
                                .get("id"));
        FinancePostgresTest.write(
                "maker",
                c.f().book(),
                null,
                "members",
                null,
                map("username", "alias", "role", "OWNER", "enabled", false));
        try {
            var freshOperation = new LinkedHashMap<>(input);
            freshOperation.put("operationId", UUID.randomUUID().toString());
            freshOperation.put("idempotencyKey", UUID.randomUUID().toString());
            var denied =
                    assertThrows(
                            FinanceException.class,
                            () -> write("alias", other, "create", null, null, freshOperation));
            assertEquals("SCOPE_DENIED", denied.code);
            assertEquals(403, denied.getStatusCode().value());
            assertFalse(denied.getReason().contains(party.toString()));
            assertFalse(denied.getReason().contains(c.f().book().toString()));
            assertTrue(denied.issues.isEmpty());
            assertTrue(
                    db.list(
                                    "SELECT request_key FROM plugin_finance_counterparty_request"
                                        + " WHERE request_key=?",
                                    freshOperation.get("idempotencyKey"))
                            .isEmpty());
        } finally {
            FinancePostgresTest.write(
                    "maker",
                    c.f().book(),
                    null,
                    "members",
                    null,
                    map("username", "alias", "role", "OWNER", "enabled", true));
        }
    }
}
