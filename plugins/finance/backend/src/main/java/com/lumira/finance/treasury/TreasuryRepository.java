package com.lumira.finance.treasury;

import com.lumira.finance.treasury.TreasuryModel.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * JDBC repository for Treasury operations against PostgreSQL 18.
 */
@Repository
public final class TreasuryRepository {
    private final ObjectMapper mapper;

    public TreasuryRepository(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // -------------------------------------------------------------
    // Bank Accounts
    // -------------------------------------------------------------

    public void createBankAccount(Connection c, BankAccount a) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_bank_account (
                tenant_id, book_id, id, account_no, account_name, bank_name, branch_name,
                account_type, currency, gl_account_id, status, maker_person_id,
                reviewer_person_id, frozen_reason, closing_reason, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, a.tenantId());
            ps.setObject(2, a.bookId());
            ps.setObject(3, a.id());
            ps.setString(4, a.accountNo());
            ps.setString(5, a.accountName());
            ps.setString(6, a.bankName());
            ps.setString(7, a.branchName());
            ps.setString(8, a.accountType().name());
            ps.setString(9, a.currency());
            ps.setObject(10, a.glAccountId());
            ps.setString(11, a.status().name());
            ps.setObject(12, a.makerPersonId());
            ps.setObject(13, a.reviewerPersonId());
            ps.setString(14, a.frozenReason());
            ps.setString(15, a.closingReason());
            ps.setInt(16, a.version());
            ps.setTimestamp(17, Timestamp.from(a.createdAt()));
            ps.setTimestamp(18, Timestamp.from(a.updatedAt()));
            ps.executeUpdate();
        }
    }

    public boolean updateBankAccountStatus(
            Connection c, UUID accountId, BankAccountStatus status, UUID reviewerId,
            String frozenReason, String closingReason, int expectedVersion) throws SQLException {
        String sql = """
            UPDATE plugin_finance_bank_account
            SET status = ?, reviewer_person_id = ?, frozen_reason = ?, closing_reason = ?,
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND version = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setObject(2, reviewerId);
            ps.setString(3, frozenReason);
            ps.setString(4, closingReason);
            ps.setObject(5, accountId);
            ps.setInt(6, expectedVersion);
            return ps.executeUpdate() == 1;
        }
    }

    public Optional<BankAccount> findBankAccountById(Connection c, UUID accountId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_bank_account WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapAccount(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<BankAccount> listBankAccounts(
            Connection c, UUID tenantId, UUID bookId, BankAccountStatus status) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM plugin_finance_bank_account WHERE tenant_id = ? AND book_id = ?");
        if (status != null) {
            sql.append(" AND status = ?");
        }
        sql.append(" ORDER BY created_at ASC");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            if (status != null) {
                ps.setString(3, status.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<BankAccount> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapAccount(rs));
                }
                return list;
            }
        }
    }

    public BigDecimal getBankAccountBalance(Connection c, UUID accountId) throws SQLException {
        // Balance derived from executed orders: receipts & transfer-in minus payments & transfer-out
        String sql = """
            SELECT COALESCE(SUM(
                CASE
                    WHEN order_type IN ('RECEIPT', 'TRANSFER_IN') AND status = 'EXECUTED' THEN amount
                    WHEN order_type IN ('PAYMENT', 'TRANSFER_OUT') AND status IN ('IN_TRANSIT', 'EXECUTED') THEN -(amount + fee_amount)
                    ELSE 0
                END
            ), 0) as balance
            FROM plugin_finance_treasury_order
            WHERE account_id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBigDecimal("balance");
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private BankAccount mapAccount(ResultSet rs) throws SQLException {
        return new BankAccount(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("book_id"),
                rs.getString("account_no"),
                rs.getString("account_name"),
                rs.getString("bank_name"),
                rs.getString("branch_name"),
                BankAccountType.valueOf(rs.getString("account_type")),
                rs.getString("currency"),
                (UUID) rs.getObject("gl_account_id"),
                BankAccountStatus.valueOf(rs.getString("status")),
                (UUID) rs.getObject("maker_person_id"),
                (UUID) rs.getObject("reviewer_person_id"),
                rs.getString("frozen_reason"),
                rs.getString("closing_reason"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    // -------------------------------------------------------------
    // Numbering Policy
    // -------------------------------------------------------------

    public void createNumberingPolicy(Connection c, NumberingPolicy p) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_treasury_numbering_policy (
                tenant_id, book_id, id, period_id, period_no, order_type, prefix,
                pattern, current_sequence, is_approved, maker_person_id, reviewer_person_id, version, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, p.tenantId());
            ps.setObject(2, p.bookId());
            ps.setObject(3, p.id());
            ps.setObject(4, p.periodId());
            ps.setInt(5, p.periodNo());
            ps.setString(6, p.orderType());
            ps.setString(7, p.prefix());
            ps.setString(8, p.pattern());
            ps.setInt(9, p.currentSequence());
            ps.setBoolean(10, p.isApproved());
            ps.setObject(11, p.makerPersonId());
            ps.setObject(12, p.reviewerPersonId());
            ps.setInt(13, p.version());
            ps.setTimestamp(14, Timestamp.from(p.createdAt()));
            ps.executeUpdate();
        }
    }

    public Optional<NumberingPolicy> findNumberingPolicy(
            Connection c, UUID tenantId, UUID bookId, UUID periodId, String orderType) throws SQLException {
        String sql = """
            SELECT * FROM plugin_finance_treasury_numbering_policy
            WHERE tenant_id = ? AND book_id = ? AND period_id = ? AND order_type = ?
            FOR UPDATE
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, periodId);
            ps.setString(4, orderType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new NumberingPolicy(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("tenant_id"),
                            (UUID) rs.getObject("book_id"),
                            (UUID) rs.getObject("period_id"),
                            rs.getInt("period_no"),
                            rs.getString("order_type"),
                            rs.getString("prefix"),
                            rs.getString("pattern"),
                            rs.getInt("current_sequence"),
                            rs.getBoolean("is_approved"),
                            (UUID) rs.getObject("maker_person_id"),
                            (UUID) rs.getObject("reviewer_person_id"),
                            rs.getInt("version"),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public int incrementNumberingSequence(Connection c, UUID policyId) throws SQLException {
        String sql = "UPDATE plugin_finance_treasury_numbering_policy SET current_sequence = current_sequence + 1 WHERE id = ? RETURNING current_sequence";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, policyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        throw new IllegalStateException("编号策略记录未找到: " + policyId);
    }

    public Optional<NumberingPolicy> findNumberingPolicyById(Connection c, UUID policyId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_treasury_numbering_policy WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, policyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapNumberingPolicy(rs));
                }
            }
        }
        return Optional.empty();
    }

    public boolean updateNumberingPolicyApproval(
            Connection c, UUID policyId, UUID reviewerId, int expectedVersion) throws SQLException {
        String sql = """
            UPDATE plugin_finance_treasury_numbering_policy
            SET is_approved = true, reviewer_person_id = ?, version = version + 1
            WHERE id = ? AND version = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, reviewerId);
            ps.setObject(2, policyId);
            ps.setInt(3, expectedVersion);
            return ps.executeUpdate() == 1;
        }
    }

    public Optional<NumberingPolicy> findApprovedNumberingPolicy(
            Connection c, UUID tenantId, UUID bookId, UUID periodId, String orderType) throws SQLException {
        String sql = """
            SELECT * FROM plugin_finance_treasury_numbering_policy
            WHERE tenant_id = ? AND book_id = ? AND period_id = ? AND order_type = ? AND is_approved = true
            FOR UPDATE
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, periodId);
            ps.setString(4, orderType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapNumberingPolicy(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<NumberingPolicy> findApprovedNumberingPolicy(
            Connection c, UUID tenantId, UUID bookId, String orderType) throws SQLException {
        String sql = """
            SELECT * FROM plugin_finance_treasury_numbering_policy
            WHERE tenant_id = ? AND book_id = ? AND order_type = ? AND is_approved = true
            ORDER BY created_at DESC
            LIMIT 1
            FOR UPDATE
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setString(3, orderType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapNumberingPolicy(rs));
                }
            }
        }
        return Optional.empty();
    }

    private NumberingPolicy mapNumberingPolicy(ResultSet rs) throws SQLException {
        return new NumberingPolicy(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("book_id"),
                (UUID) rs.getObject("period_id"),
                rs.getInt("period_no"),
                rs.getString("order_type"),
                rs.getString("prefix"),
                rs.getString("pattern"),
                rs.getInt("current_sequence"),
                rs.getBoolean("is_approved"),
                (UUID) rs.getObject("maker_person_id"),
                (UUID) rs.getObject("reviewer_person_id"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    // -------------------------------------------------------------
    // Treasury Orders
    // -------------------------------------------------------------

    public void createTreasuryOrder(Connection c, TreasuryOrder o) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_treasury_order (
                tenant_id, book_id, id, order_no, order_type, account_id, counterparty_id,
                target_account_id, transfer_pair_id, order_date, amount, fee_amount,
                fee_gl_account_id, status, purpose, maker_person_id, reviewer_person_id,
                journal_id, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, o.tenantId());
            ps.setObject(2, o.bookId());
            ps.setObject(3, o.id());
            ps.setString(4, o.orderNo());
            ps.setString(5, o.orderType().name());
            ps.setObject(6, o.accountId());
            ps.setObject(7, o.counterpartyId());
            ps.setObject(8, o.targetAccountId());
            ps.setObject(9, o.transferPairId());
            ps.setDate(10, java.sql.Date.valueOf(o.orderDate()));
            ps.setBigDecimal(11, o.amount());
            ps.setBigDecimal(12, o.feeAmount());
            ps.setObject(13, o.feeGlAccountId());
            ps.setString(14, o.status().name());
            ps.setString(15, o.purpose());
            ps.setObject(16, o.makerPersonId());
            ps.setObject(17, o.reviewerPersonId());
            ps.setObject(18, o.journalId());
            ps.setInt(19, o.version());
            ps.setTimestamp(20, Timestamp.from(o.createdAt()));
            ps.setTimestamp(21, Timestamp.from(o.updatedAt()));
            ps.executeUpdate();
        }
    }

    public Optional<TreasuryOrder> findTreasuryOrderById(Connection c, UUID orderId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_treasury_order WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapOrder(rs));
                }
            }
        }
        return Optional.empty();
    }

    public boolean updateTreasuryOrderStatus(
            Connection c, UUID orderId, OrderStatus status, UUID reviewerId, UUID journalId, int expectedVersion) throws SQLException {
        String sql = """
            UPDATE plugin_finance_treasury_order
            SET status = ?, reviewer_person_id = COALESCE(?, reviewer_person_id),
                journal_id = COALESCE(?, journal_id), version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND version = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setObject(2, reviewerId);
            ps.setObject(3, journalId);
            ps.setObject(4, orderId);
            ps.setInt(5, expectedVersion);
            return ps.executeUpdate() == 1;
        }
    }

    public List<TreasuryOrder> listTreasuryOrders(
            Connection c, UUID tenantId, UUID bookId, UUID accountId, OrderStatus status) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM plugin_finance_treasury_order WHERE tenant_id = ? AND book_id = ?");
        if (accountId != null) {
            sql.append(" AND account_id = ?");
        }
        if (status != null) {
            sql.append(" AND status = ?");
        }
        sql.append(" ORDER BY order_date DESC, created_at DESC");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setObject(idx++, tenantId);
            ps.setObject(idx++, bookId);
            if (accountId != null) {
                ps.setObject(idx++, accountId);
            }
            if (status != null) {
                ps.setString(idx++, status.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<TreasuryOrder> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapOrder(rs));
                }
                return list;
            }
        }
    }

    private TreasuryOrder mapOrder(ResultSet rs) throws SQLException {
        return new TreasuryOrder(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("book_id"),
                rs.getString("order_no"),
                OrderType.valueOf(rs.getString("order_type")),
                (UUID) rs.getObject("account_id"),
                (UUID) rs.getObject("counterparty_id"),
                (UUID) rs.getObject("target_account_id"),
                (UUID) rs.getObject("transfer_pair_id"),
                rs.getDate("order_date").toLocalDate(),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("fee_amount"),
                (UUID) rs.getObject("fee_gl_account_id"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getString("purpose"),
                (UUID) rs.getObject("maker_person_id"),
                (UUID) rs.getObject("reviewer_person_id"),
                (UUID) rs.getObject("journal_id"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    public List<TreasuryOrder> findOrdersByTransferPairId(Connection c, UUID transferPairId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_treasury_order WHERE transfer_pair_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, transferPairId);
            try (ResultSet rs = ps.executeQuery()) {
                List<TreasuryOrder> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapOrder(rs));
                }
                return list;
            }
        }
    }

    public int getNextDefaultOrderSequence(Connection c, UUID tenantId, UUID bookId, OrderType orderType) throws SQLException {
        String sql = "SELECT COUNT(*) + 1 FROM plugin_finance_treasury_order WHERE tenant_id = ? AND book_id = ? AND order_type = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setString(3, orderType.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 1;
    }

    public int getNextPaymentInstructionSequence(Connection c, UUID tenantId, UUID bookId) throws SQLException {
        String sql = "SELECT COUNT(*) + 1 FROM plugin_finance_treasury_payment_instruction WHERE tenant_id = ? AND book_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 1;
    }

    // -------------------------------------------------------------
    // Payment Instructions
    // -------------------------------------------------------------

    public void createPaymentInstruction(Connection c, PaymentInstruction pi) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_treasury_payment_instruction (
                tenant_id, book_id, id, instruction_no, treasury_order_id, payer_account_id,
                payee_account_no, payee_account_name, payee_bank_name, amount, status,
                bank_direct_channel, maker_person_id, approver_person_id, dispatched_at,
                receipt_original_sha256, receipt_provider_ref, receipt_verifier_person_id,
                receipt_verified_at, failure_reason, adjustment_ref_id, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, pi.tenantId());
            ps.setObject(2, pi.bookId());
            ps.setObject(3, pi.id());
            ps.setString(4, pi.instructionNo());
            ps.setObject(5, pi.treasuryOrderId());
            ps.setObject(6, pi.payerAccountId());
            ps.setString(7, pi.payeeAccountNo());
            ps.setString(8, pi.payeeAccountName());
            ps.setString(9, pi.payeeBankName());
            ps.setBigDecimal(10, pi.amount());
            ps.setString(11, pi.status().name());
            ps.setString(12, pi.bankDirectChannel());
            ps.setObject(13, pi.makerPersonId());
            ps.setObject(14, pi.approverPersonId());
            ps.setTimestamp(15, pi.dispatchedAt() == null ? null : Timestamp.from(pi.dispatchedAt()));
            ps.setString(16, pi.receiptOriginalSha256());
            ps.setString(17, pi.receiptProviderRef());
            ps.setObject(18, pi.receiptVerifierPersonId());
            ps.setTimestamp(19, pi.receiptVerifiedAt() == null ? null : Timestamp.from(pi.receiptVerifiedAt()));
            ps.setString(20, pi.failureReason());
            ps.setObject(21, pi.adjustmentRefId());
            ps.setInt(22, pi.version());
            ps.setTimestamp(23, Timestamp.from(pi.createdAt()));
            ps.setTimestamp(24, Timestamp.from(pi.updatedAt()));
            ps.executeUpdate();
        }
    }

    public Optional<PaymentInstruction> findPaymentInstructionById(Connection c, UUID instId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_treasury_payment_instruction WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, instId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapInstruction(rs));
                }
            }
        }
        return Optional.empty();
    }

    public boolean updatePaymentInstruction(Connection c, PaymentInstruction pi) throws SQLException {
        String sql = """
            UPDATE plugin_finance_treasury_payment_instruction
            SET status = ?, approver_person_id = ?, dispatched_at = ?, receipt_original_sha256 = ?,
                receipt_provider_ref = ?, receipt_verifier_person_id = ?, receipt_verified_at = ?,
                failure_reason = ?, adjustment_ref_id = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND version = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, pi.status().name());
            ps.setObject(2, pi.approverPersonId());
            ps.setTimestamp(3, pi.dispatchedAt() == null ? null : Timestamp.from(pi.dispatchedAt()));
            ps.setString(4, pi.receiptOriginalSha256());
            ps.setString(5, pi.receiptProviderRef());
            ps.setObject(6, pi.receiptVerifierPersonId());
            ps.setTimestamp(7, pi.receiptVerifiedAt() == null ? null : Timestamp.from(pi.receiptVerifiedAt()));
            ps.setString(8, pi.failureReason());
            ps.setObject(9, pi.adjustmentRefId());
            ps.setObject(10, pi.id());
            ps.setInt(11, pi.version() - 1); // target expected version
            return ps.executeUpdate() == 1;
        }
    }

    public List<PaymentInstruction> listPaymentInstructions(
            Connection c, UUID tenantId, UUID bookId, InstructionStatus status) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM plugin_finance_treasury_payment_instruction WHERE tenant_id = ? AND book_id = ?");
        if (status != null) {
            sql.append(" AND status = ?");
        }
        sql.append(" ORDER BY created_at DESC");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            if (status != null) {
                ps.setString(3, status.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<PaymentInstruction> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapInstruction(rs));
                }
                return list;
            }
        }
    }

    private PaymentInstruction mapInstruction(ResultSet rs) throws SQLException {
        return new PaymentInstruction(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("book_id"),
                rs.getString("instruction_no"),
                (UUID) rs.getObject("treasury_order_id"),
                (UUID) rs.getObject("payer_account_id"),
                rs.getString("payee_account_no"),
                rs.getString("payee_account_name"),
                rs.getString("payee_bank_name"),
                rs.getBigDecimal("amount"),
                InstructionStatus.valueOf(rs.getString("status")),
                rs.getString("bank_direct_channel"),
                (UUID) rs.getObject("maker_person_id"),
                (UUID) rs.getObject("approver_person_id"),
                rs.getTimestamp("dispatched_at") == null ? null : rs.getTimestamp("dispatched_at").toInstant(),
                rs.getString("receipt_original_sha256"),
                rs.getString("receipt_provider_ref"),
                (UUID) rs.getObject("receipt_verifier_person_id"),
                rs.getTimestamp("receipt_verified_at") == null ? null : rs.getTimestamp("receipt_verified_at").toInstant(),
                rs.getString("failure_reason"),
                (UUID) rs.getObject("adjustment_ref_id"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    // -------------------------------------------------------------
    // Bank Statement Batches & Lines
    // -------------------------------------------------------------

    public void createBankStatementBatch(Connection c, BankStatementBatch b) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_bank_statement_batch (
                tenant_id, book_id, id, account_id, file_sha256, file_format,
                statement_date, opening_balance, closing_balance, total_credit_amount,
                total_debit_amount, record_count, imported_by, status, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, b.tenantId());
            ps.setObject(2, b.bookId());
            ps.setObject(3, b.id());
            ps.setObject(4, b.accountId());
            ps.setString(5, b.fileSha256());
            ps.setString(6, b.fileFormat().name());
            ps.setDate(7, java.sql.Date.valueOf(b.statementDate()));
            ps.setBigDecimal(8, b.openingBalance());
            ps.setBigDecimal(9, b.closingBalance());
            ps.setBigDecimal(10, b.totalCreditAmount());
            ps.setBigDecimal(11, b.totalDebitAmount());
            ps.setInt(12, b.recordCount());
            ps.setObject(13, b.importedBy());
            ps.setString(14, b.status().name());
            ps.setTimestamp(15, Timestamp.from(b.createdAt()));
            ps.executeUpdate();
        }
    }

    public void createBankStatementLines(Connection c, List<BankStatementLine> lines) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_bank_statement_line (
                tenant_id, book_id, id, batch_id, account_id, line_no, line_hash,
                transaction_date, value_date, direction, amount, balance_after,
                counterparty_account_no, counterparty_name, transaction_no, summary,
                match_status, matched_order_id, matched_journal_line_id, match_reviewer_person_id,
                matched_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (BankStatementLine l : lines) {
                ps.setObject(1, l.tenantId());
                ps.setObject(2, l.bookId());
                ps.setObject(3, l.id());
                ps.setObject(4, l.batchId());
                ps.setObject(5, l.accountId());
                ps.setInt(6, l.lineNo());
                ps.setString(7, l.lineHash());
                ps.setDate(8, java.sql.Date.valueOf(l.transactionDate()));
                ps.setDate(9, java.sql.Date.valueOf(l.valueDate()));
                ps.setString(10, l.direction().name());
                ps.setBigDecimal(11, l.amount());
                ps.setBigDecimal(12, l.balanceAfter());
                ps.setString(13, l.counterpartyAccountNo());
                ps.setString(14, l.counterpartyName());
                ps.setString(15, l.transactionNo());
                ps.setString(16, l.summary());
                ps.setString(17, l.matchStatus().name());
                ps.setObject(18, l.matchedOrderId());
                ps.setObject(19, l.matchedJournalLineId());
                ps.setObject(20, l.matchReviewerPersonId());
                ps.setTimestamp(21, l.matchedAt() == null ? null : Timestamp.from(l.matchedAt()));
                ps.setTimestamp(22, Timestamp.from(l.createdAt()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public Optional<BankStatementBatch> findStatementBatchBySha(
            Connection c, UUID tenantId, UUID bookId, UUID accountId, String fileSha256) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_bank_statement_batch WHERE tenant_id = ? AND book_id = ? AND account_id = ? AND file_sha256 = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, accountId);
            ps.setString(4, fileSha256);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapStatementBatch(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<BankStatementLine> listStatementLines(Connection c, UUID batchId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_bank_statement_line WHERE batch_id = ? ORDER BY line_no ASC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                List<BankStatementLine> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapStatementLine(rs));
                }
                return list;
            }
        }
    }

    public boolean updateStatementLineMatch(
            Connection c, UUID lineId, StatementMatchStatus status, UUID orderId, UUID journalLineId, UUID reviewerId) throws SQLException {
        String sql = """
            UPDATE plugin_finance_bank_statement_line
            SET match_status = ?, matched_order_id = ?, matched_journal_line_id = ?,
                match_reviewer_person_id = ?, matched_at = CURRENT_TIMESTAMP
            WHERE id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setObject(2, orderId);
            ps.setObject(3, journalLineId);
            ps.setObject(4, reviewerId);
            ps.setObject(5, lineId);
            return ps.executeUpdate() == 1;
        }
    }

    public int countMatchingOrders(Connection c, UUID accountId, LocalDate txDate, BigDecimal amount, StatementDirection dir) throws SQLException {
        String orderTypeFilter = dir == StatementDirection.CREDIT ? "('RECEIPT', 'TRANSFER_IN')" : "('PAYMENT', 'TRANSFER_OUT')";
        String sql = "SELECT COUNT(*) FROM plugin_finance_treasury_order WHERE account_id = ? AND order_date = ? AND amount = ? AND order_type IN " + orderTypeFilter;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, accountId);
            ps.setDate(2, java.sql.Date.valueOf(txDate));
            ps.setBigDecimal(3, amount);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    public Optional<UUID> findSingleMatchingOrderId(
            Connection c, UUID accountId, LocalDate txDate, BigDecimal amount, StatementDirection dir) throws SQLException {
        String orderTypeFilter = dir == StatementDirection.CREDIT ? "('RECEIPT', 'TRANSFER_IN')" : "('PAYMENT', 'TRANSFER_OUT')";
        String sql = "SELECT id FROM plugin_finance_treasury_order WHERE account_id = ? AND order_date = ? AND amount = ? AND order_type IN " + orderTypeFilter + " LIMIT 2";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, accountId);
            ps.setDate(2, java.sql.Date.valueOf(txDate));
            ps.setBigDecimal(3, amount);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UUID firstId = (UUID) rs.getObject("id");
                    if (rs.next()) {
                        // More than 1 match -> ambiguous candidate!
                        return Optional.empty();
                    }
                    return Optional.of(firstId);
                }
            }
        }
        return Optional.empty();
    }

    public Optional<BankStatementLine> findStatementLineById(Connection c, UUID lineId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_bank_statement_line WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, lineId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapStatementLine(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<BankStatementBatch> findStatementBatchById(Connection c, UUID batchId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_bank_statement_batch WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapStatementBatch(rs));
                }
            }
        }
        return Optional.empty();
    }

    private BankStatementBatch mapStatementBatch(ResultSet rs) throws SQLException {
        return new BankStatementBatch(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("book_id"),
                (UUID) rs.getObject("account_id"),
                rs.getString("file_sha256"),
                StatementFormat.valueOf(rs.getString("file_format")),
                rs.getDate("statement_date").toLocalDate(),
                rs.getBigDecimal("opening_balance"),
                rs.getBigDecimal("closing_balance"),
                rs.getBigDecimal("total_credit_amount"),
                rs.getBigDecimal("total_debit_amount"),
                rs.getInt("record_count"),
                (UUID) rs.getObject("imported_by"),
                StatementBatchStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private BankStatementLine mapStatementLine(ResultSet rs) throws SQLException {
        return new BankStatementLine(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("book_id"),
                (UUID) rs.getObject("batch_id"),
                (UUID) rs.getObject("account_id"),
                rs.getInt("line_no"),
                rs.getString("line_hash"),
                rs.getDate("transaction_date").toLocalDate(),
                rs.getDate("value_date").toLocalDate(),
                StatementDirection.valueOf(rs.getString("direction")),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("balance_after"),
                rs.getString("counterparty_account_no"),
                rs.getString("counterparty_name"),
                rs.getString("transaction_no"),
                rs.getString("summary"),
                StatementMatchStatus.valueOf(rs.getString("match_status")),
                (UUID) rs.getObject("matched_order_id"),
                (UUID) rs.getObject("matched_journal_line_id"),
                (UUID) rs.getObject("match_reviewer_person_id"),
                rs.getTimestamp("matched_at") == null ? null : rs.getTimestamp("matched_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    // -------------------------------------------------------------
    // Bank Reconciliation Sheet & Unreached Items
    // -------------------------------------------------------------

    public void createBankReconciliation(Connection c, BankReconciliation r) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_bank_reconciliation (
                tenant_id, book_id, id, account_id, period_id, period_no, cutoff_date,
                bank_statement_balance, enterprise_recorded_bank_unrecorded, enterprise_paid_bank_unpaid,
                adjusted_bank_balance, ledger_balance, bank_credited_enterprise_unrecorded,
                bank_debited_enterprise_unrecorded, adjusted_ledger_balance, is_balanced,
                status, preparer_person_id, reviewer_person_id, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, r.tenantId());
            ps.setObject(2, r.bookId());
            ps.setObject(3, r.id());
            ps.setObject(4, r.accountId());
            ps.setObject(5, r.periodId());
            ps.setInt(6, r.periodNo());
            ps.setDate(7, java.sql.Date.valueOf(r.cutoffDate()));
            ps.setBigDecimal(8, r.bankStatementBalance());
            ps.setBigDecimal(9, r.enterpriseRecordedBankUnrecorded());
            ps.setBigDecimal(10, r.enterprisePaidBankUnpaid());
            ps.setBigDecimal(11, r.adjustedBankBalance());
            ps.setBigDecimal(12, r.ledgerBalance());
            ps.setBigDecimal(13, r.bankCreditedEnterpriseUnrecorded());
            ps.setBigDecimal(14, r.bankDebitedEnterpriseUnrecorded());
            ps.setBigDecimal(15, r.adjustedLedgerBalance());
            ps.setBoolean(16, r.isBalanced());
            ps.setString(17, r.status().name());
            ps.setObject(18, r.preparerPersonId());
            ps.setObject(19, r.reviewerPersonId());
            ps.setInt(20, r.version());
            ps.setTimestamp(21, Timestamp.from(r.createdAt()));
            ps.setTimestamp(22, Timestamp.from(r.updatedAt()));
            ps.executeUpdate();
        }
    }

    public void createUnreachedItems(Connection c, List<UnreachedItem> items) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_bank_unreached_item (
                tenant_id, book_id, id, reconciliation_id, account_id, item_type,
                recorded_date, amount, summary, aging_days, is_overdue_warning,
                status, investigation_notes, supplementary_evidence_id, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (UnreachedItem it : items) {
                ps.setObject(1, it.tenantId());
                ps.setObject(2, it.bookId());
                ps.setObject(3, it.id());
                ps.setObject(4, it.reconciliationId());
                ps.setObject(5, it.accountId());
                ps.setString(6, it.itemType().name());
                ps.setDate(7, java.sql.Date.valueOf(it.recordedDate()));
                ps.setBigDecimal(8, it.amount());
                ps.setString(9, it.summary());
                ps.setInt(10, it.agingDays());
                ps.setBoolean(11, it.isOverdueWarning());
                ps.setString(12, it.status().name());
                ps.setString(13, it.investigationNotes());
                ps.setObject(14, it.supplementaryEvidenceId());
                ps.setTimestamp(15, Timestamp.from(it.createdAt()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public Optional<BankReconciliation> findBankReconciliationById(Connection c, UUID recId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_bank_reconciliation WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, recId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapReconciliation(rs));
                }
            }
        }
        return Optional.empty();
    }

    public boolean updateBankReconciliationStatus(
            Connection c, UUID recId, ReconciliationStatus status, UUID reviewerId, int expectedVersion) throws SQLException {
        String sql = """
            UPDATE plugin_finance_bank_reconciliation
            SET status = ?, reviewer_person_id = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND version = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setObject(2, reviewerId);
            ps.setObject(3, recId);
            ps.setInt(4, expectedVersion);
            return ps.executeUpdate() == 1;
        }
    }

    public List<UnreachedItem> listUnreachedItems(Connection c, UUID reconciliationId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_bank_unreached_item WHERE reconciliation_id = ? ORDER BY aging_days DESC, recorded_date ASC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, reconciliationId);
            try (ResultSet rs = ps.executeQuery()) {
                List<UnreachedItem> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new UnreachedItem(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("tenant_id"),
                            (UUID) rs.getObject("book_id"),
                            (UUID) rs.getObject("reconciliation_id"),
                            (UUID) rs.getObject("account_id"),
                            UnreachedItemType.valueOf(rs.getString("item_type")),
                            rs.getDate("recorded_date").toLocalDate(),
                            rs.getBigDecimal("amount"),
                            rs.getString("summary"),
                            rs.getInt("aging_days"),
                            rs.getBoolean("is_overdue_warning"),
                            UnreachedItemStatus.valueOf(rs.getString("status")),
                            rs.getString("investigation_notes"),
                            (UUID) rs.getObject("supplementary_evidence_id"),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
                return list;
            }
        }
    }

    private BankReconciliation mapReconciliation(ResultSet rs) throws SQLException {
        return new BankReconciliation(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("book_id"),
                (UUID) rs.getObject("account_id"),
                (UUID) rs.getObject("period_id"),
                rs.getInt("period_no"),
                rs.getDate("cutoff_date").toLocalDate(),
                rs.getBigDecimal("bank_statement_balance"),
                rs.getBigDecimal("enterprise_recorded_bank_unrecorded"),
                rs.getBigDecimal("enterprise_paid_bank_unpaid"),
                rs.getBigDecimal("adjusted_bank_balance"),
                rs.getBigDecimal("ledger_balance"),
                rs.getBigDecimal("bank_credited_enterprise_unrecorded"),
                rs.getBigDecimal("bank_debited_enterprise_unrecorded"),
                rs.getBigDecimal("adjusted_ledger_balance"),
                rs.getBoolean("is_balanced"),
                ReconciliationStatus.valueOf(rs.getString("status")),
                (UUID) rs.getObject("preparer_person_id"),
                (UUID) rs.getObject("reviewer_person_id"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    // -------------------------------------------------------------
    // Cash Forecast
    // -------------------------------------------------------------

    public void createCashForecast(Connection c, CashForecast f) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_treasury_cash_forecast (
                tenant_id, book_id, id, account_id, forecast_horizon_days, tier,
                forecast_date, opening_cash, projected_inflow, projected_outflow,
                closing_cash, is_read_only_forecast, source_reference, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, f.tenantId());
            ps.setObject(2, f.bookId());
            ps.setObject(3, f.id());
            ps.setObject(4, f.accountId());
            ps.setInt(5, f.forecastHorizonDays());
            ps.setString(6, f.tier().name());
            ps.setDate(7, java.sql.Date.valueOf(f.forecastDate()));
            ps.setBigDecimal(8, f.openingCash());
            ps.setBigDecimal(9, f.projectedInflow());
            ps.setBigDecimal(10, f.projectedOutflow());
            ps.setBigDecimal(11, f.closingCash());
            ps.setBoolean(12, f.isReadOnlyForecast());
            ps.setString(13, f.sourceReference());
            ps.setTimestamp(14, Timestamp.from(f.createdAt()));
            ps.executeUpdate();
        }
    }

    public List<CashForecast> listCashForecasts(
            Connection c, UUID tenantId, UUID accountId, int horizonDays) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_treasury_cash_forecast WHERE tenant_id = ? AND account_id = ? AND forecast_horizon_days = ? ORDER BY forecast_date ASC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, accountId);
            ps.setInt(3, horizonDays);
            try (ResultSet rs = ps.executeQuery()) {
                List<CashForecast> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new CashForecast(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("tenant_id"),
                            (UUID) rs.getObject("book_id"),
                            (UUID) rs.getObject("account_id"),
                            rs.getInt("forecast_horizon_days"),
                            ForecastTier.valueOf(rs.getString("tier")),
                            rs.getDate("forecast_date").toLocalDate(),
                            rs.getBigDecimal("opening_cash"),
                            rs.getBigDecimal("projected_inflow"),
                            rs.getBigDecimal("projected_outflow"),
                            rs.getBigDecimal("closing_cash"),
                            rs.getBoolean("is_read_only_forecast"),
                            rs.getString("source_reference"),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
                return list;
            }
        }
    }

    // -------------------------------------------------------------
    // Immutable Events
    // -------------------------------------------------------------

    public void recordTreasuryEvent(Connection c, TreasuryEvent e) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_treasury_event (
                id, tenant_id, book_id, entity_type, entity_id, action, operator_person_id, payload, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, e.id());
            ps.setObject(2, e.tenantId());
            ps.setObject(3, e.bookId());
            ps.setString(4, e.entityType());
            ps.setObject(5, e.entityId());
            ps.setString(6, e.action());
            ps.setObject(7, e.operatorPersonId());
            ps.setString(8, e.payload());
            ps.setTimestamp(9, Timestamp.from(e.createdAt()));
            ps.executeUpdate();
        }
    }
}
