package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

class FinanceAuditPostgresTest {
    static FinanceAudit audit;

    @BeforeAll
    static void setup() throws Exception {
        FinancePostgresTest.setup();
        audit =
                new FinanceAudit(
                        new FinanceStore(FinancePostgresTest.jdbc), FinancePostgresTest.service);
    }

    @AfterAll
    static void cleanup() {
        FinancePostgresTest.cleanup();
    }

    static UUID seed(FinancePostgresTest.Fixture f, String hash, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        String json =
                FinancePostgresTest.service.encode(FinancePostgresTest.service.canonical(payload));
        FinancePostgresTest.jdbc
                .sql(
                        "INSERT INTO"
                            + " plugin_finance_audit(id,tenant_id,book_id,year_id,actor_id,action,target_id,payload_hash,payload,created_at)"
                            + " VALUES(?,?,?,?,?,'QA_AUDIT',?,?,?::jsonb,?)")
                .params(
                        id,
                        FinancePostgresTest.service
                                .originalScope("maker", f.book(), f.year(), "manage")
                                .get("tenant_id"),
                        f.book(),
                        f.year(),
                        FinancePostgresTest.users.get("maker"),
                        UUID.randomUUID(),
                        hash == null ? FinanceService.hash(json) : hash,
                        json,
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"))
                .update();
        return id;
    }

    @Test
    void keysetPagesHaveNoDuplicatesOrMissingRowsAndVerifyEvidence() {
        var f = FinancePostgresTest.fixture("CAS");
        for (int n = 0; n < 11; n++) seed(f, null, Map.of("version", n, "amount", "123.450000"));
        var expected =
                FinancePostgresTest.jdbc
                        .sql(
                                "SELECT id FROM plugin_finance_audit WHERE book_id=? AND year_id=?"
                                        + " ORDER BY created_at DESC,id DESC")
                        .params(f.book(), f.year())
                        .query(UUID.class)
                        .list();
        List<UUID> found = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var page = audit.list("maker", f.book(), f.year(), 3, cursor);
            var repeat = audit.list("maker", f.book(), f.year(), 3, cursor);
            assertEquals(page, repeat);
            var items = (List<Map<String, Object>>) page.get("items");
            for (var item : items) {
                found.add((UUID) item.get("id"));
                assertEquals(
                        item.get("payloadHash"),
                        FinanceService.hash(
                                FinancePostgresTest.service.encode(
                                        FinancePostgresTest.service.canonical(
                                                item.get("payload")))));
            }
            cursor = (String) page.get("nextCursor");
            pages++;
            assertTrue(pages < 20);
        } while (cursor != null);
        assertEquals(expected, found);
        assertEquals(found.size(), new HashSet<>(found).size());
        assertTrue(pages > 1);
        assertEquals(
                1L,
                FinancePostgresTest.jdbc
                        .sql(
                                "SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema()"
                                        + " AND indexname='finance_audit_scope_cursor_idx'")
                        .query(Long.class)
                        .single());
    }

    @Test
    void scopeAndStrictCursorValidationRejectUnauthorizedOrForgedRequests() {
        var f = FinancePostgresTest.fixture("SMALL");
        seed(f, null, Map.of("version", 1));
        var first = audit.list("maker", f.book(), f.year(), 1, null);
        String cursor = (String) first.get("nextCursor");
        assertNotNull(cursor);
        for (String who : List.of("reviewer", "outsider"))
            assertEquals(
                    "SCOPE_DENIED",
                    assertThrows(
                                    FinanceException.class,
                                    () -> audit.list(who, f.book(), f.year(), 20, null))
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
        assertThrows(
                FinanceException.class, () -> audit.list("reviewer", f.book(), f.year(), 20, null));
        assertThrows(FinanceException.class, () -> audit.list("maker", f.book(), next, 20, cursor));
        var other = FinancePostgresTest.fixture("CAS");
        assertThrows(
                FinanceException.class,
                () -> audit.list("maker", other.book(), other.year(), 20, cursor));
        for (int limit : List.of(-1, 0, 101))
            assertEquals(
                    "INVALID_INPUT",
                    assertThrows(
                                    FinanceException.class,
                                    () -> audit.list("maker", f.book(), f.year(), limit, null))
                            .code);
        for (String malformed : List.of("", "=", "not-json", "a".repeat(1025)))
            assertEquals(
                    "INVALID_INPUT",
                    assertThrows(
                                    FinanceException.class,
                                    () -> audit.list("maker", f.book(), f.year(), 20, malformed))
                            .code);
        var value =
                FinancePostgresTest.service.decode(
                        new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        for (var mutation :
                List.of(
                        Map.<String, Object>of("v", "1"),
                        Map.<String, Object>of("createdAt", 123),
                        Map.<String, Object>of("extra", "field"),
                        Map.<String, Object>of("id", UUID.randomUUID().toString()))) {
            var changed = new LinkedHashMap<>(value);
            changed.putAll(mutation);
            String forged =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    FinancePostgresTest.service
                                            .encode(changed)
                                            .getBytes(StandardCharsets.UTF_8));
            assertEquals(
                    "INVALID_INPUT",
                    assertThrows(
                                    FinanceException.class,
                                    () -> audit.list("maker", f.book(), f.year(), 20, forged))
                            .code);
        }
    }

    @Test
    void corruptedEvidenceFailsClosedWithoutChangingStoredRows() {
        var f = FinancePostgresTest.fixture("CAS");
        UUID id = seed(f, "0".repeat(64), Map.of("version", 1));
        assertEquals(
                "AUDIT_INTEGRITY_FAILED",
                assertThrows(
                                FinanceException.class,
                                () -> audit.list("maker", f.book(), f.year(), 100, null))
                        .code);
        assertEquals(
                "0".repeat(64),
                FinancePostgresTest.jdbc
                        .sql("SELECT payload_hash FROM plugin_finance_audit WHERE id=?")
                        .param(id)
                        .query(String.class)
                        .single());
    }
}
