package com.lumira.finance.archiveai;

import java.time.LocalDate;
import java.time.Instant;
import java.util.*;

/**
 * Domain model and validation rules for Statutory Archive Governance (ARC01, ARC02, ARC03).
 * Governed by 《会计档案管理办法》(2015年修订) and platform completeness invariants.
 */
public final class ArchiveGovModel {

    public enum RetentionCategory {
        VOUCHER_GENERAL(30, "原始凭证、记账凭证"),
        LEDGER_BOOK(30, "总账、明细账、日记账"),
        STATEMENT_ANNUAL(999, "年度财务会计报告（决算）"),
        STATEMENT_PERIODIC(10, "月度、季度、半年度财务报告"),
        BANK_RECONCILIATION(10, "银行存款余额调节表、银行对账单"),
        TAX_DECLARATION(10, "纳税申报表及附列资料"),
        CUSTODY_TRANSFER(999, "会计档案移交清册"),
        DESTRUCTION_MANIFEST(999, "会计档案销毁清册及鉴定意见书");

        private final int statutoryYears;
        private final String description;

        RetentionCategory(int statutoryYears, String description) {
            this.statutoryYears = statutoryYears;
            this.description = description;
        }

        public int statutoryYears() {
            return statutoryYears;
        }

        public String description() {
            return description;
        }

        public static RetentionCategory fromString(String val) {
            for (RetentionCategory cat : values()) {
                if (cat.name().equalsIgnoreCase(val)) return cat;
            }
            throw new IllegalArgumentException("Unknown retention category: " + val);
        }
    }

    public record ArchiveRecord(
            UUID tenantId,
            UUID bookId,
            UUID id,
            RetentionCategory category,
            UUID fiscalYearId,
            String title,
            int statutoryYears,
            Instant archivedAt,
            LocalDate retentionUntilDate,
            UUID custodianPersonId,
            boolean legalHold,
            String holdReason,
            String status
    ) {}

    public record BorrowLog(
            UUID tenantId,
            UUID bookId,
            UUID id,
            UUID recordId,
            UUID borrowerPersonId,
            String purpose,
            Instant borrowedAt,
            LocalDate expectedReturnDate,
            Instant returnedAt,
            UUID returnInspectorPersonId,
            String status
    ) {}

    public record CustodyTransfer(
            UUID tenantId,
            UUID bookId,
            UUID id,
            String batchNo,
            List<UUID> recordIds,
            UUID transferorPersonId,
            UUID recipientPersonId,
            UUID supervisorPersonId,
            LocalDate transferDate,
            String manifestDigest,
            Instant createdAt
    ) {}

    public record DestructionManifest(
            UUID tenantId,
            UUID bookId,
            UUID id,
            List<UUID> recordIds,
            UUID appraiser1PersonId,
            UUID appraiser2PersonId,
            UUID supervisor1PersonId,
            UUID supervisor2PersonId,
            LocalDate appraisalDate,
            LocalDate destructionDate,
            String method,
            String status,
            String manifestDigest,
            Instant createdAt
    ) {}

    public record CompletenessCheckResult(
            boolean completenessSatisfied,
            boolean legalArchiveComplete,
            boolean statutoryStatementsPublished,
            boolean fiscalYearClosed,
            boolean originalsVerified,
            boolean auditLogsComplete,
            List<String> blockers
    ) {}

    public static LocalDate calculateRetentionUntilDate(int archivedYear, int statutoryYears) {
        if (statutoryYears >= 999) {
            return LocalDate.of(9999, 12, 31);
        }
        return LocalDate.of(archivedYear + statutoryYears, 12, 31);
    }

    public static void validateBorrowing(UUID borrowerId, UUID returnInspectorId) {
        Objects.requireNonNull(borrowerId, "borrowerId must not be null");
        if (returnInspectorId != null && returnInspectorId.equals(borrowerId)) {
            throw new IllegalStateException("SOD_VIOLATION: Borrower cannot inspect their own return");
        }
    }

