package com.lumira.finance.statutory;

import static com.lumira.finance.statutory.StatutoryModels.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class StatutoryService {
    private final StatutoryStore store;

    public StatutoryService(StatutoryStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public List<TeachingGuide> getTeachingGuides(Standard standard) {
        return TeachingGuideEngine.getAllTeachingGuides(standard);
    }

    public List<CanonicalVoucher> getCanonicalVouchers(Standard standard) {
        return TeachingGuideEngine.getCanonicalVouchers(standard);
    }

    @Transactional
    public ReportPackage createAndAssemblePackage(
            UUID packageId,
            Scope scope,
            PackageType packageType,
            FilingTarget filingTarget,
            List<AccountBalance> balances,
            List<ContraBalance> contras,
            List<MovementRecord> movements,
            BigDecimal openingCash,
            BigDecimal closingCash,
            Map<String, BigDecimal> cashflowItems,
            BigDecimal depreciationNonCash,
            BigDecimal amortizationNonCash,
            BigDecimal workingCapitalChangeReceivables,
            BigDecimal workingCapitalChangePayables,
            BigDecimal workingCapitalChangeInventory,
            Map<String, String> notesSections,
            Actor preparer) {

        List<ReportTrace> traces = new ArrayList<>();

        // 1. Balance sheet
        StatementSheet bs = StatutoryReportEngine.buildBalanceSheet(scope, balances, contras, Map.of(), traces);

        // 2. Income statement
        StatementSheet pl = StatutoryReportEngine.buildIncomeStatement(scope, movements, Map.of(), traces);
        BigDecimal netProfit = BigDecimal.ZERO;
        String profitRowId = scope.standard() == Standard.CAS_2019 ? "P20" : "32";
        if (pl.cells().containsKey(profitRowId)) {
            netProfit = new BigDecimal(pl.cells().get(profitRowId).rawValue());
        }

        // 3. Cash flow statement
        StatementSheet cf = StatutoryReportEngine.buildCashFlowStatement(
                scope, openingCash, closingCash, cashflowItems,
                netProfit, depreciationNonCash, amortizationNonCash,
                workingCapitalChangeReceivables, workingCapitalChangePayables, workingCapitalChangeInventory,
                traces
        );

        // 4. Statement of Changes in Equity (for CAS Full Package)
        StatementSheet sce = null;
        if (packageType == PackageType.FULL_STATUTORY_REPORT_PACKAGE && scope.standard() == Standard.CAS_2019) {
            BigDecimal capital = BigDecimal.ZERO;
            if (bs.cells().containsKey("E01")) capital = new BigDecimal(bs.cells().get("E01").rawValue());
            BigDecimal reserve = BigDecimal.ZERO;
            if (bs.cells().containsKey("E03")) reserve = new BigDecimal(bs.cells().get("E03").rawValue());
            BigDecimal surplus = BigDecimal.ZERO;
            if (bs.cells().containsKey("E07")) surplus = new BigDecimal(bs.cells().get("E07").rawValue());
            BigDecimal retained = BigDecimal.ZERO;
            if (bs.cells().containsKey("E08")) retained = new BigDecimal(bs.cells().get("E08").rawValue());

            sce = StatutoryReportEngine.buildStatementOfChangesInEquity(
                    scope, capital, reserve, surplus, retained, netProfit, BigDecimal.ZERO, BigDecimal.ZERO, traces
            );
        }

        // 5. Notes
        StructuredNotes notes = StatutoryReportEngine.evaluateNotesCompleteness(notesSections);

        // 6. Assemble
        ReportPackage pkg = StatutoryReportEngine.assembleAndEvaluatePackage(
                packageId, scope, packageType, filingTarget, bs, pl, cf, sce, notes, null, preparer, null, null
        );

        store.savePackage(pkg);
        return pkg;
    }

    public Optional<ReportPackage> getPackage(UUID tenantId, UUID packageId) {
        return store.findPackageById(tenantId, packageId);
    }

    @Transactional
    public ReportPackage approvePackage(UUID tenantId, UUID packageId, Actor approver) {
        ReportPackage existing = store.findPackageById(tenantId, packageId)
                .orElseThrow(() -> new IllegalArgumentException("Package not found: " + packageId));

        if (approver.personId().equals(existing.preparer().personId())) {
            throw new IllegalArgumentException("Segregation of duties violation: approver cannot be the preparer");
        }
        if (existing.status() == PackageStatus.BLOCKED) {
            throw new IllegalStateException("Cannot approve a BLOCKED package. Gaps must be resolved first: " + existing.publicationGaps());
        }

        ReportPackage approved = new ReportPackage(
                existing.id(),
                existing.scope(),
                existing.packageType(),
                existing.filingTarget(),
                PackageStatus.APPROVED,
                existing.sheets(),
                existing.worksheet(),
                existing.notes(),
                existing.publicationGaps(),
                existing.contentDigest(),
                existing.preparer(),
                existing.reviewer(),
                approver,
                existing.createdAt(),
                java.time.Instant.now()
        );

        store.savePackage(approved);
        return approved;
    }

    @Transactional
    public ReportPackage publishPackage(UUID tenantId, UUID packageId, Actor publisher) {
        ReportPackage existing = store.findPackageById(tenantId, packageId)
                .orElseThrow(() -> new IllegalArgumentException("Package not found: " + packageId));

        if (existing.status() == PackageStatus.BLOCKED) {
            throw new IllegalStateException("Cannot publish a BLOCKED package. Publication gaps: " + existing.publicationGaps());
        }
        if (!existing.publicationGaps().isEmpty()) {
            throw new IllegalStateException("Publication blocked due to gaps: " + existing.publicationGaps());
        }
        if (existing.packageType() == PackageType.FULL_STATUTORY_REPORT_PACKAGE) {
            if (existing.scope().standard() == Standard.CAS_2019) {
                boolean hasSce = existing.sheets().stream().anyMatch(s -> s.kind() == StatementKind.SCE);
                if (!hasSce) {
                    throw new IllegalStateException("Cannot publish FULL_STATUTORY_REPORT_PACKAGE under CAS without Statement of Changes in Equity");
                }
            }
            if (existing.notes() == null || !existing.notes().mandatoryComplete()) {
                throw new IllegalStateException("Cannot publish FULL_STATUTORY_REPORT_PACKAGE without complete mandatory notes");
            }
        }

        Actor finalApprover = existing.approver();
        if (finalApprover == null) {
            if (publisher.personId().equals(existing.preparer().personId())) {
                throw new IllegalArgumentException("Segregation of duties violation: publisher cannot be the preparer");
            }
            finalApprover = publisher;
        }

        ReportPackage published = new ReportPackage(
                existing.id(),
                existing.scope(),
                existing.packageType(),
                existing.filingTarget(),
                PackageStatus.PUBLISHED,
                existing.sheets(),
                existing.worksheet(),
                existing.notes(),
                existing.publicationGaps(),
                existing.contentDigest(),
                existing.preparer(),
                existing.reviewer(),
                finalApprover,
                existing.createdAt(),
                java.time.Instant.now()
        );

        store.savePackage(published);
        return published;
    }

    @Transactional
    public Worksheet createWorksheet(
            UUID tenantId, UUID packageId, String name, String reason,
            List<AdjustmentEntry> adjustments, Actor preparer) {
        ReportPackage existing = store.findPackageById(tenantId, packageId)
                .orElseThrow(() -> new IllegalArgumentException("Package not found: " + packageId));

        String beforeDigest = existing.contentDigest();
        String afterDigest = StatutoryReportEngine.sha256Hex(beforeDigest + ":" + name + ":" + (adjustments == null ? 0 : adjustments.size()));
        UUID wsId = UUID.randomUUID();

        Worksheet ws = new Worksheet(
                wsId, packageId, name, adjustments == null ? List.of() : adjustments,
                beforeDigest, afterDigest, "PENDING", preparer, null
        );

        store.saveWorksheet(tenantId, ws);
        return ws;
    }

    @Transactional
    public Worksheet approveWorksheet(UUID tenantId, UUID packageId, UUID worksheetId, Actor approver) {
        Worksheet ws = store.findWorksheetById(tenantId, packageId, worksheetId)
                .orElseThrow(() -> new IllegalArgumentException("Worksheet not found: " + worksheetId));

        if (approver.personId().equals(ws.preparer().personId())) {
            throw new IllegalArgumentException("Segregation of duties violation: worksheet approver cannot be the preparer");
        }

        Worksheet approved = new Worksheet(
                ws.id(), ws.packageId(), ws.name(), ws.adjustments(),
                ws.beforeDigest(), ws.afterDigest(), "APPROVED", ws.preparer(), approver
        );

        store.saveWorksheet(tenantId, approved);
        return approved;
    }

    @Transactional
    public KpiMetrics evaluateKpis(
            UUID tenantId, UUID entityId, UUID bookId, UUID yearId, UUID kpiId,
            LocalDate start, LocalDate end,
            BigDecimal availableCash, BigDecimal opOutflow, BigDecimal opInflow, BigDecimal capexOutflow,
            int periodMonths, BigDecimal revenue, BigDecimal cogs, BigDecimal rdExpense,
            BigDecimal avgReceivables, BigDecimal avgPayables,
            Actor preparer, Actor approver) {

        if (approver != null && approver.personId().equals(preparer.personId())) {
            throw new IllegalArgumentException("Segregation of duties violation: approver cannot be the preparer");
        }

        KpiMetrics metrics = KpiAndCalendarEngine.computeKpis(
                availableCash, opOutflow, opInflow, capexOutflow, periodMonths,
                revenue, cogs, rdExpense, avgReceivables, avgPayables,
                preparer, approver
        );

        store.saveKpiSnapshot(tenantId, entityId, bookId, yearId, kpiId, start, end, metrics);
        return metrics;
    }

    @Transactional
    public CashCalendar generateCashCalendar(
            UUID calendarId, Scope scope, LocalDate projectionDate, int horizonDays,
            BigDecimal openingCash, BigDecimal safeBuffer,
            Map<LocalDate, BigDecimal> inflows, Map<LocalDate, BigDecimal> outflows,
            Actor publisher) {

        CashCalendar calendar = KpiAndCalendarEngine.generateCashCalendar(
                calendarId, scope, projectionDate, horizonDays,
                openingCash, safeBuffer, inflows, outflows, publisher
        );

        store.saveCashCalendar(calendar);
        return calendar;
    }

    @Transactional
    public ConsolidationScope saveConsolidationScope(UUID tenantId, ConsolidationScope scope, Actor creator) {
        store.ensureConsolidationScope(tenantId, scope, creator);
        return scope;
    }

    @Transactional
    public ConsolidationWorksheet generateConsolidationWorksheet(
            UUID tenantId, UUID worksheetId, ConsolidationScope scope,
            LocalDate start, LocalDate end, boolean isManagementRollup,
            StatementSheet parentBs, StatementSheet parentPl, StatementSheet parentCf,
            Map<UUID, StatementSheet> subBs, Map<UUID, StatementSheet> subPl, Map<UUID, StatementSheet> subCf,
            BigDecimal intercompanyReceivables, BigDecimal intercompanyBadDebt,
            BigDecimal intercompanySalesRevenue, BigDecimal intercompanySalesCost,
            BigDecimal unrealizedInventoryProfit,
            Actor preparer, Actor approver) {

        if (approver != null && approver.personId().equals(preparer.personId())) {
            throw new IllegalArgumentException("Segregation of duties violation: approver cannot be the preparer");
        }

        ConsolidationWorksheet ws = ConsolidationEngine.generateConsolidationWorksheet(
                worksheetId, scope, start, end, isManagementRollup,
                parentBs, parentPl, parentCf, subBs, subPl, subCf,
                intercompanyReceivables, intercompanyBadDebt,
                intercompanySalesRevenue, intercompanySalesCost, unrealizedInventoryProfit,
                preparer, approver
        );

        Actor creator = preparer != null ? preparer : new Actor(UUID.randomUUID(), "system", "SYSTEM");
        store.ensureConsolidationScope(tenantId, scope, creator);
        store.saveConsolidationWorksheet(tenantId, ws);
        return ws;
    }
}
