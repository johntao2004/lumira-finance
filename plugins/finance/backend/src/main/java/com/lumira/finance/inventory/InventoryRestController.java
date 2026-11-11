package com.lumira.finance.inventory;

import static com.lumira.finance.inventory.InventoryModel.*;

import com.lumira.finance.FinanceGlobalExceptionHandler;
import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/**
 * REST Controller for Inventory & Long-term Asset Extensions:
 * - Strict BigDecimal.toPlainString() formatting to prevent 0E-8 scientific notation
 * - Idempotent operation handling
 * - Clean closed JSON schema responses matching openapi-fragment.json
 */
@RestController
@RequestMapping("/api/finance/v1")
@ModuleEndpoint("finance")
public class InventoryRestController {

    private final InventoryService inventoryService;
    private final AssetExtensionService assetService;
    private final DataSource dataSource;

    public InventoryRestController() {
        this(null, new InventoryService(), new AssetExtensionService());
    }

    public InventoryRestController(DataSource dataSource) {
        this(dataSource, new InventoryService(), new AssetExtensionService());
    }

    @Autowired
    public InventoryRestController(@Autowired(required = false) DataSource dataSource, InventoryService inventoryService, AssetExtensionService assetService) {
        this.dataSource = dataSource;
        this.inventoryService = inventoryService != null ? inventoryService : new InventoryService();
        this.assetService = assetService != null ? assetService : new AssetExtensionService();
    }

