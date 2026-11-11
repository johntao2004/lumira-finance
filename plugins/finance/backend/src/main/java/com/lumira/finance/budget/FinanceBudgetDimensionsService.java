package com.lumira.finance.budget;

import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Transactional domain service for Dimensions and Budget Control (Lane W03).
 */
@Service
public class FinanceBudgetDimensionsService {

    private final DataSource dataSource;
    private final FinanceBudgetDimensionsStore store;

    public FinanceBudgetDimensionsService(DataSource dataSource, FinanceBudgetDimensionsStore store) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource required");
        this.store = Objects.requireNonNull(store, "store required");
    }

    public FinanceBudgetDimensionsStore getStore() {
        return store;
    }

    // -------------------------------------------------------------------------
    // MD01: Account Dimension Rules & Approval
    // -------------------------------------------------------------------------

    public void configureAccountRule(FinanceBudgetDimensionsInput.AccountRuleDto rule) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.saveAccountRule(c, rule);
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public Optional<FinanceBudgetDimensionsInput.AccountRuleDto> findAccountRule(
            UUID tenantId, UUID bookId, UUID yearId, UUID accountId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return store.getAccountRule(c, tenantId, bookId, yearId, accountId);
        }
    }

    public UUID submitAccountDeactivation(
            UUID tenantId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.AccountDeactivationRequest req) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID id = store.createAccountApproval(
                        c, tenantId, bookId, yearId, req.accountId(),
                        req.proposalType(), req.proposedStatus(), req.makerPerson(), req.makerAccount(), req.reason());
                c.commit();
                return id;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public void reviewAccountApproval(
            FinanceBudgetDimensionsInput.AccountApprovalDecision decision) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.decideAccountApproval(
                        c, decision.approvalId(), decision.reviewerPerson(),
                        decision.reviewerAccount(), decision.decision(), decision.decisionReason());
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    // -------------------------------------------------------------------------
    // MD02: Counterparty Bank Account & Identity
    // -------------------------------------------------------------------------

    public void registerCounterpartyBankAccount(FinanceBudgetDimensionsInput.CounterpartyBankAccountDto dto) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.saveCounterpartyBankAccount(c, dto);
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public UUID submitBankAccountReview(
            UUID tenantId, UUID legalEntityId, UUID counterpartyId,
            FinanceBudgetDimensionsInput.BankAccountReviewRequest req) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID id = store.createBankAccountReview(
                        c, tenantId, legalEntityId, counterpartyId, req.bankAccountId(),
                        req.changeType(), req.proposedPayload(), req.makerPerson(), req.reviewComment());
                c.commit();
                return id;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public void reviewBankAccount(FinanceBudgetDimensionsInput.BankAccountReviewDecision decision) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.decideBankAccountReview(
                        c, decision.reviewId(), decision.reviewerPerson(),
                        decision.decision(), decision.reviewComment());
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public void appendIdentityHistory(FinanceBudgetDimensionsInput.CounterpartyIdentityHistoryDto history) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.recordIdentityHistory(c, history);
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public void registerFrameworkContract(FinanceBudgetDimensionsInput.FrameworkContractDto contract) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.saveFrameworkContract(c, contract);
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    // -------------------------------------------------------------------------
    // MD03: Master Data Dimensions & Cost Allocation
    // -------------------------------------------------------------------------

    public void registerDepartment(FinanceBudgetDimensionsInput.DepartmentDto dept) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.saveDepartment(c, dept);
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public void registerProject(FinanceBudgetDimensionsInput.ProjectDto proj) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.saveProject(c, proj);
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public void registerCostCenter(FinanceBudgetDimensionsInput.CostCenterDto cc) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.saveCostCenter(c, cc);
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public void registerContractDimension(FinanceBudgetDimensionsInput.ContractDimensionDto contract) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.saveContractDimension(c, contract);
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public UUID createAllocationRule(
            UUID tenantId, UUID bookId,
            FinanceBudgetDimensionsInput.AllocationRuleRequest req) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID ruleId = store.saveAllocationRule(c, tenantId, bookId, req);
                c.commit();
                return ruleId;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public FinanceBudgetDimensionsInput.AllocationExecutionResult executeAllocation(
            FinanceBudgetDimensionsInput.AllocationExecutionRequest req) throws SQLException {
        List<FinanceBudgetDimensionsRules.TargetRatio> targets;
        try (Connection c = dataSource.getConnection()) {
            targets = store.listAllocationTargets(c, req.ruleId());
        }

        BigDecimal totalAmount = new BigDecimal(req.totalAmount());
        List<FinanceBudgetDimensionsRules.AllocatedTarget> allocated =
                FinanceBudgetDimensionsRules.allocate(totalAmount, targets, req.scale());

        List<FinanceBudgetDimensionsInput.AllocatedItemResult> items = new ArrayList<>(allocated.size());
        for (FinanceBudgetDimensionsRules.AllocatedTarget at : allocated) {
            items.add(new FinanceBudgetDimensionsInput.AllocatedItemResult(
                    at.targetId(),
                    at.ratio().toPlainString(),
                    at.allocatedAmount().toPlainString()
            ));
        }

        return new FinanceBudgetDimensionsInput.AllocationExecutionResult(
                req.ruleId(),
                totalAmount.toPlainString(),
                req.scale(),
                Collections.unmodifiableList(items)
        );
    }

    // -------------------------------------------------------------------------
    // BD01: Budget Formulation, Approval & Adjustment
    // -------------------------------------------------------------------------

    public UUID createBudgetVersion(
            UUID tenantId, UUID legalEntityId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetVersionCreateRequest req) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID id = store.createBudgetVersion(c, tenantId, legalEntityId, bookId, yearId, req);
                c.commit();
                return id;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public void approveBudgetVersion(
            FinanceBudgetDimensionsInput.BudgetVersionApprovalRequest req) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.approveBudgetVersion(c, req.versionId(), req.reviewerPerson(), req.decision(), req.reason());
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public UUID createBudgetAdjustment(
            UUID tenantId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetAdjustmentRequest req) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID id = store.createBudgetAdjustment(c, tenantId, bookId, yearId, req);
                c.commit();
                return id;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    // -------------------------------------------------------------------------
    // BD02: Budget Control, Reservation, Consumption & Forecast
    // -------------------------------------------------------------------------

    public UUID reserveBudget(
            UUID tenantId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetReservationRequest req) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID txId = store.reserveBudget(c, tenantId, bookId, yearId, req);
                c.commit();
                return txId;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public UUID consumeBudget(
            UUID tenantId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetConsumptionRequest req) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID txId = store.consumeBudget(c, tenantId, bookId, yearId, req);
                c.commit();
                return txId;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public UUID releaseBudget(
            UUID tenantId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetReleaseRequest req) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID txId = store.releaseBudget(c, tenantId, bookId, yearId, req);
                c.commit();
                return txId;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public UUID rollbackConsumeBudget(
            UUID tenantId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetRollbackConsumptionRequest req) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID txId = store.rollbackConsumeBudget(c, tenantId, bookId, yearId, req);
                c.commit();
                return txId;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public Optional<FinanceBudgetDimensionsInput.BudgetControlLedgerDto> findControlLedger(
            UUID tenantId, UUID bookId, UUID yearId, int period, String accountCode,
            UUID departmentId, UUID projectId, UUID costCenterId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return store.getControlLedger(c, tenantId, bookId, yearId, period, accountCode, departmentId, projectId, costCenterId);
        }
    }

    public UUID captureForecastSnapshot(
            UUID tenantId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetForecastSnapshotRequest req) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                BigDecimal ytdConsumed = store.getYtdConsumed(c, tenantId, bookId, yearId, req.asOfPeriod());
                BigDecimal baseline = store.getBaselineTotalBudget(c, req.baselineBudgetVersionId());
                UUID snapshotId = store.saveForecastSnapshot(c, tenantId, bookId, yearId, req, ytdConsumed, baseline);
                c.commit();
                return snapshotId;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }
}
