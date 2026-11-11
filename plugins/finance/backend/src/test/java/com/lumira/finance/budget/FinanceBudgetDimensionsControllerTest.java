package com.lumira.finance.budget;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.LocalDate;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Controller unit tests verifying REST response wrapping, status codes, and DTO contracts.
 */
public class FinanceBudgetDimensionsControllerTest {

    // Lightweight mock DataSource
    static class DummyDataSource implements DataSource {
        @Override public Connection getConnection() { return null; }
        @Override public Connection getConnection(String u, String p) { return null; }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    // Subclassed service to mock responses without database
    static class MockService extends FinanceBudgetDimensionsService {
        FinanceBudgetDimensionsInput.AccountRuleDto lastRule;

        MockService() {
            super(new DummyDataSource(), new FinanceBudgetDimensionsStore());
        }

        @Override
        public Optional<FinanceBudgetDimensionsInput.AccountRuleDto> findAccountRule(UUID t, UUID b, UUID y, UUID a) {
            return Optional.ofNullable(lastRule);
        }

        @Override
        public void configureAccountRule(FinanceBudgetDimensionsInput.AccountRuleDto rule) {
            this.lastRule = rule;
        }

        @Override
        public FinanceBudgetDimensionsInput.AllocationExecutionResult executeAllocation(
                FinanceBudgetDimensionsInput.AllocationExecutionRequest req) {
            return new FinanceBudgetDimensionsInput.AllocationExecutionResult(
                    req.ruleId(),
                    req.totalAmount(),
                    req.scale(),
                    List.of(new FinanceBudgetDimensionsInput.AllocatedItemResult(UUID.randomUUID(), "1.000000000000", req.totalAmount()))
            );
        }

        @Override
        public UUID rollbackConsumeBudget(UUID t, UUID b, UUID y, FinanceBudgetDimensionsInput.BudgetRollbackConsumptionRequest req) {
            return UUID.randomUUID();
        }

        @Override
        public Optional<FinanceBudgetDimensionsInput.BudgetControlLedgerDto> findControlLedger(
                UUID t, UUID b, UUID y, int period, String code, UUID dept, UUID proj, UUID cc) {
            return Optional.of(new FinanceBudgetDimensionsInput.BudgetControlLedgerDto(
                    UUID.randomUUID(), t, b, y, period, code, dept, proj, cc,
                    "100000.00000000", "0.00000000", "15000.00000000", "85000.00000000", null
            ));
        }
    }

    @Test
    void testAccountRuleControllerFlow() throws SQLException {
        MockService service = new MockService();
        FinanceBudgetDimensionsController controller = new FinanceBudgetDimensionsController(service);

        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        // 404 when not configured
        ResponseEntity<FinanceBudgetDimensionsInput.AccountRuleDto> r404 =
                controller.getAccountRule(tenantId, bookId, yearId, accountId);
        assertEquals(HttpStatus.NOT_FOUND, r404.getStatusCode());

        // Configure rule
        FinanceBudgetDimensionsInput.AccountRuleDto rule = new FinanceBudgetDimensionsInput.AccountRuleDto(
                tenantId, bookId, yearId, accountId, "6602", "CAS_2006",
                true, true, false, false, false, true, "CNY_ONLY", "ACTIVE", 1, null
        );

        ResponseEntity<Map<String, Object>> rCreate = controller.configureAccountRule(rule);
        assertEquals(HttpStatus.CREATED, rCreate.getStatusCode());
        assertEquals("CONFIGURED", rCreate.getBody().get("status"));

        // Now found
        ResponseEntity<FinanceBudgetDimensionsInput.AccountRuleDto> rFound =
                controller.getAccountRule(tenantId, bookId, yearId, accountId);
        assertEquals(HttpStatus.OK, rFound.getStatusCode());
        assertEquals("6602", rFound.getBody().accountCode());
    }

    @Test
    void testAllocationExecutionControllerFlow() throws SQLException {
        MockService service = new MockService();
        FinanceBudgetDimensionsController controller = new FinanceBudgetDimensionsController(service);

        UUID ruleId = UUID.randomUUID();
        FinanceBudgetDimensionsInput.AllocationExecutionRequest req =
                new FinanceBudgetDimensionsInput.AllocationExecutionRequest(ruleId, "50000.00", 2);

        ResponseEntity<FinanceBudgetDimensionsInput.AllocationExecutionResult> res = controller.executeAllocation(req);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("50000.00", res.getBody().totalAmount());
        assertEquals(1, res.getBody().allocatedTargets().size());
    }

    @Test
    void testBudgetRollbackAndControlLedgerControllerFlow() throws SQLException {
        MockService service = new MockService();
        FinanceBudgetDimensionsController controller = new FinanceBudgetDimensionsController(service);

        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();

        // Query control ledger
        ResponseEntity<FinanceBudgetDimensionsInput.BudgetControlLedgerDto> rLedger =
                controller.getControlLedger(tenantId, bookId, yearId, 1, "6602", null, null, null);
        assertEquals(HttpStatus.OK, rLedger.getStatusCode());
        assertEquals("85000.00000000", rLedger.getBody().availableAmount());

        // Rollback consume
        FinanceBudgetDimensionsInput.BudgetRollbackConsumptionRequest rollbackReq =
                new FinanceBudgetDimensionsInput.BudgetRollbackConsumptionRequest(
                        1, "6602", null, null, null, "5000.00000000",
                        "ACCOUNTING_REVERSAL", UUID.randomUUID(), UUID.randomUUID(), "Voucher reversal"
                );
        ResponseEntity<Map<String, Object>> rRollback =
                controller.rollbackConsume(tenantId, bookId, yearId, rollbackReq);
        assertEquals(HttpStatus.OK, rRollback.getStatusCode());
        assertEquals("ROLLBACK_CONSUMED", rRollback.getBody().get("status"));
    }
}