    public static void validateBorrowReturn(UUID borrowerId, UUID returnInspectorId) {
        Objects.requireNonNull(borrowerId, "borrowerId must not be null");
        Objects.requireNonNull(returnInspectorId, "returnInspectorId must not be null");
        if (returnInspectorId.equals(borrowerId)) {
            throw new IllegalStateException("SOD_VIOLATION: Borrower cannot inspect their own return");
        }
    }

    public static void validateCustodyTransfer(UUID transferorId, UUID recipientId, UUID supervisorId) {
        Objects.requireNonNull(transferorId, "transferorId must not be null");
        Objects.requireNonNull(recipientId, "recipientId must not be null");
        Objects.requireNonNull(supervisorId, "supervisorId must not be null");

        if (transferorId.equals(recipientId)) {
            throw new IllegalStateException("SOD_VIOLATION: Transferor cannot be recipient");
        }
        if (transferorId.equals(supervisorId)) {
            throw new IllegalStateException("SOD_VIOLATION: Transferor cannot be supervisor");
        }
        if (recipientId.equals(supervisorId)) {
            throw new IllegalStateException("SOD_VIOLATION: Recipient cannot be supervisor");
        }
    }

    public static void validateDestructionAppraisal(
            List<ArchiveRecord> records,
            UUID appraiser1Id,
            UUID appraiser2Id,
            UUID supervisor1Id,
            UUID supervisor2Id,
            LocalDate currentDate
    ) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("NO_RECORDS: Destruction appraisal manifest must contain at least one archive record.");
        }
        Objects.requireNonNull(appraiser1Id, "appraiser1Id must not be null");
        Objects.requireNonNull(appraiser2Id, "appraiser2Id must not be null");
        Objects.requireNonNull(supervisor1Id, "supervisor1Id must not be null");
        Objects.requireNonNull(supervisor2Id, "supervisor2Id must not be null");

        if (appraiser1Id.equals(appraiser2Id)) {
            throw new IllegalStateException("SOD_VIOLATION: Dual appraisers must be two distinct persons");
        }
        if (supervisor1Id.equals(supervisor2Id)) {
            throw new IllegalStateException("SOD_VIOLATION: Dual supervisors must be two distinct persons");
        }
        Set<UUID> appraisers = Set.of(appraiser1Id, appraiser2Id);
        if (appraisers.contains(supervisor1Id) || appraisers.contains(supervisor2Id)) {
            throw new IllegalStateException("SOD_VIOLATION: Supervisors must be completely distinct from appraisers");
        }

        for (ArchiveRecord rec : records) {
            if (rec.legalHold()) {
                throw new IllegalStateException("LEGAL_HOLD_ACTIVE: Record " + rec.id() +
                        " is under active legal hold/tax audit. Destruction is strictly frozen.");
            }
            if (rec.category().statutoryYears() >= 999) {
                throw new IllegalStateException("PERMANENT_RECORD: Record " + rec.id() +
                        " is classified as permanent retention and cannot be destroyed.");
            }
            if (rec.retentionUntilDate().isAfter(currentDate)) {
                throw new IllegalStateException("RETENTION_NOT_EXPIRED: Record " + rec.id() +
                        " retention expires on " + rec.retentionUntilDate() + ", cannot destroy before expiry.");
            }
        }
    }

    public static CompletenessCheckResult checkArchiveCompleteness(
            boolean statementsPublished,
            boolean fiscalYearClosed,
            boolean originalsVerified,
            boolean auditLogsComplete
    ) {
        List<String> blockers = new ArrayList<>();
        if (!statementsPublished) {
            blockers.add("STATUTORY_STATEMENTS_NOT_PUBLISHED");
        }
        if (!fiscalYearClosed) {
            blockers.add("FISCAL_YEAR_NOT_CLOSED");
        }
        if (!originalsVerified) {
            blockers.add("ORIGINALS_NOT_VERIFIED");
        }
        if (!auditLogsComplete) {
            blockers.add("AUDIT_LOGS_INCOMPLETE");
        }

        boolean passed = blockers.isEmpty();
        return new CompletenessCheckResult(
                passed,
                passed,
                statementsPublished,
                fiscalYearClosed,
                originalsVerified,
                auditLogsComplete,
                Collections.unmodifiableList(blockers)
        );
    }
}
