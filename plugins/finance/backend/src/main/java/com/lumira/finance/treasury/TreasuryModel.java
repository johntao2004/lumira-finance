package com.lumira.finance.treasury;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Immutable domain models, records, and enums for Candidate Lane W07: Treasury
 * (MD04, TR01, TR02, TR03, TR04).
 */
public final class TreasuryModel {
    private TreasuryModel() {}

    public enum BankAccountType {
        BASIC, GENERAL, TAX, CASH
    }

    public enum BankAccountStatus {
        PENDING_REVIEW, ACTIVE, FROZEN, CLOSED
    }

    public enum OrderType {
        RECEIPT, PAYMENT, TRANSFER_OUT, TRANSFER_IN
    }

    public enum OrderStatus {
        DRAFT, APPROVED, IN_TRANSIT, EXECUTED, CANCELLED, FAILED
    }

    public enum InstructionStatus {
        DRAFT, APPROVED, DISPATCHED_UNKNOWN, SETTLED, FAILED_RETURNED, CANCELLED
    }

    public enum StatementFormat {
        CSV, JSON
    }

    public enum StatementBatchStatus {
        IMPORTED, RECONCILED, CANCELLED
    }

    public enum StatementDirection {
        CREDIT, DEBIT
    }

    public enum StatementMatchStatus {
        UNMATCHED, PROPOSED, MATCHED, AMBIGUOUS_MULTI_CANDIDATE
    }

    public enum ReconciliationStatus {
        DRAFT, CONFIRMED, CANCELLED
    }

    public enum UnreachedItemType {
        ENTERPRISE_RECORDED_BANK_UNRECORDED,
        ENTERPRISE_PAID_BANK_UNPAID,
        BANK_CREDITED_ENTERPRISE_UNRECORDED,
        BANK_DEBITED_ENTERPRISE_UNRECORDED
    }

    public enum UnreachedItemStatus {
        PENDING, INVESTIGATING, ADJUSTED, CLEARED
    }

    public enum ForecastTier {
        CONFIRMED, COMMITTED, PLANNED
    }

    public record Actor(UUID accountId, UUID personId) {}
    public record Scope(UUID tenantId, UUID bookId) {}

