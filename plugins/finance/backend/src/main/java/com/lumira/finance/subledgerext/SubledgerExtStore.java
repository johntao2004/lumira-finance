package com.lumira.finance.subledgerext;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import javax.sql.DataSource;

import org.springframework.stereotype.Repository;

import com.lumira.finance.subledgerext.SubledgerExtModels.*;
import com.lumira.finance.subledgerext.SubledgerExtRules.*;

/**
 * JDBC persistence store for Lane W06 subledger extensions.
 */
@Repository
public final class SubledgerExtStore {
    private final DataSource dataSource;

    public SubledgerExtStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // --- Idempotency ---
    public Optional<String> findIdempotentResponse(UUID tenantId, UUID bookId, UUID yearId, String clientKey, String operation) {
        String sql = "SELECT response_payload FROM plugin_finance_subledger_ext_idempotency " +
                "WHERE tenant_id = ? AND book_id = ? AND year_id = ? AND client_key = ? AND operation = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, yearId);
            ps.setString(4, clientKey);
            ps.setString(5, operation);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check idempotency", e);
        }
        return Optional.empty();
    }

    public Optional<UUID> findIdempotentResourceId(UUID tenantId, UUID bookId, UUID yearId, String clientKey, String operation) {
        String sql = "SELECT resource_id FROM plugin_finance_subledger_ext_idempotency " +
                "WHERE tenant_id = ? AND book_id = ? AND year_id = ? AND client_key = ? AND operation = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, yearId);
            ps.setString(4, clientKey);
            ps.setString(5, operation);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getObject(1, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check idempotency resource", e);
        }
        return Optional.empty();
    }

    public void saveIdempotentResponse(UUID tenantId, UUID bookId, UUID yearId, String clientKey, String operation, UUID resourceId, String payloadJson) {
        String sql = "INSERT INTO plugin_finance_subledger_ext_idempotency " +
                "(tenant_id, book_id, year_id, client_key, operation, resource_id, response_payload) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) " +
                "ON CONFLICT (tenant_id, book_id, year_id, client_key, operation) DO NOTHING";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, yearId);
            ps.setString(4, clientKey);
            ps.setString(5, operation);
            ps.setObject(6, resourceId);
            ps.setString(7, payloadJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save idempotency record", e);
        }
    }

    // --- AP Purchase Order ---
    public PoOrder insertPo(UUID tenantId, UUID bookId, UUID yearId, UUID createdBy, Map<String, Object> norm) {
        UUID poId = UUID.randomUUID();
        String poNumber = (String) norm.get("poNumber");
        UUID supplierId = (UUID) norm.get("supplierId");
        UUID supplierRevisionId = (UUID) norm.get("supplierRevisionId");
        LocalDate orderDate = (LocalDate) norm.get("orderDate");
        String terms = (String) norm.get("terms");
        UUID originalId = (UUID) norm.get("originalId");
        BigDecimal totalNet = (BigDecimal) norm.get("totalNetAmount");
        BigDecimal totalTax = (BigDecimal) norm.get("totalTaxAmount");
        BigDecimal totalGross = (BigDecimal) norm.get("totalGrossAmount");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) norm.get("lines");

        String insertHeader = "INSERT INTO plugin_finance_ap_po_order " +
                "(id, tenant_id, book_id, year_id, po_number, supplier_id, supplier_revision_id, " +
                "order_date, currency, total_net_amount, total_tax_amount, total_gross_amount, " +
                "terms, status, original_id, created_by, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CNY', ?, ?, ?, ?, 'DRAFT', ?, ?, 1)";

        String insertLine = "INSERT INTO plugin_finance_ap_po_line " +
                "(id, po_id, line_no, item_code, itemName, quantity, unit_price, net_amount, tax_rate, tax_amount, gross_amount) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement psH = c.prepareStatement(insertHeader)) {
                psH.setObject(1, poId);
                psH.setObject(2, tenantId);
                psH.setObject(3, bookId);
                psH.setObject(4, yearId);
                psH.setString(5, poNumber);
                psH.setObject(6, supplierId);
                psH.setObject(7, supplierRevisionId);
                psH.setDate(8, Date.valueOf(orderDate));
                psH.setBigDecimal(9, totalNet);
                psH.setBigDecimal(10, totalTax);
                psH.setBigDecimal(11, totalGross);
                psH.setString(12, terms);
                psH.setObject(13, originalId);
                psH.setObject(14, createdBy);
                psH.executeUpdate();
            }

            try (PreparedStatement psL = c.prepareStatement(
                    "INSERT INTO plugin_finance_ap_po_line " +
                            "(id, po_id, line_no, item_code, item_name, quantity, unit_price, net_amount, tax_rate, tax_amount, gross_amount) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (Map<String, Object> l : lines) {
                    psL.setObject(1, UUID.randomUUID());
                    psL.setObject(2, poId);
                    psL.setInt(3, (Integer) l.get("lineNo"));
                    psL.setString(4, (String) l.get("itemCode"));
                    psL.setString(5, (String) l.get("itemName"));
                    psL.setBigDecimal(6, (BigDecimal) l.get("quantity"));
                    psL.setBigDecimal(7, (BigDecimal) l.get("unitPrice"));
                    psL.setBigDecimal(8, (BigDecimal) l.get("netAmount"));
                    psL.setBigDecimal(9, (BigDecimal) l.get("taxRate"));
                    psL.setBigDecimal(10, (BigDecimal) l.get("taxAmount"));
                    psL.setBigDecimal(11, (BigDecimal) l.get("grossAmount"));
                    psL.addBatch();
                }
                psL.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert PO order", e);
        }
        return getPo(poId).orElseThrow();
    }

    public Optional<PoOrder> getPo(UUID poId) {
        String sqlH = "SELECT id, tenant_id, book_id, year_id, po_number, supplier_id, supplier_revision_id, " +
                "order_date, currency, total_net_amount, total_tax_amount, total_gross_amount, terms, " +
                "status, original_id, created_by, approved_by, created_at, updated_at, version " +
                "FROM plugin_finance_ap_po_order WHERE id = ?";
        String sqlL = "SELECT id, po_id, line_no, item_code, item_name, quantity, unit_price, " +
                "net_amount, tax_rate, tax_amount, gross_amount, received_quantity, invoiced_quantity " +
                "FROM plugin_finance_ap_po_line WHERE po_id = ? ORDER BY line_no ASC";

        try (Connection c = getConnection()) {
            PoOrder header = null;
            try (PreparedStatement ps = c.prepareStatement(sqlH)) {
                ps.setObject(1, poId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        header = new PoOrder(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getObject("book_id", UUID.class),
                                rs.getObject("year_id", UUID.class),
                                rs.getString("po_number"),
                                rs.getObject("supplier_id", UUID.class),
                                rs.getObject("supplier_revision_id", UUID.class),
                                rs.getDate("order_date").toLocalDate(),
                                rs.getString("currency"),
                                rs.getBigDecimal("total_net_amount"),
                                rs.getBigDecimal("total_tax_amount"),
                                rs.getBigDecimal("total_gross_amount"),
                                rs.getString("terms"),
                                PoStatus.valueOf(rs.getString("status")),
                                rs.getObject("original_id", UUID.class),
                                rs.getObject("created_by", UUID.class),
                                rs.getObject("approved_by", UUID.class),
                                rs.getObject("created_at", OffsetDateTime.class),
                                rs.getObject("updated_at", OffsetDateTime.class),
                                rs.getLong("version"),
                                new ArrayList<>()
                        );
                    }
                }
            }
            if (header == null) return Optional.empty();

            List<PoLine> lines = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sqlL)) {
                ps.setObject(1, poId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(new PoLine(
                                rs.getObject("id", UUID.class),
                                rs.getObject("po_id", UUID.class),
                                rs.getInt("line_no"),
                                rs.getString("item_code"),
                                rs.getString("item_name"),
                                rs.getBigDecimal("quantity"),
                                rs.getBigDecimal("unit_price"),
                                rs.getBigDecimal("net_amount"),
                                rs.getBigDecimal("tax_rate"),
                                rs.getBigDecimal("tax_amount"),
                                rs.getBigDecimal("gross_amount"),
                                rs.getBigDecimal("received_quantity"),
                                rs.getBigDecimal("invoiced_quantity")
                        ));
                    }
                }
            }
            return Optional.of(new PoOrder(
                    header.id(), header.tenantId(), header.bookId(), header.yearId(),
                    header.poNumber(), header.supplierId(), header.supplierRevisionId(),
                    header.orderDate(), header.currency(), header.totalNetAmount(),
                    header.totalTaxAmount(), header.totalGrossAmount(), header.terms(),
                    header.status(), header.originalId(), header.createdBy(),
                    header.approvedBy(), header.createdAt(), header.updatedAt(),
                    header.version(), lines
            ));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read PO", e);
        }
    }

    public void approvePo(UUID poId, UUID approverId) {
        String sql = "UPDATE plugin_finance_ap_po_order SET status = 'APPROVED', approved_by = ?, " +
                "updated_at = now(), version = version + 1 WHERE id = ? AND status = 'DRAFT'";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, approverId);
            ps.setObject(2, poId);
            int rows = ps.executeUpdate();
            if (rows == 0) throw new SubledgerException("PO_NOT_FOUND_OR_NOT_DRAFT");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to approve PO", e);
        }
    }

    // --- AP Acceptance ---
    public PoAcceptance insertAcceptance(UUID tenantId, UUID bookId, UUID yearId, UUID acceptedBy, Map<String, Object> norm) {
        UUID accId = UUID.randomUUID();
        UUID poId = (UUID) norm.get("poId");
        String accNum = (String) norm.get("acceptanceNumber");
        LocalDate accDate = (LocalDate) norm.get("acceptedDate");
        String note = (String) norm.get("acceptanceNote");
        UUID originalId = (UUID) norm.get("originalId");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) norm.get("lines");

        String sqlH = "INSERT INTO plugin_finance_ap_po_acceptance " +
                "(id, tenant_id, book_id, year_id, po_id, acceptance_number, accepted_date, " +
                "accepted_by, acceptance_note, status, invoice_status, provisional_status, original_id, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACCEPTED', 'UNINVOICED', 'NONE', ?, 1)";

        String sqlL = "INSERT INTO plugin_finance_ap_po_acceptance_line " +
                "(id, acceptance_id, po_line_id, line_no, accepted_quantity, unit_cost_estimate, estimated_net_amount) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";

        String updatePoLine = "UPDATE plugin_finance_ap_po_line SET received_quantity = received_quantity + ? WHERE id = ?";

        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sqlH)) {
                ps.setObject(1, accId);
                ps.setObject(2, tenantId);
                ps.setObject(3, bookId);
                ps.setObject(4, yearId);
                ps.setObject(5, poId);
                ps.setString(6, accNum);
                ps.setDate(7, Date.valueOf(accDate));
                ps.setObject(8, acceptedBy);
                ps.setString(9, note);
                ps.setObject(10, originalId);
                ps.executeUpdate();
            }

            try (PreparedStatement psL = c.prepareStatement(sqlL);
                 PreparedStatement psPo = c.prepareStatement(updatePoLine)) {
                for (Map<String, Object> l : lines) {
                    psL.setObject(1, UUID.randomUUID());
                    psL.setObject(2, accId);
                    psL.setObject(3, l.get("poLineId"));
                    psL.setInt(4, (Integer) l.get("lineNo"));
                    BigDecimal qty = (BigDecimal) l.get("acceptedQuantity");
                    psL.setBigDecimal(5, qty);
                    psL.setBigDecimal(6, (BigDecimal) l.get("unitCostEstimate"));
                    psL.setBigDecimal(7, (BigDecimal) l.get("estimatedNetAmount"));
                    psL.addBatch();

                    psPo.setBigDecimal(1, qty);
                    psPo.setObject(2, l.get("poLineId"));
                    psPo.addBatch();
                }
                psL.executeBatch();
                psPo.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert acceptance", e);
        }
        return getAcceptance(accId).orElseThrow();
    }

    public Optional<PoAcceptance> getAcceptance(UUID accId) {
        String sqlH = "SELECT id, tenant_id, book_id, year_id, po_id, acceptance_number, accepted_date, " +
                "accepted_by, acceptance_note, status, invoice_status, provisional_status, original_id, created_at, version " +
                "FROM plugin_finance_ap_po_acceptance WHERE id = ?";
        String sqlL = "SELECT id, acceptance_id, po_line_id, line_no, accepted_quantity, unit_cost_estimate, " +
                "estimated_net_amount, invoiced_quantity " +
                "FROM plugin_finance_ap_po_acceptance_line WHERE acceptance_id = ? ORDER BY line_no ASC";

        try (Connection c = getConnection()) {
            PoAcceptance header = null;
            try (PreparedStatement ps = c.prepareStatement(sqlH)) {
                ps.setObject(1, accId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        header = new PoAcceptance(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getObject("book_id", UUID.class),
                                rs.getObject("year_id", UUID.class),
                                rs.getObject("po_id", UUID.class),
                                rs.getString("acceptance_number"),
                                rs.getDate("accepted_date").toLocalDate(),
                                rs.getObject("accepted_by", UUID.class),
                                rs.getString("acceptance_note"),
                                AcceptanceStatus.valueOf(rs.getString("status")),
                                InvoiceStatus.valueOf(rs.getString("invoice_status")),
                                ProvisionalStatus.valueOf(rs.getString("provisional_status")),
                                rs.getObject("original_id", UUID.class),
                                rs.getObject("created_at", OffsetDateTime.class),
                                rs.getLong("version"),
                                new ArrayList<>()
                        );
                    }
                }
            }
            if (header == null) return Optional.empty();

            List<AcceptanceLine> lines = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sqlL)) {
                ps.setObject(1, accId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(new AcceptanceLine(
                                rs.getObject("id", UUID.class),
                                rs.getObject("acceptance_id", UUID.class),
                                rs.getObject("po_line_id", UUID.class),
                                rs.getInt("line_no"),
                                rs.getBigDecimal("accepted_quantity"),
                                rs.getBigDecimal("unit_cost_estimate"),
                                rs.getBigDecimal("estimated_net_amount"),
                                rs.getBigDecimal("invoiced_quantity")
                        ));
                    }
                }
            }
            return Optional.of(new PoAcceptance(
                    header.id(), header.tenantId(), header.bookId(), header.yearId(),
                    header.poId(), header.acceptanceNumber(), header.acceptedDate(),
                    header.acceptedBy(), header.acceptanceNote(), header.status(),
                    header.invoiceStatus(), header.provisionalStatus(), header.originalId(),
                    header.createdAt(), header.version(), lines
            ));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read acceptance", e);
        }
    }

    // --- AP Provisional Accrual ---
    public ProvisionalAccrual insertProvisionalAccrual(UUID tenantId, UUID bookId, UUID yearId, UUID createdBy, Map<String, Object> norm) {
        UUID id = UUID.randomUUID();
        UUID acceptanceId = (UUID) norm.get("acceptanceId");
        UUID poId = (UUID) norm.get("poId");
        int period = (Integer) norm.get("accrualPeriod");
        LocalDate date = (LocalDate) norm.get("accrualDate");
        BigDecimal estNet = (BigDecimal) norm.get("estimatedNetAmount");
        String reason = (String) norm.get("reason");

        String sql = "INSERT INTO plugin_finance_ap_provisional_accrual " +
                "(id, tenant_id, book_id, year_id, acceptance_id, po_id, accrual_period, accrual_date, " +
                "estimated_net_amount, status, reason, created_by, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROVISIONALLY_ACCRUED', ?, ?, 1)";

        String updateAcc = "UPDATE plugin_finance_ap_po_acceptance SET provisional_status = 'PROVISIONALLY_ACCRUED' WHERE id = ?";

        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, id);
                ps.setObject(2, tenantId);
                ps.setObject(3, bookId);
                ps.setObject(4, yearId);
                ps.setObject(5, acceptanceId);
                ps.setObject(6, poId);
                ps.setInt(7, period);
                ps.setDate(8, Date.valueOf(date));
                ps.setBigDecimal(9, estNet);
                ps.setString(10, reason);
                ps.setObject(11, createdBy);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(updateAcc)) {
                ps.setObject(1, acceptanceId);
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert provisional accrual", e);
        }
        return getProvisionalAccrual(id).orElseThrow();
    }

    public Optional<ProvisionalAccrual> getProvisionalAccrual(UUID id) {
        String sql = "SELECT id, tenant_id, book_id, year_id, acceptance_id, po_id, accrual_period, " +
                "accrual_date, estimated_net_amount, status, journal_id, reversal_period, reversal_date, " +
                "reversal_journal_id, reason, created_by, approved_by, created_at, version " +
                "FROM plugin_finance_ap_provisional_accrual WHERE id = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ProvisionalAccrual(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("book_id", UUID.class),
                            rs.getObject("year_id", UUID.class),
                            rs.getObject("acceptance_id", UUID.class),
                            rs.getObject("po_id", UUID.class),
                            rs.getInt("accrual_period"),
                            rs.getDate("accrual_date").toLocalDate(),
                            rs.getBigDecimal("estimated_net_amount"),
                            ProvisionalStatus.valueOf(rs.getString("status")),
                            rs.getObject("journal_id", UUID.class),
                            rs.getObject("reversal_period", Integer.class),
                            rs.getDate("reversal_date") == null ? null : rs.getDate("reversal_date").toLocalDate(),
                            rs.getObject("reversal_journal_id", UUID.class),
                            rs.getString("reason"),
                            rs.getObject("created_by", UUID.class),
                            rs.getObject("approved_by", UUID.class),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getLong("version")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read provisional accrual", e);
        }
        return Optional.empty();
    }

    // --- AP Provisional Settlement & Variance Adjustment ---
    public ProvisionalSettlement insertProvisionalSettlement(
            UUID tenantId, UUID bookId, UUID yearId, UUID createdBy,
            UUID provisionalId, String invoiceNumber, LocalDate invoiceDate,
            BigDecimal actualNet, BigDecimal actualTax, BigDecimal actualGross,
            BigDecimal priceVariance, BigDecimal quantityVariance, BigDecimal totalVariance,
            BigDecimal invoicedQuantity) {

        UUID id = UUID.randomUUID();
        String sql = "INSERT INTO plugin_finance_ap_provisional_settlement " +
                "(id, tenant_id, book_id, year_id, provisional_id, invoice_number, invoice_date, " +
                "actual_net_amount, actual_tax_amount, actual_gross_amount, price_variance, " +
                "quantity_variance, total_variance, status, created_by, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', ?, 1)";

        String updateProv = "UPDATE plugin_finance_ap_provisional_accrual " +
                "SET status = 'SETTLED', reversal_date = ?, version = version + 1 WHERE id = ?";

        String updateAcc = "UPDATE plugin_finance_ap_po_acceptance " +
                "SET invoice_status = 'FULLY_INVOICED', provisional_status = 'REVERSED', version = version + 1 " +
                "WHERE id = (SELECT acceptance_id FROM plugin_finance_ap_provisional_accrual WHERE id = ?)";

        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, id);
                ps.setObject(2, tenantId);
                ps.setObject(3, bookId);
                ps.setObject(4, yearId);
                ps.setObject(5, provisionalId);
                ps.setString(6, invoiceNumber);
                ps.setDate(7, Date.valueOf(invoiceDate));
                ps.setBigDecimal(8, actualNet);
                ps.setBigDecimal(9, actualTax);
                ps.setBigDecimal(10, actualGross);
                ps.setBigDecimal(11, priceVariance);
                ps.setBigDecimal(12, quantityVariance);
                ps.setBigDecimal(13, totalVariance);
                ps.setObject(14, createdBy);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(updateProv)) {
                ps.setDate(1, Date.valueOf(invoiceDate));
                ps.setObject(2, provisionalId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(updateAcc)) {
                ps.setObject(1, provisionalId);
                ps.executeUpdate();
            }
            if (invoicedQuantity != null) {
                String updateAccLine = "UPDATE plugin_finance_ap_po_acceptance_line " +
                        "SET invoiced_quantity = ? " +
                        "WHERE acceptance_id = (SELECT acceptance_id FROM plugin_finance_ap_provisional_accrual WHERE id = ?)";
                try (PreparedStatement psAL = c.prepareStatement(updateAccLine)) {
                    psAL.setBigDecimal(1, invoicedQuantity);
                    psAL.setObject(2, provisionalId);
                    psAL.executeUpdate();
                }

                String updatePoLine = "UPDATE plugin_finance_ap_po_line " +
                        "SET invoiced_quantity = ? " +
                        "WHERE po_id = (SELECT po_id FROM plugin_finance_ap_provisional_accrual WHERE id = ?)";
                try (PreparedStatement psPL = c.prepareStatement(updatePoLine)) {
                    psPL.setBigDecimal(1, invoicedQuantity);
                    psPL.setObject(2, provisionalId);
                    psPL.executeUpdate();
                }
            }
            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert provisional settlement", e);
        }
        return getProvisionalSettlement(id).orElseThrow();
    }

    public Optional<ProvisionalSettlement> getProvisionalSettlement(UUID id) {
        String sql = "SELECT id, tenant_id, book_id, year_id, provisional_id, invoice_number, invoice_date, " +
                "actual_net_amount, actual_tax_amount, actual_gross_amount, price_variance, quantity_variance, " +
                "total_variance, status, journal_id, created_by, approved_by, created_at, version " +
                "FROM plugin_finance_ap_provisional_settlement WHERE id = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ProvisionalSettlement(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("book_id", UUID.class),
                            rs.getObject("year_id", UUID.class),
                            rs.getObject("provisional_id", UUID.class),
                            rs.getString("invoice_number"),
                            rs.getDate("invoice_date").toLocalDate(),
                            rs.getBigDecimal("actual_net_amount"),
                            rs.getBigDecimal("actual_tax_amount"),
                            rs.getBigDecimal("actual_gross_amount"),
                            rs.getBigDecimal("price_variance"),
                            rs.getBigDecimal("quantity_variance"),
                            rs.getBigDecimal("total_variance"),
                            rs.getString("status"),
                            rs.getObject("journal_id", UUID.class),
                            rs.getObject("created_by", UUID.class),
                            rs.getObject("approved_by", UUID.class),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getLong("version")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read provisional settlement", e);
        }
        return Optional.empty();
    }

    // --- AR Contract Assets (CAS 14) ---
    public ContractAsset insertContractAsset(UUID tenantId, UUID bookId, UUID yearId, UUID createdBy, Map<String, Object> norm) {
        UUID id = UUID.randomUUID();
        String sql = "INSERT INTO plugin_finance_ar_contract_asset " +
                "(id, tenant_id, book_id, year_id, contract_id, customer_id, stage_name, milestone_date, " +
                "asset_amount, condition_description, condition_status, status, created_by, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONDITIONAL', 'POSTED', ?, 1)";

        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, bookId);
            ps.setObject(4, yearId);
            ps.setObject(5, norm.get("contractId"));
            ps.setObject(6, norm.get("customerId"));
            ps.setString(7, (String) norm.get("stageName"));
            ps.setDate(8, Date.valueOf((LocalDate) norm.get("milestoneDate")));
            ps.setBigDecimal(9, (BigDecimal) norm.get("assetAmount"));
            ps.setString(10, (String) norm.get("conditionDescription"));
            ps.setObject(11, createdBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert contract asset", e);
        }
        return getContractAsset(id).orElseThrow();
    }

    public Optional<ContractAsset> getContractAsset(UUID id) {
        String sql = "SELECT id, tenant_id, book_id, year_id, contract_id, customer_id, stage_name, " +
                "milestone_date, asset_amount, condition_description, condition_status, reclassified_date, " +
                "reclassified_ar_id, status, journal_id, created_by, approved_by, created_at, version " +
                "FROM plugin_finance_ar_contract_asset WHERE id = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ContractAsset(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("book_id", UUID.class),
                            rs.getObject("year_id", UUID.class),
                            rs.getObject("contract_id", UUID.class),
                            rs.getObject("customer_id", UUID.class),
                            rs.getString("stage_name"),
                            rs.getDate("milestone_date").toLocalDate(),
                            rs.getBigDecimal("asset_amount"),
                            rs.getString("condition_description"),
                            ConditionStatus.valueOf(rs.getString("condition_status")),
                            rs.getDate("reclassified_date") == null ? null : rs.getDate("reclassified_date").toLocalDate(),
                            rs.getObject("reclassified_ar_id", UUID.class),
                            ContractAssetStatus.valueOf(rs.getString("status")),
                            rs.getObject("journal_id", UUID.class),
                            rs.getObject("created_by", UUID.class),
                            rs.getObject("approved_by", UUID.class),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getLong("version")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read contract asset", e);
        }
        return Optional.empty();
    }

    public void reclassifyContractAsset(UUID id, LocalDate reclassifiedDate, UUID arId, UUID approverId) {
        String sql = "UPDATE plugin_finance_ar_contract_asset " +
                "SET condition_status = 'FULFILLED', status = 'RECLASSIFIED', " +
                "reclassified_date = ?, reclassified_ar_id = ?, approved_by = ?, version = version + 1 " +
                "WHERE id = ? AND condition_status = 'CONDITIONAL'";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(reclassifiedDate));
            ps.setObject(2, arId);
            ps.setObject(3, approverId);
            ps.setObject(4, id);
            int rows = ps.executeUpdate();
            if (rows == 0) throw new SubledgerException("CONTRACT_ASSET_NOT_CONDITIONAL");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reclassify contract asset", e);
        }
    }

    // --- AR Constrained Variable Consideration ---
    public VariableConsideration insertVariableConsideration(UUID tenantId, UUID bookId, UUID yearId, UUID preparerId, Map<String, Object> norm) {
        UUID id = UUID.randomUUID();
        String sql = "INSERT INTO plugin_finance_ar_contract_asset_variable " +
                "(id, tenant_id, book_id, year_id, contract_id, variable_kind, estimated_amount, " +
                "confidence_score, constrained_cap_amount, cap_rationale, status, preparer_id, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?, 1)";

        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, bookId);
            ps.setObject(4, yearId);
            ps.setObject(5, norm.get("contractId"));
            ps.setString(6, ((VariableKind) norm.get("variableKind")).name());
            ps.setBigDecimal(7, (BigDecimal) norm.get("estimatedAmount"));
            ps.setBigDecimal(8, (BigDecimal) norm.get("confidenceScore"));
            ps.setBigDecimal(9, (BigDecimal) norm.get("constrainedCapAmount"));
            ps.setString(10, (String) norm.get("capRationale"));
            ps.setObject(11, preparerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert variable consideration", e);
        }
        return getVariableConsideration(id).orElseThrow();
    }

    public Optional<VariableConsideration> getVariableConsideration(UUID id) {
        String sql = "SELECT id, tenant_id, book_id, year_id, contract_id, variable_kind, estimated_amount, " +
                "confidence_score, constrained_cap_amount, cap_rationale, status, preparer_id, approved_by, " +
                "approved_at, approval_digest, created_at, version " +
                "FROM plugin_finance_ar_contract_asset_variable WHERE id = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new VariableConsideration(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("book_id", UUID.class),
                            rs.getObject("year_id", UUID.class),
                            rs.getObject("contract_id", UUID.class),
                            VariableKind.valueOf(rs.getString("variable_kind")),
                            rs.getBigDecimal("estimated_amount"),
                            rs.getBigDecimal("confidence_score"),
                            rs.getBigDecimal("constrained_cap_amount"),
                            rs.getString("cap_rationale"),
                            VariableStatus.valueOf(rs.getString("status")),
                            rs.getObject("preparer_id", UUID.class),
                            rs.getObject("approved_by", UUID.class),
                            rs.getObject("approved_at", OffsetDateTime.class),
                            rs.getString("approval_digest"),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getLong("version")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read variable consideration", e);
        }
        return Optional.empty();
    }

    public void approveVariableConsideration(UUID id, UUID approverId, String digest) {
        String sql = "UPDATE plugin_finance_ar_contract_asset_variable " +
                "SET status = 'APPROVED', approved_by = ?, approved_at = now(), approval_digest = ?, version = version + 1 " +
                "WHERE id = ? AND status = 'PENDING_APPROVAL'";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, approverId);
            ps.setString(2, digest);
            ps.setObject(3, id);
            int rows = ps.executeUpdate();
            if (rows == 0) throw new SubledgerException("VARIABLE_CONSIDERATION_NOT_PENDING");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to approve variable consideration", e);
        }
    }

    public void reviseVariableConsideration(UUID id, BigDecimal newCap, String reason) {
        String sql = "UPDATE plugin_finance_ar_contract_asset_variable " +
                "SET constrained_cap_amount = ?, cap_rationale = cap_rationale || ' | Revision: ' || ?, " +
                "status = 'REVISED', version = version + 1 WHERE id = ? AND status IN ('APPROVED', 'REVISED')";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBigDecimal(1, newCap);
            ps.setString(2, reason);
            ps.setObject(3, id);
            int rows = ps.executeUpdate();
            if (rows == 0) throw new SubledgerException("VARIABLE_CONSIDERATION_CANNOT_REVISE");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to revise variable consideration", e);
        }
    }

    // --- AR Multi-Year Contract Performance Continuity ---
    public MultiYearContract upsertMultiYearContract(
            UUID tenantId, UUID bookId, UUID contractId, int startYear, int endYear,
            BigDecimal totalValue, BigDecimal progressPct, int syncYear) {

        String checkSql = "SELECT id, total_contract_value, cumulative_revenue_recognized, current_year_revenue, last_sync_year " +
                "FROM plugin_finance_ar_contract_asset_multi_year " +
                "WHERE tenant_id = ? AND book_id = ? AND contract_id = ?";

        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            BigDecimal priorRecognized = BigDecimal.ZERO;
            UUID existingId = null;

            try (PreparedStatement ps = c.prepareStatement(checkSql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, bookId);
                ps.setObject(3, contractId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        existingId = rs.getObject("id", UUID.class);
                        BigDecimal prevCum = rs.getBigDecimal("cumulative_revenue_recognized");
                        BigDecimal prevCur = rs.getBigDecimal("current_year_revenue");
                        int lastSyncYear = rs.getInt("last_sync_year");

                        if (syncYear < lastSyncYear) {
                            throw new SubledgerException("CANNOT_SYNC_PRIOR_YEAR",
                                    "Sync year " + syncYear + " is earlier than last sync year " + lastSyncYear);
                        }

                        if (syncYear == lastSyncYear) {
                            priorRecognized = prevCum.subtract(prevCur);
                        } else {
                            priorRecognized = prevCum;
                        }
                    }
                }
            }

            BigDecimal currentYearRevenue = SubledgerExtRules.calculateMultiYearRevenue(totalValue, progressPct, priorRecognized);
            BigDecimal newCumulative = priorRecognized.add(currentYearRevenue);
            BigDecimal unrecBalance = totalValue.subtract(newCumulative);

            if (existingId == null) {
                String insertSql = "INSERT INTO plugin_finance_ar_contract_asset_multi_year " +
                        "(id, tenant_id, book_id, contract_id, start_year, end_year, total_contract_value, " +
                        "cumulative_progress_pct, cumulative_revenue_recognized, current_year_revenue, " +
                        "unrecognized_balance, last_sync_year, status, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1)";
                UUID id = UUID.randomUUID();
                try (PreparedStatement ps = c.prepareStatement(insertSql)) {
                    ps.setObject(1, id);
                    ps.setObject(2, tenantId);
                    ps.setObject(3, bookId);
                    ps.setObject(4, contractId);
                    ps.setInt(5, startYear);
                    ps.setInt(6, endYear);
                    ps.setBigDecimal(7, totalValue);
                    ps.setBigDecimal(8, progressPct);
                    ps.setBigDecimal(9, newCumulative);
                    ps.setBigDecimal(10, currentYearRevenue);
                    ps.setBigDecimal(11, unrecBalance);
                    ps.setInt(12, syncYear);
                    ps.executeUpdate();
                }
            } else {
                String updateSql = "UPDATE plugin_finance_ar_contract_asset_multi_year " +
                        "SET total_contract_value = ?, start_year = ?, end_year = ?, " +
                        "cumulative_progress_pct = ?, cumulative_revenue_recognized = ?, " +
                        "current_year_revenue = ?, unrecognized_balance = ?, last_sync_year = ?, " +
                        "updated_at = now(), version = version + 1 WHERE id = ?";
                try (PreparedStatement ps = c.prepareStatement(updateSql)) {
                    ps.setBigDecimal(1, totalValue);
                    ps.setInt(2, startYear);
                    ps.setInt(3, endYear);
                    ps.setBigDecimal(4, progressPct);
                    ps.setBigDecimal(5, newCumulative);
                    ps.setBigDecimal(6, currentYearRevenue);
                    ps.setBigDecimal(7, unrecBalance);
                    ps.setInt(8, syncYear);
                    ps.setObject(9, existingId);
                    ps.executeUpdate();
                }
            }
            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert multi-year contract", e);
        }
        return getMultiYearContract(tenantId, bookId, contractId).orElseThrow();
    }

    public Optional<MultiYearContract> getMultiYearContract(UUID tenantId, UUID bookId, UUID contractId) {
        String sql = "SELECT id, tenant_id, book_id, contract_id, start_year, end_year, total_contract_value, " +
                "cumulative_progress_pct, cumulative_revenue_recognized, current_year_revenue, " +
                "unrecognized_balance, last_sync_year, status, updated_at, version " +
                "FROM plugin_finance_ar_contract_asset_multi_year " +
                "WHERE tenant_id = ? AND book_id = ? AND contract_id = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new MultiYearContract(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("book_id", UUID.class),
                            rs.getObject("contract_id", UUID.class),
                            rs.getInt("start_year"),
                            rs.getInt("end_year"),
                            rs.getBigDecimal("total_contract_value"),
                            rs.getBigDecimal("cumulative_progress_pct"),
                            rs.getBigDecimal("cumulative_revenue_recognized"),
                            rs.getBigDecimal("current_year_revenue"),
                            rs.getBigDecimal("unrecognized_balance"),
                            rs.getInt("last_sync_year"),
                            MultiYearStatus.valueOf(rs.getString("status")),
                            rs.getObject("updated_at", OffsetDateTime.class),
                            rs.getLong("version")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read multi-year contract", e);
        }
        return Optional.empty();
    }

    // --- AR Expected Credit Loss (ECL) ---
    public EclRecord insertEclRecord(UUID tenantId, UUID bookId, UUID yearId, UUID preparerId, Map<String, Object> norm) {
        UUID id = UUID.randomUUID();
        int period = (Integer) norm.get("period");
        EclAssessmentType assessType = (EclAssessmentType) norm.get("assessmentType");
        UUID customerId = (UUID) norm.get("customerId");
        BigDecimal receivableAmount = (BigDecimal) norm.get("receivableAmount");
        AgingBucket agingBucket = (AgingBucket) norm.get("agingBucket");
        BigDecimal lossRate = (BigDecimal) norm.get("lossRate");
        BigDecimal priorAllowance = (BigDecimal) norm.get("priorAllowance");
        EclOperationKind opKind = (EclOperationKind) norm.get("operationKind");
        UUID evidenceId = (UUID) norm.get("evidenceId");

        BigDecimal targetProvision;
        BigDecimal adjustment;
        if (opKind == EclOperationKind.WRITE_OFF) {
            targetProvision = BigDecimal.ZERO;
            adjustment = receivableAmount.negate();
        } else if (opKind == EclOperationKind.WRITE_OFF_RECOVERY) {
            targetProvision = receivableAmount;
            adjustment = receivableAmount;
        } else {
            targetProvision = SubledgerExtRules.calculateEclProvision(receivableAmount, lossRate);
            adjustment = SubledgerExtRules.calculateEclAdjustment(targetProvision, priorAllowance);
        }

        String sql = "INSERT INTO plugin_finance_ar_allowance_ecl " +
                "(id, tenant_id, book_id, year_id, period, assessment_type, customer_id, " +
                "receivable_amount, aging_bucket, loss_rate, provision_amount, prior_allowance, " +
                "adjustment_amount, operation_kind, status, preparer_id, evidence_id, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, ?, 1)";

        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, bookId);
            ps.setObject(4, yearId);
            ps.setInt(5, period);
            ps.setString(6, assessType.name());
            ps.setObject(7, customerId);
            ps.setBigDecimal(8, receivableAmount);
            ps.setString(9, agingBucket.name());
            ps.setBigDecimal(10, lossRate);
            ps.setBigDecimal(11, targetProvision);
            ps.setBigDecimal(12, priorAllowance);
            ps.setBigDecimal(13, adjustment);
            ps.setString(14, opKind.name());
            ps.setObject(15, preparerId);
            ps.setObject(16, evidenceId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert ECL record", e);
        }
        return getEclRecord(id).orElseThrow();
    }

    public Optional<EclRecord> getEclRecord(UUID id) {
        String sql = "SELECT id, tenant_id, book_id, year_id, period, assessment_type, customer_id, " +
                "receivable_amount, aging_bucket, loss_rate, provision_amount, prior_allowance, " +
                "adjustment_amount, operation_kind, status, preparer_id, approver_id, approval_digest, " +
                "evidence_id, journal_id, created_at, version " +
                "FROM plugin_finance_ar_allowance_ecl WHERE id = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new EclRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("book_id", UUID.class),
                            rs.getObject("year_id", UUID.class),
                            rs.getInt("period"),
                            EclAssessmentType.valueOf(rs.getString("assessment_type")),
                            rs.getObject("customer_id", UUID.class),
                            rs.getBigDecimal("receivable_amount"),
                            AgingBucket.valueOf(rs.getString("aging_bucket")),
                            rs.getBigDecimal("loss_rate"),
                            rs.getBigDecimal("provision_amount"),
                            rs.getBigDecimal("prior_allowance"),
                            rs.getBigDecimal("adjustment_amount"),
                            EclOperationKind.valueOf(rs.getString("operation_kind")),
                            EclStatus.valueOf(rs.getString("status")),
                            rs.getObject("preparer_id", UUID.class),
                            rs.getObject("approver_id", UUID.class),
                            rs.getString("approval_digest"),
                            rs.getObject("evidence_id", UUID.class),
                            rs.getObject("journal_id", UUID.class),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getLong("version")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read ECL record", e);
        }
        return Optional.empty();
    }

    public void approveEclRecord(UUID id, UUID approverId, String digest) {
        String sql = "UPDATE plugin_finance_ar_allowance_ecl " +
                "SET status = 'POSTED', approver_id = ?, approval_digest = ?, version = version + 1 " +
                "WHERE id = ? AND status = 'SUBMITTED'";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, approverId);
            ps.setString(2, digest);
            ps.setObject(3, id);
            int rows = ps.executeUpdate();
            if (rows == 0) throw new SubledgerException("ECL_RECORD_NOT_SUBMITTED");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to approve ECL record", e);
        }
    }

    // --- AR Sales Allowance, Discount & Return ---
    public AllowanceAdjustment insertAllowanceAdjustment(UUID tenantId, UUID bookId, UUID yearId, UUID preparerId, Map<String, Object> norm) {
        UUID id = UUID.randomUUID();
        String sql = "INSERT INTO plugin_finance_ar_allowance_adjustment " +
                "(id, tenant_id, book_id, year_id, customer_id, contract_id, receivable_id, " +
                "adjustment_type, credit_note_number, adjustment_date, original_receivable_amount, " +
                "net_adjustment_amount, tax_adjustment_amount, gross_adjustment_amount, vat_red_invoice_info, " +
                "reason, status, preparer_id, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, 1)";

        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, bookId);
            ps.setObject(4, yearId);
            ps.setObject(5, norm.get("customerId"));
            ps.setObject(6, norm.get("contractId"));
            ps.setObject(7, norm.get("receivableId"));
            ps.setString(8, ((AllowanceType) norm.get("adjustmentType")).name());
            ps.setString(9, (String) norm.get("creditNoteNumber"));
            ps.setDate(10, Date.valueOf((LocalDate) norm.get("adjustmentDate")));
            ps.setBigDecimal(11, (BigDecimal) norm.get("originalReceivableAmount"));
            ps.setBigDecimal(12, (BigDecimal) norm.get("netAdjustmentAmount"));
            ps.setBigDecimal(13, (BigDecimal) norm.get("taxAdjustmentAmount"));
            ps.setBigDecimal(14, (BigDecimal) norm.get("grossAdjustmentAmount"));
            ps.setString(15, (String) norm.get("vatRedInvoiceInfo"));
            ps.setString(16, (String) norm.get("reason"));
            ps.setObject(17, preparerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert allowance adjustment", e);
        }
        return getAllowanceAdjustment(id).orElseThrow();
    }

    public Optional<AllowanceAdjustment> getAllowanceAdjustment(UUID id) {
        String sql = "SELECT id, tenant_id, book_id, year_id, customer_id, contract_id, receivable_id, " +
                "adjustment_type, credit_note_number, adjustment_date, original_receivable_amount, " +
                "net_adjustment_amount, tax_adjustment_amount, gross_adjustment_amount, vat_red_invoice_info, " +
                "reason, status, preparer_id, approver_id, approval_digest, journal_id, created_at, version " +
                "FROM plugin_finance_ar_allowance_adjustment WHERE id = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new AllowanceAdjustment(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("book_id", UUID.class),
                            rs.getObject("year_id", UUID.class),
                            rs.getObject("customer_id", UUID.class),
                            rs.getObject("contract_id", UUID.class),
                            rs.getObject("receivable_id", UUID.class),
                            AllowanceType.valueOf(rs.getString("adjustment_type")),
                            rs.getString("credit_note_number"),
                            rs.getDate("adjustment_date").toLocalDate(),
                            rs.getBigDecimal("original_receivable_amount"),
                            rs.getBigDecimal("net_adjustment_amount"),
                            rs.getBigDecimal("tax_adjustment_amount"),
                            rs.getBigDecimal("gross_adjustment_amount"),
                            rs.getString("vat_red_invoice_info"),
                            rs.getString("reason"),
                            AllowanceStatus.valueOf(rs.getString("status")),
                            rs.getObject("preparer_id", UUID.class),
                            rs.getObject("approver_id", UUID.class),
                            rs.getString("approval_digest"),
                            rs.getObject("journal_id", UUID.class),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getLong("version")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read allowance adjustment", e);
        }
        return Optional.empty();
    }

    public void approveAllowanceAdjustment(UUID id, UUID approverId, String digest) {
        String sql = "UPDATE plugin_finance_ar_allowance_adjustment " +
                "SET status = 'POSTED', approver_id = ?, approval_digest = ?, version = version + 1 " +
                "WHERE id = ? AND status = 'SUBMITTED'";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, approverId);
            ps.setString(2, digest);
            ps.setObject(3, id);
            int rows = ps.executeUpdate();
            if (rows == 0) throw new SubledgerException("ALLOWANCE_NOT_SUBMITTED");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to approve allowance adjustment", e);
        }
    }
}
