package com.lumira.finance.inventory;

import static com.lumira.finance.inventory.InventoryModel.*;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Service for Long-Term Asset Extensions:
 * - Construction In Progress (CIP 在建工程转固) accumulation & multi-card capitalization
 * - Tech R&D expenditure statutory capitalization
 * - CAS 8 Non-reversible Asset Impairment
 * - Physical Asset Stocktake & Tag/Location Archive
 */
@Service
public class AssetExtensionService {

    public void createCipProject(Connection c, Scope scope, CipProject project) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_asset_cip_project(
                tenant_id, book_id, id, project_code, project_name, accumulated_cost, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, project.id());
            ps.setString(4, project.projectCode());
            ps.setString(5, project.projectName());
            ps.setBigDecimal(6, project.accumulatedCost());
            ps.setString(7, project.status());
            ps.executeUpdate();
        }
    }

    public void addCipCost(Connection c, Scope scope, UUID projectId, BigDecimal additionalCost) throws SQLException {
        if (additionalCost == null || additionalCost.signum() <= 0) {
            throw new IllegalArgumentException("POSITIVE_CIP_COST_REQUIRED");
        }
        String sql = """
            UPDATE plugin_finance_asset_cip_project
            SET accumulated_cost = accumulated_cost + ?
            WHERE tenant_id = ? AND book_id = ? AND id = ? AND status IN ('UNDER_CONSTRUCTION','READY_FOR_USE')
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBigDecimal(1, additionalCost);
            ps.setObject(2, scope.tenantId());
            ps.setObject(3, scope.bookId());
            ps.setObject(4, projectId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException("CIP_PROJECT_NOT_FOUND_OR_CLOSED");
            }
        }
    }

    public void capitalizeCip(Connection c, Scope scope, CipCapitalizationPlan plan, Actor approver) throws SQLException {
        if (approver.personId() == null) {
            throw new IllegalArgumentException("APPROVER_PERSON_ID_REQUIRED");
        }
        if (plan.makerId().equals(approver.personId()) || plan.makerId().equals(approver.accountId())) {
            throw new IllegalArgumentException("INDEPENDENT_NATURAL_PERSON_REQUIRED");
        }
        if (plan.targetCards() == null || plan.targetCards().isEmpty()) {
            throw new IllegalArgumentException("TARGET_ASSET_CARDS_EMPTY");
        }

        // Card cost sum must exactly equal capitalized CIP cost
        BigDecimal cardCostSum = ZERO_8;
        for (CipCapitalizationCard card : plan.targetCards()) {
            cardCostSum = cardCostSum.add(card.cost());
        }
        if (cardCostSum.compareTo(plan.capitalizedCost()) != 0) {
            throw new IllegalArgumentException("CIP_CAPITALIZATION_CARDS_MISMATCH");
        }

        // Verify CIP balance
        String checkSql = "SELECT accumulated_cost, status FROM plugin_finance_asset_cip_project WHERE tenant_id=? AND book_id=? AND id=? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, plan.projectId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("CIP_PROJECT_NOT_FOUND");
                BigDecimal accumulated = rs.getBigDecimal(1);
                if (accumulated.compareTo(plan.capitalizedCost()) < 0) {
                    throw new IllegalArgumentException("INSUFFICIENT_CIP_BALANCE");
                }
            }
        }

        // Deduct CIP balance
        String updateCip = """
            UPDATE plugin_finance_asset_cip_project
            SET accumulated_cost = accumulated_cost - ?,
                status = CASE WHEN accumulated_cost - ? = 0 THEN 'CAPITALIZED' ELSE status END
            WHERE tenant_id=? AND book_id=? AND id=?
        """;
        try (PreparedStatement ps = c.prepareStatement(updateCip)) {
            ps.setBigDecimal(1, plan.capitalizedCost());
            ps.setBigDecimal(2, plan.capitalizedCost());
            ps.setObject(3, scope.tenantId());
            ps.setObject(4, scope.bookId());
            ps.setObject(5, plan.projectId());
            ps.executeUpdate();
        }

        // Serialize cards to JSON array
        StringBuilder cardsJson = new StringBuilder("[");
        for (int i = 0; i < plan.targetCards().size(); i++) {
            if (i > 0) cardsJson.append(",");
            CipCapitalizationCard card = plan.targetCards().get(i);
            cardsJson.append(String.format(java.util.Locale.ROOT,
                    "{\"name\":\"%s\",\"classification\":\"%s\",\"location\":\"%s\",\"custodian\":\"%s\",\"cost\":\"%s\",\"firstPeriod\":\"%s\",\"months\":%d,\"residual\":\"%s\"}",
                    card.name(), card.classification(), card.location(), card.custodian(),
                    card.cost().toPlainString(), card.firstPeriod(), card.months(), card.residual().toPlainString()));
        }
        cardsJson.append("]");

        // Insert capitalization record
        String capSql = """
            INSERT INTO plugin_finance_asset_cip_capitalization(
                tenant_id, book_id, id, project_id, capitalized_cost, cards_count,
                cards_payload, maker_id, approver_id, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'APPROVED')
        """;
        try (PreparedStatement ps = c.prepareStatement(capSql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, plan.id());
            ps.setObject(4, plan.projectId());
            ps.setBigDecimal(5, plan.capitalizedCost());
            ps.setInt(6, plan.targetCards().size());
            ps.setString(7, cardsJson.toString());
            ps.setObject(8, plan.makerId());
            ps.setObject(9, approver.personId());
            ps.executeUpdate();
        }
    }

    public void createRdProject(Connection c, Scope scope, RdProject project) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_asset_rd_project(
                tenant_id, book_id, id, project_code, project_name, stage,
                expensed_amount, capitalized_amount, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, project.id());
            ps.setString(4, project.projectCode());
            ps.setString(5, project.projectName());
            ps.setString(6, project.stage());
            ps.setBigDecimal(7, project.expensedAmount());
            ps.setBigDecimal(8, project.capitalizedAmount());
            ps.setString(9, project.status());
            ps.executeUpdate();
        }
    }

    public void capitalizeRd(Connection c, Scope scope, RdCapitalizationPlan plan, Actor approver) throws SQLException {
        if (approver.personId() == null) {
            throw new IllegalArgumentException("APPROVER_PERSON_ID_REQUIRED");
        }
        if (plan.makerId().equals(approver.personId()) || plan.makerId().equals(approver.accountId())) {
            throw new IllegalArgumentException("INDEPENDENT_NATURAL_PERSON_REQUIRED");
        }
        if (plan.sourceEvidenceId() == null) {
            throw new IllegalArgumentException("STATUTORY_EVIDENCE_REQUIRED");
        }

        // Verify project stage is DEVELOPMENT and balance is sufficient
        String checkSql = "SELECT stage, capitalized_amount, status FROM plugin_finance_asset_rd_project WHERE tenant_id=? AND book_id=? AND id=? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, plan.projectId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("RD_PROJECT_NOT_FOUND");
                String stage = rs.getString(1);
                BigDecimal capitalizedAmount = rs.getBigDecimal(2);
                if (!"DEVELOPMENT".equals(stage)) {
                    throw new IllegalStateException("RESEARCH_STAGE_CANNOT_CAPITALIZE");
                }
                if (capitalizedAmount.compareTo(plan.capitalizedAmount()) < 0) {
                    throw new IllegalArgumentException("INSUFFICIENT_RD_CAPITALIZED_BALANCE");
                }
            }
        }

        // Deduct RD balance & set status
        String updateRd = """
            UPDATE plugin_finance_asset_rd_project
            SET capitalized_amount = capitalized_amount - ?,
                status = 'QUALIFIED_CAPITALIZED'
            WHERE tenant_id=? AND book_id=? AND id=?
        """;
        try (PreparedStatement ps = c.prepareStatement(updateRd)) {
            ps.setBigDecimal(1, plan.capitalizedAmount());
            ps.setObject(2, scope.tenantId());
            ps.setObject(3, scope.bookId());
            ps.setObject(4, plan.projectId());
            ps.executeUpdate();
        }

        // Insert capitalization record
        String capSql = """
            INSERT INTO plugin_finance_asset_rd_capitalization(
                tenant_id, book_id, id, project_id, intangible_asset_name,
                capitalized_amount, source_evidence_id, maker_id, approver_id, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'APPROVED')
        """;
        try (PreparedStatement ps = c.prepareStatement(capSql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, plan.id());
            ps.setObject(4, plan.projectId());
            ps.setString(5, plan.intangibleAssetName());
            ps.setBigDecimal(6, plan.capitalizedAmount());
            ps.setObject(7, plan.sourceEvidenceId());
            ps.setObject(8, plan.makerId());
            ps.setObject(9, approver.personId());
            ps.executeUpdate();
        }
    }

    public void recordAssetImpairment(Connection c, Scope scope, AssetImpairmentEvaluation eval) throws SQLException {
        if ("SMALL".equals(eval.regime())) {
            throw new IllegalArgumentException("SMALL_IMPAIRMENT_BLOCKED");
        }
        if (eval.isReversible()) {
            throw new IllegalStateException("CAS8_ASSET_IMPAIRMENT_NON_REVERSIBLE");
        }

        String sql = """
            INSERT INTO plugin_finance_asset_impairment(
                tenant_id, book_id, id, asset_card_id, regime, period,
                carrying_amount, recoverable_amount, impairment_amount,
                is_reversible, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, eval.id());
            ps.setObject(4, eval.assetCardId());
            ps.setString(5, eval.regime());
            ps.setString(6, eval.period());
            ps.setBigDecimal(7, eval.carryingAmount());
            ps.setBigDecimal(8, eval.recoverableAmount());
            ps.setBigDecimal(9, eval.impairmentAmount());
            ps.setBoolean(10, false);
            ps.setString(11, eval.status());
            ps.executeUpdate();
        }
    }

    public void createAssetStocktake(Connection c, Scope scope, AssetStocktakeSheet sheet, List<AssetStocktakeLine> lines) throws SQLException {
        String sheetSql = """
            INSERT INTO plugin_finance_asset_stocktake(
                tenant_id, book_id, id, sheet_no, stocktake_date, status, maker_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sheetSql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, sheet.id());
            ps.setString(4, sheet.sheetNo());
            ps.setDate(5, Date.valueOf(sheet.stocktakeDate()));
            ps.setString(6, sheet.status());
            ps.setObject(7, sheet.makerId());
            ps.executeUpdate();
        }

        String lineSql = """
            INSERT INTO plugin_finance_asset_stocktake_line(
                tenant_id, book_id, stocktake_id, id, asset_card_id, tag_rfid,
                book_location, actual_location, variance_type, disposition
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(lineSql)) {
            for (AssetStocktakeLine line : lines) {
                ps.setObject(1, scope.tenantId());
                ps.setObject(2, scope.bookId());
                ps.setObject(3, sheet.id());
                ps.setObject(4, line.id());
                ps.setObject(5, line.assetCardId());
                ps.setString(6, line.tagRfid());
                ps.setString(7, line.bookLocation());
                ps.setString(8, line.actualLocation());
                ps.setString(9, line.varianceType());
                ps.setString(10, line.disposition());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void approveAssetStocktake(Connection c, Scope scope, UUID stocktakeId, Actor approver) throws SQLException {
        String checkSql = "SELECT maker_id, status FROM plugin_finance_asset_stocktake WHERE tenant_id=? AND book_id=? AND id=?";
        UUID makerId;
        String status;
        try (PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setObject(1, scope.tenantId());
            ps.setObject(2, scope.bookId());
            ps.setObject(3, stocktakeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("ASSET_STOCKTAKE_NOT_FOUND");
                makerId = (UUID) rs.getObject(1);
                status = rs.getString(2);
            }
        }

        if ("APPROVED".equals(status) || "CLOSED".equals(status)) {
            throw new IllegalStateException("ALREADY_APPROVED");
        }
        if (approver.personId() == null) {
            throw new IllegalArgumentException("APPROVER_PERSON_ID_REQUIRED");
        }
        if (makerId.equals(approver.personId()) || makerId.equals(approver.accountId())) {
            throw new IllegalArgumentException("INDEPENDENT_NATURAL_PERSON_REQUIRED");
        }

        String updateSql = "UPDATE plugin_finance_asset_stocktake SET status='APPROVED', approver_id=? WHERE tenant_id=? AND book_id=? AND id=?";
        try (PreparedStatement ps = c.prepareStatement(updateSql)) {
            ps.setObject(1, approver.personId());
            ps.setObject(2, scope.tenantId());
            ps.setObject(3, scope.bookId());
            ps.setObject(4, stocktakeId);
            ps.executeUpdate();
        }
    }
}
