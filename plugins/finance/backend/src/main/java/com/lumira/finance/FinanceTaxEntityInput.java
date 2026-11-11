package com.lumira.finance;

import java.time.LocalDate;
import java.util.*;

/** Closed public input; organization declarations never establish government verification. */
final class FinanceTaxEntityInput {
    static final String SCHEMA = "tax-entity/1";
    static final Set<String> CREATE =
            Set.of(
                    "schemaVersion",
                    "idempotencyKey",
                    "reason",
                    "operationId",
                    "identity",
                    "content");
    static final Set<String> PROPOSE =
            Set.of(
                    "schemaVersion",
                    "idempotencyKey",
                    "reason",
                    "expectedEntityVersion",
                    "expectedLatestRevisionId",
                    "expectedLatestRevisionVersion",
                    "expectedApprovedRevisionId",
                    "expectedApprovedContentDigest",
                    "desiredStatus",
                    "content");
    static final Set<String> ACTION =
            Set.of(
                    "schemaVersion",
                    "idempotencyKey",
                    "reason",
                    "expectedEntityVersion",
                    "expectedVersion",
                    "contentDigest");
    static final Set<String> PROFILE =
            Set.of(
                    "displayName",
                    "entityKind",
                    "registrationTypeDeclaration",
                    "residencyDeclaration",
                    "establishedOn",
                    "cancelledOn",
                    "authorityCodeDeclaration",
                    "administrativeDivisionDeclaration",
                    "organizationVerificationBasis",
                    "registrationEvidence",
                    "singleTaxpayerConfirmed",
                    "allKnownRegistrationIdentifiersDeclared",
                    "organizationReviewedNotGovernmentVerified");

    static void fields(Map<String, Object> m, Set<String> keys) {
        FinanceCounterpartyInput.fields(m, keys);
    }

    static Map<String, Object> object(Object v) {
        return FinanceCounterpartyInput.object(v);
    }

    static UUID uuid(Object v) {
        return FinanceCounterpartyInput.uuid(v);
    }

    static String counter(Object v) {
        return FinanceCounterpartyInput.counter(v);
    }

    static String digest(Object v) {
        return FinanceCounterpartyInput.digest(v);
    }

    static String text(Object v, int max, boolean nullable, boolean controls) {
        return FinanceCounterpartyInput.text(v, max, nullable, controls);
    }

    static String enumeration(Object v, Set<String> values) {
        if (!(v instanceof String s) || !values.contains(s))
            throw FinanceException.invalid("声明枚举无效");
        return s;
    }

    static String date(Object v) {
        if (v == null) return null;
        try {
            if (!(v instanceof String s)
                    || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    || !LocalDate.parse(s).toString().equals(s))
                throw new IllegalArgumentException();
            return s;
        } catch (RuntimeException bad) {
            throw FinanceException.invalid("日期须为规范日历日期");
        }
    }

    static Map<String, Object> identity(Object v) {
        var m = object(v);
        fields(m, Set.of("country", "canonicalTaxRegistrationNo", "verification"));
        if (!"CN".equals(m.get("country"))
                || !"ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED".equals(m.get("verification"))
                || !(m.get("canonicalTaxRegistrationNo") instanceof String n)
                || !n.matches("[A-Z0-9][A-Z0-9._/-]{0,63}"))
            throw FinanceException.invalid("税务登记号须为已规范化CN组织声明标识");
        return new LinkedHashMap<>(m);
    }

    static Map<String, Object> reference(Object v) {
        var m = object(v);
        fields(
                m,
                Set.of(
                        "yearId",
                        "originalId",
                        "expectedVersion",
                        "expectedSha256",
                        "sourceType",
                        "originalLocation"));
        uuid(m.get("yearId"));
        uuid(m.get("originalId"));
        counter(m.get("expectedVersion"));
        digest(m.get("expectedSha256"));
        String type = enumeration(m.get("sourceType"), Set.of("ELECTRONIC_ORIGINAL", "PAPER_SCAN"));
        String location = text(m.get("originalLocation"), 500, true, false);
        if (type.equals("PAPER_SCAN") && location == null)
            throw FinanceException.invalid("纸扫原件须提供实际保存位置");
        var n = new LinkedHashMap<>(m);
        n.put("originalLocation", location);
        return n;
    }

