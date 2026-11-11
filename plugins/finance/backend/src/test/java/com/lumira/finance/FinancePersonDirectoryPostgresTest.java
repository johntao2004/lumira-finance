package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.*;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.LinkedMultiValueMap;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Real PostgreSQL directory constraints/transactions; mock host evidence is not live MFA
 * acceptance.
 */
class FinancePersonDirectoryPostgresTest {
    static FinanceStore db;
    static FinancePersonDirectory directory;
    static final Map<String, UUID> accounts = new ConcurrentHashMap<>();
    static final Set<String> denied = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void setup() throws Exception {
        FinancePostgresTest.setup();
        db = new FinanceStore(FinancePostgresTest.jdbc);
        try (var c = FinancePostgresTest.source.getConnection()) {
            c.createStatement()
                    .execute(
                            Files.readString(Path.of("../migrations/V12__person_directory.sql"))
                                    + Files.readString(
                                            Path.of(
                                                    "../migrations/V13__person_directory_resource_index.sql")));
        }
        for (String n : List.of("a", "a2", "b", "c", "target", "target2", "outsider"))
            accounts.put(n, UUID.randomUUID());
        AdministrationPort guard =
                new AdministrationPort() {
                    public Set<String> require(String actor, String permission) {
                        if (denied.contains(actor + ":" + permission))
                            throw new FinanceException("SCOPE_DENIED", "revoked");
                        return Set.of(permission);
                    }

                    public void requireUser(String a, UUID t, String p) {}

                    public void requireGlobal(String a, String p) {}

                    public boolean hasGlobal(String a, String p) {
                        return false;
                    }
                };
        UserDirectoryPort users =
                new UserDirectoryPort() {
                    public long version(UUID id) {
                        return versionSnapshot(id);
                    }

                    public long versionSnapshot(UUID id) {
                        if (!accounts.containsValue(id))
                            throw new org.springframework.web.server.ResponseStatusException(
                                    org.springframework.http.HttpStatus.NOT_FOUND);
                        return 1;
                    }
                };
        directory =
                new FinancePersonDirectory(
                        db,
                        FinancePostgresTest.service,
                        guard,
                        users,
                        (name, lock) -> accounts.get(name),
                        FinancePostgresTest.tx.getTransactionManager());
    }

    @AfterAll
    static void cleanup() {
        FinancePostgresTest.cleanup();
    }

    @BeforeEach
    void reset() {
        denied.clear();
    }

    static UUID tenant() {
        UUID t = UUID.randomUUID();
        db.update(
                "INSERT INTO plugin_finance_tenant(id,name,owner_id) VALUES(?,?,?)",
                t,
                "Person QA",
                accounts.get("a"));
        bootstrap(t);
        return t;
    }

    static Map<String, Object> manifest(UUID t) {
        var seeds = new ArrayList<>();
        for (String name : List.of("a", "a2", "b", "c"))
            seeds.add(
                    FinancePersonDirectory.map(
                            "personReference",
                            name.equals("a2")
                                    ? "PERSON-A"
                                    : "PERSON-" + name.toUpperCase(Locale.ROOT),
                            "displayName",
                            name.equals("a2") ? "Person a" : "Person " + name,
                            "accountId",
                            accounts.get(name).toString(),
                            "abilities",
                            List.of("READ", "PROPOSE", "CONFIRM")));
        return FinancePersonDirectory.map(
                "schemaVersion",
                "1",
                "tenantId",
                t.toString(),
                "authorityId",
                UUID.randomUUID().toString(),
                "authorityName",
                "组织人工核验",
                "attestedAt",
                Instant.now().minusSeconds(1).toString(),
                "sourceReference",
                "ORG/controlled/manifest",
                "sourceSha256",
                "e".repeat(64),
                "verificationMethod",
                "ORGANIZATION_MANUAL",
                "seeds",
                seeds);
    }

    static Map<String, Object> apply(String text) {
        return db.one(
                "SELECT plugin_finance_person_directory_bootstrap(?,?)::text result",
                text,
                FinanceService.hash(text));
    }

