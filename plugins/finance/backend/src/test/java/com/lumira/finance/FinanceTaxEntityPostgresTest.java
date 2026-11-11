package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.AuthorizationContextProjectionPort;
import com.lumira.platform.identity.api.AuthenticationSession;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real finance-only current-schema transactions. Host assurance is mocked, not live MFA. */
class FinanceTaxEntityPostgresTest {
    static FinanceTaxEntities service;
    static FinanceStore db;
    static AuthorizationContextProjectionPort auth =
            p ->
                    Optional.of(
                            new AuthorizationContextProjectionPort.Snapshot(
                                    FinanceJournalPersonPostgresTest.ALL,
                                    FinanceJournalPersonPostgresTest.ALL,
                                    false,
                                    null,
                                    0));

    @BeforeAll
    static void setup() throws Exception {
        FinanceJournalPersonPostgresTest.setup(18);
        db = FinanceJournalPersonPostgresTest.db;
        FinanceCounterpartyPostgresTest.db = db;
        try (var c = FinancePostgresTest.source.getConnection()) {
            c.setAutoCommit(false);
            c.createStatement()
                    .execute(
                            Files.readString(
                                    Path.of("../migrations/V19__tax_entity_registry.sql")));
            c.commit();
        }
        service =
                new FinanceTaxEntities(
                        db,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        auth);
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
    static Map<String, Object> obj(Object x) {
        return (Map<String, Object>) x;
    }

    static Map<String, Object> map(Object... p) {
        return FinanceJournalPersonGate.map(p);
    }

    static UUID id(Object v) {
        return UUID.fromString(v.toString());
    }

    record Case(FinanceJournalPersonPostgresTest.Case base, UUID original) {
        UUID book() {
            return base.f().book();
        }

        UUID year() {
            return base.f().year();
        }

        UUID tenant() {
            return base.tenant();
        }
    }

    static Case fixture(String standard) {
        var c = FinanceJournalPersonPostgresTest.fixture(standard);
        FinanceEvidenceUsagePostgresTest.db = db;
        return new Case(c, FinanceEvidenceUsagePostgresTest.upload(c, "tax-registration"));
    }

    static Map<String, Object> profile(Case c) {
        var p = FinanceTaxEntityInputTest.profile();
        var o =
                db.one(
                        "SELECT version,sha256 FROM plugin_finance_original WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=? AND id=?",
                        c.tenant(),
                        c.book(),
                        c.year(),
                        c.original());
        p.put(
                "registrationEvidence",
                map(
                        "yearId",
                        c.year().toString(),
                        "originalId",
                        c.original().toString(),
                        "expectedVersion",
                        o.get("version").toString(),
                        "expectedSha256",
                        o.get("sha256"),
                        "sourceType",
                        "PAPER_SCAN",
                        "originalLocation",
                        "组织档案柜A\n第一层"));
        return p;
    }

    static Map<String, Object> create(Case c) {
        var n = FinanceTaxEntityInputTest.create();
        obj(n.get("identity"))
                .put(
                        "canonicalTaxRegistrationNo",
                        "TAX-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
        n.put("content", profile(c));
        return n;
    }

    static Map<String, Object> write(
            String actor, UUID book, String op, UUID p, UUID r, Map<String, Object> n) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () ->
                        obj(
                                service.write(
                                        actor,
                                        book,
                                        op,
                                        p,
                                        r,
                                        FinanceJournalPersonPostgresTest.gate.decode(
                                                FinanceJournalPersonPostgresTest.modern.encode(n)),
                                        FinanceJournalPersonPostgresTest.request())));
    }

