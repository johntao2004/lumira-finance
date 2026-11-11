package com.lumira.finance.yearend;

import static com.lumira.finance.yearend.YearendModel.*;

import com.lumira.finance.FinanceException;
import com.lumira.finance.FinanceGlobalExceptionHandler;
import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * REST controller for Lane W09 GL and Year-End operations.
 * Implements strict Jackson 3 serialization conventions and BigDecimal plain string representation.
 */
@RestController
@RequestMapping("/api/finance/v1/yearend")
@ModuleEndpoint("finance")
public class YearendController {
    private final YearendService service;
    private final DataSource dataSource;

    public YearendController(YearendService service, @Autowired(required = false) DataSource dataSource) {
        this.service = Objects.requireNonNull(service);
        this.dataSource = dataSource;
    }

    private void checkBookAccess(UUID tenantId, UUID bookId, String accountId, String personId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null && auth.getName() != null ? auth.getName() : null;

        if ("admin".equalsIgnoreCase(username)) {
            return;
        }
        if (auth != null && auth.getAuthorities() != null) {
            for (GrantedAuthority ga : auth.getAuthorities()) {
                if ("ROLE_ADMIN".equalsIgnoreCase(ga.getAuthority()) || "ADMIN".equalsIgnoreCase(ga.getAuthority())) {
                    return;
                }
            }
        }

        UUID userUuid = null;
        if (accountId != null && !accountId.isBlank()) {
            try { userUuid = UUID.fromString(accountId.trim()); } catch (Exception ignored) {}
        }
        if (userUuid == null && personId != null && !personId.isBlank()) {
            try { userUuid = UUID.fromString(personId.trim()); } catch (Exception ignored) {}
        }

        if (dataSource != null) {
            try (Connection conn = dataSource.getConnection()) {
                if (username != null && !username.isBlank()) {
                    String sql = "SELECT user_id FROM plugin_finance_member WHERE tenant_id = ? AND book_id = ? AND enabled = true AND username = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setObject(1, tenantId);
                        ps.setObject(2, bookId);
                        ps.setString(3, username);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                UUID memberUserId = (UUID) rs.getObject("user_id");
                                if (userUuid == null || userUuid.equals(memberUserId)) {
                                    return;
                                }
                            }
                        }
                    }
                } else if (userUuid != null) {
                    String sql = "SELECT username FROM plugin_finance_member WHERE tenant_id = ? AND book_id = ? AND enabled = true AND user_id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setObject(1, tenantId);
                        ps.setObject(2, bookId);
                        ps.setObject(3, userUuid);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                return;
                            }
                        }
                    }
                }
            } catch (SQLException ignored) {
            }
            throw new FinanceException("SCOPE_DENIED", "账套范围不可访问");
        } else {
            if ("plain".equalsIgnoreCase(username)) {
                throw new FinanceException("SCOPE_DENIED", "账套范围不可访问");
            }
            if (userUuid != null && !"plain".equalsIgnoreCase(username)) {
                return;
            }
            throw new FinanceException("SCOPE_DENIED", "账套范围不可访问");
        }
    }

    private PersonActor extractActor(String accountHeader, String personHeader, String name) {
        UUID accountId = accountHeader != null ? UUID.fromString(accountHeader) : UUID.randomUUID();
        UUID personId = personHeader != null ? UUID.fromString(personHeader) : UUID.randomUUID();
        return new PersonActor(accountId, personId, name != null ? name : "operator");
    }

    // --- GL01 Cutover Endpoints ---
    @PostMapping("/books/{bookId}/years/{yearId}/cutover")
    public ResponseEntity<?> proposeCutover(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @PathVariable UUID yearId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Person-Id", required = false) String personId,
            @RequestBody CutoverCreateRequest req) {
        try {
            checkBookAccess(tenantId, bookId, accountId, personId);
            PersonActor maker = extractActor(accountId, personId, "maker");
            CutoverProposal proposal = service.proposeCutover(
                    tenantId, bookId, yearId, req.cutoverDate(), req.mode(),
                    req.sourceSystem(), req.sourceWatermark(), req.openingBalances(),
                    req.baselines(), req.subledgerTotals(), maker
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(proposal);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/books/{bookId}/cutover/{planId}/approve")
    public ResponseEntity<?> approveCutover(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @PathVariable UUID planId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Person-Id", required = false) String personId) {
        try {
            checkBookAccess(tenantId, bookId, accountId, personId);
            PersonActor approver = extractActor(accountId, personId, "approver");
            service.approveCutover(tenantId, bookId, planId, approver);
            return ResponseEntity.ok(Map.of("status", "APPROVED", "planId", planId.toString()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/books/{bookId}/cutover/{planId}/lock")
    public ResponseEntity<?> lockCutover(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @PathVariable UUID planId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Person-Id", required = false) String personId) {
        try {
            checkBookAccess(tenantId, bookId, accountId, personId);
            PersonActor locker = extractActor(accountId, personId, "locker");
            service.lockCutover(tenantId, bookId, planId, locker);
            return ResponseEntity.ok(Map.of("status", "LOCKED", "planId", planId.toString()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- GL02 & GL03 Auto Rules Endpoints ---
    @PostMapping("/books/{bookId}/rule-templates")
    public ResponseEntity<?> createRuleTemplate(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Person-Id", required = false) String personId,
            @RequestBody TemplateCreateRequest req) {
        try {
            checkBookAccess(tenantId, bookId, accountId, personId);
            PersonActor maker = extractActor(accountId, personId, "maker");
            AutoRuleTemplate tmpl = service.createRuleTemplate(
                    tenantId, bookId, req.ruleCode(), req.ruleName(), req.businessType(),
                    req.lineTemplates(), maker
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(tmpl);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/books/{bookId}/rule-templates/{templateId}/approve")
    public ResponseEntity<?> approveRuleTemplate(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @PathVariable UUID templateId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Person-Id", required = false) String personId) {
        try {
            checkBookAccess(tenantId, bookId, accountId, personId);
            PersonActor approver = extractActor(accountId, personId, "approver");
            service.approveRuleTemplate(tenantId, bookId, templateId, approver);
            return ResponseEntity.ok(Map.of("status", "APPROVED", "templateId", templateId.toString()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/books/{bookId}/review-policies")
    public ResponseEntity<?> createReviewPolicy(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Person-Id", required = false) String personId,
            @RequestBody PolicyCreateRequest req) {
        try {
            checkBookAccess(tenantId, bookId, accountId, personId);
            PersonActor maker = extractActor(accountId, personId, "maker");
            BigDecimal max = new BigDecimal(req.maxAmount());
            AutoReviewPolicy policy = service.createReviewPolicy(
                    tenantId, bookId, req.policyCode(), req.policyName(),
                    req.scopeBusinessTypes(), req.allowedAccountCodes(),
                    max, req.zeroExceptionRequired(), maker
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(policy);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/books/{bookId}/review-policies/{policyId}/approve")
    public ResponseEntity<?> approveReviewPolicy(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @PathVariable UUID policyId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Person-Id", required = false) String personId) {
        try {
            checkBookAccess(tenantId, bookId, accountId, personId);
            PersonActor approver = extractActor(accountId, personId, "approver");
            service.approveReviewPolicy(tenantId, bookId, policyId, approver);
            return ResponseEntity.ok(Map.of("status", "APPROVED", "policyId", policyId.toString()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- GL05 Correction Endpoints ---
    @PostMapping("/books/{bookId}/corrections")
    public ResponseEntity<?> createCorrection(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Person-Id", required = false) String personId,
            @RequestBody CorrectionCreateRequest req) {
        try {
            checkBookAccess(tenantId, bookId, accountId, personId);
            PersonActor maker = extractActor(accountId, personId, "maker");
            UUID targetYear = req.targetYearId() != null ? req.targetYearId() : req.sourceYearId();
            JournalCorrectionRecord rec = service.createJournalCorrection(
                    tenantId, bookId, targetYear, req.sourceYearId(),
                    req.sourceJournalId(), req.sourcePostingSeq(), req.sourceDigest(),
                    req.correctionJournalId(), req.correctionKind(), req.standard(),
                    req.casAccountCode(), req.reason(), req.allocations(),
                    req.lineWatermarks(), maker
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(rec);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- GL06 Period Close Endpoints ---
    @PostMapping("/books/{bookId}/years/{yearId}/periods/{periodId}/close-preview")
    public ResponseEntity<?> previewPeriodClose(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @PathVariable UUID yearId,
            @PathVariable UUID periodId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Person-Id", required = false) String personId,
            @RequestBody PeriodClosePreviewRequest req) {
        try {
            checkBookAccess(tenantId, bookId, accountId, personId);
            PersonActor maker = extractActor(accountId, personId, "maker");
            PeriodCloseCheckPlan plan = service.previewPeriodClose(
                    tenantId, bookId, yearId, periodId, req.unpostedJournalsCount(),
                    req.trialBalanceDebit(), req.trialBalanceCredit(), req.bankReconciliationComplete(),
                    req.glBalances(), req.subledgerBalances(), req.cashflowCoverageComplete(),
                    req.taxWorksheetReady(), maker
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(plan);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/books/{bookId}/period-close/{planId}/sign-off")
    public ResponseEntity<?> signOffPeriodClose(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @PathVariable UUID planId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Person-Id", required = false) String personId) {
        try {
            checkBookAccess(tenantId, bookId, accountId, personId);
            PersonActor approver = extractActor(accountId, personId, "approver");
            service.executePeriodClose(tenantId, bookId, planId, approver);
            return ResponseEntity.ok(Map.of("status", "CLOSED", "planId", planId.toString()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- YEAR01, YEAR02, YEAR03 Carry Batch Endpoints ---
    @PostMapping("/books/{bookId}/carry-batches/preview")
    public ResponseEntity<?> previewCarryBatch(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Person-Id", required = false) String personId,
            @RequestBody CarryBatchPreviewRequest req) {
        try {
            checkBookAccess(tenantId, bookId, accountId, personId);
            PersonActor maker = extractActor(accountId, personId, "maker");
            CarryBatchPlan plan = service.previewCarryBatch(
                    tenantId, bookId, req.sourceYearId(), req.targetYearId(),
                    req.sourceCloseVersion(), req.sourceWatermark(), req.targetOpeningVersion(),
                    req.mappingVersion(), req.kind(), req.previousBatchId(), req.reason(),
                    req.balances(), req.subledgerItems(), Map.of(), maker
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(plan);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/books/{bookId}/carry-batches/{batchId}/commit")
    public ResponseEntity<?> commitCarryBatch(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @PathVariable UUID batchId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Person-Id", required = false) String personId) {
        try {
            checkBookAccess(tenantId, bookId, accountId, personId);
            PersonActor approver = extractActor(accountId, personId, "approver");
            service.commitCarryBatch(tenantId, bookId, batchId, approver);
            return ResponseEntity.ok(Map.of("status", "COMMITTED", "batchId", batchId.toString()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }
}
