package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.*;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.context.request.*;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Full V1-V14 schema; production five-argument gate, host evidence mocked separately from live MFA.
 */
class FinanceJournalPersonPostgresTest {
    static FinanceStore db;
    static FinanceJournalPersonGate gate;
    static FinanceJournalPersonSod sod;
    static FinanceService modern, oldBinary;
    static AdministrationPort permissions;
    static AccountOwnerPort owners;
    static FinancePersonDirectory directory;
    static Map<String, UUID> previousUsers;
    static final Set<String> denied = ConcurrentHashMap.newKeySet();
    static final Set<String> ALL =
            Set.of(
                    "platform:finance:access",
                    "platform:finance:read",
                    "platform:finance:manage",
                    "platform:finance:create",
                    "platform:finance:approve",
                    "platform:finance:post",
                    "platform:finance:close",
                    "platform:finance:person-read",
                    "platform:finance:person-propose",
                    "platform:finance:person-confirm");

    @BeforeAll
    static void setup() throws Exception {
        setup(16);
    }

    static void setup(int schemaVersion) throws Exception {
        FinancePostgresTest.setup();
        db = new FinanceStore(FinancePostgresTest.jdbc);
        try (var files = Files.list(Path.of("../migrations"))) {
            for (var file :
                    files.filter(p -> p.getFileName().toString().matches("V[0-9]+__.*sql"))
                            .sorted(
                                    Comparator.comparingInt(
                                            p ->
                                                    Integer.parseInt(
                                                            p.getFileName()
                                                                    .toString()
                                                                    .split("__")[0]
                                                                    .substring(1))))
                            .toList()) {
                int v = Integer.parseInt(file.getFileName().toString().split("__")[0].substring(1));
                if (v >= 5 && v <= schemaVersion)
                    try (var c = FinancePostgresTest.source.getConnection()) {
                        c.setAutoCommit(false);
                        c.createStatement().execute(Files.readString(file));
                        c.commit();
                    }
            }
        }
        var users = new ConcurrentHashMap<String, UUID>();
        for (String u : List.of("maker", "alias", "reviewer", "other", "plain", "outsider"))
            users.put(u, UUID.randomUUID());
        previousUsers = FinancePostgresTest.users;
        FinancePostgresTest.users = users;
        permissions =
                new AdministrationPort() {
                    public Set<String> require(String a, String p) {
                        if (denied.contains(a + ":" + p))
                            throw new FinanceException("SCOPE_DENIED", "denied");
                        return ALL;
                    }

                    public void requireUser(String a, UUID t, String p) {}

                    public void requireGlobal(String a, String p) {}

                    public boolean hasGlobal(String a, String p) {
                        return true;
                    }
                };
        owners =
                (name, lock) -> {
                    var id = users.get(name);
                    if (id == null) throw new FinanceException("SCOPE_DENIED", "unknown");
                    return id;
                };
        var mapper = new ObjectMapper();
        gate = new FinanceJournalPersonGate(db, permissions, owners, mapper);
        modern = new FinanceService(db, permissions, owners, mapper, gate);
        oldBinary = new FinanceService(db, permissions, owners, mapper);
        FinancePostgresTest.service = modern;
        sod =
                new FinanceJournalPersonSod(
                        db, modern, gate, FinancePostgresTest.tx.getTransactionManager());
        UserDirectoryPort userPort =
                new UserDirectoryPort() {
                    public long version(UUID id) {
                        return versionSnapshot(id);
                    }

                    public long versionSnapshot(UUID id) {
                        if (!users.containsValue(id)) throw new IllegalArgumentException();
                        return 1;
                    }
                };
        directory =
                new FinancePersonDirectory(
                        db,
                        modern,
                        permissions,
                        userPort,
                        owners,
                        FinancePostgresTest.tx.getTransactionManager());
    }

    @AfterAll
    static void cleanup() {
        FinancePostgresTest.cleanup();
        if (previousUsers != null) FinancePostgresTest.users = previousUsers;
    }

    @BeforeEach
    void reset() {
        denied.clear();
    }

    static <T> T as(String name, Supplier<T> work) {
        var account = FinancePostgresTest.users.get(name);
        var auth =
                new RequestAuthorizationContext(
                        name,
                        account,
                        "mock-session",
                        ALL,
                        ALL,
                        Map.of(),
                        ALL,
                        ALL,
                        Map.of(),
                        "mock-revision",
                        "mock-revision",
                        false,
                        null);
        var context =
                new AuthenticationRequestContext.Snapshot(
                        name, account, FinancePersonDirectoryControllerTest.POLICY, auth);
        var request = FinancePersonDirectoryControllerTest.request(Instant.now());
        var previous = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (var scope = AuthenticationRequestContext.bind(context)) {
            return work.get();
        } finally {
            if (previous == null) RequestContextHolder.resetRequestAttributes();
            else RequestContextHolder.setRequestAttributes(previous);
        }
    }

