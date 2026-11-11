package com.lumira.finance.budget;

import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * JDBC persistence store for Dimensions & Budget Control (Lane W03).
 * Operates directly against PostgreSQL 18 with explicit types and parameter binding.
 */
@Repository
public class FinanceBudgetDimensionsStore {

    public void initSchema(Connection conn, String ddlScript) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(ddlScript);
        }
    }

    // -------------------------------------------------------------------------
    // MD01: Account Rule & Deactivation
    // -------------------------------------------------------------------------

    public void saveAccountRule(Connection conn, FinanceBudgetDimensionsInput.AccountRuleDto r) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_dim_account_rule (
                tenant_id, book_id, year_id, account_id, account_code, standard_version,
                require_department, require_project, require_counterparty, require_employee,
                require_contract, require_cost_center, currency_policy, status, version, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, book_id, year_id, account_id) DO UPDATE SET
                account_code = EXCLUDED.account_code,
                standard_version = EXCLUDED.standard_version,
                require_department = EXCLUDED.require_department,
                require_project = EXCLUDED.require_project,
                require_counterparty = EXCLUDED.require_counterparty,
                require_employee = EXCLUDED.require_employee,
                require_contract = EXCLUDED.require_contract,
                require_cost_center = EXCLUDED.require_cost_center,
                currency_policy = EXCLUDED.currency_policy,
                status = EXCLUDED.status,
                version = plugin_finance_dim_account_rule.version + 1,
                updated_at = CURRENT_TIMESTAMP
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, r.tenantId());
            ps.setObject(2, r.bookId());
            ps.setObject(3, r.yearId());
            ps.setObject(4, r.accountId());
            ps.setString(5, r.accountCode());
            ps.setString(6, r.standardVersion());
            ps.setBoolean(7, r.requireDepartment());
            ps.setBoolean(8, r.requireProject());
            ps.setBoolean(9, r.requireCounterparty());
            ps.setBoolean(10, r.requireEmployee());
            ps.setBoolean(11, r.requireContract());
            ps.setBoolean(12, r.requireCostCenter());
            ps.setString(13, r.currencyPolicy());
            ps.setString(14, r.status());
            ps.setLong(15, r.version());
            ps.executeUpdate();
        }
    }

    public Optional<FinanceBudgetDimensionsInput.AccountRuleDto> getAccountRule(
            Connection conn, UUID tenantId, UUID bookId, UUID yearId, UUID accountId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_dim_account_rule WHERE tenant_id = ? AND book_id = ? AND year_id = ? AND account_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, yearId);
            ps.setObject(4, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new FinanceBudgetDimensionsInput.AccountRuleDto(
                            (UUID) rs.getObject("tenant_id"),
                            (UUID) rs.getObject("book_id"),
                            (UUID) rs.getObject("year_id"),
                            (UUID) rs.getObject("account_id"),
                            rs.getString("account_code"),
                            rs.getString("standard_version"),
                            rs.getBoolean("require_department"),
                            rs.getBoolean("require_project"),
                            rs.getBoolean("require_counterparty"),
                            rs.getBoolean("require_employee"),
                            rs.getBoolean("require_contract"),
                            rs.getBoolean("require_cost_center"),
                            rs.getString("currency_policy"),
                            rs.getString("status"),
                            rs.getLong("version"),
                            rs.getObject("updated_at", OffsetDateTime.class)
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public UUID createAccountApproval(
            Connection conn, UUID tenantId, UUID bookId, UUID yearId, UUID accountId,
            String proposalType, String proposedStatus, UUID makerPerson, UUID makerAccount, String reason) throws SQLException {
        UUID approvalId = UUID.randomUUID();
        String sql = """
            INSERT INTO plugin_finance_dim_account_approval (
                id, tenant_id, book_id, year_id, account_id, proposal_type, proposed_status,
                maker_person, maker_account, status, decision_reason, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, approvalId);
            ps.setObject(2, tenantId);
            ps.setObject(3, bookId);
            ps.setObject(4, yearId);
            ps.setObject(5, accountId);
            ps.setString(6, proposalType);
            ps.setString(7, proposedStatus);
            ps.setObject(8, makerPerson);
            ps.setObject(9, makerAccount);
            ps.setString(10, reason);
            ps.executeUpdate();
        }

        if ("DEACTIVATE".equals(proposalType)) {
            String pendingSql = "UPDATE plugin_finance_dim_account_rule SET status = 'DEACTIVATION_PENDING', updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND book_id = ? AND year_id = ? AND account_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(pendingSql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, bookId);
                ps.setObject(3, yearId);
                ps.setObject(4, accountId);
                ps.executeUpdate();
            }
        }

        return approvalId;
    }

    public void decideAccountApproval(
            Connection conn, UUID approvalId, UUID reviewerPerson, UUID reviewerAccount,
            String decision, String reason) throws SQLException {
        String selectSql = "SELECT maker_person, tenant_id, book_id, year_id, account_id, proposal_type, proposed_status FROM plugin_finance_dim_account_approval WHERE id = ? FOR UPDATE";
        UUID makerPerson = null;
        UUID tId = null, bId = null, yId = null, aId = null;
        String proposalType = null;
        String proposedStatus = null;
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setObject(1, approvalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("Account approval not found: " + approvalId);
                makerPerson = (UUID) rs.getObject("maker_person");
                tId = (UUID) rs.getObject("tenant_id");
                bId = (UUID) rs.getObject("book_id");
                yId = (UUID) rs.getObject("year_id");
                aId = (UUID) rs.getObject("account_id");
                proposalType = rs.getString("proposal_type");
                proposedStatus = rs.getString("proposed_status");
            }
        }
        FinanceBudgetDimensionsRules.validateSoD(makerPerson, reviewerPerson);

        String resolvedStatus = "APPROVE".equalsIgnoreCase(decision) || "APPROVED".equalsIgnoreCase(decision)
                ? "APPROVED" : ("REJECT".equalsIgnoreCase(decision) || "REJECTED".equalsIgnoreCase(decision) ? "REJECTED" : decision);
        String updateSql = """
            UPDATE plugin_finance_dim_account_approval
            SET reviewer_person = ?, reviewer_account = ?, status = ?, decision_reason = ?, decided_at = CURRENT_TIMESTAMP
            WHERE id = ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setObject(1, reviewerPerson);
            ps.setObject(2, reviewerAccount);
            ps.setString(3, resolvedStatus);
            ps.setString(4, reason);
            ps.setObject(5, approvalId);
            ps.executeUpdate();
        }

        if ("APPROVED".equals(resolvedStatus)) {
            String ruleSql = "UPDATE plugin_finance_dim_account_rule SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND book_id = ? AND year_id = ? AND account_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(ruleSql)) {
                ps.setString(1, proposedStatus);
                ps.setObject(2, tId);
                ps.setObject(3, bId);
                ps.setObject(4, yId);
                ps.setObject(5, aId);
                ps.executeUpdate();
            }
        } else if ("REJECTED".equals(resolvedStatus) && "DEACTIVATE".equals(proposalType)) {
            String ruleSql = "UPDATE plugin_finance_dim_account_rule SET status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND book_id = ? AND year_id = ? AND account_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(ruleSql)) {
                ps.setObject(1, tId);
                ps.setObject(2, bId);
                ps.setObject(3, yId);
                ps.setObject(4, aId);
                ps.executeUpdate();
            }
        }
    }

    // -------------------------------------------------------------------------
    // MD02: Counterparty Bank Account, Bank Review, Identity History, Contract
    // -------------------------------------------------------------------------

    public void saveCounterpartyBankAccount(Connection conn, FinanceBudgetDimensionsInput.CounterpartyBankAccountDto b) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_counterparty_bank_account (
                id, tenant_id, legal_entity_id, counterparty_id, bank_name, branch_name,
                bank_account_no, currency, purpose, is_default, status, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, legal_entity_id, counterparty_id, bank_account_no) DO UPDATE SET
                bank_name = EXCLUDED.bank_name,
                branch_name = EXCLUDED.branch_name,
                purpose = EXCLUDED.purpose,
                is_default = EXCLUDED.is_default,
                status = EXCLUDED.status,
                version = plugin_finance_counterparty_bank_account.version + 1,
                updated_at = CURRENT_TIMESTAMP
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, b.id());
            ps.setObject(2, b.tenantId());
            ps.setObject(3, b.legalEntityId());
            ps.setObject(4, b.counterpartyId());
            ps.setString(5, b.bankName());
            ps.setString(6, b.branchName());
            ps.setString(7, b.bankAccountNo());
            ps.setString(8, b.currency());
            ps.setString(9, b.purpose());
            ps.setBoolean(10, b.isDefault());
            ps.setString(11, b.status());
            ps.setLong(12, b.version());
            ps.executeUpdate();
        }
    }

    public UUID createBankAccountReview(
            Connection conn, UUID tenantId, UUID legalEntityId, UUID counterpartyId,
            UUID bankAccountId, String changeType, String payloadJson, UUID makerPerson, String comment) throws SQLException {
        UUID reviewId = UUID.randomUUID();
        String sql = """
            INSERT INTO plugin_finance_counterparty_bank_review (
                id, tenant_id, legal_entity_id, counterparty_id, bank_account_id,
                change_type, proposed_payload, maker_person, status, review_comment, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', ?, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, reviewId);
            ps.setObject(2, tenantId);
            ps.setObject(3, legalEntityId);
            ps.setObject(4, counterpartyId);
            ps.setObject(5, bankAccountId);
            ps.setString(6, changeType);
            ps.setString(7, payloadJson);
            ps.setObject(8, makerPerson);
            ps.setString(9, comment);
            ps.executeUpdate();
        }
        return reviewId;
    }

    public void decideBankAccountReview(
            Connection conn, UUID reviewId, UUID reviewerPerson, String decision, String comment) throws SQLException {
        String selectSql = "SELECT maker_person, bank_account_id, change_type FROM plugin_finance_counterparty_bank_review WHERE id = ? FOR UPDATE";
        UUID makerPerson = null;
        UUID bankAccountId = null;
        String changeType = null;
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setObject(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("Bank review not found: " + reviewId);
                makerPerson = (UUID) rs.getObject("maker_person");
                bankAccountId = (UUID) rs.getObject("bank_account_id");
                changeType = rs.getString("change_type");
            }
        }
        FinanceBudgetDimensionsRules.validateSoD(makerPerson, reviewerPerson);

        String resolvedStatus = "APPROVE".equalsIgnoreCase(decision) || "APPROVED".equalsIgnoreCase(decision)
                ? "APPROVED" : ("REJECT".equalsIgnoreCase(decision) || "REJECTED".equalsIgnoreCase(decision) ? "REJECTED" : decision);

        String updateSql = """
            UPDATE plugin_finance_counterparty_bank_review
            SET reviewer_person = ?, status = ?, review_comment = ?, decided_at = CURRENT_TIMESTAMP
            WHERE id = ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setObject(1, reviewerPerson);
            ps.setString(2, resolvedStatus);
            ps.setString(3, comment);
            ps.setObject(4, reviewId);
            ps.executeUpdate();
        }

        if ("APPROVED".equals(resolvedStatus)) {
            if ("DEACTIVATE".equals(changeType)) {
                String accSql = "UPDATE plugin_finance_counterparty_bank_account SET status = 'INACTIVE', updated_at = CURRENT_TIMESTAMP WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(accSql)) {
                    ps.setObject(1, bankAccountId);
                    ps.executeUpdate();
                }
            } else if ("CREATE".equals(changeType)) {
                String accSql = "UPDATE plugin_finance_counterparty_bank_account SET status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(accSql)) {
                    ps.setObject(1, bankAccountId);
                    ps.executeUpdate();
                }
            }
        }
    }

    public void recordIdentityHistory(Connection conn, FinanceBudgetDimensionsInput.CounterpartyIdentityHistoryDto h) throws SQLException {
        FinanceBudgetDimensionsRules.validateSoD(h.makerPerson(), h.approverPerson());
        String sql = """
            INSERT INTO plugin_finance_counterparty_identity_history (
                id, tenant_id, legal_entity_id, counterparty_id, change_seq,
                old_display_name, new_display_name, old_identifier, new_identifier,
                reason, evidence_original_id, evidence_hash, maker_person, approver_person, approved_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, h.id());
            ps.setObject(2, h.tenantId());
            ps.setObject(3, h.legalEntityId());
            ps.setObject(4, h.counterpartyId());
            ps.setLong(5, h.changeSeq());
            ps.setString(6, h.oldDisplayName());
            ps.setString(7, h.newDisplayName());
            ps.setString(8, h.oldIdentifier());
            ps.setString(9, h.newIdentifier());
            ps.setString(10, h.reason());
            ps.setObject(11, h.evidenceOriginalId());
            ps.setString(12, h.evidenceHash());
            ps.setObject(13, h.makerPerson());
            ps.setObject(14, h.approverPerson());
            ps.executeUpdate();
        }
    }

    public void saveFrameworkContract(Connection conn, FinanceBudgetDimensionsInput.FrameworkContractDto c) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_dim_framework_contract (
                id, tenant_id, legal_entity_id, counterparty_id, contract_no, contract_name,
                settlement_terms, credit_days, penalty_rate_annual, valid_from, valid_to, status, version, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, legal_entity_id, counterparty_id, contract_no) DO UPDATE SET
                contract_name = EXCLUDED.contract_name,
                settlement_terms = EXCLUDED.settlement_terms,
                credit_days = EXCLUDED.credit_days,
                penalty_rate_annual = EXCLUDED.penalty_rate_annual,
                valid_from = EXCLUDED.valid_from,
                valid_to = EXCLUDED.valid_to,
                status = EXCLUDED.status,
                version = plugin_finance_dim_framework_contract.version + 1
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, c.id());
            ps.setObject(2, c.tenantId());
            ps.setObject(3, c.legalEntityId());
            ps.setObject(4, c.counterpartyId());
            ps.setString(5, c.contractNo());
            ps.setString(6, c.contractName());
            ps.setString(7, c.settlementTerms());
            ps.setInt(8, c.creditDays());
            ps.setBigDecimal(9, new BigDecimal(c.penaltyRateAnnual()));
            ps.setObject(10, c.validFrom());
            ps.setObject(11, c.validTo());
            ps.setString(12, c.status());
            ps.setLong(13, c.version());
            ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------------------
    // MD03: Departments, Projects, Cost Centers, Contracts & Allocation Rules
    // -------------------------------------------------------------------------

    public void saveDepartment(Connection conn, FinanceBudgetDimensionsInput.DepartmentDto d) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_dim_department (
                id, tenant_id, book_id, code, name, parent_id, manager_person_id, valid_from, valid_to, status, version, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, book_id, code) DO UPDATE SET
                name = EXCLUDED.name,
                parent_id = EXCLUDED.parent_id,
                manager_person_id = EXCLUDED.manager_person_id,
                valid_from = EXCLUDED.valid_from,
                valid_to = EXCLUDED.valid_to,
                status = EXCLUDED.status,
                version = plugin_finance_dim_department.version + 1
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, d.id());
            ps.setObject(2, d.tenantId());
            ps.setObject(3, d.bookId());
            ps.setString(4, d.code());
            ps.setString(5, d.name());
            ps.setObject(6, d.parentId());
            ps.setObject(7, d.managerPersonId());
            ps.setObject(8, d.validFrom());
            ps.setObject(9, d.validTo());
            ps.setString(10, d.status());
            ps.setLong(11, d.version());
            ps.executeUpdate();
        }
    }

    public void saveProject(Connection conn, FinanceBudgetDimensionsInput.ProjectDto p) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_dim_project (
                id, tenant_id, book_id, code, name, department_id, manager_person_id, valid_from, valid_to, status, version, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, book_id, code) DO UPDATE SET
                name = EXCLUDED.name,
                department_id = EXCLUDED.department_id,
                manager_person_id = EXCLUDED.manager_person_id,
                valid_from = EXCLUDED.valid_from,
                valid_to = EXCLUDED.valid_to,
                status = EXCLUDED.status,
                version = plugin_finance_dim_project.version + 1
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, p.id());
            ps.setObject(2, p.tenantId());
            ps.setObject(3, p.bookId());
            ps.setString(4, p.code());
            ps.setString(5, p.name());
            ps.setObject(6, p.departmentId());
            ps.setObject(7, p.managerPersonId());
            ps.setObject(8, p.validFrom());
            ps.setObject(9, p.validTo());
            ps.setString(10, p.status());
            ps.setLong(11, p.version());
            ps.executeUpdate();
        }
    }

    public void saveCostCenter(Connection conn, FinanceBudgetDimensionsInput.CostCenterDto c) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_dim_cost_center (
                id, tenant_id, book_id, code, name, department_id, valid_from, valid_to, status, version, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, book_id, code) DO UPDATE SET
                name = EXCLUDED.name,
                department_id = EXCLUDED.department_id,
                valid_from = EXCLUDED.valid_from,
                valid_to = EXCLUDED.valid_to,
                status = EXCLUDED.status,
                version = plugin_finance_dim_cost_center.version + 1
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, c.id());
            ps.setObject(2, c.tenantId());
            ps.setObject(3, c.bookId());
            ps.setString(4, c.code());
            ps.setString(5, c.name());
            ps.setObject(6, c.departmentId());
            ps.setObject(7, c.validFrom());
            ps.setObject(8, c.validTo());
            ps.setString(9, c.status());
            ps.setLong(10, c.version());
            ps.executeUpdate();
        }
    }

    public void saveContractDimension(Connection conn, FinanceBudgetDimensionsInput.ContractDimensionDto c) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_dim_contract (
                id, tenant_id, book_id, code, name, counterparty_id, department_id, project_id,
                total_amount, valid_from, valid_to, status, version, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, book_id, code) DO UPDATE SET
                name = EXCLUDED.name,
                counterparty_id = EXCLUDED.counterparty_id,
                department_id = EXCLUDED.department_id,
                project_id = EXCLUDED.project_id,
                total_amount = EXCLUDED.total_amount,
                valid_from = EXCLUDED.valid_from,
                valid_to = EXCLUDED.valid_to,
                status = EXCLUDED.status,
                version = plugin_finance_dim_contract.version + 1
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, c.id());
            ps.setObject(2, c.tenantId());
            ps.setObject(3, c.bookId());
            ps.setString(4, c.code());
            ps.setString(5, c.name());
            ps.setObject(6, c.counterpartyId());
            ps.setObject(7, c.departmentId());
            ps.setObject(8, c.projectId());
            ps.setBigDecimal(9, new BigDecimal(c.totalAmount()));
            ps.setObject(10, c.validFrom());
            ps.setObject(11, c.validTo());
            ps.setString(12, c.status());
            ps.setLong(13, c.version());
            ps.executeUpdate();
        }
    }

    public UUID saveAllocationRule(
            Connection conn, UUID tenantId, UUID bookId,
            FinanceBudgetDimensionsInput.AllocationRuleRequest req) throws SQLException {
        UUID ruleId = UUID.randomUUID();
        String ruleSql = """
            INSERT INTO plugin_finance_dim_allocation_rule (
                id, tenant_id, book_id, rule_code, rule_name, source_cost_center_id,
                source_department_id, source_account_code, valid_from, valid_to, status, version, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(ruleSql)) {
            ps.setObject(1, ruleId);
            ps.setObject(2, tenantId);
            ps.setObject(3, bookId);
            ps.setString(4, req.ruleCode());
            ps.setString(5, req.ruleName());
            ps.setObject(6, req.sourceCostCenterId());
            ps.setObject(7, req.sourceDepartmentId());
            ps.setString(8, req.sourceAccountCode());
            ps.setObject(9, req.validFrom());
            ps.setObject(10, req.validTo());
            ps.executeUpdate();
        }

        String targetSql = """
            INSERT INTO plugin_finance_dim_allocation_target (
                id, rule_id, target_cost_center_id, target_department_id, target_project_id,
                ratio, priority_order, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(targetSql)) {
            for (FinanceBudgetDimensionsInput.AllocationTargetInput ti : req.targets()) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, ruleId);
                ps.setObject(3, ti.targetCostCenterId());
                ps.setObject(4, ti.targetDepartmentId());
                ps.setObject(5, ti.targetProjectId());
                ps.setBigDecimal(6, new BigDecimal(ti.ratio()));
                ps.setInt(7, ti.priorityOrder());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        return ruleId;
    }

    public List<FinanceBudgetDimensionsRules.TargetRatio> listAllocationTargets(Connection conn, UUID ruleId) throws SQLException {
        String sql = "SELECT id, ratio, priority_order FROM plugin_finance_dim_allocation_target WHERE rule_id = ? ORDER BY priority_order, id";
        List<FinanceBudgetDimensionsRules.TargetRatio> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, ruleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new FinanceBudgetDimensionsRules.TargetRatio(
                            (UUID) rs.getObject("id"),
                            rs.getBigDecimal("ratio"),
                            rs.getInt("priority_order")
                    ));
                }
            }
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // BD01: Budget Formulation, Items & Adjustment
    // -------------------------------------------------------------------------

    public UUID createBudgetVersion(
            Connection conn, UUID tenantId, UUID legalEntityId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetVersionCreateRequest req) throws SQLException {
        UUID versionId = UUID.randomUUID();
        BigDecimal totalBudget = BigDecimal.ZERO;
        for (FinanceBudgetDimensionsInput.BudgetItemInput item : req.items()) {
            totalBudget = totalBudget.add(new BigDecimal(item.amount()));
        }

        String headerSql = """
            INSERT INTO plugin_finance_budget_version (
                id, tenant_id, legal_entity_id, book_id, year_id, version_code, version_type,
                status, total_budget, maker_person, reason, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(headerSql)) {
            ps.setObject(1, versionId);
            ps.setObject(2, tenantId);
            ps.setObject(3, legalEntityId);
            ps.setObject(4, bookId);
            ps.setObject(5, yearId);
            ps.setString(6, req.versionCode());
            ps.setString(7, req.versionType());
            ps.setBigDecimal(8, totalBudget);
            ps.setObject(9, req.makerPerson());
            ps.setString(10, req.reason());
            ps.executeUpdate();
        }

        String itemSql = """
            INSERT INTO plugin_finance_budget_item (
                id, budget_version_id, period, account_code, department_id, project_id,
                cost_center_id, counterparty_id, contract_id, amount, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(itemSql)) {
            for (FinanceBudgetDimensionsInput.BudgetItemInput it : req.items()) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, versionId);
                ps.setInt(3, it.period());
                ps.setString(4, it.accountCode());
                ps.setObject(5, it.departmentId());
                ps.setObject(6, it.projectId());
                ps.setObject(7, it.costCenterId());
                ps.setObject(8, it.counterpartyId());
                ps.setObject(9, it.contractId());
                ps.setBigDecimal(10, new BigDecimal(it.amount()));
                ps.addBatch();
            }
            ps.executeBatch();
        }

        return versionId;
    }

    public void approveBudgetVersion(
            Connection conn, UUID versionId, UUID reviewerPerson, String decision, String reason) throws SQLException {
        String selectSql = "SELECT maker_person, tenant_id, book_id, year_id FROM plugin_finance_budget_version WHERE id = ? FOR UPDATE";
        UUID makerPerson = null;
        UUID tId = null, bId = null, yId = null;
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setObject(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("Budget version not found: " + versionId);
                makerPerson = (UUID) rs.getObject("maker_person");
                tId = (UUID) rs.getObject("tenant_id");
                bId = (UUID) rs.getObject("book_id");
                yId = (UUID) rs.getObject("year_id");
            }
        }
        FinanceBudgetDimensionsRules.validateSoD(makerPerson, reviewerPerson);

        String resolvedStatus = "APPROVE".equalsIgnoreCase(decision) || "APPROVED".equalsIgnoreCase(decision)
                ? "APPROVED" : ("REJECT".equalsIgnoreCase(decision) || "REJECTED".equalsIgnoreCase(decision) ? "REJECTED" : decision);

        String updateSql = """
            UPDATE plugin_finance_budget_version
            SET reviewer_person = ?, status = ?, approved_at = CURRENT_TIMESTAMP
            WHERE id = ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setObject(1, reviewerPerson);
            ps.setString(2, resolvedStatus);
            ps.setObject(3, versionId);
            ps.executeUpdate();
        }

        // If approved, initialize or update budget control ledger
        if ("APPROVED".equals(resolvedStatus)) {
            String itemsSql = "SELECT period, account_code, department_id, project_id, cost_center_id, SUM(amount) as total_amt FROM plugin_finance_budget_item WHERE budget_version_id = ? AND period > 0 GROUP BY period, account_code, department_id, project_id, cost_center_id";
            try (PreparedStatement ps = conn.prepareStatement(itemsSql)) {
                ps.setObject(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int period = rs.getInt("period");
                        String accountCode = rs.getString("account_code");
                        UUID deptId = (UUID) rs.getObject("department_id");
                        UUID projId = (UUID) rs.getObject("project_id");
                        UUID ccId = (UUID) rs.getObject("cost_center_id");
                        BigDecimal amt = rs.getBigDecimal("total_amt");

                        upsertControlLedgerBudget(conn, tId, bId, yId, period, accountCode, deptId, projId, ccId, amt);
                    }
                }
            }
        }
    }

    public UUID createBudgetAdjustment(
            Connection conn, UUID tenantId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetAdjustmentRequest req) throws SQLException {
        FinanceBudgetDimensionsRules.validateSoD(req.makerPerson(), req.reviewerPerson());
        BigDecimal adjAmt = new BigDecimal(req.adjustmentAmount());
        FinanceBudgetDimensionsRules.validateAmount(adjAmt, "adjustment_amount");
        UUID adjId = UUID.randomUUID();
        String sql = """
            INSERT INTO plugin_finance_budget_adjustment (
                id, tenant_id, book_id, year_id, source_version_id, target_version_id,
                adjustment_type, adjustment_amount, justification, maker_person, reviewer_person, approved_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, adjId);
            ps.setObject(2, tenantId);
            ps.setObject(3, bookId);
            ps.setObject(4, yearId);
            ps.setObject(5, req.sourceVersionId());
            ps.setObject(6, req.targetVersionId());
            ps.setString(7, req.adjustmentType());
            ps.setBigDecimal(8, adjAmt);
            ps.setString(9, req.justification());
            ps.setObject(10, req.makerPerson());
            ps.setObject(11, req.reviewerPerson());
            ps.executeUpdate();
        }
        return adjId;
    }

    // -------------------------------------------------------------------------
    // BD02: Budget Control Ledger, Reservation, Consumption, Forecast
    // -------------------------------------------------------------------------

    public void upsertControlLedgerBudget(
            Connection conn, UUID tenantId, UUID bookId, UUID yearId,
            int period, String accountCode, UUID departmentId, UUID projectId, UUID costCenterId,
            BigDecimal budgetAmount) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_budget_control_ledger (
                id, tenant_id, book_id, year_id, period, account_code,
                department_id, project_id, cost_center_id, budget_amount, reserved_amount, consumed_amount, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, CURRENT_TIMESTAMP)
            ON CONFLICT (
                tenant_id, book_id, year_id, period, account_code,
                COALESCE(department_id, '00000000-0000-0000-0000-000000000000'::uuid),
                COALESCE(project_id, '00000000-0000-0000-0000-000000000000'::uuid),
                COALESCE(cost_center_id, '00000000-0000-0000-0000-000000000000'::uuid)
            ) DO UPDATE SET
                budget_amount = plugin_finance_budget_control_ledger.budget_amount + EXCLUDED.budget_amount,
                updated_at = CURRENT_TIMESTAMP
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenantId);
            ps.setObject(3, bookId);
            ps.setObject(4, yearId);
            ps.setInt(5, period);
            ps.setString(6, accountCode);
            ps.setObject(7, departmentId);
            ps.setObject(8, projectId);
            ps.setObject(9, costCenterId);
            ps.setBigDecimal(10, budgetAmount);
            ps.executeUpdate();
        }
    }

    public UUID reserveBudget(
            Connection conn, UUID tenantId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetReservationRequest req) throws SQLException {
        BigDecimal amt = new BigDecimal(req.amount());
        FinanceBudgetDimensionsRules.validateAmount(amt, "reservation amount");

        String selectSql = """
            SELECT id, budget_amount, reserved_amount, consumed_amount
            FROM plugin_finance_budget_control_ledger
            WHERE tenant_id = ? AND book_id = ? AND year_id = ? AND period = ? AND account_code = ?
              AND (department_id IS NOT DISTINCT FROM ?)
              AND (project_id IS NOT DISTINCT FROM ?)
              AND (cost_center_id IS NOT DISTINCT FROM ?)
            FOR UPDATE
        """;

        UUID ledgerId;
        BigDecimal budgetAmt, reservedAmt, consumedAmt;

        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, yearId);
            ps.setInt(4, req.period());
            ps.setString(5, req.accountCode());
            ps.setObject(6, req.departmentId());
            ps.setObject(7, req.projectId());
            ps.setObject(8, req.costCenterId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("BUDGET_NOT_FOUND for specified period and dimensions");
                ledgerId = (UUID) rs.getObject("id");
                budgetAmt = rs.getBigDecimal("budget_amount");
                reservedAmt = rs.getBigDecimal("reserved_amount");
                consumedAmt = rs.getBigDecimal("consumed_amount");
            }
        }

        BigDecimal available = FinanceBudgetDimensionsRules.computeAvailable(budgetAmt, reservedAmt, consumedAmt);
        if (amt.compareTo(available) > 0) {
            throw new IllegalStateException("BUDGET_EXCEEDED: requested " + amt.toPlainString() + " exceeds available " + available.toPlainString());
        }

        BigDecimal newReserved = reservedAmt.add(amt);
        String updateSql = "UPDATE plugin_finance_budget_control_ledger SET reserved_amount = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setBigDecimal(1, newReserved);
            ps.setObject(2, ledgerId);
            ps.executeUpdate();
        }

        UUID txId = UUID.randomUUID();
        String txSql = """
            INSERT INTO plugin_finance_budget_transaction (
                id, ledger_id, transaction_type, amount, reference_type, reference_id, actor_person, note, created_at
            ) VALUES (?, ?, 'RESERVE', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(txSql)) {
            ps.setObject(1, txId);
            ps.setObject(2, ledgerId);
            ps.setBigDecimal(3, amt);
            ps.setString(4, req.referenceType());
            ps.setObject(5, req.referenceId());
            ps.setObject(6, req.actorPerson());
            ps.setString(7, req.note());
            ps.executeUpdate();
        }

        return txId;
    }

    public UUID consumeBudget(
            Connection conn, UUID tenantId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetConsumptionRequest req) throws SQLException {
        BigDecimal amt = new BigDecimal(req.amount());
        FinanceBudgetDimensionsRules.validateAmount(amt, "consumption amount");

        String selectSql = """
            SELECT id, budget_amount, reserved_amount, consumed_amount
            FROM plugin_finance_budget_control_ledger
            WHERE tenant_id = ? AND book_id = ? AND year_id = ? AND period = ? AND account_code = ?
              AND (department_id IS NOT DISTINCT FROM ?)
              AND (project_id IS NOT DISTINCT FROM ?)
              AND (cost_center_id IS NOT DISTINCT FROM ?)
            FOR UPDATE
        """;

        UUID ledgerId;
        BigDecimal budgetAmt, reservedAmt, consumedAmt;

        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, yearId);
            ps.setInt(4, req.period());
            ps.setString(5, req.accountCode());
            ps.setObject(6, req.departmentId());
            ps.setObject(7, req.projectId());
            ps.setObject(8, req.costCenterId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("BUDGET_NOT_FOUND");
                ledgerId = (UUID) rs.getObject("id");
                budgetAmt = rs.getBigDecimal("budget_amount");
                reservedAmt = rs.getBigDecimal("reserved_amount");
                consumedAmt = rs.getBigDecimal("consumed_amount");
            }
        }

        BigDecimal newReserved = reservedAmt;
        BigDecimal newConsumed = consumedAmt.add(amt);

        if (req.fromReservation()) {
            if (amt.compareTo(reservedAmt) > 0) {
                throw new IllegalStateException("BUDGET_RESERVATION_INSUFFICIENT: consumed " + amt.toPlainString() + " exceeds reserved " + reservedAmt.toPlainString());
            }
            newReserved = reservedAmt.subtract(amt);
        } else {
            BigDecimal available = FinanceBudgetDimensionsRules.computeAvailable(budgetAmt, reservedAmt, consumedAmt);
            if (amt.compareTo(available) > 0) {
                throw new IllegalStateException("BUDGET_EXCEEDED: requested " + amt.toPlainString() + " exceeds available " + available.toPlainString());
            }
        }

        FinanceBudgetDimensionsRules.validateControlLedgerInvariant(budgetAmt, newReserved, newConsumed);

        String updateSql = "UPDATE plugin_finance_budget_control_ledger SET reserved_amount = ?, consumed_amount = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setBigDecimal(1, newReserved);
            ps.setBigDecimal(2, newConsumed);
            ps.setObject(3, ledgerId);
            ps.executeUpdate();
        }

        UUID txId = UUID.randomUUID();
        String txSql = """
            INSERT INTO plugin_finance_budget_transaction (
                id, ledger_id, transaction_type, amount, reference_type, reference_id, actor_person, note, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(txSql)) {
            ps.setObject(1, txId);
            ps.setObject(2, ledgerId);
            ps.setString(3, req.fromReservation() ? "CONSUME" : "DIRECT_CONSUME");
            ps.setBigDecimal(4, amt);
            ps.setString(5, req.referenceType());
            ps.setObject(6, req.referenceId());
            ps.setObject(7, req.actorPerson());
            ps.setString(8, req.note());
            ps.executeUpdate();
        }

        return txId;
    }

    public UUID releaseBudget(
            Connection conn, UUID tenantId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetReleaseRequest req) throws SQLException {
        BigDecimal amt = new BigDecimal(req.amount());
        FinanceBudgetDimensionsRules.validateAmount(amt, "release amount");

        String selectSql = """
            SELECT id, reserved_amount
            FROM plugin_finance_budget_control_ledger
            WHERE tenant_id = ? AND book_id = ? AND year_id = ? AND period = ? AND account_code = ?
              AND (department_id IS NOT DISTINCT FROM ?)
              AND (project_id IS NOT DISTINCT FROM ?)
              AND (cost_center_id IS NOT DISTINCT FROM ?)
            FOR UPDATE
        """;

        UUID ledgerId;
        BigDecimal reservedAmt;

        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, yearId);
            ps.setInt(4, req.period());
            ps.setString(5, req.accountCode());
            ps.setObject(6, req.departmentId());
            ps.setObject(7, req.projectId());
            ps.setObject(8, req.costCenterId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("BUDGET_NOT_FOUND");
                ledgerId = (UUID) rs.getObject("id");
                reservedAmt = rs.getBigDecimal("reserved_amount");
            }
        }

        if (amt.compareTo(reservedAmt) > 0) {
            throw new IllegalStateException("BUDGET_RELEASE_EXCEEDS_RESERVATION: release " + amt.toPlainString() + " exceeds reserved " + reservedAmt.toPlainString());
        }

        BigDecimal newReserved = reservedAmt.subtract(amt);
        String updateSql = "UPDATE plugin_finance_budget_control_ledger SET reserved_amount = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setBigDecimal(1, newReserved);
            ps.setObject(2, ledgerId);
            ps.executeUpdate();
        }

        UUID txId = UUID.randomUUID();
        String txSql = """
            INSERT INTO plugin_finance_budget_transaction (
                id, ledger_id, transaction_type, amount, reference_type, reference_id, actor_person, note, created_at
            ) VALUES (?, ?, 'RELEASE', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(txSql)) {
            ps.setObject(1, txId);
            ps.setObject(2, ledgerId);
            ps.setBigDecimal(3, amt);
            ps.setString(4, req.referenceType());
            ps.setObject(5, req.referenceId());
            ps.setObject(6, req.actorPerson());
            ps.setString(7, req.note());
            ps.executeUpdate();
        }

        return txId;
    }

    public UUID rollbackConsumeBudget(
            Connection conn, UUID tenantId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetRollbackConsumptionRequest req) throws SQLException {
        BigDecimal amt = new BigDecimal(req.amount());
        FinanceBudgetDimensionsRules.validateAmount(amt, "rollback amount");

        String selectSql = """
            SELECT id, budget_amount, reserved_amount, consumed_amount
            FROM plugin_finance_budget_control_ledger
            WHERE tenant_id = ? AND book_id = ? AND year_id = ? AND period = ? AND account_code = ?
              AND (department_id IS NOT DISTINCT FROM ?)
              AND (project_id IS NOT DISTINCT FROM ?)
              AND (cost_center_id IS NOT DISTINCT FROM ?)
            FOR UPDATE
        """;

        UUID ledgerId;
        BigDecimal budgetAmt, reservedAmt, consumedAmt;

        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, yearId);
            ps.setInt(4, req.period());
            ps.setString(5, req.accountCode());
            ps.setObject(6, req.departmentId());
            ps.setObject(7, req.projectId());
            ps.setObject(8, req.costCenterId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("BUDGET_NOT_FOUND");
                ledgerId = (UUID) rs.getObject("id");
                budgetAmt = rs.getBigDecimal("budget_amount");
                reservedAmt = rs.getBigDecimal("reserved_amount");
                consumedAmt = rs.getBigDecimal("consumed_amount");
            }
        }

        FinanceBudgetDimensionsRules.validateRollbackConsume(consumedAmt, amt);
        BigDecimal newConsumed = consumedAmt.subtract(amt);
        FinanceBudgetDimensionsRules.validateControlLedgerInvariant(budgetAmt, reservedAmt, newConsumed);

        String updateSql = "UPDATE plugin_finance_budget_control_ledger SET consumed_amount = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setBigDecimal(1, newConsumed);
            ps.setObject(2, ledgerId);
            ps.executeUpdate();
        }

        UUID txId = UUID.randomUUID();
        String txSql = """
            INSERT INTO plugin_finance_budget_transaction (
                id, ledger_id, transaction_type, amount, reference_type, reference_id, actor_person, note, created_at
            ) VALUES (?, ?, 'ROLLBACK_CONSUME', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(txSql)) {
            ps.setObject(1, txId);
            ps.setObject(2, ledgerId);
            ps.setBigDecimal(3, amt);
            ps.setString(4, req.referenceType());
            ps.setObject(5, req.referenceId());
            ps.setObject(6, req.actorPerson());
            ps.setString(7, req.note());
            ps.executeUpdate();
        }

        return txId;
    }

    public Optional<FinanceBudgetDimensionsInput.BudgetControlLedgerDto> getControlLedger(
            Connection conn, UUID tenantId, UUID bookId, UUID yearId, int period, String accountCode,
            UUID departmentId, UUID projectId, UUID costCenterId) throws SQLException {
        String sql = """
            SELECT id, tenant_id, book_id, year_id, period, account_code,
                   department_id, project_id, cost_center_id,
                   budget_amount, reserved_amount, consumed_amount, updated_at
            FROM plugin_finance_budget_control_ledger
            WHERE tenant_id = ? AND book_id = ? AND year_id = ? AND period = ? AND account_code = ?
              AND (department_id IS NOT DISTINCT FROM ?)
              AND (project_id IS NOT DISTINCT FROM ?)
              AND (cost_center_id IS NOT DISTINCT FROM ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, yearId);
            ps.setInt(4, period);
            ps.setString(5, accountCode);
            ps.setObject(6, departmentId);
            ps.setObject(7, projectId);
            ps.setObject(8, costCenterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal b = rs.getBigDecimal("budget_amount");
                    BigDecimal r = rs.getBigDecimal("reserved_amount");
                    BigDecimal c = rs.getBigDecimal("consumed_amount");
                    BigDecimal a = FinanceBudgetDimensionsRules.computeAvailable(b, r, c);
                    return Optional.of(new FinanceBudgetDimensionsInput.BudgetControlLedgerDto(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("tenant_id"),
                            (UUID) rs.getObject("book_id"),
                            (UUID) rs.getObject("year_id"),
                            rs.getInt("period"),
                            rs.getString("account_code"),
                            (UUID) rs.getObject("department_id"),
                            (UUID) rs.getObject("project_id"),
                            (UUID) rs.getObject("cost_center_id"),
                            b.toPlainString(),
                            r.toPlainString(),
                            c.toPlainString(),
                            a.toPlainString(),
                            rs.getObject("updated_at", OffsetDateTime.class)
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public BigDecimal getYtdConsumed(Connection conn, UUID tenantId, UUID bookId, UUID yearId, int asOfPeriod) throws SQLException {
        String sql = """
            SELECT COALESCE(SUM(consumed_amount), 0) as ytd
            FROM plugin_finance_budget_control_ledger
            WHERE tenant_id = ? AND book_id = ? AND year_id = ? AND period <= ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, yearId);
            ps.setInt(4, asOfPeriod);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getBigDecimal("ytd");
            }
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal getBaselineTotalBudget(Connection conn, UUID baselineVersionId) throws SQLException {
        String sql = "SELECT total_budget FROM plugin_finance_budget_version WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, baselineVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getBigDecimal("total_budget");
            }
        }
        return BigDecimal.ZERO;
    }

    public UUID saveForecastSnapshot(
            Connection conn, UUID tenantId, UUID bookId, UUID yearId,
            FinanceBudgetDimensionsInput.BudgetForecastSnapshotRequest req,
            BigDecimal ytdConsumed, BigDecimal baselineBudget) throws SQLException {
        BigDecimal forecastedRem = new BigDecimal(req.forecastedRemaining());
        BigDecimal forecastTotal = FinanceBudgetDimensionsRules.computeForecastTotal(ytdConsumed, forecastedRem);
        BigDecimal projectedVariance = FinanceBudgetDimensionsRules.computeProjectedVariance(forecastTotal, baselineBudget);
        String hash = FinanceBudgetDimensionsRules.computeForecastSnapshotHash(
                tenantId, bookId, yearId, req.asOfPeriod(), req.baselineBudgetVersionId(),
                ytdConsumed, forecastedRem, forecastTotal, projectedVariance);

        UUID snapshotId = UUID.randomUUID();
        String sql = """
            INSERT INTO plugin_finance_budget_forecast_snapshot (
                id, tenant_id, book_id, year_id, as_of_period, baseline_budget_version_id,
                ytd_consumed, forecasted_remaining, forecast_total, projected_variance,
                snapshot_hash, created_by_person, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, snapshotId);
            ps.setObject(2, tenantId);
            ps.setObject(3, bookId);
            ps.setObject(4, yearId);
            ps.setInt(5, req.asOfPeriod());
            ps.setObject(6, req.baselineBudgetVersionId());
            ps.setBigDecimal(7, ytdConsumed);
            ps.setBigDecimal(8, forecastedRem);
            ps.setBigDecimal(9, forecastTotal);
            ps.setBigDecimal(10, projectedVariance);
            ps.setString(11, hash);
            ps.setObject(12, req.createdByPerson());
            ps.executeUpdate();
        }

        return snapshotId;
    }
}
