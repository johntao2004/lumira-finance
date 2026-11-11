package com.lumira.finance.statutory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public final class StatutoryModels {
    private StatutoryModels() {}

    public enum Standard { CAS_2019, SBE_2011 }
    public enum StatementKind { BS, PL, CF, SCE, NOTES }
    public enum PackageType { THREE_STATEMENT_PACKAGE, FULL_STATUTORY_REPORT_PACKAGE }
    public enum PackageStatus { DRAFT, REVIEWED, APPROVED, PUBLISHED, BLOCKED }
    public enum FilingTarget { TAX_FILING, MARKET_SUPERVISION_FILING, STATUTORY_AUDIT_FILING }
    public enum ConsolidationMethod { FULL_CONSOLIDATION, EQUITY_METHOD }

    public record Actor(UUID personId, String name, String role) {
        public Actor {
            Objects.requireNonNull(personId, "personId cannot be null");
            Objects.requireNonNull(name, "name cannot be null");
        }
    }

    public record Scope(
            UUID tenantId,
            UUID entityId,
            UUID bookId,
            UUID yearId,
            Standard standard,
            LocalDate from,
            LocalDate through,
            String currency) {
        public Scope {
            Objects.requireNonNull(tenantId, "tenantId cannot be null");
            Objects.requireNonNull(entityId, "entityId cannot be null");
            Objects.requireNonNull(bookId, "bookId cannot be null");
            Objects.requireNonNull(yearId, "yearId cannot be null");
            Objects.requireNonNull(standard, "standard cannot be null");
            Objects.requireNonNull(from, "from cannot be null");
            Objects.requireNonNull(through, "through cannot be null");
            if (from.isAfter(through)) throw new IllegalArgumentException("from date must not be after through date");
            if (currency == null || currency.isBlank()) currency = "CNY";
        }
    }

    public record VoucherLine(
            int lineNo,
            String accountCode,
            String accountName,
            String direction, // "DEBIT" or "CREDIT"
            BigDecimal amount,
            String memo) {
        public VoucherLine {
            Objects.requireNonNull(accountCode, "accountCode cannot be null");
            Objects.requireNonNull(accountName, "accountName cannot be null");
            Objects.requireNonNull(direction, "direction cannot be null");
            Objects.requireNonNull(amount, "amount cannot be null");
            if (amount.signum() <= 0) throw new IllegalArgumentException("VoucherLine amount must be strictly positive");
        }
    }

    public record CanonicalVoucher(
            String code,
            String title,
            Standard standard,
            String businessDescription,
            List<VoucherLine> lines,
            BigDecimal totalDebit,
            BigDecimal totalCredit) {
        public CanonicalVoucher {
            lines = List.copyOf(lines);
            if (totalDebit == null || totalCredit == null || totalDebit.compareTo(totalCredit) != 0) {
                throw new IllegalArgumentException("Voucher lines must be balanced: totalDebit=" + totalDebit + " vs totalCredit=" + totalCredit);
            }
        }
    }

    public record TeachingGuide(
            String guideCode,
            Standard standard,
            String topic,
            String title,
            Map<String, Object> content,
            List<CanonicalVoucher> vouchers) {
        public TeachingGuide {
            content = Map.copyOf(content);
            vouchers = List.copyOf(vouchers);
        }
    }

    public record AccountBalance(
            String accountCode,
            String accountName,
            String nettingUnit,
            String objectId,
            String semantic,
            BigDecimal debitBalance,
            BigDecimal creditBalance) {
        public AccountBalance {
            if (debitBalance == null) debitBalance = BigDecimal.ZERO;
            if (creditBalance == null) creditBalance = BigDecimal.ZERO;
        }
        public BigDecimal netDebit() {
            return debitBalance.subtract(creditBalance);
        }
    }

    public record ContraBalance(
            String contraAccountCode,
            String targetAccountCode,
            String nettingUnit,
            String contraType, // "DEPRECIATION", "AMORTIZATION", "IMPAIRMENT"
            BigDecimal creditBalance) {
        public ContraBalance {
            Objects.requireNonNull(creditBalance);
            if (creditBalance.signum() < 0) throw new IllegalArgumentException("Contra credit balance cannot be negative");
        }
    }

    public record MovementRecord(
            String accountCode,
            String semantic,
            LocalDate date,
            BigDecimal debit,
            BigDecimal credit,
            String closingType) { // "NONE", "MONTH_PROFIT_TRANSFER", "ANNUAL_PROFIT_TRANSFER", "TRANSFER_REVERSAL"
        public MovementRecord {
            if (debit == null) debit = BigDecimal.ZERO;
            if (credit == null) credit = BigDecimal.ZERO;
            if (closingType == null) closingType = "NONE";
        }
    }

    public record ReportCell(
            String rowId,
            String label,
            String rawValue,
            String formattedValue,
            String status, // "EXACT", "DRAFT", "MISSING", "ZERO"
            List<String> gaps) {
        public ReportCell {
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }
    }

    public record ReportTrace(
            String operation,
            String sourceAccount,
            String nettingUnit,
            String fromRow,
            String toRow,
            String amount,
            String explanation) {}

    public record StatementSheet(
            StatementKind kind,
            String templateCode,
            Map<String, ReportCell> cells,
            String articulationStatus, // "PASS", "FAIL", "BLOCKED"
            Map<String, Object> articulationDetails,
            List<ReportTrace> traces) {
        public StatementSheet {
            cells = Map.copyOf(cells);
            articulationDetails = Map.copyOf(articulationDetails);
            traces = traces == null ? List.of() : List.copyOf(traces);
        }
    }

    public record AdjustmentEntry(
            UUID id,
            StatementKind statementKind,
            String rowId,
            BigDecimal adjustmentAmount,
            String reason,
            Actor preparer,
            Actor approver,
            Instant createdAt) {}

    public record Worksheet(
            UUID id,
            UUID packageId,
            String name,
            List<AdjustmentEntry> adjustments,
            String beforeDigest,
            String afterDigest,
            String status, // "PENDING", "APPROVED", "REJECTED"
            Actor preparer,
            Actor approver) {
        public Worksheet {
            adjustments = adjustments == null ? List.of() : List.copyOf(adjustments);
        }
    }

    public record StructuredNotes(
            Map<String, String> sections,
            boolean mandatoryComplete,
            List<String> missingSections) {
        public StructuredNotes {
            sections = Map.copyOf(sections);
            missingSections = List.copyOf(missingSections);
        }
    }

    public record ReportPackage(
            UUID id,
            Scope scope,
            PackageType packageType,
            FilingTarget filingTarget,
            PackageStatus status,
            List<StatementSheet> sheets,
            Worksheet worksheet,
            StructuredNotes notes,
            List<String> publicationGaps,
            String contentDigest,
            Actor preparer,
            Actor reviewer,
            Actor approver,
            Instant createdAt,
            Instant updatedAt) {
        public ReportPackage {
            sheets = List.copyOf(sheets);
            publicationGaps = List.copyOf(publicationGaps);
        }
    }

    public record KpiMetrics(
            BigDecimal burnRate,
            BigDecimal runwayMonths,
            BigDecimal rdIntensity,
            BigDecimal grossMargin,
            BigDecimal dsoDays,
            BigDecimal dpoDays,
            Map<String, Object> details,
            Actor preparer,
            Actor approver,
            String status) {
        public KpiMetrics {
            details = Map.copyOf(details);
        }
    }

    public record DailyCashForecast(
            LocalDate date,
            BigDecimal scheduledInflow,
            BigDecimal scheduledOutflow,
            BigDecimal netDailyChange,
            BigDecimal projectedBalance,
            boolean belowBuffer) {}

    public record CashCalendar(
            UUID id,
            Scope scope,
            LocalDate projectionDate,
            int horizonDays,
            BigDecimal openingBalance,
            BigDecimal safeBufferAmount,
            BigDecimal minProjectedBalance,
            boolean bufferBreached,
            List<DailyCashForecast> dailyForecasts,
            String status, // "DRAFT", "PUBLISHED"
            Actor publisher) {
        public CashCalendar {
            dailyForecasts = List.copyOf(dailyForecasts);
        }
    }

    public record ConsolidationMember(
            UUID entityId,
            String entityName,
            BigDecimal shareholdingRatio,
            BigDecimal votingRatio,
            ConsolidationMethod method) {}

    public record ConsolidationScope(
            UUID id,
            String scopeCode,
            UUID parentEntityId,
            String scopeName,
            List<ConsolidationMember> members,
            String status) {
        public ConsolidationScope {
            members = List.copyOf(members);
        }
    }

    public record EliminationEntry(
            String entryType,
            String description,
            List<VoucherLine> lines,
            BigDecimal totalDebit,
            BigDecimal totalCredit) {
        public EliminationEntry {
            lines = List.copyOf(lines);
        }
    }

    public record MinorityInterest(
            UUID subsidiaryId,
            String subsidiaryName,
            BigDecimal minorityRatio,
            BigDecimal minorityEquity,
            BigDecimal minorityProfit) {}

    public record ConsolidationWorksheet(
            UUID id,
            UUID scopeId,
            LocalDate periodStart,
            LocalDate periodEnd,
            boolean isManagementRollup,
            Map<String, Object> rollupData,
            List<EliminationEntry> eliminations,
            Map<String, StatementSheet> consolidatedSheets,
            List<MinorityInterest> minorityInterests,
            String status, // "DRAFT", "REVIEWED", "APPROVED"
            Actor preparer,
            Actor approver) {
        public ConsolidationWorksheet {
            rollupData = Map.copyOf(rollupData);
            eliminations = List.copyOf(eliminations);
            consolidatedSheets = Map.copyOf(consolidatedSheets);
            minorityInterests = List.copyOf(minorityInterests);
        }
    }
}
