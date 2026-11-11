package com.lumira.finance.inventory;

import static com.lumira.finance.inventory.InventoryModel.*;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transactional JDBC service for Inventory Operations (INV01, INV02, INV03).
 */
@Service
public class InventoryService {

    public void createWarehouse(Connection c, Scope scope, Warehouse warehouse) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_inventory_warehouse(tenant_id, book_id, id, code, name, location, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, warehouse.id());
            ps.setString(4, warehouse.code());
            ps.setString(5, warehouse.name());
            ps.setString(6, warehouse.location());
            ps.setBoolean(7, warehouse.isActive());
            ps.executeUpdate();
        }
    }

    public void createItem(Connection c, Scope scope, InventoryItem item) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_inventory_item(
                tenant_id, book_id, id, code, name, spec, unit, valuation_method,
                inventory_account_id, cogs_account_id, loss_gain_account_id,
                impairment_account_id, impairment_expense_account_id, is_active
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, item.id());
            ps.setString(4, item.code());
            ps.setString(5, item.name());
            ps.setString(6, item.spec());
            ps.setString(7, item.unit());
            ps.setString(8, item.valuationMethod().name());
            ps.setObject(9, item.inventoryAccountId());
            ps.setObject(10, item.cogsAccountId());
            ps.setObject(11, item.lossGainAccountId());
            ps.setObject(12, item.impairmentAccountId());
            ps.setObject(13, item.impairmentExpenseAccountId());
            ps.setBoolean(14, item.isActive());
            ps.executeUpdate();
        }
    }

    public void recordStockMovement(Connection c, Scope scope, StockMovement m) throws SQLException {
        if (m.quantity() == null || m.quantity().signum() <= 0) {
            throw new IllegalArgumentException("POSITIVE_QUANTITY_REQUIRED");
        }
        if (m.movementType() == MovementType.PURCHASE_IN && m.sourceEvidenceId() == null) {
            throw new IllegalArgumentException("MANDATORY_SOURCE_EVIDENCE_REQUIRED");
        }
        if (m.movementType() == MovementType.PURCHASE_IN || m.movementType() == MovementType.PRODUCTION_IN) {
            if (m.destWarehouseId() == null) {
                throw new IllegalArgumentException("DEST_WAREHOUSE_REQUIRED");
            }
        }
        if (m.movementType() == MovementType.SALES_OUT || m.movementType() == MovementType.PRODUCTION_ISSUE) {
            if (m.sourceWarehouseId() == null) {
                throw new IllegalArgumentException("SOURCE_WAREHOUSE_REQUIRED");
            }
        }
        if (m.movementType() == MovementType.TRANSFER) {
            if (m.sourceWarehouseId() == null || m.destWarehouseId() == null
                    || m.sourceWarehouseId().equals(m.destWarehouseId())) {
                throw new IllegalArgumentException("DISTINCT_TRANSFER_WAREHOUSES_REQUIRED");
            }
        }

        // Outbound stock balance check
        if (m.movementType() == MovementType.SALES_OUT || m.movementType() == MovementType.PRODUCTION_ISSUE
                || m.movementType() == MovementType.TRANSFER) {
            BigDecimal stock = getWarehouseStock(c, scope, m.sourceWarehouseId(), m.itemId());
            if (stock.compareTo(m.quantity()) < 0) {
                throw new IllegalArgumentException("INSUFFICIENT_STOCK");
            }

            // If a specific batch is specified for outbound, enforce and decrement remaining batch quantity
            if (m.batchId() != null) {
                String updateBatchSql = """
                    UPDATE plugin_finance_inventory_batch
                    SET remaining_quantity = remaining_quantity - ?
                    WHERE tenant_id = ? AND book_id = ? AND id = ? AND remaining_quantity >= ?
                """;
                try (PreparedStatement ubs = c.prepareStatement(updateBatchSql)) {
                    ubs.setBigDecimal(1, m.quantity());
                    ubs.setObject(2, scope.tenantId());
                    ubs.setObject(3, scope.bookId());
                    ubs.setObject(4, m.batchId());
                    ubs.setBigDecimal(5, m.quantity());
                    if (ubs.executeUpdate() == 0) {
                        throw new IllegalArgumentException("INSUFFICIENT_BATCH_QUANTITY");
                    }
                }
            }
        }

        String sql = """
            INSERT INTO plugin_finance_inventory_movement(
                tenant_id, book_id, year_id, id, movement_no, movement_type, item_id,
                source_warehouse_id, dest_warehouse_id, batch_id, quantity, unit_cost,
                total_cost, source_evidence_id, business_date, status, journal_id,
                created_by, approved_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, scope.yearId());
            ps.setObject(4, m.id());
            ps.setString(5, m.movementNo());
            ps.setString(6, m.movementType().name());
            ps.setObject(7, m.itemId());
            ps.setObject(8, m.sourceWarehouseId());
            ps.setObject(9, m.destWarehouseId());
            ps.setObject(10, m.batchId());
            ps.setBigDecimal(11, m.quantity());
            ps.setBigDecimal(12, m.unitCost());
            ps.setBigDecimal(13, m.totalCost());
            ps.setObject(14, m.sourceEvidenceId());
            ps.setDate(15, Date.valueOf(m.businessDate()));
            ps.setString(16, m.status());
            ps.setObject(17, m.journalId());
            ps.setObject(18, m.createdBy());
            ps.setObject(19, m.approvedBy());
            ps.executeUpdate();
        }

        // Inbound creates or updates batch layer
        if (m.movementType() == MovementType.PURCHASE_IN || m.movementType() == MovementType.PRODUCTION_IN) {
            if (m.batchId() != null) {
                String batchSql = """
                    INSERT INTO plugin_finance_inventory_batch(
                        tenant_id, book_id, id, item_id, batch_no, inbound_date,
                        initial_cost, remaining_quantity
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, book_id, item_id, batch_no)
                    DO UPDATE SET remaining_quantity = plugin_finance_inventory_batch.remaining_quantity + EXCLUDED.remaining_quantity
                """;
                try (PreparedStatement bps = c.prepareStatement(batchSql)) {
                    bps.setObject(1, scope.tenantId());
                    bps.setObject(2, scope.bookId());
                    bps.setObject(3, m.batchId());
                    bps.setObject(4, m.itemId());
                    bps.setString(5, "BATCH-" + m.movementNo());
                    bps.setDate(6, Date.valueOf(m.businessDate()));
                    bps.setBigDecimal(7, m.unitCost());
                    bps.setBigDecimal(8, m.quantity());
                    bps.executeUpdate();
                }
            }
        }
    }

    public BigDecimal getWarehouseStock(Connection c, Scope scope, UUID warehouseId, UUID itemId) throws SQLException {
        String inSql = """
            SELECT COALESCE(SUM(quantity), 0) FROM plugin_finance_inventory_movement
            WHERE tenant_id=? AND book_id=? AND item_id=? AND dest_warehouse_id=?
            AND status IN ('SUBMITTED','APPROVED','POSTED')
        """;
        String outSql = """
            SELECT COALESCE(SUM(quantity), 0) FROM plugin_finance_inventory_movement
            WHERE tenant_id=? AND book_id=? AND item_id=? AND source_warehouse_id=?
            AND status IN ('SUBMITTED','APPROVED','POSTED')
        """;
        BigDecimal inQty = ZERO_8;
        BigDecimal outQty = ZERO_8;
        try (PreparedStatement ps = c.prepareStatement(inSql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, itemId);
            ps.setObject(4, warehouseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) inQty = rs.getBigDecimal(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(outSql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, itemId);
            ps.setObject(4, warehouseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) outQty = rs.getBigDecimal(1);
            }
        }
        return inQty.subtract(outQty);
    }

    public BigDecimal getTotalBookStock(Connection c, Scope scope, UUID itemId) throws SQLException {
        String inSql = """
            SELECT COALESCE(SUM(quantity), 0) FROM plugin_finance_inventory_movement
            WHERE tenant_id=? AND book_id=? AND item_id=?
            AND movement_type IN ('PURCHASE_IN','PRODUCTION_IN')
            AND status IN ('SUBMITTED','APPROVED','POSTED')
        """;
        String outSql = """
            SELECT COALESCE(SUM(quantity), 0) FROM plugin_finance_inventory_movement
            WHERE tenant_id=? AND book_id=? AND item_id=?
            AND movement_type IN ('SALES_OUT','PRODUCTION_ISSUE')
            AND status IN ('SUBMITTED','APPROVED','POSTED')
        """;
        BigDecimal inQty = ZERO_8;
        BigDecimal outQty = ZERO_8;
        try (PreparedStatement ps = c.prepareStatement(inSql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) inQty = rs.getBigDecimal(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(outSql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) outQty = rs.getBigDecimal(1);
            }
        }
        return inQty.subtract(outQty);
    }

    public void createStocktake(Connection c, Scope scope, StocktakeSheet sheet, List<StocktakeLine> lines) throws SQLException {
        String sheetSql = """
            INSERT INTO plugin_finance_inventory_stocktake(
                tenant_id, book_id, id, sheet_no, warehouse_id, cutoff_date,
                status, maker_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sheetSql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, sheet.id());
            ps.setString(4, sheet.sheetNo());
            ps.setObject(5, sheet.warehouseId());
            ps.setDate(6, Date.valueOf(sheet.cutoffDate()));
            ps.setString(7, sheet.status());
            ps.setObject(8, sheet.makerId());
            ps.executeUpdate();
        }

        String lineSql = """
            INSERT INTO plugin_finance_inventory_stocktake_line(
                tenant_id, book_id, stocktake_id, id, item_id, batch_id,
                book_quantity, book_unit_cost, counted_quantity, variance_quantity,
                variance_amount, variance_type, disposition
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(lineSql)) {
            for (StocktakeLine line : lines) {
                ps.setObject(1, scope.tenantId());
                ps.setObject(2, scope.bookId());
                ps.setObject(3, sheet.id());
                ps.setObject(4, line.id());
                ps.setObject(5, line.itemId());
                ps.setObject(6, line.batchId());
                ps.setBigDecimal(7, line.bookQuantity());
                ps.setBigDecimal(8, line.bookUnitCost());
                ps.setBigDecimal(9, line.countedQuantity());
                ps.setBigDecimal(10, line.varianceQuantity());
                ps.setBigDecimal(11, line.varianceAmount());
                ps.setString(12, line.varianceType());
                ps.setString(13, line.disposition());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void approveStocktake(Connection c, Scope scope, UUID stocktakeId, Actor approver) throws SQLException {
        // Fetch maker
        String checkSql = "SELECT maker_id, status FROM plugin_finance_inventory_stocktake WHERE tenant_id=? AND book_id=? AND id=?";
        UUID makerId;
        String status;
        try (PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, stocktakeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("STOCKTAKE_NOT_FOUND");
                makerId = (UUID) rs.getObject(1);
                status = rs.getString(2);
            }
        }

        if ("APPROVED".equals(status) || "POSTED".equals(status)) {
            throw new IllegalStateException("ALREADY_APPROVED");
        }
        if (approver.personId() == null) {
            throw new IllegalArgumentException("APPROVER_PERSON_ID_REQUIRED");
        }
        if (makerId.equals(approver.personId()) || makerId.equals(approver.accountId())) {
            throw new IllegalArgumentException("INDEPENDENT_NATURAL_PERSON_REQUIRED");
        }

        String updateSql = "UPDATE plugin_finance_inventory_stocktake SET status='APPROVED', approver_id=? WHERE tenant_id=? AND book_id=? AND id=?";
        try (PreparedStatement ps = c.prepareStatement(updateSql)) {
            ps.setObject(1, approver.personId());
            ps.setObject(2, scope.tenantId());
            ps.setObject(3, scope.bookId());
            ps.setObject(4, stocktakeId);
            ps.executeUpdate();
        }
    }

    public void recordImpairment(Connection c, Scope scope, InventoryImpairmentEvaluation eval) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_inventory_impairment(
                tenant_id, book_id, id, item_id, regime, period, book_cost,
                nrv, prior_impairment, impairment_adjustment, new_impairment, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, eval.id());
            ps.setObject(4, eval.itemId());
            ps.setString(5, eval.regime());
            ps.setString(6, eval.period());
            ps.setBigDecimal(7, eval.bookCost());
            ps.setBigDecimal(8, eval.nrv());
            ps.setBigDecimal(9, eval.priorImpairment());
            ps.setBigDecimal(10, eval.impairmentAdjustment());
            ps.setBigDecimal(11, eval.newImpairment());
            ps.setString(12, eval.status());
            ps.executeUpdate();
        }
    }
}
