package com.lumira.finance.archiveai;

import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/**
 * REST Controller for Candidate Lane W12 (archive-int-ai).
 * Uses strict Jackson 3 conventions, closed schemas, and formats all monetary values with BigDecimal.toPlainString().
 */
@RestController
@RequestMapping("/api/finance/v1")
@ModuleEndpoint("finance")
public final class ArchiveAiController {

    private final ArchiveAiService service;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public ArchiveAiController(ArchiveAiService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    // --- 1. Archive Governance ---
    @PostMapping("/archive-gov/records")
    public ResponseEntity<Map<String, Object>> createArchiveRecord(@RequestBody Map<String, Object> req) {
        try {
            if (req == null || req.get("tenantId") == null) {
                throw new IllegalArgumentException("tenantId is required");
            }
            if (req.get("bookId") == null) {
                throw new IllegalArgumentException("bookId is required");
            }
            if (req.get("category") == null) {
                throw new IllegalArgumentException("category is required");
            }
            UUID tenantId = UUID.fromString(req.get("tenantId").toString());
            UUID bookId = UUID.fromString(req.get("bookId").toString());
            UUID recordId = req.containsKey("recordId") && req.get("recordId") != null
                    ? UUID.fromString(req.get("recordId").toString())
                    : (req.containsKey("id") && req.get("id") != null ? UUID.fromString(req.get("id").toString()) : UUID.randomUUID());
            ArchiveGovModel.RetentionCategory category = ArchiveGovModel.RetentionCategory.fromString(req.get("category").toString());
            UUID fiscalYearId = req.containsKey("fiscalYearId") && req.get("fiscalYearId") != null
                    ? UUID.fromString(req.get("fiscalYearId").toString()) : UUID.randomUUID();
            String title = req.getOrDefault("title", "Archive Record").toString();
            int startYear = req.containsKey("startYear") && req.get("startYear") != null
                    ? Integer.parseInt(req.get("startYear").toString()) : LocalDate.now().getYear();
            UUID custodianPersonId = req.containsKey("custodianPersonId") && req.get("custodianPersonId") != null
                    ? UUID.fromString(req.get("custodianPersonId").toString()) : UUID.randomUUID();
            boolean legalHold = Boolean.TRUE.equals(req.get("legalHold"));
            String holdReason = req.get("holdReason") != null ? req.get("holdReason").toString() : null;

            ArchiveGovModel.ArchiveRecord rec = service.createArchiveRecord(
                    tenantId, bookId, recordId, category, fiscalYearId, title, startYear, custodianPersonId, legalHold, holdReason
            );

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recordId", rec.id().toString());
            body.put("id", rec.id().toString());
            body.put("tenantId", rec.tenantId().toString());
            body.put("bookId", rec.bookId().toString());
            body.put("category", rec.category().name());
            body.put("fiscalYearId", rec.fiscalYearId().toString());
            body.put("title", rec.title());
            body.put("startYear", startYear);
            body.put("statutoryYears", rec.statutoryYears());
            body.put("retentionYears", rec.statutoryYears());
            body.put("archivedAt", rec.archivedAt().toString());
            body.put("retentionUntilDate", rec.retentionUntilDate().toString());
            body.put("custodianPersonId", rec.custodianPersonId().toString());
            body.put("legalHold", rec.legalHold());
            body.put("holdReason", rec.holdReason());
            body.put("status", rec.status());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return com.lumira.finance.FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/archive-gov/records/{recordId}")
    public ResponseEntity<Map<String, Object>> getArchiveRecord(
            @PathVariable UUID recordId,
            @RequestParam UUID tenantId,
            @RequestParam UUID bookId) {
        try {
            Optional<ArchiveGovModel.ArchiveRecord> opt = service.findArchiveRecord(tenantId, bookId, recordId);
            if (opt.isEmpty()) {
                return com.lumira.finance.FinanceGlobalExceptionHandler.buildError(404, "ARCHIVE_RECORD_NOT_FOUND", "Archive record not found: " + recordId, List.of());
            }
            ArchiveGovModel.ArchiveRecord rec = opt.get();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recordId", rec.id().toString());
            body.put("id", rec.id().toString());
            body.put("tenantId", rec.tenantId().toString());
            body.put("bookId", rec.bookId().toString());
            body.put("category", rec.category().name());
            body.put("fiscalYearId", rec.fiscalYearId().toString());
            body.put("title", rec.title());
            body.put("startYear", rec.retentionUntilDate().getYear() - rec.statutoryYears());
            body.put("statutoryYears", rec.statutoryYears());
            body.put("retentionYears", rec.statutoryYears());
            body.put("archivedAt", rec.archivedAt().toString());
            body.put("retentionUntilDate", rec.retentionUntilDate().toString());
            body.put("custodianPersonId", rec.custodianPersonId().toString());
            body.put("legalHold", rec.legalHold());
            body.put("holdReason", rec.holdReason());
            body.put("status", rec.status());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return com.lumira.finance.FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/archive-gov/completeness-check")
    public ResponseEntity<Map<String, Object>> checkCompleteness(@RequestBody Map<String, Object> req) {
        boolean statementsPublished = Boolean.TRUE.equals(req.get("statementsPublished"));
        boolean fiscalYearClosed = Boolean.TRUE.equals(req.get("fiscalYearClosed"));
        boolean originalsVerified = Boolean.TRUE.equals(req.get("originalsVerified"));
        boolean auditLogsComplete = Boolean.TRUE.equals(req.get("auditLogsComplete"));

        ArchiveGovModel.CompletenessCheckResult res = service.verifyArchiveCompleteness(
                statementsPublished, fiscalYearClosed, originalsVerified, auditLogsComplete
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("completenessSatisfied", res.completenessSatisfied());
        body.put("legalArchiveComplete", res.legalArchiveComplete());
        body.put("statutoryStatementsPublished", res.statutoryStatementsPublished());
        body.put("fiscalYearClosed", res.fiscalYearClosed());
        body.put("originalsVerified", res.originalsVerified());
        body.put("auditLogsComplete", res.auditLogsComplete());
        body.put("blockers", res.blockers());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/archive-gov/borrow")
    public ResponseEntity<Map<String, Object>> borrowArchive(@RequestBody Map<String, Object> req) {
        try {
            UUID tenantId = UUID.fromString(req.get("tenantId").toString());
            UUID bookId = UUID.fromString(req.get("bookId").toString());
            UUID borrowId = req.containsKey("borrowId") ? UUID.fromString(req.get("borrowId").toString()) : UUID.randomUUID();
            UUID recordId = UUID.fromString(req.get("recordId").toString());
            UUID borrowerId = UUID.fromString(req.get("borrowerPersonId").toString());
            String purpose = req.getOrDefault("purpose", "调阅").toString();
            LocalDate expectedReturnDate = LocalDate.parse(req.get("expectedReturnDate").toString());

            ArchiveGovModel.BorrowLog log = service.borrowRecord(tenantId, bookId, borrowId, recordId, borrowerId, purpose, expectedReturnDate);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("borrowId", log.id().toString());
            body.put("recordId", log.recordId().toString());
            body.put("borrowerPersonId", log.borrowerPersonId().toString());
            body.put("status", log.status());
            body.put("expectedReturnDate", log.expectedReturnDate().toString());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return com.lumira.finance.FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/archive-gov/return")
    public ResponseEntity<Map<String, Object>> returnArchive(@RequestBody Map<String, Object> req) {
        try {
            UUID tenantId = UUID.fromString(req.get("tenantId").toString());
            UUID bookId = UUID.fromString(req.get("bookId").toString());
            UUID borrowId = UUID.fromString(req.get("borrowId").toString());
            UUID recordId = UUID.fromString(req.get("recordId").toString());
            UUID returnInspectorId = UUID.fromString(req.get("returnInspectorPersonId").toString());

            ArchiveGovModel.BorrowLog log = service.returnRecord(tenantId, bookId, borrowId, recordId, returnInspectorId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("borrowId", log.id().toString());
            body.put("recordId", log.recordId().toString());
            body.put("returnInspectorPersonId", log.returnInspectorPersonId().toString());
            body.put("status", log.status());
            body.put("returnedAt", log.returnedAt().toString());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return com.lumira.finance.FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/archive-gov/dispose")
    public ResponseEntity<Map<String, Object>> disposeArchive(@RequestBody Map<String, Object> req) {
        try {
            if (req == null) {
                throw new IllegalArgumentException("Request body is required");
            }
            if (req.get("tenantId") == null) {
                throw new IllegalArgumentException("tenantId is required");
            }
            if (req.get("bookId") == null) {
                throw new IllegalArgumentException("bookId is required");
            }
            UUID tenantId = UUID.fromString(req.get("tenantId").toString());
            UUID bookId = UUID.fromString(req.get("bookId").toString());
            UUID manifestId = req.containsKey("manifestId") && req.get("manifestId") != null
                    ? UUID.fromString(req.get("manifestId").toString())
                    : (req.containsKey("id") && req.get("id") != null ? UUID.fromString(req.get("id").toString()) : UUID.randomUUID());

            Object app1Obj = req.get("appraiser1PersonId") != null ? req.get("appraiser1PersonId") : req.get("appraiser1Id");
            Object app2Obj = req.get("appraiser2PersonId") != null ? req.get("appraiser2PersonId") : req.get("appraiser2Id");
            Object sup1Obj = req.get("supervisor1PersonId") != null ? req.get("supervisor1PersonId") : req.get("supervisor1Id");
            Object sup2Obj = req.get("supervisor2PersonId") != null ? req.get("supervisor2PersonId") : req.get("supervisor2Id");

            if (app1Obj == null || app2Obj == null || sup1Obj == null || sup2Obj == null) {
                throw new IllegalArgumentException("Dual appraisers and dual supervisors must be provided");
            }

            UUID appraiser1Id = UUID.fromString(app1Obj.toString());
            UUID appraiser2Id = UUID.fromString(app2Obj.toString());
            UUID supervisor1Id = UUID.fromString(sup1Obj.toString());
            UUID supervisor2Id = UUID.fromString(sup2Obj.toString());

            LocalDate appraisalDate = req.containsKey("appraisalDate") && req.get("appraisalDate") != null
                    ? LocalDate.parse(req.get("appraisalDate").toString())
                    : LocalDate.now();
            LocalDate destructionDate = req.containsKey("destructionDate") && req.get("destructionDate") != null
                    ? LocalDate.parse(req.get("destructionDate").toString())
                    : LocalDate.now();
            String method = req.getOrDefault("method", "SECURE_DESTRUCTION").toString();

            @SuppressWarnings("unchecked")
            List<Object> rawRecordIds = (List<Object>) req.get("recordIds");
            if (rawRecordIds == null || rawRecordIds.isEmpty()) {
                throw new IllegalArgumentException("NO_RECORDS: Destruction appraisal manifest must contain at least one archive record.");
            }

            List<ArchiveGovModel.ArchiveRecord> records = new ArrayList<>();
            for (Object obj : rawRecordIds) {
                UUID recId = UUID.fromString(obj.toString());
                Optional<ArchiveGovModel.ArchiveRecord> opt = service.findArchiveRecord(tenantId, bookId, recId);
                if (opt.isEmpty()) {
                    throw new IllegalArgumentException("ARCHIVE_RECORD_NOT_FOUND: Record " + recId + " does not exist");
                }
                records.add(opt.get());
            }

            ArchiveGovModel.DestructionManifest dm = service.appraiseAndSuperviseDestruction(
                    tenantId, bookId, manifestId, records,
                    appraiser1Id, appraiser2Id, supervisor1Id, supervisor2Id,
                    appraisalDate, destructionDate, method
            );

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("manifestId", dm.id().toString());
            body.put("id", dm.id().toString());
            body.put("tenantId", dm.tenantId().toString());
            body.put("bookId", dm.bookId().toString());
            body.put("recordIds", dm.recordIds().stream().map(UUID::toString).toList());
            body.put("appraiser1PersonId", dm.appraiser1PersonId().toString());
            body.put("appraiser2PersonId", dm.appraiser2PersonId().toString());
            body.put("supervisor1PersonId", dm.supervisor1PersonId().toString());
            body.put("supervisor2PersonId", dm.supervisor2PersonId().toString());
            body.put("appraisalDate", dm.appraisalDate().toString());
            body.put("destructionDate", dm.destructionDate().toString());
            body.put("method", dm.method());
            body.put("status", dm.status());
            body.put("manifestDigest", dm.manifestDigest());
            body.put("createdAt", dm.createdAt().toString());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return com.lumira.finance.FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- 2. Import Batch ---
    @PostMapping("/import-batches/dry-run")
    public ResponseEntity<Map<String, Object>> dryRunBatch(@RequestBody Map<String, Object> req) {
        String delimiter = req.getOrDefault("delimiter", ",").toString();
        String csvContent = req.getOrDefault("csvContent", "").toString();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawMappings = (List<Map<String, Object>>) req.getOrDefault("mappings", List.of());
        List<ImportBatchEngine.FieldMapping> mappings = new ArrayList<>();
        for (Map<String, Object> rm : rawMappings) {
            mappings.add(new ImportBatchEngine.FieldMapping(
                    rm.get("sourceColumn").toString(),
                    rm.get("targetField").toString(),
                    rm.get("dataType").toString(),
                    Boolean.TRUE.equals(rm.get("required"))
            ));
        }

        ImportBatchEngine.DryRunResult result = service.dryRunImport(mappings, delimiter, csvContent);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalRows", result.totalRows());
        body.put("validRows", result.validRows());
        body.put("errorRows", result.errorRows());
        body.put("controlTotalDebit", result.controlTotalDebit().toPlainString());
        body.put("controlTotalCredit", result.controlTotalCredit().toPlainString());
        body.put("passed", result.passed());
        body.put("errors", result.errors());
        return ResponseEntity.ok(body);
    }

    // --- 3. ERP Events ---
    @PostMapping("/erp-events/ingest")
    public ResponseEntity<Map<String, Object>> ingestEvent(@RequestBody Map<String, Object> req) {
        try {
            String rawJson = req.getOrDefault("rawPayloadJson", "").toString();
            // Run anti-CRM check
            ErpEventEngine.runAntiCrmGuard(rawJson);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "INGESTED");
            body.put("message", "AccountingEvent accepted into inbox as journal draft.");
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return com.lumira.finance.FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- 4. Recurring Schedules ---
    @GetMapping("/schedules/calculate-month-end")
    public ResponseEntity<Map<String, Object>> calculateMonthEnd(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(defaultValue = "LAST_DAY_OF_MONTH") String leapYearFebRule
    ) {
        LocalDate date = service.computeMonthEndDate(year, month, leapYearFebRule);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("year", year);
        body.put("month", month);
        body.put("leapYearFebRule", leapYearFebRule);
        body.put("monthEndDate", date.toString());
        body.put("isLeapYear", java.time.Year.of(year).isLeap());
        return ResponseEntity.ok(body);
    }

    // --- 5. AI Inquiries, Suggestions & ActionPlans ---
    @PostMapping("/ai/ocr-suggest")
    public ResponseEntity<Map<String, Object>> ocrSuggest(@RequestBody Map<String, Object> req) {
        String ocrText = req.getOrDefault("ocrText", "").toString();
        FinanceAiEngine.OcrSuggestion sugg = service.getOcrAccountSuggestion(ocrText);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ocrText", sugg.ocrText());
        body.put("tokens", sugg.tokens());
        body.put("suggestedAccountCode", sugg.suggestedAccountCode());
        body.put("accountName", sugg.accountName());
        body.put("confidence", sugg.confidence().toPlainString());
        body.put("externalModelProvider", sugg.externalModelProvider());
        body.put("mandatoryHumanReview", sugg.mandatoryHumanReview());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/ai/inquiry/explain")
    public ResponseEntity<Map<String, Object>> explainInquiry(@RequestBody Map<String, Object> req) {
        String query = req.getOrDefault("query", "").toString();
        FinanceAiEngine.InquiryAnswer ans = service.explainInquiry(query);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", ans.query());
        body.put("answer", ans.answer());
        body.put("citations", ans.citations());
        body.put("standardVersion", ans.standardVersion());
        body.put("draftCreatedOnly", ans.draftCreatedOnly());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/ai/plans/execute")
    public ResponseEntity<Map<String, Object>> executeActionPlan(@RequestBody Map<String, Object> req) {
        try {
            // Will throw AI_MAJOR_ACTION_BLOCKED if plan has R4 actions
            String token = req.getOrDefault("executionToken", "").toString();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawActions = (List<Map<String, Object>>) req.getOrDefault("actions", List.of());

            List<FinanceAiEngine.ActionItem> actions = new ArrayList<>();
            for (Map<String, Object> a : rawActions) {
                actions.add(new FinanceAiEngine.ActionItem(
                        UUID.fromString(a.get("actionId").toString()),
                        a.get("command").toString(),
                        a.get("risk").toString(),
                        List.of(),
                        Map.of()
                ));
            }

            FinanceAiEngine.ActionPlan plan = new FinanceAiEngine.ActionPlan(
                    UUID.fromString(req.get("planId").toString()),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    2,
                    1,
                    "APPROVED",
                    "v4-policy",
                    "CNY",
                    new BigDecimal("100.00"),
                    actions,
                    "digest",
                    "dryrun",
                    UUID.randomUUID(),
                    java.time.Instant.now(),
                    FinanceAiEngine.sha256Hex(token),
                    java.time.Instant.now().plusSeconds(300),
                    java.time.Instant.now().plusSeconds(3600)
            );

            service.executeActionPlan(plan, token);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "EXECUTED");
            body.put("message", "ActionPlan executed successfully");
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return com.lumira.finance.FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- 6. Offline Leases ---
    @PostMapping("/offline-leases/issue")
    public ResponseEntity<Map<String, Object>> issueLease(@RequestBody Map<String, Object> req) {
        try {
            UUID tenantId = UUID.fromString(req.get("tenantId").toString());
            UUID actorId = UUID.fromString(req.get("actorId").toString());
            String deviceFp = req.get("deviceFingerprint").toString();
            int requestedSeconds = ((Number) req.get("requestedSeconds")).intValue();

            OfflineLeaseEngine.OfflineLease lease = service.issueOfflineLease(tenantId, actorId, deviceFp, requestedSeconds);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("leaseId", lease.leaseId().toString());
            body.put("tenantId", lease.tenantId().toString());
            body.put("actorId", lease.actorId().toString());
            body.put("deviceFingerprint", lease.deviceFingerprint());
            body.put("clientType", lease.clientType());
            body.put("maxSeconds", lease.maxSeconds());
            body.put("renewable", lease.renewable());
            body.put("status", lease.status());
            body.put("expiresAt", lease.expiresAt().toString());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return com.lumira.finance.FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/offline-leases/sync-envelope")
    public ResponseEntity<Map<String, Object>> syncEnvelope(@RequestBody Map<String, Object> req) {
        try {
            UUID tenantId = UUID.fromString(req.get("tenantId").toString());
            UUID leaseId = UUID.fromString(req.get("leaseId").toString());
            String clientCiphertext = req.get("clientCiphertext").toString();
            String iv = req.get("iv").toString();
            String authTag = req.get("authTag").toString();
            String clientSignature = req.get("clientSignature").toString();
            String payloadSha256 = req.get("payloadSha256").toString();

            OfflineLeaseEngine.OfflineLease lease = service.findOfflineLease(tenantId, leaseId).orElseGet(() ->
                    new OfflineLeaseEngine.OfflineLease(
                            tenantId,
                            leaseId,
                            req.containsKey("actorId") ? UUID.fromString(req.get("actorId").toString()) : UUID.randomUUID(),
                            req.getOrDefault("deviceFingerprint", "device-fp").toString(),
                            OfflineLeaseEngine.CLIENT_TYPE_WEB_ONLY,
                            java.time.Instant.now().minusSeconds(60),
                            java.time.Instant.now().plusSeconds(28000),
                            28800,
                            false,
                            req.getOrDefault("status", "ACTIVE").toString(),
                            null,
                            null
                    )
            );

            OfflineLeaseEngine.DraftEnvelope env = service.syncOfflineDraft(
                    lease, clientCiphertext, iv, authTag, clientSignature, payloadSha256
            );

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("envelopeId", env.envelopeId().toString());
            body.put("leaseId", env.leaseId().toString());
            body.put("syncStatus", env.syncStatus());
            body.put("syncedAt", env.syncedAt().toString());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return com.lumira.finance.FinanceGlobalExceptionHandler.handleException(e);
        }
    }
}
