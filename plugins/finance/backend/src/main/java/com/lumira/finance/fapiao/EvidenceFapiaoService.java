package com.lumira.finance.fapiao;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Service implementing business logic for EV01, EV02, EV03, EV04, FP01, FP02, FP03.
 */
@Service
public class EvidenceFapiaoService {
    private final EvidenceFapiaoJdbcStore store;

    public EvidenceFapiaoService(EvidenceFapiaoJdbcStore store) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
    }

    // =========================================================================
    // EV01: Evidence Completeness State Machine & Original Classification
    // =========================================================================

    public EvidenceFapiaoModels.CompletenessRecord registerCompleteness(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID sourceOriginalId,
            EvidenceClassification classification,
            String paperStorageLocation,
            Map<String, Object> metadataIndex,
            Map<String, Object> createdBy
    ) throws SQLException {
        boolean isScreenshot = metadataIndex != null && Boolean.TRUE.equals(metadataIndex.get("isScreenshotOnly"));
        List<String> missingReasons = new ArrayList<>();
        boolean isComplete;
        EvidenceCompletenessState state;

        if (isScreenshot || classification == EvidenceClassification.OTHER_PAPER_SCAN) {
            isComplete = false;
            state = EvidenceCompletenessState.PENDING_COMPLETION;
            if (isScreenshot) missingReasons.add("SCREENSHOT_ONLY_MISSING_ORIGINAL_XML_OR_OFD");
            if (classification == EvidenceClassification.OTHER_PAPER_SCAN && (paperStorageLocation == null || paperStorageLocation.isBlank())) {
                missingReasons.add("MISSING_PHYSICAL_PAPER_STORAGE_LOCATION");
            }
        } else {
            isComplete = true;
            state = EvidenceCompletenessState.USABLE;
        }

        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        EvidenceFapiaoModels.CompletenessRecord record = new EvidenceFapiaoModels.CompletenessRecord(
                id, tenantId, legalEntityId, sourceOriginalId, classification, state, isComplete,
                missingReasons, paperStorageLocation, metadataIndex != null ? metadataIndex : Collections.emptyMap(),
                1L, null, createdBy != null ? createdBy : Collections.emptyMap(), now, now
        );
        store.insertCompleteness(c, record);
        return record;
    }

    public EvidenceFapiaoModels.CompletenessRecord replaceEvidence(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID previousRecordId,
            UUID newSourceOriginalId,
            EvidenceClassification classification,
            String paperStorageLocation,
            Map<String, Object> metadataIndex,
            Map<String, Object> createdBy
    ) throws SQLException {
        EvidenceFapiaoModels.CompletenessRecord prev = store.getCompleteness(c, tenantId, legalEntityId, previousRecordId)
                .orElseThrow(() -> new IllegalArgumentException("Previous evidence record not found: " + previousRecordId));

        // Mark previous as REPLACED
        String updateSql = "UPDATE plugin_finance_evidence_completeness SET completeness_state = 'REPLACED', updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (var ps = c.prepareStatement(updateSql)) {
            ps.setObject(1, prev.id());
            ps.executeUpdate();
        }

        UUID newId = UUID.randomUUID();
        Instant now = Instant.now();
        EvidenceFapiaoModels.CompletenessRecord newRecord = new EvidenceFapiaoModels.CompletenessRecord(
                newId, tenantId, legalEntityId, newSourceOriginalId, classification,
                EvidenceCompletenessState.USABLE, true, Collections.emptyList(),
                paperStorageLocation, metadataIndex != null ? metadataIndex : Collections.emptyMap(),
                prev.version() + 1, prev.id(), createdBy != null ? createdBy : Collections.emptyMap(), now, now
        );
        store.insertCompleteness(c, newRecord);
        return newRecord;
    }

    public EvidenceFapiaoModels.ExportIndexRecord generateMetadataExportIndex(
            Connection c, UUID tenantId, UUID legalEntityId, UUID evidenceId
    ) throws SQLException {
        EvidenceFapiaoModels.CompletenessRecord rec = store.getCompleteness(c, tenantId, legalEntityId, evidenceId)
                .orElseThrow(() -> new IllegalArgumentException("Evidence completeness record not found: " + evidenceId));

        Map<String, Object> meta = rec.metadataIndex() != null ? rec.metadataIndex() : Collections.emptyMap();
        String filename = meta.containsKey("originalFilename") ? String.valueOf(meta.get("originalFilename"))
                : ("evidence_" + rec.id().toString().substring(0, 8) + (rec.classification() == EvidenceClassification.EINVOICE_XML ? ".xml" : ".dat"));
        String contentType = meta.containsKey("contentType") ? String.valueOf(meta.get("contentType"))
                : (rec.classification() == EvidenceClassification.EINVOICE_XML ? "application/xml"
                : rec.classification() == EvidenceClassification.EINVOICE_PDF ? "application/pdf"
                : "application/octet-stream");
        Long byteSize = meta.containsKey("byteSize") ? Long.parseLong(String.valueOf(meta.get("byteSize"))) : 0L;
        String sha256 = meta.containsKey("fileHash") ? String.valueOf(meta.get("fileHash"))
                : meta.containsKey("sha256") ? String.valueOf(meta.get("sha256"))
                : "0".repeat(64);

        String catalogIndexCode = "CAT-" + tenantId.toString().substring(0, 8).toUpperCase()
                + "-" + rec.classification().name()
                + "-" + rec.id().toString().substring(0, 8).toUpperCase()
                + "-V" + rec.version();

        return new EvidenceFapiaoModels.ExportIndexRecord(
                rec.id(), catalogIndexCode, filename, contentType, byteSize, sha256,
                rec.classification(), rec.paperStorageLocation(), rec.createdAt(),
                rec.completenessState(), rec.isComplete(), rec.version()
        );
    }

    // =========================================================================
    // EV02: Deterministic Structured Parsing & OCR Cross-Check
    // =========================================================================

    public record ParseAndVerifyResult(
            FapiaoStructuredData structuredData,
            EvidenceFapiaoModels.VerificationRecord verificationRecord
    ) {}

    public ParseAndVerifyResult parseAndVerifyXml(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID evidenceId,
            String xmlContent,
            String verifier
    ) throws SQLException {
        FapiaoStructuredData data = FapiaoXmlParser.parseXml(xmlContent);
        if (!data.isMathValid()) {
            throw new IllegalArgumentException("Invoice mathematical check failed: untaxed + tax != total");
        }

        UUID verifId = UUID.randomUUID();
        Instant now = Instant.now();
        EvidenceFapiaoModels.VerificationRecord vRec = new EvidenceFapiaoModels.VerificationRecord(
                verifId, tenantId, legalEntityId, evidenceId,
                "LOCAL_STRUCTURAL_AND_DIGEST_CHECK",
                data.payloadDigest(),
                null,
                "PARSED",
                Collections.emptyMap(),
                "NOT_VERIFIED", // Never claim live government verification
                verifier,
                now,
                "Structural XML parsing and decimal math check passed"
        );
        store.insertVerification(c, vRec);
        return new ParseAndVerifyResult(data, vRec);
    }

    public FapiaoOcrComparator.ComparisonResult crossCheckOcr(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID evidenceId,
            FapiaoStructuredData structuredData,
            FapiaoOcrComparator.OcrResult ocrResult,
            String verifier
    ) throws SQLException {
        FapiaoOcrComparator.ComparisonResult cmp = FapiaoOcrComparator.compare(structuredData, ocrResult);

        UUID verifId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> discMap = new LinkedHashMap<>();
        cmp.discrepancies().forEach((k, v) -> discMap.put(k, Map.of(
                "structured", v.structuredValue(),
                "ocr", v.ocrValue(),
                "note", v.deltaDescription()
        )));

        EvidenceFapiaoModels.VerificationRecord vRec = new EvidenceFapiaoModels.VerificationRecord(
                verifId, tenantId, legalEntityId, evidenceId,
                cmp.verificationMethod(),
                cmp.structuralDigest(),
                cmp.ocrDigest(),
                cmp.status(),
                discMap,
                cmp.taxBureauVerification(),
                verifier,
                now,
                cmp.isMatch() ? "OCR fields match structured fields exactly" : "OCR discrepancies detected; enqueued to manual review queue"
        );
        store.insertVerification(c, vRec);
        return cmp;
    }

    // =========================================================================
    // EV03: Extended Quota Buckets
    // =========================================================================

    public EvidenceFapiaoModels.ExtendedQuotaRecord initExtendedQuota(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID evidenceId,
            ExtendedQuotaBucket bucketType,
            BigDecimal capacity
    ) throws SQLException {
        BigDecimal cap = capacity.setScale(2, RoundingMode.HALF_UP);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        EvidenceFapiaoModels.ExtendedQuotaRecord quota = new EvidenceFapiaoModels.ExtendedQuotaRecord(
                id, tenantId, legalEntityId, evidenceId, bucketType, "CNY",
                cap, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, cap, now, now
        );
        store.insertQuota(c, quota);
        return quota;
    }

    public EvidenceFapiaoModels.ExtendedQuotaRecord reserveQuota(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID evidenceId,
            ExtendedQuotaBucket bucketType,
            BigDecimal amount,
            UUID businessChainId,
            Map<String, Object> operator
    ) throws SQLException {
        BigDecimal req = amount.setScale(2, RoundingMode.HALF_UP);
        EvidenceFapiaoModels.ExtendedQuotaRecord q = store.getQuotaForUpdate(c, tenantId, legalEntityId, evidenceId, bucketType)
                .orElseThrow(() -> new IllegalArgumentException("Extended quota not found for bucket: " + bucketType));

        BigDecimal available = q.availableAmount();
        if (available.compareTo(req) < 0) {
            throw new IllegalStateException("Quota bucket " + bucketType + " capacity exceeded: requested " + req.toPlainString() + " > available " + available.toPlainString());
        }

        BigDecimal newReserved = q.reservedAmount().add(req);
        store.updateQuotaAmounts(c, q.id(), newReserved, q.committedAmount(), q.releasedAmount());

        store.insertQuotaLog(c, new EvidenceFapiaoModels.ExtendedQuotaLogRecord(
                UUID.randomUUID(), tenantId, legalEntityId, q.id(), "RESERVE", req, businessChainId, operator, Instant.now()
        ));

        return new EvidenceFapiaoModels.ExtendedQuotaRecord(
                q.id(), q.tenantId(), q.legalEntityId(), q.evidenceId(), q.bucketType(), q.currency(),
                q.capacity(), newReserved, q.committedAmount(), q.releasedAmount(),
                q.capacity().subtract(newReserved).subtract(q.committedAmount()), q.createdAt(), Instant.now()
        );
    }

    public EvidenceFapiaoModels.ExtendedQuotaRecord commitQuota(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID evidenceId,
            ExtendedQuotaBucket bucketType,
            BigDecimal amount,
            UUID businessChainId,
            Map<String, Object> operator
    ) throws SQLException {
        BigDecimal req = amount.setScale(2, RoundingMode.HALF_UP);
        EvidenceFapiaoModels.ExtendedQuotaRecord q = store.getQuotaForUpdate(c, tenantId, legalEntityId, evidenceId, bucketType)
                .orElseThrow(() -> new IllegalArgumentException("Extended quota not found for bucket: " + bucketType));

        if (q.reservedAmount().compareTo(req) < 0) {
            throw new IllegalStateException("Cannot commit unreserved amount: requested " + req.toPlainString() + " > reserved " + q.reservedAmount().toPlainString());
        }

        BigDecimal newReserved = q.reservedAmount().subtract(req);
        BigDecimal newCommitted = q.committedAmount().add(req);
        store.updateQuotaAmounts(c, q.id(), newReserved, newCommitted, q.releasedAmount());

        store.insertQuotaLog(c, new EvidenceFapiaoModels.ExtendedQuotaLogRecord(
                UUID.randomUUID(), tenantId, legalEntityId, q.id(), "COMMIT", req, businessChainId, operator, Instant.now()
        ));

        return new EvidenceFapiaoModels.ExtendedQuotaRecord(
                q.id(), q.tenantId(), q.legalEntityId(), q.evidenceId(), q.bucketType(), q.currency(),
                q.capacity(), newReserved, newCommitted, q.releasedAmount(),
                q.capacity().subtract(newReserved).subtract(newCommitted), q.createdAt(), Instant.now()
        );
    }

    public EvidenceFapiaoModels.ExtendedQuotaRecord releaseQuota(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID evidenceId,
            ExtendedQuotaBucket bucketType,
            BigDecimal amount,
            UUID businessChainId,
            Map<String, Object> operator
    ) throws SQLException {
        BigDecimal req = amount.setScale(2, RoundingMode.HALF_UP);
        EvidenceFapiaoModels.ExtendedQuotaRecord q = store.getQuotaForUpdate(c, tenantId, legalEntityId, evidenceId, bucketType)
                .orElseThrow(() -> new IllegalArgumentException("Extended quota not found for bucket: " + bucketType));

        BigDecimal toRelease = req.min(q.reservedAmount());
        BigDecimal newReserved = q.reservedAmount().subtract(toRelease);
        BigDecimal newReleased = q.releasedAmount().add(toRelease);
        store.updateQuotaAmounts(c, q.id(), newReserved, q.committedAmount(), newReleased);

        store.insertQuotaLog(c, new EvidenceFapiaoModels.ExtendedQuotaLogRecord(
                UUID.randomUUID(), tenantId, legalEntityId, q.id(), "RELEASE", toRelease, businessChainId, operator, Instant.now()
        ));

        return new EvidenceFapiaoModels.ExtendedQuotaRecord(
                q.id(), q.tenantId(), q.legalEntityId(), q.evidenceId(), q.bucketType(), q.currency(),
                q.capacity(), newReserved, q.committedAmount(), newReleased,
                q.capacity().subtract(newReserved).subtract(q.committedAmount()), q.createdAt(), Instant.now()
        );
    }

    // =========================================================================
    // EV04: Business Evidence Graph & Exception Tracker
    // =========================================================================

    public EvidenceFapiaoModels.GraphNodeRecord addGraphNode(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            GraphNodeType nodeType,
            String businessRefId,
            String nodeLabel,
            Map<String, Object> metadata
    ) throws SQLException {
        EvidenceFapiaoModels.GraphNodeRecord node = new EvidenceFapiaoModels.GraphNodeRecord(
                UUID.randomUUID(), tenantId, legalEntityId, nodeType, businessRefId, nodeLabel,
                metadata != null ? metadata : Collections.emptyMap(), Instant.now()
        );
        store.insertGraphNode(c, node);
        return node;
    }

    public EvidenceFapiaoModels.GraphEdgeRecord addGraphEdge(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID sourceNodeId,
            UUID targetNodeId,
            GraphEdgeType relationshipType,
            BigDecimal amount,
            Map<String, Object> metadata
    ) throws SQLException {
        if (store.isReachable(c, tenantId, legalEntityId, targetNodeId, sourceNodeId)) {
            throw new IllegalStateException("Cycle detected in business evidence graph: cannot add edge from " + sourceNodeId + " to " + targetNodeId + " as it would create a directed cycle");
        }
        EvidenceFapiaoModels.GraphEdgeRecord edge = new EvidenceFapiaoModels.GraphEdgeRecord(
                UUID.randomUUID(), tenantId, legalEntityId, sourceNodeId, targetNodeId,
                relationshipType, amount, metadata != null ? metadata : Collections.emptyMap(), Instant.now()
        );
        store.insertGraphEdge(c, edge);
        return edge;
    }

    public EvidenceFapiaoModels.EvidencePathRecord traceEvidencePath(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID startNodeId
    ) throws SQLException {
        store.getGraphNode(c, tenantId, legalEntityId, startNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Evidence graph node not found: " + startNodeId));
        return store.traceEvidencePath(c, tenantId, legalEntityId, startNodeId);
    }

    public EvidenceFapiaoModels.EvidenceExceptionRecord registerException(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID businessChainId,
            EvidenceExceptionType exceptionType,
            String description,
            BigDecimal provisionalAmount,
            String responsiblePersonId,
            String responsiblePersonName,
            LocalDate dueDate,
            Map<String, Object> createdBy
    ) throws SQLException {
        Instant now = Instant.now();
        EvidenceFapiaoModels.EvidenceExceptionRecord ex = new EvidenceFapiaoModels.EvidenceExceptionRecord(
                UUID.randomUUID(), tenantId, legalEntityId, businessChainId, exceptionType,
                description, provisionalAmount, responsiblePersonId, responsiblePersonName, dueDate,
                EvidenceExceptionStatus.REGISTERED, null, null, null,
                createdBy != null ? createdBy : Collections.emptyMap(), now, now
        );
        store.insertException(c, ex);
        return ex;
    }

    public void approveException(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID exceptionId,
            String approvalReason,
            Map<String, Object> approvedBy
    ) throws SQLException {
        EvidenceFapiaoModels.EvidenceExceptionRecord ex = store.getException(c, tenantId, legalEntityId, exceptionId)
                .orElseThrow(() -> new IllegalArgumentException("Evidence exception not found: " + exceptionId));

        String approverPersonId = approvedBy != null ? (String) approvedBy.get("personId") : null;
        if (approverPersonId != null && approverPersonId.equals(ex.responsiblePersonId())) {
            throw new IllegalStateException("SoD conflict: approving person cannot be the assigned responsible person");
        }

        store.approveException(c, tenantId, legalEntityId, exceptionId, approvalReason, approvedBy);
    }

    // =========================================================================
    // FP01: Fapiao Pool
    // =========================================================================

    public EvidenceFapiaoModels.FapiaoPoolRecord registerFapiao(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            FapiaoDirection direction,
            String invoiceCode,
            String fapiaoNumber,
            FapiaoType fapiaoType,
            LocalDate issueDate,
            String buyerTaxNo,
            String buyerName,
            String sellerTaxNo,
            String sellerName,
            BigDecimal untaxedAmount,
            BigDecimal taxRate,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            UUID originalDocumentId,
            UUID ledgerJournalId,
            UUID taxWorksheetId
    ) throws SQLException {
        BigDecimal untax = untaxedAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tax = taxAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = totalAmount.setScale(2, RoundingMode.HALF_UP);

        if (untax.add(tax).compareTo(total) != 0) {
            throw new IllegalArgumentException("Invoice amount check failed: untaxed + tax != total");
        }

        // Check duplicate
        Optional<EvidenceFapiaoModels.FapiaoPoolRecord> existing = store.getFapiaoByNumber(c, tenantId, legalEntityId, invoiceCode, fapiaoNumber);
        if (existing.isPresent()) {
            throw new IllegalStateException("Duplicate invoice guard: invoice code " + invoiceCode + " number " + fapiaoNumber + " already registered");
        }

        FapiaoLifecycleStatus status = ledgerJournalId != null ? FapiaoLifecycleStatus.MATCHED : FapiaoLifecycleStatus.UNVERIFIED_LOCAL;
        String reconStatus = ledgerJournalId != null ? "RECONCILED" : "UNRECONCILED";

        Instant now = Instant.now();
        EvidenceFapiaoModels.FapiaoPoolRecord r = new EvidenceFapiaoModels.FapiaoPoolRecord(
                UUID.randomUUID(), tenantId, legalEntityId, direction, invoiceCode != null ? invoiceCode : "",
                fapiaoNumber, fapiaoType, issueDate, buyerTaxNo, buyerName, sellerTaxNo, sellerName,
                untax, taxRate, tax, total, originalDocumentId,
                "NOT_VERIFIED", // Always NOT_VERIFIED
                status, ledgerJournalId, taxWorksheetId, reconStatus, now, now
        );
        store.insertFapiao(c, r);
        return r;
    }

    public void updateFapiaoLifecycleStatus(
            Connection c, UUID tenantId, UUID legalEntityId, UUID fapiaoId, FapiaoLifecycleStatus newStatus
    ) throws SQLException {
        store.updateFapiaoStatus(c, tenantId, legalEntityId, fapiaoId, newStatus);
    }

    public EvidenceFapiaoModels.FapiaoPoolRecord reconcileFapiao(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID fapiaoId,
            UUID ledgerJournalId,
            UUID taxWorksheetId
    ) throws SQLException {
        if (ledgerJournalId == null && taxWorksheetId == null) {
            throw new IllegalArgumentException("Reconciliation requires at least ledgerJournalId or taxWorksheetId");
        }
        store.reconcileFapiao(c, tenantId, legalEntityId, fapiaoId, ledgerJournalId, taxWorksheetId);
        return store.getFapiao(c, tenantId, legalEntityId, fapiaoId)
                .orElseThrow(() -> new IllegalArgumentException("Fapiao not found: " + fapiaoId));
    }

    // =========================================================================
    // FP02: Issuance Application & Manual Issuance Confirmation
    // =========================================================================

    public EvidenceFapiaoModels.IssuanceAppRecord submitIssuanceApp(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            String applicationNumber,
            Map<String, Object> applicant,
            LocalDate applicationDate,
            String contractId,
            String customerId,
            String buyerTaxNo,
            String buyerName,
            String buyerAddressPhone,
            String buyerBankAccount,
            BigDecimal untaxedAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            List<Map<String, Object>> items
    ) throws SQLException {
        BigDecimal untax = untaxedAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tax = taxAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = totalAmount.setScale(2, RoundingMode.HALF_UP);

        if (untax.add(tax).compareTo(total) != 0) {
            throw new IllegalArgumentException("Issuance application math error: untaxed + tax != total");
        }

        Instant now = Instant.now();
        EvidenceFapiaoModels.IssuanceAppRecord r = new EvidenceFapiaoModels.IssuanceAppRecord(
                UUID.randomUUID(), tenantId, legalEntityId, applicationNumber, applicant, applicationDate,
                contractId, customerId, buyerTaxNo, buyerName, buyerAddressPhone, buyerBankAccount,
                BuyerQualificationStatus.PENDING_REVIEW, null, null, null,
                untax, tax, total, items != null ? items : Collections.emptyList(),
                IssuanceAppStatus.SUBMITTED,
                "NOT_VERIFIED", // External tax channel is NOT_VERIFIED
                null, null, null, null, now, now
        );
        store.insertIssuanceApp(c, r);
        return r;
    }

    public void reviewBuyerQualification(
            Connection c, UUID tenantId, UUID legalEntityId, UUID appId,
            BuyerQualificationStatus status, String notes, Map<String, Object> reviewer
    ) throws SQLException {
        store.updateBuyerQualification(c, tenantId, legalEntityId, appId, status, notes, reviewer);
    }

    public EvidenceFapiaoModels.FapiaoPoolRecord confirmManualIssuance(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID appId,
            String issuedFapiaoNumber,
            String invoiceCode,
            LocalDate issueDate,
            String sellerTaxNo,
            String sellerName,
            UUID boundOriginalDocumentId,
            String notes,
            Map<String, Object> confirmedBy
    ) throws SQLException {
        EvidenceFapiaoModels.IssuanceAppRecord app = store.getIssuanceApp(c, tenantId, legalEntityId, appId)
                .orElseThrow(() -> new IllegalArgumentException("Issuance app not found: " + appId));

        if (app.status() == IssuanceAppStatus.ISSUED_MANUAL_CONFIRMED) {
            throw new IllegalStateException("Application is already confirmed and issued: " + appId);
        }
        if (app.status() == IssuanceAppStatus.REJECTED || app.status() == IssuanceAppStatus.CANCELLED) {
            throw new IllegalStateException("Cannot issue fapiao: application is " + app.status());
        }
        if (app.buyerQualificationStatus() != BuyerQualificationStatus.QUALIFIED) {
            throw new IllegalStateException("Cannot issue fapiao: buyer tax qualification is not QUALIFIED (status=" + app.buyerQualificationStatus() + ")");
        }

        // Register issued output fapiao into FP01 pool
        EvidenceFapiaoModels.FapiaoPoolRecord poolRecord = registerFapiao(
                c, tenantId, legalEntityId, FapiaoDirection.OUTPUT, invoiceCode != null ? invoiceCode : "",
                issuedFapiaoNumber, FapiaoType.ELECTRONIC_SPECIAL, issueDate,
                app.buyerTaxNo(), app.buyerName(), sellerTaxNo, sellerName,
                app.untaxedAmount(), BigDecimal.ZERO, app.taxAmount(), app.totalAmount(),
                boundOriginalDocumentId, null, null
        );

        // Update fapiao to MATCHED status and update application
        store.updateFapiaoStatus(c, tenantId, legalEntityId, poolRecord.id(), FapiaoLifecycleStatus.MATCHED);
        store.confirmManualIssuance(c, tenantId, legalEntityId, appId, poolRecord.id(), notes, confirmedBy);
        return store.getFapiao(c, tenantId, legalEntityId, poolRecord.id()).orElse(poolRecord);
    }

    // =========================================================================
    // FP03: Red-Letter Confirmation Notice (72h rule & linkage)
    // =========================================================================

    public EvidenceFapiaoModels.RedNoticeRecord registerRedNotice(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            String redNoticeNumber,
            UUID originalBlueFapiaoId,
            String direction,
            String initiator,
            String reason,
            BigDecimal redUntaxedAmount,
            BigDecimal redTaxAmount,
            BigDecimal redTotalAmount
    ) throws SQLException {
        EvidenceFapiaoModels.FapiaoPoolRecord blue = store.getFapiaoForUpdate(c, tenantId, legalEntityId, originalBlueFapiaoId)
                .orElseThrow(() -> new IllegalArgumentException("Original blue fapiao not found: " + originalBlueFapiaoId));

        if (blue.untaxedAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Cannot register red notice against negative or non-positive invoice");
        }
        if (blue.lifecycleStatus() == FapiaoLifecycleStatus.VOIDED) {
            throw new IllegalStateException("Cannot register red notice against voided invoice");
        }

        BigDecimal rUntax = redUntaxedAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal rTax = redTaxAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal rTotal = redTotalAmount.setScale(2, RoundingMode.HALF_UP);

        if (rUntax.compareTo(BigDecimal.ZERO) >= 0 || rTax.compareTo(BigDecimal.ZERO) > 0 || rTotal.compareTo(BigDecimal.ZERO) >= 0) {
            throw new IllegalArgumentException("Red letter notice amounts must be negative numbers");
        }
        if (rUntax.add(rTax).compareTo(rTotal) != 0) {
            throw new IllegalArgumentException("Red notice math error: untaxed + tax != total");
        }

        // Cumulative limit check against original blue fapiao
        EvidenceFapiaoModels.CumulativeRedNoticeInfo cumInfo = store.getCumulativeRedNotices(
                c, tenantId, legalEntityId, originalBlueFapiaoId, blue.untaxedAmount(), blue.taxAmount(), blue.totalAmount()
        );

        if (rUntax.abs().compareTo(cumInfo.remainingUntaxedAmount()) > 0) {
            throw new IllegalStateException("Red notice amount cannot exceed remaining blue invoice amount: redUntax="
                    + rUntax.abs().toPlainString() + " > remaining " + cumInfo.remainingUntaxedAmount().toPlainString());
        }
        if (rTax.abs().compareTo(cumInfo.remainingTaxAmount()) > 0) {
            throw new IllegalStateException("Red notice tax amount cannot exceed remaining blue invoice tax: redTax="
                    + rTax.abs().toPlainString() + " > remaining " + cumInfo.remainingTaxAmount().toPlainString());
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofHours(72));

        EvidenceFapiaoModels.RedNoticeRecord notice = new EvidenceFapiaoModels.RedNoticeRecord(
                UUID.randomUUID(), tenantId, legalEntityId, redNoticeNumber, originalBlueFapiaoId,
                direction, initiator, reason, rUntax, rTax, rTotal,
                RedNoticeStatus.WAITING_CONFIRMATION, now, expiresAt, null,
                null, BigDecimal.ZERO, BigDecimal.ZERO, null, null, now, now
        );
        store.insertRedNotice(c, notice);
        return notice;
    }

    public void confirmRedNotice(Connection c, UUID tenantId, UUID legalEntityId, UUID noticeId, Instant now) throws SQLException {
        EvidenceFapiaoModels.RedNoticeRecord notice = store.getRedNotice(c, tenantId, legalEntityId, noticeId)
                .orElseThrow(() -> new IllegalArgumentException("Red notice not found: " + noticeId));

        if (notice.status() != RedNoticeStatus.WAITING_CONFIRMATION) {
            throw new IllegalStateException("Cannot confirm red notice in status: " + notice.status() + " (must be WAITING_CONFIRMATION)");
        }

        if (now.isAfter(notice.expiresAt())) {
            store.expireRedNotice(c, tenantId, legalEntityId, noticeId);
            throw new IllegalStateException("Red notice expired past 72-hour window (registered at " + notice.registeredAt() + ", expired at " + notice.expiresAt() + ")");
        }

        store.confirmRedNotice(c, tenantId, legalEntityId, noticeId);
    }

    public void linkCreditFapiao(
            Connection c,
            UUID tenantId,
            UUID legalEntityId,
            UUID redNoticeId,
            UUID creditFapiaoId,
            BigDecimal revenueReversal,
            BigDecimal vatAdjustment,
            UUID revJournalId,
            UUID vatJournalId
    ) throws SQLException {
        EvidenceFapiaoModels.RedNoticeRecord notice = store.getRedNotice(c, tenantId, legalEntityId, redNoticeId)
                .orElseThrow(() -> new IllegalArgumentException("Red notice not found: " + redNoticeId));

        if (notice.status() != RedNoticeStatus.CONFIRMED) {
            throw new IllegalStateException("Cannot link credit fapiao: red notice must be CONFIRMED (status=" + notice.status() + ")");
        }

        EvidenceFapiaoModels.FapiaoPoolRecord creditFapiao = store.getFapiao(c, tenantId, legalEntityId, creditFapiaoId)
                .orElseThrow(() -> new IllegalArgumentException("Credit fapiao not found: " + creditFapiaoId));

        if (creditFapiao.totalAmount().compareTo(BigDecimal.ZERO) >= 0) {
            throw new IllegalArgumentException("Credit fapiao must be a negative red invoice, got total: " + creditFapiao.totalAmount().toPlainString());
        }
        if (creditFapiao.totalAmount().compareTo(notice.redTotalAmount()) != 0) {
            throw new IllegalArgumentException("Credit fapiao total amount " + creditFapiao.totalAmount().toPlainString() + " does not match red notice total amount " + notice.redTotalAmount().toPlainString());
        }

        BigDecimal revRev = revenueReversal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal vatAdj = vatAdjustment.setScale(2, RoundingMode.HALF_UP);
        if (revRev.compareTo(BigDecimal.ZERO) > 0 || vatAdj.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalArgumentException("Accounting revenue reversal and VAT adjustment amounts must be non-positive numbers");
        }
        if (revJournalId == null || vatJournalId == null) {
            throw new IllegalArgumentException("Revenue reversal journal ID and VAT adjustment journal ID are required for linkage");
        }

        store.linkCreditFapiao(c, tenantId, legalEntityId, redNoticeId, creditFapiaoId, revRev, vatAdj, revJournalId, vatJournalId);

        // Check if cumulative issued red invoice total fully offsets the original blue fapiao
        EvidenceFapiaoModels.FapiaoPoolRecord blue = store.getFapiao(c, tenantId, legalEntityId, notice.originalFapiaoId())
                .orElseThrow(() -> new IllegalArgumentException("Original blue fapiao not found: " + notice.originalFapiaoId()));
        BigDecimal totalIssued = store.getCumulativeIssuedRedTotal(c, tenantId, legalEntityId, notice.originalFapiaoId());
        if (totalIssued.compareTo(blue.totalAmount()) >= 0) {
            store.updateFapiaoStatus(c, tenantId, legalEntityId, notice.originalFapiaoId(), FapiaoLifecycleStatus.RED_OFFSET);
        }
    }
}