    static String bootstrap(UUID t) {
        String m = FinancePostgresTest.service.encode(manifest(t));
        apply(m);
        return m;
    }

    static AuthenticationRequestContext.Scope context(String name) {
        return AuthenticationRequestContext.bind(
                FinancePersonDirectoryControllerTest.context(name, accounts.get(name)));
    }

    static MockHttpServletRequest request() {
        return FinancePersonDirectoryControllerTest.request(Instant.now());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> write(
            String actor, UUID t, String action, UUID id, Map<String, Object> n) {
        try (var scope = context(actor)) {
            return (Map<String, Object>) directory.write(actor, t, action, id, n, request());
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(
            String actor, UUID t, String resource, UUID id, LinkedMultiValueMap<String, String> q) {
        try (var scope = context(actor)) {
            return (Map<String, Object>) directory.read(actor, t, resource, id, q);
        }
    }

    static Map<String, Object> create(UUID account) {
        var n = FinancePersonDirectoryControllerTest.create();
        n.put("accountId", account.toString());
        n.put("idempotencyKey", UUID.randomUUID().toString());
        return n;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> p(Map<String, Object> d) {
        return (Map<String, Object>) d.get("proposal");
    }

    static UUID id(Map<String, Object> d) {
        return (UUID) p(d).get("id");
    }

    static Map<String, Object> action(Map<String, Object> d, String decision) {
        var m =
                FinancePersonDirectory.map(
                        "schemaVersion",
                        "1",
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        p(d).get("version"),
                        "contentDigest",
                        p(d).get("contentDigest"),
                        "reason",
                        "独立组织复核");
        if (decision != null) m.put("decision", decision);
        return m;
    }

    static String count(UUID t, String table) {
        return db.one("SELECT count(*)::text n FROM " + table + " WHERE tenant_id=?", t)
                .get("n")
                .toString();
    }

    static void code(String expected, Runnable work) {
        assertEquals(expected, assertThrows(FinanceException.class, work::run).code);
    }

    @Test
    void existingPersonBindingRequiresIndependentTargetPerson() {
        UUID t = tenant();
        var person =
                db.one(
                                "SELECT person_id FROM plugin_finance_person_binding WHERE"
                                        + " tenant_id=? AND account_id=?",
                                t,
                                accounts.get("b"))
                        .get("person_id");
        var n = create(accounts.get("target"));
        n.put("operation", "BIND_EXISTING_PERSON");
        n.put("personId", person.toString());
        n.put("displayName", null);
        var d = write("a", t, "create", null, n);
        code(
                "SELF_APPROVAL_FORBIDDEN",
                () -> write("b", t, "confirm", id(d), action(d, "APPROVE")));
        var done = write("c", t, "confirm", id(d), action(d, "APPROVE"));
        assertEquals(person, done.get("effectPersonId"));
        assertEquals("3", count(t, "plugin_finance_person"));
        assertEquals("5", count(t, "plugin_finance_person_binding"));
    }

    @Test
    void bootstrapExactBytesAndClosedConstants() {
        UUID t = UUID.randomUUID();
        db.update(
                "INSERT INTO plugin_finance_tenant(id,name,owner_id) VALUES(?,?,?)",
                t,
                "bootstrap",
                accounts.get("a"));
        String m = bootstrap(t);
        assertTrue(apply(m).get("result").toString().contains("true"));
        assertThrows(RuntimeException.class, () -> apply(m + " "));
        for (String field : List.of("verificationMethod", "attestedAt")) {
            UUID badTenant = UUID.randomUUID();
            db.update(
                    "INSERT INTO plugin_finance_tenant(id,name,owner_id) VALUES(?,?,?)",
                    badTenant,
                    "invalid manifest",
                    accounts.get("a"));
            var bad = manifest(badTenant);
            bad.put(field, null);
            assertThrows(
                    RuntimeException.class, () -> apply(FinancePostgresTest.service.encode(bad)));
        }
        UUID badTenant = UUID.randomUUID();
        db.update(
                "INSERT INTO plugin_finance_tenant(id,name,owner_id) VALUES(?,?,?)",
                badTenant,
                "invalid manifest",
                accounts.get("a"));
        var bad = manifest(badTenant);
        ((Map<String, Object>) ((List<?>) bad.get("seeds")).getFirst()).put("abilities", null);
        assertThrows(RuntimeException.class, () -> apply(FinancePostgresTest.service.encode(bad)));
        assertEquals("3", count(t, "plugin_finance_person"));
        assertEquals("4", count(t, "plugin_finance_person_binding"));
    }

    @Test
    void independentEffectRevokeTombstoneAndCurrentReplay() {
        UUID t = tenant();
        var create = create(accounts.get("target"));
        var draft = write("a", t, "create", null, create);
        code(
                "SELF_APPROVAL_FORBIDDEN",
                () -> write("a2", t, "confirm", id(draft), action(draft, "APPROVE")));
        var cmd = action(draft, "APPROVE");
        var approved = write("b", t, "confirm", id(draft), cmd);
        assertEquals("APPROVED", p(approved).get("status"));
        var revoke = create(accounts.get("target"));
        revoke.put("operation", "REVOKE_BINDING");
        revoke.put("personId", approved.get("effectPersonId").toString());
        revoke.put("displayName", null);
        var rd = write("a", t, "create", null, revoke);
        write("b", t, "confirm", id(rd), action(rd, "APPROVE"));
        assertEquals(
                "REVOKED",
                db.one(
                                "SELECT status FROM plugin_finance_person_binding WHERE tenant_id=?"
                                        + " AND account_id=?",
                                t,
                                accounts.get("target"))
                        .get("status"));
        assertEquals("APPROVED", p(write("b", t, "confirm", id(draft), cmd)).get("status"));
        code(
                "PERSON_BINDING_CONFLICT",
                () -> write("a", t, "create", null, create(accounts.get("target"))));
        denied.add("a:platform:finance:person-propose");
        code("PERSON_PROPOSAL_STALE", () -> write("b", t, "confirm", id(draft), cmd));
        denied.clear();
        denied.add("b:platform:finance:person-confirm");
        code("SCOPE_DENIED", () -> write("b", t, "confirm", id(draft), cmd));
    }

    @Test
    void twentyDifferentRequestsOnePermanentBinding() throws Exception {
        UUID t = tenant();
        var proposals = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 20; i++)
            proposals.add(write("a", t, "create", null, create(accounts.get("target2"))));
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (var p : proposals)
                tasks.add(
                        () -> {
                            try {
                                write("b", t, "confirm", id(p), action(p, "APPROVE"));
                                return true;
                            } catch (FinanceException e) {
                                assertEquals("PERSON_BINDING_CONFLICT", e.code);
                                return false;
                            }
                        });
            int ok = 0;
            for (var f : pool.invokeAll(tasks)) if (f.get()) ok++;
            assertEquals(1, ok);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                "1",
                db.one(
                                "SELECT count(*)::text n FROM plugin_finance_person_binding WHERE"
                                        + " tenant_id=? AND account_id=?",
                                t,
                                accounts.get("target2"))
                        .get("n"));
    }

    @Test
    void staleCanTerminateAndSeedsProtected() {
        UUID t = tenant();
        var d = write("a", t, "create", null, create(accounts.get("target")));
        denied.add("a:platform:finance:person-propose");
        code("PERSON_PROPOSAL_STALE", () -> write("b", t, "confirm", id(d), action(d, "APPROVE")));
        assertEquals(
                "REJECTED", p(write("b", t, "confirm", id(d), action(d, "REJECT"))).get("status"));
        denied.clear();
        var seed =
                db.one(
                        "SELECT person_id FROM plugin_finance_person_binding WHERE tenant_id=? AND"
                                + " account_id=?",
                        t,
                        accounts.get("b"));
        var n = create(accounts.get("b"));
        n.put("operation", "REVOKE_BINDING");
        n.put("personId", seed.get("person_id").toString());
        n.put("displayName", null);
        code("PERSON_SEED_PROTECTED", () -> write("a", t, "create", null, n));
        var x = write("a", t, "create", null, create(accounts.get("target")));
        assertEquals("CANCELLED", p(write("a", t, "cancel", id(x), action(x, null))).get("status"));
    }

    @Test
    void boundedPagesAreGenerationAndActorBound() {
        UUID t = tenant();
        var q = new LinkedMultiValueMap<String, String>();
        q.add("limit", "1");
        var first = read("a", t, "persons", null, q);
        assertNotNull(first.get("nextCursor"));
        q.add("cursor", first.get("nextCursor").toString());
        var next = read("a", t, "persons", null, q);
        assertNotEquals(
                ((List<?>) first.get("items")).getFirst(),
                ((List<?>) next.get("items")).getFirst());
        code("PERSON_DIRECTORY_PAGE_STALE", () -> read("b", t, "persons", null, q));
        write("a", t, "create", null, create(accounts.get("target")));
        code("PERSON_DIRECTORY_PAGE_STALE", () -> read("a", t, "persons", null, q));
        code(
                "SCOPE_DENIED",
                () -> read("outsider", t, "configuration", null, new LinkedMultiValueMap<>()));
    }

    @Test
    void rollbackAndDirectSqlCannotManufactureEffect() {
        UUID t = tenant();
        var d = write("a", t, "create", null, create(accounts.get("target")));
        String persons = count(t, "plugin_finance_person"),
                bindings = count(t, "plugin_finance_person_binding"),
                audits = count(t, "plugin_finance_person_audit"),
                generation =
                        db.one(
                                        "SELECT generation FROM plugin_finance_person_realm WHERE"
                                                + " tenant_id=?",
                                        t)
                                .get("generation")
                                .toString();
        db.update(
                "CREATE FUNCTION person_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
                        + " NEW.tenant_id='"
                        + t
                        + "'::uuid AND NEW.action='PERSON_PROPOSAL_APPROVED' THEN RAISE EXCEPTION"
                        + " 'PERSON_TEST_INJECTED'; END IF; RETURN NEW; END $$");
        db.update(
                "CREATE TRIGGER person_test_fail BEFORE INSERT ON plugin_finance_person_audit FOR"
                        + " EACH ROW EXECUTE FUNCTION person_test_fail()");
        var failure =
                assertThrows(
                        RuntimeException.class,
                        () -> write("b", t, "confirm", id(d), action(d, "APPROVE")));
        boolean injected = false;
        for (Throwable e = failure; e != null; e = e.getCause())
            if (e.getMessage() != null && e.getMessage().contains("PERSON_TEST_INJECTED"))
                injected = true;
        assertTrue(injected);
        assertEquals(persons, count(t, "plugin_finance_person"));
        assertEquals(bindings, count(t, "plugin_finance_person_binding"));
        assertEquals(audits, count(t, "plugin_finance_person_audit"));
        assertEquals(
                generation,
                db.one("SELECT generation FROM plugin_finance_person_realm WHERE tenant_id=?", t)
                        .get("generation")
                        .toString());
        assertEquals(
                "PENDING",
                p(read("a", t, "proposals", id(d), new LinkedMultiValueMap<>())).get("status"));
        assertThrows(
                RuntimeException.class,
                () ->
                        db.update(
                                "INSERT INTO"
                                    + " plugin_finance_person(tenant_id,id,person_reference,display_name,source_reference,source_sha256,seed,proposal_id)"
                                    + " VALUES(?,?,?,'forged','bad',?,false,?)",
                                t,
                                UUID.randomUUID(),
                                "forged",
                                "a".repeat(64),
                                id(d)));
        var seed =
                db.one("SELECT id FROM plugin_finance_person_binding WHERE tenant_id=? LIMIT 1", t);
        assertThrows(
                RuntimeException.class,
                () ->
                        db.update(
                                "UPDATE plugin_finance_person_binding SET account_id=? WHERE"
                                        + " tenant_id=? AND id=?",
                                UUID.randomUUID(),
                                t,
                                seed.get("id")));
    }
}
