package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.*;

class FinanceCashAllocationControllerTest {
    private static final String SCHEMA = "cash-allocation/1";
    private static final String HASH = "a".repeat(64);
    private static final UUID BOOK = UUID.randomUUID();
    private static final UUID YEAR = UUID.randomUUID();
    private static final UUID ALLOCATION = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();

    private static FinanceCashAllocations boundary() {
        return new FinanceCashAllocations(null, null, null, null, null, null);
    }

    private static Map<String, Object> parent() {
        return Map.of(
                "reviewId",
                UUID.randomUUID().toString(),
                "expectedReviewVersion",
                3,
                "reviewContentDigest",
                HASH,
                "activeReviewUsageId",
                UUID.randomUUID().toString(),
                "claimId",
                UUID.randomUUID().toString(),
                "claimGeneration",
                1,
                "claimContentDigest",
                HASH,
                "sourceMatchGeneration",
                1);
    }

    private static Map<String, Object> line(String component, Object amount) {
        return new LinkedHashMap<>(
                Map.of(
                        "componentKey",
                        component,
                        "amount",
                        amount,
                        "policyId",
                        UUID.randomUUID().toString(),
                        "expectedPolicyVersion",
                        3,
                        "policyContentDigest",
                        HASH,
                        "businessPurpose",
                        "EQUITY_CAPITAL_CASH_GROSS",
                        "purposeEvidenceOriginalId",
                        UUID.randomUUID().toString(),
                        "description",
                        "A real evidenced part of the same gross source",
                        "ordinaryPurposeConfirmed",
                        true));
    }

    private static Map<String, Object> create() {
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", SCHEMA);
        result.put("idempotencyKey", "allocation-boundary-key");
        result.put("expectedParent", parent());
        result.put("supersedesAllocationId", null);
        result.put("expectedSupersededVersion", null);
        result.put("supersededContentDigest", null);
        result.put("lines", List.of(line("PART_1", "60.00"), line("PART_2", "40.00")));
        result.put("reason", "Independent ordinary full-gross preparation");
        return result;
    }

    private static void invalid(String code, Runnable command) {
        var error = assertThrows(FinanceException.class, command::run);
        assertEquals(code, error.code);
    }

    @Test
    void closedInputsRejectCallerReportFieldsAndMissingNullablePredecessor() {
        var service = boundary();
        var valid = create();
        assertDoesNotThrow(() -> service.normalize("create", valid));
        for (String required : valid.keySet()) {
            var missing = new LinkedHashMap<>(valid);
            missing.remove(required);
            invalid("INVALID_INPUT", () -> service.normalize("create", missing));
        }
        for (String required : parent().keySet()) {
            var missing = new LinkedHashMap<>(parent());
            missing.remove(required);
            invalid("INVALID_INPUT", () -> service.expected(missing));
        }
        var injected = new LinkedHashMap<>(valid);
        injected.put("rowId", "C01");
        invalid("INVALID_INPUT", () -> service.normalize("create", injected));
        var omitted = new LinkedHashMap<>(valid);
        omitted.remove("supersedesAllocationId");
        invalid("INVALID_INPUT", () -> service.normalize("create", omitted));
        var wrongNested = new LinkedHashMap<>(parent());
        wrongNested.put("approvedBy", UUID.randomUUID().toString());
        invalid("INVALID_INPUT", () -> service.expected(wrongNested));
        var fakeLeaf = line("PART", "100.00");
        fakeLeaf.put("semanticLeaf", "OP_OTHER_IN");
        invalid("INVALID_INPUT", () -> service.inputLines(List.of(fakeLeaf)));
    }

    @Test
    void currencyLiteralsAreStrictAndNeverSilentlyRounded() {
        for (Object value : List.of(1, 1.0, "0", "0.00", "-1.00", "01.00", "1e2", " 1.00"))
            invalid("INVALID_INPUT", () -> FinanceCashAllocations.money(value));
        for (String value : List.of("1.000", "1.001", "1.005"))
            invalid("INVALID_CURRENCY_SCALE", () -> FinanceCashAllocations.money(value));
        invalid("AMOUNT_OVERFLOW", () -> FinanceCashAllocations.money("1" + "0".repeat(20)));
        assertEquals("100.01000000", FinanceCashAllocations.money("100.01"));
        assertEquals("0.01000000", FinanceCashAllocations.money("0.01"));
    }