    public record BankAccount(
            UUID id,
            UUID tenantId,
            UUID bookId,
            String accountNo,
            String accountName,
            String bankName,
            String branchName,
            BankAccountType accountType,
            String currency,
            UUID glAccountId,
            BankAccountStatus status,
            UUID makerPersonId,
            UUID reviewerPersonId,
            String frozenReason,
            String closingReason,
            int version,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record NumberingPolicy(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID periodId,
            int periodNo,
            String orderType,
            String prefix,
            String pattern,
            int currentSequence,
            boolean isApproved,
            UUID makerPersonId,
            UUID reviewerPersonId,
            int version,
            Instant createdAt
    ) {}

    public record TreasuryOrder(
            UUID id,
            UUID tenantId,
            UUID bookId,
            String orderNo,
            OrderType orderType,
            UUID accountId,
            UUID counterpartyId,
            UUID targetAccountId,
            UUID transferPairId,
            LocalDate orderDate,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feeAmount,
            UUID feeGlAccountId,
            OrderStatus status,
            String purpose,
            UUID makerPersonId,
            UUID reviewerPersonId,
            UUID journalId,
            int version,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record PaymentInstruction(
            UUID id,
            UUID tenantId,
            UUID bookId,
            String instructionNo,
            UUID treasuryOrderId,
            UUID payerAccountId,
            String payeeAccountNo,
            String payeeAccountName,
            String payeeBankName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            InstructionStatus status,
            String bankDirectChannel,
            UUID makerPersonId,
            UUID approverPersonId,
            Instant dispatchedAt,
            String receiptOriginalSha256,
            String receiptProviderRef,
            UUID receiptVerifierPersonId,
            Instant receiptVerifiedAt,
            String failureReason,
            UUID adjustmentRefId,
            int version,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record BankStatementBatch(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID accountId,
            String fileSha256,
            StatementFormat fileFormat,
            LocalDate statementDate,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openingBalance,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal closingBalance,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalCreditAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalDebitAmount,
            int recordCount,
            UUID importedBy,
            StatementBatchStatus status,
            Instant createdAt
    ) {}

    public record BankStatementLine(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID batchId,
            UUID accountId,
            int lineNo,
            String lineHash,
            LocalDate transactionDate,
            LocalDate valueDate,
            StatementDirection direction,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balanceAfter,
            String counterpartyAccountNo,
            String counterpartyName,
            String transactionNo,
            String summary,
            StatementMatchStatus matchStatus,
            UUID matchedOrderId,
            UUID matchedJournalLineId,
            UUID matchReviewerPersonId,
            Instant matchedAt,
            Instant createdAt
    ) {}

    public record BankReconciliation(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID accountId,
            UUID periodId,
            int periodNo,
            LocalDate cutoffDate,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal bankStatementBalance,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal enterpriseRecordedBankUnrecorded,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal enterprisePaidBankUnpaid,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal adjustedBankBalance,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal ledgerBalance,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal bankCreditedEnterpriseUnrecorded,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal bankDebitedEnterpriseUnrecorded,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal adjustedLedgerBalance,
            boolean isBalanced,
            ReconciliationStatus status,
            UUID preparerPersonId,
            UUID reviewerPersonId,
            int version,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record UnreachedItem(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID reconciliationId,
            UUID accountId,
            UnreachedItemType itemType,
            LocalDate recordedDate,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            String summary,
            int agingDays,
            boolean isOverdueWarning,
            UnreachedItemStatus status,
            String investigationNotes,
            UUID supplementaryEvidenceId,
            Instant createdAt
    ) {}

    public record CashForecast(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID accountId,
            int forecastHorizonDays,
            ForecastTier tier,
            LocalDate forecastDate,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openingCash,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal projectedInflow,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal projectedOutflow,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal closingCash,
            boolean isReadOnlyForecast,
            String sourceReference,
            Instant createdAt
    ) {}

    public record TreasuryEvent(
            UUID id,
            UUID tenantId,
            UUID bookId,
            String entityType,
            UUID entityId,
            String action,
            UUID operatorPersonId,
            String payload,
            Instant createdAt
    ) {}

    // Input DTOs
    public record CreateAccountInput(
            String accountNo,
            String accountName,
            String bankName,
            String branchName,
            String accountType,
            UUID glAccountId
    ) {}

    public record ReviewAccountInput(
            String action, // ACTIVATE, FREEZE, UNFREEZE, CLOSE
            String reason
    ) {}

    public record ConfigureNumberingInput(
            UUID periodId,
            int periodNo,
            String orderType,
            String prefix,
            String pattern
    ) {}

    public record CreateOrderInput(
            String orderType,
            UUID accountId,
            UUID counterpartyId,
            UUID targetAccountId,
            LocalDate orderDate,
            String amount,
            String feeAmount,
            UUID feeGlAccountId,
            String purpose,
            UUID periodId
    ) {
        public CreateOrderInput(
                String orderType,
                UUID accountId,
                UUID counterpartyId,
                UUID targetAccountId,
                LocalDate orderDate,
                String amount,
                String feeAmount,
                UUID feeGlAccountId,
                String purpose
        ) {
            this(orderType, accountId, counterpartyId, targetAccountId, orderDate, amount, feeAmount, feeGlAccountId, purpose, null);
        }
    }

    public record CreatePaymentInstructionInput(
            UUID treasuryOrderId,
            UUID payerAccountId,
            String payeeAccountNo,
            String payeeAccountName,
            String payeeBankName,
            String amount
    ) {}

    public record VerifySettlementReceiptInput(
            String providerRef,
            String receiptOriginalSha256
    ) {}

    public record RawStatementLine(
            int lineNo,
            LocalDate transactionDate,
            LocalDate valueDate,
            StatementDirection direction,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String counterpartyAccountNo,
            String counterpartyName,
            String transactionNo,
            String summary
    ) {}

    public record ImportStatementInput(
            UUID accountId,
            String fileFormat,
            String rawContent,
            LocalDate statementDate,
            String openingBalance,
            String closingBalance,
            String totalCreditAmount,
            String totalDebitAmount,
            int recordCount
    ) {}

    public record MatchReviewInput(
            UUID lineId,
            UUID targetOrderId,
            UUID targetJournalLineId,
            String decision // CONFIRM, REJECT, AMBIGUOUS
    ) {}

    public record UnreachedItemInput(
            String itemType,
            LocalDate recordedDate,
            String amount,
            String summary,
            UUID supplementaryEvidenceId
    ) {}

    public record CreateReconciliationInput(
            UUID accountId,
            UUID periodId,
            int periodNo,
            LocalDate cutoffDate,
            String bankStatementBalance,
            String ledgerBalance,
            List<UnreachedItemInput> unreachedItems
    ) {}

    public record ForecastItemInput(
            String tier,
            String inflow,
            String outflow,
            String sourceReference
    ) {}

    public record GenerateForecastInput(
            UUID accountId,
            int horizonDays,
            LocalDate forecastDate,
            String openingCash,
            List<ForecastItemInput> items
    ) {}
}
