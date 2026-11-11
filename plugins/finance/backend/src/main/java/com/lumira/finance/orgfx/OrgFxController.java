package com.lumira.finance.orgfx;

import com.lumira.finance.FinanceGlobalExceptionHandler;
import com.lumira.finance.orgfx.OrgFxModels.*;
import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Spring REST Controller for Lane W01 (org-fx).
 * Strict adherence to BoundedBody, Jackson 3, and BigDecimal plain strings.
 * Exposes all 11 endpoints declared in openapi-fragment.json plus supporting queries.
 */
@RestController
@RequestMapping("/api/finance/v1")
@ModuleEndpoint("finance")
public final class OrgFxController {
    private final OrgFxService service;

    public OrgFxController(OrgFxService service) {
        this.service = service;
    }

    private void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
    }

    // --- ORG01: Branches & Lifecycle ---

    public record BranchCreateDto(
            String branchCode,
            String branchName,
            String branchType,
            String taxFilingMode,
            String taxBureauCode,
            String taxBureauName,
            String address
    ) {}

    @PostMapping("/entities/{entityId}/branches")
    public ResponseEntity<?> createBranch(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID entityId,
            @RequestBody @BoundedBody(maxBytes = 65536, errorCode = "INVALID_INPUT") BranchCreateDto dto,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            return ResponseEntity.ok(service.createBranch(
                    tenantId,
                    entityId,
                    dto.branchCode(),
                    dto.branchName(),
                    dto.branchType(),
                    dto.taxFilingMode(),
                    dto.taxBureauCode(),
                    dto.taxBureauName(),
                    dto.address()
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/entities/{entityId}/branches")
    public ResponseEntity<?> listBranches(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID entityId,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            return ResponseEntity.ok(service.listBranches(tenantId, entityId));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    public record LifecycleTransitionDto(
            String targetStatus,
            String reason,
            UUID makerPersonId,
            UUID reviewerPersonId
    ) {}

    @PostMapping("/entities/{entityId}/lifecycle")
    public ResponseEntity<?> transitionLifecycle(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID entityId,
            @RequestBody @BoundedBody(maxBytes = 65536, errorCode = "INVALID_INPUT") LifecycleTransitionDto dto,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            Actor maker = Actor.of(UUID.randomUUID(), dto.makerPersonId());
            Actor reviewer = Actor.of(UUID.randomUUID(), dto.reviewerPersonId());
            return ResponseEntity.ok(service.transitionEntityLifecycle(
                    tenantId,
                    entityId,
                    EntityStatus.valueOf(dto.targetStatus()),
                    dto.reason(),
                    maker,
                    reviewer
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/entities/{entityId}/lifecycle")
    public ResponseEntity<?> getLifecycle(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID entityId,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            return ResponseEntity.ok(service.getLifecycle(tenantId, entityId)
                    .orElse(new EntityLifecycleRecord(tenantId, entityId, EntityStatus.PREPARING, 0L, java.time.OffsetDateTime.now())));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/tax-bureaus")
    public ResponseEntity<?> listTaxBureaus(HttpServletResponse response) {
        try {
            noCache(response);
            return ResponseEntity.ok(service.listTaxBureaus());
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- ORG02: Standards Eligibility, Policy Versions & Conversion Bridge ---

    public record StandardEligibilityDto(
            boolean isPubliclyTraded,
            boolean isFinancialInstitution,
            boolean isGroupSubsidiaryOfPublic,
            int employeeCount,
            String annualRevenue,
            String totalAssets,
            UUID assessorPersonId,
            String evaluationNotes
    ) {}

    @PostMapping("/entities/{entityId}/standard-eligibility")
    public ResponseEntity<?> assessEligibility(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID entityId,
            @RequestBody @BoundedBody(maxBytes = 65536, errorCode = "INVALID_INPUT") StandardEligibilityDto dto,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            Actor assessor = Actor.of(UUID.randomUUID(), dto.assessorPersonId());
            return ResponseEntity.ok(service.assessStandardEligibility(
                    tenantId,
                    entityId,
                    dto.isPubliclyTraded(),
                    dto.isFinancialInstitution(),
                    dto.isGroupSubsidiaryOfPublic(),
                    dto.employeeCount(),
                    new BigDecimal(dto.annualRevenue()),
                    new BigDecimal(dto.totalAssets()),
                    assessor,
                    dto.evaluationNotes()
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    public record PolicyVersionCreateDto(
            String accountingStandard,
            String depreciationMethod,
            String inventoryCostingMethod,
            boolean impairmentAllowed,
            boolean badDebtProvisionAllowed,
            String effectiveFrom,
            String effectiveTo,
            UUID makerPersonId
    ) {}

    @PostMapping("/books/{bookId}/policy-versions")
    public ResponseEntity<?> createPolicyVersion(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @RequestBody @BoundedBody(maxBytes = 32768, errorCode = "INVALID_INPUT") PolicyVersionCreateDto dto,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            Actor maker = Actor.of(UUID.randomUUID(), dto.makerPersonId());
            PolicyVersion created = service.createPolicyVersion(
                    tenantId,
                    bookId,
                    0, // auto-increment
                    AccountingStandard.valueOf(dto.accountingStandard()),
                    dto.depreciationMethod(),
                    dto.inventoryCostingMethod(),
                    dto.impairmentAllowed(),
                    dto.badDebtProvisionAllowed(),
                    LocalDate.parse(dto.effectiveFrom()),
                    dto.effectiveTo() != null && !dto.effectiveTo().isBlank() ? LocalDate.parse(dto.effectiveTo()) : null,
                    maker
            );
            return ResponseEntity.ok(Map.of(
                    "id", created.id().toString(),
                    "bookId", created.bookId().toString(),
                    "policyVersionNo", created.policyVersionNo(),
                    "accountingStandard", created.accountingStandard().name(),
                    "status", created.status().name(),
                    "version", created.version()
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    public record PolicyVersionApproveDto(
            UUID reviewerPersonId,
            String approvalDigest
    ) {}

    @PostMapping("/books/{bookId}/policy-versions/{versionId}/approve")
    public ResponseEntity<?> approvePolicyVersion(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @PathVariable UUID versionId,
            @RequestBody @BoundedBody(maxBytes = 32768, errorCode = "INVALID_INPUT") PolicyVersionApproveDto dto,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            Actor reviewer = Actor.of(UUID.randomUUID(), dto.reviewerPersonId());
            PolicyVersion approved = service.approvePolicyVersion(tenantId, bookId, versionId, reviewer, dto.approvalDigest());
            return ResponseEntity.ok(Map.of(
                    "id", approved.id().toString(),
                    "bookId", approved.bookId().toString(),
                    "policyVersionNo", approved.policyVersionNo(),
                    "accountingStandard", approved.accountingStandard().name(),
                    "status", approved.status().name(),
                    "version", approved.version()
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    public record ConversionBridgeItemDto(
            String accountCode,
            String accountName,
            String originalBalance,
            String adjustmentDebit,
            String adjustmentCredit,
            String adjustmentReason,
            String affectedOpeningBalanceType
    ) {}

    public record ConversionBridgeCreateDto(
            String fromStandard,
            String toStandard,
            int effectiveFiscalYear,
            boolean eligibleForSmall,
            UUID makerPersonId,
            UUID reviewerPersonId,
            String justification,
            List<ConversionBridgeItemDto> items
    ) {}

    @PostMapping("/books/{bookId}/conversion-bridges")
    public ResponseEntity<?> createConversionBridge(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @RequestBody @BoundedBody(maxBytes = 65536, errorCode = "INVALID_INPUT") ConversionBridgeCreateDto dto,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            Actor maker = Actor.of(UUID.randomUUID(), dto.makerPersonId());
            Actor reviewer = dto.reviewerPersonId() != null ? Actor.of(UUID.randomUUID(), dto.reviewerPersonId()) : null;

            List<ConversionBridgeItem> rawItems = new ArrayList<>();
            if (dto.items() != null) {
                for (ConversionBridgeItemDto i : dto.items()) {
                    rawItems.add(new ConversionBridgeItem(
                            UUID.randomUUID(),
                            null,
                            i.accountCode(),
                            i.accountName(),
                            new BigDecimal(i.originalBalance()),
                            new BigDecimal(i.adjustmentDebit()),
                            new BigDecimal(i.adjustmentCredit()),
                            BigDecimal.ZERO,
                            i.adjustmentReason(),
                            i.affectedOpeningBalanceType()
                    ));
                }
            }

            return ResponseEntity.ok(service.createConversionBridge(
                    tenantId,
                    bookId,
                    AccountingStandard.valueOf(dto.fromStandard()),
                    AccountingStandard.valueOf(dto.toStandard()),
                    dto.effectiveFiscalYear(),
                    dto.eligibleForSmall(),
                    maker,
                    reviewer,
                    dto.justification(),
                    rawItems
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- ORG03: Multi-Date Semantics & Period Reopen ---

    public record MultiDateValidateDto(
            String businessDate,
            String invoiceDate,
            String accountingDate,
            String taxObligationDate,
            String periodStatus,
            String justification
    ) {}

    @PostMapping("/books/{bookId}/multi-date/validate")
    public ResponseEntity<?> validateDates(
            @PathVariable UUID bookId,
            @RequestBody @BoundedBody(maxBytes = 32768, errorCode = "INVALID_INPUT") MultiDateValidateDto dto,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            MultiDateContext ctx = new MultiDateContext(
                    dto.businessDate() != null ? LocalDate.parse(dto.businessDate()) : null,
                    dto.invoiceDate() != null ? LocalDate.parse(dto.invoiceDate()) : null,
                    dto.accountingDate() != null ? LocalDate.parse(dto.accountingDate()) : null,
                    dto.taxObligationDate() != null ? LocalDate.parse(dto.taxObligationDate()) : null,
                    dto.periodStatus(),
                    dto.justification()
            );
            return ResponseEntity.ok(service.validateMultiDates(ctx));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    public record PeriodReopenDto(
            String action,
            String reason,
            UUID actorPersonId,
            String decisionReason
    ) {}

    @PostMapping("/books/{bookId}/periods/{periodId}/reopen")
    public ResponseEntity<?> handlePeriodReopen(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @PathVariable UUID periodId,
            @RequestBody @BoundedBody(maxBytes = 32768, errorCode = "INVALID_INPUT") PeriodReopenDto dto,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            Actor actor = Actor.of(UUID.randomUUID(), dto.actorPersonId());
            return ResponseEntity.ok(service.handlePeriodReopen(
                    tenantId,
                    bookId,
                    periodId,
                    dto.action(),
                    dto.reason(),
                    actor,
                    dto.decisionReason()
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- ORG04: FX Rate, Line Translation, Revaluation ---

    public record FxRateRegisterDto(
            String sourceCurrency,
            String rateDate,
            String rateType,
            String rateValue,
            String quoteSource,
            UUID makerPersonId,
            UUID reviewerPersonId
    ) {}

    @PostMapping("/fx/rate-snapshots")
    public ResponseEntity<?> registerFxRate(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestBody @BoundedBody(maxBytes = 32768, errorCode = "INVALID_INPUT") FxRateRegisterDto dto,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            Actor maker = Actor.of(UUID.randomUUID(), dto.makerPersonId());
            Actor reviewer = dto.reviewerPersonId() != null ? Actor.of(UUID.randomUUID(), dto.reviewerPersonId()) : null;
            FxRateSnapshot snapshot = service.registerFxRateSnapshot(
                    tenantId,
                    dto.sourceCurrency(),
                    LocalDate.parse(dto.rateDate()),
                    FxRateType.valueOf(dto.rateType()),
                    new BigDecimal(dto.rateValue()),
                    dto.quoteSource(),
                    maker,
                    reviewer
            );
            return ResponseEntity.ok(Map.of(
                    "id", snapshot.id().toString(),
                    "sourceCurrency", snapshot.sourceCurrency(),
                    "targetCurrency", snapshot.targetCurrency(),
                    "rateDate", snapshot.rateDate().toString(),
                    "rateType", snapshot.rateType().name(),
                    "rateValue", snapshot.rateValue().toPlainString()
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    public record FxLineTranslateDto(
            String originalCurrency,
            String originalAmount,
            String rateValue
    ) {}

    @PostMapping("/fx/translate-line")
    public ResponseEntity<?> translateLine(
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT") FxLineTranslateDto dto,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            FxLineTranslationResult result = service.translateLine(
                    dto.originalCurrency(),
                    new BigDecimal(dto.originalAmount()),
                    new BigDecimal(dto.rateValue())
            );
            return ResponseEntity.ok(Map.of(
                    "originalCurrency", result.originalCurrency(),
                    "originalAmount", result.originalAmount().toPlainString(),
                    "rateValue", result.rateValue().toPlainString(),
                    "functionalCnyExact", result.functionalCnyExact().toPlainString(),
                    "functionalCnyQuantized", result.functionalCnyQuantized().toPlainString(),
                    "roundingDifference", result.roundingDifference().toPlainString()
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    public record FxRevaluationItemDto(
            String accountCode,
            String accountName,
            String currencyCode,
            String foreignBalance,
            String carryingCnyBalance,
            String closingRate,
            String targetExchangeAccount
    ) {}

    public record FxRevaluationPlanDto(
            UUID periodId,
            String revaluationDate,
            UUID closingRateSnapshotId,
            UUID makerPersonId,
            List<FxRevaluationItemDto> items
    ) {}

    @PostMapping("/books/{bookId}/fx-revaluations")
    public ResponseEntity<?> createFxRevaluationPlan(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID bookId,
            @RequestBody @BoundedBody(maxBytes = 65536, errorCode = "INVALID_INPUT") FxRevaluationPlanDto dto,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            Actor maker = Actor.of(UUID.randomUUID(), dto.makerPersonId());
            List<FxRevaluationItem> rawItems = new ArrayList<>();
            if (dto.items() != null) {
                for (FxRevaluationItemDto item : dto.items()) {
                    rawItems.add(new FxRevaluationItem(
                            null,
                            null,
                            item.accountCode(),
                            item.accountName(),
                            item.currencyCode(),
                            new BigDecimal(item.foreignBalance()),
                            new BigDecimal(item.carryingCnyBalance()),
                            new BigDecimal(item.closingRate()),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            item.targetExchangeAccount()
                    ));
                }
            }

            FxRevaluationPlan plan = service.createRevaluationPlan(
                    tenantId,
                    bookId,
                    UUID.randomUUID(),
                    dto.periodId(),
                    LocalDate.parse(dto.revaluationDate()),
                    dto.closingRateSnapshotId(),
                    maker,
                    rawItems
            );

            return ResponseEntity.ok(Map.of(
                    "id", plan.id().toString(),
                    "bookId", plan.bookId().toString(),
                    "status", plan.status(),
                    "totalGainLoss", plan.totalGainLoss().toPlainString(),
                    "itemCount", plan.items().size()
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/currencies")
    public ResponseEntity<?> listCurrencies(HttpServletResponse response) {
        try {
            noCache(response);
            return ResponseEntity.ok(service.listCurrencies());
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- ORG05: Tax Calendar & Obligations ---

    public record TaxResolveDto(
            String regionCode,
            int taxYear,
            int periodMonth,
            String taxpayerType,
            String citMode,
            boolean isSmallMicroEnterprise
    ) {}

    @PostMapping("/tax-calendars/resolve-obligations")
    public ResponseEntity<?> resolveObligations(
            @RequestBody @BoundedBody(maxBytes = 32768, errorCode = "INVALID_INPUT") TaxResolveDto dto,
            HttpServletResponse response
    ) {
        try {
            noCache(response);
            List<ResolvedTaxObligation> list = service.resolveTaxObligations(
                    dto.regionCode(),
                    dto.taxYear(),
                    dto.periodMonth(),
                    dto.taxpayerType(),
                    dto.citMode(),
                    dto.isSmallMicroEnterprise()
            );
            return ResponseEntity.ok(Map.of(
                    "obligations", list,
                    "governmentVerification", "NOT_VERIFIED"
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }
}
