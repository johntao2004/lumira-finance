package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class FinancePayablesAp01InputTest {
    static String uuid() {
        return UUID.randomUUID().toString();
    }

    static Map<String, Object> document() {
        return FinanceJournalPersonGate.map(
                "documentId",
                uuid(),
                "documentVersion",
                "2",
                "currentReviewId",
                uuid(),
                "basisVersion",
                "1",
                "basisContentDigest",
                "a".repeat(64),
                "sourceOriginalVersion",
                "1",
                "sourceOriginalSha256",
                "b".repeat(64));
    }

    static Map<String, Object> basis() {
        return FinanceJournalPersonGate.map(
                "schemaVersion",
                FinancePayablesAp01Input.SCHEMA,
                "idempotencyKey",
                uuid(),
                "reason",
                "真实组织声明\n不是税务验真",
                "documentExpectation",
                document(),
                "expectedCurrentBasisId",
                null,
                "expectedCurrentBasisVersion",
                null,
                "accountingCapacity",
                "800.00",
                "basisOriginalId",
                uuid(),
                "recognitionEligibilityReason",
                "组织独立复核服务费用可会计确认",
                "organizationReviewedNotTaxVerified",
                true);
    }

    static Map<String, Object> create() {
        var doc = document();
        return FinanceJournalPersonGate.map(
                "schemaVersion",
                FinancePayablesAp01Input.SCHEMA,
                "idempotencyKey",
                uuid(),
                "reason",
                "真实服务应付",
                "operationId",
                uuid(),
                "expectedPredecessorId",
                null,
                "expectedPredecessorVersion",
                null,
                "expectedParent",
                FinanceJournalPersonGate.map(
                        "allocationId",
                        uuid(),
                        "documentId",
                        doc.get("documentId"),
                        "chainId",
                        uuid(),
                        "version",
                        "3",
                        "contentVersion",
                        "1",
                        "contentDigest",
                        "c".repeat(64),
                        "submissionId",
                        uuid(),
                        "usageId",
                        uuid(),
                        "confirmationId",
                        uuid(),
                        "confirmedAmount",
                        "600.00000000",
                        "approvedReversedAmount",
                        "0.00000000",
                        "netConfirmedAmount",
                        "600.00000000",
                        "documentExpectation",
                        doc),
                "expectedSupplier",
                FinanceJournalPersonGate.map(
                        "counterpartyId",
                        uuid(),
                        "governanceHomeBookId",
                        uuid(),
                        "registryVersion",
                        "2",
                        "approvedRevisionId",
                        uuid(),
                        "approvedRevisionDigest",
                        "d".repeat(64)),
                "expectedAccountingBasis",
                FinanceJournalPersonGate.map(
                        "basisId",
                        uuid(),
                        "basisVersion",
                        "2",
                        "contentDigest",
                        "e".repeat(64),
                        "documentExpectation",
                        doc),
                "accountingDate",
                "2026-01-15",
                "dueDate",
                null,
                "expenseAccountId",
                uuid(),
                "payableAccountId",
                uuid(),
                "serviceAcceptanceOriginalId",
                uuid(),
                "recognitionOriginalId",
                uuid(),
                "recognitionReason",
                "服务接受后的会计确认",
                "summary",
                "组织服务费用应付",
                "ordinaryServiceExpenseConfirmed",
                true,
                "supplierMatchesDocumentConfirmed",
                true,
                "serviceAcceptedConfirmed",
                true,
                "noTaxAssetInventoryPayrollConfirmed",
                true,
                "payableAccountUseConfirmed",
                true);
    }

    @Test
    void strictRequiredNullableAndServerOwnedAmount() {
        var good = create();
        assertEquals(good, FinancePayablesAp01Input.normalized("create", good));
        for (String key : good.keySet()) {
            var bad = new LinkedHashMap<>(good);
            bad.remove(key);
            assertThrows(
                    FinanceException.class,
                    () -> FinancePayablesAp01Input.normalized("create", bad),
                    key);
        }
        var forged = create();
        forged.put("amount", "1.00");
        assertThrows(
                FinanceException.class,
                () -> FinancePayablesAp01Input.normalized("create", forged));
    }

    @Test
    void separateCapacityCnyAndExplicitAssertions() {
        assertEquals("800.00000000", FinancePayablesAp01Input.money("800.00"));
        var n = basis();
        assertDoesNotThrow(() -> FinancePayablesAp01Input.normalized("basis-create", n));
        n.put("accountingCapacity", "800.001");
        assertThrows(
                FinanceException.class,
                () -> FinancePayablesAp01Input.normalized("basis-create", n));
        var a = create();
        a.put("payableAccountUseConfirmed", false);
        assertThrows(
                FinanceException.class, () -> FinancePayablesAp01Input.normalized("create", a));
    }

    @Test
    void canonicalCountersDatesAndOriginalBodyRetained() {
        assertEquals("0.00000000", FinancePayablesAp01Projection.representable("0"));
        assertEquals("1.00000001", FinancePayablesAp01Projection.representable("1.00000001"));
        assertNull(FinancePayablesAp01Projection.representable("NaN"));
        assertNull(FinancePayablesAp01Projection.representable("0.000000001"));
        assertNull(FinancePayablesAp01Projection.representable("100000000000000000000"));
        var good = create();
        good.put("summary", " 服务\n费用 ");
        assertEquals(
                good.get("summary"),
                FinancePayablesAp01Input.normalized("create", good).get("summary"));
        good.put("accountingDate", "2026-02-30");
        assertThrows(
                FinanceException.class, () -> FinancePayablesAp01Input.normalized("create", good));
        assertThrows(FinanceException.class, () -> FinancePayablesAp01Input.uuid("bad"));
        assertThrows(FinanceException.class, () -> FinancePayablesAp01Input.counter(1, false));
    }
}
