package com.lumira.finance.archiveai;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Service coordinating Archive Governance, Import Batches, ERP Events,
 * Recurring Schedules, AI Suggestions / ActionPlans, and Offline Leases.
 */
@Service
public class ArchiveAiService {

    private final ArchiveAiJdbcStore store;

    public ArchiveAiService(ArchiveAiJdbcStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    // --- 1. Archive Governance ---
    @Transactional
    public void initDefaultRetentionRules(UUID tenantId) throws SQLException {
        for (ArchiveGovModel.RetentionCategory cat : ArchiveGovModel.RetentionCategory.values()) {
            store.insertRetentionRule(tenantId, cat.name(), cat.statutoryYears(), cat.description());
        }
    }

    @Transactional
    public ArchiveGovModel.ArchiveRecord createArchiveRecord(
            UUID tenantId, UUID bookId, UUID id, ArchiveGovModel.RetentionCategory category,
            UUID fiscalYearId, String title, int startYear, UUID custodianPersonId, boolean legalHold, String holdReason
    ) throws SQLException {
        int years = category.statutoryYears();
        LocalDate expiry = ArchiveGovModel.calculateRetentionUntilDate(startYear, years);
        ArchiveGovModel.ArchiveRecord rec = new ArchiveGovModel.ArchiveRecord(
                tenantId, bookId, id, category, fiscalYearId, title, years,
                Instant.now(), expiry, custodianPersonId, legalHold, holdReason, "IN_CUSTODY"
        );
        store.insertArchiveRecord(rec);
        return rec;
    }

    public Optional<ArchiveGovModel.ArchiveRecord> findArchiveRecord(UUID tenantId, UUID bookId, UUID recordId) throws SQLException {
        return store.findArchiveRecord(tenantId, bookId, recordId);
    }

    @Transactional
    public ArchiveGovModel.BorrowLog borrowRecord(
            UUID tenantId, UUID bookId, UUID borrowId, UUID recordId,
            UUID borrowerPersonId, String purpose, LocalDate expectedReturnDate
    ) throws SQLException {
        ArchiveGovModel.validateBorrowing(borrowerPersonId, null);
        Optional<ArchiveGovModel.ArchiveRecord> opt = store.findArchiveRecord(tenantId, bookId, recordId);
        if (opt.isEmpty()) {
            throw new IllegalArgumentException("ARCHIVE_RECORD_NOT_FOUND: Record " + recordId + " does not exist");
        }
        ArchiveGovModel.ArchiveRecord r = opt.get();
        if ("DESTROYED".equalsIgnoreCase(r.status())) {
            throw new IllegalStateException("RECORD_DESTROYED: Record " + recordId + " has been destroyed and cannot be borrowed.");
        }
        if ("BORROWED".equalsIgnoreCase(r.status())) {
            throw new IllegalStateException("RECORD_ALREADY_BORROWED: Record " + recordId + " is currently borrowed.");
        }
        ArchiveGovModel.BorrowLog log = new ArchiveGovModel.BorrowLog(
                tenantId, bookId, borrowId, recordId, borrowerPersonId, purpose,
                Instant.now(), expectedReturnDate, null, null, "BORROWED"
        );
        store.insertBorrowLog(log);
        store.updateArchiveRecordStatus(tenantId, bookId, recordId, "BORROWED");
        return log;
    }

    @Transactional
    public ArchiveGovModel.BorrowLog returnRecord(
            UUID tenantId, UUID bookId, UUID borrowId, UUID recordId, UUID returnInspectorPersonId
    ) throws SQLException {
        Optional<ArchiveGovModel.BorrowLog> logOpt = store.findBorrowLog(tenantId, bookId, borrowId);
        if (logOpt.isEmpty()) {
            throw new IllegalArgumentException("BORROW_RECORD_NOT_FOUND: Borrow log " + borrowId + " does not exist");
        }
        ArchiveGovModel.BorrowLog log = logOpt.get();
        ArchiveGovModel.validateBorrowReturn(log.borrowerPersonId(), returnInspectorPersonId);
        Instant returnedAt = Instant.now();
        store.updateBorrowLogReturn(tenantId, bookId, borrowId, returnInspectorPersonId, returnedAt);
        store.updateArchiveRecordStatus(tenantId, bookId, recordId, "IN_CUSTODY");
        return new ArchiveGovModel.BorrowLog(
                log.tenantId(), log.bookId(), log.id(), log.recordId(), log.borrowerPersonId(),
                log.purpose(), log.borrowedAt(), log.expectedReturnDate(), returnedAt,
                returnInspectorPersonId, "RETURNED"
        );
    }

    @Transactional
    public ArchiveGovModel.CustodyTransfer transferCustody(
            UUID tenantId, UUID bookId, UUID transferId, String batchNo, List<UUID> recordIds,
            UUID transferorId, UUID recipientId, UUID supervisorId, LocalDate transferDate
    ) throws SQLException {
        ArchiveGovModel.validateCustodyTransfer(transferorId, recipientId, supervisorId);
        String digest = ErpEventEngine.sha256Hex(batchNo + ":" + recordIds.toString() + ":" + transferDate);
        ArchiveGovModel.CustodyTransfer ct = new ArchiveGovModel.CustodyTransfer(
                tenantId, bookId, transferId, batchNo, recordIds, transferorId,
                recipientId, supervisorId, transferDate, digest, Instant.now()
        );
        store.insertCustodyTransfer(ct);
        return ct;
    }

    @Transactional
    public ArchiveGovModel.DestructionManifest appraiseAndSuperviseDestruction(
            UUID tenantId, UUID bookId, UUID manifestId, List<ArchiveGovModel.ArchiveRecord> records,
            UUID appraiser1Id, UUID appraiser2Id, UUID supervisor1Id, UUID supervisor2Id,
            LocalDate appraisalDate, LocalDate destructionDate, String method
    ) throws SQLException {
        ArchiveGovModel.validateDestructionAppraisal(records, appraiser1Id, appraiser2Id, supervisor1Id, supervisor2Id, appraisalDate);
        List<UUID> recordIds = records.stream().map(ArchiveGovModel.ArchiveRecord::id).toList();
        String digest = ErpEventEngine.sha256Hex("destruction:" + recordIds.toString() + ":" + appraisalDate);
        ArchiveGovModel.DestructionManifest dm = new ArchiveGovModel.DestructionManifest(
                tenantId, bookId, manifestId, recordIds, appraiser1Id, appraiser2Id,
                supervisor1Id, supervisor2Id, appraisalDate, destructionDate, method, "APPROVED_FOR_DESTRUCTION", digest, Instant.now()
        );
        store.insertDestructionManifest(dm);
        for (ArchiveGovModel.ArchiveRecord rec : records) {
            store.updateArchiveRecordStatus(rec.tenantId(), rec.bookId(), rec.id(), "DESTROYED");
        }
        return dm;
    }

    public ArchiveGovModel.CompletenessCheckResult verifyArchiveCompleteness(
            boolean statementsPublished, boolean fiscalYearClosed, boolean originalsVerified, boolean auditLogsComplete
    ) {
        return ArchiveGovModel.checkArchiveCompleteness(statementsPublished, fiscalYearClosed, originalsVerified, auditLogsComplete);
    }

    // --- 2. Import Batch Engine ---
    public ImportBatchEngine.DryRunResult dryRunImport(
            List<ImportBatchEngine.FieldMapping> mappings, String delimiter, String csvContent
    ) {
        return ImportBatchEngine.dryRunCsv(mappings, delimiter, csvContent);
    }

    @Transactional
    public void approveImportBatch(UUID tenantId, UUID bookId, UUID batchId, UUID preparerId, UUID approverId, String reason) throws SQLException {
        ImportBatchEngine.validateBatchApproval(preparerId, approverId);
        store.approveImportBatch(tenantId, bookId, batchId, approverId, reason);
    }

    public ImportBatchEngine.ReconciliationResult reconcileBatchTotals(
            BigDecimal expectedDebit, BigDecimal expectedCredit, BigDecimal actualDebit, BigDecimal actualCredit,
            int expectedRows, int actualRows
    ) {
        return ImportBatchEngine.reconcileControlTotals(expectedDebit, expectedCredit, actualDebit, actualCredit, expectedRows, actualRows);
    }

    // --- 3. ERP Connector, AccountingEvent & Anti-CRM ---
    @Transactional
    public void ingestAccountingEventWithAntiCrmGuard(
            String rawPayloadJson,
            ErpEventEngine.ErpConnector connector,
            String providedApiKey,
            ErpEventEngine.AccountingEvent event,
            long incomingWatermark,
            Map<String, String> existingEventDigests,
            Map<String, UUID> existingDraftMap
    ) throws SQLException {
        // Anti-CRM Guard: inspects raw payload for CRM fields
        try {
            ErpEventEngine.runAntiCrmGuard(rawPayloadJson);
        } catch (IllegalStateException e) {
            UUID tid = (event != null) ? event.tenantId() : (connector != null ? connector.tenantId() : UUID.randomUUID());
            String sys = (event != null) ? event.sourceSystem() : (connector != null ? connector.connectorName() : "UNKNOWN");
            store.logAntiCrmRejection(tid, UUID.randomUUID(), sys,
                    rawPayloadJson.substring(0, Math.min(rawPayloadJson.length(), 200)), "[\"crm_field_detected\"]", e.getMessage());
            throw e;
        }

        // Connector authorization
        ErpEventEngine.validateConnectorAuth(connector, providedApiKey, "FINANCE_EVENT_WRITE");

        Map<String, String> digests = (existingEventDigests != null) ? new HashMap<>(existingEventDigests) : new HashMap<>();
        Map<String, UUID> drafts = (existingDraftMap != null) ? new HashMap<>(existingDraftMap) : new HashMap<>();
        if (event != null && !digests.containsKey(event.sourceEventId())) {
            Optional<ArchiveAiJdbcStore.InboxEntry> stored = store.findEventInbox(event.tenantId(), event.sourceSystem(), event.sourceEventId());
            if (stored.isPresent()) {
                digests.put(event.sourceEventId(), stored.get().payloadDigest());
                if (stored.get().draftJournalId() != null) {
                    drafts.put(event.sourceEventId(), stored.get().draftJournalId());
                }
            }
        }

        long latestWatermark = store.getLatestWatermark(event.tenantId(), event.sourceSystem(), event.bookId());
        ErpEventEngine.IngestionResult res = ErpEventEngine.processAccountingEvent(
                event, latestWatermark, incomingWatermark, digests, drafts
        );

        if ("DUPLICATE_IGNORED".equals(res.status())) {
            return;
        }

        if ("WATERMARK_OUT_OF_ORDER".equals(res.status())) {
            store.insertEventInbox(event.tenantId(), event.eventId(), event.sourceSystem(), event.sourceEventId(),
                    event.sourceDocumentId(), event.sourceDocumentVersion(), event.eventType(), event.legalEntityId(),
                    event.bookId(), incomingWatermark, event.payloadDigest(), rawPayloadJson, null,
                    event.reversesSourceEventId(), "WATERMARK_OUT_OF_ORDER");
            return;
        }

        store.insertEventInbox(event.tenantId(), event.eventId(), event.sourceSystem(), event.sourceEventId(),
                event.sourceDocumentId(), event.sourceDocumentVersion(), event.eventType(), event.legalEntityId(),
                event.bookId(), incomingWatermark, event.payloadDigest(), rawPayloadJson, res.draftJournalId(),
                event.reversesSourceEventId(), res.status());

        if (incomingWatermark > latestWatermark) {
            store.updateWatermark(event.tenantId(), event.sourceSystem(), event.bookId(), incomingWatermark);
        }
    }

    // --- 4. Recurring Schedules ---
    public LocalDate computeMonthEndDate(int year, int month, String leapYearFebRule) {
        return ScheduleRuleEngine.calculateMonthEndDate(year, month, leapYearFebRule);
    }

    public LocalDate calculateScheduleExecutionDate(ScheduleRuleEngine.ScheduleDefinition sched, int year, int month) {
        return ScheduleRuleEngine.calculateExecutionDate(sched, year, month);
    }

    public List<ScheduleRuleEngine.MissedWindow> detectMissedScheduleWindows(
            List<ScheduleRuleEngine.PeriodInfo> sortedPeriods, Set<UUID> executedPeriods, int currentPeriod, String leapYearRule
    ) {
        return ScheduleRuleEngine.detectMissedWindows(sortedPeriods, executedPeriods, currentPeriod, leapYearRule);
    }

    @Transactional
    public void executeScheduleTask(
            UUID tenantId, UUID bookId, UUID executionId, UUID scheduleId, UUID fiscalYearId, UUID periodId,
            LocalDate scheduledDate, String executionType, Set<String> executedSchedulePeriodKeys
    ) throws SQLException {
        ScheduleRuleEngine.validateDeduplication(scheduleId, periodId, executedSchedulePeriodKeys);
        store.recordScheduleExecution(tenantId, bookId, executionId, scheduleId, fiscalYearId, periodId, scheduledDate, executionType, "SUCCESS", "{\"status\":\"COMPLETED\"}");
    }

    // --- 5. AI Inquiries, Anomaly Detection & ActionPlan ---
    public FinanceAiEngine.OcrSuggestion getOcrAccountSuggestion(String ocrText) {
        return FinanceAiEngine.suggestAccountFromOcr(ocrText);
    }

    public List<FinanceAiEngine.AnomalyAlert> detectAnomalies(
            UUID journalId, LocalDate date, BigDecimal debitSum, BigDecimal creditSum,
            List<BigDecimal> amounts, BigDecimal mean, BigDecimal stdDev
    ) {
        return FinanceAiEngine.detectAnomalies(journalId, date, debitSum, creditSum, amounts, List.of(), mean, stdDev);
    }

    public List<FinanceAiEngine.AnomalyAlert> detectAnomalies(
            UUID journalId, LocalDate date, BigDecimal debitSum, BigDecimal creditSum,
            List<BigDecimal> amounts, List<String> invoiceRefs, BigDecimal mean, BigDecimal stdDev
    ) {
        return FinanceAiEngine.detectAnomalies(journalId, date, debitSum, creditSum, amounts, invoiceRefs, mean, stdDev);
    }

    public FinanceAiEngine.AnomalyAlert closeAnomalyAlertFalsePositive(
            FinanceAiEngine.AnomalyAlert alert, UUID closedBy, String reason, String evidenceDigest
    ) {
        return FinanceAiEngine.closeAnomalyAsFalsePositive(alert, closedBy, reason, evidenceDigest);
    }

    public FinanceAiEngine.InquiryAnswer explainInquiry(String query) {
        return FinanceAiEngine.explainInquiry(query);
    }

    public FinanceAiEngine.ActionPlan approveActionPlan(
            FinanceAiEngine.ActionPlan plan, UUID approverId, long expectedVersion, String expectedPayloadDigest, String expectedDryRunDigest
    ) {
        return FinanceAiEngine.approveActionPlan(plan, approverId, expectedVersion, expectedPayloadDigest, expectedDryRunDigest);
    }

    public void executeActionPlan(FinanceAiEngine.ActionPlan plan, String token) {
        FinanceAiEngine.validateActionPlanExecution(plan, token);
    }

    // --- 6. Offline Leases ---
    @Transactional
    public OfflineLeaseEngine.OfflineLease issueOfflineLease(
            UUID tenantId, UUID actorId, String deviceFingerprint, int requestedSeconds
    ) throws SQLException {
        OfflineLeaseEngine.OfflineLease lease = OfflineLeaseEngine.issueLease(tenantId, actorId, deviceFingerprint, requestedSeconds);
        store.insertOfflineLease(lease);
        return lease;
    }

    @Transactional
    public OfflineLeaseEngine.DraftEnvelope syncOfflineDraft(
            OfflineLeaseEngine.OfflineLease lease, String ciphertext, String iv, String authTag, String signature, String payloadSha256
    ) throws SQLException {
        OfflineLeaseEngine.DraftEnvelope env = OfflineLeaseEngine.storeEnvelope(lease, ciphertext, iv, authTag, signature, payloadSha256);
        store.insertDraftEnvelope(env);
        return env;
    }

    public Optional<OfflineLeaseEngine.OfflineLease> findOfflineLease(UUID tenantId, UUID leaseId) throws SQLException {
        return store.findOfflineLease(tenantId, leaseId);
    }
}
