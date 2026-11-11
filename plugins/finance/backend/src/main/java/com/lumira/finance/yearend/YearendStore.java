package com.lumira.finance.yearend;

import static com.lumira.finance.yearend.YearendModel.*;

import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * JDBC Store for Lane W09 (GL and Year-End) candidate persistence.
 */
@Repository
public class YearendStore {
    private final ObjectMapper mapper;

    public YearendStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    private String json(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON_SERIALIZATION_FAILED", e);
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("JSON_DESERIALIZATION_FAILED", e);
        }
    }

    private <T> List<T> fromJsonList(String json, Class<T> clazz) {
        if (json == null) return List.of();
        try {
            var javaType = mapper.getTypeFactory().constructCollectionType(List.class, clazz);
            return mapper.readValue(json, javaType);
        } catch (Exception e) {
            return List.of();
        }
    }

    // --- GL01 Cutover Plan CRUD ---
    public void insertCutoverPlan(Connection c, CutoverPlan plan) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_cutover_plan
            (tenant_id, book_id, year_id, id, cutover_date, mode, status, version, content_digest, source_system, source_watermark, maker, approver, locked_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, plan.tenantId());
            ps.setObject(2, plan.bookId());
            ps.setObject(3, plan.yearId());
            ps.setObject(4, plan.id());
            ps.setDate(5, java.sql.Date.valueOf(plan.cutoverDate()));
            ps.setString(6, plan.mode());
            ps.setString(7, plan.status());
            ps.setLong(8, plan.version());
            ps.setString(9, plan.contentDigest());
            ps.setString(10, plan.sourceSystem());
            ps.setString(11, plan.sourceWatermark());
            ps.setString(12, json(plan.maker()));
            ps.setString(13, plan.approver() == null ? null : json(plan.approver()));
            ps.setTimestamp(14, plan.lockedAt() == null ? null : Timestamp.from(plan.lockedAt()));
            ps.setTimestamp(15, Timestamp.from(plan.createdAt()));
            ps.setTimestamp(16, Timestamp.from(plan.updatedAt()));
            ps.executeUpdate();
        }
    }

    public void insertOpeningBalances(Connection c, UUID tenantId, UUID bookId, UUID planId, List<OpeningBalanceItem> items) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_cutover_opening_balance
            (tenant_id, book_id, cutover_plan_id, id, account_id, account_code, account_name, currency, direction, amount, aux_dimensions, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (OpeningBalanceItem item : items) {
                ps.setObject(1, tenantId);
                ps.setObject(2, bookId);
                ps.setObject(3, planId);
                ps.setObject(4, item.id());
                ps.setObject(5, item.accountId());
                ps.setString(6, item.accountCode());
                ps.setString(7, item.accountName());
                ps.setString(8, item.currency());
                ps.setString(9, item.direction());
                ps.setBigDecimal(10, item.amount());
                ps.setString(11, json(item.auxDimensions() == null ? Map.of() : item.auxDimensions()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void insertBaselines(Connection c, UUID tenantId, UUID bookId, UUID planId, List<BaselineItem> items) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_cutover_baseline_item
            (tenant_id, book_id, cutover_plan_id, id, baseline_type, period_month, category_code, category_name, amount, aux_payload, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (BaselineItem item : items) {
                ps.setObject(1, tenantId);
                ps.setObject(2, bookId);
                ps.setObject(3, planId);
                ps.setObject(4, item.id());
                ps.setString(5, item.baselineType());
                ps.setInt(6, item.periodMonth());
                ps.setString(7, item.categoryCode());
                ps.setString(8, item.categoryName());
                ps.setBigDecimal(9, item.amount());
                ps.setString(10, json(item.auxPayload() == null ? Map.of() : item.auxPayload()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void insertTieOuts(Connection c, UUID tenantId, UUID bookId, UUID planId, List<TieOutResult> items) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_cutover_tie_out
            (tenant_id, book_id, cutover_plan_id, id, check_item, passed, gl_amount, subledger_amount, difference, details, evaluated_at)
            VALUES (?, ?, ?, gen_random_uuid(), ?, ?, ?, ?, ?, ?::jsonb, now())
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (TieOutResult item : items) {
                ps.setObject(1, tenantId);
                ps.setObject(2, bookId);
                ps.setObject(3, planId);
                ps.setString(4, item.checkItem());
                ps.setBoolean(5, item.passed());
                ps.setBigDecimal(6, item.glAmount());
                ps.setBigDecimal(7, item.subledgerAmount());
                ps.setBigDecimal(8, item.difference());
                ps.setString(9, json(item.details() == null ? Map.of() : item.details()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void updateCutoverStatus(Connection c, UUID tenantId, UUID bookId, UUID planId, String newStatus, PersonActor approver, Instant lockedAt) throws SQLException {
        String sql = """
            UPDATE plugin_finance_cutover_plan
            SET status = ?, approver = ?::jsonb, locked_at = ?, version = version + 1, updated_at = now()
            WHERE tenant_id = ? AND book_id = ? AND id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setString(2, approver == null ? null : json(approver));
            ps.setTimestamp(3, lockedAt == null ? null : Timestamp.from(lockedAt));
            ps.setObject(4, tenantId);
            ps.setObject(5, bookId);
            ps.setObject(6, planId);
            ps.executeUpdate();
        }
    }

    public Optional<CutoverPlan> findCutoverPlan(Connection c, UUID tenantId, UUID bookId, UUID planId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_cutover_plan WHERE tenant_id = ? AND book_id = ? AND id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, planId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new CutoverPlan(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("tenant_id"),
                        (UUID) rs.getObject("book_id"),
                        (UUID) rs.getObject("year_id"),
                        rs.getDate("cutover_date").toLocalDate(),
                        rs.getString("mode"),
                        rs.getString("status"),
                        rs.getLong("version"),
                        rs.getString("content_digest"),
                        rs.getString("source_system"),
                        rs.getString("source_watermark"),
                        fromJson(rs.getString("maker"), PersonActor.class),
                        fromJson(rs.getString("approver"), PersonActor.class),
                        rs.getTimestamp("locked_at") == null ? null : rs.getTimestamp("locked_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                    ));
                }
            }
        }
        return Optional.empty();
    }

    // --- GL02 & GL03 Rules and Policies ---
    public void insertRuleTemplate(Connection c, AutoRuleTemplate tmpl) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_auto_rule_template
            (tenant_id, book_id, id, rule_code, rule_name, business_type, version, status, content_digest, line_templates, maker, approver, approved_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tmpl.tenantId());
            ps.setObject(2, tmpl.bookId());
            ps.setObject(3, tmpl.id());
            ps.setString(4, tmpl.ruleCode());
            ps.setString(5, tmpl.ruleName());
            ps.setString(6, tmpl.businessType());
            ps.setInt(7, tmpl.version());
            ps.setString(8, tmpl.status());
            ps.setString(9, tmpl.contentDigest());
            ps.setString(10, json(tmpl.lineTemplates()));
            ps.setString(11, json(tmpl.maker()));
            ps.setString(12, tmpl.approver() == null ? null : json(tmpl.approver()));
            ps.setTimestamp(13, tmpl.approvedAt() == null ? null : Timestamp.from(tmpl.approvedAt()));
            ps.setTimestamp(14, Timestamp.from(tmpl.createdAt()));
            ps.executeUpdate();
        }
    }

    public void insertReviewPolicy(Connection c, AutoReviewPolicy policy) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_auto_rule_policy
            (tenant_id, book_id, id, policy_code, policy_name, version, status, content_digest, scope_business_types, allowed_account_codes, max_amount, zero_exception_required, maker, approver, approved_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, policy.tenantId());
            ps.setObject(2, policy.bookId());
            ps.setObject(3, policy.id());
            ps.setString(4, policy.policyCode());
            ps.setString(5, policy.policyName());
            ps.setInt(6, policy.version());
            ps.setString(7, policy.status());
            ps.setString(8, policy.contentDigest());
            ps.setString(9, json(policy.scopeBusinessTypes()));
            ps.setString(10, json(policy.allowedAccountCodes()));
            ps.setBigDecimal(11, policy.maxAmount());
            ps.setBoolean(12, policy.zeroExceptionRequired());
            ps.setString(13, json(policy.maker()));
            ps.setString(14, policy.approver() == null ? null : json(policy.approver()));
            ps.setTimestamp(15, policy.approvedAt() == null ? null : Timestamp.from(policy.approvedAt()));
            ps.setTimestamp(16, Timestamp.from(policy.createdAt()));
            ps.executeUpdate();
        }
    }

    public void insertRuleExecution(Connection c, AutoRuleExecution exec) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_auto_rule_execution
            (tenant_id, book_id, year_id, id, journal_id, source_event_id, source_system, stable_effect_key, template_id, template_version, policy_id, policy_version, policy_digest, auto_reviewed, exception_count, audit_evidence, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, exec.tenantId());
            ps.setObject(2, exec.bookId());
            ps.setObject(3, exec.yearId());
            ps.setObject(4, exec.id());
            ps.setObject(5, exec.journalId());
            ps.setString(6, exec.sourceEventId());
            ps.setString(7, exec.sourceSystem());
            ps.setString(8, exec.stableEffectKey());
            ps.setObject(9, exec.templateId());
            ps.setInt(10, exec.templateVersion());
            ps.setObject(11, exec.policyId());
            if (exec.policyVersion() != null) ps.setInt(12, exec.policyVersion()); else ps.setNull(12, Types.INTEGER);
            ps.setString(13, exec.policyDigest());
            ps.setBoolean(14, exec.autoReviewed());
            ps.setInt(15, exec.exceptionCount());
            ps.setString(16, json(exec.auditEvidence()));
            ps.setTimestamp(17, Timestamp.from(exec.createdAt()));
            ps.executeUpdate();
        }
    }

    // --- GL05 Journal Corrections ---
    public void insertCorrectionRecord(Connection c, JournalCorrectionRecord rec) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_journal_correction
            (tenant_id, book_id, target_year_id, id, source_year_id, source_journal_id, source_posting_seq, source_digest, correction_journal_id, correction_kind, standard, cas_account_code, status, reason, allocations, maker, approver, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, rec.tenantId());
            ps.setObject(2, rec.bookId());
            ps.setObject(3, rec.targetYearId());
            ps.setObject(4, rec.id());
            ps.setObject(5, rec.sourceYearId());
            ps.setObject(6, rec.sourceJournalId());
            ps.setLong(7, rec.sourcePostingSeq());
            ps.setString(8, rec.sourceDigest());
            ps.setObject(9, rec.correctionJournalId());
            ps.setString(10, rec.correctionKind());
            ps.setString(11, rec.standard());
            ps.setString(12, rec.casAccountCode());
            ps.setString(13, rec.status());
            ps.setString(14, rec.reason());
            ps.setString(15, json(rec.allocations()));
            ps.setString(16, json(rec.maker()));
            ps.setString(17, rec.approver() == null ? null : json(rec.approver()));
            ps.setTimestamp(18, Timestamp.from(rec.createdAt()));
            ps.executeUpdate();
        }
    }

    // --- GL06 Period Close Check Plan ---
    public void insertPeriodClosePlan(Connection c, PeriodCloseCheckPlan plan) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_period_close_check_plan
            (tenant_id, book_id, year_id, period_id, id, version, status, content_digest, source_watermark, all_checks_passed, unposted_journals_count, trial_balance_debit, trial_balance_credit, trial_balance_balanced, bank_reconciliation_complete, ar_tie_out_passed, ap_tie_out_passed, asset_tie_out_passed, inventory_tie_out_passed, payroll_tie_out_passed, cashflow_coverage_complete, tax_worksheet_ready, maker, approver, approved_at, closed_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, plan.tenantId());
            ps.setObject(2, plan.bookId());
            ps.setObject(3, plan.yearId());
            ps.setObject(4, plan.periodId());
            ps.setObject(5, plan.id());
            ps.setLong(6, plan.version());
            ps.setString(7, plan.status());
            ps.setString(8, plan.contentDigest());
            ps.setLong(9, plan.sourceWatermark());
            ps.setBoolean(10, plan.allChecksPassed());
            ps.setInt(11, plan.unpostedJournalsCount());
            ps.setBigDecimal(12, plan.trialBalanceDebit());
            ps.setBigDecimal(13, plan.trialBalanceCredit());
            ps.setBoolean(14, plan.trialBalanceBalanced());
            ps.setBoolean(15, plan.bankReconciliationComplete());
            ps.setBoolean(16, plan.arTieOutPassed());
            ps.setBoolean(17, plan.apTieOutPassed());
            ps.setBoolean(18, plan.assetTieOutPassed());
            ps.setBoolean(19, plan.inventoryTieOutPassed());
            ps.setBoolean(20, plan.payrollTieOutPassed());
            ps.setBoolean(21, plan.cashflowCoverageComplete());
            ps.setBoolean(22, plan.taxWorksheetReady());
            ps.setString(23, json(plan.maker()));
            ps.setString(24, plan.approver() == null ? null : json(plan.approver()));
            ps.setTimestamp(25, plan.approvedAt() == null ? null : Timestamp.from(plan.approvedAt()));
            ps.setTimestamp(26, plan.closedAt() == null ? null : Timestamp.from(plan.closedAt()));
            ps.setTimestamp(27, Timestamp.from(plan.createdAt()));
            ps.executeUpdate();
        }

        if (plan.items() != null && !plan.items().isEmpty()) {
            String itemSql = """
                INSERT INTO plugin_finance_period_close_check_item
                (tenant_id, book_id, plan_id, id, check_category, check_code, check_title, passed, details, evaluated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
            """;
            try (PreparedStatement ips = c.prepareStatement(itemSql)) {
                for (PeriodCloseCheckItem item : plan.items()) {
                    ips.setObject(1, plan.tenantId());
                    ips.setObject(2, plan.bookId());
                    ips.setObject(3, plan.id());
                    ips.setObject(4, item.id());
                    ips.setString(5, item.checkCategory());
                    ips.setString(6, item.checkCode());
                    ips.setString(7, item.checkTitle());
                    ips.setBoolean(8, item.passed());
                    ips.setString(9, json(item.details() == null ? Map.of() : item.details()));
                    ips.addBatch();
                }
                ips.executeBatch();
            }
        }
    }

    public void updatePeriodCloseStatus(Connection c, UUID tenantId, UUID bookId, UUID planId, String newStatus, PersonActor approver, Instant closedAt) throws SQLException {
        String sql = """
            UPDATE plugin_finance_period_close_check_plan
            SET status = ?, approver = ?::jsonb, closed_at = ?, version = version + 1
            WHERE tenant_id = ? AND book_id = ? AND id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setString(2, approver == null ? null : json(approver));
            ps.setTimestamp(3, closedAt == null ? null : Timestamp.from(closedAt));
            ps.setObject(4, tenantId);
            ps.setObject(5, bookId);
            ps.setObject(6, planId);
            ps.executeUpdate();
        }
    }

    // --- YEAR01, YEAR02, YEAR03 Carry Batch Plan ---
    public void insertCarryBatchPlan(Connection c, CarryBatchPlan plan) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_carry_batch_plan
            (tenant_id, book_id, source_year_id, target_year_id, id, kind, status, source_close_version, source_watermark, target_opening_version, mapping_version, content_version, content_digest, previous_batch_id, reason, maker, approver, committed_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, plan.tenantId());
            ps.setObject(2, plan.bookId());
            ps.setObject(3, plan.sourceYearId());
            ps.setObject(4, plan.targetYearId());
            ps.setObject(5, plan.id());
            ps.setString(6, plan.kind());
            ps.setString(7, plan.status());
            ps.setInt(8, plan.sourceCloseVersion());
            ps.setLong(9, plan.sourceWatermark());
            ps.setInt(10, plan.targetOpeningVersion());
            ps.setString(11, plan.mappingVersion());
            ps.setLong(12, plan.contentVersion());
            ps.setString(13, plan.contentDigest());
            ps.setObject(14, plan.previousBatchId());
            ps.setString(15, plan.reason());
            ps.setString(16, json(plan.maker()));
            ps.setString(17, plan.approver() == null ? null : json(plan.approver()));
            ps.setTimestamp(18, plan.committedAt() == null ? null : Timestamp.from(plan.committedAt()));
            ps.setTimestamp(19, Timestamp.from(plan.createdAt()));
            ps.executeUpdate();
        }

        if (plan.balances() != null && !plan.balances().isEmpty()) {
            String bSql = """
                INSERT INTO plugin_finance_carry_batch_balance
                (tenant_id, book_id, batch_id, id, source_account_id, target_account_id, source_account_code, target_account_code, currency, direction, base_amount, delta_amount, net_opening_amount, aux_dimensions)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;
            try (PreparedStatement bps = c.prepareStatement(bSql)) {
                for (CarryBalanceItem b : plan.balances()) {
                    bps.setObject(1, plan.tenantId());
                    bps.setObject(2, plan.bookId());
                    bps.setObject(3, plan.id());
                    bps.setObject(4, b.id());
                    bps.setObject(5, b.sourceAccountId());
                    bps.setObject(6, b.targetAccountId());
                    bps.setString(7, b.sourceAccountCode());
                    bps.setString(8, b.targetAccountCode());
                    bps.setString(9, b.currency());
                    bps.setString(10, b.direction());
                    bps.setBigDecimal(11, b.baseAmount());
                    bps.setBigDecimal(12, b.deltaAmount());
                    bps.setBigDecimal(13, b.netOpeningAmount());
                    bps.setString(14, json(b.auxDimensions() == null ? Map.of() : b.auxDimensions()));
                    bps.addBatch();
                }
                bps.executeBatch();
            }
        }

        if (plan.subledgerItems() != null && !plan.subledgerItems().isEmpty()) {
            String sSql = """
                INSERT INTO plugin_finance_carry_batch_subledger
                (tenant_id, book_id, batch_id, id, subledger_kind, business_ref, counterparty_id, original_date, currency, original_amount, remaining_open_amount, fx_rate, cny_equivalent, aux_payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;
            try (PreparedStatement sps = c.prepareStatement(sSql)) {
                for (CarrySubledgerItem s : plan.subledgerItems()) {
                    sps.setObject(1, plan.tenantId());
                    sps.setObject(2, plan.bookId());
                    sps.setObject(3, plan.id());
                    sps.setObject(4, s.id());
                    sps.setString(5, s.subledgerKind());
                    sps.setString(6, s.businessRef());
                    sps.setObject(7, s.counterpartyId());
                    sps.setDate(8, java.sql.Date.valueOf(s.originalDate()));
                    sps.setString(9, s.currency());
                    sps.setBigDecimal(10, s.originalAmount());
                    sps.setBigDecimal(11, s.remainingOpenAmount());
                    sps.setBigDecimal(12, s.fxRate());
                    sps.setBigDecimal(13, s.cnyEquivalent());
                    sps.setString(14, json(s.auxPayload() == null ? Map.of() : s.auxPayload()));
                    sps.addBatch();
                }
                sps.executeBatch();
            }
        }

        if (plan.restatements() != null && !plan.restatements().isEmpty()) {
            String rSql = """
                INSERT INTO plugin_finance_carry_batch_restatement
                (tenant_id, book_id, batch_id, id, restatement_scope, statement_line_code, statement_line_name, pre_correction_amount, delta_amount, restated_amount, impact_summary, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            """;
            try (PreparedStatement rps = c.prepareStatement(rSql)) {
                for (RestatementManifestItem r : plan.restatements()) {
                    rps.setObject(1, plan.tenantId());
                    rps.setObject(2, plan.bookId());
                    rps.setObject(3, plan.id());
                    rps.setObject(4, r.id());
                    rps.setString(5, r.restatementScope());
                    rps.setString(6, r.statementLineCode());
                    rps.setString(7, r.statementLineName());
                    rps.setBigDecimal(8, r.preCorrectionAmount());
                    rps.setBigDecimal(9, r.deltaAmount());
                    rps.setBigDecimal(10, r.restatedAmount());
                    rps.setString(11, r.impactSummary());
                    rps.addBatch();
                }
                rps.executeBatch();
            }
        }
    }

    public void updateCarryBatchStatus(Connection c, UUID tenantId, UUID bookId, UUID planId, String newStatus, PersonActor approver, Instant committedAt) throws SQLException {
        String sql = """
            UPDATE plugin_finance_carry_batch_plan
            SET status = ?, approver = ?::jsonb, committed_at = ?, content_version = content_version + 1
            WHERE tenant_id = ? AND book_id = ? AND id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setString(2, approver == null ? null : json(approver));
            ps.setTimestamp(3, committedAt == null ? null : Timestamp.from(committedAt));
            ps.setObject(4, tenantId);
            ps.setObject(5, bookId);
            ps.setObject(6, planId);
            ps.executeUpdate();
        }
    }

    public boolean hasUnpassedTieOuts(Connection c, UUID tenantId, UUID bookId, UUID planId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM plugin_finance_cutover_tie_out WHERE tenant_id = ? AND book_id = ? AND cutover_plan_id = ? AND passed = false";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, planId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    public Optional<AutoRuleTemplate> findRuleTemplate(Connection c, UUID tenantId, UUID bookId, UUID templateId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_auto_rule_template WHERE tenant_id = ? AND book_id = ? AND id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new AutoRuleTemplate(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("tenant_id"),
                        (UUID) rs.getObject("book_id"),
                        rs.getString("rule_code"),
                        rs.getString("rule_name"),
                        rs.getString("business_type"),
                        rs.getInt("version"),
                        rs.getString("status"),
                        rs.getString("content_digest"),
                        fromJsonList(rs.getString("line_templates"), LineRule.class),
                        fromJson(rs.getString("maker"), PersonActor.class),
                        fromJson(rs.getString("approver"), PersonActor.class),
                        rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public void updateRuleTemplateStatus(Connection c, UUID tenantId, UUID bookId, UUID templateId, String newStatus, PersonActor approver) throws SQLException {
        String sql = """
            UPDATE plugin_finance_auto_rule_template
            SET status = ?, approver = ?::jsonb, approved_at = now()
            WHERE tenant_id = ? AND book_id = ? AND id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setString(2, approver == null ? null : json(approver));
            ps.setObject(3, tenantId);
            ps.setObject(4, bookId);
            ps.setObject(5, templateId);
            ps.executeUpdate();
        }
    }

    public Optional<AutoReviewPolicy> findReviewPolicy(Connection c, UUID tenantId, UUID bookId, UUID policyId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_auto_rule_policy WHERE tenant_id = ? AND book_id = ? AND id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, policyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new AutoReviewPolicy(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("tenant_id"),
                        (UUID) rs.getObject("book_id"),
                        rs.getString("policy_code"),
                        rs.getString("policy_name"),
                        rs.getInt("version"),
                        rs.getString("status"),
                        rs.getString("content_digest"),
                        fromJsonList(rs.getString("scope_business_types"), String.class),
                        fromJsonList(rs.getString("allowed_account_codes"), String.class),
                        rs.getBigDecimal("max_amount"),
                        rs.getBoolean("zero_exception_required"),
                        fromJson(rs.getString("maker"), PersonActor.class),
                        fromJson(rs.getString("approver"), PersonActor.class),
                        rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public void updateReviewPolicyStatus(Connection c, UUID tenantId, UUID bookId, UUID policyId, String newStatus, PersonActor approver) throws SQLException {
        String sql = """
            UPDATE plugin_finance_auto_rule_policy
            SET status = ?, approver = ?::jsonb, approved_at = now()
            WHERE tenant_id = ? AND book_id = ? AND id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setString(2, approver == null ? null : json(approver));
            ps.setObject(3, tenantId);
            ps.setObject(4, bookId);
            ps.setObject(5, policyId);
            ps.executeUpdate();
        }
    }

    public Optional<PeriodCloseCheckPlan> findPeriodClosePlan(Connection c, UUID tenantId, UUID bookId, UUID planId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_period_close_check_plan WHERE tenant_id = ? AND book_id = ? AND id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, planId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PeriodCloseCheckPlan(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("tenant_id"),
                        (UUID) rs.getObject("book_id"),
                        (UUID) rs.getObject("year_id"),
                        (UUID) rs.getObject("period_id"),
                        rs.getLong("version"),
                        rs.getString("status"),
                        rs.getString("content_digest"),
                        rs.getLong("source_watermark"),
                        rs.getBoolean("all_checks_passed"),
                        rs.getInt("unposted_journals_count"),
                        rs.getBigDecimal("trial_balance_debit"),
                        rs.getBigDecimal("trial_balance_credit"),
                        rs.getBoolean("trial_balance_balanced"),
                        rs.getBoolean("bank_reconciliation_complete"),
                        rs.getBoolean("ar_tie_out_passed"),
                        rs.getBoolean("ap_tie_out_passed"),
                        rs.getBoolean("asset_tie_out_passed"),
                        rs.getBoolean("inventory_tie_out_passed"),
                        rs.getBoolean("payroll_tie_out_passed"),
                        rs.getBoolean("cashflow_coverage_complete"),
                        rs.getBoolean("tax_worksheet_ready"),
                        fromJson(rs.getString("maker"), PersonActor.class),
                        fromJson(rs.getString("approver"), PersonActor.class),
                        rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant(),
                        rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        List.of()
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<CarryBatchPlan> findCarryBatchPlan(Connection c, UUID tenantId, UUID bookId, UUID batchId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_carry_batch_plan WHERE tenant_id = ? AND book_id = ? AND id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new CarryBatchPlan(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("tenant_id"),
                        (UUID) rs.getObject("book_id"),
                        (UUID) rs.getObject("source_year_id"),
                        (UUID) rs.getObject("target_year_id"),
                        rs.getString("kind"),
                        rs.getString("status"),
                        rs.getInt("source_close_version"),
                        rs.getLong("source_watermark"),
                        rs.getInt("target_opening_version"),
                        rs.getString("mapping_version"),
                        rs.getLong("content_version"),
                        rs.getString("content_digest"),
                        (UUID) rs.getObject("previous_batch_id"),
                        rs.getString("reason"),
                        fromJson(rs.getString("maker"), PersonActor.class),
                        fromJson(rs.getString("approver"), PersonActor.class),
                        rs.getTimestamp("committed_at") == null ? null : rs.getTimestamp("committed_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        List.of(), List.of(), List.of()
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Integer> getFiscalYear(Connection c, UUID tenantId, UUID bookId, UUID yearId) throws SQLException {
        String sql = "SELECT year FROM plugin_finance_year WHERE tenant_id = ? AND book_id = ? AND id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, bookId);
            ps.setObject(3, yearId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getInt(1));
                }
            }
        }
        return Optional.empty();
    }
}
