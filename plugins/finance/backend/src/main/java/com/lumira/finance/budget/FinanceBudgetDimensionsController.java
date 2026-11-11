package com.lumira.finance.budget;

import com.lumira.finance.FinanceGlobalExceptionHandler;
import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.*;

/**
 * REST controller candidate for Dimensions & Budget Control (Lane W03).
 * Uses strict Jackson 3 and plain string decimals for exact numeric precision.
 */
@RestController
@RequestMapping("/api/finance/v1/budget-dimensions")
@ModuleEndpoint("finance")
public class FinanceBudgetDimensionsController {

    private final FinanceBudgetDimensionsService service;

    public FinanceBudgetDimensionsController(FinanceBudgetDimensionsService service) {
        this.service = Objects.requireNonNull(service, "service required");
    }

    // -------------------------------------------------------------------------
    // MD01: Account Auxiliary Dimensions & Deactivation
    // -------------------------------------------------------------------------

    @GetMapping("/account-rule")
    public ResponseEntity<FinanceBudgetDimensionsInput.AccountRuleDto> getAccountRule(
            @RequestParam UUID tenantId,
            @RequestParam UUID bookId,
            @RequestParam UUID yearId,
            @RequestParam UUID accountId) throws SQLException {
        Optional<FinanceBudgetDimensionsInput.AccountRuleDto> opt = service.findAccountRule(tenantId, bookId, yearId, accountId);
        return opt.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/account-rule")
    public ResponseEntity<Map<String, Object>> configureAccountRule(
            @RequestBody FinanceBudgetDimensionsInput.AccountRuleDto rule) {
        try {
            service.configureAccountRule(rule);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "CONFIGURED", "accountId", rule.accountId()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/account-deactivation")
    public ResponseEntity<Map<String, Object>> submitAccountDeactivation(
            @RequestParam UUID tenantId,
            @RequestParam UUID bookId,
            @RequestParam UUID yearId,
            @RequestBody FinanceBudgetDimensionsInput.AccountDeactivationRequest req) {
        try {
            UUID approvalId = service.submitAccountDeactivation(tenantId, bookId, yearId, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("approvalId", approvalId, "status", "PENDING"));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/account-approval")
    public ResponseEntity<Map<String, Object>> reviewAccountApproval(
            @RequestBody FinanceBudgetDimensionsInput.AccountApprovalDecision decision) {
        try {
            service.reviewAccountApproval(decision);
            return ResponseEntity.ok(Map.of("approvalId", decision.approvalId(), "status", decision.decision()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // -------------------------------------------------------------------------
    // MD02: Counterparty Bank Accounts & Identity
    // -------------------------------------------------------------------------

    @PostMapping("/counterparty-bank")
    public ResponseEntity<Map<String, Object>> registerCounterpartyBankAccount(
            @RequestBody FinanceBudgetDimensionsInput.CounterpartyBankAccountDto dto) {
        try {
            service.registerCounterpartyBankAccount(dto);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", dto.id(), "status", dto.status()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/counterparty-bank/review")
    public ResponseEntity<Map<String, Object>> submitBankAccountReview(
            @RequestParam UUID tenantId,
            @RequestParam UUID legalEntityId,
            @RequestParam UUID counterpartyId,
            @RequestBody FinanceBudgetDimensionsInput.BankAccountReviewRequest req) {
        try {
            UUID reviewId = service.submitBankAccountReview(tenantId, legalEntityId, counterpartyId, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("reviewId", reviewId, "status", "PENDING"));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/counterparty-bank/decide")
    public ResponseEntity<Map<String, Object>> reviewBankAccount(
            @RequestBody FinanceBudgetDimensionsInput.BankAccountReviewDecision decision) {
        try {
            service.reviewBankAccount(decision);
            return ResponseEntity.ok(Map.of("reviewId", decision.reviewId(), "status", decision.decision()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/counterparty-identity-history")
    public ResponseEntity<Map<String, Object>> recordIdentityHistory(
            @RequestBody FinanceBudgetDimensionsInput.CounterpartyIdentityHistoryDto history) {
        try {
            service.appendIdentityHistory(history);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", history.id(), "status", "RECORDED"));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/framework-contract")
    public ResponseEntity<Map<String, Object>> registerFrameworkContract(
            @RequestBody FinanceBudgetDimensionsInput.FrameworkContractDto contract) {
        try {
            service.registerFrameworkContract(contract);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", contract.id(), "status", contract.status()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // -------------------------------------------------------------------------
    // MD03: Dimensions & Cost Allocation
    // -------------------------------------------------------------------------

    @PostMapping("/department")
    public ResponseEntity<Map<String, Object>> registerDepartment(
            @RequestBody FinanceBudgetDimensionsInput.DepartmentDto dept) {
        try {
            service.registerDepartment(dept);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", dept.id(), "status", dept.status()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/project")
    public ResponseEntity<Map<String, Object>> registerProject(
            @RequestBody FinanceBudgetDimensionsInput.ProjectDto proj) {
        try {
            service.registerProject(proj);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", proj.id(), "status", proj.status()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/cost-center")
    public ResponseEntity<Map<String, Object>> registerCostCenter(
            @RequestBody FinanceBudgetDimensionsInput.CostCenterDto cc) {
        try {
            service.registerCostCenter(cc);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", cc.id(), "status", cc.status()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/contract-dimension")
    public ResponseEntity<Map<String, Object>> registerContractDimension(
            @RequestBody FinanceBudgetDimensionsInput.ContractDimensionDto contract) {
        try {
            service.registerContractDimension(contract);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", contract.id(), "status", contract.status()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/allocation-rule")
    public ResponseEntity<Map<String, Object>> createAllocationRule(
            @RequestParam UUID tenantId,
            @RequestParam UUID bookId,
            @RequestBody FinanceBudgetDimensionsInput.AllocationRuleRequest req) {
        try {
            UUID ruleId = service.createAllocationRule(tenantId, bookId, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ruleId", ruleId, "status", "ACTIVE"));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/allocation-execute")
    public ResponseEntity<FinanceBudgetDimensionsInput.AllocationExecutionResult> executeAllocation(
            @RequestBody FinanceBudgetDimensionsInput.AllocationExecutionRequest req) throws SQLException {
        FinanceBudgetDimensionsInput.AllocationExecutionResult res = service.executeAllocation(req);
        return ResponseEntity.ok(res);
    }

    // -------------------------------------------------------------------------
    // BD01: Budget Formulation, Approval & Adjustment
    // -------------------------------------------------------------------------

    @PostMapping("/budget-version")
    public ResponseEntity<Map<String, Object>> createBudgetVersion(
            @RequestParam UUID tenantId,
            @RequestParam UUID legalEntityId,
            @RequestParam UUID bookId,
            @RequestParam UUID yearId,
            @RequestBody FinanceBudgetDimensionsInput.BudgetVersionCreateRequest req) {
        try {
            UUID versionId = service.createBudgetVersion(tenantId, legalEntityId, bookId, yearId, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("versionId", versionId, "status", "DRAFT"));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/budget-version/approval")
    public ResponseEntity<Map<String, Object>> approveBudgetVersion(
            @RequestBody FinanceBudgetDimensionsInput.BudgetVersionApprovalRequest req) {
        try {
            service.approveBudgetVersion(req);
            return ResponseEntity.ok(Map.of("versionId", req.versionId(), "status", req.decision()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/budget-adjustment")
    public ResponseEntity<Map<String, Object>> createBudgetAdjustment(
            @RequestParam UUID tenantId,
            @RequestParam UUID bookId,
            @RequestParam UUID yearId,
            @RequestBody FinanceBudgetDimensionsInput.BudgetAdjustmentRequest req) {
        try {
            UUID adjId = service.createBudgetAdjustment(tenantId, bookId, yearId, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("adjustmentId", adjId, "status", "APPROVED"));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // -------------------------------------------------------------------------
    // BD02: Budget Control, Reservation, Consumption & Forecast
    // -------------------------------------------------------------------------

    @PostMapping("/budget/reserve")
    public ResponseEntity<Map<String, Object>> reserveBudget(
            @RequestParam UUID tenantId,
            @RequestParam UUID bookId,
            @RequestParam UUID yearId,
            @RequestBody FinanceBudgetDimensionsInput.BudgetReservationRequest req) {
        try {
            UUID txId = service.reserveBudget(tenantId, bookId, yearId, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("transactionId", txId, "status", "RESERVED"));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/budget/consume")
    public ResponseEntity<Map<String, Object>> consumeBudget(
            @RequestParam UUID tenantId,
            @RequestParam UUID bookId,
            @RequestParam UUID yearId,
            @RequestBody FinanceBudgetDimensionsInput.BudgetConsumptionRequest req) {
        try {
            UUID txId = service.consumeBudget(tenantId, bookId, yearId, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("transactionId", txId, "status", "CONSUMED"));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/budget/release")
    public ResponseEntity<Map<String, Object>> releaseBudget(
            @RequestParam UUID tenantId,
            @RequestParam UUID bookId,
            @RequestParam UUID yearId,
            @RequestBody FinanceBudgetDimensionsInput.BudgetReleaseRequest req) {
        try {
            UUID txId = service.releaseBudget(tenantId, bookId, yearId, req);
            return ResponseEntity.ok(Map.of("transactionId", txId, "status", "RELEASED"));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/budget/rollback-consume")
    public ResponseEntity<Map<String, Object>> rollbackConsume(
            @RequestParam UUID tenantId,
            @RequestParam UUID bookId,
            @RequestParam UUID yearId,
            @RequestBody FinanceBudgetDimensionsInput.BudgetRollbackConsumptionRequest req) {
        try {
            UUID txId = service.rollbackConsumeBudget(tenantId, bookId, yearId, req);
            return ResponseEntity.ok(Map.of("transactionId", txId, "status", "ROLLBACK_CONSUMED"));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/budget/control-ledger")
    public ResponseEntity<FinanceBudgetDimensionsInput.BudgetControlLedgerDto> getControlLedger(
            @RequestParam UUID tenantId,
            @RequestParam UUID bookId,
            @RequestParam UUID yearId,
            @RequestParam int period,
            @RequestParam String accountCode,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID costCenterId) throws SQLException {
        Optional<FinanceBudgetDimensionsInput.BudgetControlLedgerDto> opt =
                service.findControlLedger(tenantId, bookId, yearId, period, accountCode, departmentId, projectId, costCenterId);
        return opt.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/budget/forecast-snapshot")
    public ResponseEntity<Map<String, Object>> captureForecastSnapshot(
            @RequestParam UUID tenantId,
            @RequestParam UUID bookId,
            @RequestParam UUID yearId,
            @RequestBody FinanceBudgetDimensionsInput.BudgetForecastSnapshotRequest req) {
        try {
            UUID snapshotId = service.captureForecastSnapshot(tenantId, bookId, yearId, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("snapshotId", snapshotId, "status", "SNAPSHOT_CAPTURED"));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }
}