    static MockHttpServletRequest request() {
        return (MockHttpServletRequest)
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                        .getRequest();
    }

    record Case(FinancePostgresTest.Fixture f, UUID tenant) {}

    static Case fixture(String standard) {
        var f = FinancePostgresTest.fixture(standard);
        UUID t =
                (UUID)
                        db.one("SELECT tenant_id FROM plugin_finance_book WHERE id=?", f.book())
                                .get("tenant_id");
        for (var pair :
                Map.of("alias", "OWNER", "reviewer", "REVIEWER", "other", "OWNER", "plain", "MAKER")
                        .entrySet())
            FinancePostgresTest.write(
                    "maker",
                    f.book(),
                    f.year(),
                    "members",
                    null,
                    Map.of("username", pair.getKey(), "role", pair.getValue(), "enabled", true));
        var seeds = new ArrayList<>();
        for (String name : List.of("maker", "alias", "reviewer"))
            seeds.add(
                    map(
                            "personReference",
                            name.equals("alias") ? "PERSON-maker" : "PERSON-" + name,
                            "displayName",
                            name.equals("alias") ? "maker" : name,
                            "accountId",
                            FinancePostgresTest.users.get(name).toString(),
                            "abilities",
                            List.of("READ", "PROPOSE", "CONFIRM")));
        String manifest =
                modern.encode(
                        map(
                                "schemaVersion",
                                "1",
                                "tenantId",
                                t.toString(),
                                "authorityId",
                                UUID.randomUUID().toString(),
                                "authorityName",
                                "Synthetic organization manual fixture",
                                "attestedAt",
                                Instant.now().minusSeconds(1).toString(),
                                "verificationMethod",
                                "ORGANIZATION_MANUAL",
                                "sourceReference",
                                "QA-SYNTHETIC-NOT-REAL-EMPLOYEE-VERIFICATION",
                                "sourceSha256",
                                "f".repeat(64),
                                "seeds",
                                seeds));
        db.one(
                "SELECT plugin_finance_person_directory_bootstrap(?,?) value",
                manifest,
                FinanceService.hash(manifest));
        var c = new Case(f, t);
        for (String name : List.of("other", "plain")) bind(c, name);
        return c;
    }

    static Map<String, Object> map(Object... v) {
        return FinancePersonDirectory.map(v);
    }

