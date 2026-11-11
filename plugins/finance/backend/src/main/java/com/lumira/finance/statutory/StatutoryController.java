package com.lumira.finance.statutory;

import static com.lumira.finance.statutory.StatutoryModels.*;

import com.lumira.finance.FinanceGlobalExceptionHandler;
import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/finance/v1/statutory")
@ModuleEndpoint("finance")
public class StatutoryController {
    private final StatutoryService service;

    public StatutoryController(StatutoryService service) {
        this.service = Objects.requireNonNull(service);
    }

    public record TeachingGuideRequest(String standard) {}
    public record CreatePackageRequest(
            UUID tenantId,
            UUID entityId,
            UUID bookId,
            UUID yearId,
            String standard,
            LocalDate from,
            LocalDate through,
            String packageType,
            String filingTarget,
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
            Actor preparer) {}

    public record ApprovePackageRequest(UUID tenantId, Actor approver) {}

    public record EvaluateKpiRequest(
            UUID tenantId,
            UUID entityId,
            UUID bookId,
            UUID yearId,
            LocalDate periodStart,
            LocalDate periodEnd,
            BigDecimal availableCash,
            BigDecimal opOutflow,
            BigDecimal opInflow,
            BigDecimal capexOutflow,
            int periodMonths,
            BigDecimal revenue,
            BigDecimal cogs,
            BigDecimal rdExpense,
            BigDecimal avgReceivables,
            BigDecimal avgPayables,
            Actor preparer,
            Actor approver) {}

    public record GenerateCalendarRequest(
            UUID tenantId,
            UUID entityId,
            UUID bookId,
            UUID yearId,
            String standard,
            LocalDate projectionDate,
            int horizonDays,
            BigDecimal openingCash,
            BigDecimal safeBufferAmount,
            Map<LocalDate, BigDecimal> inflows,
            Map<LocalDate, BigDecimal> outflows,
            Actor publisher) {}

    private static Standard parseStandard(String s) {
        if (s == null || s.isBlank()) return Standard.CAS_2019;
        String trimmed = s.trim();
        if ("CAS".equalsIgnoreCase(trimmed) || "CAS_2019".equalsIgnoreCase(trimmed)) {
            return Standard.CAS_2019;
        }
        if ("SMALL".equalsIgnoreCase(trimmed) || "SBE_2011".equalsIgnoreCase(trimmed)) {
            return Standard.SBE_2011;
        }
        return Standard.valueOf(trimmed);
    }

