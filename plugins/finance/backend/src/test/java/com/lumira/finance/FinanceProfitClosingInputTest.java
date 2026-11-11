package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class FinanceProfitClosingInputTest {
    static Map<String, Object> source() {
        return FinanceJournalPersonGate.map(
                "tenantId",
                UUID.randomUUID().toString(),
                "bookId",
                UUID.randomUUID().toString(),
                "yearId",
                UUID.randomUUID().toString(),
                "cutoff",
                "2026-01-31",
                "modeEpoch",
                UUID.randomUUID().toString(),
                "modeGeneration",
                "1",
                "periodId",
                UUID.randomUUID().toString(),
                "periodVersion",
                "1",
                "includedPostingSeq",
                "0",
                "openingVersion",
                "0",
                "baselineVersion",
                "0",
                "sourceCloseVersion",
                "0",
                "accountSemanticDigest",
                "a".repeat(64));
    }

    static Map<String, Object> create() {
        return FinanceJournalPersonGate.map(
                "schemaVersion",
                FinanceProfitClosingInput.SCHEMA,
                "idempotencyKey",
                UUID.randomUUID().toString(),
                "operationId",
                UUID.randomUUID().toString(),
                "producerKind",
                "PROFIT_LOSS",
                "cutoff",
                "2026-01-31",
                "expectedSource",
                source(),
                "expectedSourceDigest",
                "b".repeat(64),
                "expectedGeneratedDigest",
                "c".repeat(64),
                "predecessorPostedRunId",
                null,
                "supersedesCancelledAttemptId",
                null,
                "expectedSupersededVersion",
                null,
                "reversesRunId",
                null,
                "reason",
                "真实来源残余结转意图");
    }

    @Test
    void closedSourceAndCreateRequireExplicitNullableKeysAndRejectClientGeneratedFacts() {
        var create = create();
        assertDoesNotThrow(() -> FinanceProfitClosingInput.normalized("create", create));
        for (String key : create.keySet()) {
            var missing = new LinkedHashMap<>(create);
            missing.remove(key);
            assertThrows(
                    FinanceException.class,
                    () -> FinanceProfitClosingInput.normalized("create", missing),
                    key);
        }
        for (String key : source().keySet()) {
            var missing = source();
            missing.remove(key);
            assertThrows(
                    FinanceException.class, () -> FinanceProfitClosingInput.source(missing), key);
        }
        for (String key : List.of("lines", "preparedBy", "postingSeq", "system")) {
            var injected = create();
            injected.put(key, true);
            assertThrows(
                    FinanceException.class,
                    () -> FinanceProfitClosingInput.normalized("create", injected));
        }
    }

    @Test
    void cutoffKindsCountersAndExplicitRecreationAreNotCoerced() {
        assertDoesNotThrow(() -> FinanceProfitClosingInput.cutoff("2028-02-29"));
        for (Object date :
                Arrays.asList("2026-02-29", "2026-01-30", "2026-01-31T00:00:00Z", 20260131, null))
            assertThrows(FinanceException.class, () -> FinanceProfitClosingInput.cutoff(date));
        var yearEnd = create();
        yearEnd.put("producerKind", "YEAR_END");
        assertThrows(
                FinanceException.class,
                () -> FinanceProfitClosingInput.normalized("create", yearEnd));
        var reverse = create();
        reverse.put("producerKind", "TRANSFER_REVERSAL");
        assertThrows(
                FinanceException.class,
                () -> FinanceProfitClosingInput.normalized("create", reverse));
        reverse.put("reversesRunId", UUID.randomUUID().toString());
        assertDoesNotThrow(() -> FinanceProfitClosingInput.normalized("create", reverse));
        var recreate = create();
        recreate.put("supersedesCancelledAttemptId", UUID.randomUUID().toString());
        assertThrows(
                FinanceException.class,
                () -> FinanceProfitClosingInput.normalized("create", recreate));
        recreate.put("expectedSupersededVersion", "2");
        assertDoesNotThrow(() -> FinanceProfitClosingInput.normalized("create", recreate));
        for (Object counter : Arrays.asList(1, "01", "-1", "9223372036854775808", null))
            assertThrows(
                    FinanceException.class, () -> FinanceProfitClosingInput.counter(counter, true));
    }

    @Test
    void approvePostBodiesRequireExactVersionsAndStableErrorStatuses() {
        var action =
                FinanceJournalPersonGate.map(
                        "schemaVersion",
                        FinanceProfitClosingInput.SCHEMA,
                        "idempotencyKey",
                        "action-1",
                        "expectedVersion",
                        "1",
                        "expectedContentVersion",
                        "1",
                        "contentDigest",
                        "a".repeat(64),
                        "reason",
                        "独立审签");
        var approve = new LinkedHashMap<>(action);
        approve.put("requestedExpiresAt", null);
        assertDoesNotThrow(() -> FinanceProfitClosingInput.normalized("approve", approve));
        approve.put("requestedExpiresAt", "2026-01-31T10:20:30+08:00");
        assertDoesNotThrow(() -> FinanceProfitClosingInput.normalized("approve", approve));
        approve.put("requestedExpiresAt", "2026-01-31");
        assertThrows(
                FinanceException.class,
                () -> FinanceProfitClosingInput.normalized("approve", approve));
        var post = new LinkedHashMap<>(action);
        post.put("approvalId", UUID.randomUUID().toString());
        assertDoesNotThrow(() -> FinanceProfitClosingInput.normalized("post", post));
        post.put("expectedVersion", 1);
        assertThrows(
                FinanceException.class, () -> FinanceProfitClosingInput.normalized("post", post));
        assertEquals(
                409,
                new FinanceException("PROFIT_CLOSING_SOURCE_STALE", "safe")
                        .getStatusCode()
                        .value());
        assertEquals(
                422,
                new FinanceException("PROFIT_CLOSING_SOURCE_UNSUPPORTED", "safe")
                        .getStatusCode()
                        .value());
    }
}
