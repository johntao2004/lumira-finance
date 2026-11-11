package com.lumira.finance.subledgerext;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

import com.lumira.finance.subledgerext.SubledgerExtRules.*;

class SubledgerExtInputTest {

    @Test
    void testNormalizePoCreateValid() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("idempotencyKey", "key-001");
        raw.put("poNumber", "PO-2026-0001");
        raw.put("supplierId", UUID.randomUUID().toString());
        raw.put("supplierRevisionId", UUID.randomUUID().toString());
        raw.put("orderDate", "2026-11-01");
        raw.put("terms", "Net 30 days");
        raw.put("originalId", null);
        raw.put("lines", List.of(
                Map.of(
                        "itemCode", "ITEM-01",
                        "itemName", "Electronic Sensors",
                        "quantity", "100.00",
                        "unitPrice", "50.00",
                        "taxRate", "0.13"
                )
        ));

        Map<String, Object> norm = SubledgerExtInput.normalizePoCreate(raw);
        assertNotNull(norm);
        assertEquals("PO-2026-0001", norm.get("poNumber"));
        assertEquals(new BigDecimal("5000.00000000"), norm.get("totalNetAmount"));
        assertEquals(new BigDecimal("650.00000000"), norm.get("totalTaxAmount"));
        assertEquals(new BigDecimal("5650.00000000"), norm.get("totalGrossAmount"));
    }

    @Test
    void testNormalizePoCreateRejectsExtraField() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("idempotencyKey", "key-001");
        raw.put("poNumber", "PO-2026-0001");
        raw.put("supplierId", UUID.randomUUID().toString());
        raw.put("supplierRevisionId", UUID.randomUUID().toString());
        raw.put("orderDate", "2026-11-01");
        raw.put("terms", "Net 30 days");
        raw.put("originalId", null);
        raw.put("lines", List.of());
        raw.put("unauthorizedField", "attack_payload"); // extra field

        SubledgerException ex = assertThrows(SubledgerException.class, () -> SubledgerExtInput.normalizePoCreate(raw));
        assertEquals("INVALID_INPUT", ex.code);
    }

    @Test
    void testNormalizeProvisionalSettlementChecksGrossConsistency() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("idempotencyKey", "key-002");
        raw.put("provisionalId", UUID.randomUUID().toString());
        raw.put("invoiceNumber", "INV-2026-999");
        raw.put("invoiceDate", "2026-11-15");
        raw.put("actualNetAmount", "100.00");
        raw.put("actualTaxAmount", "13.00");
        raw.put("actualGrossAmount", "115.00"); // 100 + 13 != 115!

        SubledgerException ex = assertThrows(SubledgerException.class,
                () -> SubledgerExtInput.normalizeProvisionalSettlementCreate(raw));
        assertEquals("INVALID_INPUT", ex.code);
    }

    @Test
    void testNormalizeVariableConsiderationRejectsExcessiveCap() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("idempotencyKey", "key-003");
        raw.put("contractId", UUID.randomUUID().toString());
        raw.put("variableKind", "BONUS");
        raw.put("estimatedAmount", "10000.00");
        raw.put("confidenceScore", "0.7500");
        raw.put("constrainedCapAmount", "8000.00"); // 8000 > 10000 * 0.75 = 7500!
        raw.put("capRationale", "Aggressive estimate");

        SubledgerException ex = assertThrows(SubledgerException.class,
                () -> SubledgerExtInput.normalizeVariableConsiderationCreate(raw));
        assertEquals("VARIABLE_CAP_EXCEEDS_CONSTRAINED_LIMIT", ex.code);
    }

    @Test
    void testNormalizeAllowanceAdjustmentRejectsGrossMismatch() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("idempotencyKey", "key-004");
        raw.put("customerId", UUID.randomUUID().toString());
        raw.put("contractId", null);
        raw.put("receivableId", null);
        raw.put("adjustmentType", "SALES_ALLOWANCE");
        raw.put("creditNoteNumber", "CN-2026-01");
        raw.put("adjustmentDate", "2026-11-20");
        raw.put("originalReceivableAmount", "1000.00");
        raw.put("netAdjustmentAmount", "200.00");
        raw.put("taxAdjustmentAmount", "26.00");
        raw.put("grossAdjustmentAmount", "250.00"); // 200 + 26 != 250!
        raw.put("vatRedInvoiceInfo", "RED-INFO-12345");
        raw.put("reason", "Defective components");

        SubledgerException ex = assertThrows(SubledgerException.class,
                () -> SubledgerExtInput.normalizeAllowanceAdjustmentCreate(raw));
        assertEquals("INVALID_INPUT", ex.code);
    }

    @Test
    void testNormalizeProvisionalSettlementSupportsInvoicedQuantity() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("idempotencyKey", "key-005");
        raw.put("provisionalId", UUID.randomUUID().toString());
        raw.put("invoiceNumber", "INV-2026-QTY-001");
        raw.put("invoiceDate", "2026-11-20");
        raw.put("actualNetAmount", "7790.00");
        raw.put("actualTaxAmount", "1012.70");
        raw.put("actualGrossAmount", "8802.70");
        raw.put("invoicedQuantity", "95.00");

        Map<String, Object> norm = SubledgerExtInput.normalizeProvisionalSettlementCreate(raw);
        assertNotNull(norm);
        assertEquals(new BigDecimal("95.00000000"), norm.get("invoicedQuantity"));
        assertEquals(new BigDecimal("7790.00000000"), norm.get("actualNetAmount"));

        // When invoicedQuantity is not provided, it normalizes to null
        raw.remove("invoicedQuantity");
        Map<String, Object> normDefault = SubledgerExtInput.normalizeProvisionalSettlementCreate(raw);
        assertNull(normDefault.get("invoicedQuantity"));

        // When an unexpected property is passed, it must be rejected
        raw.put("illegalExtraField", "malicious_input");
        SubledgerException ex = assertThrows(SubledgerException.class,
                () -> SubledgerExtInput.normalizeProvisionalSettlementCreate(raw));
        assertEquals("INVALID_INPUT", ex.code);
    }

    @Test
    void testNormalizeVariableConsiderationReviseValidAndInvalid() {
        Map<String, Object> valid = new LinkedHashMap<>();
        valid.put("idempotencyKey", "key-rev-001");
        valid.put("revisedCapAmount", "15000.00");
        valid.put("revisionReason", "Mid-term performance metrics reached");

        Map<String, Object> norm = SubledgerExtInput.normalizeVariableConsiderationRevise(valid);
        assertNotNull(norm);
        assertEquals(new BigDecimal("15000.00000000"), norm.get("revisedCapAmount"));

        // Extra key rejected
        valid.put("extraKey", "unexpected");
        SubledgerException ex = assertThrows(SubledgerException.class,
                () -> SubledgerExtInput.normalizeVariableConsiderationRevise(valid));
        assertEquals("INVALID_INPUT", ex.code);
    }
}