    static Map<String, Object> read(
            String actor, UUID book, String op, UUID p, UUID r, Map<String, String> query) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () -> {
                    var req = FinanceJournalPersonPostgresTest.request();
                    query.forEach(req::addParameter);
                    return obj(service.read(actor, book, op, p, r, req));
                });
    }

    static Map<String, Object> current(Map<String, Object> result) {
        return obj(result.get("current"));
    }

    static UUID party(Map<String, Object> d) {
        return id(obj(d.get("taxEntity")).get("id"));
    }

    static UUID revision(Map<String, Object> d) {
        return id(obj(d.get("revision")).get("id"));
    }

    static Map<String, Object> action(Map<String, Object> d) {
        return map(
                "schemaVersion",
                FinanceTaxEntityInput.SCHEMA,
                "idempotencyKey",
                UUID.randomUUID().toString(),
                "reason",
                "组织人工独立决定\n不是税局认证",
                "expectedEntityVersion",
                obj(d.get("taxEntity")).get("version"),
                "expectedVersion",
                obj(d.get("revision")).get("version"),
                "contentDigest",
                obj(d.get("revision")).get("contentDigest"));
    }

    static Map<String, Object> decide(String actor, Case c, String op, Map<String, Object> d) {
        return write(actor, c.book(), op, party(d), revision(d), action(d));
    }

    static Map<String, Object> propose(Case c, Map<String, Object> d, String status) {
        var p = obj(d.get("taxEntity"));
        var app = p.get("approvedRevision") == null ? null : obj(p.get("approvedRevision"));
        var latest =
                db.one(
                        "SELECT version FROM plugin_finance_tax_entity_revision WHERE tenant_id=?"
                            + " AND id=?",
                        c.tenant(),
                        id(p.get("latestRevisionId")));
        return map(
                "schemaVersion",
                FinanceTaxEntityInput.SCHEMA,
                "idempotencyKey",
                UUID.randomUUID().toString(),
                "reason",
                "真实修订而不是重建纳税主体",
                "expectedEntityVersion",
                p.get("version"),
                "expectedLatestRevisionId",
                p.get("latestRevisionId").toString(),
                "expectedLatestRevisionVersion",
                latest.get("version").toString(),
                "expectedApprovedRevisionId",
                app == null ? null : app.get("id").toString(),
                "expectedApprovedContentDigest",
                app == null ? null : app.get("contentDigest"),
                "desiredStatus",
                status,
                "content",
                profile(c));
    }

    static Map<String, Object> proposal(Case c, Map<String, Object> d, String status) {
        return current(
                write(
                        "maker",
                        c.book(),
                        "propose-revision",
                        party(d),
                        null,
                        propose(c, d, status)));
    }

    static void code(String expected, Runnable work) {
        assertEquals(expected, assertThrows(FinanceException.class, work::run).code);
    }

    static long count(String table, Case c) {
        return ((Number)
                        db.one(
                                        "SELECT count(*) n FROM plugin_finance_tax_entity_"
                                                + table
                                                + " WHERE tenant_id=?",
                                        c.tenant())
                                .get("n"))
                .longValue();
    }

    @Test
    void casAndSmallPublishInactivateRestoreWithImmutableReceiptsAndNoFinancialEffects() {
        for (String standard : List.of("CAS", "SMALL")) {
            var c = fixture(standard);
            var before = FinanceCounterpartyPostgresTest.financial(c.book());
            var n = create(c);
            var created = write("maker", c.book(), "create", null, null, n);
            var d = current(created);
            UUID p = party(d);
            var rc = obj(created.get("commandReceipt"));
            assertEquals("CREATE", rc.get("action"));
            assertEquals("1", rc.get("targetVersion"));
            assertEquals("UNPUBLISHED", obj(d.get("taxEntity")).get("effectiveStatus"));
            assertEquals(
                    FinancePostgresTest.users.get("plain").toString(),
                    obj(obj(d.get("content")).get("registrationEvidence")).get("createdBy"));
            assertTrue(((List<?>) d.get("allowedActions")).contains("CANCEL"));
            assertFalse(
                    ((List<?>)
                                    read(
                                                    "alias",
                                                    c.book(),
                                                    "revision-detail",
                                                    p,
                                                    revision(d),
                                                    Map.of())
                                            .get("allowedActions"))
                            .contains("APPROVE"));
            var approvalBody = action(d);
            var published = write("reviewer", c.book(), "approve", p, revision(d), approvalBody);
            d = current(published);
            assertEquals("ACTIVE", obj(d.get("taxEntity")).get("effectiveStatus"));
            d = current(decide("reviewer", c, "approve", proposal(c, d, "INACTIVE")));
            assertEquals("INACTIVE", obj(d.get("taxEntity")).get("effectiveStatus"));
            assertEquals(false, obj(d.get("taxEntity")).get("masterDataEligible"));
            var replay =
                    write(
                            "reviewer",
                            c.book(),
                            "approve",
                            p,
                            id(rc.get("revisionId")),
                            approvalBody);
            assertEquals(
                    obj(published.get("commandReceipt")).get("id"),
                    obj(replay.get("commandReceipt")).get("id"));
            assertEquals("INACTIVE", obj(current(replay).get("taxEntity")).get("effectiveStatus"));
            d = current(decide("reviewer", c, "approve", proposal(c, d, "ACTIVE")));
            assertEquals("ACTIVE", obj(d.get("taxEntity")).get("effectiveStatus"));
            var newKey = new LinkedHashMap<>(n);
            newKey.put("idempotencyKey", UUID.randomUUID().toString());
            var opReplay = write("maker", c.book(), "create", null, null, newKey);
            assertEquals(rc.get("id"), obj(opReplay.get("commandReceipt")).get("id"));
            assertEquals(p, party(current(opReplay)));
            assertEquals(3L, count("revision", c));
            assertEquals(6L, count("command_receipt", c));
            assertEquals(before, FinanceCounterpartyPostgresTest.financial(c.book()));
            assertEquals(
                    false,
                    read("maker", c.book(), "catalog", null, null, Map.of())
                            .get("taxFilingImplemented"));
        }
    }

    @Test
    void firstRejectOrCancelKeepsIdentityAndCanContinueWithoutAnApprovedBaseline() {
        for (String terminal : List.of("reject", "cancel")) {
            var c = fixture("CAS");
            var d = current(write("maker", c.book(), "create", null, null, create(c)));
            UUID p = party(d);
            d = current(decide(terminal.equals("cancel") ? "maker" : "reviewer", c, terminal, d));
            assertEquals("UNPUBLISHED", obj(d.get("taxEntity")).get("effectiveStatus"));
            var bad = propose(c, d, "INACTIVE");
            code(
                    "TAX_ENTITY_STATE_CONFLICT",
                    () -> write("maker", c.book(), "propose-revision", p, null, bad));
            d = current(decide("reviewer", c, "approve", proposal(c, d, "ACTIVE")));
            assertEquals(p, party(d));
            assertEquals("2", obj(d.get("revision")).get("revision"));
        }
    }

    @Test
    void aliasStrongSessionAndDepartedMakerAreCheckedBeforeCachedApproval() {
        var c = fixture("CAS");
        var input = create(c);
        var d = current(write("maker", c.book(), "create", null, null, input));
        var a = action(d);
        UUID p = party(d), r = revision(d);
        code("SELF_APPROVAL_FORBIDDEN", () -> write("alias", c.book(), "approve", p, r, a));
        code("SCOPE_DENIED", () -> write("plain", c.book(), "create", null, null, create(c)));
        FinanceJournalPersonPostgresTest.as(
                "maker",
                () -> {
                    var req = FinanceJournalPersonPostgresTest.request();
                    req.getSession()
                            .setAttribute(AuthenticationSession.VERIFIED_FACTORS, List.of());
                    code(
                            "PERSON_AUTH_ASSURANCE_REQUIRED",
                            () ->
                                    service.write(
                                            "maker", c.book(), "create", null, null, input, req));
                    return null;
                });
        var approved = write("reviewer", c.book(), "approve", p, r, a);
        assertEquals("APPROVED", obj(current(approved).get("revision")).get("status"));
        FinanceJournalPersonPostgresTest.denied.add("maker:platform:finance:manage");
        code("TAX_ENTITY_APPROVAL_STALE", () -> write("reviewer", c.book(), "approve", p, r, a));
        FinanceJournalPersonPostgresTest.denied.clear();
        var pending = proposal(c, current(approved), "INACTIVE");
        FinanceJournalPersonPostgresTest.denied.add("maker:platform:finance:create");
        var rejected = decide("reviewer", c, "reject", pending);
        assertEquals("REJECTED", obj(current(rejected).get("revision")).get("status"));
    }

    @Test
    void crossBookDualReadAndSourceYearAreSeparateFromClosedYearAndTenantWideIdentity() {
        var c = fixture("CAS");
        UUID other = FinanceCounterpartyPostgresTest.sameEntityBook(c.base());
        var n = create(c);
        var d = current(write("maker", c.book(), "create", null, null, n));
        UUID p = party(d), r = revision(d);
        assertEquals(
                p,
                id(
                        obj(read("maker", other, "detail", p, null, Map.of()).get("taxEntity"))
                                .get("id")));
        assertTrue(
                ((List<?>) read("maker", other, "list", null, null, Map.of()).get("items"))
                        .isEmpty());
        code("SCOPE_DENIED", () -> write("maker", other, "approve", p, r, action(d)));
        FinancePostgresTest.write(
                "maker",
                other,
                null,
                "members",
                null,
                map("username", "plain", "role", "MAKER", "enabled", true));
        UUID otherYear =
                id(FinancePostgresTest.read("maker", other, null, "years").getFirst().get("id"));
        var otherBase =
                new FinanceJournalPersonPostgresTest.Case(
                        new FinancePostgresTest.Fixture(other, otherYear, null, null), c.tenant());
        var otherCase =
                new Case(
                        otherBase,
                        FinanceEvidenceUsagePostgresTest.upload(otherBase, "other-home-tax"));
        var duplicate = new LinkedHashMap<>(n);
        duplicate.put("content", profile(otherCase));
        duplicate.put("operationId", UUID.randomUUID().toString());
        duplicate.put("idempotencyKey", UUID.randomUUID().toString());
        var error =
                assertThrows(
                        FinanceException.class,
                        () -> write("maker", other, "create", null, null, duplicate));
        assertEquals("TAX_ENTITY_IDENTITY_EXISTS", error.code);
        assertEquals(p.toString(), error.issues.get(0).get("reason"));
        assertEquals(c.book().toString(), error.issues.get(1).get("reason"));
        FinancePostgresTest.write(
                "maker",
                other,
                null,
                "members",
                null,
                map("username", "outsider", "role", "OWNER", "enabled", true));
        FinanceJournalPersonPostgresTest.bind(c.base(), "outsider");
        var denied =
                assertThrows(
                        FinanceException.class,
                        () -> write("outsider", other, "create", null, null, duplicate));
        assertEquals("SCOPE_DENIED", denied.code);
        assertTrue(denied.issues.isEmpty());
        assertFalse(denied.getReason().contains(p.toString()));
        UUID anotherYear =
                id(
                        FinancePostgresTest.write(
                                        "maker", c.book(), null, "years", null, map("year", 2027))
                                .get("id"));
        FinancePostgresTest.write(
                "maker",
                c.book(),
                null,
                "members",
                null,
                map(
                        "username",
                        "maker",
                        "role",
                        "OWNER",
                        "yearScope",
                        "SELECTED",
                        "yearIds",
                        List.of(anotherYear.toString()),
                        "enabled",
                        true));
        assertNotNull(read("maker", c.book(), "list", null, null, Map.of()).get("items"));
        code("SCOPE_DENIED", () -> read("maker", c.book(), "revision-detail", p, r, Map.of()));
        FinancePostgresTest.write(
                "other",
                c.book(),
                null,
                "members",
                null,
                map(
                        "username",
                        "maker",
                        "role",
                        "OWNER",
                        "yearScope",
                        "ALL",
                        "yearIds",
                        List.of(),
                        "enabled",
                        true));
        db.update(
                "UPDATE plugin_finance_year SET status='CLOSED' WHERE tenant_id=? AND book_id=? AND"
                    + " id=?",
                c.tenant(),
                c.book(),
                c.year());
        var approved = decide("reviewer", c, "approve", d);
        assertEquals("ACTIVE", obj(current(approved).get("taxEntity")).get("effectiveStatus"));
        assertEquals(
                "CLOSED",
                db.one("SELECT status FROM plugin_finance_year WHERE id=?", c.year())
                        .get("status"));
    }

    @Test
    void twoWorkersActuallyContendAndPermanentOperationDoesNotCreateAnotherIdentity()
            throws Exception {
        var c = fixture("CAS");
        var n = create(c);
        var pool = Executors.newFixedThreadPool(2);
        var go = new CountDownLatch(1);
        var futures = new ArrayList<Future<Map<String, Object>>>();
        try (var holder = FinancePostgresTest.source.getConnection()) {
            holder.setAutoCommit(false);
            try (var s =
                    holder.prepareStatement(
                            "SELECT id FROM plugin_finance_book WHERE id=? FOR UPDATE")) {
                s.setObject(1, c.book());
                s.executeQuery();
            }
            for (int i = 0; i < 2; i++) {
                var x = new LinkedHashMap<>(n);
                x.put("idempotencyKey", UUID.randomUUID().toString());
                futures.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    return write("maker", c.book(), "create", null, null, x);
                                }));
            }
            go.countDown();
            long until = System.nanoTime() + 1_500_000_000L;
            long blocked = 0;
            while (System.nanoTime() < until) {
                blocked =
                        ((Number)
                                        db.one(
                                                        "SELECT count(*) n FROM pg_stat_activity"
                                                            + " WHERE datname=current_database()"
                                                            + " AND pid<>pg_backend_pid() AND"
                                                            + " wait_event_type='Lock' AND query"
                                                            + " LIKE"
                                                            + " '%plugin_finance_tax_entity_lock%'"
                                                            + " ")
                                                .get("n"))
                                .longValue();
                if (blocked >= 2) break;
                Thread.sleep(20);
            }
            assertEquals(2, blocked);
            holder.rollback();
            var a = futures.get(0).get(15, TimeUnit.SECONDS);
            var b = futures.get(1).get(15, TimeUnit.SECONDS);
            assertEquals(party(current(a)), party(current(b)));
            assertEquals(
                    obj(a.get("commandReceipt")).get("id"), obj(b.get("commandReceipt")).get("id"));
            assertEquals(1L, count("registry", c));
            assertEquals(1L, count("audit", c));
            System.out.println(
                    "TAX_ENTITY actual overlappingWorkers=2 blocked="
                            + blocked
                            + " oneReceipt=true");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    static void commitDenied(String sql) throws Exception {
        boolean completed = false, committed = false;
        java.sql.SQLException rejected = null;
        try (var c = FinancePostgresTest.source.getConnection()) {
            c.setAutoCommit(false);
            try {
                c.createStatement().execute(sql);
                completed = true;
                c.commit();
                committed = true;
            } catch (java.sql.SQLException x) {
                rejected = x;
                c.rollback();
            }
        }
        System.out.println(
                "TAX_ENTITY direct allSQLCompleted="
                        + completed
                        + " committed="
                        + committed
                        + " SQLSTATE="
                        + (rejected == null ? null : rejected.getSQLState())
                        + " cause="
                        + (rejected == null ? null : rejected.getMessage()));
        assertTrue(completed);
        assertFalse(committed);
        assertNotNull(rejected);
        assertEquals("P0001", rejected.getSQLState());
        assertTrue(rejected.getMessage().contains("TAX_ENTITY_STATE_CONFLICT"));
    }

    @Test
    void realCommitRejectsPoisonCurrentAndApprovedWithoutReceiptAuditRequest() throws Exception {
        var c = fixture("CAS");
        var d = current(write("maker", c.book(), "create", null, null, create(c)));
        UUID p = party(d), r = revision(d);
        String where = "tenant_id='" + c.tenant() + "'::uuid AND id='" + p + "'::uuid";
        commitDenied(
                "UPDATE plugin_finance_tax_entity_registry SET"
                    + " version=version+1,approved_revision_id='"
                        + r
                        + "'::uuid,updated_at=CURRENT_TIMESTAMP WHERE "
                        + where);
        UUID missing = UUID.randomUUID();
        commitDenied(
                "UPDATE plugin_finance_tax_entity_revision SET"
                    + " status='APPROVED',version=2,decision_receipt_id='"
                        + missing
                        + "'::uuid WHERE tenant_id='"
                        + c.tenant()
                        + "'::uuid AND id='"
                        + r
                        + "'::uuid;UPDATE plugin_finance_tax_entity_registry SET"
                        + " version=version+1,pending_revision_id=NULL,approved_revision_id='"
                        + r
                        + "'::uuid,updated_at=CURRENT_TIMESTAMP WHERE "
                        + where
                        + ";INSERT INTO"
                        + " plugin_finance_tax_entity_command_receipt(tenant_id,legal_entity_id,tax_entity_id,home_book_id,revision_id,id,action,registry_version,target_version,content_digest,actor,reason,payload,payload_hash,route)"
                        + " SELECT"
                        + " v.tenant_id,v.legal_entity_id,v.tax_entity_id,v.home_book_id,v.id,'"
                        + missing
                        + "'::uuid,'APPROVE',2,2,v.content_digest,plugin_finance_journal_person_identity(v.tenant_id,'"
                        + FinancePostgresTest.users.get("reviewer")
                        + "'::uuid,true),'independent"
                        + " decision',q.payload,plugin_finance_tax_entity_digest(q.payload),'approve/'||v.tax_entity_id::text||'/'||v.id::text"
                        + " FROM plugin_finance_tax_entity_revision v CROSS JOIN LATERAL(SELECT"
                        + " jsonb_build_object('schemaVersion','tax-entity/1','reason','independent"
                        + " decision','expectedEntityVersion','1','expectedVersion','1','contentDigest',v.content_digest)"
                        + " payload) q WHERE v.tenant_id='"
                        + c.tenant()
                        + "'::uuid AND v.id='"
                        + r
                        + "'::uuid");
        assertEquals(
                "PENDING",
                db.one("SELECT status FROM plugin_finance_tax_entity_revision WHERE id=?", r)
                        .get("status"));
        assertEquals(1L, count("command_receipt", c));
    }

    @Test
    void auditFailureRollsBackAllSixTablesAndKeyThenSameRequestRecovers() {
        var c = fixture("SMALL");
        var n = create(c);
        var before =
                db.one(
                                "SELECT generation FROM plugin_finance_tax_entity_home_guard WHERE"
                                    + " book_id=?",
                                c.book())
                        .get("generation");
        db.update(
                "CREATE FUNCTION tax_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
                    + " NEW.tenant_id='"
                        + c.tenant()
                        + "'::uuid THEN RAISE EXCEPTION 'TAX_TEST_INJECTED_AUDIT_FAILURE';END"
                        + " IF;RETURN NEW;END $$");
        db.update(
                "CREATE TRIGGER tax_test_fail BEFORE INSERT ON plugin_finance_tax_entity_audit FOR"
                    + " EACH ROW EXECUTE FUNCTION tax_test_fail()");
        try {
            var failure =
                    assertThrows(
                            RuntimeException.class,
                            () -> write("maker", c.book(), "create", null, null, n));
            StringBuilder causes = new StringBuilder();
            for (Throwable x = failure; x != null; x = x.getCause()) causes.append(x.getMessage());
            assertTrue(causes.toString().contains("TAX_TEST_INJECTED_AUDIT_FAILURE"));
            for (String table :
                    List.of("registry", "revision", "command_receipt", "request", "audit"))
                assertEquals(0L, count(table, c));
            assertEquals(
                    before,
                    db.one(
                                    "SELECT generation FROM plugin_finance_tax_entity_home_guard"
                                        + " WHERE book_id=?",
                                    c.book())
                            .get("generation"));
        } finally {
            db.update("DROP TRIGGER tax_test_fail ON plugin_finance_tax_entity_audit");
            db.update("DROP FUNCTION tax_test_fail()");
        }
        assertNotNull(write("maker", c.book(), "create", null, null, n).get("commandReceipt"));
    }

    @Test
    void boundedCursorRejectsMixedEpochForeignActorFiltersAndReadNeverLocks() {
        var c = fixture("CAS");
        for (int i = 0; i < 3; i++) write("maker", c.book(), "create", null, null, create(c));
        var first = read("maker", c.book(), "list", null, null, Map.of("limit", "1"));
        String cursor = first.get("nextCursor").toString();
        var q = Map.of("limit", "1", "cursor", cursor);
        assertEquals(
                1, ((List<?>) read("maker", c.book(), "list", null, null, q).get("items")).size());
        code("INVALID_INPUT", () -> read("reviewer", c.book(), "list", null, null, q));
        code(
                "INVALID_INPUT",
                () ->
                        read(
                                "maker",
                                c.book(),
                                "list",
                                null,
                                null,
                                Map.of("limit", "2", "cursor", cursor)));
        write("maker", c.book(), "create", null, null, create(c));
        code("TAX_ENTITY_PAGE_STALE", () -> read("maker", c.book(), "list", null, null, q));
        var observed =
                new FinanceStore(FinancePostgresTest.jdbc) {
                    @Override
                    List<Map<String, Object>> list(String sql, Object... args) {
                        assertFalse(sql.toUpperCase(Locale.ROOT).contains("FOR UPDATE"));
                        assertFalse(sql.toUpperCase(Locale.ROOT).contains("FOR SHARE"));
                        var mode =
                                super.list(
                                                "SELECT current_setting('transaction_read_only')"
                                                    + " ro,current_setting('transaction_isolation')"
                                                    + " iso")
                                        .getFirst();
                        assertEquals("on", mode.get("ro"));
                        assertEquals("repeatable read", mode.get("iso"));
                        return super.list(sql, args);
                    }
                };
        var readonly =
                new FinanceTaxEntities(
                        observed,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        auth);
        FinanceJournalPersonPostgresTest.as(
                "maker",
                () ->
                        readonly.read(
                                "maker",
                                c.book(),
                                "list",
                                null,
                                null,
                                FinanceJournalPersonPostgresTest.request()));
    }

    @Test
    void actualLockAndStatementBudgetsAreTypedAndNeverReturnPartialSuccess() throws Exception {
        var c = fixture("CAS");
        var n = create(c);
        try (var holder = FinancePostgresTest.source.getConnection()) {
            holder.setAutoCommit(false);
            try (var s =
                    holder.prepareStatement(
                            "SELECT id FROM plugin_finance_book WHERE id=? FOR UPDATE")) {
                s.setObject(1, c.book());
                s.executeQuery();
            }
            code("TAX_ENTITY_BUSY", () -> write("maker", c.book(), "create", null, null, n));
            holder.rollback();
        }
        assertEquals(0L, count("request", c));
        assertNotNull(write("maker", c.book(), "create", null, null, n).get("commandReceipt"));
        var slow =
                new FinanceStore(FinancePostgresTest.jdbc) {
                    boolean delay = true;

                    @Override
                    List<Map<String, Object>> list(String sql, Object... args) {
                        if (delay && sql.startsWith("SELECT p.*")) {
                            delay = false;
                            super.list("SELECT pg_sleep(4)");
                        }
                        return super.list(sql, args);
                    }
                };
        var limited =
                new FinanceTaxEntities(
                        slow,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        auth);
        FinanceJournalPersonPostgresTest.as(
                "maker",
                () -> {
                    code(
                            "TAX_ENTITY_QUERY_BUDGET_EXCEEDED",
                            () ->
                                    limited.read(
                                            "maker",
                                            c.book(),
                                            "list",
                                            null,
                                            null,
                                            FinanceJournalPersonPostgresTest.request()));
                    return null;
                });
        var plan =
                db.list(
                        "EXPLAIN SELECT id FROM plugin_finance_tax_entity_registry WHERE"
                            + " tenant_id=? AND legal_entity_id=(SELECT legal_entity_id FROM"
                            + " plugin_finance_book WHERE id=?) AND home_book_id=? ORDER BY"
                            + " created_at DESC,id DESC LIMIT 101",
                        c.tenant(),
                        c.book(),
                        c.book());
        System.out.println("TAX_ENTITY bounded home plan (no seqscan override): " + plan);
    }
}
