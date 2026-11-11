package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.util.*;

/** Uses only the explicitly configured finance QA DB and the existing unique-schema harness. */
class FinanceOriginalsPostgresTest {
    static FinanceOriginals originals;

    @BeforeAll
    static void setup() throws Exception {
        FinancePostgresTest.setup();
        originals =
                new FinanceOriginals(
                        new FinanceStore(FinancePostgresTest.jdbc),
                        FinancePostgresTest.service,
                        (name, lock) -> FinancePostgresTest.users.get(name));
    }

    @AfterAll
    static void cleanup() {
        FinancePostgresTest.cleanup();
    }

    static Map<String, Object> upload(
            String who, FinancePostgresTest.Fixture f, String key, byte[] value) {
        return FinancePostgresTest.tx.execute(
                status -> {
                    try {
                        return originals.upload(
                                who,
                                f.book(),
                                f.year(),
                                "电子原件.pdf",
                                key,
                                new ByteArrayInputStream(value),
                                value.length);
                    } catch (java.io.IOException failure) {
                        throw new RuntimeException(failure);
                    }
                });
    }

    @Test
    void exactBytesScopedReadIdempotencyAndImmutableStorage() {
        var f = FinancePostgresTest.fixture("CAS");
        byte[] bytes = {0, 1, 13, 10, (byte) 255};
        String key = "original-" + UUID.randomUUID();
        var first = upload("maker", f, key, bytes);
        UUID id = (UUID) first.get("id");
        assertEquals(FinanceOriginals.digest(bytes), first.get("sha256"));
        assertArrayEquals(bytes, originals.download("reviewer", f.book(), f.year(), id).content());
        assertEquals(id.toString(), upload("maker", f, key, bytes).get("id").toString());
        assertThrows(FinanceException.class, () -> upload("maker", f, key, new byte[] {1, 2}));
        var other = FinancePostgresTest.fixture("SMALL");
        assertThrows(
                FinanceException.class,
                () -> originals.download("maker", other.book(), other.year(), id));
        assertThrows(
                FinanceException.class,
                () -> originals.download("outsider", f.book(), f.year(), id));
        assertThrows(
                Exception.class,
                () ->
                        FinancePostgresTest.jdbc
                                .sql("UPDATE plugin_finance_original SET content=? WHERE id=?")
                                .params(new byte[] {2}, id)
                                .update());
        assertEquals(1, originals.list("maker", f.book(), f.year()).size());
        assertEquals(
                1L,
                FinancePostgresTest.jdbc
                        .sql(
                                "SELECT count(*) FROM plugin_finance_audit WHERE target_id=? AND"
                                        + " action='ORIGINAL_UPLOAD'")
                        .param(id)
                        .query(Long.class)
                        .single());
    }