    static Map<String, Object> content(Object v) {
        var m = object(v);
        fields(m, PROFILE);
        var n = new LinkedHashMap<>(m);
        n.put("displayName", text(m.get("displayName"), 200, false, true));
        n.put(
                "entityKind",
                enumeration(
                        m.get("entityKind"),
                        Set.of("LEGAL_ENTITY", "BRANCH", "OTHER_REGISTERED_TAXPAYER")));
        n.put(
                "registrationTypeDeclaration",
                text(m.get("registrationTypeDeclaration"), 100, false, true));
        n.put(
                "residencyDeclaration",
                enumeration(
                        m.get("residencyDeclaration"),
                        Set.of("RESIDENT", "NON_RESIDENT", "UNKNOWN")));
        n.put("establishedOn", date(m.get("establishedOn")));
        n.put("cancelledOn", date(m.get("cancelledOn")));
        if (n.get("establishedOn") != null
                && n.get("cancelledOn") != null
                && n.get("establishedOn").toString().compareTo(n.get("cancelledOn").toString()) > 0)
            throw FinanceException.invalid("注销日期不得早于设立日期");
        n.put("authorityCodeDeclaration", text(m.get("authorityCodeDeclaration"), 100, true, true));
        n.put(
                "administrativeDivisionDeclaration",
                text(m.get("administrativeDivisionDeclaration"), 100, true, true));
        n.put(
                "organizationVerificationBasis",
                text(m.get("organizationVerificationBasis"), 1000, false, false));
        n.put("registrationEvidence", reference(m.get("registrationEvidence")));
        for (String k :
                List.of(
                        "singleTaxpayerConfirmed",
                        "allKnownRegistrationIdentifiersDeclared",
                        "organizationReviewedNotGovernmentVerified"))
            if (!Boolean.TRUE.equals(m.get(k))) throw FinanceException.invalid("须主动确认组织登记声明");
        return n;
    }

    static Map<String, Object> normalized(String op, Map<String, Object> m) {
        if (!Set.of("create", "propose-revision", "approve", "reject", "cancel").contains(op))
            throw FinanceException.invalid("操作无效");
        fields(m, op.equals("create") ? CREATE : op.equals("propose-revision") ? PROPOSE : ACTION);
        if (!SCHEMA.equals(m.get("schemaVersion"))) throw FinanceException.invalid("协议版本无效");
        var n = new LinkedHashMap<>(m);
        String key = text(m.get("idempotencyKey"), 128, false, true);
        if (key.length() < 8) throw FinanceException.invalid("请求键长度须8..128");
        n.put("idempotencyKey", key);
        n.put("reason", text(m.get("reason"), 1000, false, false));
        if (op.equals("create")) {
            uuid(m.get("operationId"));
            n.put("identity", identity(m.get("identity")));
            n.put("content", content(m.get("content")));
        } else {
            counter(m.get("expectedEntityVersion"));
            if (op.equals("propose-revision")) {
                uuid(m.get("expectedLatestRevisionId"));
                counter(m.get("expectedLatestRevisionVersion"));
                if ((m.get("expectedApprovedRevisionId") == null)
                        != (m.get("expectedApprovedContentDigest") == null))
                    throw FinanceException.invalid("有效版本与摘要须同时提供或显式null");
                if (m.get("expectedApprovedRevisionId") != null) {
                    uuid(m.get("expectedApprovedRevisionId"));
                    digest(m.get("expectedApprovedContentDigest"));
                }
                enumeration(m.get("desiredStatus"), Set.of("ACTIVE", "INACTIVE"));
                n.put("content", content(m.get("content")));
            } else {
                counter(m.get("expectedVersion"));
                digest(m.get("contentDigest"));
            }
        }
        return n;
    }

    private FinanceTaxEntityInput() {}
}
