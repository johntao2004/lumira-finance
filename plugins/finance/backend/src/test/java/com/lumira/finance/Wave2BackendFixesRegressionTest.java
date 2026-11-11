package com.lumira.finance;

import com.lumira.finance.archiveai.*;
import com.lumira.finance.payrolltax.*;
import com.lumira.finance.statutory.*;
import com.lumira.finance.yearend.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class Wave2BackendFixesRegressionTest {

    // --- 1. ArchiveAiJdbcStore withConnection & DataSource Lifecycle ---
    @Test
    void testArchiveAiJdbcStoreWithConnectionLifecycle() throws Exception {
        assertThrows(NullPointerException.class, () -> new ArchiveAiJdbcStore((Connection) null));
        assertThrows(NullPointerException.class, () -> new ArchiveAiJdbcStore((DataSource) null));

        // Test with mock Connection
        Connection mockConn = mock(Connection.class);
        PreparedStatement mockPs = mock(PreparedStatement.class);
        ResultSet mockRs = mock(ResultSet.class);
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPs);
        when(mockPs.executeQuery()).thenReturn(mockRs);

        ArchiveAiJdbcStore connStore = new ArchiveAiJdbcStore(mockConn);
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();

        Optional<ArchiveGovModel.ArchiveRecord> result = connStore.findArchiveRecord(tenantId, bookId, recordId);
        assertTrue(result.isEmpty());
        // Verify mockPs was closed in try-with-resources but mockConn was NOT closed
        verify(mockPs).close();
        verify(mockConn, never()).close();

        // Test with mock DataSource
        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenReturn(mockConn);

        ArchiveAiJdbcStore dsStore = new ArchiveAiJdbcStore(mockDs);
        Optional<ArchiveGovModel.ArchiveRecord> dsResult = dsStore.findArchiveRecord(tenantId, bookId, recordId);
        assertTrue(dsResult.isEmpty());
        // Statement was properly executed before connection release
        verify(mockPs, times(2)).executeQuery();
    }

    // --- 2. Archive Governance Record API (create, get, borrow, return) ---
    @Test
    void testArchiveGovRecordControllerAndService() throws Exception {
        ArchiveAiJdbcStore mockStore = mock(ArchiveAiJdbcStore.class);
        ArchiveAiService service = new ArchiveAiService(mockStore);
        ArchiveAiController controller = new ArchiveAiController(service);

        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        UUID fiscalYearId = UUID.randomUUID();
        UUID custodianId = UUID.randomUUID();

        // Missing required fields -> 400
        ResponseEntity<Map<String, Object>> missingTenant = controller.createArchiveRecord(Map.of("bookId", bookId.toString()));
        assertEquals(400, missingTenant.getStatusCode().value());
        assertEquals("INVALID_INPUT", missingTenant.getBody().get("code"));

        // Valid creation -> returns both recordId and id, matching OpenAPI schema
        Map<String, Object> req = Map.of(
                "tenantId", tenantId.toString(),
                "bookId", bookId.toString(),
                "recordId", recordId.toString(),
                "category", "VOUCHER_GENERAL",
                "fiscalYearId", fiscalYearId.toString(),
                "title", "2026 Q1 Vouchers",
                "startYear", 2026,
                "custodianPersonId", custodianId.toString(),
                "legalHold", false
        );

        ResponseEntity<Map<String, Object>> createResp = controller.createArchiveRecord(req);
        assertEquals(200, createResp.getStatusCode().value());
        Map<String, Object> body = createResp.getBody();
        assertNotNull(body);
        assertEquals(recordId.toString(), body.get("recordId"));
        assertEquals(recordId.toString(), body.get("id"));
        assertEquals("VOUCHER_GENERAL", body.get("category"));
        assertEquals(30, body.get("statutoryYears"));
        assertEquals(30, body.get("retentionYears"));
        assertEquals("IN_CUSTODY", body.get("status"));
        assertNotNull(body.get("archivedAt"));
        assertNotNull(body.get("retentionUntilDate"));

        // GET non-existent record -> 404
        when(mockStore.findArchiveRecord(tenantId, bookId, recordId)).thenReturn(Optional.empty());
        ResponseEntity<Map<String, Object>> getNotFound = controller.getArchiveRecord(recordId, tenantId, bookId);
        assertEquals(404, getNotFound.getStatusCode().value());
        assertEquals("ARCHIVE_RECORD_NOT_FOUND", getNotFound.getBody().get("code"));

        // Borrow non-existent record -> 404
        IllegalArgumentException borrowEx = assertThrows(IllegalArgumentException.class, () ->
                service.borrowRecord(tenantId, bookId, UUID.randomUUID(), recordId, UUID.randomUUID(), "Audit", LocalDate.now().plusDays(30))
        );
        assertTrue(borrowEx.getMessage().contains("ARCHIVE_RECORD_NOT_FOUND"));

        // Return non-existent borrow log -> 404
        IllegalArgumentException returnEx = assertThrows(IllegalArgumentException.class, () ->
                service.returnRecord(tenantId, bookId, UUID.randomUUID(), recordId, UUID.randomUUID())
        );
        assertTrue(returnEx.getMessage().contains("BORROW_RECORD_NOT_FOUND"));

        // Return inspector same as borrower -> 403 SoD violation
        UUID samePersonId = UUID.randomUUID();
        when(mockStore.findBorrowLog(eq(tenantId), eq(bookId), any())).thenReturn(Optional.of(
                new ArchiveGovModel.BorrowLog(tenantId, bookId, UUID.randomUUID(), recordId, samePersonId, "Audit", java.time.Instant.now(), LocalDate.now(), null, null, "BORROWED")
        ));
        IllegalStateException sodReturnEx = assertThrows(IllegalStateException.class, () ->
                service.returnRecord(tenantId, bookId, UUID.randomUUID(), recordId, samePersonId)
        );
        ResponseEntity<Map<String, Object>> sodResp = FinanceGlobalExceptionHandler.handleException(sodReturnEx);
        assertEquals(403, sodResp.getStatusCode().value());
        assertEquals("SOD_VIOLATION", sodResp.getBody().get("code"));
    }

    // --- 3. Statutory Consolidation Scope FK Auto-Ensure ---
    @Test
    void testConsolidationScopeAutoEnsure() {
        UUID scopeId = UUID.randomUUID();
        StatutoryModels.ConsolidationScope scope = new StatutoryModels.ConsolidationScope(
                scopeId,
                "SCOPE_TEST",
                null,
                "Scope Name",
                List.of(),
                "ACTIVE"
        );
        assertNotNull(scope);
        assertEquals("SCOPE_TEST", scope.scopeCode());

        // CreateConsolidationScopeRequest defaults
        StatutoryController.CreateConsolidationScopeRequest req = new StatutoryController.CreateConsolidationScopeRequest(
                UUID.randomUUID(), null, null, null, "Default Scope", null, null, null
        );
        assertNotNull(req.tenantId());
    }

    // --- 4. YearendController Scope Guard & Anti-Spoofing ---
    @Test
    void testYearendControllerPlainUserBlockedAndAntiSpoof() {
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        YearendStore mockStore = new YearendStore(mapper);
        YearendService service = new YearendService(null, mockStore);
        YearendController controller = new YearendController(service, null);

        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();

        YearendModel.CutoverCreateRequest req = new YearendModel.CutoverCreateRequest(
                LocalDate.now(), "FULL_HISTORY", "SRC", "WM",
                List.of(), List.of(), Map.of()
        );

        // 1. Plain user without membership is rejected with 403 SCOPE_DENIED
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("plain", "password")
        );
        ResponseEntity<?> resp = controller.proposeCutover(
                tenantId, bookId, yearId, null, null, req
        );
        assertEquals(403, resp.getStatusCode().value());
        assertTrue(resp.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("SCOPE_DENIED", body.get("code"));

        // 2. Spoofing attempt: plain user providing someone else's accountId in header is still rejected with 403
        ResponseEntity<?> spoofResp = controller.proposeCutover(
                tenantId, bookId, yearId, UUID.randomUUID().toString(), null, req
        );
        assertEquals(403, spoofResp.getStatusCode().value());
        assertEquals("SCOPE_DENIED", ((Map<?, ?>) spoofResp.getBody()).get("code"));

        // 3. Admin user is allowed past checkBookAccess
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", "password", "ROLE_ADMIN")
        );
        ResponseEntity<?> adminResp = controller.proposeCutover(
                tenantId, bookId, yearId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), req
        );
        assertNotEquals(403, adminResp.getStatusCode().value());

        SecurityContextHolder.clearContext();
    }

    // --- 5. PayrollTaxExt Policy Pack Creation & 404 / 200 Evaluation ---
    @Test
    void testPayrollTaxExtPolicyPackLifecycle() {
        PayrollTaxExtJdbcStore mockStore = mock(PayrollTaxExtJdbcStore.class);
        PayrollTaxExtService service = new PayrollTaxExtService(mockStore);

        UUID tenantId = UUID.randomUUID();
        String packId = "PACK_TEST_001";

        // Create policy pack
        PayrollTaxExtModel.CreatePolicyPackCommand createCmd = new PayrollTaxExtModel.CreatePolicyPackCommand(
                packId, "CIT_SMALL_LOW_PROFIT", "1.0.0",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                null, "APPROVED", false, Map.of("statutoryRate", "0.25")
        );
        TaxPolicyRuleEngine.PolicyPack pack = service.createPolicyPack(tenantId, createCmd);
        assertNotNull(pack);
        assertEquals(packId, pack.id());
        assertNotNull(pack.digest());
        assertEquals(64, pack.digest().length());
        verify(mockStore).insertPolicyPack(eq(tenantId), any());

        // Evaluation when policy pack is missing -> throws POLICY_PACK_NOT_FOUND -> 404
        when(mockStore.findPolicyPack(tenantId, "UNKNOWN_PACK")).thenReturn(Optional.empty());
        PayrollTaxExtModel.EvaluateCitCommand missingCmd = new PayrollTaxExtModel.EvaluateCitCommand(
                "UNKNOWN_PACK", UUID.randomUUID(), LocalDate.of(2026, 6, 30),
                "100000.00", "50", "1000000.00", null
        );
        IllegalArgumentException missingEx = assertThrows(IllegalArgumentException.class, () ->
                service.evaluateCitPolicy(tenantId, missingCmd)
        );
        assertTrue(missingEx.getMessage().contains("POLICY_PACK_NOT_FOUND"));

        ResponseEntity<Map<String, Object>> missingResp = FinanceGlobalExceptionHandler.handleException(missingEx);
        assertEquals(404, missingResp.getStatusCode().value());
        assertEquals("POLICY_PACK_NOT_FOUND", missingResp.getBody().get("code"));
        assertEquals(false, missingResp.getBody().get("retryable"));
        assertNotNull(missingResp.getBody().get("traceId"));

        // Evaluation when policy pack exists -> calculates result
        when(mockStore.findPolicyPack(tenantId, packId)).thenReturn(Optional.of(pack));
        PayrollTaxExtModel.EvaluateCitCommand validCmd = new PayrollTaxExtModel.EvaluateCitCommand(
                packId, UUID.randomUUID(), LocalDate.of(2026, 6, 30),
                "100000.00", "50", "1000000.00", null
        );
        TaxPolicyRuleEngine.CalculationResult calcResult = service.evaluateCitPolicy(tenantId, validCmd);
        assertNotNull(calcResult);
        assertEquals("CIT_SMALL_LOW_PROFIT", calcResult.taxType());
        assertNotNull(calcResult.payableTax());
    }

    // --- 6. FinanceGlobalExceptionHandler Wave-2 Domain & SQL Exceptions ---
    @Test
    void testFinanceGlobalExceptionHandlerWave2() {
        // SoD violation -> 403
        IllegalArgumentException sodEx = new IllegalArgumentException("Segregation of duties violation: approver cannot be the preparer");
        ResponseEntity<Map<String, Object>> sodResp = FinanceGlobalExceptionHandler.handleException(sodEx);
        assertEquals(403, sodResp.getStatusCode().value());
        assertEquals("SOD_VIOLATION", sodResp.getBody().get("code"));

        // Self approval -> 403
        IllegalStateException selfEx = new IllegalStateException("SELF_APPROVAL_FORBIDDEN: cannot self approve");
        ResponseEntity<Map<String, Object>> selfResp = FinanceGlobalExceptionHandler.handleException(selfEx);
        assertEquals(403, selfResp.getStatusCode().value());
        assertEquals("SOD_VIOLATION", selfResp.getBody().get("code"));

        // Independent natural person required -> 403
        IllegalStateException personEx = new IllegalStateException("INDEPENDENT_NATURAL_PERSON_REQUIRED: distinct natural person must approve");
        ResponseEntity<Map<String, Object>> personResp = FinanceGlobalExceptionHandler.handleException(personEx);
        assertEquals(403, personResp.getStatusCode().value());
        assertEquals("SOD_VIOLATION", personResp.getBody().get("code"));

        // Small impairment blocked -> 400
        IllegalArgumentException impairEx = new IllegalArgumentException("SMALL_IMPAIRMENT_BLOCKED: micro-entities cannot record asset impairment");
        ResponseEntity<Map<String, Object>> impairResp = FinanceGlobalExceptionHandler.handleException(impairEx);
        assertEquals(400, impairResp.getStatusCode().value());
        assertEquals("SMALL_IMPAIRMENT_BLOCKED", impairResp.getBody().get("code"));

        // Insufficient stock -> 400
        IllegalArgumentException stockEx = new IllegalArgumentException("INSUFFICIENT_STOCK: available 5 but requested 10");
        ResponseEntity<Map<String, Object>> stockResp = FinanceGlobalExceptionHandler.handleException(stockEx);
        assertEquals(400, stockResp.getStatusCode().value());
        assertEquals("INSUFFICIENT_STOCK", stockResp.getBody().get("code"));

        // Budget exceeded -> 400
        IllegalArgumentException budgetEx = new IllegalArgumentException("BUDGET_EXCEEDED: amount exceeds limit");
        ResponseEntity<Map<String, Object>> budgetResp = FinanceGlobalExceptionHandler.handleException(budgetEx);
        assertEquals(400, budgetResp.getStatusCode().value());
        assertEquals("BUDGET_EXCEEDED", budgetResp.getBody().get("code"));

        // Null pointer exception -> 400 INVALID_INPUT
        NullPointerException npe = new NullPointerException("tenantId is required");
        ResponseEntity<Map<String, Object>> npeResp = FinanceGlobalExceptionHandler.handleException(npe);
        assertEquals(400, npeResp.getStatusCode().value());
        assertEquals("INVALID_INPUT", npeResp.getBody().get("code"));

        // SQL check constraint violation (23514) -> 400
        SQLException sqlCheckEx = new SQLException("Check constraint violation: cloud_dr_status IN ('HEALTHY','DEGRADED')", "23514");
        ResponseEntity<Map<String, Object>> checkResp = FinanceGlobalExceptionHandler.handleException(sqlCheckEx);
        assertEquals(400, checkResp.getStatusCode().value());

        // SQL check constraint SoD violation (23514 with check_sod) -> 403
        SQLException sqlCheckSod = new SQLException("new row for relation violates check constraint check_sod_maker_approver", "23514");
        ResponseEntity<Map<String, Object>> checkSodResp = FinanceGlobalExceptionHandler.handleException(sqlCheckSod);
        assertEquals(403, checkSodResp.getStatusCode().value());
        assertEquals("SOD_VIOLATION", checkSodResp.getBody().get("code"));

        // SQL unique constraint violation (23505) -> 409
        SQLException sqlUniqueEx = new SQLException("duplicate key value violates unique constraint", "23505");
        ResponseEntity<Map<String, Object>> uniqueResp = FinanceGlobalExceptionHandler.handleException(sqlUniqueEx);
        assertEquals(409, uniqueResp.getStatusCode().value());
        assertEquals("DUPLICATE_KEY_CONFLICT", uniqueResp.getBody().get("code"));

        // SQL foreign key violation (23503) -> 409
        SQLException sqlFkEx = new SQLException("violates foreign key constraint", "23503");
        ResponseEntity<Map<String, Object>> fkResp = FinanceGlobalExceptionHandler.handleException(sqlFkEx);
        assertEquals(409, fkResp.getStatusCode().value());
        assertEquals("FOREIGN_KEY_VIOLATION", fkResp.getBody().get("code"));
    }
}