    @Test
    void componentsHaveDistinctIdentityButMayShareOneRealSupportingFile() {
        var service = boundary();
        var first = line("PART_1", "60.00");
        var second = line("PART_2", "40.00");
        second.put("purposeEvidenceOriginalId", first.get("purposeEvidenceOriginalId"));
        var result = service.inputLines(List.of(first, second));
        assertEquals(2, result.size());
        assertEquals("60.00000000", result.getFirst().get("amount"));
        assertEquals(
                first.get("purposeEvidenceOriginalId"),
                result.getLast().get("purposeEvidenceOriginalId"));
        var duplicate = new LinkedHashMap<>(second);
        duplicate.put("componentKey", "PART_1");
        invalid("INVALID_INPUT", () -> service.inputLines(List.of(first, duplicate)));
        invalid("INVALID_INPUT", () -> service.inputLines(List.of()));
        var tooMany = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 21; i++) tooMany.add(line("PART_" + i, "1.00"));
        invalid("INVALID_INPUT", () -> service.inputLines(tooMany));
        assertEquals(20, service.inputLines(tooMany.subList(0, 20)).size());
        var unsupported = line("PART", "1.00");
        unsupported.put("businessPurpose", "OTHER");
        invalid(
                "CASH_ALLOCATION_UNSUPPORTED_FACTS",
                () -> service.inputLines(List.of(unsupported)));
        var unconfirmed = line("PART", "1.00");
        unconfirmed.put("ordinaryPurposeConfirmed", false);
        invalid(
                "CASH_ALLOCATION_UNSUPPORTED_FACTS",
                () -> service.inputLines(List.of(unconfirmed)));
    }

    @Test
    void bodyVersionsStayJsonIntegersAndHeadersMustAgreeBeforeDispatch() {
        var service = mock(FinanceCashAllocations.class);
        var controller = new FinanceCashAllocationController(service);
        var actor = FinanceHttpTest.actor();
        var request = request("/approve");
        FinanceHttpTest.error(
                controller.action(
                        actor,
                        BOOK,
                        YEAR,
                        ALLOCATION,
                        request,
                        Map.of("idempotencyKey", "body-key", "expectedVersion", 3),
                        "different-key",
                        "3"),
                400,
                "INVALID_INPUT");
        FinanceHttpTest.error(
                controller.action(
                        actor,
                        BOOK,
                        YEAR,
                        ALLOCATION,
                        request,
                        Map.of("expectedVersion", 3),
                        "header-key",
                        "4"),
                400,
                "INVALID_INPUT");
        FinanceHttpTest.error(
                controller.action(
                        actor,
                        BOOK,
                        YEAR,
                        ALLOCATION,
                        request,
                        Map.of("expectedVersion", "3"),
                        "header-key",
                        "3"),
                400,
                "INVALID_INPUT");
        FinanceHttpTest.error(
                controller.action(
                        actor, BOOK, YEAR, ALLOCATION, request, Map.of(), "header-key", "W/\"3\""),
                400,
                "INVALID_INPUT");
        verifyNoInteractions(service);
        controller.action(
                actor,
                BOOK,
                YEAR,
                ALLOCATION,
                request,
                Map.of("contentDigest", HASH),
                "header-key",
                "\"3\"");
        verify(service)
                .write(
                        eq("maker"),
                        eq(BOOK),
                        eq(YEAR),
                        eq("approve"),
                        eq(ALLOCATION),
                        isNull(),
                        argThat(
                                body ->
                                        body.get("expectedVersion").equals(3L)
                                                && body.get("idempotencyKey").equals("header-key")
                                                && body.get("contentDigest").equals(HASH)));
    }

    private static HttpServletRequest request(String suffix) {
        var request = mock(HttpServletRequest.class);
        when(request.getRequestURI())
                .thenReturn(
                        "/api/finance/v1/books/"
                                + BOOK
                                + "/years/"
                                + YEAR
                                + "/cashflow/allocations"
                                + suffix);
        return request;
    }

    @Test
    void allEighteenOperationsKeepScopeAndDistinctReleaseReplacementTargets() {
        var service = mock(FinanceCashAllocations.class);
        var controller = new FinanceCashAllocationController(service);
        var actor = FinanceHttpTest.actor();
        UUID review = UUID.randomUUID(), claim = UUID.randomUUID();
        controller.eligible(actor, BOOK, YEAR, review, claim, 7, "eligible-cursor");
        verify(service).eligible("maker", BOOK, YEAR, review, claim, 7, "eligible-cursor");
        controller.list(actor, BOOK, YEAR, review, claim, "SUPERSEDED", 9, "list-cursor");
        verify(service).list("maker", BOOK, YEAR, review, claim, "SUPERSEDED", 9, "list-cursor");
        controller.detail(actor, BOOK, YEAR, ALLOCATION);
        verify(service).detail("maker", BOOK, YEAR, ALLOCATION);
        controller.releases(actor, BOOK, YEAR, ALLOCATION, 3, "release-cursor");
        verify(service).releases("maker", BOOK, YEAR, ALLOCATION, 3, "release-cursor");
        controller.replacements(actor, BOOK, YEAR, ALLOCATION, 4, "replacement-cursor");
        verify(service).replacements("maker", BOOK, YEAR, ALLOCATION, 4, "replacement-cursor");
        var body = Map.<String, Object>of("contentDigest", HASH);
        controller.create(actor, BOOK, YEAR, body, "route-key");
        verify(service)
                .write(
                        "maker",
                        BOOK,
                        YEAR,
                        "create",
                        null,
                        null,
                        Map.of("contentDigest", HASH, "idempotencyKey", "route-key"));
        for (String operation : List.of("submit", "approve", "reject", "cancel")) {
            controller.action(
                    actor,
                    BOOK,
                    YEAR,
                    ALLOCATION,
                    request("/" + ALLOCATION + "/" + operation),
                    body,
                    "route-key",
                    "3");
            verify(service)
                    .write(
                            "maker",
                            BOOK,
                            YEAR,
                            operation,
                            ALLOCATION,
                            null,
                            Map.of(
                                    "contentDigest",
                                    HASH,
                                    "idempotencyKey",
                                    "route-key",
                                    "expectedVersion",
                                    3L));
        }
        controller.requestRelease(actor, BOOK, YEAR, ALLOCATION, body, "route-key", "3");
        verify(service)
                .write(
                        "maker",
                        BOOK,
                        YEAR,
                        "release",
                        ALLOCATION,
                        null,
                        Map.of(
                                "contentDigest",
                                HASH,
                                "idempotencyKey",
                                "route-key",
                                "expectedVersion",
                                3L));
        controller.requestReplacement(actor, BOOK, YEAR, body, "route-key");
        verify(service)
                .write(
                        "maker",
                        BOOK,
                        YEAR,
                        "replace",
                        null,
                        null,
                        Map.of("contentDigest", HASH, "idempotencyKey", "route-key"));
        for (String operation : List.of("approve", "reject", "cancel")) {
            controller.releaseDecision(
                    actor,
                    BOOK,
                    YEAR,
                    ALLOCATION,
                    REQUEST,
                    request("/" + ALLOCATION + "/release-requests/" + REQUEST + "/" + operation),
                    body,
                    "route-key",
                    "3");
            verify(service)
                    .write(
                            "maker",
                            BOOK,
                            YEAR,
                            "release-" + operation,
                            ALLOCATION,
                            REQUEST,
                            Map.of(
                                    "contentDigest",
                                    HASH,
                                    "idempotencyKey",
                                    "route-key",
                                    "expectedVersion",
                                    3L));
            controller.replacementDecision(
                    actor,
                    BOOK,
                    YEAR,
                    REQUEST,
                    request("/replacement-requests/" + REQUEST + "/" + operation),
                    body,
                    "route-key",
                    "3");
            verify(service)
                    .write(
                            "maker",
                            BOOK,
                            YEAR,
                            "replace-" + operation,
                            null,
                            REQUEST,
                            Map.of(
                                    "contentDigest",
                                    HASH,
                                    "idempotencyKey",
                                    "route-key",
                                    "expectedVersion",
                                    3L));
        }
        assertEquals(18, mockingDetails(service).getInvocations().size());
        int declaredRoutes =
                Arrays.stream(FinanceCashAllocationController.class.getDeclaredMethods())
                        .mapToInt(
                                method -> {
                                    var get = method.getAnnotation(GetMapping.class);
                                    var post = method.getAnnotation(PostMapping.class);
                                    return get != null
                                            ? Math.max(1, get.value().length)
                                            : post != null ? Math.max(1, post.value().length) : 0;
                                })
                        .sum();
        assertEquals(18, declaredRoutes);
    }

    @Test
    void allocationConflictsAndIndependentReviewKeepStructuredErrorCodes() {
        var service = mock(FinanceCashAllocations.class);
        var controller = new FinanceCashAllocationController(service);
        for (String code :
                List.of(
                        "CASH_ALLOCATION_PARENT_STALE",
                        "CASH_ALLOCATION_POLICY_UNAVAILABLE",
                        "CASH_ALLOCATION_SUM_MISMATCH",
                        "CASH_ALLOCATION_REPLACEMENT_REQUIRED",
                        "CASH_ALLOCATION_RELEASE_PENDING",
                        "CASH_ALLOCATION_REPLACEMENT_PENDING",
                        "CASH_ALLOCATION_SUBMISSION_EXPIRED")) {
            doThrow(new FinanceException(code, "分配状态冲突"))
                    .when(service)
                    .write(anyString(), any(), any(), anyString(), any(), any(), anyMap());
            FinanceHttpTest.error(
                    controller.create(
                            FinanceHttpTest.actor(),
                            BOOK,
                            YEAR,
                            Map.of("idempotencyKey", "allocation-conflict-key"),
                            null),
                    409,
                    code);
        }
        doThrow(new FinanceException("SELF_APPROVAL_FORBIDDEN", "独立复核要求"))
                .when(service)
                .write(anyString(), any(), any(), anyString(), any(), any(), anyMap());
        FinanceHttpTest.error(
                controller.action(
                        FinanceHttpTest.actor(),
                        BOOK,
                        YEAR,
                        ALLOCATION,
                        request("/approve"),
                        Map.of("idempotencyKey", "allocation-self-key"),
                        null,
                        null),
                403,
                "SELF_APPROVAL_FORBIDDEN");
    }
}
