package com.lumira.finance;

import static com.lumira.finance.FinanceReceivablesRules.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

class FinanceReceivablesInputTest {
    static String uuid() {
        return UUID.randomUUID().toString();
    }

    static Map<String, Object> base(String kind, String amount) {
        var m = new LinkedHashMap<String, Object>();
        m.put("idempotencyKey", uuid());
        m.put("operationId", uuid());
        m.put("kind", kind);
        m.put("customerId", uuid());
        m.put("customerRevisionId", uuid());
        m.put("originalId", uuid());
        m.put("reason", "真实业务与会计依据");
        m.put("businessKey", "AR-" + kind + "-001");
        m.put("date", "2026-01-15");
        m.put("amount", amount);
        return m;
    }

    @Test
    void closedCommandSchemaRejectsUnknownMissingOrServerOwnedFields() {
        var contract = base("CONTRACT", "1000.00");
        contract.put("terms", "分两阶段交付并独立验收");
        var normalized = FinanceReceivablesInput.normalize("create", contract);
        assertEquals("1000.00", normalized.get("amount"));
        assertEquals("CONTRACT", normalized.get("kind"));

        for (String key : contract.keySet()) {
            var missing = new LinkedHashMap<>(contract);
            missing.remove(key);
            assertEquals(
                    "INVALID_INPUT",
                    assertThrows(
                                    Rejected.class,
                                    () -> FinanceReceivablesInput.normalize("create", missing))
                            .code);
        }

        for (String injected :
                List.of("tenantId", "taxAmount", "taxExempt", "journalId", "approvedBy")) {
            var forged = new LinkedHashMap<>(contract);
            forged.put(injected, "forged");
            assertEquals(
                    "INVALID_INPUT",
                    assertThrows(
                                    Rejected.class,
                                    () -> FinanceReceivablesInput.normalize("create", forged))
                            .code);
        }
    }

    @Test
    void performanceAndRecognitionValidateDatesAndClosedExtraFields() {
        var perf = base("PERFORMANCE", "600");
        perf.put("contractId", uuid());
        perf.put("performanceRule", "里程碑一验收完成");
        perf.put("acceptedOn", "2026-01-15");
        assertEquals("600.00", FinanceReceivablesInput.normalize("create", perf).get("amount"));

        var futureAcceptance = new LinkedHashMap<>(perf);
        futureAcceptance.put("acceptedOn", "2026-01-16");
        assertEquals(
                "AR_PERFORMANCE_NOT_OCCURRED",
                assertThrows(
                                Rejected.class,
                                () -> FinanceReceivablesInput.normalize("create", futureAcceptance))
                        .code);

        var rec = base("RECOGNITION", "600.00");
        rec.put("performanceId", uuid());
        rec.put("decisionId", uuid());
        rec.put("dueDate", "2026-02-15");
        assertDoesNotThrow(() -> FinanceReceivablesInput.normalize("create", rec));

        var invalidDue = new LinkedHashMap<>(rec);
        invalidDue.put("dueDate", "2026-01-14");
        assertEquals(
                "INVALID_INPUT",
                assertThrows(
                                Rejected.class,
                                () -> FinanceReceivablesInput.normalize("create", invalidDue))
                        .code);
    }

    @Test
    void allocationEnforcesBoundsUniqueItemsAndExactSum() {
        String r1 = "00000000-0000-4000-8000-000000000002";
        String r2 = "00000000-0000-4000-8000-000000000001";
        var alloc = base("ALLOCATION", "500.50");
        alloc.put("receiptId", uuid());
        alloc.put("allocationDecisionId", uuid());
        alloc.put(
                "items",
                List.of(
                        Map.of("recognitionId", r1, "amount", "200.5"),
                        Map.of("recognitionId", r2, "amount", "300")));
        var out = FinanceReceivablesInput.normalize("create", alloc);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) out.get("items");
        assertEquals(List.of(r2, r1), items.stream().map(i -> i.get("recognitionId")).toList());
        assertEquals(
                List.of("300.00", "200.50"), items.stream().map(i -> i.get("amount")).toList());

        var duplicate = new LinkedHashMap<>(alloc);
        duplicate.put(
                "items",
                List.of(
                        Map.of("recognitionId", r1, "amount", "250.25"),
                        Map.of("recognitionId", r1, "amount", "250.25")));
        assertEquals(
                "AR_DUPLICATE_ITEM",
                assertThrows(
                                Rejected.class,
                                () -> FinanceReceivablesInput.normalize("create", duplicate))
                        .code);

