package com.lumira.finance.expense;

import com.lumira.finance.expense.ExpenseDomain.*;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ExpenseControllerTest {

    private ExpenseService service;
    private ExpenseController controller;
    private ObjectMapper mapper;

    @BeforeEach
    void setup() {
        service = new ExpenseService(
                new ExpenseJdbcStore(),
                new TravelPolicyEngine(),
                new ExpenseRulesEngine(),
                new ExpenseAccountingAdapter()
        );
        controller = new ExpenseController(service);
        mapper = com.lumira.finance.FinanceStrictJson.MAPPER;
    }

    @Test
    void testRequestDTOJacksonSerialization() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();

        ExpenseController.CreateRequisitionRequest req = new ExpenseController.CreateRequisitionRequest(
                "IDEM-REQ-001", "REQ-2026-X1", applicantId, UUID.randomUUID(), null,
                "Cloud infrastructure migration", "50000.00", LocalDate.of(2026, 12, 31)
        );

        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"totalBudget\":\"50000.00\""));
        assertTrue(json.contains("\"requisitionNo\":\"REQ-2026-X1\""));

        ExpenseController.CreateRequisitionRequest parsed = mapper.readValue(json, ExpenseController.CreateRequisitionRequest.class);
        assertEquals(req.requisitionNo(), parsed.requisitionNo());
        assertEquals("50000.00", parsed.totalBudget());
    }

    @Test
    void testClaimRequestSerializationAndLineValidation() throws Exception {
        UUID deptId = UUID.randomUUID();
        ExpenseController.ClaimLineRequest line = new ExpenseController.ClaimLineRequest(
                1, ExpenseCategory.MEAL, deptId, null,
                UUID.randomUUID(), "INV-CODE", "100293", "e".repeat(64),
                "500.00", "50.00", "Alcoholic beverage excluded", "0.06", null, false
        );

        ExpenseController.CreateClaimRequest claimReq = new ExpenseController.CreateClaimRequest(
                "IDEM-CLM-001", "CLM-2026-X1", UUID.randomUUID(), null,
                "Team quarterly lunch", LocalDate.of(2026, 7, 1), UUID.randomUUID(),
                List.of(line), Map.of()
        );

        String json = mapper.writeValueAsString(claimReq);
        assertTrue(json.contains("\"submittedAmount\":\"500.00\""));
        assertTrue(json.contains("\"disallowedAmount\":\"50.00\""));

        ExpenseController.CreateClaimRequest parsed = mapper.readValue(json, ExpenseController.CreateClaimRequest.class);
        assertEquals(1, parsed.lines().size());
        assertEquals("Alcoholic beverage excluded", parsed.lines().get(0).disallowanceReason());
    }

    @Test
    void testReverseClaimRequestSerialization() throws Exception {
        UUID actorId = UUID.randomUUID();
        ExpenseController.ReverseClaimRequest req = new ExpenseController.ReverseClaimRequest(
                "IDEM-REV-01", actorId, "Audit team disallowance and policy reversal"
        );

        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"idempotencyKey\":\"IDEM-REV-01\""));
        assertTrue(json.contains("\"reason\":\"Audit team disallowance and policy reversal\""));

        ExpenseController.ReverseClaimRequest parsed = mapper.readValue(json, ExpenseController.ReverseClaimRequest.class);
        assertEquals(req.idempotencyKey(), parsed.idempotencyKey());
        assertEquals(actorId, parsed.actorId());
        assertEquals("Audit team disallowance and policy reversal", parsed.reason());
    }

    @Test
    void testDisbursePaymentRequestSerialization() throws Exception {
        UUID cashierId = UUID.randomUUID();
        ExpenseController.DisbursePaymentRequest req = new ExpenseController.DisbursePaymentRequest(
                "IDEM-PAY-01", "PAY-2026-001", "1500.00", LocalDate.of(2026, 8, 15),
                "BANK_TRANSFER", "BANK-TX-554433", cashierId
        );

        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"amount\":\"1500.00\""));
        assertTrue(json.contains("\"paymentNo\":\"PAY-2026-001\""));

        ExpenseController.DisbursePaymentRequest parsed = mapper.readValue(json, ExpenseController.DisbursePaymentRequest.class);
        assertEquals("1500.00", parsed.amount());
        assertEquals(cashierId, parsed.cashierId());
    }

    @Test
    void testActionRequestsSerialization() throws Exception {
        UUID actorId = UUID.randomUUID();
        ExpenseController.ClaimActionRequest claimAction = new ExpenseController.ClaimActionRequest(
                "IDEM-ACT-01", "reverse", actorId
        );
        String claimJson = mapper.writeValueAsString(claimAction);
        assertTrue(claimJson.contains("\"action\":\"reverse\""));

        ExpenseController.AdvanceActionRequest advAction = new ExpenseController.AdvanceActionRequest(
                "IDEM-ADV-01", "cancel", actorId, null
        );
        String advJson = mapper.writeValueAsString(advAction);
        assertTrue(advJson.contains("\"action\":\"cancel\""));
    }
}
