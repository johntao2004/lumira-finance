package com.lumira.finance.subledgerext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import com.lumira.finance.FinanceGlobalExceptionHandler;
import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import com.lumira.finance.subledgerext.SubledgerExtModels.*;
import com.lumira.finance.subledgerext.SubledgerExtRules.*;

/**
 * REST controller for AP01, AR01, and AR02 subledger extensions.
 * Follows strict closed schemas and serializes all BigDecimals with .toPlainString().
 */
@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/subledger-ext")
@ModuleEndpoint("finance")
public final class SubledgerExtController {
    private final SubledgerExtService service;
    private final DataSource dataSource;

    public SubledgerExtController(SubledgerExtService service) {
        this(service, null);
    }

    @Autowired
    public SubledgerExtController(SubledgerExtService service, @Autowired(required = false) DataSource dataSource) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.dataSource = dataSource;
    }

    private UUID parseUuid(String s) {
        return SubledgerExtRules.uuid(s);
    }

    private UUID resolveTenant(String tenantHeader, String bookStr) {
        if (tenantHeader != null && !tenantHeader.isBlank()) {
            return parseUuid(tenantHeader);
        }
        if (dataSource != null && bookStr != null && !bookStr.isBlank()) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT tenant_id FROM plugin_finance_book WHERE id = ?")) {
                ps.setObject(1, parseUuid(bookStr));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return (UUID) rs.getObject("tenant_id");
                    }
                }
            } catch (Exception ignored) {
            }
        }
        throw new IllegalArgumentException("TENANT_REQUIRED: X-Tenant-Id header is required and could not be resolved");
    }

    private UUID resolveActor(String actorHeader) {
        if (actorHeader != null && !actorHeader.isBlank()) {
            return parseUuid(actorHeader);
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
            String username = auth.getName();
            if (dataSource != null) {
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "SELECT id FROM platform_user WHERE username = ? AND enabled = true AND deleted_at IS NULL")) {
                    ps.setString(1, username);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return (UUID) rs.getObject("id");
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            return UUID.nameUUIDFromBytes(("lumira:user:" + username).getBytes(StandardCharsets.UTF_8));
        }
        throw new IllegalArgumentException("ACTOR_REQUIRED: X-Actor-Id header is required");
    }

    private AccountingStandard parseStandard(String s) {
        if (s == null || s.isBlank()) return AccountingStandard.CAS;
        return AccountingStandard.valueOf(s.trim().toUpperCase());
    }

    // --- AP PO Endpoints ---
    @PostMapping("/ap/po")
    public ResponseEntity<?> createPo(
            @PathVariable String book,
            @PathVariable String year,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            PoOrder po = service.createPo(tenantId, parseUuid(book), parseUuid(year), actorId, body);
            return ResponseEntity.status(HttpStatus.CREATED).body(formatPo(po));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/ap/po/{id}")
    public ResponseEntity<?> getPo(@PathVariable String id) {
        return service.getStore().getPo(parseUuid(id))
                .map(po -> ResponseEntity.ok(formatPo(po)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/ap/po/{id}/approve")
    public ResponseEntity<?> approvePo(
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            service.approvePo(tenantId, parseUuid(book), parseUuid(year), actorId, parseUuid(id), body);
            return ResponseEntity.ok(Map.of("status", "APPROVED"));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- AP Acceptance Endpoints ---
    @PostMapping("/ap/acceptance")
    public ResponseEntity<?> createAcceptance(
            @PathVariable String book,
            @PathVariable String year,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            PoAcceptance acc = service.createAcceptance(tenantId, parseUuid(book), parseUuid(year), actorId, body);
            return ResponseEntity.status(HttpStatus.CREATED).body(formatAcceptance(acc));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/ap/acceptance/{id}")
    public ResponseEntity<?> getAcceptance(@PathVariable String id) {
        return service.getStore().getAcceptance(parseUuid(id))
                .map(acc -> ResponseEntity.ok(formatAcceptance(acc)))
                .orElse(ResponseEntity.notFound().build());
    }

    // --- AP Provisional Accrual & Settlement Endpoints ---
    @PostMapping("/ap/provisional")
    public ResponseEntity<?> createProvisionalAccrual(
            @PathVariable String book,
            @PathVariable String year,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            ProvisionalAccrual pa = service.createProvisionalAccrual(tenantId, parseUuid(book), parseUuid(year), actorId, body);
            return ResponseEntity.status(HttpStatus.CREATED).body(formatProvisional(pa));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/ap/provisional/{id}")
    public ResponseEntity<?> getProvisional(@PathVariable String id) {
        return service.getStore().getProvisionalAccrual(parseUuid(id))
                .map(pa -> ResponseEntity.ok(formatProvisional(pa)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/ap/provisional/settle")
    public ResponseEntity<?> settleProvisionalAccrual(
            @PathVariable String book,
            @PathVariable String year,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            ProvisionalSettlement ps = service.settleProvisionalAccrual(tenantId, parseUuid(book), parseUuid(year), actorId, body);
            return ResponseEntity.status(HttpStatus.CREATED).body(formatSettlement(ps));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- AR Contract Asset Endpoints (CAS 14) ---
    @PostMapping("/ar/contract-assets")
    public ResponseEntity<?> createContractAsset(
            @PathVariable String book,
            @PathVariable String year,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestHeader(name = "X-Accounting-Standard", defaultValue = "CAS") String standardHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            ContractAsset ca = service.createContractAsset(
                    tenantId, parseUuid(book), parseUuid(year),
                    actorId, parseStandard(standardHeader), body
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(formatContractAsset(ca));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/ar/contract-assets/{id}")
    public ResponseEntity<?> getContractAsset(@PathVariable String id) {
        return service.getStore().getContractAsset(parseUuid(id))
                .map(ca -> ResponseEntity.ok(formatContractAsset(ca)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/ar/contract-assets/{id}/reclassify")
    public ResponseEntity<?> reclassifyContractAsset(
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestHeader(name = "X-Accounting-Standard", defaultValue = "CAS") String standardHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            service.reclassifyContractAsset(
                    tenantId, parseUuid(book), parseUuid(year),
                    actorId, parseUuid(id), parseStandard(standardHeader), body
            );
            return ResponseEntity.ok(Map.of("status", "RECLASSIFIED"));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- AR Variable Consideration Endpoints ---
    @PostMapping("/ar/variable-consideration")
    public ResponseEntity<?> createVariableConsideration(
            @PathVariable String book,
            @PathVariable String year,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestHeader(name = "X-Accounting-Standard", defaultValue = "CAS") String standardHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            VariableConsideration vc = service.createVariableConsideration(
                    tenantId, parseUuid(book), parseUuid(year),
                    actorId, parseStandard(standardHeader), body
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(formatVariable(vc));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/ar/variable-consideration/{id}/approve")
    public ResponseEntity<?> approveVariableConsideration(
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            service.approveVariableConsideration(tenantId, parseUuid(book), parseUuid(year), actorId, parseUuid(id), body);
            return ResponseEntity.ok(Map.of("status", "APPROVED"));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/ar/variable-consideration/{id}/revise")
    public ResponseEntity<?> reviseVariableConsideration(
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            service.reviseVariableConsideration(tenantId, parseUuid(book), parseUuid(year), actorId, parseUuid(id), body);
            return ResponseEntity.ok(Map.of("status", "REVISED"));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- AR Multi-Year Contract Continuity Endpoints ---
    @PostMapping("/ar/multi-year-sync")
    public ResponseEntity<?> syncMultiYear(
            @PathVariable String book,
            @PathVariable String year,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            MultiYearContract myc = service.syncMultiYearContract(tenantId, parseUuid(book), parseUuid(year), actorId, body);
            return ResponseEntity.ok(formatMultiYear(myc));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- AR Expected Credit Loss (ECL) Endpoints ---
    @PostMapping("/ar/ecl")
    public ResponseEntity<?> createEcl(
            @PathVariable String book,
            @PathVariable String year,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestHeader(name = "X-Accounting-Standard", defaultValue = "CAS") String standardHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            EclRecord ecl = service.createEclRecord(
                    tenantId, parseUuid(book), parseUuid(year),
                    actorId, parseStandard(standardHeader), body
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(formatEcl(ecl));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/ar/ecl/{id}/approve")
    public ResponseEntity<?> approveEcl(
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            service.approveEclRecord(tenantId, parseUuid(book), parseUuid(year), actorId, parseUuid(id), body);
            return ResponseEntity.ok(Map.of("status", "POSTED"));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- AR Allowance / Return Endpoints ---
    @PostMapping("/ar/allowance")
    public ResponseEntity<?> createAllowance(
            @PathVariable String book,
            @PathVariable String year,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            AllowanceAdjustment adj = service.createAllowanceAdjustment(tenantId, parseUuid(book), parseUuid(year), actorId, body);
            return ResponseEntity.status(HttpStatus.CREATED).body(formatAllowance(adj));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/ar/allowance/{id}/approve")
    public ResponseEntity<?> approveAllowance(
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorHeader,
            @RequestBody Map<String, Object> body) {
        try {
            UUID tenantId = resolveTenant(tenantHeader, book);
            UUID actorId = resolveActor(actorHeader);
            service.approveAllowanceAdjustment(tenantId, parseUuid(book), parseUuid(year), actorId, parseUuid(id), body);
            return ResponseEntity.ok(Map.of("status", "POSTED"));
        } catch (SubledgerException e) {
            return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- Strict Plain String Formatters ---
    private Map<String, Object> formatPo(PoOrder po) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", po.id().toString());
        m.put("poNumber", po.poNumber());
        m.put("supplierId", po.supplierId().toString());
        m.put("orderDate", po.orderDate().toString());
        m.put("currency", po.currency());
        m.put("totalNetAmount", po.totalNetAmount().toPlainString());
        m.put("totalTaxAmount", po.totalTaxAmount().toPlainString());
        m.put("totalGrossAmount", po.totalGrossAmount().toPlainString());
        m.put("status", po.status().name());
        m.put("version", po.version());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (PoLine l : po.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("lineNo", l.lineNo());
            lm.put("itemCode", l.itemCode());
            lm.put("itemName", l.itemName());
            lm.put("quantity", l.quantity().toPlainString());
            lm.put("unitPrice", l.unitPrice().toPlainString());
            lm.put("netAmount", l.netAmount().toPlainString());
            lm.put("taxRate", l.taxRate().toPlainString());
            lm.put("taxAmount", l.taxAmount().toPlainString());
            lm.put("grossAmount", l.grossAmount().toPlainString());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }

    private Map<String, Object> formatAcceptance(PoAcceptance acc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", acc.id().toString());
        m.put("poId", acc.poId().toString());
        m.put("acceptanceNumber", acc.acceptanceNumber());
        m.put("acceptedDate", acc.acceptedDate().toString());
        m.put("status", acc.status().name());
        m.put("invoiceStatus", acc.invoiceStatus().name());
        m.put("provisionalStatus", acc.provisionalStatus().name());
        m.put("version", acc.version());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (AcceptanceLine l : acc.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("lineNo", l.lineNo());
            lm.put("poLineId", l.poLineId().toString());
            lm.put("acceptedQuantity", l.acceptedQuantity().toPlainString());
            lm.put("unitCostEstimate", l.unitCostEstimate().toPlainString());
            lm.put("estimatedNetAmount", l.estimatedNetAmount().toPlainString());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }

    private Map<String, Object> formatProvisional(ProvisionalAccrual pa) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", pa.id().toString());
        m.put("acceptanceId", pa.acceptanceId().toString());
        m.put("poId", pa.poId().toString());
        m.put("accrualPeriod", pa.accrualPeriod());
        m.put("accrualDate", pa.accrualDate().toString());
        m.put("estimatedNetAmount", pa.estimatedNetAmount().toPlainString());
        m.put("status", pa.status().name());
        m.put("version", pa.version());
        return m;
    }

    private Map<String, Object> formatSettlement(ProvisionalSettlement ps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ps.id().toString());
        m.put("provisionalId", ps.provisionalId().toString());
        m.put("invoiceNumber", ps.invoiceNumber());
        m.put("invoiceDate", ps.invoiceDate().toString());
        m.put("actualNetAmount", ps.actualNetAmount().toPlainString());
        m.put("actualTaxAmount", ps.actualTaxAmount().toPlainString());
        m.put("actualGrossAmount", ps.actualGrossAmount().toPlainString());
        m.put("priceVariance", ps.priceVariance().toPlainString());
        m.put("quantityVariance", ps.quantityVariance().toPlainString());
        m.put("totalVariance", ps.totalVariance().toPlainString());
        m.put("status", ps.status());
        m.put("version", ps.version());
        return m;
    }

    private Map<String, Object> formatContractAsset(ContractAsset ca) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ca.id().toString());
        m.put("contractId", ca.contractId().toString());
        m.put("customerId", ca.customerId().toString());
        m.put("stageName", ca.stageName());
        m.put("milestoneDate", ca.milestoneDate().toString());
        m.put("assetAmount", ca.assetAmount().toPlainString());
        m.put("conditionStatus", ca.conditionStatus().name());
        m.put("status", ca.status().name());
        m.put("version", ca.version());
        return m;
    }

    private Map<String, Object> formatVariable(VariableConsideration vc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", vc.id().toString());
        m.put("contractId", vc.contractId().toString());
        m.put("variableKind", vc.variableKind().name());
        m.put("estimatedAmount", vc.estimatedAmount().toPlainString());
        m.put("confidenceScore", vc.confidenceScore().toPlainString());
        m.put("constrainedCapAmount", vc.constrainedCapAmount().toPlainString());
        m.put("status", vc.status().name());
        m.put("version", vc.version());
        return m;
    }

    private Map<String, Object> formatMultiYear(MultiYearContract myc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", myc.id().toString());
        m.put("contractId", myc.contractId().toString());
        m.put("startYear", myc.startYear());
        m.put("endYear", myc.endYear());
        m.put("totalContractValue", myc.totalContractValue().toPlainString());
        m.put("cumulativeProgressPct", myc.cumulativeProgressPct().toPlainString());
        m.put("cumulativeRevenueRecognized", myc.cumulativeRevenueRecognized().toPlainString());
        m.put("currentYearRevenue", myc.currentYearRevenue().toPlainString());
        m.put("unrecognizedBalance", myc.unrecognizedBalance().toPlainString());
        m.put("lastSyncYear", myc.lastSyncYear());
        m.put("status", myc.status().name());
        m.put("version", myc.version());
        return m;
    }

    private Map<String, Object> formatEcl(EclRecord ecl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ecl.id().toString());
        m.put("customerId", ecl.customerId().toString());
        m.put("period", ecl.period());
        m.put("receivableAmount", ecl.receivableAmount().toPlainString());
        m.put("agingBucket", ecl.agingBucket().name());
        m.put("lossRate", ecl.lossRate().toPlainString());
        m.put("provisionAmount", ecl.provisionAmount().toPlainString());
        m.put("priorAllowance", ecl.priorAllowance().toPlainString());
        m.put("adjustmentAmount", ecl.adjustmentAmount().toPlainString());
        m.put("operationKind", ecl.operationKind().name());
        m.put("status", ecl.status().name());
        m.put("version", ecl.version());
        return m;
    }

    private Map<String, Object> formatAllowance(AllowanceAdjustment adj) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", adj.id().toString());
        m.put("customerId", adj.customerId().toString());
        m.put("adjustmentType", adj.adjustmentType().name());
        m.put("creditNoteNumber", adj.creditNoteNumber());
        m.put("originalReceivableAmount", adj.originalReceivableAmount().toPlainString());
        m.put("netAdjustmentAmount", adj.netAdjustmentAmount().toPlainString());
        m.put("taxAdjustmentAmount", adj.taxAdjustmentAmount().toPlainString());
        m.put("grossAdjustmentAmount", adj.grossAdjustmentAmount().toPlainString());
        m.put("status", adj.status().name());
        m.put("version", adj.version());
        return m;
    }
}
