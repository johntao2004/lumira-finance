package com.lumira.finance.inventory;

import static com.lumira.finance.inventory.InventoryModel.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class InventoryAssetsPostgresTest {
    static PGSimpleDataSource adminSource;
    static PGSimpleDataSource testSource;
    static String schemaName;
    static InventoryService inventoryService;
    static AssetExtensionService assetService;

    @BeforeAll
    static void setup() throws Exception {
        String url = System.getenv("FINANCE_TEST_JDBC_URL");
        if (url == null || url.isBlank()) {
            url = "jdbc:postgresql://127.0.0.1:15489/lumira_qa_finance_kernel";
        }
        if (url.contains("lumira_qa_finance_web") || url.contains("lumira_next")) {
            throw new IllegalStateException("PROTECTED_DATABASE_GUARD: Refusing to run tests on: " + url);
        }

        String user = System.getenv("FINANCE_TEST_DB_USER");
        String pass = System.getenv("FINANCE_TEST_DB_PASSWORD");

        if (user == null || pass == null) {
            // Read from .runtime/finance-qa.json
            for (String p : List.of(".runtime/finance-qa.json", "../../../.runtime/finance-qa.json", "../../../../.runtime/finance-qa.json")) {
                Path path = Path.of(p);
                if (Files.exists(path)) {
                    String json = Files.readString(path).replace("\uFEFF", "");
                    Matcher mUser = Pattern.compile("\"LUMIRA_DB_USER\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                    Matcher mPass = Pattern.compile("\"LUMIRA_DB_PASSWORD\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                    if (mUser.find() && mPass.find()) {
                        user = mUser.group(1);
                        pass = mPass.group(1);
                        break;
                    }
                }
            }
        }

        if (user == null || pass == null) {
            throw new IllegalStateException("Explicit finance QA credentials required");
        }

        adminSource = new PGSimpleDataSource();
        adminSource.setURL(url);
        adminSource.setUser(user);
        adminSource.setPassword(pass);

        // Unique test schema per lane W08 specification
        schemaName = "test_w08_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection conn = adminSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA " + schemaName);
        }

        testSource = new PGSimpleDataSource();
        testSource.setURL(url);
        testSource.setUser(user);
        testSource.setPassword(pass);
        testSource.setCurrentSchema(schemaName);

        // Run candidate migration
        Path migrationPath = java.util.List.of(
            Path.of("plugins/finance/migrations/V33__inventory_and_asset_subledgers.sql"),
            Path.of("../migrations/V33__inventory_and_asset_subledgers.sql"),
            Path.of(".runtime/v4-wave2/inventory-assets/migration-candidate.sql"),
            Path.of("../../../.runtime/v4-wave2/inventory-assets/migration-candidate.sql"),
            Path.of("migration-candidate.sql")
        ).stream().filter(Files::exists).findFirst().orElseThrow();

        String sql = Files.readString(migrationPath);
        try (Connection conn = testSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }

        inventoryService = new InventoryService();
        assetService = new AssetExtensionService();
    }

    @AfterAll
    static void cleanup() {
        if (schemaName != null && schemaName.startsWith("test_w08_")) {
            try (Connection conn = adminSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
            } catch (Exception e) {
                System.err.println("Failed to drop schema " + schemaName + ": " + e.getMessage());
            }
        }
    }

    @Test
    void testInventoryMasterDataAndPurchaseMovement() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();
        Scope scope = new Scope(tenantId, bookId, yearId);

        UUID wId = UUID.randomUUID();
        Warehouse w = new Warehouse(wId, "WH-MAIN", "Main Central Warehouse", "Building A", true);

        UUID itemId = UUID.randomUUID();
        InventoryItem item = new InventoryItem(
                itemId, "ITEM-001", "Electronic Component", "Rev B", "PCS",
                ValuationMethod.FIFO,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), true
        );

        try (Connection c = testSource.getConnection()) {
            inventoryService.createWarehouse(c, scope, w);
            inventoryService.createItem(c, scope, item);

            // Purchase Inbound requires source evidence binding
            UUID evidenceId = UUID.randomUUID();
            UUID batchId = UUID.randomUUID();
            StockMovement in = new StockMovement(
                    UUID.randomUUID(), tenantId, bookId, yearId,
                    "IN-PO-1001", MovementType.PURCHASE_IN, itemId, null, wId, batchId,
                    new BigDecimal("100.00000000"), new BigDecimal("25.50000000"), new BigDecimal("2550.00000000"),
                    evidenceId, LocalDate.of(2026, 2, 1), "POSTED", null, UUID.randomUUID(), null
            );
            inventoryService.recordStockMovement(c, scope, in);

            BigDecimal stock = inventoryService.getWarehouseStock(c, scope, wId, itemId);
            assertEquals(new BigDecimal("100.00000000"), stock);

            // Outbound 30 units consuming that batch
            StockMovement out = new StockMovement(
                    UUID.randomUUID(), tenantId, bookId, yearId,
                    "OUT-SO-2001", MovementType.SALES_OUT, itemId, wId, null, batchId,
                    new BigDecimal("30.00000000"), new BigDecimal("25.50000000"), new BigDecimal("765.00000000"),
                    null, LocalDate.of(2026, 2, 2), "POSTED", null, UUID.randomUUID(), null
            );
            inventoryService.recordStockMovement(c, scope, out);

            // Warehouse stock is now 70
            assertEquals(new BigDecimal("70.00000000"), inventoryService.getWarehouseStock(c, scope, wId, itemId));

            // Verify batch remaining quantity in DB is exactly 70.00000000
            try (var ps = c.prepareStatement("SELECT remaining_quantity FROM plugin_finance_inventory_batch WHERE id = ?")) {
                ps.setObject(1, batchId);
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(new BigDecimal("70.00000000"), rs.getBigDecimal(1));
                }
            }

            // Overdrafting batch beyond remaining quantity (70) throws INSUFFICIENT_BATCH_QUANTITY
            StockMovement overdraft = new StockMovement(
                    UUID.randomUUID(), tenantId, bookId, yearId,
                    "OUT-SO-OVER", MovementType.SALES_OUT, itemId, wId, null, batchId,
                    new BigDecimal("80.00000000"), new BigDecimal("25.50000000"), new BigDecimal("2040.00000000"),
                    null, LocalDate.of(2026, 2, 3), "POSTED", null, UUID.randomUUID(), null
            );
            assertThrows(IllegalArgumentException.class, () ->
                    inventoryService.recordStockMovement(c, scope, overdraft));
        }
    }

    @Test
    void testTransferConservesTotalQuantity() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();
        Scope scope = new Scope(tenantId, bookId, yearId);

        UUID w1 = UUID.randomUUID();
        UUID w2 = UUID.randomUUID();
        Warehouse wh1 = new Warehouse(w1, "WH-EAST", "East Warehouse", "Floor 1", true);
        Warehouse wh2 = new Warehouse(w2, "WH-WEST", "West Warehouse", "Floor 2", true);

        UUID itemId = UUID.randomUUID();
        InventoryItem item = new InventoryItem(
                itemId, "ITEM-TRANSFER", "Transfer Item", "Std", "KG",
                ValuationMethod.MOVING_WEIGHTED_AVERAGE,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), true
        );

        try (Connection c = testSource.getConnection()) {
            inventoryService.createWarehouse(c, scope, wh1);
            inventoryService.createWarehouse(c, scope, wh2);
            inventoryService.createItem(c, scope, item);

            // Inbound 100 to WH-EAST
            StockMovement in = new StockMovement(
                    UUID.randomUUID(), tenantId, bookId, yearId,
                    "IN-INIT", MovementType.PURCHASE_IN, itemId, null, w1, null,
                    new BigDecimal("100.00000000"), new BigDecimal("10.00000000"), new BigDecimal("1000.00000000"),
                    UUID.randomUUID(), LocalDate.of(2026, 2, 5), "POSTED", null, UUID.randomUUID(), null
            );
            inventoryService.recordStockMovement(c, scope, in);

            // Transfer 40 from WH-EAST to WH-WEST
            StockMovement transfer = new StockMovement(
                    UUID.randomUUID(), tenantId, bookId, yearId,
                    "TR-001", MovementType.TRANSFER, itemId, w1, w2, null,
                    new BigDecimal("40.00000000"), new BigDecimal("10.00000000"), new BigDecimal("400.00000000"),
                    null, LocalDate.of(2026, 2, 10), "POSTED", null, UUID.randomUUID(), null
            );
            inventoryService.recordStockMovement(c, scope, transfer);

            BigDecimal stockEast = inventoryService.getWarehouseStock(c, scope, w1, itemId);
            BigDecimal stockWest = inventoryService.getWarehouseStock(c, scope, w2, itemId);
            BigDecimal totalStock = inventoryService.getTotalBookStock(c, scope, itemId);

            assertEquals(new BigDecimal("60.00000000"), stockEast);
            assertEquals(new BigDecimal("40.00000000"), stockWest);
            // Invariant: Total across tenant/book remains exactly 100.00000000 (conserved)
            assertEquals(new BigDecimal("100.00000000"), totalStock);
        }
    }

    @Test
    void testInsufficientStockThrows() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();
        Scope scope = new Scope(tenantId, bookId, yearId);

        UUID wId = UUID.randomUUID();
        Warehouse wh = new Warehouse(wId, "WH-STOCK", "Stock Warehouse", "Zone A", true);
        UUID itemId = UUID.randomUUID();
        InventoryItem item = new InventoryItem(
                itemId, "ITEM-LIMITED", "Limited Item", "Std", "BOX",
                ValuationMethod.FIFO,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), true
        );

        try (Connection c = testSource.getConnection()) {
            inventoryService.createWarehouse(c, scope, wh);
            inventoryService.createItem(c, scope, item);

            // Outbound without stock -> throws
            StockMovement out = new StockMovement(
                    UUID.randomUUID(), tenantId, bookId, yearId,
                    "OUT-OVERDRAFT", MovementType.SALES_OUT, itemId, wId, null, null,
                    new BigDecimal("50.00000000"), new BigDecimal("10.00000000"), new BigDecimal("500.00000000"),
                    null, LocalDate.of(2026, 2, 15), "SUBMITTED", null, UUID.randomUUID(), null
            );
            assertThrows(IllegalArgumentException.class, () ->
                    inventoryService.recordStockMovement(c, scope, out));
        }
    }

    @Test
    void testStocktakeVarianceAndIndependentApproval() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();
        Scope scope = new Scope(tenantId, bookId, yearId);

        UUID wId = UUID.randomUUID();
        inventoryService.createWarehouse(c(), scope, new Warehouse(wId, "WH-COUNT", "Count Warehouse", "Zone B", true));
        UUID itemId = UUID.randomUUID();
        inventoryService.createItem(c(), scope, new InventoryItem(
                itemId, "ITEM-COUNT", "Item for Count", "Std", "UNIT",
                ValuationMethod.FIFO,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), true
        ));

        UUID sheetId = UUID.randomUUID();
        UUID makerId = UUID.randomUUID();
        StocktakeSheet sheet = new StocktakeSheet(
                sheetId, wId, LocalDate.of(2026, 2, 28), "ST-202602-01", "COUNTED", makerId, null
        );

        StocktakeLine line = new StocktakeLine(
                UUID.randomUUID(), sheetId, itemId, null,
                new BigDecimal("100.00000000"), new BigDecimal("15.00000000"),
                new BigDecimal("98.00000000"), new BigDecimal("-2.00000000"),
                new BigDecimal("-30.00000000"), "DEFICIT", "MANAGEMENT_EXPENSE"
        );

        try (Connection conn = testSource.getConnection()) {
            inventoryService.createStocktake(conn, scope, sheet, List.of(line));

            // Same person self-approval is rejected by SoD rule
            Actor selfApprover = new Actor(UUID.randomUUID(), makerId);
            assertThrows(IllegalArgumentException.class, () ->
                    inventoryService.approveStocktake(conn, scope, sheetId, selfApprover));

            // Independent person approval succeeds
            Actor independentApprover = new Actor(UUID.randomUUID(), UUID.randomUUID());
            inventoryService.approveStocktake(conn, scope, sheetId, independentApprover);
        }
    }

    @Test
    void testCipAccumulationAndMultiCardCapitalization() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();
        Scope scope = new Scope(tenantId, bookId, yearId);

        UUID projectId = UUID.randomUUID();
        CipProject project = new CipProject(
                projectId, "CIP-FACTORY-1", "Factory Expansion Phase 1",
                new BigDecimal("100000.00000000"), "READY_FOR_USE"
        );

        try (Connection conn = testSource.getConnection()) {
            assetService.createCipProject(conn, scope, project);

            // Add additional engineering cost 20000.00000000
            assetService.addCipCost(conn, scope, projectId, new BigDecimal("20000.00000000"));

            // Capitalize into 2 asset cards: Building (80000) + HVAC (40000) = 120000
            UUID makerId = UUID.randomUUID();
            UUID approverId = UUID.randomUUID();
            CipCapitalizationPlan plan = new CipCapitalizationPlan(
                    UUID.randomUUID(), projectId, new BigDecimal("120000.00000000"),
                    List.of(
                            new CipCapitalizationCard("Building 1", "BUILDING", "Site A", UUID.randomUUID(), new BigDecimal("80000.00000000"), "2026-03", 240, new BigDecimal("4000.00000000")),
                            new CipCapitalizationCard("HVAC System", "EQUIPMENT", "Site A", UUID.randomUUID(), new BigDecimal("40000.00000000"), "2026-03", 120, new BigDecimal("2000.00000000"))
                    ),
                    makerId, approverId, "APPROVED"
            );

            // Self-approval rejected
            assertThrows(IllegalArgumentException.class, () ->
                    assetService.capitalizeCip(conn, scope, plan, new Actor(UUID.randomUUID(), makerId)));

            // Independent approver succeeds
            assetService.capitalizeCip(conn, scope, plan, new Actor(UUID.randomUUID(), approverId));

            // Verify cards_payload in DB actually contains Building 1 and HVAC System
            try (var ps = conn.prepareStatement("SELECT cards_payload::text, cards_count FROM plugin_finance_asset_cip_capitalization WHERE id = ?")) {
                ps.setObject(1, plan.id());
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    String json = rs.getString(1);
                    assertEquals(2, rs.getInt(2));
                    assertTrue(json.contains("Building 1"), "Must serialize Building 1");
                    assertTrue(json.contains("HVAC System"), "Must serialize HVAC System");
                }
            }
        }
    }

    @Test
    void testRdStatutoryCapitalization() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();
        Scope scope = new Scope(tenantId, bookId, yearId);

        UUID projectId = UUID.randomUUID();
        RdProject rdProject = new RdProject(
                projectId, "RD-AI-ENGINE", "AI Accounting Engine", "DEVELOPMENT",
                new BigDecimal("30000.00000000"), new BigDecimal("70000.00000000"), "ONGOING"
        );

        try (Connection conn = testSource.getConnection()) {
            assetService.createRdProject(conn, scope, rdProject);

            UUID evidenceId = UUID.randomUUID();
            UUID makerId = UUID.randomUUID();
            UUID approverId = UUID.randomUUID();
            RdCapitalizationPlan plan = new RdCapitalizationPlan(
                    UUID.randomUUID(), projectId, "Core AI Software License",
                    new BigDecimal("70000.00000000"), evidenceId, makerId, approverId, "APPROVED"
            );

            // Capitalize to Intangible Asset
            assetService.capitalizeRd(conn, scope, plan, new Actor(UUID.randomUUID(), approverId));
        }
    }

    @Test
    void testCas8AssetImpairmentNonReversibleInDb() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();
        Scope scope = new Scope(tenantId, bookId, yearId);

        UUID cardId = UUID.randomUUID();
        AssetImpairmentEvaluation eval = new AssetImpairmentEvaluation(
                UUID.randomUUID(), cardId, "CAS", "2026-06",
                new BigDecimal("50000.00000000"), new BigDecimal("40000.00000000"),
                new BigDecimal("10000.00000000"), false, "POSTED"
        );

        try (Connection conn = testSource.getConnection()) {
            assetService.recordAssetImpairment(conn, scope, eval);

            // Attempting to record reversible impairment throws exception
            AssetImpairmentEvaluation illegalReversible = new AssetImpairmentEvaluation(
                    UUID.randomUUID(), cardId, "CAS", "2026-07",
                    new BigDecimal("50000.00000000"), new BigDecimal("40000.00000000"),
                    new BigDecimal("10000.00000000"), true, "POSTED"
            );
            assertThrows(IllegalStateException.class, () ->
                    assetService.recordAssetImpairment(conn, scope, illegalReversible));

            // Attempting impairment on SMALL regime throws
            AssetImpairmentEvaluation smallEval = new AssetImpairmentEvaluation(
                    UUID.randomUUID(), cardId, "SMALL", "2026-06",
                    new BigDecimal("50000.00000000"), new BigDecimal("40000.00000000"),
                    new BigDecimal("10000.00000000"), false, "POSTED"
            );
            assertThrows(IllegalArgumentException.class, () ->
                    assetService.recordAssetImpairment(conn, scope, smallEval));
        }
    }

    @Test
    void testImmutableTriggerPreventsDeletionOfPostedMovements() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();
        Scope scope = new Scope(tenantId, bookId, yearId);

        UUID wId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        inventoryService.createWarehouse(c(), scope, new Warehouse(wId, "WH-IMM", "Imm Warehouse", "Zone C", true));
        inventoryService.createItem(c(), scope, new InventoryItem(
                itemId, "ITEM-IMM", "Imm Item", "Std", "PC",
                ValuationMethod.FIFO,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), true
        ));

        UUID movId = UUID.randomUUID();
        StockMovement in = new StockMovement(
                movId, tenantId, bookId, yearId,
                "IN-IMM-1", MovementType.PURCHASE_IN, itemId, null, wId, null,
                new BigDecimal("10.00000000"), new BigDecimal("5.00000000"), new BigDecimal("50.00000000"),
                UUID.randomUUID(), LocalDate.of(2026, 3, 1), "POSTED", null, UUID.randomUUID(), null
        );

        try (Connection conn = testSource.getConnection()) {
            inventoryService.recordStockMovement(conn, scope, in);

            // Attempting to delete a POSTED movement triggers database trigger error
            try (Statement s = conn.createStatement()) {
                assertThrows(Exception.class, () ->
                        s.execute("DELETE FROM plugin_finance_inventory_movement WHERE id = '" + movId + "'"));
            }
        }
    }

    @Test
    void testInventoryRestControllerFullEndpoints() throws Exception {
        InventoryRestController controller = new InventoryRestController(testSource);
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();

        // 1. Create warehouse via controller
        var whReq = Map.<String, Object>of(
                "code", "WH-CTRL",
                "name", "Controller Warehouse",
                "location", "Bay 1",
                "tenantId", tenantId.toString(),
                "bookId", bookId.toString(),
                "yearId", yearId.toString()
        );
        var whResp = controller.createWarehouse(whReq, null, null, null);
        assertEquals(200, whResp.getStatusCode().value());
        UUID whId = UUID.fromString((String) whResp.getBody().get("id"));

        // 2. Create item via controller
        var itemReq = mapOf(
                "code", "ITEM-CTRL",
                "name", "Controller Item",
                "spec", "V1",
                "unit", "PCS",
                "valuationMethod", "FIFO",
                "inventoryAccountId", UUID.randomUUID().toString(),
                "cogsAccountId", UUID.randomUUID().toString(),
                "lossGainAccountId", UUID.randomUUID().toString(),
                "impairmentAccountId", UUID.randomUUID().toString(),
                "impairmentExpenseAccountId", UUID.randomUUID().toString(),
                "tenantId", tenantId.toString(),
                "bookId", bookId.toString(),
                "yearId", yearId.toString()
        );
        var itemResp = controller.createItem(itemReq, null, null, null);
        assertEquals(200, itemResp.getStatusCode().value());
        UUID itemId = UUID.fromString((String) itemResp.getBody().get("id"));

        // 3. Record movement via controller
        var movReq = mapOf(
                "movementNo", "IN-CTRL-01",
                "movementType", "PURCHASE_IN",
                "itemId", itemId.toString(),
                "destWarehouseId", whId.toString(),
                "quantity", "50.00000000",
                "unitCost", "10.00000000",
                "totalCost", "500.00000000",
                "sourceEvidenceId", UUID.randomUUID().toString(),
                "businessDate", "2026-03-01",
                "tenantId", tenantId.toString(),
                "bookId", bookId.toString(),
                "yearId", yearId.toString()
        );
        var movResp = controller.recordMovement(movReq, null, null, null);
        assertEquals(200, movResp.getStatusCode().value());
        assertEquals("50.00000000", movResp.getBody().get("quantity"));

        // 4. Preview depreciation
        var deprReq = Map.<String, Object>of(
                "method", "DOUBLE_DECLINING_BALANCE",
                "startPeriod", "2026-01",
                "totalMonths", 60,
                "originalCost", "50000.00",
                "residualValue", "2000.00"
        );
        var deprResp = controller.previewDepreciation(deprReq);
        assertEquals(200, deprResp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) deprResp.getBody().get("rows");
        assertEquals(60, rows.size());

        // 5. Evaluate inventory impairment
        var impReq = Map.<String, Object>of(
                "regime", "CAS",
                "period", "2026-03",
                "itemId", itemId.toString(),
                "bookCost", "500.00000000",
                "nrv", "400.00000000"
        );
        var impResp = controller.evaluateInventoryImpairment(impReq);
        assertEquals(200, impResp.getStatusCode().value());
        assertEquals("100.00000000", impResp.getBody().get("newImpairment"));
    }

    private static Map<String, Object> mapOf(Object... kvs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) {
            map.put((String) kvs[i], kvs[i + 1]);
        }
        return map;
    }

    private static Connection c() throws Exception {
        return testSource.getConnection();
    }
}