    @SuppressWarnings("unchecked")
    static void bind(Case c, String name) {
        var n = FinancePersonDirectoryControllerTest.create();
        n.put("idempotencyKey", UUID.randomUUID().toString());
        n.put("accountId", FinancePostgresTest.users.get(name).toString());
        n.put("displayName", name);
        var d =
                as(
                        "maker",
                        () ->
                                (Map<String, Object>)
                                        directory.write(
                                                "maker", c.tenant(), "create", null, n, request()));
        var p = (Map<String, Object>) d.get("proposal");
        as(
                "reviewer",
                () ->
                        directory.write(
                                "reviewer",
                                c.tenant(),
                                "confirm",
                                (UUID) p.get("id"),
                                map(
                                        "schemaVersion",
                                        "1",
                                        "idempotencyKey",
                                        UUID.randomUUID().toString(),
                                        "expectedVersion",
                                        p.get("version"),
                                        "contentDigest",
                                        p.get("contentDigest"),
                                        "decision",
                                        "APPROVE",
                                        "reason",
                                        "Synthetic organization assertion"),
                                request()));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cmd(
            String actor, Case c, String op, UUID target, Map<String, Object> n) {
        return as(
                actor,
                () ->
                        (Map<String, Object>)
                                sod.write(
                                        actor,
                                        c.f().book(),
                                        c.f().year(),
                                        op,
                                        target,
                                        n,
                                        request()));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(String actor, Case c, String op, UUID target) {
        return as(
                actor,
                () ->
                        (Map<String, Object>)
                                sod.read(actor, c.f().book(), c.f().year(), op, target, request()));
    }

    static Map<String, Object> body(Object... rest) {
        var m =
                map(
                        "schemaVersion",
                        FinanceJournalPersonSod.SCHEMA,
                        "idempotencyKey",
                        UUID.randomUUID().toString());
        m.putAll(map(rest));
        return m;
    }

    static Map<String, Object> activation(Case c) {
        return cmd(
                "maker",
                c,
                "request",
                null,
                body(
                        "expectedModeGeneration",
                        db.one(
                                        "SELECT generation::text v FROM"
                                            + " plugin_finance_journal_person_mode WHERE year_id=?",
                                        c.f().year())
                                .get("v"),
                        "reason",
                        "Enable limited manual NORMAL protocol"));
    }

    static Map<String, Object> decide(Map<String, Object> r) {
        return body(
                "expectedVersion",
                r.get("version"),
                "requestDigest",
                r.get("requestDigest"),
                "reason",
                "Independent decision");
    }

    static void enable(Case c) {
        var r = activation(c);
        cmd("reviewer", c, "approve", (UUID) r.get("id"), decide(r));
    }

    static Map<String, Object> journalInput(Case c, String amount) {
        return map(
                "date",
                "2026-01-15",
                "summary",
                "Manual person protocol",
                "lines",
                List.of(
                        map(
                                "accountId",
                                c.f().cash().toString(),
                                "debit",
                                amount,
                                "credit",
                                "0",
                                "memo",
                                "cash"),
                        map(
                                "accountId",
                                c.f().capital().toString(),
                                "debit",
                                "0",
                                "credit",
                                amount,
                                "memo",
                                "equity")));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> legacy(
            String actor, Case c, String op, UUID id, Map<String, Object> n) {
        var body = new LinkedHashMap<>(n);
        body.putIfAbsent("idempotencyKey", UUID.randomUUID().toString());
        return as(
                actor,
                () ->
                        (Map<String, Object>)
                                FinancePostgresTest.tx.execute(
                                        status ->
                                                modern.write(
                                                        actor,
                                                        c.f().book(),
                                                        c.f().year(),
                                                        op,
                                                        id,
                                                        body)));
    }

    static UUID create(String actor, Case c) {
        return (UUID) legacy(actor, c, "journal", null, journalInput(c, "10.00")).get("id");
    }

    static Map<String, Object> action(Case c, UUID j) {
        var x = read("maker", c, "context", j);
        return body(
                "expectedJournalVersion",
                x.get("journalVersion"),
                "expectedContentVersion",
                x.get("contentVersion"),
                "contentDigest",
                x.get("contentDigest"));
    }

    static void submit(Case c, UUID j) {
        var x = read("maker", c, "context", j);
        legacy(
                "maker",
                c,
                "submit",
                j,
                map("expectedVersion", Long.parseLong(x.get("journalVersion").toString())));
    }

    static Map<String, Object> approve(Case c, UUID j, Duration ttl) {
        var n = action(c, j);
        n.put("requestedExpiresAt", Instant.now().plus(ttl).toString());
        return cmd("reviewer", c, "person-approve", j, n);
    }

    static void code(String value, Runnable work) {
        assertEquals(value, assertThrows(FinanceException.class, work::run).code);
    }

    static String digest(Case c) {
        return db.one(
                        "SELECT md5(string_agg(x,'|' ORDER BY x)) d FROM(SELECT"
                            + " row_to_json(j)::text x FROM plugin_finance_journal j WHERE"
                            + " year_id=? UNION ALL SELECT row_to_json(l)::text FROM"
                            + " plugin_finance_line l WHERE year_id=? UNION ALL SELECT"
                            + " row_to_json(p)::text FROM plugin_finance_posting p WHERE year_id=?"
                            + " UNION ALL SELECT row_to_json(a)::text FROM plugin_finance_audit a"
                            + " WHERE year_id=? UNION ALL SELECT row_to_json(k)::text FROM"
                            + " plugin_finance_idempotency k WHERE route LIKE ?) t",
                        c.f().year(),
                        c.f().year(),
                        c.f().year(),
                        c.f().year(),
                        c.f().book() + "/" + c.f().year() + "/%")
                .get("d")
                .toString();
    }

    static boolean cause(Throwable e, String message) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t.getMessage() != null && t.getMessage().contains(message)) return true;
        return false;
    }

    @Test
    void fullSchemaAccountOnlyKeepsOldWireCacheAndUnboundActor() {
        var f = FinancePostgresTest.fixture("CAS");
        var c =
                new Case(
                        f,
                        (UUID)
                                db.one(
                                                "SELECT tenant_id FROM plugin_finance_book WHERE"
                                                        + " id=?",
                                                f.book())
                                        .get("tenant_id"));
        var n = journalInput(c, "10.00");
        n.put("idempotencyKey", UUID.randomUUID().toString());
        var first = legacy("maker", c, "journal", null, n);
        String before = digest(c);
        assertEquals(
                modern.encode(modern.canonical(first)),
                modern.encode(modern.canonical(legacy("maker", c, "journal", null, n))));
        assertEquals(before, digest(c));
        assertEquals(
                "ACCOUNT_ONLY_LEGACY",
                read("maker", c, "context", (UUID) first.get("id")).get("personProofStatus"));
        assertEquals("UNCONFIGURED", read("maker", c, "mode", null).get("identityAvailability"));
    }

    @Test
    void casAndSmallIndependentAliasOldBinaryAndRealPost() {
        for (String standard : List.of("CAS", "SMALL")) {
            var c = fixture(standard);
            var r = activation(c);
            code(
                    "SELF_APPROVAL_FORBIDDEN",
                    () -> cmd("alias", c, "approve", (UUID) r.get("id"), decide(r)));
            assertEquals(
                    r.get("id"),
                    read("reviewer", c, "mode", null).get("pendingActivationRequestId"));
            cmd("reviewer", c, "approve", (UUID) r.get("id"), decide(r));
            UUID j = create("maker", c);
            submit(c, j);
            var n = action(c, j);
            n.put("requestedExpiresAt", Instant.now().plusSeconds(600).toString());
            code("SELF_APPROVAL_FORBIDDEN", () -> cmd("alias", c, "person-approve", j, n));
            code(
                    "JOURNAL_PERSON_MODE_REQUIRED",
                    () -> legacy("reviewer", c, "approve", j, map("expectedVersion", 2)));
            var approved = approve(c, j, Duration.ofMinutes(5));
            String before = digest(c);
            var old =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    as(
                                            "maker",
                                            () ->
                                                    FinancePostgresTest.tx.execute(
                                                            status ->
                                                                    oldBinary.write(
                                                                            "maker",
                                                                            c.f().book(),
                                                                            c.f().year(),
                                                                            "post",
                                                                            j,
                                                                            map(
                                                                                    "idempotencyKey",
                                                                                    UUID.randomUUID()
                                                                                            .toString(),
                                                                                    "expectedVersion",
                                                                                    3)))));
            assertTrue(cause(old, "JOURNAL_PERSON"), old.toString());
            assertEquals(before, digest(c));
            var post = action(c, j);
            post.put(
                    "approvalId",
                    ((Map<?, ?>) approved.get("currentApproval")).get("id").toString());
            var done = cmd("maker", c, "person-post", j, post);
            assertEquals("PERSON_POSTED", done.get("personProofStatus"));
            assertEquals(done, cmd("maker", c, "person-post", j, post));
            assertEquals(
                    "1",
                    db.one(
                                    "SELECT count(*)::text n FROM"
                                            + " plugin_finance_journal_person_post WHERE year_id=?",
                                    c.f().year())
                            .get("n"));
        }
    }

