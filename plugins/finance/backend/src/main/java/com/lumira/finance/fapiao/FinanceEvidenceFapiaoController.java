package com.lumira.finance.fapiao;

import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * REST Controller for Evidence & Fapiao Lifecycle.
 * Implements strict Jackson 3 closed serialization with BigDecimal.toPlainString().
 */
@RestController
@RequestMapping("/api/finance/v1/evidence-fapiao")
@ModuleEndpoint("finance")
public class FinanceEvidenceFapiaoController {
    private final EvidenceFapiaoService service;
    private final DataSource dataSource;
    private final JsonMapper jsonMapper;

    public FinanceEvidenceFapiaoController(EvidenceFapiaoService service, DataSource dataSource) {
        this.service = service;
        this.dataSource = dataSource;
        this.jsonMapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    private static Map<String, Object> response(int code, Object data) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("data", data);
        return map;
    }

    private static Map<String, Object> error(int code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("error", message);
        return map;
    }

    private static UUID parseUuid(Object obj) {
        if (obj == null) return null;
        return UUID.fromString(obj.toString());
    }

    private static BigDecimal parseDecimal(Object obj) {
        if (obj == null) return null;
        return new BigDecimal(obj.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    // =========================================================================
    // EV01
    // =========================================================================

    @PostMapping("/completeness")
    public ResponseEntity<?> registerCompleteness(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID sourceOriginalId = parseUuid(body.get("sourceOriginalId"));
            EvidenceClassification classification = EvidenceClassification.valueOf((String) body.get("classification"));
            String paperStorageLocation = (String) body.get("paperStorageLocation");
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) body.get("metadataIndex");
            @SuppressWarnings("unchecked")
            Map<String, Object> createdBy = (Map<String, Object>) body.get("createdBy");

            EvidenceFapiaoModels.CompletenessRecord rec = service.registerCompleteness(
                    c, tenantId, legalEntityId, sourceOriginalId, classification,
                    paperStorageLocation, metadata, createdBy
            );
            return ResponseEntity.status(201).body(response(201, rec));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @GetMapping("/completeness/{id}/export-index")
    public ResponseEntity<?> getExportIndex(@PathVariable String id, @RequestParam UUID tenantId, @RequestParam UUID legalEntityId) {
        try (Connection c = dataSource.getConnection()) {
            UUID evidenceId = UUID.fromString(id);
            var exportIndex = service.generateMetadataExportIndex(c, tenantId, legalEntityId, evidenceId);
            return ResponseEntity.ok(response(200, exportIndex));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    // =========================================================================
    // EV02
    // =========================================================================

    @PostMapping("/verify/xml")
    public ResponseEntity<?> parseAndVerifyXml(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID evidenceId = parseUuid(body.get("evidenceId"));
            String xmlContent = (String) body.get("xmlContent");
            String verifier = (String) body.get("verifier");

            var result = service.parseAndVerifyXml(c, tenantId, legalEntityId, evidenceId, xmlContent, verifier);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("fapiaoNumber", result.structuredData().fapiaoNumber());
            res.put("untaxedAmount", result.structuredData().untaxedAmount().toPlainString());
            res.put("taxAmount", result.structuredData().taxAmount().toPlainString());
            res.put("totalAmount", result.structuredData().totalAmount().toPlainString());
            res.put("taxBureauVerification", result.verificationRecord().taxBureauVerification());
            res.put("status", result.verificationRecord().comparisonStatus());
            return ResponseEntity.ok(response(200, res));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @PostMapping("/verify/ocr-cross-check")
    public ResponseEntity<?> crossCheckOcr(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID evidenceId = parseUuid(body.get("evidenceId"));
            String xmlContent = (String) body.get("xmlContent");
            @SuppressWarnings("unchecked")
            Map<String, Object> ocrMap = (Map<String, Object>) body.get("ocr");
            String verifier = (String) body.get("verifier");

            FapiaoStructuredData structured = FapiaoXmlParser.parseXml(xmlContent);
            FapiaoOcrComparator.OcrResult ocrResult = new FapiaoOcrComparator.OcrResult(
                    (String) ocrMap.get("fapiaoNumber"),
                    (String) ocrMap.get("buyerTaxNo"),
                    (String) ocrMap.get("sellerTaxNo"),
                    parseDecimal(ocrMap.get("untaxedAmount")),
                    parseDecimal(ocrMap.get("taxAmount")),
                    parseDecimal(ocrMap.get("totalAmount")),
                    (String) ocrMap.get("rawOcrDigest")
            );

            var cmp = service.crossCheckOcr(c, tenantId, legalEntityId, evidenceId, structured, ocrResult, verifier);
            return ResponseEntity.ok(response(200, cmp));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    // =========================================================================
    // EV03
    // =========================================================================

    @PostMapping("/quota/init")
    public ResponseEntity<?> initQuota(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID evidenceId = parseUuid(body.get("evidenceId"));
            ExtendedQuotaBucket bucketType = ExtendedQuotaBucket.valueOf((String) body.get("bucketType"));
            BigDecimal capacity = parseDecimal(body.get("capacity"));

            var q = service.initExtendedQuota(c, tenantId, legalEntityId, evidenceId, bucketType, capacity);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("id", q.id());
            res.put("bucketType", q.bucketType().name());
            res.put("capacity", q.capacity().toPlainString());
            res.put("availableAmount", q.availableAmount().toPlainString());
            return ResponseEntity.status(201).body(response(201, res));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @PostMapping("/quota/reserve")
    public ResponseEntity<?> reserveQuota(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID evidenceId = parseUuid(body.get("evidenceId"));
            ExtendedQuotaBucket bucketType = ExtendedQuotaBucket.valueOf((String) body.get("bucketType"));
            BigDecimal amount = parseDecimal(body.get("amount"));
            UUID chainId = parseUuid(body.get("businessChainId"));
            @SuppressWarnings("unchecked")
            Map<String, Object> operator = (Map<String, Object>) body.get("operator");

            var q = service.reserveQuota(c, tenantId, legalEntityId, evidenceId, bucketType, amount, chainId, operator);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("id", q.id());
            res.put("bucketType", q.bucketType().name());
            res.put("reservedAmount", q.reservedAmount().toPlainString());
            res.put("availableAmount", q.availableAmount().toPlainString());
            return ResponseEntity.ok(response(200, res));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @PostMapping("/quota/commit")
    public ResponseEntity<?> commitQuota(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID evidenceId = parseUuid(body.get("evidenceId"));
            ExtendedQuotaBucket bucketType = ExtendedQuotaBucket.valueOf((String) body.get("bucketType"));
            BigDecimal amount = parseDecimal(body.get("amount"));
            UUID chainId = parseUuid(body.get("businessChainId"));
            @SuppressWarnings("unchecked")
            Map<String, Object> operator = (Map<String, Object>) body.get("operator");

            var q = service.commitQuota(c, tenantId, legalEntityId, evidenceId, bucketType, amount, chainId, operator);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("id", q.id());
            res.put("bucketType", q.bucketType().name());
            res.put("committedAmount", q.committedAmount().toPlainString());
            res.put("availableAmount", q.availableAmount().toPlainString());
            return ResponseEntity.ok(response(200, res));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @PostMapping("/quota/release")
    public ResponseEntity<?> releaseQuota(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID evidenceId = parseUuid(body.get("evidenceId"));
            ExtendedQuotaBucket bucketType = ExtendedQuotaBucket.valueOf((String) body.get("bucketType"));
            BigDecimal amount = parseDecimal(body.get("amount"));
            UUID chainId = parseUuid(body.get("businessChainId"));
            @SuppressWarnings("unchecked")
            Map<String, Object> operator = (Map<String, Object>) body.get("operator");

            var q = service.releaseQuota(c, tenantId, legalEntityId, evidenceId, bucketType, amount, chainId, operator);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("id", q.id());
            res.put("bucketType", q.bucketType().name());
            res.put("releasedAmount", q.releasedAmount().toPlainString());
            res.put("availableAmount", q.availableAmount().toPlainString());
            return ResponseEntity.ok(response(200, res));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    // =========================================================================
    // EV04
    // =========================================================================

    @PostMapping("/graph/nodes")
    public ResponseEntity<?> addGraphNode(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            GraphNodeType nodeType = GraphNodeType.valueOf((String) body.get("nodeType"));
            String refId = (String) body.get("businessRefId");
            String label = (String) body.get("nodeLabel");
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) body.get("metadata");

            var node = service.addGraphNode(c, tenantId, legalEntityId, nodeType, refId, label, metadata);
            return ResponseEntity.status(201).body(response(201, node));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @PostMapping("/graph/edges")
    public ResponseEntity<?> addGraphEdge(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID sourceId = parseUuid(body.get("sourceNodeId"));
            UUID targetId = parseUuid(body.get("targetNodeId"));
            GraphEdgeType edgeType = GraphEdgeType.valueOf((String) body.get("relationshipType"));
            BigDecimal amount = parseDecimal(body.get("amount"));
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) body.get("metadata");

            var edge = service.addGraphEdge(c, tenantId, legalEntityId, sourceId, targetId, edgeType, amount, metadata);
            return ResponseEntity.status(201).body(response(201, edge));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @PostMapping("/graph/trace")
    public ResponseEntity<?> traceEvidenceGraph(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID nodeId = parseUuid(body.get("nodeId"));

            var trace = service.traceEvidencePath(c, tenantId, legalEntityId, nodeId);
            return ResponseEntity.ok(response(200, trace));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @PostMapping("/exceptions")
    public ResponseEntity<?> registerException(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID chainId = parseUuid(body.get("businessChainId"));
            EvidenceExceptionType exType = EvidenceExceptionType.valueOf((String) body.get("exceptionType"));
            String desc = (String) body.get("description");
            BigDecimal provisionalAmount = parseDecimal(body.get("provisionalAmount"));
            String respId = (String) body.get("responsiblePersonId");
            String respName = (String) body.get("responsiblePersonName");
            LocalDate dueDate = LocalDate.parse((String) body.get("dueDate"));
            @SuppressWarnings("unchecked")
            Map<String, Object> createdBy = (Map<String, Object>) body.get("createdBy");

            var ex = service.registerException(
                    c, tenantId, legalEntityId, chainId, exType, desc,
                    provisionalAmount != null ? provisionalAmount : BigDecimal.ZERO,
                    respId, respName, dueDate, createdBy
            );
            return ResponseEntity.status(201).body(response(201, ex));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @PostMapping("/exceptions/{id}/approve")
    public ResponseEntity<?> approveException(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID exId = UUID.fromString(id);
            String reason = (String) body.get("approvalReason");
            @SuppressWarnings("unchecked")
            Map<String, Object> approvedBy = (Map<String, Object>) body.get("approvedBy");

            service.approveException(c, tenantId, legalEntityId, exId, reason, approvedBy);
            return ResponseEntity.ok(response(200, Map.of("id", exId, "status", "APPROVED_EXCEPTION")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    // =========================================================================
    // FP01
    // =========================================================================

    @PostMapping("/fapiao")
    public ResponseEntity<?> registerFapiao(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            FapiaoDirection direction = FapiaoDirection.valueOf((String) body.get("direction"));
            String code = (String) body.get("invoiceCode");
            String num = (String) body.get("fapiaoNumber");
            FapiaoType type = FapiaoType.valueOf((String) body.get("fapiaoType"));
            LocalDate issueDate = LocalDate.parse((String) body.get("issueDate"));
            String buyerTax = (String) body.get("buyerTaxNo");
            String buyerName = (String) body.get("buyerName");
            String sellerTax = (String) body.get("sellerTaxNo");
            String sellerName = (String) body.get("sellerName");
            BigDecimal untaxed = parseDecimal(body.get("untaxedAmount"));
            BigDecimal taxRate = new BigDecimal(body.get("taxRate").toString());
            BigDecimal taxAmount = parseDecimal(body.get("taxAmount"));
            BigDecimal totalAmount = parseDecimal(body.get("totalAmount"));
            UUID docId = parseUuid(body.get("originalDocumentId"));
            UUID journalId = parseUuid(body.get("ledgerJournalId"));
            UUID taxSheetId = parseUuid(body.get("taxWorksheetId"));

            var fapiao = service.registerFapiao(
                    c, tenantId, legalEntityId, direction, code, num, type, issueDate,
                    buyerTax, buyerName, sellerTax, sellerName, untaxed, taxRate, taxAmount, totalAmount,
                    docId, journalId, taxSheetId
            );

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("id", fapiao.id());
            res.put("fapiaoNumber", fapiao.fapiaoNumber());
            res.put("untaxedAmount", fapiao.untaxedAmount().toPlainString());
            res.put("taxAmount", fapiao.taxAmount().toPlainString());
            res.put("totalAmount", fapiao.totalAmount().toPlainString());
            res.put("lifecycleStatus", fapiao.lifecycleStatus().name());
            res.put("taxBureauVerification", fapiao.taxBureauVerification());
            return ResponseEntity.status(201).body(response(201, res));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @PostMapping("/fapiao/{id}/reconcile")
    public ResponseEntity<?> reconcileFapiao(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID fapiaoId = UUID.fromString(id);
            UUID ledgerJournalId = parseUuid(body.get("ledgerJournalId"));
            UUID taxWorksheetId = parseUuid(body.get("taxWorksheetId"));

            var fapiao = service.reconcileFapiao(c, tenantId, legalEntityId, fapiaoId, ledgerJournalId, taxWorksheetId);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("id", fapiao.id());
            res.put("lifecycleStatus", fapiao.lifecycleStatus().name());
            res.put("reconciliationStatus", fapiao.reconciliationStatus());
            res.put("ledgerJournalId", fapiao.ledgerJournalId() != null ? fapiao.ledgerJournalId().toString() : null);
            res.put("taxWorksheetId", fapiao.taxWorksheetId() != null ? fapiao.taxWorksheetId().toString() : null);
            return ResponseEntity.ok(response(200, res));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    // =========================================================================
    // FP02
    // =========================================================================

    @PostMapping("/issuance")
    public ResponseEntity<?> submitIssuanceApp(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            String appNum = (String) body.get("applicationNumber");
            @SuppressWarnings("unchecked")
            Map<String, Object> applicant = (Map<String, Object>) body.get("applicant");
            LocalDate appDate = LocalDate.parse((String) body.get("applicationDate"));
            String contractId = (String) body.get("contractId");
            String customerId = (String) body.get("customerId");
            String buyerTax = (String) body.get("buyerTaxNo");
            String buyerName = (String) body.get("buyerName");
            String addrPhone = (String) body.get("buyerAddressPhone");
            String bankAcc = (String) body.get("buyerBankAccount");
            BigDecimal untaxed = parseDecimal(body.get("untaxedAmount"));
            BigDecimal taxAmount = parseDecimal(body.get("taxAmount"));
            BigDecimal totalAmount = parseDecimal(body.get("totalAmount"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");

            var app = service.submitIssuanceApp(
                    c, tenantId, legalEntityId, appNum, applicant, appDate, contractId, customerId,
                    buyerTax, buyerName, addrPhone, bankAcc, untaxed, taxAmount, totalAmount, items
            );

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("id", app.id());
            res.put("applicationNumber", app.applicationNumber());
            res.put("status", app.status().name());
            res.put("buyerQualificationStatus", app.buyerQualificationStatus().name());
            res.put("externalTaxChannel", app.externalTaxChannel());
            return ResponseEntity.status(201).body(response(201, res));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @PostMapping("/issuance/{id}/review-buyer")
    public ResponseEntity<?> reviewBuyerQualification(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID appId = UUID.fromString(id);
            BuyerQualificationStatus status = BuyerQualificationStatus.valueOf((String) body.get("buyerQualificationStatus"));
            String notes = (String) body.get("qualificationNotes");
            @SuppressWarnings("unchecked")
            Map<String, Object> reviewer = (Map<String, Object>) body.get("reviewer");

            service.reviewBuyerQualification(c, tenantId, legalEntityId, appId, status, notes, reviewer);
            return ResponseEntity.ok(response(200, Map.of("id", appId, "buyerQualificationStatus", status.name())));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @PostMapping("/issuance/{id}/confirm")
    public ResponseEntity<?> confirmManualIssuance(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID appId = UUID.fromString(id);
            String fapiaoNum = (String) body.get("issuedFapiaoNumber");
            String invoiceCode = (String) body.get("invoiceCode");
            LocalDate issueDate = LocalDate.parse((String) body.get("issueDate"));
            String sellerTax = (String) body.get("sellerTaxNo");
            String sellerName = (String) body.get("sellerName");
            UUID boundOriginalId = parseUuid(body.get("boundOriginalDocumentId"));
            String notes = (String) body.get("notes");
            @SuppressWarnings("unchecked")
            Map<String, Object> confirmedBy = (Map<String, Object>) body.get("confirmedBy");

            var fapiao = service.confirmManualIssuance(
                    c, tenantId, legalEntityId, appId, fapiaoNum, invoiceCode,
                    issueDate, sellerTax, sellerName, boundOriginalId, notes, confirmedBy
            );
            return ResponseEntity.ok(response(200, Map.of(
                    "appId", appId,
                    "issuedFapiaoId", fapiao.id(),
                    "fapiaoNumber", fapiao.fapiaoNumber(),
                    "status", "ISSUED_MANUAL_CONFIRMED"
            )));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    // =========================================================================
    // FP03
    // =========================================================================

    @PostMapping("/red-notices")
    public ResponseEntity<?> registerRedNotice(@RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            String noticeNum = (String) body.get("redNoticeNumber");
            UUID blueId = parseUuid(body.get("originalBlueFapiaoId"));
            String dir = (String) body.get("direction");
            String init = (String) body.get("initiator");
            String reason = (String) body.get("reason");
            BigDecimal redUntaxed = parseDecimal(body.get("redUntaxedAmount"));
            BigDecimal redTax = parseDecimal(body.get("redTaxAmount"));
            BigDecimal redTotal = parseDecimal(body.get("redTotalAmount"));

            var notice = service.registerRedNotice(
                    c, tenantId, legalEntityId, noticeNum, blueId, dir, init, reason, redUntaxed, redTax, redTotal
            );

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("id", notice.id());
            res.put("redNoticeNumber", notice.redNoticeNumber());
            res.put("status", notice.status().name());
            res.put("expiresAt", notice.expiresAt().toString());
            res.put("redTotalAmount", notice.redTotalAmount().toPlainString());
            return ResponseEntity.status(201).body(response(201, res));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @PostMapping("/red-notices/{id}/confirm")
    public ResponseEntity<?> confirmRedNotice(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID noticeId = UUID.fromString(id);
            Instant now = body.containsKey("currentTime") ? Instant.parse((String) body.get("currentTime")) : Instant.now();

            service.confirmRedNotice(c, tenantId, legalEntityId, noticeId, now);
            return ResponseEntity.ok(response(200, Map.of("id", noticeId, "status", "CONFIRMED")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }

    @PostMapping("/red-notices/{id}/link-credit")
    public ResponseEntity<?> linkCreditFapiao(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try (Connection c = dataSource.getConnection()) {
            UUID tenantId = parseUuid(body.get("tenantId"));
            UUID legalEntityId = parseUuid(body.get("legalEntityId"));
            UUID noticeId = UUID.fromString(id);
            UUID creditFapiaoId = parseUuid(body.get("creditFapiaoId"));
            BigDecimal revReversal = parseDecimal(body.get("accountingRevenueReversalAmount"));
            BigDecimal vatAdj = parseDecimal(body.get("vatAdjustmentAmount"));
            UUID revJournalId = parseUuid(body.get("revenueReversalJournalId"));
            UUID vatJournalId = parseUuid(body.get("vatAdjustmentJournalId"));

            service.linkCreditFapiao(c, tenantId, legalEntityId, noticeId, creditFapiaoId, revReversal, vatAdj, revJournalId, vatJournalId);
            return ResponseEntity.ok(response(200, Map.of(
                    "id", noticeId,
                    "status", "RED_INVOICE_ISSUED",
                    "creditFapiaoId", creditFapiaoId,
                    "revenueReversalAmount", revReversal.toPlainString(),
                    "vatAdjustmentAmount", vatAdj.toPlainString()
            )));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(400, e.getMessage()));
        }
    }
}
