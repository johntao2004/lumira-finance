package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;

import java.util.*;
import java.util.concurrent.*;

/**
 * Written against the isolated PostgreSQL harness; execution waits for the owner's runtime window.
 */
class FinanceSourcesPostgresTest {
    static FinanceSources sources;
    static FinanceOutbox outbox;
    static FinanceStore db;

    @BeforeAll
    static void setup() throws Exception {
        FinancePostgresTest.setup();
        db = new FinanceStore(FinancePostgresTest.jdbc);
        sources = new FinanceSources(db, FinancePostgresTest.service);
        outbox = new FinanceOutbox(db, FinancePostgresTest.service);
    }

    @AfterAll
    static void cleanup() {
        FinancePostgresTest.cleanup();
    }

    static Map<String, Object> body(
            FinancePostgresTest.Fixture f, String source, String key, String handler) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", "1.0.0");
        event.put("eventId", UUID.randomUUID().toString());
        event.put("sourceSystem", "qa-erp");
        event.put("sourceEventId", source);
        event.put("sourceDocumentId", "invoice-" + source);
        event.put("sourceDocumentVersion", 1);
        event.put("eventType", "CASH_RECEIVED");
        event.put("effectType", "CREATE_JOURNAL_DRAFT");
        event.put("businessDate", "2026-01-10");
        event.put("accountingDate", "2026-01-10");
        event.put("occurredAt", "2026-01-10T01:02:03Z");
        event.put("currency", "CNY");
        event.put("reason", "");
        event.put(
                "lines",
                List.of(
                        Map.of(
                                "sourceLineId",
                                "L1",
                                "businessSemantic",
                                "capital-receipt",
                                "description",
                                "",
                                "quantity",
                                "1",
                                "netAmount",
                                "100.00",
                                "taxAmount",
                                "0.00",
                                "grossAmount",
                                "100.00",
                                "dimensions",
                                List.of(),
                                "taxTreatment",
                                "UNDETERMINED")));
        event.put("evidenceIds", List.of());
        event.put("reversesSourceEventId", null);
        return new LinkedHashMap<>(
                Map.of(
                        "idempotencyKey",
                        key,
                        "mappingRuleVersion",
                        "explicit-map-" + handler,
                        "handlerVersion",
                        handler,
                        "event",
                        event,
                        "journal",
                        Map.of(
                                "summary",
                                "controlled source draft",
                                "lines",
                                List.of(
                                        Map.of(
                                                "sourceLineId",
                                                "L1",
                                                "accountId",
                                                f.cash().toString(),
                                                "debit",
                                                "100.00",
                                                "credit",
                                                "0",
                                                "memo",
                                                ""),
                                        Map.of(
                                                "sourceLineId",
                                                "L1",
                                                "accountId",
                                                f.capital().toString(),
                                                "debit",
                                                "0",
                                                "credit",
                                                "100.00",
                                                "memo",
                                                "")))));
    }

    static Map<String, Object> ingest(
            String actor, FinancePostgresTest.Fixture f, Map<String, Object> body) {
        return FinancePostgresTest.tx.execute(
                status -> sources.ingest(actor, f.book(), f.year(), body));
    }

    static long count(String table, UUID book) {
        return ((Number)
                        db.one("SELECT count(*) AS n FROM " + table + " WHERE book_id=?", book)
                                .get("n"))
                .longValue();
    }

    @Test
    void twentyIndependentDeliveriesAndHandlerVersionsProduceOneStableEffect() throws Exception {
        var f = FinancePostgresTest.fixture("CAS");
        String source = "same-source-" + UUID.randomUUID();
        Set<String> results = new HashSet<>();
        var simultaneousStart = new CyclicBarrier(20);
        try (var pool = Executors.newFixedThreadPool(20)) {
            List<Callable<Map<String, Object>>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String version = "handler-" + i;
                calls.add(
                        () -> {
                            simultaneousStart.await(15, TimeUnit.SECONDS);
                            return ingest(
                                    "maker",
                                    f,
                                    body(f, source, UUID.randomUUID().toString(), version));
                        });
            }
            for (var future : pool.invokeAll(calls))
                results.add(future.get().get("journalId").toString());
        }
        assertEquals(1, results.size());
        assertEquals(1, count("plugin_finance_source_event", f.book()));
        assertEquals(1, count("plugin_finance_business_effect", f.book()));
        assertEquals(2, count("plugin_finance_outbox", f.book()));
        assertEquals(0, count("plugin_finance_posting", f.book()));
        assertEquals(
                1L,
                ((Number)
                                db.one(
                                                "SELECT count(*) AS n FROM"
                                                        + " plugin_finance_consumer_receipt WHERE"
                                                        + " tenant_id=?",
                                                FinancePostgresTest.service
                                                        .originalScope(
                                                                "maker", f.book(), f.year(),
                                                                "manage")
                                                        .get("tenant_id"))
                                        .get("n"))
                        .longValue());
        var changed = body(f, source, UUID.randomUUID().toString(), "handler-99");
        ((Map<String, Object>) changed.get("event")).put("reason", "changed business payload");
        assertEquals(
                "SOURCE_PAYLOAD_CONFLICT",
                assertThrows(FinanceException.class, () -> ingest("maker", f, changed)).code);
        var mapped = body(f, source, UUID.randomUUID().toString(), "handler-100");
        var mappedJournal = new LinkedHashMap<>((Map<String, Object>) mapped.get("journal"));
        mappedJournal.put("summary", "different actual mapping");
        mapped.put("journal", mappedJournal);
        assertEquals(
                "EFFECT_PAYLOAD_CONFLICT",
                assertThrows(FinanceException.class, () -> ingest("maker", f, mapped)).code);
        var effect =
                db.one("SELECT * FROM plugin_finance_business_effect WHERE book_id=?", f.book());
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "INSERT INTO"
                                    + " plugin_finance_consumer_receipt(tenant_id,effect_id,stable_consumer,effect_key,payload_digest,handler_version)"
                                    + " VALUES(?,?,?,?,?,'forged')",
                                effect.get("tenant_id"),
                                effect.get("id"),
                                effect.get("stable_consumer"),
                                "0".repeat(64),
                                effect.get("payload_digest")));
        var detail =
                sources.detail(
                        "maker",
                        f.book(),
                        f.year(),
                        (UUID)
                                db.one(
                                                "SELECT id FROM plugin_finance_source_event WHERE"
                                                        + " book_id=?",
                                                f.book())
                                        .get("id"));
        var mapping = (Map<String, Object>) detail.get("mappingSnapshot");
        assertEquals(
                "L1",
                ((List<Map<String, Object>>) mapping.get("lines")).getFirst().get("sourceLineId"));
        var multi = body(f, "multi-source", UUID.randomUUID().toString(), "v1");
        var event = (Map<String, Object>) multi.get("event");
        var line = ((List<?>) event.get("lines")).getFirst();
        event.put("lines", List.of(line, line));
        assertThrows(FinanceException.class, () -> ingest("maker", f, multi));
        assertEquals(1, count("plugin_finance_journal", f.book()));
    }

    @Test
    void sourceAndPostedOutboxRollbackWithTheirLocalFinancialResult() {
        var f = FinancePostgresTest.fixture("SMALL");
        assertThrows(
                IllegalStateException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                status -> {
                                    sources.ingest(
                                            "maker",
                                            f.book(),
                                            f.year(),
                                            body(
                                                    f,
                                                    "rollback",
                                                    UUID.randomUUID().toString(),
                                                    "v1"));
                                    throw new IllegalStateException(
                                            "injected after complete intake");
                                }));
        for (String table :
                List.of(
                        "plugin_finance_source_event",
                        "plugin_finance_business_effect",
                        "plugin_finance_journal",
                        "plugin_finance_outbox")) assertEquals(0, count(table, f.book()));
        var source =
                ingest(
                        "maker",
                        f,
                        body(f, "commit-after-rollback", UUID.randomUUID().toString(), "v2"));
        UUID journal = (UUID) source.get("journalId");
        FinancePostgresTest.action("maker", f, journal, "submit", 1);
        FinancePostgresTest.action("reviewer", f, journal, "approve", 2);
        assertThrows(
                IllegalStateException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                status -> {
                                    FinancePostgresTest.service.write(
                                            "maker",
                                            f.book(),
                                            f.year(),
                                            "post",
                                            journal,
                                            Map.of(
                                                    "idempotencyKey",
                                                    UUID.randomUUID().toString(),
                                                    "expectedVersion",
                                                    3));
                                    assertEquals(5, count("plugin_finance_outbox", f.book()));
                                    throw new IllegalStateException(
                                            "injected after posted envelope");
                                }));
        assertEquals(4, count("plugin_finance_outbox", f.book()));
        assertEquals(0, count("plugin_finance_posting", f.book()));
        assertEquals(
                "APPROVED",
                FinancePostgresTest.service
                        .journal(
                                FinancePostgresTest.service.originalScope(
                                        "maker", f.book(), f.year(), "read"),
                                f.year(),
                                journal)
                        .get("status"));
    }

    @Test
    void localConsumerLeaseRecoveryDedupAndRebuildNeverGenerateAnotherJournal() {
        var f = FinancePostgresTest.fixture("CAS");
        var result = ingest("maker", f, body(f, "projection", UUID.randomUUID().toString(), "v1"));
        UUID journal = (UUID) result.get("journalId");
        FinancePostgresTest.action("maker", f, journal, "submit", 1);
        FinancePostgresTest.action("reviewer", f, journal, "approve", 2);
        FinancePostgresTest.action("maker", f, journal, "post", 3);
        var claimed = FinancePostgresTest.tx.execute(status -> outbox.claim(20));
        assertTrue(claimed.size() >= 5);
        for (var row : claimed)
            FinancePostgresTest.tx.executeWithoutResult(
                    status -> outbox.consume((UUID) row.get("id"), (UUID) row.get("lease_token")));
        assertEquals(1, count("plugin_finance_journal_projection", f.book()));
        assertEquals(1, count("plugin_finance_journal", f.book()));
        assertEquals(
                "POSTED",
                db.one(
                                "SELECT status FROM plugin_finance_journal_projection WHERE"
                                        + " journal_id=?",
                                journal)
                        .get("status"));
        UUID event = (UUID) claimed.getFirst().get("id"), stale = UUID.randomUUID();
        db.update(
                "UPDATE plugin_finance_outbox SET"
                        + " state='PROCESSING',lease_token=?,lease_until=now()+interval '1 minute'"
                        + " WHERE id=?",
                stale,
                event);
        assertEquals(
                "OUTBOX_LEASE_ACTIVE",
                assertThrows(
                                FinanceException.class,
                                () ->
                                        FinancePostgresTest.tx.execute(
                                                status ->
                                                        outbox.retry(
                                                                "maker",
                                                                f.book(),
                                                                f.year(),
                                                                event,
                                                                Map.of(
                                                                        "idempotencyKey",
                                                                        UUID.randomUUID()
                                                                                .toString(),
                                                                        "reason",
                                                                        "do not steal active"
                                                                                + " lease"))))
                        .code);
        db.update(
                "UPDATE plugin_finance_outbox SET lease_until=now()-interval '1 second' WHERE id=?",
                event);
        var reclaimed = FinancePostgresTest.tx.execute(status -> outbox.claim(20));
        assertEquals(1, reclaimed.size());
        UUID fresh = (UUID) reclaimed.getFirst().get("lease_token");
        FinancePostgresTest.tx.executeWithoutResult(status -> outbox.consume(event, stale));
        assertEquals(
                "PROCESSING",
                db.one("SELECT state FROM plugin_finance_outbox WHERE id=?", event).get("state"));
        FinancePostgresTest.tx.executeWithoutResult(status -> outbox.consume(event, fresh));
        assertEquals(1, count("plugin_finance_journal_projection", f.book()));
        db.update(
                "UPDATE plugin_finance_journal_projection SET source_version=999,summary='damaged"
                        + " technical view' WHERE book_id=?",
                f.book());
        db.update(
                "UPDATE plugin_finance_year SET status='CLOSED_PENDING_ARCHIVE' WHERE id=?",
                f.year());
        var rebuild =
                FinancePostgresTest.tx.execute(
                        status ->
                                outbox.rebuild(
                                        "maker",
                                        f.book(),
                                        f.year(),
                                        Map.of(
                                                "idempotencyKey",
                                                UUID.randomUUID().toString(),
                                                "reason",
                                                "restore lost technical projection")));
        assertEquals("REBUILT", rebuild.get("status"));
        assertEquals(
                4L,
                db.one(
                                "SELECT source_version FROM plugin_finance_journal_projection WHERE"
                                        + " journal_id=?",
                                journal)
                        .get("source_version"));
        db.update("DELETE FROM plugin_finance_journal_projection WHERE book_id=?", f.book());
        FinancePostgresTest.tx.execute(
                status ->
                        outbox.rebuild(
                                "maker",
                                f.book(),
                                f.year(),
                                Map.of(
                                        "idempotencyKey",
                                        UUID.randomUUID().toString(),
                                        "reason",
                                        "restore missing view while DONE receipts stay intact")));
        assertEquals(1, count("plugin_finance_journal_projection", f.book()));
        assertEquals(1, count("plugin_finance_journal", f.book()));
        assertEquals(1, count("plugin_finance_posting", f.book()));
        assertEquals("DONE", outbox.detail("maker", f.book(), f.year(), event).get("state"));
        assertThrows(FinanceException.class, () -> outbox.claim(21));
    }

    @Test
    void scopesAnnualGrantsAndRevocationApplyBeforeReplayAndInspection() {
        var f = FinancePostgresTest.fixture("CAS");
        String source = "scoped-" + UUID.randomUUID();
        var input = body(f, source, UUID.randomUUID().toString(), "v1");
        var first = ingest("maker", f, input);
        UUID id = (UUID) first.get("id");
        for (String actor : List.of("reviewer", "outsider"))
            assertEquals(
                    "SCOPE_DENIED",
                    assertThrows(
                                    FinanceException.class,
                                    () ->
                                            ingest(
                                                    actor,
                                                    f,
                                                    body(
                                                            f,
                                                            source,
                                                            UUID.randomUUID().toString(),
                                                            "v2")))
                            .code);
        var other = FinancePostgresTest.fixture("SMALL");
        assertThrows(
                FinanceException.class,
                () -> sources.detail("maker", other.book(), other.year(), id));
        var originalScope =
                FinancePostgresTest.service.originalScope("maker", f.book(), f.year(), "manage");
        var linkedBook =
                FinancePostgresTest.write(
                        "maker",
                        null,
                        null,
                        "book",
                        null,
                        Map.of(
                                "tenantId",
                                originalScope.get("tenant_id").toString(),
                                "legalEntityId",
                                originalScope.get("legal_entity_id").toString(),
                                "name",
                                "same tenant second book",
                                "standard",
                                "CAS",
                                "baseCurrency",
                                "CNY",
                                "year",
                                2026));
        UUID linkedId = (UUID) linkedBook.get("id"),
                linkedYear =
                        (UUID)
                                FinancePostgresTest.read("maker", linkedId, null, "years")
                                        .getFirst()
                                        .get("id");
        UUID
                linkedCash =
                        (UUID)
                                FinancePostgresTest.write(
                                                "maker",
                                                linkedId,
                                                linkedYear,
                                                "accounts",
                                                null,
                                                Map.of(
                                                        "code",
                                                        "1001",
                                                        "name",
                                                        "Cash",
                                                        "normalSide",
                                                        "DEBIT",
                                                        "category",
                                                        "ASSET"))
                                        .get("id"),
                linkedCapital =
                        (UUID)
                                FinancePostgresTest.write(
                                                "maker",
                                                linkedId,
                                                linkedYear,
                                                "accounts",
                                                null,
                                                Map.of(
                                                        "code",
                                                        "4001",
                                                        "name",
                                                        "Capital",
                                                        "normalSide",
                                                        "CREDIT",
                                                        "category",
                                                        "EQUITY"))
                                        .get("id");
        var linked =
                new FinancePostgresTest.Fixture(linkedId, linkedYear, linkedCash, linkedCapital);
        assertEquals(
                "SOURCE_SCOPE_CONFLICT",
                assertThrows(
                                FinanceException.class,
                                () ->
                                        ingest(
                                                "maker",
                                                linked,
                                                body(
                                                        linked,
                                                        source,
                                                        UUID.randomUUID().toString(),
                                                        "v3")))
                        .code);
        UUID next =
                (UUID)
                        FinancePostgresTest.write(
                                        "maker",
                                        f.book(),
                                        null,
                                        "years",
                                        null,
                                        Map.of("year", 2027))
                                .get("id");
        UUID
                yearCash =
                        (UUID)
                                FinancePostgresTest.write(
                                                "maker",
                                                f.book(),
                                                next,
                                                "accounts",
                                                null,
                                                Map.of(
                                                        "code",
                                                        "1001",
                                                        "name",
                                                        "Cash",
                                                        "normalSide",
                                                        "DEBIT",
                                                        "category",
                                                        "ASSET"))
                                        .get("id"),
                yearCapital =
                        (UUID)
                                FinancePostgresTest.write(
                                                "maker",
                                                f.book(),
                                                next,
                                                "accounts",
                                                null,
                                                Map.of(
                                                        "code",
                                                        "4001",
                                                        "name",
                                                        "Capital",
                                                        "normalSide",
                                                        "CREDIT",
                                                        "category",
                                                        "EQUITY"))
                                        .get("id");
        var target = new FinancePostgresTest.Fixture(f.book(), next, yearCash, yearCapital);
        var across = body(target, source, UUID.randomUUID().toString(), "v3");
        ((Map<String, Object>) across.get("event")).put("businessDate", "2027-01-10");
        ((Map<String, Object>) across.get("event")).put("accountingDate", "2027-01-10");
        assertEquals(
                "SOURCE_SCOPE_CONFLICT",
                assertThrows(FinanceException.class, () -> ingest("maker", target, across)).code);
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of(
                        "username",
                        "reviewer",
                        "role",
                        "OWNER",
                        "yearScope",
                        "SELECTED",
                        "yearIds",
                        List.of(next.toString()),
                        "enabled",
                        true));
        assertEquals(
                "SCOPE_DENIED",
                assertThrows(FinanceException.class, () -> ingest("reviewer", target, across))
                        .code);
        assertThrows(
                FinanceException.class,
                () -> sources.list("reviewer", f.book(), f.year(), 20, null));
        assertThrows(
                FinanceException.class,
                () -> outbox.list("reviewer", f.book(), f.year(), 20, null));
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "OWNER", "enabled", true));
        var replay = body(f, source, UUID.randomUUID().toString(), "v2");
        ingest("reviewer", f, replay);
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "OWNER", "enabled", false));
        assertThrows(FinanceException.class, () -> ingest("reviewer", f, replay));
        assertEquals(1, count("plugin_finance_journal", f.book()));
        assertThrows(
                FinanceException.class, () -> sources.list("maker", f.book(), f.year(), 101, null));
        assertThrows(
                FinanceException.class,
                () -> outbox.list("maker", f.book(), f.year(), 20, "malformed!"));
    }

    static void drain() {
        for (int pass = 0; pass < 5; pass++) {
            var rows = FinancePostgresTest.tx.execute(status -> outbox.claim(20));
            if (rows.isEmpty()) return;
            for (var row : rows)
                FinancePostgresTest.tx.executeWithoutResult(
                        status ->
                                outbox.consume(
                                        (UUID) row.get("id"), (UUID) row.get("lease_token")));
        }
        throw new AssertionError("unexpected projection backlog");
    }

    @Test
    void everyDraftStateChangeHasControlledProjectionEvent() {
        var f = FinancePostgresTest.fixture("SMALL");
        UUID journal = (UUID) FinancePostgresTest.draft(f).get("id");
        drain();
        assertEquals(
                "DRAFT",
                db.one(
                                "SELECT status FROM plugin_finance_journal_projection WHERE"
                                        + " journal_id=?",
                                journal)
                        .get("status"));
        FinancePostgresTest.action("maker", f, journal, "submit", 1);
        drain();
        assertEquals(
                "SUBMITTED",
                db.one(
                                "SELECT status FROM plugin_finance_journal_projection WHERE"
                                        + " journal_id=?",
                                journal)
                        .get("status"));
        FinancePostgresTest.action("reviewer", f, journal, "approve", 2);
        drain();
        assertEquals(
                "APPROVED",
                db.one(
                                "SELECT status FROM plugin_finance_journal_projection WHERE"
                                        + " journal_id=?",
                                journal)
                        .get("status"));
        UUID cancel = (UUID) FinancePostgresTest.draft(f).get("id");
        FinancePostgresTest.write(
                "maker",
                f.book(),
                f.year(),
                "cancel",
                cancel,
                Map.of("expectedVersion", 1, "reason", "unused draft"));
        drain();
        assertEquals(
                "CANCELLED",
                db.one(
                                "SELECT status FROM plugin_finance_journal_projection WHERE"
                                        + " journal_id=?",
                                cancel)
                        .get("status"));
        assertEquals(0, count("plugin_finance_posting", f.book()));
    }

    @Test
    void manageWithoutCreateCannotProduceDraftButCommittedProjectionSurvivesOffboarding() {
        var f = FinancePostgresTest.fixture("CAS");
        com.lumira.platform.common.AdministrationPort restricted =
                new com.lumira.platform.common.AdministrationPort() {
                    public Set<String> require(String actor, String permission) {
                        if (permission.equals("platform:finance:create"))
                            throw new org.springframework.web.server.ResponseStatusException(
                                    org.springframework.http.HttpStatus.FORBIDDEN,
                                    "permissionDenied");
                        return Set.of(permission);
                    }

                    public void requireUser(String actor, UUID user, String permission) {}

                    public void requireGlobal(String actor, String permission) {}

                    public boolean hasGlobal(String actor, String permission) {
                        return false;
                    }
                };
        var restrictedFinance =
                new FinanceService(
                        db,
                        restricted,
                        (name, lock) -> FinancePostgresTest.users.get(name),
                        new tools.jackson.databind.ObjectMapper());
        var restrictedSources = new FinanceSources(db, restrictedFinance);
        var denied =
                assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        status ->
                                                restrictedSources.ingest(
                                                        "maker",
                                                        f.book(),
                                                        f.year(),
                                                        body(
                                                                f,
                                                                "manage-not-create",
                                                                UUID.randomUUID().toString(),
                                                                "v1"))));
        assertEquals(403, denied.getStatusCode().value());
        assertEquals(0, count("plugin_finance_source_event", f.book()));
        assertEquals(0, count("plugin_finance_journal", f.book()));
        ingest(
                "maker",
                f,
                body(f, "committed-before-offboarding", UUID.randomUUID().toString(), "v1"));
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "OWNER", "enabled", true));
        FinancePostgresTest.write(
                "reviewer",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "maker", "role", "OWNER", "enabled", false));
        drain();
        assertEquals(1, count("plugin_finance_journal_projection", f.book()));
        assertEquals(1, count("plugin_finance_journal", f.book()));
        assertEquals(0, count("plugin_finance_posting", f.book()));
        assertThrows(
                FinanceException.class, () -> sources.list("maker", f.book(), f.year(), 20, null));
        assertEquals(
                2,
                ((List<?>) outbox.list("reviewer", f.book(), f.year(), 20, null).get("items"))
                        .size());
    }

    @Test
    void projectionReceiptRollsBackWithResultAndPoisonBackoffStopsAtFiveAttempts() {
        drain();
        var f = FinancePostgresTest.fixture("CAS");
        UUID journal = (UUID) FinancePostgresTest.draft(f).get("id");
        var claimed = FinancePostgresTest.tx.execute(status -> outbox.claim(20));
        assertEquals(1, claimed.size());
        UUID event = (UUID) claimed.getFirst().get("id"),
                token = (UUID) claimed.getFirst().get("lease_token");
        assertThrows(
                IllegalStateException.class,
                () ->
                        FinancePostgresTest.tx.executeWithoutResult(
                                status -> {
                                    outbox.consume(event, token);
                                    throw new IllegalStateException(
                                            "injected after projection and receipt");
                                }));
        assertEquals(0, count("plugin_finance_journal_projection", f.book()));
        assertEquals(
                0L,
                db.one(
                                "SELECT count(*) AS n FROM plugin_finance_projection_receipt WHERE"
                                        + " event_id=?",
                                event)
                        .get("n"));
        assertEquals(
                "PROCESSING",
                db.one("SELECT state FROM plugin_finance_outbox WHERE id=?", event).get("state"));
        db.update(
                "UPDATE plugin_finance_outbox SET lease_until=now()-interval '1 second' WHERE id=?",
                event);
        var recovery = FinancePostgresTest.tx.execute(status -> outbox.claim(20));
        UUID recoveredToken = (UUID) recovery.getFirst().get("lease_token");
        FinancePostgresTest.tx.executeWithoutResult(status -> outbox.consume(event, token));
        assertEquals(0, count("plugin_finance_journal_projection", f.book()));
        FinancePostgresTest.tx.executeWithoutResult(
                status -> outbox.consume(event, recoveredToken));
        assertEquals(1, count("plugin_finance_journal_projection", f.book()));
        UUID poison = UUID.randomUUID();
        var b = FinancePostgresTest.service.originalScope("maker", f.book(), f.year(), "manage");
        String payload =
                FinancePostgresTest.service.encode(
                        Map.of("journalId", journal.toString(), "sourceVersion", 999));
        db.update(
                "INSERT INTO"
                    + " plugin_finance_outbox(tenant_id,book_id,year_id,id,event_type,aggregate_id,aggregate_version,payload,payload_digest,created_by)"
                    + " VALUES(?,?,?,?,'JOURNAL_CHANGED',?,999,?::jsonb,?,?)",
                b.get("tenant_id"),
                f.book(),
                f.year(),
                poison,
                journal,
                payload,
                "0".repeat(64),
                FinancePostgresTest.users.get("maker"));
        for (int attempt = 1; attempt <= 5; attempt++) {
            var lease = FinancePostgresTest.tx.execute(status -> outbox.claim(20));
            assertEquals(1, lease.size());
            UUID leaseToken = (UUID) lease.getFirst().get("lease_token");
            assertEquals(
                    "OUTBOX_INTEGRITY_FAILED",
                    assertThrows(
                                    FinanceException.class,
                                    () ->
                                            FinancePostgresTest.tx.executeWithoutResult(
                                                    status -> outbox.consume(poison, leaseToken)))
                            .code);
            FinancePostgresTest.tx.executeWithoutResult(
                    status -> outbox.failed(poison, leaseToken));
            var state =
                    db.one(
                            "SELECT state,attempts,next_attempt_at>now() AS"
                                + " has_backoff,last_error_code FROM plugin_finance_outbox WHERE"
                                + " id=?",
                            poison);
            assertEquals(attempt, ((Number) state.get("attempts")).intValue());
            assertEquals(attempt == 5 ? "FAILED" : "PENDING", state.get("state"));
            assertEquals(true, state.get("has_backoff"));
            assertEquals("LOCAL_PROJECTION_FAILED", state.get("last_error_code"));
            if (attempt < 5)
                db.update(
                        "UPDATE plugin_finance_outbox SET next_attempt_at=now() WHERE id=?",
                        poison);
        }
        assertTrue(FinancePostgresTest.tx.execute(status -> outbox.claim(20)).isEmpty());
        assertEquals(
                0L,
                db.one(
                                "SELECT count(*) AS n FROM plugin_finance_projection_receipt WHERE"
                                        + " event_id=?",
                                poison)
                        .get("n"));
        db.update(
                "UPDATE plugin_finance_year SET status='CLOSED_PENDING_ARCHIVE' WHERE id=?",
                f.year());
        assertEquals(
                "OUTBOX_INTEGRITY_FAILED",
                assertThrows(
                                FinanceException.class,
                                () ->
                                        FinancePostgresTest.tx.execute(
                                                status ->
                                                        outbox.retry(
                                                                "maker",
                                                                f.book(),
                                                                f.year(),
                                                                poison,
                                                                Map.of(
                                                                        "idempotencyKey",
                                                                        UUID.randomUUID()
                                                                                .toString(),
                                                                        "reason",
                                                                        "corrupt evidence must"
                                                                            + " remain failed"))))
                        .code);
        assertEquals(
                "FAILED",
                db.one("SELECT state FROM plugin_finance_outbox WHERE id=?", poison).get("state"));
        db.update(
                "UPDATE plugin_finance_outbox SET"
                    + " state='FAILED',attempts=5,last_error_code='QA_TRANSIENT_IO' WHERE id=?",
                event);
        var retry =
                FinancePostgresTest.tx.execute(
                        status ->
                                outbox.retry(
                                        "maker",
                                        f.book(),
                                        f.year(),
                                        event,
                                        Map.of(
                                                "idempotencyKey",
                                                UUID.randomUUID().toString(),
                                                "reason",
                                                "controlled retry of transient projection"
                                                    + " failure")));
        assertEquals("PENDING", retry.get("state"));
        assertEquals(0, ((Number) retry.get("attempts")).intValue());
        drain();
        assertEquals(
                "DONE",
                db.one("SELECT state FROM plugin_finance_outbox WHERE id=?", event).get("state"));
        assertEquals(1, count("plugin_finance_journal", f.book()));
        assertEquals(0, count("plugin_finance_posting", f.book()));
        // Keep this deliberate corrupt envelope terminal so it cannot contaminate another test's
        // global worker.
        db.update(
                "UPDATE plugin_finance_outbox SET"
                        + " state='FAILED',last_error_code='QA_POISON_RETAINED' WHERE id=?",
                poison);
    }
}