    @Test
    void sameCanonicalAndEvidenceFreezeAndAdministrativeProjection() throws Exception {
        var c = fixture("CAS");
        enable(c);
        UUID j = create("maker", c);
        var before = read("maker", c, "context", j);
        var edit = journalInput(c, "10.00");
        edit.put("expectedVersion", 1);
        legacy("maker", c, "edit", j, edit);
        var same = read("maker", c, "context", j);
        assertEquals("1", same.get("contentVersion"));
        assertEquals(before.get("contentDigest"), same.get("contentDigest"));
        byte[] bytes =
                "real synthetic fixture bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var originals = new FinanceOriginals(db, modern, owners);
        var original =
                as(
                        "maker",
                        () ->
                                FinancePostgresTest.tx.execute(
                                        status -> {
                                            try {
                                                return originals.upload(
                                                        "maker",
                                                        c.f().book(),
                                                        c.f().year(),
                                                        "evidence.txt",
                                                        UUID.randomUUID().toString(),
                                                        new java.io.ByteArrayInputStream(bytes),
                                                        bytes.length);
                                            } catch (Exception e) {
                                                throw new RuntimeException(e);
                                            }
                                        }));
        legacy(
                "maker",
                c,
                "documents",
                j,
                map(
                        "expectedVersion",
                        2,
                        "originalId",
                        original.get("id").toString(),
                        "source",
                        "synthetic file",
                        "originalLocation",
                        "QA fixture"));
        var evidence = read("maker", c, "context", j);
        assertEquals("2", evidence.get("contentVersion"));
        assertNotEquals(before.get("contentDigest"), evidence.get("contentDigest"));
        var snap = (Map<?, ?>) evidence.get("contentSnapshot");
        assertEquals(
                original.get("sha256"),
                ((Map<?, ?>) ((List<?>) snap.get("documents")).getFirst()).get("sha256"));
        var outbox = new FinanceOutbox(db, modern);
        drain(outbox);
        assertEquals(
                "DRAFT",
                db.one("SELECT status FROM plugin_finance_journal_projection WHERE journal_id=?", j)
                        .get("status"));
        var term = action(c, j);
        term.put("reason", "Independent administrative closure, not maker cancel");
        cmd("other", c, "terminate", j, term);
        drain(outbox);
        var view =
                db.one(
                        "SELECT status,source_version FROM plugin_finance_journal_projection WHERE"
                                + " journal_id=?",
                        j);
        assertEquals("CANCELLED", view.get("status"));
        assertEquals("4", view.get("source_version").toString());
        assertNotNull(read("maker", c, "context", j).get("administrativeTermination"));
    }