    @PostMapping("/teaching-guide/query")
    public ResponseEntity<?> queryTeachingGuide(@RequestBody TeachingGuideRequest req) {
        try {
            Standard std = parseStandard(req.standard());
            List<TeachingGuide> guides = service.getTeachingGuides(std);
            List<CanonicalVoucher> vouchers = service.getCanonicalVouchers(std);
            return ResponseEntity.ok(Map.of(
                    "standard", std.name(),
                    "guides", guides,
                    "canonicalVouchers", vouchers
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/packages")
    public ResponseEntity<?> createPackage(@RequestBody CreatePackageRequest req) {
        try {
            Standard std = parseStandard(req.standard());
            Scope scope = new Scope(req.tenantId(), req.entityId(), req.bookId(), req.yearId(), std, req.from(), req.through(), "CNY");
            PackageType pkgType = PackageType.valueOf(req.packageType());
            FilingTarget target = FilingTarget.valueOf(req.filingTarget());

            ReportPackage pkg = service.createAndAssemblePackage(
                    UUID.randomUUID(), scope, pkgType, target,
                    req.balances() == null ? List.of() : req.balances(),
                    req.contras() == null ? List.of() : req.contras(),
                    req.movements() == null ? List.of() : req.movements(),
                    req.openingCash() == null ? BigDecimal.ZERO : req.openingCash(),
                    req.closingCash() == null ? BigDecimal.ZERO : req.closingCash(),
                    req.cashflowItems() == null ? Map.of() : req.cashflowItems(),
                    req.depreciationNonCash(), req.amortizationNonCash(),
                    req.workingCapitalChangeReceivables(), req.workingCapitalChangePayables(), req.workingCapitalChangeInventory(),
                    req.notesSections(), req.preparer()
            );
            return ResponseEntity.ok(pkg);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/packages/{packageId}/approve")
    public ResponseEntity<?> approvePackage(
            @PathVariable UUID packageId,
            @RequestBody ApprovePackageRequest req) {
        try {
            ReportPackage approved = service.approvePackage(req.tenantId(), packageId, req.approver());
            return ResponseEntity.ok(approved);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    public record PublishPackageRequest(UUID tenantId, Actor publisher) {}

    public record CreateWorksheetRequest(
            UUID tenantId,
            String name,
            String reason,
            List<AdjustmentEntry> adjustments,
            Actor preparer) {}

    public record ApproveWorksheetRequest(UUID tenantId, Actor approver) {}

    public record CreateConsolidationScopeRequest(
            UUID tenantId,
            UUID scopeId,
            String scopeCode,
            UUID parentEntityId,
            String scopeName,
            List<ConsolidationMember> members,
            String status,
            Actor createdBy) {}

    public record ConsolidationWorksheetRequest(
            UUID tenantId,
            UUID scopeId,
            String scopeCode,
            UUID parentEntityId,
            String scopeName,
            List<ConsolidationMember> members,
            LocalDate periodStart,
            LocalDate periodEnd,
            boolean isManagementRollup,
            StatementSheet parentBs,
            StatementSheet parentPl,
            StatementSheet parentCf,
            Map<UUID, StatementSheet> subsidiaryBsMap,
            Map<UUID, StatementSheet> subsidiaryPlMap,
            Map<UUID, StatementSheet> subsidiaryCfMap,
            BigDecimal intercompanyReceivables,
            BigDecimal intercompanyBadDebt,
            BigDecimal intercompanySalesRevenue,
            BigDecimal intercompanySalesCost,
            BigDecimal unrealizedInventoryProfit,
            Actor preparer,
            Actor approver) {}

    @GetMapping("/packages/{packageId}")
    public ResponseEntity<?> getPackage(
            @PathVariable UUID packageId,
            @RequestParam UUID tenantId) {
        try {
            return service.getPackage(tenantId, packageId)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/packages/{packageId}/publish")
    public ResponseEntity<?> publishPackage(
            @PathVariable UUID packageId,
            @RequestBody PublishPackageRequest req) {
        try {
            ReportPackage published = service.publishPackage(req.tenantId(), packageId, req.publisher());
            return ResponseEntity.ok(published);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/packages/{packageId}/worksheets")
    public ResponseEntity<?> createWorksheet(
            @PathVariable UUID packageId,
            @RequestBody CreateWorksheetRequest req) {
        try {
            Worksheet ws = service.createWorksheet(
                    req.tenantId(), packageId, req.name(), req.reason(), req.adjustments(), req.preparer()
            );
            return ResponseEntity.ok(ws);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/packages/{packageId}/worksheets/{worksheetId}/approve")
    public ResponseEntity<?> approveWorksheet(
            @PathVariable UUID packageId,
            @PathVariable UUID worksheetId,
            @RequestBody ApproveWorksheetRequest req) {
        try {
            Worksheet approved = service.approveWorksheet(req.tenantId(), packageId, worksheetId, req.approver());
            return ResponseEntity.ok(approved);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/consolidation/scopes")
    public ResponseEntity<?> createConsolidationScope(@RequestBody CreateConsolidationScopeRequest req) {
        try {
            ConsolidationScope scope = new ConsolidationScope(
                    req.scopeId() == null ? UUID.randomUUID() : req.scopeId(),
                    req.scopeCode() == null ? "SCOPE_" + UUID.randomUUID().toString().substring(0, 8) : req.scopeCode(),
                    req.parentEntityId() == null ? UUID.randomUUID() : req.parentEntityId(),
                    req.scopeName() == null ? "Consolidation Scope" : req.scopeName(),
                    req.members() == null ? List.of() : req.members(),
                    req.status() == null ? "ACTIVE" : req.status()
            );
            Actor creator = req.createdBy() != null ? req.createdBy() : new Actor(UUID.randomUUID(), "system", "SYSTEM");
            ConsolidationScope saved = service.saveConsolidationScope(req.tenantId(), scope, creator);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/consolidation/worksheet")
    public ResponseEntity<?> createConsolidationWorksheet(
            @RequestBody ConsolidationWorksheetRequest req) {
        try {
            UUID parentEntityId = req.parentEntityId() != null ? req.parentEntityId() : UUID.randomUUID();
            ConsolidationScope scope = new ConsolidationScope(
                    req.scopeId() == null ? UUID.randomUUID() : req.scopeId(),
                    req.scopeCode() == null ? "SCOPE_" + UUID.randomUUID().toString().substring(0, 8) : req.scopeCode(),
                    parentEntityId,
                    req.scopeName() == null ? "Consolidation Scope" : req.scopeName(),
                    req.members() == null ? List.of() : req.members(),
                    "ACTIVE"
            );

            ConsolidationWorksheet ws = service.generateConsolidationWorksheet(
                    req.tenantId(),
                    UUID.randomUUID(),
                    scope,
                    req.periodStart(),
                    req.periodEnd(),
                    req.isManagementRollup(),
                    req.parentBs(),
                    req.parentPl(),
                    req.parentCf(),
                    req.subsidiaryBsMap() == null ? Map.of() : req.subsidiaryBsMap(),
                    req.subsidiaryPlMap() == null ? Map.of() : req.subsidiaryPlMap(),
                    req.subsidiaryCfMap() == null ? Map.of() : req.subsidiaryCfMap(),
                    req.intercompanyReceivables(),
                    req.intercompanyBadDebt(),
                    req.intercompanySalesRevenue(),
                    req.intercompanySalesCost(),
                    req.unrealizedInventoryProfit(),
                    req.preparer(),
                    req.approver()
            );

            return ResponseEntity.ok(ws);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/kpi/evaluate")
    public ResponseEntity<?> evaluateKpis(@RequestBody EvaluateKpiRequest req) {
        try {
            KpiMetrics metrics = service.evaluateKpis(
                    req.tenantId(), req.entityId(), req.bookId(), req.yearId(), UUID.randomUUID(),
                    req.periodStart(), req.periodEnd(), req.availableCash(), req.opOutflow(), req.opInflow(), req.capexOutflow(),
                    req.periodMonths(), req.revenue(), req.cogs(), req.rdExpense(), req.avgReceivables(), req.avgPayables(),
                    req.preparer(), req.approver()
            );
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/calendar/generate")
    public ResponseEntity<?> generateCalendar(@RequestBody GenerateCalendarRequest req) {
        try {
            Standard std = parseStandard(req.standard());
            Scope scope = new Scope(req.tenantId(), req.entityId(), req.bookId(), req.yearId(), std, req.projectionDate(), req.projectionDate().plusDays(req.horizonDays()), "CNY");
            CashCalendar cal = service.generateCashCalendar(
                    UUID.randomUUID(), scope, req.projectionDate(), req.horizonDays(),
                    req.openingCash(), req.safeBufferAmount(), req.inflows(), req.outflows(), req.publisher()
            );
            return ResponseEntity.ok(cal);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }
}
