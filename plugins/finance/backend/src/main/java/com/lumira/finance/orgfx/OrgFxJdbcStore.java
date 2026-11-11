package com.lumira.finance.orgfx;

import com.lumira.finance.orgfx.OrgFxModels.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;

/**
 * JDBC persistence store for Lane W01 (org-fx).
 * Executes strictly on the target tenant connection with transaction guards and advisory locks.
 */
@Repository
public final class OrgFxJdbcStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public OrgFxJdbcStore(JdbcClient jdbc, PlatformTransactionManager txManager, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(txManager);
        this.mapper = mapper;
    }

    public <T> T inTransaction(UUID tenantId, String lockKey, Function<OrgFxJdbcStore, T> callback) {
        return transactions.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                    .param("orgfx:" + tenantId + ":" + lockKey)
                    .query().listOfRows();
            return callback.apply(this);
        });
    }

    // --- ORG01: Tax Bureaus, Branches & Entity Lifecycle ---

    public List<TaxBureau> listTaxBureaus() {
        return jdbc.sql("""
            SELECT bureau_code, bureau_name, province_district_code, authority_level, is_active
            FROM plugin_finance_org_tax_bureau
            WHERE is_active = true
            ORDER BY bureau_code ASC
        """).query((rs, rowNum) -> new TaxBureau(
                rs.getString("bureau_code"),
                rs.getString("bureau_name"),
                rs.getString("province_district_code"),
                rs.getString("authority_level"),
                rs.getBoolean("is_active")
        )).list();
    }

    public void insertTaxBureau(TaxBureau b) {
        jdbc.sql("""
            INSERT INTO plugin_finance_org_tax_bureau (
                bureau_code, bureau_name, province_district_code, authority_level, is_active
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (bureau_code) DO NOTHING
        """).params(b.bureauCode(), b.bureauName(), b.provinceDistrictCode(), b.authorityLevel(), b.isActive()).update();
    }

    public void insertBranch(Branch b) {
        jdbc.sql("""
            INSERT INTO plugin_finance_org_branch (
                tenant_id, legal_entity_id, id, branch_code, branch_name, branch_type,
                tax_filing_mode, tax_bureau_code, tax_bureau_name, address, status, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(
                b.tenantId(), b.legalEntityId(), b.id(), b.branchCode(), b.branchName(), b.branchType(),
                b.taxFilingMode(), b.taxBureauCode(), b.taxBureauName(), b.address(), b.status(), b.version(),
                b.createdAt(), b.createdAt()
        ).update();
    }

    public List<Branch> listBranches(UUID tenantId, UUID legalEntityId) {
        return jdbc.sql("""
            SELECT tenant_id, legal_entity_id, id, branch_code, branch_name, branch_type,
                   tax_filing_mode, tax_bureau_code, tax_bureau_name, address, status, version, created_at
            FROM plugin_finance_org_branch
            WHERE tenant_id = ? AND legal_entity_id = ?
            ORDER BY branch_code ASC
        """).params(tenantId, legalEntityId).query((rs, rowNum) -> mapBranch(rs)).list();
    }

    private Branch mapBranch(ResultSet rs) throws SQLException {
        return new Branch(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("legal_entity_id")),
                rs.getString("branch_code"),
                rs.getString("branch_name"),
                rs.getString("branch_type"),
                rs.getString("tax_filing_mode"),
                rs.getString("tax_bureau_code"),
                rs.getString("tax_bureau_name"),
                rs.getString("address"),
                rs.getString("status"),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    public void upsertLifecycleRecord(EntityLifecycleRecord r) {
        jdbc.sql("""
            INSERT INTO plugin_finance_org_entity_lifecycle (tenant_id, legal_entity_id, current_status, version, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, legal_entity_id) DO UPDATE SET
                current_status = EXCLUDED.current_status,
                version = plugin_finance_org_entity_lifecycle.version + 1,
                updated_at = EXCLUDED.updated_at
        """).params(r.tenantId(), r.legalEntityId(), r.currentStatus().name(), r.version(), r.updatedAt()).update();
    }

    public Optional<EntityLifecycleRecord> findLifecycleRecord(UUID tenantId, UUID legalEntityId) {
        return jdbc.sql("""
            SELECT tenant_id, legal_entity_id, current_status, version, updated_at
            FROM plugin_finance_org_entity_lifecycle
            WHERE tenant_id = ? AND legal_entity_id = ?
        """).params(tenantId, legalEntityId).query((rs, rowNum) -> new EntityLifecycleRecord(
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("legal_entity_id")),
                EntityStatus.valueOf(rs.getString("current_status")),
                rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class)
        )).optional();
    }

    public void insertLifecycleEvent(EntityLifecycleEvent e) {
        jdbc.sql("""
            INSERT INTO plugin_finance_org_entity_lifecycle_event (
                id, tenant_id, legal_entity_id, from_status, to_status, reason,
                maker_account, maker_person, reviewer_account, reviewer_person, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(
                e.id(), e.tenantId(), e.legalEntityId(), e.fromStatus().name(), e.toStatus().name(), e.reason(),
                e.maker().accountId(), e.maker().personId(), e.reviewer().accountId(), e.reviewer().personId(), e.createdAt()
        ).update();
    }

    // --- ORG02: Standards Eligibility, Policy Versions & Conversion Bridge ---

    public void insertStandardEligibility(StandardEligibilityAssessment a) {
        jdbc.sql("""
            INSERT INTO plugin_finance_org_standard_eligibility (
                id, tenant_id, legal_entity_id, is_publicly_traded, is_financial_institution,
                is_group_subsidiary_of_public, employee_count, annual_revenue, total_assets,
                eligible_for_small, evaluated_standard, assessor_account, assessor_person, evaluation_notes, assessed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(
                a.id(), a.tenantId(), a.legalEntityId(), a.isPubliclyTraded(), a.isFinancialInstitution(),
                a.isGroupSubsidiaryOfPublic(), a.employeeCount(), a.annualRevenue(), a.totalAssets(),
                a.eligibleForSmall(), a.evaluatedStandard().name(), a.assessor().accountId(), a.assessor().personId(),
                a.evaluationNotes(), a.assessedAt()
        ).update();
    }

    public void insertPolicyVersion(PolicyVersion p) {
        jdbc.sql("""
            INSERT INTO plugin_finance_org_policy_version (
                id, tenant_id, book_id, policy_version_no, accounting_standard, depreciation_method,
                inventory_costing_method, impairment_allowed, bad_debt_provision_allowed, effective_from,
                effective_to, status, maker_account, maker_person, reviewer_account, reviewer_person,
                approval_digest, version, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(
                p.id(), p.tenantId(), p.bookId(), p.policyVersionNo(), p.accountingStandard().name(),
                p.depreciationMethod(), p.inventoryCostingMethod(), p.impairmentAllowed(), p.badDebtProvisionAllowed(),
                p.effectiveFrom(), p.effectiveTo(), p.status().name(), p.maker().accountId(), p.maker().personId(),
                p.reviewer() != null ? p.reviewer().accountId() : null,
                p.reviewer() != null ? p.reviewer().personId() : null,
                p.approvalDigest(), p.version(), p.createdAt()
        ).update();
    }

    public void updatePolicyVersionApproval(UUID tenantId, UUID bookId, UUID id, Actor reviewer, String approvalDigest) {
        jdbc.sql("""
            UPDATE plugin_finance_org_policy_version
            SET status = 'APPROVED', reviewer_account = ?, reviewer_person = ?, approval_digest = ?, version = version + 1
            WHERE tenant_id = ? AND book_id = ? AND id = ?
        """).params(reviewer.accountId(), reviewer.personId(), approvalDigest, tenantId, bookId, id).update();
    }

    public Optional<PolicyVersion> findPolicyVersion(UUID tenantId, UUID bookId, UUID id) {
        return jdbc.sql("""
            SELECT id, tenant_id, book_id, policy_version_no, accounting_standard, depreciation_method,
                   inventory_costing_method, impairment_allowed, bad_debt_provision_allowed, effective_from,
                   effective_to, status, maker_account, maker_person, reviewer_account, reviewer_person,
                   approval_digest, version, created_at
            FROM plugin_finance_org_policy_version
            WHERE tenant_id = ? AND book_id = ? AND id = ?
        """).params(tenantId, bookId, id).query((rs, rowNum) -> {
            String revAcc = rs.getString("reviewer_account");
            String revPer = rs.getString("reviewer_person");
            return new PolicyVersion(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("tenant_id")),
                    UUID.fromString(rs.getString("book_id")),
                    rs.getInt("policy_version_no"),
                    AccountingStandard.valueOf(rs.getString("accounting_standard")),
                    rs.getString("depreciation_method"),
                    rs.getString("inventory_costing_method"),
                    rs.getBoolean("impairment_allowed"),
                    rs.getBoolean("bad_debt_provision_allowed"),
                    rs.getDate("effective_from").toLocalDate(),
                    rs.getDate("effective_to") != null ? rs.getDate("effective_to").toLocalDate() : null,
                    PolicyStatus.valueOf(rs.getString("status")),
                    new Actor(UUID.fromString(rs.getString("maker_account")), UUID.fromString(rs.getString("maker_person")), UUID.randomUUID()),
                    (revAcc != null && revPer != null) ? new Actor(UUID.fromString(revAcc), UUID.fromString(revPer), UUID.randomUUID()) : null,
                    rs.getString("approval_digest"),
                    rs.getLong("version"),
                    rs.getObject("created_at", OffsetDateTime.class)
            );
        }).optional();
    }

    public int findLatestPolicyVersionNo(UUID tenantId, UUID bookId) {
        Integer max = jdbc.sql("""
            SELECT COALESCE(MAX(policy_version_no), 0)
            FROM plugin_finance_org_policy_version
            WHERE tenant_id = ? AND book_id = ?
        """).params(tenantId, bookId).query(Integer.class).single();
        return max != null ? max : 0;
    }

    public void insertConversionBridge(ConversionBridge b) {
        jdbc.sql("""
            INSERT INTO plugin_finance_org_conversion_bridge (
                id, tenant_id, book_id, from_standard, to_standard, effective_fiscal_year,
                status, maker_account, maker_person, reviewer_account, reviewer_person,
                total_adjustment_debit, total_adjustment_credit, justification, version, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(
                b.id(), b.tenantId(), b.bookId(), b.fromStandard().name(), b.toStandard().name(),
                b.effectiveFiscalYear(), b.status(), b.maker().accountId(), b.maker().personId(),
                b.reviewer() != null ? b.reviewer().accountId() : null,
                b.reviewer() != null ? b.reviewer().personId() : null,
                b.totalAdjustmentDebit(), b.totalAdjustmentCredit(), b.justification(), b.version(), b.createdAt()
        ).update();

        for (ConversionBridgeItem item : b.items()) {
            jdbc.sql("""
                INSERT INTO plugin_finance_org_conversion_bridge_item (
                    id, tenant_id, book_id, bridge_id, account_code, account_name, original_balance,
                    adjustment_debit, adjustment_credit, adjusted_balance, adjustment_reason, affected_opening_balance_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """).params(
                    item.id(), b.tenantId(), b.bookId(), b.id(), item.accountCode(), item.accountName(),
                    item.originalBalance(), item.adjustmentDebit(), item.adjustmentCredit(), item.adjustedBalance(),
                    item.adjustmentReason(), item.affectedOpeningBalanceType()
            ).update();
        }
    }

    public Optional<ConversionBridge> findConversionBridge(UUID tenantId, UUID bookId, UUID id) {
        Optional<ConversionBridge> base = jdbc.sql("""
            SELECT id, tenant_id, book_id, from_standard, to_standard, effective_fiscal_year,
                   status, maker_account, maker_person, reviewer_account, reviewer_person,
                   total_adjustment_debit, total_adjustment_credit, justification, version, created_at
            FROM plugin_finance_org_conversion_bridge
            WHERE tenant_id = ? AND book_id = ? AND id = ?
        """).params(tenantId, bookId, id).query((rs, rowNum) -> {
            String revAcc = rs.getString("reviewer_account");
            String revPer = rs.getString("reviewer_person");
            return new ConversionBridge(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("tenant_id")),
                    UUID.fromString(rs.getString("book_id")),
                    AccountingStandard.valueOf(rs.getString("from_standard")),
                    AccountingStandard.valueOf(rs.getString("to_standard")),
                    rs.getInt("effective_fiscal_year"),
                    rs.getString("status"),
                    new Actor(UUID.fromString(rs.getString("maker_account")), UUID.fromString(rs.getString("maker_person")), UUID.randomUUID()),
                    (revAcc != null && revPer != null) ? new Actor(UUID.fromString(revAcc), UUID.fromString(revPer), UUID.randomUUID()) : null,
                    rs.getBigDecimal("total_adjustment_debit"),
                    rs.getBigDecimal("total_adjustment_credit"),
                    rs.getString("justification"),
                    List.of(),
                    rs.getLong("version"),
                    rs.getObject("created_at", OffsetDateTime.class)
            );
        }).optional();

        if (base.isEmpty()) return Optional.empty();

        List<ConversionBridgeItem> items = jdbc.sql("""
            SELECT id, bridge_id, account_code, account_name, original_balance,
                   adjustment_debit, adjustment_credit, adjusted_balance, adjustment_reason, affected_opening_balance_type
            FROM plugin_finance_org_conversion_bridge_item
            WHERE tenant_id = ? AND bridge_id = ?
            ORDER BY account_code ASC
        """).params(tenantId, id).query((rs, rowNum) -> new ConversionBridgeItem(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("bridge_id")),
                rs.getString("account_code"),
                rs.getString("account_name"),
                rs.getBigDecimal("original_balance"),
                rs.getBigDecimal("adjustment_debit"),
                rs.getBigDecimal("adjustment_credit"),
                rs.getBigDecimal("adjusted_balance"),
                rs.getString("adjustment_reason"),
                rs.getString("affected_opening_balance_type")
        )).list();

        ConversionBridge b = base.get();
        return Optional.of(new ConversionBridge(
                b.id(), b.tenantId(), b.bookId(), b.fromStandard(), b.toStandard(),
                b.effectiveFiscalYear(), b.status(), b.maker(), b.reviewer(),
                b.totalAdjustmentDebit(), b.totalAdjustmentCredit(), b.justification(),
                items, b.version(), b.createdAt()
        ));
    }

    public List<ConversionBridge> listConversionBridges(UUID tenantId, UUID bookId) {
        return jdbc.sql("""
            SELECT id, tenant_id, book_id, from_standard, to_standard, effective_fiscal_year,
                   status, maker_account, maker_person, reviewer_account, reviewer_person,
                   total_adjustment_debit, total_adjustment_credit, justification, version, created_at
            FROM plugin_finance_org_conversion_bridge
            WHERE tenant_id = ? AND book_id = ?
            ORDER BY created_at DESC
        """).params(tenantId, bookId).query((rs, rowNum) -> {
            String revAcc = rs.getString("reviewer_account");
            String revPer = rs.getString("reviewer_person");
            return new ConversionBridge(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("tenant_id")),
                    UUID.fromString(rs.getString("book_id")),
                    AccountingStandard.valueOf(rs.getString("from_standard")),
                    AccountingStandard.valueOf(rs.getString("to_standard")),
                    rs.getInt("effective_fiscal_year"),
                    rs.getString("status"),
                    new Actor(UUID.fromString(rs.getString("maker_account")), UUID.fromString(rs.getString("maker_person")), UUID.randomUUID()),
                    (revAcc != null && revPer != null) ? new Actor(UUID.fromString(revAcc), UUID.fromString(revPer), UUID.randomUUID()) : null,
                    rs.getBigDecimal("total_adjustment_debit"),
                    rs.getBigDecimal("total_adjustment_credit"),
                    rs.getString("justification"),
                    List.of(),
                    rs.getLong("version"),
                    rs.getObject("created_at", OffsetDateTime.class)
            );
        }).list();
    }

    // --- ORG03: Period Reopen & Audit Trail ---

    public void insertPeriodReopen(PeriodReopenRecord r) {
        try {
            String reqJson = mapper.writeValueAsString(r.requestedBy());
            String appJson = r.approvedBy() != null ? mapper.writeValueAsString(r.approvedBy()) : null;
            jdbc.sql("""
                INSERT INTO plugin_finance_org_period_reopen (
                    id, tenant_id, book_id, year_id, period_id, period_month, reopen_reason,
                    status, requested_by, approved_by, decision_reason, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
            """).params(
                    r.id(), r.tenantId(), r.bookId(), r.yearId(), r.periodId(), r.periodMonth(),
                    r.reopenReason(), r.status().name(), reqJson, appJson, r.decisionReason(),
                    r.version(), r.createdAt(), r.updatedAt()
            ).update();
        } catch (Exception e) {
            throw new RuntimeException("SERIALIZATION_ERROR: " + e.getMessage(), e);
        }
    }

    public void updatePeriodReopenStatus(UUID tenantId, UUID bookId, UUID id, ReopenStatus newStatus, Actor approver, String decisionReason) {
        try {
            String appJson = approver != null ? mapper.writeValueAsString(approver) : null;
            jdbc.sql("""
                UPDATE plugin_finance_org_period_reopen
                SET status = ?, approved_by = ?::jsonb, decision_reason = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND book_id = ? AND id = ?
            """).params(newStatus.name(), appJson, decisionReason, tenantId, bookId, id).update();
        } catch (Exception e) {
            throw new RuntimeException("SERIALIZATION_ERROR: " + e.getMessage(), e);
        }
    }

    public Optional<PeriodReopenRecord> findPendingPeriodReopen(UUID tenantId, UUID bookId, UUID periodId) {
        return jdbc.sql("""
            SELECT id, tenant_id, book_id, year_id, period_id, period_month, reopen_reason,
                   status, requested_by, approved_by, decision_reason, version, created_at, updated_at
            FROM plugin_finance_org_period_reopen
            WHERE tenant_id = ? AND book_id = ? AND period_id = ? AND status = 'REQUESTED'
            ORDER BY created_at DESC LIMIT 1
        """).params(tenantId, bookId, periodId).query((rs, rowNum) -> mapPeriodReopen(rs)).optional();
    }

    public Optional<PeriodReopenRecord> findPeriodReopen(UUID tenantId, UUID bookId, UUID id) {
        return jdbc.sql("""
            SELECT id, tenant_id, book_id, year_id, period_id, period_month, reopen_reason,
                   status, requested_by, approved_by, decision_reason, version, created_at, updated_at
            FROM plugin_finance_org_period_reopen
            WHERE tenant_id = ? AND book_id = ? AND id = ?
        """).params(tenantId, bookId, id).query((rs, rowNum) -> mapPeriodReopen(rs)).optional();
    }

    public Optional<PeriodReopenRecord> findLatestPeriodReopen(UUID tenantId, UUID bookId, UUID periodId) {
        return jdbc.sql("""
            SELECT id, tenant_id, book_id, year_id, period_id, period_month, reopen_reason,
                   status, requested_by, approved_by, decision_reason, version, created_at, updated_at
            FROM plugin_finance_org_period_reopen
            WHERE tenant_id = ? AND book_id = ? AND period_id = ?
            ORDER BY created_at DESC LIMIT 1
        """).params(tenantId, bookId, periodId).query((rs, rowNum) -> mapPeriodReopen(rs)).optional();
    }

    private PeriodReopenRecord mapPeriodReopen(ResultSet rs) throws SQLException {
        try {
            Actor req = mapper.readValue(rs.getString("requested_by"), Actor.class);
            String appStr = rs.getString("approved_by");
            Actor app = appStr != null ? mapper.readValue(appStr, Actor.class) : null;
            return new PeriodReopenRecord(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("tenant_id")),
                    UUID.fromString(rs.getString("book_id")),
                    UUID.fromString(rs.getString("year_id")),
                    UUID.fromString(rs.getString("period_id")),
                    rs.getInt("period_month"),
                    rs.getString("reopen_reason"),
                    ReopenStatus.valueOf(rs.getString("status")),
                    req,
                    app,
                    rs.getString("decision_reason"),
                    rs.getLong("version"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class)
            );
        } catch (Exception e) {
            throw new SQLException("DESERIALIZATION_ERROR: " + e.getMessage(), e);
        }
    }

    public void insertPeriodAuditTrail(PeriodAuditTrailEntry a) {
        jdbc.sql("""
            INSERT INTO plugin_finance_org_period_audit_trail (
                id, tenant_id, book_id, year_id, period_id, action, actor_account, actor_person, details, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        """).params(
                a.id(), a.tenantId(), a.bookId(), a.yearId(), a.periodId(), a.action(),
                a.actor().accountId(), a.actor().personId(), a.detailsJson(), a.recordedAt()
        ).update();
    }

    // --- ORG04: Currencies, FX Snapshot, Revaluation & Tax Worksheet ---

    public void insertFxRateSnapshot(FxRateSnapshot s) {
        jdbc.sql("""
            INSERT INTO plugin_finance_fx_rate_snapshot (
                id, tenant_id, source_currency, target_currency, rate_date, rate_type,
                rate_value, quote_source, maker_person, reviewer_person, is_active, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(
                s.id(), s.tenantId(), s.sourceCurrency(), s.targetCurrency(), s.rateDate(),
                s.rateType().name(), s.rateValue(), s.quoteSource(), s.maker().personId(),
                s.reviewer() != null ? s.reviewer().personId() : null, s.isActive(), s.createdAt()
        ).update();
    }

    public Optional<FxRateSnapshot> findFxRateSnapshot(UUID tenantId, String sourceCurrency, String targetCurrency, LocalDate date, FxRateType type) {
        return jdbc.sql("""
            SELECT id, tenant_id, source_currency, target_currency, rate_date, rate_type,
                   rate_value, quote_source, maker_person, reviewer_person, is_active, created_at
            FROM plugin_finance_fx_rate_snapshot
            WHERE tenant_id = ? AND source_currency = ? AND target_currency = ? AND rate_date = ? AND rate_type = ?
        """).params(tenantId, sourceCurrency, targetCurrency, date, type.name()).query((rs, rowNum) -> new FxRateSnapshot(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("source_currency"),
                rs.getString("target_currency"),
                rs.getDate("rate_date").toLocalDate(),
                FxRateType.valueOf(rs.getString("rate_type")),
                rs.getBigDecimal("rate_value"),
                rs.getString("quote_source"),
                new Actor(UUID.randomUUID(), UUID.fromString(rs.getString("maker_person")), UUID.randomUUID()),
                rs.getString("reviewer_person") != null ? new Actor(UUID.randomUUID(), UUID.fromString(rs.getString("reviewer_person")), UUID.randomUUID()) : null,
                rs.getBoolean("is_active"),
                rs.getObject("created_at", OffsetDateTime.class)
        )).optional();
    }

    public void insertFxRevaluationPlan(FxRevaluationPlan p) {
        jdbc.sql("""
            INSERT INTO plugin_finance_fx_revaluation_plan (
                id, tenant_id, book_id, year_id, period_id, revaluation_date, closing_rate_snapshot_id,
                status, total_gain_loss, maker_account, maker_person, reviewer_account, reviewer_person,
                journal_entry_id, version, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(
                p.id(), p.tenantId(), p.bookId(), p.yearId(), p.periodId(), p.revaluationDate(),
                p.closingRateSnapshotId(), p.status(), p.totalGainLoss(), p.maker().accountId(),
                p.maker().personId(), p.reviewer() != null ? p.reviewer().accountId() : null,
                p.reviewer() != null ? p.reviewer().personId() : null, p.journalEntryId(), p.version(), p.createdAt()
        ).update();

        for (FxRevaluationItem item : p.items()) {
            jdbc.sql("""
                INSERT INTO plugin_finance_fx_revaluation_item (
                    id, tenant_id, book_id, plan_id, account_code, account_name, currency_code,
                    foreign_balance, carrying_cny_balance, closing_rate, restated_cny_balance,
                    gain_loss_amount, exchange_gain_loss_account
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """).params(
                    item.id(), p.tenantId(), p.bookId(), p.id(), item.accountCode(), item.accountName(),
                    item.currencyCode(), item.foreignBalance(), item.carryingCnyBalance(), item.closingRate(),
                    item.restatedCnyBalance(), item.gainLossAmount(), item.exchangeGainLossAccount()
            ).update();
        }
    }

    public void insertTaxWorksheetItem(FxTaxWorksheetItem w) {
        jdbc.sql("""
            INSERT INTO plugin_finance_fx_tax_worksheet (
                id, tenant_id, book_id, period_id, transaction_ref, original_currency,
                original_amount, accounting_fx_rate, accounting_cny_amount, tax_fx_rate_type,
                tax_fx_rate, tax_cny_amount, tax_accounting_diff, explanation, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(
                w.id(), w.tenantId(), w.bookId(), w.periodId(), w.transactionRef(), w.originalCurrency(),
                w.originalAmount(), w.accountingFxRate(), w.accountingCnyAmount(), w.taxFxRateType(),
                w.taxFxRate(), w.taxCnyAmount(), w.taxAccountingDiff(), w.explanation(), w.createdAt()
        ).update();
    }

    public Optional<FxRevaluationPlan> findFxRevaluationPlan(UUID tenantId, UUID bookId, UUID id) {
        Optional<FxRevaluationPlan> base = jdbc.sql("""
            SELECT id, tenant_id, book_id, year_id, period_id, revaluation_date, closing_rate_snapshot_id,
                   status, total_gain_loss, maker_account, maker_person, reviewer_account, reviewer_person,
                   journal_entry_id, version, created_at
            FROM plugin_finance_fx_revaluation_plan
            WHERE tenant_id = ? AND book_id = ? AND id = ?
        """).params(tenantId, bookId, id).query((rs, rowNum) -> {
            String revAcc = rs.getString("reviewer_account");
            String revPer = rs.getString("reviewer_person");
            String je = rs.getString("journal_entry_id");
            return new FxRevaluationPlan(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("tenant_id")),
                    UUID.fromString(rs.getString("book_id")),
                    UUID.fromString(rs.getString("year_id")),
                    UUID.fromString(rs.getString("period_id")),
                    rs.getDate("revaluation_date").toLocalDate(),
                    UUID.fromString(rs.getString("closing_rate_snapshot_id")),
                    rs.getString("status"),
                    rs.getBigDecimal("total_gain_loss"),
                    new Actor(UUID.fromString(rs.getString("maker_account")), UUID.fromString(rs.getString("maker_person")), UUID.randomUUID()),
                    (revAcc != null && revPer != null) ? new Actor(UUID.fromString(revAcc), UUID.fromString(revPer), UUID.randomUUID()) : null,
                    je != null ? UUID.fromString(je) : null,
                    List.of(),
                    rs.getLong("version"),
                    rs.getObject("created_at", OffsetDateTime.class)
            );
        }).optional();

        if (base.isEmpty()) return Optional.empty();

        List<FxRevaluationItem> items = jdbc.sql("""
            SELECT id, plan_id, account_code, account_name, currency_code,
                   foreign_balance, carrying_cny_balance, closing_rate, restated_cny_balance,
                   gain_loss_amount, exchange_gain_loss_account
            FROM plugin_finance_fx_revaluation_item
            WHERE tenant_id = ? AND plan_id = ?
            ORDER BY account_code ASC
        """).params(tenantId, id).query((rs, rowNum) -> new FxRevaluationItem(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("plan_id")),
                rs.getString("account_code"),
                rs.getString("account_name"),
                rs.getString("currency_code"),
                rs.getBigDecimal("foreign_balance"),
                rs.getBigDecimal("carrying_cny_balance"),
                rs.getBigDecimal("closing_rate"),
                rs.getBigDecimal("restated_cny_balance"),
                rs.getBigDecimal("gain_loss_amount"),
                rs.getString("exchange_gain_loss_account")
        )).list();

        FxRevaluationPlan p = base.get();
        return Optional.of(new FxRevaluationPlan(
                p.id(), p.tenantId(), p.bookId(), p.yearId(), p.periodId(), p.revaluationDate(),
                p.closingRateSnapshotId(), p.status(), p.totalGainLoss(), p.maker(), p.reviewer(),
                p.journalEntryId(), items, p.version(), p.createdAt()
        ));
    }

    public List<FxCurrency> listCurrencies() {
        return jdbc.sql("""
            SELECT currency_code, currency_name, symbol, standard_scale, is_base, is_active
            FROM plugin_finance_fx_currency
            WHERE is_active = true
            ORDER BY is_base DESC, currency_code ASC
        """).query((rs, rowNum) -> new FxCurrency(
                rs.getString("currency_code"),
                rs.getString("currency_name"),
                rs.getString("symbol"),
                rs.getInt("standard_scale"),
                rs.getBoolean("is_base"),
                rs.getBoolean("is_active")
        )).list();
    }

    // --- ORG05: Tax Calendar Templates & Catalog Rules ---

    public void insertTaxCalendarTemplate(TaxCalendarTemplate t) {
        jdbc.sql("""
            INSERT INTO plugin_finance_tax_calendar_template (
                id, region_code, tax_year, period_month, tax_category,
                statutory_deadline, extended_deadline, extension_reason, official_announcement_ref, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (region_code, tax_year, period_month, tax_category) DO UPDATE SET
                statutory_deadline = EXCLUDED.statutory_deadline,
                extended_deadline = EXCLUDED.extended_deadline,
                extension_reason = EXCLUDED.extension_reason,
                official_announcement_ref = EXCLUDED.official_announcement_ref
        """).params(
                t.id(), t.regionCode(), t.taxYear(), t.periodMonth(), t.taxCategory(),
                t.statutoryDeadline(), t.extendedDeadline(), t.extensionReason(), t.officialAnnouncementRef(),
                java.time.OffsetDateTime.now()
        ).update();
    }

    public List<TaxCalendarTemplate> listTaxCalendarTemplates(String regionCode, int taxYear) {
        return jdbc.sql("""
            SELECT id, region_code, tax_year, period_month, tax_category,
                   statutory_deadline, extended_deadline, extension_reason, official_announcement_ref
            FROM plugin_finance_tax_calendar_template
            WHERE region_code = ? AND tax_year = ?
            ORDER BY period_month ASC, tax_category ASC
        """).params(regionCode, taxYear).query((rs, rowNum) -> new TaxCalendarTemplate(
                UUID.fromString(rs.getString("id")),
                rs.getString("region_code"),
                rs.getInt("tax_year"),
                rs.getInt("period_month"),
                rs.getString("tax_category"),
                rs.getDate("statutory_deadline").toLocalDate(),
                rs.getDate("extended_deadline").toLocalDate(),
                rs.getString("extension_reason"),
                rs.getString("official_announcement_ref")
        )).list();
    }

    public List<TaxCatalogRule> listTaxCatalogRules() {
        return jdbc.sql("""
            SELECT obligation_code, tax_category, tax_type_name, filing_frequency,
                   default_role, statutory_filing_unit, applicable_taxpayer_type, applicable_cit_mode, is_active
            FROM plugin_finance_tax_catalog_rule
            WHERE is_active = true
            ORDER BY obligation_code ASC
        """).query((rs, rowNum) -> new TaxCatalogRule(
                rs.getString("obligation_code"),
                rs.getString("tax_category"),
                rs.getString("tax_type_name"),
                rs.getString("filing_frequency"),
                rs.getString("default_role"),
                rs.getString("statutory_filing_unit"),
                rs.getString("applicable_taxpayer_type"),
                rs.getString("applicable_cit_mode"),
                rs.getBoolean("is_active")
        )).list();
    }
}
