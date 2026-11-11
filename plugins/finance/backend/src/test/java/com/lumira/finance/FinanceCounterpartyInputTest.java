package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class FinanceCounterpartyInputTest {
    static Map<String, Object> map(Object... p) {
        return FinanceJournalPersonGate.map(p);
    }

    static Map<String, Object> content() {
        return map(
                "displayName",
                "同名组织",
                "roles",
                List.of("SUPPLIER", "CUSTOMER"),
                "settlementTerms",
                null,
                "contractDescription",
                null,
                "relatedPartyStatus",
                "UNKNOWN",
                "relatedPartyBasis",
                null,
                "registrationBasis",
                "组织提供的登记声明，不是政府核验");
    }

    static Map<String, Object> identity() {
        return map(
                "namespace",
                "ORGANIZATION_DECLARED_REGISTRATION",
                "jurisdiction",
                "CN",
                "identifier",
                "MD02/ABC-1",
                "verification",
                "ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED");
    }

    static Map<String, Object> create() {
        return map(
                "schemaVersion",
                FinanceCounterpartyInput.SCHEMA,
                "idempotencyKey",
                UUID.randomUUID().toString(),
                "operationId",
                UUID.randomUUID().toString(),
                "reason",
                "组织主数据申请",
                "identity",
                identity(),
                "content",
                content(),
                "organizationDeclarationConfirmed",
                true,
                "identityNotPlaceholderConfirmed",
                true);
    }

    @Test
    void closedRequiredFieldsAndDeclarations() {
        for (String key : FinanceCounterpartyInput.CREATE) {
            var n = create();
            n.remove(key);
            assertThrows(
                    FinanceException.class,
                    () -> FinanceCounterpartyInput.normalized("create", n),
                    key);
        }
        for (String flag :
                List.of("organizationDeclarationConfirmed", "identityNotPlaceholderConfirmed")) {
            var n = create();
            n.put(flag, false);
            assertThrows(
                    FinanceException.class, () -> FinanceCounterpartyInput.normalized("create", n));
        }
        var n = create();
        n.put("bankAccount", "123");
        assertThrows(
                FinanceException.class, () -> FinanceCounterpartyInput.normalized("create", n));
    }

    @Test
    void publicRegistrationMustBeCanonicalAndCannotUseNameOrUnknown() {
        for (String field : List.of("jurisdiction", "identifier")) {
            var i = identity();
            i.put(field, field.equals("jurisdiction") ? "cn" : "md02/abc-1");
            assertThrows(FinanceException.class, () -> FinanceCounterpartyInput.identity(i));
            i.put(field, field.equals("jurisdiction") ? " CN" : "MD02/ABC-1 ");
            assertThrows(FinanceException.class, () -> FinanceCounterpartyInput.identity(i));
        }
        var i = identity();
        i.put("identifier", "随机企业名");
        assertThrows(FinanceException.class, () -> FinanceCounterpartyInput.identity(i));
        i.put("identifier", null);
        assertThrows(FinanceException.class, () -> FinanceCounterpartyInput.identity(i));
        assertEquals(identity(), FinanceCounterpartyInput.identity(identity()));
    }

    @Test
    void nfcSortedRolesAndMultilineDescriptionsPreserveRawTextButRejectOtherControlsOrBlank() {
        var c = content();
        c.put("displayName", " Cafe\u0301 ");
        c.put("registrationBasis", "  原文\n第二行\r\n\t依据  ");
        var n = FinanceCounterpartyInput.content(c);
        assertEquals(" Café ", n.get("displayName"));
        assertEquals(List.of("CUSTOMER", "SUPPLIER"), n.get("roles"));
        assertEquals(c.get("registrationBasis"), n.get("registrationBasis"));
        for (String bad : List.of("\u0001", " \t\r\n", "\u00a0")) {
            var x = content();
            x.put("registrationBasis", bad);
            assertThrows(FinanceException.class, () -> FinanceCounterpartyInput.content(x));
        }
        c.put("displayName", "Name\n");
        assertThrows(FinanceException.class, () -> FinanceCounterpartyInput.content(c));
        var relation = content();
        relation.put("relatedPartyStatus", "RELATED");
        assertThrows(FinanceException.class, () -> FinanceCounterpartyInput.content(relation));
        relation.put("relatedPartyBasis", "组织提供关联关系依据");
        assertDoesNotThrow(() -> FinanceCounterpartyInput.content(relation));
    }

    @Test
    void revisionUsesExplicitNullableBaseAndStringCounters() {
        var n =
                map(
                        "schemaVersion",
                        FinanceCounterpartyInput.SCHEMA,
                        "idempotencyKey",
                        "key",
                        "reason",
                        "修订",
                        "expectedRegistryVersion",
                        "2",
                        "baseApprovedRevisionId",
                        null,
                        "baseApprovedContentDigest",
                        null,
                        "previousRevisionId",
                        UUID.randomUUID().toString(),
                        "desiredStatus",
                        "ACTIVE",
                        "content",
                        content());
        assertDoesNotThrow(() -> FinanceCounterpartyInput.normalized("propose", n));
        n.remove("baseApprovedRevisionId");
        assertThrows(
                FinanceException.class, () -> FinanceCounterpartyInput.normalized("propose", n));
        assertThrows(FinanceException.class, () -> FinanceCounterpartyInput.counter(2));
        assertThrows(FinanceException.class, () -> FinanceCounterpartyInput.counter("01"));
        assertThrows(
                FinanceException.class,
                () -> FinanceCounterpartyInput.counter("9223372036854775808"));
    }
}