    @Test
    void revokedMembershipCannotReplayPreviouslyUploadedBytes() {
        var f = FinancePostgresTest.fixture("CAS");
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "MAKER", "enabled", true));
        String key = "revoked-original-" + UUID.randomUUID();
        byte[] bytes = {4, 5, 6};
        upload("reviewer", f, key, bytes);
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "MAKER", "enabled", false));
        assertThrows(FinanceException.class, () -> upload("reviewer", f, key, bytes));
    }

    @Test
    void journalLinkUsesAuthoritativeStoredOriginalHashAndScope() {
        var f = FinancePostgresTest.fixture("CAS");
        byte[] value = {7, 8, 9, 0, (byte) 255};
        var original = upload("maker", f, "link-original-" + UUID.randomUUID(), value);
        UUID originalId = (UUID) original.get("id");
        UUID journal = (UUID) FinancePostgresTest.draft(f).get("id");
        var linked =
                FinancePostgresTest.write(
                        "maker",
                        f.book(),
                        f.year(),
                        "documents",
                        journal,
                        Map.of(
                                "expectedVersion",
                                1,
                                "originalId",
                                originalId.toString(),
                                "source",
                                "电子原件",
                                "originalLocation",
                                "试验原件库"));
        assertEquals(original.get("sha256"), linked.get("sha256"));
        var authority =
                FinancePostgresTest.jdbc
                        .sql(
                                "SELECT d.sha256 AS linked_hash,o.sha256 AS stored_hash,o.content"
                                    + " FROM plugin_finance_document d JOIN plugin_finance_original"
                                    + " o ON o.tenant_id=d.tenant_id AND o.book_id=d.book_id AND"
                                    + " o.year_id=d.year_id AND o.id=d.original_id WHERE"
                                    + " d.journal_id=?")
                        .param(journal)
                        .query(
                                (rs, n) ->
                                        Map.<String, Object>of(
                                                "linked",
                                                rs.getString("linked_hash"),
                                                "stored",
                                                rs.getString("stored_hash"),
                                                "bytes",
                                                rs.getBytes("content")))
                        .single();
        assertEquals(authority.get("linked"), authority.get("stored"));
        assertArrayEquals(value, (byte[]) authority.get("bytes"));
        assertThrows(
                FinanceException.class,
                () ->
                        FinancePostgresTest.write(
                                "maker",
                                f.book(),
                                f.year(),
                                "documents",
                                journal,
                                Map.of(
                                        "expectedVersion",
                                        2,
                                        "originalId",
                                        originalId.toString(),
                                        "sha256",
                                        "0".repeat(64),
                                        "source",
                                        "伪造hash",
                                        "originalLocation",
                                        "不可覆盖")));
        var other = FinancePostgresTest.fixture("SMALL");
        UUID otherOriginal =
                (UUID)
                        upload("maker", other, "other-original-" + UUID.randomUUID(), value)
                                .get("id");
        assertThrows(
                FinanceException.class,
                () ->
                        FinancePostgresTest.write(
                                "maker",
                                f.book(),
                                f.year(),
                                "documents",
                                journal,
                                Map.of(
                                        "expectedVersion",
                                        2,
                                        "originalId",
                                        otherOriginal.toString(),
                                        "source",
                                        "跨账套",
                                        "originalLocation",
                                        "拒绝")));
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "MAKER", "enabled", true));
        UUID reviewerOriginal =
                (UUID)
                        upload("reviewer", f, "reviewer-original-" + UUID.randomUUID(), value)
                                .get("id");
        assertThrows(
                FinanceException.class,
                () ->
                        FinancePostgresTest.write(
                                "maker",
                                f.book(),
                                f.year(),
                                "documents",
                                journal,
                                Map.of(
                                        "expectedVersion",
                                        2,
                                        "originalId",
                                        reviewerOriginal.toString(),
                                        "source",
                                        "他人原件",
                                        "originalLocation",
                                        "拒绝")));
    }

    @Test
    void selectedYearScopeAlsoProtectsOriginalUploadListAndDownload() {
        var f = FinancePostgresTest.fixture("CAS");
        byte[] value = {1, 3, 5, 7};
        UUID original =
                (UUID) upload("maker", f, "annual-original-" + UUID.randomUUID(), value).get("id");
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
                        "MAKER",
                        "yearScope",
                        "SELECTED",
                        "yearIds",
                        List.of(next.toString()),
                        "enabled",
                        true));
        assertThrows(FinanceException.class, () -> originals.list("reviewer", f.book(), f.year()));
        assertThrows(
                FinanceException.class,
                () -> originals.download("reviewer", f.book(), f.year(), original));
        assertThrows(
                FinanceException.class,
                () -> upload("reviewer", f, "annual-forbidden-" + UUID.randomUUID(), value));
        assertTrue(originals.list("reviewer", f.book(), next).isEmpty());
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
                        "READER",
                        "yearScope",
                        "ALL",
                        "yearIds",
                        List.of(),
                        "enabled",
                        true));
        assertArrayEquals(
                value, originals.download("reviewer", f.book(), f.year(), original).content());
    }
}