    private Scope resolveScope(Map<String, Object> req, String headerTenant, String headerBook, String headerYear) {
        UUID bookId = req != null && req.containsKey("bookId") && req.get("bookId") != null
                ? UUID.fromString(req.get("bookId").toString())
                : (headerBook != null && !headerBook.isBlank() ? UUID.fromString(headerBook) : null);
        if (bookId == null) {
            throw new IllegalArgumentException("BOOK_REQUIRED: bookId is required in body or X-Book-Id header");
        }

        UUID yearId = req != null && req.containsKey("yearId") && req.get("yearId") != null
                ? UUID.fromString(req.get("yearId").toString())
                : (headerYear != null && !headerYear.isBlank() ? UUID.fromString(headerYear) : null);
        if (yearId == null) {
            throw new IllegalArgumentException("YEAR_REQUIRED: yearId is required in body or X-Year-Id header");
        }

        UUID tenantId = req != null && req.containsKey("tenantId") && req.get("tenantId") != null
                ? UUID.fromString(req.get("tenantId").toString())
                : (headerTenant != null && !headerTenant.isBlank() ? UUID.fromString(headerTenant) : null);

        if (tenantId == null && dataSource != null) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT tenant_id FROM plugin_finance_book WHERE id = ?")) {
                ps.setObject(1, bookId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        tenantId = (UUID) rs.getObject("tenant_id");
                    }
                }
            } catch (SQLException ignored) {
            }
        }

        if (tenantId == null) {
            throw new IllegalArgumentException("TENANT_REQUIRED: tenantId is required in body or X-Tenant-Id header");
        }

        return new Scope(tenantId, bookId, yearId);
    }

    private Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new IllegalStateException("DATA_SOURCE_NOT_CONFIGURED");
        }
        return dataSource.getConnection();
    }

    @PostMapping("/inventory/depreciation/preview")
    public ResponseEntity<Map<String, Object>> previewDepreciation(@RequestBody Map<String, Object> req) {
        try {
            String methodStr = (String) req.get("method");
            String startPeriod = (String) req.get("startPeriod");
            int totalMonths = ((Number) req.get("totalMonths")).intValue();
            BigDecimal cost = new BigDecimal(req.get("originalCost").toString());
            BigDecimal residual = new BigDecimal(req.get("residualValue").toString());

            DepreciationMethod method = DepreciationMethod.valueOf(methodStr);
            List<DepreciationScheduleRow> schedule = AssetDepreciationEngine.generateSchedule(
                    method, startPeriod, totalMonths, cost, residual
            );

            List<Map<String, Object>> rows = schedule.stream().map(r -> Map.<String, Object>of(
                    "period", r.period(),
                    "monthIndex", r.monthIndex(),
                    "monthlyDepreciation", r.monthlyDepreciation().toPlainString(),
                    "remainingCarryingAmount", r.remainingCarryingAmount().toPlainString()
            )).toList();

            return ResponseEntity.ok(Map.of(
                    "method", method.name(),
                    "startPeriod", startPeriod,
                    "totalMonths", totalMonths,
                    "rows", rows
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/inventory/costing/fifo-preview")
    public ResponseEntity<Map<String, Object>> previewFifo(@RequestBody Map<String, Object> req) {
        try {
            BigDecimal needed = new BigDecimal(req.get("quantity").toString());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawLayers = (List<Map<String, Object>>) req.get("layers");

            List<FifoLayer> layers = new ArrayList<>();
            if (rawLayers != null) {
                for (Map<String, Object> l : rawLayers) {
                    layers.add(new FifoLayer(
                            UUID.fromString(l.get("batchId").toString()),
                            LocalDate.parse(l.get("inboundDate").toString()),
                            new BigDecimal(l.get("remainingQuantity").toString()),
                            new BigDecimal(l.get("unitCost").toString())
                    ));
                }
            }

            FifoResult res = InventoryCostingEngine.calculateFifoOutbound(layers, needed);

            List<Map<String, Object>> allocs = res.allocations().stream().map(a -> Map.<String, Object>of(
                    "batchId", a.batchId().toString(),
                    "quantityConsumed", a.quantityConsumed().toPlainString(),
                    "unitCost", a.unitCost().toPlainString(),
                    "costConsumed", a.costConsumed().toPlainString()
            )).toList();

            List<Map<String, Object>> remainingLayers = res.updatedLayers().stream().map(l -> Map.<String, Object>of(
                    "batchId", l.batchId().toString(),
                    "inboundDate", l.inboundDate().toString(),
                    "remainingQuantity", l.remainingQuantity().toPlainString(),
                    "unitCost", l.unitCost().toPlainString()
            )).toList();

            return ResponseEntity.ok(Map.of(
                    "totalConsumedQuantity", res.totalConsumedQuantity().toPlainString(),
                    "totalConsumedCost", res.totalConsumedCost().toPlainString(),
                    "allocations", allocs,
                    "updatedLayers", remainingLayers
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/inventory/impairment/evaluate")
    public ResponseEntity<Map<String, Object>> evaluateInventoryImpairment(@RequestBody Map<String, Object> req) {
        try {
            String regime = (String) req.get("regime");
            String period = (String) req.get("period");
            UUID itemId = UUID.fromString(req.get("itemId").toString());
            BigDecimal bookCost = new BigDecimal(req.get("bookCost").toString());
            BigDecimal nrv = new BigDecimal(req.get("nrv").toString());
            BigDecimal prior = req.get("priorImpairment") != null
                    ? new BigDecimal(req.get("priorImpairment").toString()) : ZERO_8;

            InventoryImpairmentEvaluation eval = InventoryCostingEngine.evaluateInventoryImpairment(
                    regime, period, itemId, bookCost, nrv, prior
            );

            return ResponseEntity.ok(Map.of(
                    "itemId", eval.itemId().toString(),
                    "regime", eval.regime(),
                    "period", eval.period(),
                    "bookCost", eval.bookCost().toPlainString(),
                    "nrv", eval.nrv().toPlainString(),
                    "priorImpairment", eval.priorImpairment().toPlainString(),
                    "impairmentAdjustment", eval.impairmentAdjustment().toPlainString(),
                    "newImpairment", eval.newImpairment().toPlainString(),
                    "status", eval.status()
            ));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/inventory/warehouses")
    public ResponseEntity<Map<String, Object>> createWarehouse(
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "X-Tenant-Id", required = false) String hTenant,
            @RequestHeader(value = "X-Book-Id", required = false) String hBook,
            @RequestHeader(value = "X-Year-Id", required = false) String hYear) {
        try {
            Scope scope = resolveScope(req, hTenant, hBook, hYear);
            UUID id = UUID.randomUUID();
            String code = (String) req.get("code");
            String name = (String) req.get("name");
            String location = (String) req.get("location");
            Warehouse w = new Warehouse(id, code, name, location, true);

            try (Connection c = getConnection()) {
                inventoryService.createWarehouse(c, scope, w);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", id.toString());
            resp.put("code", code);
            resp.put("name", name);
            resp.put("location", location);
            resp.put("isActive", true);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/inventory/items")
    public ResponseEntity<Map<String, Object>> createItem(
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "X-Tenant-Id", required = false) String hTenant,
            @RequestHeader(value = "X-Book-Id", required = false) String hBook,
            @RequestHeader(value = "X-Year-Id", required = false) String hYear) {
        try {
            Scope scope = resolveScope(req, hTenant, hBook, hYear);
            UUID id = UUID.randomUUID();
            String code = (String) req.get("code");
            String name = (String) req.get("name");
            String spec = (String) req.get("spec");
            String unit = (String) req.get("unit");
            ValuationMethod method = ValuationMethod.valueOf((String) req.get("valuationMethod"));
            UUID invAcc = UUID.fromString((String) req.get("inventoryAccountId"));
            UUID cogsAcc = UUID.fromString((String) req.get("cogsAccountId"));
            UUID lossGainAcc = UUID.fromString((String) req.get("lossGainAccountId"));
            UUID impAcc = UUID.fromString((String) req.get("impairmentAccountId"));
            UUID impExpAcc = UUID.fromString((String) req.get("impairmentExpenseAccountId"));

            InventoryItem item = new InventoryItem(id, code, name, spec, unit, method,
                    invAcc, cogsAcc, lossGainAcc, impAcc, impExpAcc, true);

            try (Connection c = getConnection()) {
                inventoryService.createItem(c, scope, item);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", id.toString());
            resp.put("code", code);
            resp.put("name", name);
            resp.put("spec", spec);
            resp.put("unit", unit);
            resp.put("valuationMethod", method.name());
            resp.put("inventoryAccountId", invAcc.toString());
            resp.put("cogsAccountId", cogsAcc.toString());
            resp.put("lossGainAccountId", lossGainAcc.toString());
            resp.put("impairmentAccountId", impAcc.toString());
            resp.put("impairmentExpenseAccountId", impExpAcc.toString());
            resp.put("isActive", true);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/inventory/movements")
    public ResponseEntity<Map<String, Object>> recordMovement(
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "X-Tenant-Id", required = false) String hTenant,
            @RequestHeader(value = "X-Book-Id", required = false) String hBook,
            @RequestHeader(value = "X-Year-Id", required = false) String hYear) {
        try {
            Scope scope = resolveScope(req, hTenant, hBook, hYear);
            UUID id = UUID.randomUUID();
            String movementNo = (String) req.get("movementNo");
            MovementType type = MovementType.valueOf((String) req.get("movementType"));
            UUID itemId = UUID.fromString((String) req.get("itemId"));
            UUID srcWh = req.get("sourceWarehouseId") != null ? UUID.fromString((String) req.get("sourceWarehouseId")) : null;
            UUID destWh = req.get("destWarehouseId") != null ? UUID.fromString((String) req.get("destWarehouseId")) : null;
            UUID batchId = req.get("batchId") != null ? UUID.fromString((String) req.get("batchId")) : null;
            BigDecimal qty = new BigDecimal(req.get("quantity").toString());
            BigDecimal unitCost = new BigDecimal(req.get("unitCost").toString());
            BigDecimal totalCost = new BigDecimal(req.get("totalCost").toString());
            UUID sourceEvidenceId = req.get("sourceEvidenceId") != null ? UUID.fromString((String) req.get("sourceEvidenceId")) : null;
            LocalDate businessDate = LocalDate.parse((String) req.get("businessDate"));
            UUID createdBy = req.get("createdBy") != null ? UUID.fromString((String) req.get("createdBy")) : UUID.randomUUID();

            StockMovement m = new StockMovement(id, scope.tenantId(), scope.bookId(), scope.yearId(),
                    movementNo, type, itemId, srcWh, destWh, batchId, qty, unitCost, totalCost,
                    sourceEvidenceId, businessDate, "POSTED", null, createdBy, null);

            try (Connection c = getConnection()) {
                inventoryService.recordStockMovement(c, scope, m);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", id.toString());
            resp.put("movementNo", movementNo);
            resp.put("movementType", type.name());
            resp.put("itemId", itemId.toString());
            resp.put("sourceWarehouseId", srcWh != null ? srcWh.toString() : null);
            resp.put("destWarehouseId", destWh != null ? destWh.toString() : null);
            resp.put("batchId", batchId != null ? batchId.toString() : null);
            resp.put("quantity", qty.toPlainString());
            resp.put("unitCost", unitCost.toPlainString());
            resp.put("totalCost", totalCost.toPlainString());
            resp.put("sourceEvidenceId", sourceEvidenceId != null ? sourceEvidenceId.toString() : null);
            resp.put("businessDate", businessDate.toString());
            resp.put("status", "POSTED");
            resp.put("journalId", null);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/inventory/stocktake")
    public ResponseEntity<Map<String, Object>> createStocktake(
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "X-Tenant-Id", required = false) String hTenant,
            @RequestHeader(value = "X-Book-Id", required = false) String hBook,
            @RequestHeader(value = "X-Year-Id", required = false) String hYear) {
        try {
            Scope scope = resolveScope(req, hTenant, hBook, hYear);
            UUID sheetId = UUID.randomUUID();
            String sheetNo = (String) req.get("sheetNo");
            UUID warehouseId = UUID.fromString((String) req.get("warehouseId"));
            LocalDate cutoffDate = LocalDate.parse((String) req.get("cutoffDate"));
            UUID makerId = req.get("makerId") != null ? UUID.fromString((String) req.get("makerId")) : UUID.randomUUID();

            StocktakeSheet sheet = new StocktakeSheet(sheetId, warehouseId, cutoffDate, sheetNo, "COUNTED", makerId, null);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawLines = (List<Map<String, Object>>) req.get("lines");
            List<StocktakeLine> lines = new ArrayList<>();
            if (rawLines != null) {
                for (Map<String, Object> rl : rawLines) {
                    UUID lineId = UUID.randomUUID();
                    UUID itemId = UUID.fromString((String) rl.get("itemId"));
                    UUID batchId = rl.get("batchId") != null ? UUID.fromString((String) rl.get("batchId")) : null;
                    BigDecimal bookQty = new BigDecimal(rl.get("bookQuantity").toString());
                    BigDecimal bookUnitCost = new BigDecimal(rl.get("bookUnitCost").toString());
                    BigDecimal countedQty = new BigDecimal(rl.get("countedQuantity").toString());
                    BigDecimal varQty = countedQty.subtract(bookQty);
                    BigDecimal varAmt = varQty.multiply(bookUnitCost);
                    String varType = varQty.signum() > 0 ? "SURPLUS" : (varQty.signum() < 0 ? "DEFICIT" : "EQUAL");
                    String disp = (String) rl.get("disposition");

                    lines.add(new StocktakeLine(lineId, sheetId, itemId, batchId, bookQty, bookUnitCost, countedQty, varQty, varAmt, varType, disp));
                }
            }

            try (Connection c = getConnection()) {
                inventoryService.createStocktake(c, scope, sheet, lines);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", sheetId.toString());
            resp.put("sheetNo", sheetNo);
            resp.put("warehouseId", warehouseId.toString());
            resp.put("cutoffDate", cutoffDate.toString());
            resp.put("status", "COUNTED");
            resp.put("makerId", makerId.toString());
            resp.put("approverId", null);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/inventory/stocktake/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveStocktake(
            @PathVariable("id") String sheetIdStr,
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "X-Tenant-Id", required = false) String hTenant,
            @RequestHeader(value = "X-Book-Id", required = false) String hBook,
            @RequestHeader(value = "X-Year-Id", required = false) String hYear) {
        try {
            Scope scope = resolveScope(req, hTenant, hBook, hYear);
            UUID sheetId = UUID.fromString(sheetIdStr);
            UUID approverPersonId = UUID.fromString((String) req.get("approverPersonId"));
            UUID approverAccountId = UUID.fromString((String) req.get("approverAccountId"));
            Actor approver = new Actor(approverAccountId, approverPersonId);

            try (Connection c = getConnection()) {
                inventoryService.approveStocktake(c, scope, sheetId, approver);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", sheetId.toString());
            resp.put("status", "APPROVED");
            resp.put("approverId", approverPersonId.toString());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/assets/cip/projects")
    public ResponseEntity<Map<String, Object>> createCipProject(
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "X-Tenant-Id", required = false) String hTenant,
            @RequestHeader(value = "X-Book-Id", required = false) String hBook,
            @RequestHeader(value = "X-Year-Id", required = false) String hYear) {
        try {
            Scope scope = resolveScope(req, hTenant, hBook, hYear);
            UUID id = UUID.randomUUID();
            String code = (String) req.get("projectCode");
            String name = (String) req.get("projectName");
            BigDecimal cost = new BigDecimal(req.get("initialCost").toString());

            CipProject project = new CipProject(id, code, name, cost, "READY_FOR_USE");

            try (Connection c = getConnection()) {
                assetService.createCipProject(c, scope, project);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", id.toString());
            resp.put("projectCode", code);
            resp.put("projectName", name);
            resp.put("accumulatedCost", cost.toPlainString());
            resp.put("status", "READY_FOR_USE");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/assets/cip/capitalize")
    public ResponseEntity<Map<String, Object>> capitalizeCip(
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "X-Tenant-Id", required = false) String hTenant,
            @RequestHeader(value = "X-Book-Id", required = false) String hBook,
            @RequestHeader(value = "X-Year-Id", required = false) String hYear) {
        try {
            Scope scope = resolveScope(req, hTenant, hBook, hYear);
            UUID planId = UUID.randomUUID();
            UUID projectId = UUID.fromString((String) req.get("projectId"));
            BigDecimal capitalizedCost = new BigDecimal(req.get("capitalizedCost").toString());
            UUID makerId = UUID.fromString((String) req.get("makerId"));
            UUID approverId = UUID.fromString((String) req.get("approverId"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawCards = (List<Map<String, Object>>) req.get("cards");
            List<CipCapitalizationCard> cards = new ArrayList<>();
            if (rawCards != null) {
                for (Map<String, Object> rc : rawCards) {
                    cards.add(new CipCapitalizationCard(
                            (String) rc.get("name"),
                            (String) rc.get("classification"),
                            (String) rc.get("location"),
                            UUID.fromString((String) rc.get("custodian")),
                            new BigDecimal(rc.get("cost").toString()),
                            (String) rc.get("firstPeriod"),
                            ((Number) rc.get("months")).intValue(),
                            new BigDecimal(rc.get("residual").toString())
                    ));
                }
            }

            CipCapitalizationPlan plan = new CipCapitalizationPlan(planId, projectId, capitalizedCost, cards, makerId, approverId, "APPROVED");

            try (Connection c = getConnection()) {
                assetService.capitalizeCip(c, scope, plan, new Actor(UUID.randomUUID(), approverId));
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("planId", planId.toString());
            resp.put("status", "APPROVED");
            resp.put("capitalizedCost", capitalizedCost.toPlainString());
            resp.put("cardsCount", cards.size());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/assets/rd/capitalize")
    public ResponseEntity<Map<String, Object>> capitalizeRd(
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "X-Tenant-Id", required = false) String hTenant,
            @RequestHeader(value = "X-Book-Id", required = false) String hBook,
            @RequestHeader(value = "X-Year-Id", required = false) String hYear) {
        try {
            Scope scope = resolveScope(req, hTenant, hBook, hYear);
            UUID planId = UUID.randomUUID();
            UUID projectId = UUID.fromString((String) req.get("projectId"));
            String assetName = (String) req.get("intangibleAssetName");
            BigDecimal amount = new BigDecimal(req.get("capitalizedAmount").toString());
            UUID evidenceId = UUID.fromString((String) req.get("sourceEvidenceId"));
            UUID makerId = UUID.fromString((String) req.get("makerId"));
            UUID approverId = UUID.fromString((String) req.get("approverId"));

            RdCapitalizationPlan plan = new RdCapitalizationPlan(planId, projectId, assetName, amount, evidenceId, makerId, approverId, "APPROVED");

            try (Connection c = getConnection()) {
                assetService.capitalizeRd(c, scope, plan, new Actor(UUID.randomUUID(), approverId));
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("planId", planId.toString());
            resp.put("status", "APPROVED");
            resp.put("capitalizedAmount", amount.toPlainString());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/assets/impairment")
    public ResponseEntity<Map<String, Object>> recordAssetImpairment(
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "X-Tenant-Id", required = false) String hTenant,
            @RequestHeader(value = "X-Book-Id", required = false) String hBook,
            @RequestHeader(value = "X-Year-Id", required = false) String hYear) {
        try {
            Scope scope = resolveScope(req, hTenant, hBook, hYear);
            String regime = (String) req.get("regime");
            String period = (String) req.get("period");
            UUID cardId = UUID.fromString((String) req.get("assetCardId"));
            BigDecimal carrying = new BigDecimal(req.get("carryingAmount").toString());
            BigDecimal recoverable = new BigDecimal(req.get("recoverableAmount").toString());

            AssetImpairmentEvaluation eval = AssetDepreciationEngine.evaluateAssetImpairment(regime, cardId, period, carrying, recoverable);

            try (Connection c = getConnection()) {
                assetService.recordAssetImpairment(c, scope, eval);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", eval.id().toString());
            resp.put("assetCardId", cardId.toString());
            resp.put("regime", regime);
            resp.put("period", period);
            resp.put("carryingAmount", carrying.toPlainString());
            resp.put("recoverableAmount", recoverable.toPlainString());
            resp.put("impairmentAmount", eval.impairmentAmount().toPlainString());
            resp.put("isReversible", false);
            resp.put("status", "POSTED");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }
}