    static void drain(FinanceOutbox o) {
        for (int i = 0; i < 100; i++) {
            var rows = FinancePostgresTest.tx.execute(status -> o.claim(20));
            if (rows.isEmpty()) return;
            for (var row : rows)
                FinancePostgresTest.tx.executeWithoutResult(
                        status -> o.consume((UUID) row.get("id"), (UUID) row.get("lease_token")));
        }
        fail("unexpected outbox backlog");
    }

    @Test
    void expiredAndDepartedApproverCanBeRejectedWithoutRevivingMaker() throws Exception {
        var c = fixture("CAS");
        enable(c);
        UUID j = create("maker", c);
        submit(c, j);
        approve(c, j, Duration.ofSeconds(4));
        Thread.sleep(4300);
        var expired = action(c, j);
        expired.put(
                "approvalId",
                ((Map<?, ?>) read("maker", c, "context", j).get("currentApproval"))
                        .get("id")
                        .toString());
        code("JOURNAL_PERSON_APPROVAL_EXPIRED", () -> cmd("maker", c, "person-post", j, expired));
        legacy(
                "other",
                c,
                "reject",
                j,
                map("expectedVersion", 3, "reason", "Expired approval, real independent return"));
        var edit = journalInput(c, "11.00");
        edit.put("expectedVersion", 4);
        legacy("maker", c, "edit", j, edit);
        assertEquals("2", read("maker", c, "context", j).get("contentVersion"));
        submit(c, j);
        approve(c, j, Duration.ofMinutes(1));
        for (String name : List.of("maker", "reviewer"))
            as(
                    "other",
                    () ->
                            FinancePostgresTest.write(
                                    "other",
                                    c.f().book(),
                                    null,
                                    "members",
                                    null,
                                    map(
                                            "username",
                                            name,
                                            "role",
                                            name.equals("maker") ? "OWNER" : "REVIEWER",
                                            "enabled",
                                            false)));
        legacy(
                "other",
                c,
                "reject",
                j,
                map("expectedVersion", 7, "reason", "Prior actors departed, independent return"));
        var ctx = read("other", c, "context", j);
        var terminate =
                body(
                        "expectedJournalVersion",
                        ctx.get("journalVersion"),
                        "expectedContentVersion",
                        ctx.get("contentVersion"),
                        "contentDigest",
                        ctx.get("contentDigest"),
                        "reason",
                        "Orphan draft administrative termination");
        assertEquals("CANCELLED", cmd("other", c, "terminate", j, terminate).get("journalStatus"));
    }

