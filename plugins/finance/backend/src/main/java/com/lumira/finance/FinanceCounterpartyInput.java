package com.lumira.finance;

import java.text.Normalizer;
import java.util.*;

final class FinanceCounterpartyInput {
    static final String SCHEMA = "counterparty/1";
    static final Set<String> CREATE =
            Set.of(
                    "schemaVersion",
                    "idempotencyKey",
                    "reason",
                    "operationId",
                    "identity",
                    "content",
                    "organizationDeclarationConfirmed",
                    "identityNotPlaceholderConfirmed");
    static final Set<String> PROPOSE =
            Set.of(
                    "schemaVersion",
                    "idempotencyKey",
                    "reason",
                    "expectedRegistryVersion",
                    "baseApprovedRevisionId",
                    "baseApprovedContentDigest",
                    "previousRevisionId",
                    "desiredStatus",
                    "content");
    static final Set<String> ACTION =
            Set.of(
                    "schemaVersion",
                    "idempotencyKey",
                    "reason",
                    "expectedRegistryVersion",
                    "expectedRevisionVersion",
                    "contentDigest");

    static void fields(Map<String, Object> value, Set<String> expected) {
        if (value == null || !value.keySet().equals(expected))
            throw FinanceException.invalid("字段缺失或未知");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> m)) throw FinanceException.invalid("须为对象");
        return (Map<String, Object>) m;
    }

    static UUID uuid(Object value) {
        try {
            return FinancePersonDirectory.uuid(value);
        } catch (IllegalArgumentException invalid) {
            throw FinanceException.invalid("UUID须为规范小写完整值");
        }
    }

    static String text(Object value, int max, boolean nullable, boolean controls) {
        if (value == null && nullable) return null;
        if (!(value instanceof String s)) throw FinanceException.invalid("文本类型无效");
        String n = Normalizer.normalize(s, Normalizer.Form.NFC);
        if (n.isEmpty()
                || n.length() > max
                || n.codePoints()
                        .allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                || n.codePoints()
                        .anyMatch(
                                c ->
                                        Character.isISOControl(c)
                                                && (controls || (c != 9 && c != 10 && c != 13))))
            throw FinanceException.invalid("文本长度或字符无效");
        return n;
    }

    static String registration(Object value, int max) {
        String s = text(value, 256, false, true);
        if (s.chars().anyMatch(c -> c < 32 || c > 126))
            throw FinanceException.invalid("登记标识仅支持ASCII");
        int first = 0, last = s.length();
        while (first < last && s.charAt(first) == ' ') first++;
        while (last > first && s.charAt(last - 1) == ' ') last--;
        s = s.substring(first, last);
        StringBuilder n = new StringBuilder();
        for (char c : s.toCharArray()) n.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        if (n.length() < 1 || n.length() > max) throw FinanceException.invalid("登记标识长度无效");
        return n.toString();
    }

    static Map<String, Object> identity(Object value) {
        var v = object(value);
        fields(v, Set.of("namespace", "jurisdiction", "identifier", "verification"));
        if (!"ORGANIZATION_DECLARED_REGISTRATION".equals(v.get("namespace"))
                || !"ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED".equals(v.get("verification")))
            throw FinanceException.invalid("登记声明协议无效");
        String country = registration(v.get("jurisdiction"), 2),
                identifier = registration(v.get("identifier"), 64);
        if (!country.equals(v.get("jurisdiction"))
                || !identifier.equals(v.get("identifier"))
                || !country.matches("[A-Z]{2}")
                || !identifier.matches("[A-Z0-9._/-]{1,64}"))
            throw FinanceException.invalid("登记标识字符无效");
        return FinanceJournalPersonGate.map(
                "namespace",
                v.get("namespace"),
                "jurisdiction",
                country,
                "identifier",
                identifier,
                "verification",
                v.get("verification"));
    }

    static Map<String, Object> content(Object value) {
        var v = object(value);
        fields(
                v,
                Set.of(
                        "displayName",
                        "roles",
                        "settlementTerms",
                        "contractDescription",
                        "relatedPartyStatus",
                        "relatedPartyBasis",
                        "registrationBasis"));
        if (!(v.get("roles") instanceof List<?> roles)
                || roles.isEmpty()
                || roles.size() > 2
                || new HashSet<>(roles).size() != roles.size()
                || roles.stream()
                        .anyMatch(
                                x ->
                                        !(x instanceof String)
                                                || !Set.of("CUSTOMER", "SUPPLIER").contains(x)))
            throw FinanceException.invalid("往来角色无效");
        String relation = text(v.get("relatedPartyStatus"), 20, false, true),
                basis = text(v.get("relatedPartyBasis"), 1000, true, false);
        if (!Set.of("UNKNOWN", "RELATED", "NOT_RELATED").contains(relation)
                || (relation.equals("UNKNOWN") != (basis == null)))
            throw FinanceException.invalid("关联方未知须为null，已知声明须有依据");
        return FinanceJournalPersonGate.map(
                "displayName",
                text(v.get("displayName"), 300, false, true),
                "roles",
                roles.stream().map(Object::toString).sorted().toList(),
                "settlementTerms",
                text(v.get("settlementTerms"), 1000, true, false),
                "contractDescription",
                text(v.get("contractDescription"), 1000, true, false),
                "relatedPartyStatus",
                relation,
                "relatedPartyBasis",
                basis,
                "registrationBasis",
                text(v.get("registrationBasis"), 1000, false, false));
    }

    static String counter(Object value) {
        if (!(value instanceof String s) || !s.matches("0|[1-9][0-9]{0,18}"))
            throw FinanceException.invalid("版本须为规范非负整数字符串");
        try {
            Long.parseLong(s);
        } catch (NumberFormatException invalid) {
            throw FinanceException.invalid("版本超出容量");
        }
        return s;
    }

    static String digest(Object value) {
        if (!(value instanceof String s) || !s.matches("[a-f0-9]{64}"))
            throw FinanceException.invalid("内容摘要无效");
        return s;
    }

    static Map<String, Object> normalized(String op, Map<String, Object> input) {
        if (!Set.of("create", "propose", "approve", "reject", "cancel").contains(op))
            throw FinanceException.invalid("操作无效");
        fields(input, op.equals("create") ? CREATE : op.equals("propose") ? PROPOSE : ACTION);
        if (!SCHEMA.equals(input.get("schemaVersion"))) throw FinanceException.invalid("协议版本无效");
        String key = text(input.get("idempotencyKey"), 128, false, true);
        var n = new LinkedHashMap<>(input);
        n.put("idempotencyKey", key);
        n.put("reason", text(input.get("reason"), 1000, false, false));
        if (op.equals("create")) {
            uuid(input.get("operationId"));
            if (!Boolean.TRUE.equals(input.get("organizationDeclarationConfirmed"))
                    || !Boolean.TRUE.equals(input.get("identityNotPlaceholderConfirmed")))
                throw FinanceException.invalid("须主动确认组织声明与非占位登记身份");
            n.put("identity", identity(input.get("identity")));
            n.put("content", content(input.get("content")));
        } else {
            counter(input.get("expectedRegistryVersion"));
            if (op.equals("propose")) {
                uuid(input.get("previousRevisionId"));
                if ((input.get("baseApprovedRevisionId") == null)
                        != (input.get("baseApprovedContentDigest") == null))
                    throw FinanceException.invalid("有效基准ID和摘要须同时显式为null或同时指定");
                if (input.get("baseApprovedRevisionId") != null) {
                    uuid(input.get("baseApprovedRevisionId"));
                    digest(input.get("baseApprovedContentDigest"));
                }
                if (!(input.get("desiredStatus") instanceof String desired)
                        || !Set.of("ACTIVE", "FROZEN", "INACTIVE").contains(desired))
                    throw FinanceException.invalid("目标状态无效");
                n.put("content", content(input.get("content")));
            } else {
                counter(input.get("expectedRevisionVersion"));
                digest(input.get("contentDigest"));
            }
        }
        return n;
    }
}
