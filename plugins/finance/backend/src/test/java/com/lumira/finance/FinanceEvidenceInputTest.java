package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class FinanceEvidenceInputTest {
    static Map<String, Object> fields() {
        return new LinkedHashMap<>(
                Map.ofEntries(
                        Map.entry("documentType", "ORDINARY_EXPENSE_INVOICE"),
                        Map.entry("issuerKey", "ISSUER-A"),
                        Map.entry("externalIdentityKey", "INV-001"),
                        Map.entry("faceGross", "1000.00"),
                        Map.entry("expenseCapacity", "900.00"),
                        Map.entry("basisOriginalId", UUID.randomUUID().toString()),
                        Map.entry("reason", "组织复核费用依据"),
                        Map.entry("singleFileSingleDocumentConfirmed", true),
                        Map.entry("allKnownDocumentIdentifiersDeclared", true),
                        Map.entry("sameDocumentConfirmed", true),
                        Map.entry("sourceType", "PAPER_SCAN"),
                        Map.entry("originalLocation", "档案柜A/001")));
    }

    @Test
    void explicitCompleteFieldsAndPaperLocationAreRequired() {
        var valid = fields();
        assertEquals(valid, FinanceEvidenceInput.review(valid));
        for (String key : valid.keySet()) {
            var missing = new LinkedHashMap<>(valid);
            missing.remove(key);
            assertThrows(FinanceException.class, () -> FinanceEvidenceInput.review(missing), key);
        }
        for (String key :
                List.of(
                        "singleFileSingleDocumentConfirmed",
                        "allKnownDocumentIdentifiersDeclared",
                        "sameDocumentConfirmed")) {
            var missingAssertion = new LinkedHashMap<>(valid);
            missingAssertion.put(key, false);
            assertThrows(
                    FinanceException.class, () -> FinanceEvidenceInput.review(missingAssertion));
        }
        valid.put("originalLocation", null);
        assertThrows(FinanceException.class, () -> FinanceEvidenceInput.review(valid));
        valid.put("sourceType", "ELECTRONIC_ORIGINAL");
        assertDoesNotThrow(() -> FinanceEvidenceInput.review(valid));
    }

    @Test
    void moneyIsLiteralTwoDecimalsWithoutRoundOrNumericCoercion() {
        assertEquals("0.00000000", FinanceEvidenceInput.money("0", false).toPlainString());
        for (Object invalid : Arrays.asList(1, "1.000", "1.005", "01.00", "-1", "1e2", null))
            assertThrows(FinanceException.class, () -> FinanceEvidenceInput.money(invalid, true));
        assertEquals(
                "AMOUNT_OVERFLOW",
                assertThrows(
                                FinanceException.class,
                                () -> FinanceEvidenceInput.money("100000000000000000000.00", true))
                        .code);
        assertThrows(FinanceException.class, () -> FinanceEvidenceInput.money("0", true));
        var excessive = fields();
        excessive.put("expenseCapacity", "1000.01");
        assertThrows(FinanceException.class, () -> FinanceEvidenceInput.review(excessive));
    }

    @Test
    void stableIdentityAndVersionsCannotBeCoercedOrSilentlyTrimmed() {
        for (Object invalid : Arrays.asList(null, " a", "a ", "a\u0000b", true, 2))
            assertThrows(FinanceException.class, () -> FinanceEvidenceInput.stable(invalid, 128));
        for (Object invalid : Arrays.asList(1, "01", "0", "-1", "9223372036854775808"))
            assertThrows(FinanceException.class, () -> FinanceEvidenceInput.version(invalid));
        assertEquals(Long.MAX_VALUE, FinanceEvidenceInput.version("9223372036854775807"));
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", FinanceEvidenceInput.SCHEMA);
        body.put("idempotencyKey", "command-1");
        body.put("originalId", UUID.randomUUID().toString());
        body.put("fields", fields());
        assertDoesNotThrow(() -> FinanceEvidenceInput.normalize("register", body));
        body.put("serverVerified", true);
        assertThrows(
                FinanceException.class, () -> FinanceEvidenceInput.normalize("register", body));
    }

    @Test
    void businessIdentityUsesNfcSpaceOnlyTrimAndAsciiCaseGoldens() {
        assertEquals("AB-C", FinanceEvidenceInput.businessId(" ab-c ", 128));
        assertEquals("éA", FinanceEvidenceInput.businessId(" e\u0301a ", 128));
        assertEquals("éA", FinanceEvidenceInput.businessId("éa", 128));
        assertEquals("ÉA", FinanceEvidenceInput.businessId("Éa", 128));
        assertEquals("\u2003A\u2003", FinanceEvidenceInput.businessId("\u2003a\u2003", 128));
        assertThrows(FinanceException.class, () -> FinanceEvidenceInput.businessId("a\tb", 128));
        assertThrows(FinanceException.class, () -> FinanceEvidenceInput.stable(" request ", 128));
    }
}