    @Test
    void ordinaryNonGovernanceMakerAndUnsupportedPathsAndSqlTamper() {
        var c = fixture("SMALL");
        enable(c);
        UUID j = create("plain", c);
        assertEquals(
                "plain",
                FinancePostgresTest.users.entrySet().stream()
                        .filter(
                                e ->
                                        e.getValue()
                                                .equals(
                                                        gate.rawJournal(
                                                                        map(
                                                                                "tenant_id",
                                                                                c.tenant(),
                                                                                "id",
                                                                                c.f().book()),
                                                                        c.f().year(),
                                                                        j)
                                                                .get("created_by")))
                        .findFirst()
                        .orElseThrow()
                        .getKey());
        as(
                "plain",
                () -> {
                    assertThrows(
                            FinanceException.class,
                            () ->
                                    directory.read(
                                            "plain",
                                            c.tenant(),
                                            "configuration",
                                            null,
                                            new LinkedMultiValueMap<>()));
                    return null;
                });
        code(
                "JOURNAL_PERSON_PROTOCOL_UNSUPPORTED",
                () ->
                        legacy(
                                "maker",
                                c,
                                "closing-drafts",
                                null,
                                map("date", "2026-01-31", "stage", "PROFIT_LOSS")));
        String before = digest(c);
        assertThrows(
                RuntimeException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                status -> {
                                    db.update(
                                            "UPDATE plugin_finance_line SET memo='tampered' WHERE"
                                                    + " journal_id=? AND line_no=1",
                                            j);
                                    return null;
                                }));
        assertEquals(before, digest(c));
        assertThrows(
                RuntimeException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                status -> {
                                    db.update(
                                            "UPDATE plugin_finance_journal SET"
                                                    + " status='SUBMITTED',version=version+1 WHERE"
                                                    + " id=?",
                                            j);
                                    return null;
                                }));
        assertEquals(before, digest(c));
    }

    static boolean sqlState(Throwable failure, String expected) {
        for (Throwable t = failure; t != null; t = t.getCause())
            if (t instanceof java.sql.SQLException q && expected.equals(q.getSQLState()))
                return true;
        return false;
    }

    static int pid(java.sql.Connection c) throws Exception {
        try (var r = c.createStatement().executeQuery("SELECT pg_backend_pid()")) {
            r.next();
            return r.getInt(1);
        }
    }

    static void awaitLock(int pid) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < end) {
            var rows = db.list("SELECT wait_event_type FROM pg_stat_activity WHERE pid=?", pid);
            if (!rows.isEmpty() && "Lock".equals(rows.getFirst().get("wait_event_type"))) return;
            Thread.sleep(20);
        }
        fail("own backend did not reach actual lock wait: " + pid);
    }

    static void sqlApprove(java.sql.Connection c, Case f, Map<String, Object> request)
            throws Exception {
        try (var p =
                c.prepareStatement(
                        "UPDATE plugin_finance_journal_person_activation SET"
                            + " status='APPROVED',version=version+1,decider=?::jsonb,decided_at=CURRENT_TIMESTAMP,decision_reason='direct"
                            + " SQL concurrency probe' WHERE tenant_id=? AND book_id=? AND"
                            + " year_id=? AND id=?")) {
            p.setString(
                    1,
                    gate.encode(
                            gate.identity(f.tenant(), FinancePostgresTest.users.get("reviewer"))));
            p.setObject(2, f.tenant());
            p.setObject(3, f.f().book());
            p.setObject(4, f.f().year());
            p.setObject(5, request.get("id"));
            p.executeUpdate();
        }
    }

    @Test
    void readCommittedActivationSeesLegacyInsertCommittedWhileWaiting() throws Exception {
        var c = fixture("CAS");
        var r = activation(c);
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try (var activationConnection = FinancePostgresTest.source.getConnection()) {
            activationConnection.setAutoCommit(false);
            activationConnection.setTransactionIsolation(
                    java.sql.Connection.TRANSACTION_READ_COMMITTED);
            int activationPid = pid(activationConnection);
            var writer =
                    pool.submit(
                            () ->
                                    as(
                                            "maker",
                                            () ->
                                                    FinancePostgresTest.tx.execute(
                                                            status -> {
                                                                var n = journalInput(c, "10.00");
                                                                n.put(
                                                                        "idempotencyKey",
                                                                        UUID.randomUUID()
                                                                                .toString());
                                                                oldBinary.write(
                                                                        "maker",
                                                                        c.f().book(),
                                                                        c.f().year(),
                                                                        "journal",
                                                                        null,
                                                                        n);
                                                                ready.countDown();
                                                                try {
                                                                    if (!release.await(
                                                                            10, TimeUnit.SECONDS))
                                                                        throw new IllegalStateException(
                                                                                "test release"
                                                                                    + " timeout");
                                                                } catch (InterruptedException e) {
                                                                    throw new RuntimeException(e);
                                                                }
                                                                return null;
                                                            })));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            var approving =
                    pool.submit(
                            () -> {
                                sqlApprove(activationConnection, c, r);
                                activationConnection.commit();
                                return true;
                            });
            awaitLock(activationPid);
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            var error =
                    assertThrows(
                            ExecutionException.class, () -> approving.get(10, TimeUnit.SECONDS));
            assertTrue(
                    cause(error, "JOURNAL_PERSON_ACTIVATION_STALE")
                            || cause(error, "JOURNAL_PERSON_YEAR_NOT_EMPTY"),
                    error.toString());
            activationConnection.rollback();
            assertEquals("ACCOUNT_ONLY", read("maker", c, "mode", null).get("mode"));
            assertEquals(
                    "PENDING", read("maker", c, "activation", (UUID) r.get("id")).get("status"));
            assertEquals(
                    "1",
                    db.one(
                                    "SELECT count(*)::text n FROM plugin_finance_journal WHERE"
                                            + " year_id=?",
                                    c.f().year())
                            .get("n"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void activationFirstRejectsWaitingOldBinaryAndRepeatableReadFailsClosed() throws Exception {
        var c = fixture("SMALL");
        var r = activation(c);
        var pidReady = new CountDownLatch(1);
        var writerPid = new java.util.concurrent.atomic.AtomicInteger();
        var pool = Executors.newSingleThreadExecutor();
        try (var activationConnection = FinancePostgresTest.source.getConnection()) {
            activationConnection.setAutoCommit(false);
            activationConnection.setTransactionIsolation(
                    java.sql.Connection.TRANSACTION_READ_COMMITTED);
            sqlApprove(activationConnection, c, r);
            var writer =
                    pool.submit(
                            () ->
                                    as(
                                            "maker",
                                            () ->
                                                    FinancePostgresTest.tx.execute(
                                                            status -> {
                                                                writerPid.set(
                                                                        ((Number)
                                                                                        db.one(
                                                                                                        "SELECT"
                                                                                                            + " pg_backend_pid()"
                                                                                                            + " pid")
                                                                                                .get(
                                                                                                        "pid"))
                                                                                .intValue());
                                                                pidReady.countDown();
                                                                var n = journalInput(c, "10.00");
                                                                n.put(
                                                                        "idempotencyKey",
                                                                        UUID.randomUUID()
                                                                                .toString());
                                                                return oldBinary.write(
                                                                        "maker",
                                                                        c.f().book(),
                                                                        c.f().year(),
                                                                        "journal",
                                                                        null,
                                                                        n);
                                                            })));
            assertTrue(pidReady.await(5, TimeUnit.SECONDS));
            awaitLock(writerPid.get());
            activationConnection.commit();
            var error =
                    assertThrows(ExecutionException.class, () -> writer.get(10, TimeUnit.SECONDS));
            assertTrue(cause(error, "JOURNAL_PERSON"), error.toString());
            assertEquals("JOURNAL_PERSON_SOD", read("maker", c, "mode", null).get("mode"));
            assertEquals(
                    "0",
                    db.one(
                                    "SELECT count(*)::text n FROM plugin_finance_journal WHERE"
                                            + " year_id=?",
                                    c.f().year())
                            .get("n"));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        var rr = fixture("CAS");
        var pending = activation(rr);
        try (var connection = FinancePostgresTest.source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            connection.createStatement().executeQuery("SELECT 1").close();
            var error =
                    assertThrows(
                            java.sql.SQLException.class, () -> sqlApprove(connection, rr, pending));
            assertEquals("40001", error.getSQLState());
            connection.rollback();
        }
        assertEquals("ACCOUNT_ONLY", read("maker", rr, "mode", null).get("mode"));
    }

    @Test
    void directSqlCannotForgeAliasApprovalOrOrphanReceiptOrNewCanonicalVersion() {
        var c = fixture("CAS");
        enable(c);
        UUID j = create("maker", c);
        submit(c, j);
        var content =
                gate.content(map("tenant_id", c.tenant(), "id", c.f().book()), c.f().year(), j);
        String before = digest(c);
        var aliasError =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        status -> {
                                            db.update(
                                                    "INSERT INTO"
                                                        + " plugin_finance_journal_person_approval(tenant_id,book_id,year_id,journal_id,id,content_version,content_digest,approved_journal_version,approver,approver_username,expires_at)"
                                                        + " VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,CURRENT_TIMESTAMP+interval"
                                                        + " '1 minute')",
                                                    c.tenant(),
                                                    c.f().book(),
                                                    c.f().year(),
                                                    j,
                                                    UUID.randomUUID(),
                                                    content.get("content_version"),
                                                    content.get("digest"),
                                                    3,
                                                    gate.encode(
                                                            gate.identity(
                                                                    c.tenant(),
                                                                    FinancePostgresTest.users.get(
                                                                            "alias"))),
                                                    "alias");
                                            return null;
                                        }));
        assertTrue(cause(aliasError, "JOURNAL_PERSON_SELF_APPROVAL"));
        var orphan =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        status -> {
                                            db.update(
                                                    "INSERT INTO"
                                                        + " plugin_finance_journal_person_approval(tenant_id,book_id,year_id,journal_id,id,content_version,content_digest,approved_journal_version,approver,approver_username,expires_at)"
                                                        + " VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,CURRENT_TIMESTAMP+interval"
                                                        + " '1 minute')",
                                                    c.tenant(),
                                                    c.f().book(),
                                                    c.f().year(),
                                                    j,
                                                    UUID.randomUUID(),
                                                    content.get("content_version"),
                                                    content.get("digest"),
                                                    3,
                                                    gate.encode(
                                                            gate.identity(
                                                                    c.tenant(),
                                                                    FinancePostgresTest.users.get(
                                                                            "reviewer"))),
                                                    "reviewer");
                                            return null;
                                        }));
        assertTrue(sqlState(orphan, "23503") || cause(orphan, "JOURNAL_PERSON"), orphan.toString());
        assertEquals(
                "0",
                db.one(
                                "SELECT count(*)::text n FROM"
                                    + " plugin_finance_journal_person_approval WHERE journal_id=?",
                                j)
                        .get("n"));
        var version =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        status -> {
                                            db.update(
                                                    "INSERT INTO"
                                                        + " plugin_finance_journal_person_content(tenant_id,book_id,year_id,journal_id,content_version,format,snapshot,canonical_text,digest,maker,editor,created_journal_version)"
                                                        + " SELECT"
                                                        + " tenant_id,book_id,year_id,journal_id,content_version+1,format,snapshot,canonical_text,digest,maker,editor,2"
                                                        + " FROM"
                                                        + " plugin_finance_journal_person_content"
                                                        + " WHERE journal_id=? AND"
                                                        + " content_version=1",
                                                    j);
                                            return null;
                                        }));
        assertTrue(cause(version, "JOURNAL_PERSON_CONTENT_VERSION"));
        assertEquals(before, digest(c));
        legacy("maker", c, "withdraw", j, map("expectedVersion", 2));
        String sparseBefore = digest(c);
        var sparse =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        status -> {
                                            db.update(
                                                    "UPDATE plugin_finance_line SET line_no=500"
                                                            + " WHERE journal_id=? AND line_no=1",
                                                    j);
                                            return null;
                                        }));
        assertTrue(cause(sparse, "JOURNAL_PERSON_INVALID_CONTENT"), sparse.toString());
        assertEquals(sparseBefore, digest(c));
    }

    @Test
    void sourceAndBothYearCloseDirectionsAreBlockedBeforeEffects() {
        var c = fixture("CAS");
        enable(c);
        UUID j = create("maker", c);
        code(
                "JOURNAL_PERSON_PROTOCOL_UNSUPPORTED",
                () ->
                        legacy(
                                "maker",
                                c,
                                "reversals",
                                j,
                                map(
                                        "date",
                                        "2026-01-16",
                                        "reason",
                                        "unsupported reversal",
                                        "expectedVersion",
                                        1)));
        var source = new FinanceSources(db, modern);
        var payload =
                FinanceSourcesPostgresTest.body(
                        c.f(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), "qa-v1");
        code(
                "JOURNAL_PERSON_PROTOCOL_UNSUPPORTED",
                () ->
                        as(
                                "maker",
                                () ->
                                        FinancePostgresTest.tx.execute(
                                                status ->
                                                        source.ingest(
                                                                "maker",
                                                                c.f().book(),
                                                                c.f().year(),
                                                                payload))));
        var oldSource = new FinanceSources(db, oldBinary);
        var raw =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                as(
                                        "maker",
                                        () ->
                                                FinancePostgresTest.tx.execute(
                                                        status ->
                                                                oldSource.ingest(
                                                                        "maker",
                                                                        c.f().book(),
                                                                        c.f().year(),
                                                                        payload))));
        assertTrue(cause(raw, "JOURNAL_PERSON_UNSUPPORTED_PATH"));
        assertEquals(
                "0",
                db.one(
                                "SELECT count(*)::text n FROM plugin_finance_source_event WHERE"
                                        + " year_id=?",
                                c.f().year())
                        .get("n"));
        var next =
                as(
                        "maker",
                        () ->
                                FinancePostgresTest.write(
                                        "maker",
                                        c.f().book(),
                                        null,
                                        "years",
                                        null,
                                        map("year", 2027)));
        UUID nextYear = (UUID) next.get("id");
        code(
                "JOURNAL_PERSON_YEAR_CLOSE_UNSUPPORTED",
                () ->
                        legacy(
                                "maker",
                                c,
                                "year-plan",
                                null,
                                map("targetYearId", nextYear.toString())));
        var prior =
                as(
                        "maker",
                        () ->
                                FinancePostgresTest.write(
                                        "maker",
                                        c.f().book(),
                                        null,
                                        "years",
                                        null,
                                        map("year", 2025)));
        var sourceCase =
                new Case(
                        new FinancePostgresTest.Fixture(
                                c.f().book(),
                                (UUID) prior.get("id"),
                                c.f().cash(),
                                c.f().capital()),
                        c.tenant());
        code(
                "JOURNAL_PERSON_YEAR_CLOSE_UNSUPPORTED",
                () ->
                        legacy(
                                "maker",
                                sourceCase,
                                "year-plan",
                                null,
                                map("targetYearId", c.f().year().toString())));
        var direct =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " plugin_finance_close_plan(tenant_id,book_id,id,source_year_id,target_year_id,digest,created_by,payload)"
                                            + " VALUES(?,?,?,?,?,?,?,?::jsonb)",
                                        c.tenant(),
                                        c.f().book(),
                                        UUID.randomUUID(),
                                        prior.get("id"),
                                        c.f().year(),
                                        "a".repeat(64),
                                        FinancePostgresTest.users.get("maker"),
                                        "{}"));
        assertTrue(cause(direct, "JOURNAL_PERSON_UNSUPPORTED_PATH"));
    }
}
