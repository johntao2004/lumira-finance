package com.lumira.finance.payrolltax;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * JDBC Data Store for PAY03, PAY04, TAX01, and TAX05.
 * Strictly operates under PostgreSQL 18 Row Level Security (RLS).
 * Implements once-per-tax-year-per-person bonus guard, immutability of locked forms,
 * and persistence for labor and tax policy models.
 */
@Repository
public class PayrollTaxExtJdbcStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    @Autowired
    public PayrollTaxExtJdbcStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
        this.objectMapper = new ObjectMapper();
    }

    public PayrollTaxExtJdbcStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record BonusBatchRecord(
            UUID id,
            UUID tenantId,
            UUID withholdingEntityId,
            int taxYear,
            String bonusName,
            LocalDate payDate,
            PayrollTaxExtModel.BatchState state,
            UUID preparerId,
            UUID reviewerId,
            UUID approverId,
            Instant lockedAt,
            long version
    ) {}

    public record BonusItemRecord(
            UUID id,
            UUID tenantId,
            UUID batchId,
            UUID employeeId,
            BigDecimal bonusAmount,
            BonusCalculationEngine.BonusMode calculationMode,
            BigDecimal monthlyQuotient,
            BigDecimal applicableRate,
            BigDecimal quickDeduction,
            BigDecimal withholdingTax,
            BigDecimal netAmount,
            BonusCalculationEngine.BonusMode comparisonRecommendedMode,
            BigDecimal comparisonTaxSavings,
            CalculationExplainTree explainTree
    ) {}

    public record LaborBatchRecord(
            UUID id,
            UUID tenantId,
            UUID withholdingEntityId,
            String period,
            String batchName,
            LocalDate payDate,
            PayrollTaxExtModel.BatchState state,
            UUID preparerId,
            UUID reviewerId,
            UUID approverId,
            Instant lockedAt,
            long version
    ) {}

    public record LaborItemRecord(
            UUID id,
            UUID tenantId,
            UUID batchId,
            UUID naturalPersonId,
            LaborRemunerationEngine.RemunerationType serviceCategory,
            BigDecimal grossPayment,
            BigDecimal deductionAmount,
            BigDecimal taxableIncome,
            BigDecimal iitRate,
            BigDecimal iitQuickDeduction,
            BigDecimal iitWithholding,
            BigDecimal vatSales,
            boolean vatExempt,
            BigDecimal vatRate,
            BigDecimal vatReduction,
            BigDecimal vatWithheld,
            BigDecimal citySurtax,
            BigDecimal educationSurcharge,
            BigDecimal localEducationSurcharge,
            BigDecimal totalSurchargeWithheld,
            BigDecimal netPayable,
            boolean proxyInvoiceCompleted,
            String proxyInvoiceRef,
            CalculationExplainTree explainTree
    ) {}

    public record CalendarPackRecord(
            String id,
            UUID tenantId,
            String region,
            int taxYear,
            String packVersion,
            String digest,
            String approvalStatus,
            Map<String, String> scheduleEntries
    ) {}

    // --- PAY03: Bonus Batch & Items & Election ---

    public void insertBonusBatch(
            UUID tenantId,
            UUID batchId,
            UUID withholdingEntityId,
            int taxYear,
            String bonusName,
            LocalDate payDate,
            PayrollTaxExtModel.BatchState state,
            UUID preparerId
    ) {
        jdbc.sql("""
            INSERT INTO plugin_finance_payroll_bonus_batch (
                id, tenant_id, withholding_entity_id, tax_year, bonus_name, pay_date,
                state, preparer_id, external_tax_channel, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'NOT_VERIFIED', 0)
        """)
        .param(batchId)
        .param(tenantId)
        .param(withholdingEntityId)
        .param(taxYear)
        .param(bonusName)
        .param(Date.valueOf(payDate))
        .param(state.name())
        .param(preparerId)
        .update();
    }

    public void insertBonusItem(
            UUID tenantId,
            UUID itemId,
            UUID batchId,
            UUID employeeId,
            BonusCalculationEngine.BonusCalculationResult calc
    ) {
        String treeJson = serializeJson(calc.explainTree());
        jdbc.sql("""
            INSERT INTO plugin_finance_payroll_bonus_item (
                id, tenant_id, batch_id, employee_id, bonus_amount, calculation_mode,
                monthly_quotient, applicable_rate, quick_deduction, withholding_tax,
                net_amount, comparison_recommended_mode, comparison_tax_savings, explain_tree
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """)
        .param(itemId)
        .param(tenantId)
        .param(batchId)
        .param(employeeId)
        .param(calc.bonusAmount())
        .param(calc.electedMode().name())
        .param(calc.monthlyQuotient())
        .param(calc.applicableRate())
        .param(calc.quickDeduction())
        .param(calc.withholdingTax())
        .param(calc.netAmount())
        .param(calc.recommendedMode().name())
        .param(calc.taxSavings())
        .param(treeJson)
        .update();
    }

    /**
     * Records bonus separate calculation election.
     * Enforces the once-per-tax-year-per-person guard!
     */
    public void recordSeparateBonusElection(
            UUID tenantId,
            UUID withholdingEntityId,
            UUID employeeId,
            int taxYear,
            UUID batchId
    ) {
        // First check if an election already exists for this person in this tax year
        Long count = jdbc.sql("""
            SELECT count(*) FROM plugin_finance_payroll_bonus_election
            WHERE tenant_id = ? AND withholding_entity_id = ? AND employee_id = ? AND tax_year = ?
        """)
        .param(tenantId)
        .param(withholdingEntityId)
        .param(employeeId)
        .param(taxYear)
        .query(Long.class)
        .single();

        if (count != null && count > 0) {
            throw new IllegalStateException("BONUS_SEPARATE_CALCULATION_ALREADY_USED: Employee " + employeeId
                    + " already used separate bonus calculation in tax year " + taxYear);
        }

        jdbc.sql("""
            INSERT INTO plugin_finance_payroll_bonus_election (
                id, tenant_id, withholding_entity_id, employee_id, tax_year, batch_id, election_mode
            ) VALUES (?, ?, ?, ?, ?, ?, 'SEPARATE_MONTHLY_QUOTIENT')
        """)
        .param(UUID.randomUUID())
        .param(tenantId)
        .param(withholdingEntityId)
        .param(employeeId)
        .param(taxYear)
        .param(batchId)
        .update();
    }

    public boolean hasUsedSeparateBonusElection(UUID tenantId, UUID withholdingEntityId, UUID employeeId, int taxYear) {
        Long count = jdbc.sql("""
            SELECT count(*) FROM plugin_finance_payroll_bonus_election
            WHERE tenant_id = ? AND withholding_entity_id = ? AND employee_id = ? AND tax_year = ?
        """)
        .param(tenantId)
        .param(withholdingEntityId)
        .param(employeeId)
        .param(taxYear)
        .query(Long.class)
        .single();
        return count != null && count > 0;
    }

    public void updateBonusBatchState(
            UUID tenantId,
            UUID batchId,
            PayrollTaxExtModel.BatchState newState,
            UUID reviewerId,
            UUID approverId,
            Instant lockedAt,
            long expectedVersion
    ) {
        int rows = jdbc.sql("""
            UPDATE plugin_finance_payroll_bonus_batch
            SET state = ?, reviewer_id = COALESCE(?, reviewer_id),
                approver_id = COALESCE(?, approver_id),
                locked_at = COALESCE(?, locked_at),
                version = version + 1,
                updated_at = clock_timestamp()
            WHERE tenant_id = ? AND id = ? AND version = ?
        """)
        .param(newState.name())
        .param(reviewerId)
        .param(approverId)
        .param(lockedAt == null ? null : Timestamp.from(lockedAt))
        .param(tenantId)
        .param(batchId)
        .param(expectedVersion)
        .update();

        if (rows == 0) {
            throw new IllegalStateException("CONCURRENT_MODIFICATION_OR_NOT_FOUND");
        }
    }

    public Optional<BonusBatchRecord> findBonusBatch(UUID tenantId, UUID batchId) {
        return jdbc.sql("""
            SELECT id, tenant_id, withholding_entity_id, tax_year, bonus_name, pay_date,
                   state, preparer_id, reviewer_id, approver_id, locked_at, version
            FROM plugin_finance_payroll_bonus_batch
            WHERE tenant_id = ? AND id = ?
        """)
        .param(tenantId)
        .param(batchId)
        .query((rs, rowNum) -> {
            Timestamp ts = rs.getTimestamp("locked_at");
            return new BonusBatchRecord(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("tenant_id"),
                    (UUID) rs.getObject("withholding_entity_id"),
                    rs.getInt("tax_year"),
                    rs.getString("bonus_name"),
                    rs.getDate("pay_date").toLocalDate(),
                    PayrollTaxExtModel.BatchState.valueOf(rs.getString("state")),
                    (UUID) rs.getObject("preparer_id"),
                    (UUID) rs.getObject("reviewer_id"),
                    (UUID) rs.getObject("approver_id"),
                    ts == null ? null : ts.toInstant(),
                    rs.getLong("version")
            );
        })
        .optional();
    }

    public List<BonusItemRecord> findBonusItems(UUID tenantId, UUID batchId) {
        return jdbc.sql("""
            SELECT id, tenant_id, batch_id, employee_id, bonus_amount, calculation_mode,
                   monthly_quotient, applicable_rate, quick_deduction, withholding_tax,
                   net_amount, comparison_recommended_mode, comparison_tax_savings, explain_tree::text as tree_text
            FROM plugin_finance_payroll_bonus_item
            WHERE tenant_id = ? AND batch_id = ?
            ORDER BY created_at ASC
        """)
        .param(tenantId)
        .param(batchId)
        .query((rs, rowNum) -> new BonusItemRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("batch_id"),
                (UUID) rs.getObject("employee_id"),
                rs.getBigDecimal("bonus_amount"),
                BonusCalculationEngine.BonusMode.valueOf(rs.getString("calculation_mode")),
                rs.getBigDecimal("monthly_quotient"),
                rs.getBigDecimal("applicable_rate"),
                rs.getBigDecimal("quick_deduction"),
                rs.getBigDecimal("withholding_tax"),
                rs.getBigDecimal("net_amount"),
                BonusCalculationEngine.BonusMode.valueOf(rs.getString("comparison_recommended_mode")),
                rs.getBigDecimal("comparison_tax_savings"),
                deserializeExplainTree(rs.getString("tree_text"))
        ))
        .list();
    }

    // --- PAY04: Labor Batch & Items ---

    public void insertLaborBatch(
            UUID tenantId,
            UUID batchId,
            UUID withholdingEntityId,
            String period,
            String batchName,
            LocalDate payDate,
            PayrollTaxExtModel.BatchState state,
            UUID preparerId
    ) {
        jdbc.sql("""
            INSERT INTO plugin_finance_payroll_labor_batch (
                id, tenant_id, withholding_entity_id, period, batch_name, pay_date,
                state, preparer_id, external_tax_channel, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'NOT_VERIFIED', 0)
        """)
        .param(batchId)
        .param(tenantId)
        .param(withholdingEntityId)
        .param(period)
        .param(batchName)
        .param(Date.valueOf(payDate))
        .param(state.name())
        .param(preparerId)
        .update();
    }

    public void insertLaborItem(
            UUID tenantId,
            UUID itemId,
            UUID batchId,
            UUID naturalPersonId,
            LaborRemunerationEngine.LaborResult calc
    ) {
        String treeJson = serializeJson(calc.explainTree());
        jdbc.sql("""
            INSERT INTO plugin_finance_payroll_labor_item (
                id, tenant_id, batch_id, natural_person_id, service_category, gross_payment,
                deduction_amount, taxable_income, iit_rate, iit_quick_deduction, iit_withholding,
                vat_sales, vat_exempt, vat_rate, vat_reduction, vat_withheld,
                city_surtax, education_surcharge, local_education_surcharge,
                total_surcharge_withheld, net_payable, proxy_invoice_completed, proxy_invoice_ref, explain_tree
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """)
        .param(itemId)
        .param(tenantId)
        .param(batchId)
        .param(naturalPersonId)
        .param(calc.type().name())
        .param(calc.grossPayment())
        .param(calc.deductionAmount())
        .param(calc.taxableIncome())
        .param(calc.iitRate())
        .param(calc.iitQuickDeduction())
        .param(calc.iitWithholding())
        .param(calc.vatSales())
        .param(calc.vatExempt())
        .param(calc.vatRate())
        .param(calc.vatReduction())
        .param(calc.vatWithheld())
        .param(calc.citySurtax())
        .param(calc.educationSurcharge())
        .param(calc.localEducationSurcharge())
        .param(calc.totalSurchargeWithheld())
        .param(calc.netPayable())
        .param(calc.proxyInvoiceCompleted())
        .param(calc.proxyInvoiceRef())
        .param(treeJson)
        .update();
    }

    public void updateLaborBatchState(
            UUID tenantId,
            UUID batchId,
            PayrollTaxExtModel.BatchState newState,
            UUID reviewerId,
            UUID approverId,
            Instant lockedAt,
            long expectedVersion
    ) {
        int rows = jdbc.sql("""
            UPDATE plugin_finance_payroll_labor_batch
            SET state = ?, reviewer_id = COALESCE(?, reviewer_id),
                approver_id = COALESCE(?, approver_id),
                locked_at = COALESCE(?, locked_at),
                version = version + 1,
                updated_at = clock_timestamp()
            WHERE tenant_id = ? AND id = ? AND version = ?
        """)
        .param(newState.name())
        .param(reviewerId)
        .param(approverId)
        .param(lockedAt == null ? null : Timestamp.from(lockedAt))
        .param(tenantId)
        .param(batchId)
        .param(expectedVersion)
        .update();

        if (rows == 0) {
            throw new IllegalStateException("CONCURRENT_MODIFICATION_OR_NOT_FOUND");
        }
    }

    public Optional<LaborBatchRecord> findLaborBatch(UUID tenantId, UUID batchId) {
        return jdbc.sql("""
            SELECT id, tenant_id, withholding_entity_id, period, batch_name, pay_date,
                   state, preparer_id, reviewer_id, approver_id, locked_at, version
            FROM plugin_finance_payroll_labor_batch
            WHERE tenant_id = ? AND id = ?
        """)
        .param(tenantId)
        .param(batchId)
        .query((rs, rowNum) -> {
            Timestamp ts = rs.getTimestamp("locked_at");
            return new LaborBatchRecord(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("tenant_id"),
                    (UUID) rs.getObject("withholding_entity_id"),
                    rs.getString("period"),
                    rs.getString("batch_name"),
                    rs.getDate("pay_date").toLocalDate(),
                    PayrollTaxExtModel.BatchState.valueOf(rs.getString("state")),
                    (UUID) rs.getObject("preparer_id"),
                    (UUID) rs.getObject("reviewer_id"),
                    (UUID) rs.getObject("approver_id"),
                    ts == null ? null : ts.toInstant(),
                    rs.getLong("version")
            );
        })
        .optional();
    }

    public List<LaborItemRecord> findLaborItems(UUID tenantId, UUID batchId) {
        return jdbc.sql("""
            SELECT id, tenant_id, batch_id, natural_person_id, service_category, gross_payment,
                   deduction_amount, taxable_income, iit_rate, iit_quick_deduction, iit_withholding,
                   vat_sales, vat_exempt, vat_rate, vat_reduction, vat_withheld,
                   city_surtax, education_surcharge, local_education_surcharge,
                   total_surcharge_withheld, net_payable, proxy_invoice_completed, proxy_invoice_ref, explain_tree::text as tree_text
            FROM plugin_finance_payroll_labor_item
            WHERE tenant_id = ? AND batch_id = ?
            ORDER BY created_at ASC
        """)
        .param(tenantId)
        .param(batchId)
        .query((rs, rowNum) -> new LaborItemRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("batch_id"),
                (UUID) rs.getObject("natural_person_id"),
                LaborRemunerationEngine.RemunerationType.valueOf(rs.getString("service_category")),
                rs.getBigDecimal("gross_payment"),
                rs.getBigDecimal("deduction_amount"),
                rs.getBigDecimal("taxable_income"),
                rs.getBigDecimal("iit_rate"),
                rs.getBigDecimal("iit_quick_deduction"),
                rs.getBigDecimal("iit_withholding"),
                rs.getBigDecimal("vat_sales"),
                rs.getBoolean("vat_exempt"),
                rs.getBigDecimal("vat_rate"),
                rs.getBigDecimal("vat_reduction"),
                rs.getBigDecimal("vat_withheld"),
                rs.getBigDecimal("city_surtax"),
                rs.getBigDecimal("education_surcharge"),
                rs.getBigDecimal("local_education_surcharge"),
                rs.getBigDecimal("total_surcharge_withheld"),
                rs.getBigDecimal("net_payable"),
                rs.getBoolean("proxy_invoice_completed"),
                rs.getString("proxy_invoice_ref"),
                deserializeExplainTree(rs.getString("tree_text"))
        ))
        .list();
    }

    // --- TAX01: Policy Pack & Qualification Attestation ---

    public void insertPolicyPack(UUID tenantId, TaxPolicyRuleEngine.PolicyPack pack) {
        String rulesJson = serializeJson(pack.rules());
        jdbc.sql("""
            INSERT INTO plugin_finance_tax_policy_pack (
                id, tenant_id, tax_type, version, effective_from, effective_to,
                digest, approval_status, attestation_required, rules
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (id) DO UPDATE SET
                tenant_id = EXCLUDED.tenant_id,
                tax_type = EXCLUDED.tax_type,
                version = EXCLUDED.version,
                effective_from = EXCLUDED.effective_from,
                effective_to = EXCLUDED.effective_to,
                digest = EXCLUDED.digest,
                approval_status = EXCLUDED.approval_status,
                attestation_required = EXCLUDED.attestation_required,
                rules = EXCLUDED.rules
        """)
        .param(pack.id())
        .param(tenantId)
        .param(pack.taxType())
        .param(pack.version())
        .param(Date.valueOf(pack.effectiveFrom()))
        .param(Date.valueOf(pack.effectiveTo()))
        .param(pack.digest())
        .param(pack.approvalStatus())
        .param(pack.attestationRequired())
        .param(rulesJson)
        .update();
    }

    public Optional<TaxPolicyRuleEngine.PolicyPack> findPolicyPack(UUID tenantId, String id) {
        return jdbc.sql("""
            SELECT id, tax_type, version, effective_from, effective_to, digest, approval_status,
                   attestation_required, rules::text as rules_text
            FROM plugin_finance_tax_policy_pack
            WHERE tenant_id = ? AND id = ?
        """)
        .param(tenantId)
        .param(id)
        .query((rs, rowNum) -> {
            Map<String, Object> rules = deserializeMap(rs.getString("rules_text"));
            return new TaxPolicyRuleEngine.PolicyPack(
                    rs.getString("id"),
                    rs.getString("tax_type"),
                    rs.getString("version"),
                    rs.getDate("effective_from").toLocalDate(),
                    rs.getDate("effective_to").toLocalDate(),
                    rs.getString("digest"),
                    rs.getString("approval_status"),
                    rs.getBoolean("attestation_required"),
                    rules
            );
        })
        .optional();
    }

    public void insertAttestation(UUID tenantId, TaxPolicyRuleEngine.Attestation attestation) {
        String factsJson = serializeJson(attestation.qualificationFacts());
        jdbc.sql("""
            INSERT INTO plugin_finance_tax_qualification_attestation (
                id, tenant_id, policy_pack_id, tax_entity_id, qualification_category,
                valid_from, valid_through, attested_by_natural_person, attestation_digest, qualification_facts
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """)
        .param(attestation.id())
        .param(tenantId)
        .param(attestation.policyPackId())
        .param(attestation.taxEntityId())
        .param(attestation.qualificationCategory())
        .param(Date.valueOf(attestation.validFrom()))
        .param(Date.valueOf(attestation.validThrough()))
        .param(attestation.attestedBy())
        .param(attestation.attestationDigest())
        .param(factsJson)
        .update();
    }

    public Optional<TaxPolicyRuleEngine.Attestation> findAttestation(UUID tenantId, UUID id) {
        return jdbc.sql("""
            SELECT id, policy_pack_id, tax_entity_id, qualification_category,
                   valid_from, valid_through, attested_by_natural_person, attestation_digest,
                   qualification_facts::text as facts_text
            FROM plugin_finance_tax_qualification_attestation
            WHERE tenant_id = ? AND id = ?
        """)
        .param(tenantId)
        .param(id)
        .query((rs, rowNum) -> {
            Map<String, Object> facts = deserializeMap(rs.getString("facts_text"));
            return new TaxPolicyRuleEngine.Attestation(
                    (UUID) rs.getObject("id"),
                    rs.getString("policy_pack_id"),
                    (UUID) rs.getObject("tax_entity_id"),
                    rs.getString("qualification_category"),
                    rs.getDate("valid_from").toLocalDate(),
                    rs.getDate("valid_through").toLocalDate(),
                    (UUID) rs.getObject("attested_by_natural_person"),
                    rs.getString("attestation_digest"),
                    facts
            );
        })
        .optional();
    }

    // --- TAX05: Form Version Cell Lock ---

    public void insertFormLock(
            UUID tenantId,
            UUID id,
            UUID taxObligationId,
            String formCode,
            int versionNumber,
            String contentDigest,
            Map<String, String> cellValues,
            Set<String> mandatoryAttachments
    ) {
        String cellsJson = serializeJson(cellValues);
        String attachJson = serializeJson(mandatoryAttachments);
        jdbc.sql("""
            INSERT INTO plugin_finance_tax_form_lock (
                id, tenant_id, tax_obligation_id, form_code, version_number,
                is_locked, content_digest, cell_values, mandatory_attachments
            ) VALUES (?, ?, ?, ?, ?, false, ?, ?::jsonb, ?::jsonb)
        """)
        .param(id)
        .param(tenantId)
        .param(taxObligationId)
        .param(formCode)
        .param(versionNumber)
        .param(contentDigest)
        .param(cellsJson)
        .param(attachJson)
        .update();
    }

    public void lockForm(
            UUID tenantId,
            UUID formId,
            UUID lockingPersonId,
            String newDigest
    ) {
        Boolean locked = jdbc.sql("""
            SELECT is_locked FROM plugin_finance_tax_form_lock
            WHERE tenant_id = ? AND id = ?
        """)
        .param(tenantId)
        .param(formId)
        .query(Boolean.class)
        .single();

        if (locked != null && locked) {
            throw new IllegalStateException("FORM_VERSION_LOCKED: Form version is already locked");
        }

        jdbc.sql("""
            UPDATE plugin_finance_tax_form_lock
            SET is_locked = true, locked_at = clock_timestamp(),
                locked_by_natural_person = ?, content_digest = ?
            WHERE tenant_id = ? AND id = ?
        """)
        .param(lockingPersonId)
        .param(newDigest)
        .param(tenantId)
        .param(formId)
        .update();
    }

    public Optional<FormVersionCellLock.FormLockState> findFormLock(UUID tenantId, UUID formId) {
        return jdbc.sql("""
            SELECT id, tax_obligation_id, form_code, version_number, is_locked,
                   locked_at, locked_by_natural_person, content_digest, cell_values::text as cells_text
            FROM plugin_finance_tax_form_lock
            WHERE tenant_id = ? AND id = ?
        """)
        .param(tenantId)
        .param(formId)
        .query((rs, rowNum) -> {
            Map<String, String> cells = deserializeStringMap(rs.getString("cells_text"));
            Timestamp ts = rs.getTimestamp("locked_at");
            return new FormVersionCellLock.FormLockState(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("tax_obligation_id"),
                    rs.getString("form_code"),
                    rs.getInt("version_number"),
                    rs.getBoolean("is_locked"),
                    ts == null ? null : ts.toInstant(),
                    (UUID) rs.getObject("locked_by_natural_person"),
                    rs.getString("content_digest"),
                    cells
            );
        })
        .optional();
    }

    // --- TAX05: Calendar Pack Persistence ---

    public void insertCalendarPack(
            UUID tenantId,
            String id,
            String region,
            int taxYear,
            String packVersion,
            String digest,
            String approvalStatus,
            Map<String, String> scheduleEntries
    ) {
        String entriesJson = serializeJson(scheduleEntries);
        jdbc.sql("""
            INSERT INTO plugin_finance_tax_calendar_pack (
                id, tenant_id, region, tax_year, pack_version, digest, approval_status, schedule_entries
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """)
        .param(id)
        .param(tenantId)
        .param(region)
        .param(taxYear)
        .param(packVersion)
        .param(digest)
        .param(approvalStatus)
        .param(entriesJson)
        .update();
    }

    public Optional<CalendarPackRecord> findCalendarPack(UUID tenantId, String packVersion) {
        return jdbc.sql("""
            SELECT id, tenant_id, region, tax_year, pack_version, digest, approval_status,
                   schedule_entries::text as entries_text
            FROM plugin_finance_tax_calendar_pack
            WHERE tenant_id = ? AND pack_version = ?
        """)
        .param(tenantId)
        .param(packVersion)
        .query((rs, rowNum) -> new CalendarPackRecord(
                rs.getString("id"),
                (UUID) rs.getObject("tenant_id"),
                rs.getString("region"),
                rs.getInt("tax_year"),
                rs.getString("pack_version"),
                rs.getString("digest"),
                rs.getString("approval_status"),
                deserializeStringMap(rs.getString("entries_text"))
        ))
        .optional();
    }

    // --- Serialization Helpers ---

    private String serializeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("JSON_SERIALIZATION_FAILED", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> deserializeStringMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private CalculationExplainTree deserializeExplainTree(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, CalculationExplainTree.class);
        } catch (Exception e) {
            return null;
        }
    }
}
