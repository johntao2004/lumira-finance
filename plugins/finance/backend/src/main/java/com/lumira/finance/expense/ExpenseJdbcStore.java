package com.lumira.finance.expense;

import com.lumira.finance.expense.ExpenseDomain.*;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * JDBC data access store for EXP01-EXP04 tables.
 */
@Repository
public final class ExpenseJdbcStore {

    public void setSessionContext(Connection conn, UUID tenantId, UUID employeeId, boolean isFinanceReviewer) throws SQLException {
        if (tenantId != null) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, tenantId.toString());
                ps.execute();
            }
        }
        if (employeeId != null) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.current_employee_id', ?, true)")) {
                ps.setString(1, employeeId.toString());
                ps.execute();
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.is_finance_reviewer', ?, true)")) {
            ps.setString(1, isFinanceReviewer ? "true" : "false");
            ps.execute();
        }
    }

    // --- Travel Policy & Caps (EXP04) ---

    public void insertTravelPolicy(Connection conn, TravelPolicy policy) throws SQLException {
        String sql = "INSERT INTO plugin_finance_travel_policy (id, tenant_id, policy_code, version_int, name, effective_from, effective_to, status, created_at, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, policy.id());
            ps.setObject(2, policy.tenantId());
            ps.setString(3, policy.policyCode());
            ps.setInt(4, policy.versionInt());
            ps.setString(5, policy.name());
            ps.setDate(6, java.sql.Date.valueOf(policy.effectiveFrom()));
            ps.setDate(7, policy.effectiveTo() != null ? java.sql.Date.valueOf(policy.effectiveTo()) : null);
            ps.setString(8, policy.status().name());
            ps.setObject(9, policy.createdAt());
            ps.setObject(10, policy.createdBy());
            ps.executeUpdate();
        }
    }

    public void insertTravelPolicyCap(Connection conn, TravelPolicyCap cap) throws SQLException {
        String sql = "INSERT INTO plugin_finance_travel_policy_cap (id, policy_id, city_tier, employee_rank, expense_category, daily_cap_amount, currency) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, cap.id());
            ps.setObject(2, cap.policyId());
            ps.setString(3, cap.cityTier().name());
            ps.setString(4, cap.employeeRank().name());
            ps.setString(5, cap.expenseCategory().name());
            ps.setBigDecimal(6, cap.dailyCapAmount());
            ps.setString(7, cap.currency());
            ps.executeUpdate();
        }
    }

    public void updateTravelPolicyStatus(Connection conn, UUID policyId, PolicyStatus status) throws SQLException {
        String sql = "UPDATE plugin_finance_travel_policy SET status = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setObject(2, policyId);
            ps.executeUpdate();
        }
    }

    public Optional<TravelPolicy> findTravelPolicyById(Connection conn, UUID id) throws SQLException {
        String sql = "SELECT id, tenant_id, policy_code, version_int, name, effective_from, effective_to, status, created_at, created_by " +
                "FROM plugin_finance_travel_policy WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapTravelPolicy(rs));
            }
        }
        return Optional.empty();
    }

    public List<TravelPolicyCap> listCapsByPolicyId(Connection conn, UUID policyId) throws SQLException {
        String sql = "SELECT id, policy_id, city_tier, employee_rank, expense_category, daily_cap_amount, currency " +
                "FROM plugin_finance_travel_policy_cap WHERE policy_id = ?";
        List<TravelPolicyCap> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, policyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new TravelPolicyCap(
                            rs.getObject("id", UUID.class),
                            rs.getObject("policy_id", UUID.class),
                            CityTier.valueOf(rs.getString("city_tier")),
                            EmployeeRank.valueOf(rs.getString("employee_rank")),
                            ExpenseCategory.valueOf(rs.getString("expense_category")),
                            rs.getBigDecimal("daily_cap_amount"),
                            rs.getString("currency")
                    ));
                }
            }
        }
        return list;
    }

    public void insertTravelException(Connection conn, TravelException ex) throws SQLException {
        String sql = "INSERT INTO plugin_finance_travel_exception (id, tenant_id, claim_id, line_no, policy_cap_id, requested_amount, " +
                "standard_cap_amount, excess_amount, applicant_person_id, approver_person_id, status, justification, decision_notes, decided_at, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, ex.id());
            ps.setObject(2, ex.tenantId());
            ps.setObject(3, ex.claimId());
            ps.setInt(4, ex.lineNo());
            ps.setObject(5, ex.policyCapId());
            ps.setBigDecimal(6, ex.requestedAmount());
            ps.setBigDecimal(7, ex.standardCapAmount());
            ps.setBigDecimal(8, ex.excessAmount());
            ps.setObject(9, ex.applicantPersonId());
            ps.setObject(10, ex.approverPersonId());
            ps.setString(11, ex.status().name());
            ps.setString(12, ex.justification());
            ps.setString(13, ex.decisionNotes());
            ps.setObject(14, ex.decidedAt());
            ps.setObject(15, ex.createdAt());
            ps.executeUpdate();
        }
    }

    public void updateTravelExceptionDecision(Connection conn, TravelException ex) throws SQLException {
        String sql = "UPDATE plugin_finance_travel_exception SET approver_person_id = ?, status = ?, decision_notes = ?, decided_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, ex.approverPersonId());
            ps.setString(2, ex.status().name());
            ps.setString(3, ex.decisionNotes());
            ps.setObject(4, ex.decidedAt());
            ps.setObject(5, ex.id());
            ps.executeUpdate();
        }
    }

    public Optional<TravelException> findTravelExceptionById(Connection conn, UUID id) throws SQLException {
        String sql = "SELECT id, tenant_id, claim_id, line_no, policy_cap_id, requested_amount, standard_cap_amount, excess_amount, " +
                "applicant_person_id, approver_person_id, status, justification, decision_notes, decided_at, created_at " +
                "FROM plugin_finance_travel_exception WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapTravelException(rs));
            }
        }
        return Optional.empty();
    }

    // --- Expense Requisition (EXP01) ---

    public void insertRequisition(Connection conn, ExpenseRequisition req) throws SQLException {
        String sql = "INSERT INTO plugin_finance_expense_requisition (id, tenant_id, book_id, requisition_no, applicant_id, department_id, " +
                "project_id, business_purpose, currency, total_budget_amount, reserved_amount, utilized_amount, status, approver_id, approved_at, " +
                "effective_until, created_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, req.id());
            ps.setObject(2, req.tenantId());
            ps.setObject(3, req.bookId());
            ps.setString(4, req.requisitionNo());
            ps.setObject(5, req.applicantId());
            ps.setObject(6, req.departmentId());
            ps.setObject(7, req.projectId());
            ps.setString(8, req.businessPurpose());
            ps.setString(9, req.currency());
            ps.setBigDecimal(10, req.totalBudgetAmount());
            ps.setBigDecimal(11, req.reservedAmount());
            ps.setBigDecimal(12, req.utilizedAmount());
            ps.setString(13, req.status().name());
            ps.setObject(14, req.approverId());
            ps.setObject(15, req.approvedAt());
            ps.setDate(16, java.sql.Date.valueOf(req.effectiveUntil()));
            ps.setObject(17, req.createdAt());
            ps.setLong(18, req.version());
            ps.executeUpdate();
        }
    }

    public void updateRequisition(Connection conn, ExpenseRequisition req) throws SQLException {
        String sql = "UPDATE plugin_finance_expense_requisition SET reserved_amount = ?, utilized_amount = ?, status = ?, " +
                "approver_id = ?, approved_at = ?, version = version + 1 WHERE id = ? AND version = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, req.reservedAmount());
            ps.setBigDecimal(2, req.utilizedAmount());
            ps.setString(3, req.status().name());
            ps.setObject(4, req.approverId());
            ps.setObject(5, req.approvedAt());
            ps.setObject(6, req.id());
            ps.setLong(7, req.version() - 1);
            if (ps.executeUpdate() == 0) {
                throw new SQLException("Optimistic lock error updating requisition " + req.id());
            }
        }
    }

    public Optional<ExpenseRequisition> findRequisitionById(Connection conn, UUID id) throws SQLException {
        String sql = "SELECT id, tenant_id, book_id, requisition_no, applicant_id, department_id, project_id, business_purpose, " +
                "currency, total_budget_amount, reserved_amount, utilized_amount, status, approver_id, approved_at, effective_until, created_at, version " +
                "FROM plugin_finance_expense_requisition WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRequisition(rs));
            }
        }
        return Optional.empty();
    }

    // --- Cash Advance & Refund (EXP02) ---

    public void insertAdvance(Connection conn, ExpenseAdvance adv) throws SQLException {
        String sql = "INSERT INTO plugin_finance_expense_advance (id, tenant_id, book_id, advance_no, employee_id, requisition_id, " +
                "purpose, currency, principal_amount, offset_amount, refunded_amount, remaining_balance, status, applicant_id, approver_id, " +
                "cashier_id, payout_ref, payout_at, created_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, adv.id());
            ps.setObject(2, adv.tenantId());
            ps.setObject(3, adv.bookId());
            ps.setString(4, adv.advanceNo());
            ps.setObject(5, adv.employeeId());
            ps.setObject(6, adv.requisitionId());
            ps.setString(7, adv.purpose());
            ps.setString(8, adv.currency());
            ps.setBigDecimal(9, adv.principalAmount());
            ps.setBigDecimal(10, adv.offsetAmount());
            ps.setBigDecimal(11, adv.refundedAmount());
            ps.setBigDecimal(12, adv.remainingBalance());
            ps.setString(13, adv.status().name());
            ps.setObject(14, adv.applicantId());
            ps.setObject(15, adv.approverId());
            ps.setObject(16, adv.cashierId());
            ps.setString(17, adv.payoutRef());
            ps.setObject(18, adv.payoutAt());
            ps.setObject(19, adv.createdAt());
            ps.setLong(20, adv.version());
            ps.executeUpdate();
        }
    }

    public void updateAdvance(Connection conn, ExpenseAdvance adv) throws SQLException {
        String sql = "UPDATE plugin_finance_expense_advance SET offset_amount = ?, refunded_amount = ?, remaining_balance = ?, " +
                "status = ?, approver_id = ?, cashier_id = ?, payout_ref = ?, payout_at = ?, version = version + 1 " +
                "WHERE id = ? AND version = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, adv.offsetAmount());
            ps.setBigDecimal(2, adv.refundedAmount());
            ps.setBigDecimal(3, adv.remainingBalance());
            ps.setString(4, adv.status().name());
            ps.setObject(5, adv.approverId());
            ps.setObject(6, adv.cashierId());
            ps.setString(7, adv.payoutRef());
            ps.setObject(8, adv.payoutAt());
            ps.setObject(9, adv.id());
            ps.setLong(10, adv.version() - 1);
            if (ps.executeUpdate() == 0) {
                throw new SQLException("Optimistic lock error updating advance " + adv.id());
            }
        }
    }

    public Optional<ExpenseAdvance> findAdvanceById(Connection conn, UUID id) throws SQLException {
        String sql = "SELECT id, tenant_id, book_id, advance_no, employee_id, requisition_id, purpose, currency, " +
                "principal_amount, offset_amount, refunded_amount, remaining_balance, status, applicant_id, approver_id, " +
                "cashier_id, payout_ref, payout_at, created_at, version FROM plugin_finance_expense_advance WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapAdvance(rs));
            }
        }
        return Optional.empty();
    }

    public void insertAdvanceRefund(Connection conn, ExpenseAdvanceRefund ref) throws SQLException {
        String sql = "INSERT INTO plugin_finance_expense_advance_refund (id, tenant_id, advance_id, refund_no, refund_amount, " +
                "refund_date, receipt_method, receipt_ref, cashier_person_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, ref.id());
            ps.setObject(2, ref.tenantId());
            ps.setObject(3, ref.advanceId());
            ps.setString(4, ref.refundNo());
            ps.setBigDecimal(5, ref.refundAmount());
            ps.setDate(6, java.sql.Date.valueOf(ref.refundDate()));
            ps.setString(7, ref.receiptMethod());
            ps.setString(8, ref.receiptRef());
            ps.setObject(9, ref.cashierPersonId());
            ps.setObject(10, ref.createdAt());
            ps.executeUpdate();
        }
    }

    // --- Expense Claim & Lines & Offsets & Payables (EXP03) ---

    public void insertClaim(Connection conn, ExpenseClaim claim, List<ExpenseClaimLine> lines, List<ExpenseClaimAdvanceOffset> offsets) throws SQLException {
        String sqlClaim = "INSERT INTO plugin_finance_expense_claim (id, tenant_id, book_id, claim_no, employee_id, requisition_id, " +
                "title, claim_date, currency, submitted_amount, approved_amount, disallowed_amount, offset_advance_amount, payable_amount, " +
                "paid_amount, status, submitter_id, reviewer_id, approver_id, reviewed_at, approved_at, journal_id, reversal_journal_id, " +
                "created_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sqlClaim)) {
            ps.setObject(1, claim.id());
            ps.setObject(2, claim.tenantId());
            ps.setObject(3, claim.bookId());
            ps.setString(4, claim.claimNo());
            ps.setObject(5, claim.employeeId());
            ps.setObject(6, claim.requisitionId());
            ps.setString(7, claim.title());
            ps.setDate(8, java.sql.Date.valueOf(claim.claimDate()));
            ps.setString(9, claim.currency());
            ps.setBigDecimal(10, claim.submittedAmount());
            ps.setBigDecimal(11, claim.approvedAmount());
            ps.setBigDecimal(12, claim.disallowedAmount());
            ps.setBigDecimal(13, claim.offsetAdvanceAmount());
            ps.setBigDecimal(14, claim.payableAmount());
            ps.setBigDecimal(15, claim.paidAmount());
            ps.setString(16, claim.status().name());
            ps.setObject(17, claim.submitterId());
            ps.setObject(18, claim.reviewerId());
            ps.setObject(19, claim.approverId());
            ps.setObject(20, claim.reviewedAt());
            ps.setObject(21, claim.approvedAt());
            ps.setObject(22, claim.journalId());
            ps.setObject(23, claim.reversalJournalId());
            ps.setObject(24, claim.createdAt());
            ps.setLong(25, claim.version());
            ps.executeUpdate();
        }

        String sqlLine = "INSERT INTO plugin_finance_expense_claim_line (id, claim_id, line_no, expense_category, department_id, project_id, " +
                "invoice_id, invoice_code, invoice_number, invoice_digest, submitted_amount, approved_reimbursable_amount, disallowed_amount, " +
                "disallowance_reason, vat_rate, vat_input_amount, net_expense_amount, travel_policy_cap_id, is_exception_approved) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sqlLine)) {
            for (ExpenseClaimLine line : lines) {
                ps.setObject(1, line.id());
                ps.setObject(2, line.claimId());
                ps.setInt(3, line.lineNo());
                ps.setString(4, line.expenseCategory().name());
                ps.setObject(5, line.departmentId());
                ps.setObject(6, line.projectId());
                ps.setObject(7, line.invoiceId());
                ps.setString(8, line.invoiceCode());
                ps.setString(9, line.invoiceNumber());
                ps.setString(10, line.invoiceDigest());
                ps.setBigDecimal(11, line.submittedAmount());
                ps.setBigDecimal(12, line.approvedReimbursableAmount());
                ps.setBigDecimal(13, line.disallowedAmount());
                ps.setString(14, line.disallowanceReason());
                ps.setBigDecimal(15, line.vatRate());
                ps.setBigDecimal(16, line.vatInputAmount());
                ps.setBigDecimal(17, line.netExpenseAmount());
                ps.setObject(18, line.travelPolicyCapId());
                ps.setBoolean(19, line.isExceptionApproved());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        if (offsets != null && !offsets.isEmpty()) {
            String sqlOffset = "INSERT INTO plugin_finance_expense_claim_advance_offset (id, claim_id, advance_id, offset_amount, created_at) " +
                    "VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sqlOffset)) {
                for (ExpenseClaimAdvanceOffset off : offsets) {
                    ps.setObject(1, off.id());
                    ps.setObject(2, off.claimId());
                    ps.setObject(3, off.advanceId());
                    ps.setBigDecimal(4, off.offsetAmount());
                    ps.setObject(5, off.createdAt());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    public void updateClaim(Connection conn, ExpenseClaim claim) throws SQLException {
        String sql = "UPDATE plugin_finance_expense_claim SET status = ?, reviewer_id = ?, approver_id = ?, " +
                "reviewed_at = ?, approved_at = ?, journal_id = ?, reversal_journal_id = ?, paid_amount = ?, " +
                "approved_amount = ?, disallowed_amount = ?, offset_advance_amount = ?, payable_amount = ?, " +
                "version = version + 1 WHERE id = ? AND version = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, claim.status().name());
            ps.setObject(2, claim.reviewerId());
            ps.setObject(3, claim.approverId());
            ps.setObject(4, claim.reviewedAt());
            ps.setObject(5, claim.approvedAt());
            ps.setObject(6, claim.journalId());
            ps.setObject(7, claim.reversalJournalId());
            ps.setBigDecimal(8, claim.paidAmount());
            ps.setBigDecimal(9, claim.approvedAmount());
            ps.setBigDecimal(10, claim.disallowedAmount());
            ps.setBigDecimal(11, claim.offsetAdvanceAmount());
            ps.setBigDecimal(12, claim.payableAmount());
            ps.setObject(13, claim.id());
            ps.setLong(14, claim.version() - 1);
            if (ps.executeUpdate() == 0) {
                throw new SQLException("Optimistic lock error updating claim " + claim.id());
            }
        }
    }

    public Optional<ExpenseClaim> findClaimById(Connection conn, UUID id) throws SQLException {
        String sql = "SELECT id, tenant_id, book_id, claim_no, employee_id, requisition_id, title, claim_date, currency, " +
                "submitted_amount, approved_amount, disallowed_amount, offset_advance_amount, payable_amount, paid_amount, " +
                "status, submitter_id, reviewer_id, approver_id, reviewed_at, approved_at, journal_id, reversal_journal_id, " +
                "created_at, version FROM plugin_finance_expense_claim WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapClaim(rs));
            }
        }
        return Optional.empty();
    }

    public List<ExpenseClaimLine> listClaimLines(Connection conn, UUID claimId) throws SQLException {
        String sql = "SELECT id, claim_id, line_no, expense_category, department_id, project_id, invoice_id, " +
                "invoice_code, invoice_number, invoice_digest, submitted_amount, approved_reimbursable_amount, disallowed_amount, " +
                "disallowance_reason, vat_rate, vat_input_amount, net_expense_amount, travel_policy_cap_id, is_exception_approved " +
                "FROM plugin_finance_expense_claim_line WHERE claim_id = ? ORDER BY line_no ASC";
        List<ExpenseClaimLine> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, claimId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new ExpenseClaimLine(
                            rs.getObject("id", UUID.class),
                            rs.getObject("claim_id", UUID.class),
                            rs.getInt("line_no"),
                            ExpenseCategory.valueOf(rs.getString("expense_category")),
                            rs.getObject("department_id", UUID.class),
                            rs.getObject("project_id", UUID.class),
                            rs.getObject("invoice_id", UUID.class),
                            rs.getString("invoice_code"),
                            rs.getString("invoice_number"),
                            rs.getString("invoice_digest"),
                            rs.getBigDecimal("submitted_amount"),
                            rs.getBigDecimal("approved_reimbursable_amount"),
                            rs.getBigDecimal("disallowed_amount"),
                            rs.getString("disallowance_reason"),
                            rs.getBigDecimal("vat_rate"),
                            rs.getBigDecimal("vat_input_amount"),
                            rs.getBigDecimal("net_expense_amount"),
                            rs.getObject("travel_policy_cap_id", UUID.class),
                            rs.getBoolean("is_exception_approved")
                    ));
                }
            }
        }
        return list;
    }

    public List<ExpenseClaimAdvanceOffset> listClaimOffsets(Connection conn, UUID claimId) throws SQLException {
        String sql = "SELECT id, claim_id, advance_id, offset_amount, created_at FROM plugin_finance_expense_claim_advance_offset WHERE claim_id = ?";
        List<ExpenseClaimAdvanceOffset> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, claimId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new ExpenseClaimAdvanceOffset(
                            rs.getObject("id", UUID.class),
                            rs.getObject("claim_id", UUID.class),
                            rs.getObject("advance_id", UUID.class),
                            rs.getBigDecimal("offset_amount"),
                            rs.getObject("created_at", OffsetDateTime.class)
                    ));
                }
            }
        }
        return list;
    }

    public void insertPayableLedger(Connection conn, ExpensePayableLedger ledger) throws SQLException {
        String sql = "INSERT INTO plugin_finance_expense_payable_ledger (id, tenant_id, book_id, claim_id, employee_id, " +
                "payable_amount, paid_amount, outstanding_balance, settlement_status, created_at, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, ledger.id());
            ps.setObject(2, ledger.tenantId());
            ps.setObject(3, ledger.bookId());
            ps.setObject(4, ledger.claimId());
            ps.setObject(5, ledger.employeeId());
            ps.setBigDecimal(6, ledger.payableAmount());
            ps.setBigDecimal(7, ledger.paidAmount());
            ps.setBigDecimal(8, ledger.outstandingBalance());
            ps.setString(9, ledger.settlementStatus().name());
            ps.setObject(10, ledger.createdAt());
            ps.setLong(11, ledger.version());
            ps.executeUpdate();
        }
    }

    public void updatePayableLedger(Connection conn, ExpensePayableLedger ledger) throws SQLException {
        String sql = "UPDATE plugin_finance_expense_payable_ledger SET paid_amount = ?, outstanding_balance = ?, " +
                "settlement_status = ?, version = version + 1 WHERE id = ? AND version = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, ledger.paidAmount());
            ps.setBigDecimal(2, ledger.outstandingBalance());
            ps.setString(3, ledger.settlementStatus().name());
            ps.setObject(4, ledger.id());
            ps.setLong(5, ledger.version() - 1);
            if (ps.executeUpdate() == 0) {
                throw new SQLException("Optimistic lock error updating payable ledger " + ledger.id());
            }
        }
    }

    public Optional<ExpensePayableLedger> findPayableByClaimId(Connection conn, UUID claimId) throws SQLException {
        String sql = "SELECT id, tenant_id, book_id, claim_id, employee_id, payable_amount, paid_amount, " +
                "outstanding_balance, settlement_status, created_at, version FROM plugin_finance_expense_payable_ledger WHERE claim_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, claimId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapPayableLedger(rs));
            }
        }
        return Optional.empty();
    }

    public void insertPayment(Connection conn, ExpensePayment payment) throws SQLException {
        String sql = "INSERT INTO plugin_finance_expense_payment (id, tenant_id, payable_id, payment_no, amount, payment_date, " +
                "payment_method, payment_ref, cashier_id, journal_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, payment.id());
            ps.setObject(2, payment.tenantId());
            ps.setObject(3, payment.payableId());
            ps.setString(4, payment.paymentNo());
            ps.setBigDecimal(5, payment.amount());
            ps.setDate(6, java.sql.Date.valueOf(payment.paymentDate()));
            ps.setString(7, payment.paymentMethod());
            ps.setString(8, payment.paymentRef());
            ps.setObject(9, payment.cashierId());
            ps.setObject(10, payment.journalId());
            ps.setObject(11, payment.createdAt());
            ps.executeUpdate();
        }
    }

    public List<TravelPolicy> listPolicies(Connection conn, UUID tenantId) throws SQLException {
        String sql = "SELECT id, tenant_id, policy_code, version_int, name, effective_from, effective_to, status, created_at, created_by " +
                "FROM plugin_finance_travel_policy WHERE tenant_id = ? ORDER BY policy_code, version_int DESC";
        List<TravelPolicy> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapTravelPolicy(rs));
            }
        }
        return list;
    }

    public List<ExpenseRequisition> listRequisitions(Connection conn, UUID tenantId, UUID employeeId, boolean isReviewer) throws SQLException {
        String sql = "SELECT id, tenant_id, book_id, requisition_no, applicant_id, department_id, project_id, business_purpose, " +
                "currency, total_budget_amount, reserved_amount, utilized_amount, status, approver_id, approved_at, effective_until, created_at, version " +
                "FROM plugin_finance_expense_requisition WHERE tenant_id = ?" +
                (!isReviewer && employeeId != null ? " AND applicant_id = ?" : "") +
                " ORDER BY created_at DESC";
        List<ExpenseRequisition> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            if (!isReviewer && employeeId != null) {
                ps.setObject(2, employeeId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRequisition(rs));
            }
        }
        return list;
    }

    public List<ExpenseAdvance> listAdvances(Connection conn, UUID tenantId, UUID employeeId, boolean isReviewer) throws SQLException {
        String sql = "SELECT id, tenant_id, book_id, advance_no, employee_id, requisition_id, purpose, currency, " +
                "principal_amount, offset_amount, refunded_amount, remaining_balance, status, applicant_id, approver_id, " +
                "cashier_id, payout_ref, payout_at, created_at, version FROM plugin_finance_expense_advance WHERE tenant_id = ?" +
                (!isReviewer && employeeId != null ? " AND employee_id = ?" : "") +
                " ORDER BY created_at DESC";
        List<ExpenseAdvance> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            if (!isReviewer && employeeId != null) {
                ps.setObject(2, employeeId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapAdvance(rs));
            }
        }
        return list;
    }

    public List<ExpenseClaim> listClaims(Connection conn, UUID tenantId, UUID employeeId, boolean isReviewer) throws SQLException {
        String sql = "SELECT id, tenant_id, book_id, claim_no, employee_id, requisition_id, title, claim_date, currency, " +
                "submitted_amount, approved_amount, disallowed_amount, offset_advance_amount, payable_amount, paid_amount, " +
                "status, submitter_id, reviewer_id, approver_id, reviewed_at, approved_at, journal_id, reversal_journal_id, " +
                "created_at, version FROM plugin_finance_expense_claim WHERE tenant_id = ?" +
                (!isReviewer && employeeId != null ? " AND employee_id = ?" : "") +
                " ORDER BY created_at DESC";
        List<ExpenseClaim> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            if (!isReviewer && employeeId != null) {
                ps.setObject(2, employeeId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapClaim(rs));
            }
        }
        return list;
    }

    public List<ExpensePayableLedger> listPayables(Connection conn, UUID tenantId, UUID employeeId, boolean isReviewer) throws SQLException {
        String sql = "SELECT id, tenant_id, book_id, claim_id, employee_id, payable_amount, paid_amount, " +
                "outstanding_balance, settlement_status, created_at, version FROM plugin_finance_expense_payable_ledger WHERE tenant_id = ?" +
                (!isReviewer && employeeId != null ? " AND employee_id = ?" : "") +
                " ORDER BY created_at DESC";
        List<ExpensePayableLedger> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            if (!isReviewer && employeeId != null) {
                ps.setObject(2, employeeId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapPayableLedger(rs));
            }
        }
        return list;
    }

    // --- ResultSet Mappers ---

    private TravelPolicy mapTravelPolicy(ResultSet rs) throws SQLException {
        return new TravelPolicy(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("policy_code"),
                rs.getInt("version_int"),
                rs.getString("name"),
                rs.getDate("effective_from").toLocalDate(),
                rs.getDate("effective_to") != null ? rs.getDate("effective_to").toLocalDate() : null,
                PolicyStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class)
        );
    }

    private TravelException mapTravelException(ResultSet rs) throws SQLException {
        return new TravelException(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("claim_id", UUID.class),
                rs.getInt("line_no"),
                rs.getObject("policy_cap_id", UUID.class),
                rs.getBigDecimal("requested_amount"),
                rs.getBigDecimal("standard_cap_amount"),
                rs.getBigDecimal("excess_amount"),
                rs.getObject("applicant_person_id", UUID.class),
                rs.getObject("approver_person_id", UUID.class),
                TravelExceptionStatus.valueOf(rs.getString("status")),
                rs.getString("justification"),
                rs.getString("decision_notes"),
                rs.getObject("decided_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private ExpenseRequisition mapRequisition(ResultSet rs) throws SQLException {
        return new ExpenseRequisition(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("book_id", UUID.class),
                rs.getString("requisition_no"),
                rs.getObject("applicant_id", UUID.class),
                rs.getObject("department_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("business_purpose"),
                rs.getString("currency"),
                rs.getBigDecimal("total_budget_amount"),
                rs.getBigDecimal("reserved_amount"),
                rs.getBigDecimal("utilized_amount"),
                RequisitionStatus.valueOf(rs.getString("status")),
                rs.getObject("approver_id", UUID.class),
                rs.getObject("approved_at", OffsetDateTime.class),
                rs.getDate("effective_until").toLocalDate(),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("version")
        );
    }

    private ExpenseAdvance mapAdvance(ResultSet rs) throws SQLException {
        return new ExpenseAdvance(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("book_id", UUID.class),
                rs.getString("advance_no"),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("requisition_id", UUID.class),
                rs.getString("purpose"),
                rs.getString("currency"),
                rs.getBigDecimal("principal_amount"),
                rs.getBigDecimal("offset_amount"),
                rs.getBigDecimal("refunded_amount"),
                rs.getBigDecimal("remaining_balance"),
                AdvanceStatus.valueOf(rs.getString("status")),
                rs.getObject("applicant_id", UUID.class),
                rs.getObject("approver_id", UUID.class),
                rs.getObject("cashier_id", UUID.class),
                rs.getString("payout_ref"),
                rs.getObject("payout_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("version")
        );
    }

    private ExpenseClaim mapClaim(ResultSet rs) throws SQLException {
        return new ExpenseClaim(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("book_id", UUID.class),
                rs.getString("claim_no"),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("requisition_id", UUID.class),
                rs.getString("title"),
                rs.getDate("claim_date").toLocalDate(),
                rs.getString("currency"),
                rs.getBigDecimal("submitted_amount"),
                rs.getBigDecimal("approved_amount"),
                rs.getBigDecimal("disallowed_amount"),
                rs.getBigDecimal("offset_advance_amount"),
                rs.getBigDecimal("payable_amount"),
                rs.getBigDecimal("paid_amount"),
                ClaimStatus.valueOf(rs.getString("status")),
                rs.getObject("submitter_id", UUID.class),
                rs.getObject("reviewer_id", UUID.class),
                rs.getObject("approver_id", UUID.class),
                rs.getObject("reviewed_at", OffsetDateTime.class),
                rs.getObject("approved_at", OffsetDateTime.class),
                rs.getObject("journal_id", UUID.class),
                rs.getObject("reversal_journal_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("version")
        );
    }

    private ExpensePayableLedger mapPayableLedger(ResultSet rs) throws SQLException {
        return new ExpensePayableLedger(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("book_id", UUID.class),
                rs.getObject("claim_id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getBigDecimal("payable_amount"),
                rs.getBigDecimal("paid_amount"),
                rs.getBigDecimal("outstanding_balance"),
                SettlementStatus.valueOf(rs.getString("settlement_status")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("version")
        );
    }
}