        var mismatch = new LinkedHashMap<>(alloc);
        mismatch.put("items", List.of(Map.of("recognitionId", r1, "amount", "500.00")));
        assertEquals(
                "AR_ALLOCATION_TOTAL_MISMATCH",
                assertThrows(
                                Rejected.class,
                                () -> FinanceReceivablesInput.normalize("create", mismatch))
                        .code);
    }

    @Test
    void cnyScaleStateTransitionsAgingRemainingCapacityAndPersonIndependence() {
        assertEquals(new BigDecimal("12.30000000"), money("12.3", false));
        assertEquals(new BigDecimal("0.00000000"), money("0", true));
        for (String bad : List.of("1.234", "01.00", "-1.00", "1e2", "100000000000000000000.00")) {
            assertThrows(Rejected.class, () -> money(bad, true));
        }
        assertEquals("INVALID_INPUT", assertThrows(Rejected.class, () -> money("0.00", false)).code);

        assertEquals(State.CONFIRMED, transition(Kind.CONTRACT, State.SUBMITTED, "approve"));
        assertEquals(State.CONFIRMED, transition(Kind.PERFORMANCE, State.SUBMITTED, "approve"));
        assertEquals(State.APPROVED, transition(Kind.RECOGNITION, State.SUBMITTED, "approve"));
        assertEquals(State.POSTED, transition(Kind.RECOGNITION, State.APPROVED, "post"));
        assertThrows(Rejected.class, () -> transition(Kind.CONTRACT, State.CONFIRMED, "post"));
        assertThrows(Rejected.class, () -> transition(Kind.RECOGNITION, State.POSTED, "cancel"));

        LocalDate due = LocalDate.of(2026, 1, 10);
        assertEquals("SETTLED", aging(due, LocalDate.of(2026, 9, 1), BigDecimal.ZERO));
        assertEquals("NOT_DUE", aging(due, LocalDate.of(2026, 1, 10), BigDecimal.ONE));
        assertEquals("DAYS_1_30", aging(due, LocalDate.of(2026, 2, 9), BigDecimal.ONE));
        assertEquals("DAYS_31_60", aging(due, LocalDate.of(2026, 2, 10), BigDecimal.ONE));
        assertEquals("DAYS_181_PLUS", aging(due, LocalDate.of(2026, 8, 1), BigDecimal.ONE));

        assertEquals(
                new BigDecimal("40.00000000"),
                remaining(
                        new BigDecimal("100.00000000"),
                        List.of(new BigDecimal("35.00"), new BigDecimal("25.00"))));
        assertEquals(
                "AR_CAPACITY_EXCEEDED",
                assertThrows(
                                Rejected.class,
                                () ->
                                        remaining(
                                                new BigDecimal("50.00"),
                                                List.of(new BigDecimal("50.01"))))
                        .code);

        UUID samePerson = UUID.randomUUID();
        assertEquals(
                "SELF_APPROVAL_FORBIDDEN",
                assertThrows(Rejected.class, () -> independent(samePerson, samePerson)).code);

        var mapper = new tools.jackson.databind.ObjectMapper();
        var tax =
                new FinanceReceivablesDependencies.TaxFact(
                        UUID.randomUUID(),
                        1L,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        FinanceReceivablesDependencies.TaxTreatment.TAXABLE,
                        new BigDecimal("60.00"),
                        "VAT-13",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "a".repeat(64));
        var plan =
                new FinanceReceivablesDependencies.Plan(
                        UUID.randomUUID(),
                        1L,
                        UUID.randomUUID(),
                        java.time.Instant.parse("2026-12-31T00:00:00Z"),
                        "b".repeat(64),
                        "AR-POLICY-V1",
                        "AR_REVENUE",
                        LocalDate.of(2026, 1, 15),
                        new BigDecimal("1060.00"),
                        new BigDecimal("1000.00"),
                        tax,
                        List.of(
                                new FinanceReceivablesDependencies.Line(
                                        UUID.randomUUID(),
                                        1L,
                                        FinanceReceivablesDependencies.AccountRole.RECEIVABLE,
                                        new BigDecimal("1060.00"),
                                        BigDecimal.ZERO.setScale(2)),
                                new FinanceReceivablesDependencies.Line(
                                        UUID.randomUUID(),
                                        1L,
                                        FinanceReceivablesDependencies.AccountRole.REVENUE,
                                        BigDecimal.ZERO.setScale(2),
                                        new BigDecimal("1000.00")),
                                new FinanceReceivablesDependencies.Line(
                                        UUID.randomUUID(),
                                        1L,
                                        FinanceReceivablesDependencies.AccountRole.OUTPUT_TAX,
                                        BigDecimal.ZERO.setScale(2),
                                        new BigDecimal("60.00"))),
                        Map.of("recognitionClass", "UNCONDITIONAL_RECEIVABLE"));
        var decoded = mapper.readValue(mapper.writeValueAsString(plan), FinanceReceivablesDependencies.Plan.class);
        assertEquals(plan, decoded);
    }
}
