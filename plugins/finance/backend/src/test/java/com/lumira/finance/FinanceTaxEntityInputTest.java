package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class FinanceTaxEntityInputTest {
    static Map<String, Object> map(Object... p) {
        return FinanceJournalPersonGate.map(p);
    }

    static Map<String, Object> profile() {
        return map(
                "displayName",
                "组织人工登记主体",
                "entityKind",
                "LEGAL_ENTITY",
                "registrationTypeDeclaration",
                "组织登记声明",
                "residencyDeclaration",
                "UNKNOWN",
                "establishedOn",
                null,
                "cancelledOn",
                null,
                "authorityCodeDeclaration",
                null,
                "administrativeDivisionDeclaration",
                null,
                "organizationVerificationBasis",
                "组织人工复核\r\n\t不是官方验真",
                "registrationEvidence",
                map(
                        "yearId",
                        UUID.randomUUID().toString(),
                        "originalId",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "1",
                        "expectedSha256",
                        "a".repeat(64),
                        "sourceType",
                        "ELECTRONIC_ORIGINAL",
                        "originalLocation",
                        null),
                "singleTaxpayerConfirmed",
                true,
                "allKnownRegistrationIdentifiersDeclared",
                true,
                "organizationReviewedNotGovernmentVerified",
                true);
    }

    static Map<String, Object> create() {
        return map(
                "schemaVersion",
                FinanceTaxEntityInput.SCHEMA,
                "idempotencyKey",
                UUID.randomUUID().toString(),
                "operationId",
                UUID.randomUUID().toString(),
                "reason",
                "组织登记申请",
                "identity",
                map(
                        "country",
                        "CN",
                        "canonicalTaxRegistrationNo",
                        "CN-TAX/123",
                        "verification",
                        "ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED"),
                "content",
                profile());
    }

    @Test
    void closedRequiredFieldsAndExplicitOrganizationAssertions() {
        for (String key : FinanceTaxEntityInput.CREATE) {
            var n = create();
            n.remove(key);
            assertThrows(
                    FinanceException.class,
                    () -> FinanceTaxEntityInput.normalized("create", n),
                    key);
        }
        for (String key : FinanceTaxEntityInput.PROFILE) {
            var p = profile();
            p.remove(key);
            assertThrows(FinanceException.class, () -> FinanceTaxEntityInput.content(p), key);
        }
        for (String key :
                List.of(
                        "singleTaxpayerConfirmed",
                        "allKnownRegistrationIdentifiersDeclared",
                        "organizationReviewedNotGovernmentVerified")) {
            var p = profile();
            p.put(key, false);
            assertThrows(FinanceException.class, () -> FinanceTaxEntityInput.content(p));
        }
        var n = create();
        n.put("tenantId", UUID.randomUUID().toString());
        assertThrows(FinanceException.class, () -> FinanceTaxEntityInput.normalized("create", n));
    }

    @Test
    void canonicalIdentityDatesAndPaperEvidenceAreNotInvented() {
        for (String value : List.of("cn-123", " CN-123", "CN-123 ", "未知", "", "A".repeat(65))) {
            var i =
                    map(
                            "country",
                            "CN",
                            "canonicalTaxRegistrationNo",
                            value,
                            "verification",
                            "ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED");
            assertThrows(FinanceException.class, () -> FinanceTaxEntityInput.identity(i));
        }
        var p = profile();
        p.put("establishedOn", "2026-02-30");
        assertThrows(FinanceException.class, () -> FinanceTaxEntityInput.content(p));
        p.put("establishedOn", "2026-02-28");
        p.put("cancelledOn", "2026-02-27");
        assertThrows(FinanceException.class, () -> FinanceTaxEntityInput.content(p));
        var e = FinanceTaxEntityInput.object(profile().get("registrationEvidence"));
        e.put("sourceType", "PAPER_SCAN");
        assertThrows(FinanceException.class, () -> FinanceTaxEntityInput.reference(e));
        e.put("originalLocation", "柜A\n层1");
        assertEquals("柜A\n层1", FinanceTaxEntityInput.reference(e).get("originalLocation"));
        e.put("expectedVersion", 1);
        assertThrows(FinanceException.class, () -> FinanceTaxEntityInput.reference(e));
    }

    @Test
    void textControlsAndCounterTypesRemainStrict() {
        var p = profile();
        assertEquals(
                p.get("organizationVerificationBasis"),
                FinanceTaxEntityInput.content(p).get("organizationVerificationBasis"));
        p.put("displayName", "a\nb");
        assertThrows(FinanceException.class, () -> FinanceTaxEntityInput.content(p));
        p.put("displayName", "名称");
        p.put("organizationVerificationBasis", "a\u0001b");
        assertThrows(FinanceException.class, () -> FinanceTaxEntityInput.content(p));
        p.put("organizationVerificationBasis", " \n\t");
        assertThrows(FinanceException.class, () -> FinanceTaxEntityInput.content(p));
        for (Object value : List.of(1, "01", "-1", "9223372036854775808"))
            assertThrows(FinanceException.class, () -> FinanceTaxEntityInput.counter(value));
    }
}
