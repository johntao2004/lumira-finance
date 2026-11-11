package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.util.LinkedMultiValueMap;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.*;

class FinancePersonDirectoryResourcesPostgresTest {
    static FinanceStore db;
    static FinancePersonDirectory base;

    @BeforeAll
    static void setup() throws Exception {
        FinancePersonDirectoryPostgresTest.setup();
        FinancePersonDirectoryPostgresTest.denied.clear();
        db = FinancePersonDirectoryPostgresTest.db;
        base = FinancePersonDirectoryPostgresTest.directory;
    }

    @AfterAll
    static void cleanup() {
        FinancePersonDirectoryPostgresTest.cleanup();
    }

    static FinancePersonDirectory copy(FinanceStore store, PlatformTransactionManager manager) {
        return new FinancePersonDirectory(
                store, base.json, base.permissions, base.users, base.owners, manager);
    }

    static UUID tenant() {
        return FinancePersonDirectoryPostgresTest.tenant();
    }

    static UUID account() {
        return FinancePersonDirectoryPostgresTest.accounts.get("target");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> write(FinancePersonDirectory d, UUID t, Map<String, Object> n) {
        try (var scope = FinancePersonDirectoryPostgresTest.context("a")) {
            return (Map<String, Object>)
                    d.write(
                            "a",
                            t,
                            "create",
                            null,
                            n,
                            FinancePersonDirectoryPostgresTest.request());
        }
    }

    static String fingerprint(UUID t) {
        return db.one(
                        "SELECT md5(string_agg(v,'|' ORDER BY v)) hash FROM (SELECT"
                            + " row_to_json(x)::text v FROM plugin_finance_person_proposal x WHERE"
                            + " tenant_id=? UNION ALL SELECT row_to_json(x)::text FROM"
                            + " plugin_finance_person_request x WHERE tenant_id=? UNION ALL SELECT"
                            + " row_to_json(x)::text FROM plugin_finance_person_audit x WHERE"
                            + " tenant_id=? UNION ALL SELECT row_to_json(x)::text FROM"
                            + " plugin_finance_person_binding x WHERE tenant_id=? UNION ALL SELECT"
                            + " row_to_json(x)::text FROM plugin_finance_person_realm x WHERE"
                            + " tenant_id=? UNION ALL SELECT row_to_json(x)::text FROM"
                            + " plugin_finance_person x WHERE tenant_id=? UNION ALL SELECT"
                            + " row_to_json(x)::text FROM plugin_finance_person_governance x WHERE"
                            + " tenant_id=?) rows",
                        t,
                        t,
                        t,
                        t,
                        t,
                        t,
                        t)
                .get("hash")
                .toString();
    }

    static boolean state(Throwable e, String code) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof SQLException s && code.equals(s.getSQLState())) return true;
        return false;
    }

    @Test
    void realStatementSleepCancelledAndNoPartialRead() {
        UUID t = tenant();
        var person =
                db.one("SELECT id FROM plugin_finance_person WHERE tenant_id=? LIMIT 1", t)
                        .get("id");
        var caught = new AtomicReference<RuntimeException>();
        var timeout = new AtomicReference<String>();
        var slow =
                new FinanceStore(db.jdbc) {
                    @Override
                    List<Map<String, Object>> list(String sql, Object... args) {
                        if (sql.startsWith("SELECT * FROM plugin_finance_person WHERE")) {
                            timeout.set(
                                    super.one("SHOW statement_timeout")
                                            .get("statement_timeout")
                                            .toString());
                            try {
                                super.list("SELECT pg_sleep(4)");
                            } catch (RuntimeException e) {
                                caught.set(e);
                                throw e;
                            }
                        }
                        return super.list(sql, args);
                    }
                };
        try (var scope = FinancePersonDirectoryPostgresTest.context("a")) {
            var ex =
                    assertThrows(
                            FinanceException.class,
                            () ->
                                    copy(slow, base.transactions)
                                            .read(
                                                    "a",
                                                    t,
                                                    "persons",
                                                    (UUID) person,
                                                    new LinkedMultiValueMap<>()));
            assertEquals("PERSON_DIRECTORY_QUERY_BUDGET_EXCEEDED", ex.code);
        }
        assertTrue(state(caught.get(), "57014"));
        assertEquals("3s", timeout.get());
        try (var scope = FinancePersonDirectoryPostgresTest.context("a")) {
            assertNotNull(base.read("a", t, "persons", (UUID) person, new LinkedMultiValueMap<>()));
        }
    }

    @Test
    void lockedRealmBusyRollbackAndSameKeyRetryDoesNotResurrect() throws Exception {
        UUID t = tenant();
        var n = FinancePersonDirectoryPostgresTest.create(account());
        String before = fingerprint(t);
        try (var c = FinancePostgresTest.source.getConnection()) {
            c.setAutoCommit(false);
            try (var p =
                    c.prepareStatement(
                            "SELECT tenant_id FROM plugin_finance_person_realm WHERE tenant_id=?"
                                    + " FOR UPDATE")) {
                p.setObject(1, t);
                p.executeQuery().close();
            }
            var ex = assertThrows(FinanceException.class, () -> write(base, t, n));
            assertEquals("PERSON_DIRECTORY_BUSY", ex.code);
            assertEquals(before, fingerprint(t));
            c.rollback();
        }
        var d = write(base, t, n);
        var id = FinancePersonDirectoryPostgresTest.id(d);
        try (var scope = FinancePersonDirectoryPostgresTest.context("a")) {
            base.write(
                    "a",
                    t,
                    "cancel",
                    id,
                    FinancePersonDirectoryPostgresTest.action(d, null),
                    FinancePersonDirectoryPostgresTest.request());
        }
        var replay = write(base, t, n);
        assertEquals("CANCELLED", FinancePersonDirectoryPostgresTest.p(replay).get("status"));
        assertEquals(
                "4", FinancePersonDirectoryPostgresTest.count(t, "plugin_finance_person_binding"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> confirm(
            FinancePersonDirectory d,
            UUID tenant,
            Map<String, Object> draft,
            Map<String, Object> command) {
        try (var scope = FinancePersonDirectoryPostgresTest.context("b")) {
            return (Map<String, Object>)
                    d.write(
                            "b",
                            tenant,
                            "confirm",
                            FinancePersonDirectoryPostgresTest.id(draft),
                            command,
                            FinancePersonDirectoryPostgresTest.request());
        }
    }

    @Test
    void postMutationStatementTimeoutRollsBackWholeCommand() {
        UUID t = tenant();
        var draft = write(base, t, FinancePersonDirectoryPostgresTest.create(account()));
        var command = FinancePersonDirectoryPostgresTest.action(draft, "APPROVE");
        String before = fingerprint(t);
        var caught = new AtomicReference<RuntimeException>();
        var slow =
                new FinanceStore(db.jdbc) {
                    @Override
                    int update(String sql, Object... args) {
                        int count = super.update(sql, args);
                        if (sql.startsWith("UPDATE plugin_finance_person_proposal SET")) {
                            assertEquals(
                                    "4",
                                    super.one(
                                                    "SELECT count(*)::text n FROM"
                                                        + " plugin_finance_person WHERE"
                                                        + " tenant_id=?",
                                                    t)
                                            .get("n"));
                            assertEquals(
                                    "5",
                                    super.one(
                                                    "SELECT count(*)::text n FROM"
                                                        + " plugin_finance_person_binding WHERE"
                                                        + " tenant_id=?",
                                                    t)
                                            .get("n"));
                            try {
                                super.list("SELECT pg_sleep(4)");
                            } catch (RuntimeException e) {
                                caught.set(e);
                                throw e;
                            }
                        }
                        return count;
                    }
                };
        var ex =
                assertThrows(
                        FinanceException.class,
                        () -> confirm(copy(slow, base.transactions), t, draft, command));
        assertEquals("PERSON_DIRECTORY_QUERY_BUDGET_EXCEEDED", ex.code);
        assertTrue(state(caught.get(), "57014"));
        assertEquals(before, fingerprint(t));
        var approved = confirm(base, t, draft, command);
        assertEquals("APPROVED", FinancePersonDirectoryPostgresTest.p(approved).get("status"));
        confirm(base, t, draft, command);
        assertEquals("4", FinancePersonDirectoryPostgresTest.count(t, "plugin_finance_person"));
        assertEquals(
                "5", FinancePersonDirectoryPostgresTest.count(t, "plugin_finance_person_binding"));
    }

    @Test
    void PreCommitExpiryRollsBackButSuccessfulCommitIsNeverRelabelled() {
        UUID t = tenant();
        var clock = new AtomicLong();
        var wrapped =
                new FinanceStore(db.jdbc) {
                    boolean registered;

                    @Override
                    int update(String sql, Object... args) {
                        int count = super.update(sql, args);
                        if (!registered
                                && sql.startsWith("INSERT INTO plugin_finance_person_proposal")) {
                            registered = true;
                            TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                        @Override
                                        public int getOrder() {
                                            return Integer.MIN_VALUE;
                                        }

                                        @Override
                                        public void beforeCommit(boolean readOnly) {
                                            clock.set(11_000_000_000L);
                                        }
                                    });
                        }
                        return count;
                    }
                };
        String before = fingerprint(t);
        var ex =
                assertThrows(
                        FinanceException.class,
                        () ->
                                write(
                                        copy(wrapped, base.transactions).withClock(clock::get),
                                        t,
                                        FinancePersonDirectoryPostgresTest.create(account())));
        assertEquals("PERSON_DIRECTORY_QUERY_BUDGET_EXCEEDED", ex.code);
        assertEquals(before, fingerprint(t));
        clock.set(0);
        PlatformTransactionManager lateCommit =
                new PlatformTransactionManager() {
                    public TransactionStatus getTransaction(TransactionDefinition d) {
                        return base.transactions.getTransaction(d);
                    }

                    public void commit(TransactionStatus s) {
                        base.transactions.commit(s);
                        clock.set(20_000_000_000L);
                    }

                    public void rollback(TransactionStatus s) {
                        base.transactions.rollback(s);
                    }
                };
        var committed =
                write(
                        copy(db, lateCommit).withClock(clock::get),
                        t,
                        FinancePersonDirectoryPostgresTest.create(account()));
        assertEquals("PENDING", FinancePersonDirectoryPostgresTest.p(committed).get("status"));
    }

    @Test
    void thousandTypedBindingsUseRealUnforcedFewAndManyPlans() throws Exception {
        UUID t = tenant();
        UUID
                a =
                        (UUID)
                                db.one(
                                                "SELECT person_id FROM"
                                                        + " plugin_finance_person_binding WHERE"
                                                        + " tenant_id=? AND account_id=?",
                                                t,
                                                FinancePersonDirectoryPostgresTest.accounts.get(
                                                        "a"))
                                        .get("person_id"),
                b =
                        (UUID)
                                db.one(
                                                "SELECT person_id FROM"
                                                        + " plugin_finance_person_binding WHERE"
                                                        + " tenant_id=? AND account_id=?",
                                                t,
                                                FinancePersonDirectoryPostgresTest.accounts.get(
                                                        "b"))
                                        .get("person_id"),
                few =
                        (UUID)
                                db.one(
                                                "SELECT person_id FROM"
                                                        + " plugin_finance_person_binding WHERE"
                                                        + " tenant_id=? AND account_id=?",
                                                t,
                                                FinancePersonDirectoryPostgresTest.accounts.get(
                                                        "c"))
                                        .get("person_id");
        FinancePostgresTest.tx.execute(
                status -> {
                    for (int i = 0; i < 1000; i++) {
                        UUID target = UUID.randomUUID(),
                                id = UUID.randomUUID(),
                                binding = UUID.randomUUID(),
                                person = i < 900 ? b : a;
                        FinancePersonDirectoryPostgresTest.accounts.put("bulk-" + target, target);
                        var content =
                                FinancePersonDirectory.map(
                                        "schemaVersion",
                                        "1",
                                        "operation",
                                        "BIND_EXISTING_PERSON",
                                        "accountId",
                                        target.toString(),
                                        "personId",
                                        person.toString(),
                                        "displayName",
                                        null,
                                        "sourceReference",
                                        "QA-SYNTHETIC-ORG-ATTESTATION",
                                        "sourceSha256",
                                        "f".repeat(64),
                                        "reason",
                                        "synthetic typed resource fixture, not real employee"
                                                + " verification",
                                        "noAccountTransferConfirmed",
                                        true,
                                        "allKnownAccountsDeclared",
                                        true);
                        String encoded = base.json.encode(base.json.canonical(content));
                        db.update(
                                "INSERT INTO"
                                    + " plugin_finance_person_proposal(tenant_id,id,operation,account_id,person_id,source_reference,source_sha256,content,content_text,content_digest,reason,prepared_by,prepared_username,prepared_person_id)"
                                    + " VALUES(?,?,'BIND_EXISTING_PERSON',?,?,?, ?,?::jsonb,?,?,"
                                    + " ?,?,'a',?)",
                                t,
                                id,
                                target,
                                person,
                                content.get("sourceReference"),
                                content.get("sourceSha256"),
                                encoded,
                                encoded,
                                FinanceService.hash(encoded),
                                content.get("reason"),
                                FinancePersonDirectoryPostgresTest.accounts.get("a"),
                                a);
                        db.update(
                                "UPDATE plugin_finance_person_proposal SET"
                                    + " status='APPROVED',version=version+1,decided_by=?,decided_person_id=?,decided_at=CURRENT_TIMESTAMP,decision_reason='synthetic"
                                    + " resource fixture',effect_person_id=?,effect_binding_id=?"
                                    + " WHERE tenant_id=? AND id=?",
                                FinancePersonDirectoryPostgresTest.accounts.get("c"),
                                few,
                                person,
                                binding,
                                t,
                                id);
                    }
                    return null;
                });
        db.update("ANALYZE plugin_finance_person_binding");
        StringBuilder output =
                new StringBuilder(
                        "1000 synthetic typed legal bindings; normal planner, no enable_seqscan"
                                + " changes. Not production capacity certification.\n");
        for (UUID person : List.of(few, b)) {
            var row =
                    db.one(
                            "EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON) SELECT * FROM"
                                + " plugin_finance_person_binding WHERE tenant_id=? AND person_id=?"
                                + " ORDER BY created_at,id LIMIT 101",
                            t,
                            person);
            String plan = row.values().iterator().next().toString();
            output.append(person.equals(few) ? "FEW\n" : "MANY\n").append(plan).append('\n');
            assertTrue(plan.contains("plugin_finance_person_binding_person_page"), plan);
        }
        Files.createDirectories(Path.of("../../../.runtime/acceptance/finance-0.13.0"));
        Files.writeString(
                Path.of(
                        "../../../.runtime/acceptance/finance-0.13.0/person-binding-query-plans.txt"),
                output);
        try (var scope = FinancePersonDirectoryPostgresTest.context("a")) {
            var readiness =
                    (Map<?, ?>) base.read("a", t, "readiness", null, new LinkedMultiValueMap<>());
            assertEquals("1004", readiness.get("activeBindingCount"));
            assertEquals("3", readiness.get("activePersonCount"));
        }
    }
}
