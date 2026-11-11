package com.lumira.finance.treasury;

import com.lumira.finance.FinanceGlobalExceptionHandler;
import com.lumira.finance.treasury.TreasuryModel.*;
import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/**
 * REST controller for Treasury operations (MD04, TR01, TR02, TR03, TR04).
 * Enforces BigDecimal.toPlainString() formatting and strict Jackson 3 conventions.
 */
@RestController
@RequestMapping("/api/finance/v1/books/{bookId}/treasury")
@ModuleEndpoint("finance")
public final class TreasuryController {
    private final TreasuryService service;
    private final DataSource dataSource;

    public TreasuryController(TreasuryService service) {
        this(service, null);
    }

    @Autowired
    public TreasuryController(TreasuryService service, @Autowired(required = false) DataSource dataSource) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.dataSource = dataSource;
    }

    private Scope resolveScope(UUID bookId, String tenantHeader) {
        if (tenantHeader != null && !tenantHeader.isBlank()) {
            return new Scope(UUID.fromString(tenantHeader), bookId);
        }
        if (dataSource != null && bookId != null) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT tenant_id FROM plugin_finance_book WHERE id = ?")) {
                ps.setObject(1, bookId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Scope((UUID) rs.getObject("tenant_id"), bookId);
                    }
                }
            } catch (SQLException ignored) {
            }
        }
        throw new IllegalArgumentException("TENANT_REQUIRED: Tenant header X-Tenant-Id is required and could not be resolved");
    }

    private Actor resolveActor(String actorHeader, String personHeader) {
        if (actorHeader != null && !actorHeader.isBlank() && personHeader != null && !personHeader.isBlank()) {
            return new Actor(UUID.fromString(actorHeader), UUID.fromString(personHeader));
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
            String username = auth.getName();
            UUID accountId = null;
            UUID personId = null;
            if (actorHeader != null && !actorHeader.isBlank()) {
                try { accountId = UUID.fromString(actorHeader.trim()); } catch (Exception ignored) {}
            }
            if (personHeader != null && !personHeader.isBlank()) {
                try { personId = UUID.fromString(personHeader.trim()); } catch (Exception ignored) {}
            }
            if (dataSource != null) {
                try (Connection conn = dataSource.getConnection()) {
                    if (accountId == null) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT id FROM platform_user WHERE username = ? AND enabled = true AND deleted_at IS NULL")) {
                            ps.setString(1, username);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    accountId = (UUID) rs.getObject("id");
                                }
                            }
                        }
                    }
                    if (accountId != null && personId == null) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT person_id FROM plugin_finance_person_binding WHERE account_id = ? AND status = 'ACTIVE' LIMIT 1")) {
                            ps.setObject(1, accountId);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    personId = (UUID) rs.getObject("person_id");
                                }
                            }
                        }
                    }
                } catch (SQLException ignored) {}
            }
            if (accountId == null) {
                accountId = UUID.nameUUIDFromBytes(("lumira:account:" + username).getBytes(StandardCharsets.UTF_8));
            }
            if (personId == null) {
                personId = accountId;
            }
            return new Actor(accountId, personId);
        }
        throw new IllegalArgumentException("ACTOR_REQUIRED: X-Actor-Account-Id and X-Actor-Person-Id headers are required");
    }

    private static String dec(BigDecimal val) {
        return val == null ? null : val.toPlainString();
    }

    // ==========================================
    // MD04: Accounts & Policies
    // ==========================================

    @PostMapping("/accounts")
    public ResponseEntity<Map<String, Object>> openAccount(
            @PathVariable UUID bookId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader,
            @RequestBody CreateAccountInput body) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            BankAccount acct = service.openAccount(scope, actor, body);
            return ResponseEntity.ok(formatAccount(acct));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/accounts/{accountId}/review")
    public ResponseEntity<Map<String, Object>> reviewAccount(
            @PathVariable UUID bookId,
            @PathVariable UUID accountId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader,
            @RequestBody ReviewAccountInput body) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            BankAccount acct = service.reviewAccount(scope, actor, accountId, body);
            return ResponseEntity.ok(formatAccount(acct));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/numbering-policies")
    public ResponseEntity<Map<String, Object>> configureNumberingPolicy(
            @PathVariable UUID bookId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader,
            @RequestBody ConfigureNumberingInput body) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            NumberingPolicy policy = service.configureNumberingPolicy(scope, actor, body);
            return ResponseEntity.ok(Map.of(
                    "id", policy.id(),
                    "periodId", policy.periodId(),
                    "orderType", policy.orderType(),
                    "prefix", policy.prefix(),
                    "pattern", policy.pattern(),
                    "currentSequence", policy.currentSequence(),
                    "isApproved", policy.isApproved()
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/numbering-policies/{policyId}/approve")
    public ResponseEntity<Map<String, Object>> approveNumberingPolicy(
            @PathVariable UUID bookId,
            @PathVariable UUID policyId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            NumberingPolicy policy = service.approveNumberingPolicy(scope, actor, policyId);
            return ResponseEntity.ok(Map.of(
                    "id", policy.id(),
                    "periodId", policy.periodId(),
                    "orderType", policy.orderType(),
                    "prefix", policy.prefix(),
                    "pattern", policy.pattern(),
                    "currentSequence", policy.currentSequence(),
                    "isApproved", policy.isApproved()
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // ==========================================
    // TR01: Orders & Internal Transfers
    // ==========================================

    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> createOrder(
            @PathVariable UUID bookId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader,
            @RequestBody CreateOrderInput body) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            TreasuryOrder order = service.createTreasuryOrder(scope, actor, body);
            return ResponseEntity.ok(formatOrder(order));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/orders/{orderId}/approve")
    public ResponseEntity<Map<String, Object>> approveOrder(
            @PathVariable UUID bookId,
            @PathVariable UUID orderId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            TreasuryOrder order = service.approveTreasuryOrder(scope, actor, orderId);
            return ResponseEntity.ok(formatOrder(order));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // ==========================================
    // TR02: Payment Instructions & UNKNOWN Hold
    // ==========================================

    @PostMapping("/payment-instructions")
    public ResponseEntity<Map<String, Object>> createPaymentInstruction(
            @PathVariable UUID bookId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader,
            @RequestBody CreatePaymentInstructionInput body) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            PaymentInstruction inst = service.createPaymentInstruction(scope, actor, body);
            return ResponseEntity.ok(formatInstruction(inst));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/payment-instructions/{instId}/approve")
    public ResponseEntity<Map<String, Object>> approvePaymentInstruction(
            @PathVariable UUID bookId,
            @PathVariable UUID instId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            PaymentInstruction inst = service.approvePaymentInstruction(scope, actor, instId);
            return ResponseEntity.ok(formatInstruction(inst));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/payment-instructions/{instId}/dispatch")
    public ResponseEntity<Map<String, Object>> dispatchPaymentInstruction(
            @PathVariable UUID bookId,
            @PathVariable UUID instId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            PaymentInstruction inst = service.dispatchPaymentInstruction(scope, actor, instId);
            return ResponseEntity.ok(formatInstruction(inst));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/payment-instructions/{instId}/settle")
    public ResponseEntity<Map<String, Object>> settlePaymentInstruction(
            @PathVariable UUID bookId,
            @PathVariable UUID instId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader,
            @RequestBody VerifySettlementReceiptInput body) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            PaymentInstruction inst = service.settlePaymentInstruction(scope, actor, instId, body);
            return ResponseEntity.ok(formatInstruction(inst));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/payment-instructions/{instId}/fail")
    public ResponseEntity<Map<String, Object>> failPaymentInstruction(
            @PathVariable UUID bookId,
            @PathVariable UUID instId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader,
            @RequestParam String reason,
            @RequestParam(required = false) UUID adjustmentRefId) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            PaymentInstruction inst = service.failPaymentInstruction(scope, actor, instId, reason, adjustmentRefId);
            return ResponseEntity.ok(formatInstruction(inst));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // ==========================================
    // TR03: Bank Statement Import & Matching
    // ==========================================

    @PostMapping("/statement-batches/import")
    public ResponseEntity<Map<String, Object>> importStatement(
            @PathVariable UUID bookId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader,
            @RequestBody ImportStatementInput body) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            BankStatementBatch batch = service.importBankStatement(scope, actor, body);
            return ResponseEntity.ok(Map.of(
                    "batchId", batch.id(),
                    "fileSha256", batch.fileSha256(),
                    "recordCount", batch.recordCount(),
                    "openingBalance", dec(batch.openingBalance()),
                    "closingBalance", dec(batch.closingBalance()),
                    "totalCreditAmount", dec(batch.totalCreditAmount()),
                    "totalDebitAmount", dec(batch.totalDebitAmount()),
                    "status", batch.status().name()
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/statement-lines/review-match")
    public ResponseEntity<Map<String, Object>> reviewMatch(
            @PathVariable UUID bookId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader,
            @RequestBody MatchReviewInput body) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            boolean success = service.reviewMatch(scope, actor, body);
            return ResponseEntity.ok(Map.of("success", success, "decision", body.decision()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // ==========================================
    // TR04: Reconciliation & Forecast
    // ==========================================

    @PostMapping("/reconciliations")
    public ResponseEntity<Map<String, Object>> createReconciliation(
            @PathVariable UUID bookId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader,
            @RequestBody CreateReconciliationInput body) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            BankReconciliation rec = service.createBankReconciliation(scope, actor, body);
            return ResponseEntity.ok(formatReconciliation(rec));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/reconciliations/{recId}/confirm")
    public ResponseEntity<Map<String, Object>> confirmReconciliation(
            @PathVariable UUID bookId,
            @PathVariable UUID recId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            BankReconciliation rec = service.confirmBankReconciliation(scope, actor, recId);
            return ResponseEntity.ok(formatReconciliation(rec));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/forecasts")
    public ResponseEntity<Map<String, Object>> generateForecast(
            @PathVariable UUID bookId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "X-Actor-Account-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Actor-Person-Id", required = false) String personHeader,
            @RequestBody GenerateForecastInput body) {
        try {
            Scope scope = resolveScope(bookId, tenantHeader);
            Actor actor = resolveActor(actorHeader, personHeader);
            List<CashForecast> forecasts = service.generateCashForecast(scope, actor, body);
            List<Map<String, Object>> list = new ArrayList<>();
            for (CashForecast f : forecasts) {
                list.add(Map.of(
                        "tier", f.tier().name(),
                        "horizonDays", f.forecastHorizonDays(),
                        "openingCash", dec(f.openingCash()),
                        "projectedInflow", dec(f.projectedInflow()),
                        "projectedOutflow", dec(f.projectedOutflow()),
                        "closingCash", dec(f.closingCash()),
                        "isReadOnlyForecast", f.isReadOnlyForecast()
                ));
            }
            return ResponseEntity.ok(Map.of("forecasts", list));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    private static Map<String, Object> formatAccount(BankAccount a) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", a.id());
        m.put("accountNo", a.accountNo());
        m.put("accountName", a.accountName());
        m.put("bankName", a.bankName());
        m.put("branchName", a.branchName());
        m.put("accountType", a.accountType().name());
        m.put("currency", a.currency());
        m.put("status", a.status().name());
        m.put("makerPersonId", a.makerPersonId());
        m.put("reviewerPersonId", a.reviewerPersonId());
        m.put("frozenReason", a.frozenReason());
        m.put("closingReason", a.closingReason());
        m.put("version", a.version());
        return m;
    }

    private static Map<String, Object> formatOrder(TreasuryOrder o) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", o.id());
        m.put("orderNo", o.orderNo());
        m.put("orderType", o.orderType().name());
        m.put("accountId", o.accountId());
        m.put("targetAccountId", o.targetAccountId());
        m.put("transferPairId", o.transferPairId());
        m.put("orderDate", o.orderDate().toString());
        m.put("amount", dec(o.amount()));
        m.put("feeAmount", dec(o.feeAmount()));
        m.put("status", o.status().name());
        m.put("purpose", o.purpose());
        m.put("makerPersonId", o.makerPersonId());
        m.put("reviewerPersonId", o.reviewerPersonId());
        m.put("version", o.version());
        return m;
    }

    private static Map<String, Object> formatInstruction(PaymentInstruction i) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", i.id());
        m.put("instructionNo", i.instructionNo());
        m.put("treasuryOrderId", i.treasuryOrderId());
        m.put("payerAccountId", i.payerAccountId());
        m.put("payeeAccountNo", i.payeeAccountNo());
        m.put("payeeAccountName", i.payeeAccountName());
        m.put("payeeBankName", i.payeeBankName());
        m.put("amount", dec(i.amount()));
        m.put("status", i.status().name());
        m.put("bankDirectChannel", i.bankDirectChannel());
        m.put("makerPersonId", i.makerPersonId());
        m.put("approverPersonId", i.approverPersonId());
        m.put("receiptOriginalSha256", i.receiptOriginalSha256());
        m.put("receiptProviderRef", i.receiptProviderRef());
        m.put("receiptVerifierPersonId", i.receiptVerifierPersonId());
        m.put("failureReason", i.failureReason());
        m.put("adjustmentRefId", i.adjustmentRefId());
        m.put("version", i.version());
        return m;
    }

    private static Map<String, Object> formatReconciliation(BankReconciliation r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.id());
        m.put("bankStatementBalance", dec(r.bankStatementBalance()));
        m.put("enterpriseRecordedBankUnrecorded", dec(r.enterpriseRecordedBankUnrecorded()));
        m.put("enterprisePaidBankUnpaid", dec(r.enterprisePaidBankUnpaid()));
        m.put("adjustedBankBalance", dec(r.adjustedBankBalance()));
        m.put("ledgerBalance", dec(r.ledgerBalance()));
        m.put("bankCreditedEnterpriseUnrecorded", dec(r.bankCreditedEnterpriseUnrecorded()));
        m.put("bankDebitedEnterpriseUnrecorded", dec(r.bankDebitedEnterpriseUnrecorded()));
        m.put("adjustedLedgerBalance", dec(r.adjustedLedgerBalance()));
        m.put("isBalanced", r.isBalanced());
        m.put("status", r.status().name());
        m.put("version", r.version());
        return m;
    }
}
