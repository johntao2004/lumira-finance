package com.lumira.finance.expense;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lumira.finance.FinanceGlobalExceptionHandler;
import com.lumira.finance.expense.ExpenseDomain.*;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/**
 * REST controller for EXP01-EXP04 Expense & Travel capabilities.
 * Enforces strict 2-decimal CNY strings, closed command shapes, and explicit idempotency.
 */
@RestController
@RequestMapping("/api/finance/v1/books/{bookId}/expense")
@ModuleEndpoint("finance")
public final class ExpenseController {

    private final ExpenseService service;
    private final DataSource dataSource;

    public ExpenseController(ExpenseService service) {
        this(service, null);
    }

    @Autowired
    public ExpenseController(ExpenseService service, @Autowired(required = false) DataSource dataSource) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.dataSource = dataSource;
    }

    private Connection getConnection() throws SQLException {
        if (dataSource != null) {
            return dataSource.getConnection();
        }
        throw new IllegalStateException("DATA_SOURCE_NOT_CONFIGURED");
    }

    private UUID resolveTenant(Connection conn, UUID bookId, UUID headerTenant, HttpServletRequest req) throws SQLException {
        if (headerTenant != null) return headerTenant;
        if (req != null) {
            Object attr = req.getAttribute("tenantId");
            if (attr instanceof UUID u) return u;
            if (attr instanceof String s && !s.isBlank()) return UUID.fromString(s);
            String h = req.getHeader("X-Tenant-Id");
            if (h != null && !h.isBlank()) return UUID.fromString(h);
        }
        if (conn != null && bookId != null) {
            try (java.sql.PreparedStatement ps = conn.prepareStatement("SELECT tenant_id FROM plugin_finance_book WHERE id = ?")) {
                ps.setObject(1, bookId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return (UUID) rs.getObject("tenant_id");
                    }
                }
            }
        }
        throw new ValidationException("TENANT_REQUIRED", "Tenant context could not be resolved");
    }

    // --- Request / Response Records ---

    public record CreatePolicyRequest(
            String idempotencyKey,
            String policyCode,
            int versionInt,
            String name,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            UUID createdBy,
            List<TravelPolicyCapRequest> caps
    ) {}

    public record TravelPolicyCapRequest(
            CityTier cityTier,
            EmployeeRank employeeRank,
            ExpenseCategory expenseCategory,
            String dailyCapAmount
    ) {}

    public record CreateExceptionRequest(
            String idempotencyKey,
            UUID claimId,
            int lineNo,
            UUID policyCapId,
            String requestedAmount,
            String standardCapAmount,
            UUID applicantPersonId,
            String justification
    ) {}

    public record DecideExceptionRequest(
            String idempotencyKey,
            UUID approverPersonId,
            boolean approve,
            String decisionNotes
    ) {}

    public record CreateRequisitionRequest(
            String idempotencyKey,
            String requisitionNo,
            UUID applicantId,
            UUID departmentId,
            UUID projectId,
            String purpose,
            String totalBudget,
            LocalDate effectiveUntil
    ) {}

    public record RequisitionActionRequest(
            String idempotencyKey,
            String action, // "submit", "approve", "cancel"
            UUID approverId
    ) {}

    public record CreateAdvanceRequest(
            String idempotencyKey,
            String advanceNo,
            UUID employeeId,
            UUID requisitionId,
            String purpose,
            String principalAmount,
            UUID applicantId
    ) {}

    public record AdvanceActionRequest(
            String idempotencyKey,
            String action, // "submit", "approve", "payout"
            UUID actorId,
            String payoutRef
    ) {}

    public record AdvanceRefundRequest(
            String idempotencyKey,
            String refundNo,
            String refundAmount,
            LocalDate refundDate,
            String receiptMethod,
            String receiptRef,
            UUID cashierPersonId
    ) {}

    public record CreateClaimRequest(
            String idempotencyKey,
            String claimNo,
            UUID employeeId,
            UUID requisitionId,
            String title,
            LocalDate claimDate,
            UUID submitterId,
            List<ClaimLineRequest> lines,
            Map<UUID, String> advanceOffsets
    ) {}

    public record ClaimLineRequest(
            int lineNo,
            ExpenseCategory expenseCategory,
            UUID departmentId,
            UUID projectId,
            UUID invoiceId,
            String invoiceCode,
            String invoiceNumber,
            String invoiceDigest,
            String submittedAmount,
            String disallowedAmount,
            String disallowanceReason,
            String vatRate,
            UUID travelPolicyCapId,
            boolean isExceptionApproved
    ) {}

    public record ClaimActionRequest(
            String idempotencyKey,
            String action, // "submit", "review", "approve", "post"
            UUID actorId
    ) {}

    public record DisbursePaymentRequest(
            String idempotencyKey,
            String paymentNo,
            String amount,
            LocalDate paymentDate,
            String paymentMethod,
            String paymentRef,
            UUID cashierId
    ) {}

    public record ReverseClaimRequest(
            String idempotencyKey,
            UUID actorId,
            String reason
    ) {}

    // --- Endpoints ---

    @PostMapping("/policies")
    public ResponseEntity<?> createPolicy(
            @PathVariable UUID bookId,
            @RequestHeader(name = "X-Tenant-Id", required = false) UUID headerTenant,
            @RequestBody CreatePolicyRequest req,
            HttpServletRequest httpReq
    ) {
        try (Connection conn = getConnection()) {
            UUID tenantId = resolveTenant(conn, bookId, headerTenant, httpReq);
            List<TravelPolicyCap> caps = new ArrayList<>();
            if (req.caps() != null) {
                for (TravelPolicyCapRequest c : req.caps()) {
                    caps.add(new TravelPolicyCap(
                            null, null, c.cityTier(), c.employeeRank(), c.expenseCategory(),
                            ExpenseDomain.cny(c.dailyCapAmount(), true), ExpenseDomain.CURRENCY_CNY
                    ));
                }
            }
            TravelPolicy created = service.createTravelPolicy(
                    conn, tenantId, req.policyCode(), req.versionInt(), req.name(),
                    req.effectiveFrom(), req.effectiveTo(), req.createdBy(), caps
            );
            return ResponseEntity.ok(created);
        } catch (ValidationException e) {
            int status = e.getErrorCode().contains("SOD") ? 403 : 400;
            return ResponseEntity.status(status).body(Map.of("code", e.getErrorCode(), "error", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/policies/{policyId}/activate")
    public ResponseEntity<?> activatePolicy(
            @PathVariable UUID bookId,
            @PathVariable UUID policyId
    ) {
        try (Connection conn = getConnection()) {
            return ResponseEntity.ok(service.activateTravelPolicy(conn, policyId));
        } catch (ValidationException e) {
            int status = e.getErrorCode().contains("SOD") ? 403 : 400;
            return ResponseEntity.status(status).body(Map.of("code", e.getErrorCode(), "error", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/exceptions")
    public ResponseEntity<?> requestException(
            @PathVariable UUID bookId,
            @RequestHeader(name = "X-Tenant-Id", required = false) UUID headerTenant,
            @RequestBody CreateExceptionRequest req,
            HttpServletRequest httpReq
    ) {
        try (Connection conn = getConnection()) {
            UUID tenantId = resolveTenant(conn, bookId, headerTenant, httpReq);
            return ResponseEntity.ok(service.requestTravelException(
                    conn, tenantId, req.claimId(), req.lineNo(), req.policyCapId(),
                    ExpenseDomain.cny(req.requestedAmount(), false),
                    ExpenseDomain.cny(req.standardCapAmount(), true),
                    req.applicantPersonId(), req.justification()
            ));
        } catch (ValidationException e) {
            int status = e.getErrorCode().contains("SOD") ? 403 : 400;
            return ResponseEntity.status(status).body(Map.of("code", e.getErrorCode(), "error", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/exceptions/{exceptionId}/decision")
    public ResponseEntity<?> decideException(
            @PathVariable UUID bookId,
            @PathVariable UUID exceptionId,
            @RequestBody DecideExceptionRequest req
    ) {
        try (Connection conn = getConnection()) {
            return ResponseEntity.ok(service.decideTravelException(
                    conn, exceptionId, req.approverPersonId(), req.approve(), req.decisionNotes()
            ));
        } catch (ValidationException e) {
            int status = e.getErrorCode().contains("SOD") ? 403 : 400;
            return ResponseEntity.status(status).body(Map.of("code", e.getErrorCode(), "error", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/requisitions")
    public ResponseEntity<?> createRequisition(
            @PathVariable UUID bookId,
            @RequestHeader(name = "X-Tenant-Id", required = false) UUID headerTenant,
            @RequestBody CreateRequisitionRequest req,
            HttpServletRequest httpReq
    ) {
        try (Connection conn = getConnection()) {
            UUID tenantId = resolveTenant(conn, bookId, headerTenant, httpReq);
            return ResponseEntity.ok(service.createRequisition(
                    conn, tenantId, bookId, req.requisitionNo(), req.applicantId(),
                    req.departmentId(), req.projectId(), req.purpose(),
                    ExpenseDomain.cny(req.totalBudget(), false), req.effectiveUntil()
            ));
        } catch (ValidationException e) {
            int status = e.getErrorCode().contains("SOD") ? 403 : 400;
            return ResponseEntity.status(status).body(Map.of("code", e.getErrorCode(), "error", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/requisitions/{id}/actions")
    public ResponseEntity<?> actRequisition(
            @PathVariable UUID bookId,
            @PathVariable UUID id,
            @RequestBody RequisitionActionRequest req
    ) {
        try (Connection conn = getConnection()) {
            return switch (req.action()) {
                case "submit" -> ResponseEntity.ok(service.submitRequisition(conn, id));
                case "approve" -> ResponseEntity.ok(service.approveRequisition(conn, id, req.approverId()));
                case "cancel" -> ResponseEntity.ok(service.cancelRequisition(conn, id));
                default -> throw new ValidationException("INVALID_ACTION", "Unknown requisition action: " + req.action());
            };
        } catch (ValidationException e) {
            int status = e.getErrorCode().contains("SOD") ? 403 : 400;
            return ResponseEntity.status(status).body(Map.of("code", e.getErrorCode(), "error", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/advances")
    public ResponseEntity<?> createAdvance(
            @PathVariable UUID bookId,
            @RequestHeader(name = "X-Tenant-Id", required = false) UUID headerTenant,
            @RequestBody CreateAdvanceRequest req,
            HttpServletRequest httpReq
    ) {
        try (Connection conn = getConnection()) {
            UUID tenantId = resolveTenant(conn, bookId, headerTenant, httpReq);
            return ResponseEntity.ok(service.createAdvance(
                    conn, tenantId, bookId, req.advanceNo(), req.employeeId(), req.requisitionId(),
                    req.purpose(), ExpenseDomain.cny(req.principalAmount(), false), req.applicantId()
            ));
        } catch (ValidationException e) {
            int status = e.getErrorCode().contains("SOD") ? 403 : 400;
            return ResponseEntity.status(status).body(Map.of("code", e.getErrorCode(), "error", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/advances/{id}/actions")
    public ResponseEntity<?> actAdvance(
            @PathVariable UUID bookId,
            @PathVariable UUID id,
            @RequestBody AdvanceActionRequest req
    ) {
        try (Connection conn = getConnection()) {
            return switch (req.action()) {
                case "submit" -> ResponseEntity.ok(service.submitAdvance(conn, id));
                case "approve" -> ResponseEntity.ok(service.approveAdvance(conn, id, req.actorId()));
                case "payout" -> ResponseEntity.ok(service.payoutAdvance(conn, id, req.actorId(), req.payoutRef()));
                case "cancel" -> ResponseEntity.ok(service.cancelAdvance(conn, id, req.actorId()));
                default -> throw new ValidationException("INVALID_ACTION", "Unknown advance action: " + req.action());
            };
        } catch (ValidationException e) {
            int status = e.getErrorCode().contains("SOD") ? 403 : 400;
            return ResponseEntity.status(status).body(Map.of("code", e.getErrorCode(), "error", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/advances/{id}/refunds")
    public ResponseEntity<?> refundAdvance(
            @PathVariable UUID bookId,
            @PathVariable UUID id,
            @RequestBody AdvanceRefundRequest req
    ) {
        try (Connection conn = getConnection()) {
            return ResponseEntity.ok(service.refundAdvanceCash(
                    conn, id, req.refundNo(), ExpenseDomain.cny(req.refundAmount(), false),
                    req.refundDate(), req.receiptMethod(), req.receiptRef(), req.cashierPersonId()
            ));
        } catch (ValidationException e) {
            int status = e.getErrorCode().contains("SOD") ? 403 : 400;
            return ResponseEntity.status(status).body(Map.of("code", e.getErrorCode(), "error", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/claims")
    public ResponseEntity<?> createClaim(
            @PathVariable UUID bookId,
            @RequestHeader(name = "X-Tenant-Id", required = false) UUID headerTenant,
            @RequestBody CreateClaimRequest req,
            HttpServletRequest httpReq
    ) {
        try (Connection conn = getConnection()) {
            UUID tenantId = resolveTenant(conn, bookId, headerTenant, httpReq);
            List<ExpenseClaimLine> lines = new ArrayList<>();
            if (req.lines() != null) {
                for (ClaimLineRequest l : req.lines()) {
                    BigDecimal sub = ExpenseDomain.cny(l.submittedAmount(), false);
                    BigDecimal dis = l.disallowedAmount() != null ? ExpenseDomain.cny(l.disallowedAmount(), true) : BigDecimal.ZERO;
                    BigDecimal vat = l.vatRate() != null ? new BigDecimal(l.vatRate()) : BigDecimal.ZERO;
                    lines.add(new ExpenseClaimLine(
                            null, null, l.lineNo(), l.expenseCategory(), l.departmentId(), l.projectId(),
                            l.invoiceId(), l.invoiceCode(), l.invoiceNumber(), l.invoiceDigest(),
                            sub, sub.subtract(dis), dis, l.disallowanceReason(), vat,
                            BigDecimal.ZERO, BigDecimal.ZERO, l.travelPolicyCapId(), l.isExceptionApproved()
                    ));
                }
            }
            Map<UUID, BigDecimal> offsets = new HashMap<>();
            if (req.advanceOffsets() != null) {
                for (Map.Entry<UUID, String> e : req.advanceOffsets().entrySet()) {
                    offsets.put(e.getKey(), ExpenseDomain.cny(e.getValue(), false));
                }
            }
            var res = service.createClaim(
                    conn, tenantId, bookId, req.claimNo(), req.employeeId(), req.requisitionId(),
                    req.title(), req.claimDate(), req.submitterId(), lines, offsets
            );
            return ResponseEntity.ok(res.claim());
        } catch (ValidationException e) {
            int status = e.getErrorCode().contains("SOD") ? 403 : 400;
            return ResponseEntity.status(status).body(Map.of("code", e.getErrorCode(), "error", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/claims/{id}/actions")
    public ResponseEntity<?> actClaim(
            @PathVariable UUID bookId,
            @PathVariable UUID id,
            @RequestBody ClaimActionRequest req
    ) {
        try (Connection conn = getConnection()) {
            return switch (req.action()) {
                case "submit" -> ResponseEntity.ok(service.submitClaim(conn, id));
                case "review" -> ResponseEntity.ok(service.reviewClaim(conn, id, req.actorId()));
                case "approve" -> ResponseEntity.ok(service.approveClaim(conn, id, req.actorId()));
                case "post" -> {
                    service.postClaimJournal(conn, id);
                    yield ResponseEntity.ok(service.store().findClaimById(conn, id).orElseThrow());
                }
                case "reject" -> ResponseEntity.ok(service.rejectClaim(conn, id, req.actorId(), "Claim rejected"));
                case "cancel" -> ResponseEntity.ok(service.cancelClaim(conn, id, req.actorId()));
                case "reverse" -> {
                    service.reverseClaim(conn, id, req.actorId(), "Claim reversed");
                    yield ResponseEntity.ok(service.store().findClaimById(conn, id).orElseThrow());
                }
                default -> throw new ValidationException("INVALID_ACTION", "Unknown claim action: " + req.action());
            };
        } catch (ValidationException e) {
            int status = e.getErrorCode().contains("SOD") ? 403 : 400;
            return ResponseEntity.status(status).body(Map.of("code", e.getErrorCode(), "error", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/claims/{id}/reverse")
    public ResponseEntity<?> reverseClaim(
            @PathVariable UUID bookId,
            @PathVariable UUID id,
            @RequestBody ReverseClaimRequest req
    ) {
        try (Connection conn = getConnection()) {
            return ResponseEntity.ok(service.reverseClaim(conn, id, req.actorId(), req.reason()));
        } catch (ValidationException e) {
            int status = e.getErrorCode().contains("SOD") ? 403 : 400;
            return ResponseEntity.status(status).body(Map.of("code", e.getErrorCode(), "error", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/claims/{id}/disburse")
    public ResponseEntity<?> disburse(
            @PathVariable UUID bookId,
            @PathVariable UUID id,
            @RequestBody DisbursePaymentRequest req
    ) {
        try (Connection conn = getConnection()) {
            return ResponseEntity.ok(service.disbursePayment(
                    conn, id, req.paymentNo(), ExpenseDomain.cny(req.amount(), false),
                    req.paymentDate(), req.paymentMethod(), req.paymentRef(), req.cashierId()
            ));
        } catch (ValidationException e) {
            int status = e.getErrorCode().contains("SOD") ? 403 : 400;
            return ResponseEntity.status(status).body(Map.of("code", e.getErrorCode(), "error", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- Query / GET endpoints ---

    @GetMapping("/policies")
    public ResponseEntity<?> listPolicies(
            @PathVariable UUID bookId,
            @RequestHeader(name = "X-Tenant-Id", required = false) UUID headerTenant,
            HttpServletRequest httpReq
    ) {
        try (Connection conn = getConnection()) {
            UUID tenantId = resolveTenant(conn, bookId, headerTenant, httpReq);
            return ResponseEntity.ok(service.listPolicies(conn, tenantId));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/requisitions")
    public ResponseEntity<?> listRequisitions(
            @PathVariable UUID bookId,
            @RequestHeader(name = "X-Tenant-Id", required = false) UUID headerTenant,
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false, defaultValue = "false") boolean isReviewer,
            HttpServletRequest httpReq
    ) {
        try (Connection conn = getConnection()) {
            UUID tenantId = resolveTenant(conn, bookId, headerTenant, httpReq);
            return ResponseEntity.ok(service.listRequisitions(conn, tenantId, employeeId, isReviewer));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/advances")
    public ResponseEntity<?> listAdvances(
            @PathVariable UUID bookId,
            @RequestHeader(name = "X-Tenant-Id", required = false) UUID headerTenant,
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false, defaultValue = "false") boolean isReviewer,
            HttpServletRequest httpReq
    ) {
        try (Connection conn = getConnection()) {
            UUID tenantId = resolveTenant(conn, bookId, headerTenant, httpReq);
            return ResponseEntity.ok(service.listAdvances(conn, tenantId, employeeId, isReviewer));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/claims")
    public ResponseEntity<?> listClaims(
            @PathVariable UUID bookId,
            @RequestHeader(name = "X-Tenant-Id", required = false) UUID headerTenant,
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false, defaultValue = "false") boolean isReviewer,
            HttpServletRequest httpReq
    ) {
        try (Connection conn = getConnection()) {
            UUID tenantId = resolveTenant(conn, bookId, headerTenant, httpReq);
            return ResponseEntity.ok(service.listClaims(conn, tenantId, employeeId, isReviewer));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/payables")
    public ResponseEntity<?> listPayables(
            @PathVariable UUID bookId,
            @RequestHeader(name = "X-Tenant-Id", required = false) UUID headerTenant,
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false, defaultValue = "false") boolean isReviewer,
            HttpServletRequest httpReq
    ) {
        try (Connection conn = getConnection()) {
            UUID tenantId = resolveTenant(conn, bookId, headerTenant, httpReq);
            return ResponseEntity.ok(service.listPayables(conn, tenantId, employeeId, isReviewer));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }
}
