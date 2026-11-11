package com.lumira.finance;

import com.lumira.finance.domain.PrecisionRules;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;

/** Strict EV03 boundary; organizational assertions are recorded, never treated as verification. */
final class FinanceEvidenceInput {
    static final String SCHEMA = "evidence-usage/1";
    static final String SYSTEM = "ORG_MANUAL_EXPENSE_V1";
    static final String PURPOSE = "EXPENSE_ALLOCATION";
    static final String REVERSE_SCOPE = "EVIDENCE_CAPACITY_ONLY_NOT_BANK_REFUND_OR_GL_CORRECTION";

    private FinanceEvidenceInput() {}

    static void fields(Map<String, Object> in, String... names) {
        if (in == null || !in.keySet().equals(Set.of(names)))
            throw FinanceException.invalid("字段缺失或包含未知字段");
    }

    static String text(Object value, int max, boolean multiline) {
        if (!(value instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.chars()
                        .anyMatch(
                                c ->
                                        Character.isISOControl(c)
                                                && !(multiline
                                                        && (c == '\n' || c == '\r' || c == '\t'))))
            throw FinanceException.invalid("文本字段无效");
        return s;
    }

    static String stable(Object value, int max) {
        String s = text(value, max, false);
        if (!s.equals(s.strip())) throw FinanceException.invalid("稳定标识不得包含首尾空白");
        return s;
    }

    static String businessId(Object value, int max) {
        String s = text(value, max, false);
        s = Normalizer.normalize(s, Normalizer.Form.NFC);
        int start = 0, end = s.length();
        while (start < end && s.charAt(start) == ' ') start++;
        while (end > start && s.charAt(end - 1) == ' ') end--;
        s = s.substring(start, end);
        var result = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            result.append(c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c);
        }
        return text(result.toString(), max, false);
    }

    static UUID uuid(Object value) {
        return FinancePersonDirectory.uuid(value);
    }

    static long version(Object value) {
        if (!(value instanceof String s) || !s.matches("[1-9][0-9]{0,18}"))
            throw FinanceException.invalid("版本须为正整数规范字符串");
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw FinanceException.invalid("版本超出范围");
        }
    }

    static String digest(Object value) {
        if (!(value instanceof String s) || !s.matches("[a-f0-9]{64}"))
            throw FinanceException.invalid("内容摘要无效");
        return s;
    }

    static BigDecimal money(Object value, boolean positive) {
        if (!(value instanceof String s)) throw FinanceException.invalid("金额须为字符串");
        if (!s.matches("(0|[1-9][0-9]*)([.][0-9]{1,2})?"))
            throw new FinanceException("INVALID_CURRENCY_SCALE", "人民币金额须为最多两位小数的规范字符串");
        if ((s.indexOf('.') < 0 ? s.length() : s.indexOf('.')) > 20)
            throw new FinanceException("AMOUNT_OVERFLOW", "金额超出numeric(28,8)容量");
        BigDecimal result;
        try {
            result = PrecisionRules.formalCurrencyMoney(s, 2);
        } catch (IllegalArgumentException e) {
            if ("DECIMAL_OVERFLOW".equals(e.getMessage()))
                throw new FinanceException("AMOUNT_OVERFLOW", "金额超出numeric(28,8)容量");
            throw new FinanceException("INVALID_CURRENCY_SCALE", "人民币金额精度无效");
        }
        if (positive && result.signum() <= 0) throw FinanceException.invalid("金额须大于零");
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> review(Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw FinanceException.invalid("金额依据须为对象");
        Map<String, Object> in = (Map<String, Object>) raw;
        fields(
                in,
                "documentType",
                "issuerKey",
                "externalIdentityKey",
                "faceGross",
                "expenseCapacity",
                "basisOriginalId",
                "reason",
                "singleFileSingleDocumentConfirmed",
                "allKnownDocumentIdentifiersDeclared",
                "sameDocumentConfirmed",
                "sourceType",
                "originalLocation");
        if (!"ORDINARY_EXPENSE_INVOICE".equals(in.get("documentType")))
            throw new FinanceException("EVIDENCE_DOCUMENT_BUNDLE_UNSUPPORTED", "首片只支持单文件单普通费用单据");
        for (String key :
                List.of(
                        "singleFileSingleDocumentConfirmed",
                        "allKnownDocumentIdentifiersDeclared",
                        "sameDocumentConfirmed"))
            if (!Boolean.TRUE.equals(in.get(key))) throw FinanceException.invalid("须主动确认单据组织声明");
        String issuer = businessId(in.get("issuerKey"), 128);
        String identity = businessId(in.get("externalIdentityKey"), 200);
        BigDecimal face = money(in.get("faceGross"), true),
                cap = money(in.get("expenseCapacity"), false);
        if (cap.compareTo(face) > 0) throw FinanceException.invalid("费用允许额度不得超过单据面额");
        uuid(in.get("basisOriginalId"));
        text(in.get("reason"), 1000, true);
        if ("PAPER_SCAN".equals(in.get("sourceType"))) text(in.get("originalLocation"), 500, true);
        else if (!"ELECTRONIC_ORIGINAL".equals(in.get("sourceType")))
            throw FinanceException.invalid("原件类型或纸质保管位置无效");
        else if (in.get("originalLocation") != null) text(in.get("originalLocation"), 500, true);
        var result = new LinkedHashMap<>(in);
        result.put("issuerKey", issuer);
        result.put("externalIdentityKey", identity);
        return result;
    }

    static Map<String, Object> normalize(String op, Map<String, Object> in) {
        String[] keys =
                switch (op) {
                    case "register" ->
                            new String[] {
                                "schemaVersion", "idempotencyKey", "originalId", "fields"
                            };
                    case "review-create" ->
                            new String[] {
                                "schemaVersion",
                                "idempotencyKey",
                                "expectedDocumentVersion",
                                "fields"
                            };
                    case "create" ->
                            new String[] {
                                "schemaVersion",
                                "idempotencyKey",
                                "documentId",
                                "expectedBasisVersion",
                                "sourceSystem",
                                "businessChainKey",
                                "sourceOperationKey",
                                "occurredDate",
                                "amount",
                                "purpose",
                                "purposeEvidenceOriginalId",
                                "description"
                            };
                    case "edit" ->
                            new String[] {
                                "schemaVersion",
                                "idempotencyKey",
                                "expectedVersion",
                                "contentDigest",
                                "expectedBasisVersion",
                                "occurredDate",
                                "amount",
                                "purposeEvidenceOriginalId",
                                "description"
                            };
                    case "reverse-create" ->
                            new String[] {
                                "schemaVersion",
                                "idempotencyKey",
                                "expectedAllocationVersion",
                                "allocationContentDigest",
                                "amount",
                                "basisOriginalId",
                                "reason",
                                "scopeOfEffect"
                            };
                    case "review-approve",
                            "review-reject",
                            "review-cancel",
                            "reverse-approve",
                            "reverse-reject",
                            "reverse-cancel",
                            "submit",
                            "confirm",
                            "withdraw",
                            "reject",
                            "admin-terminate",
                            "cancel" ->
                            new String[] {
                                "schemaVersion",
                                "idempotencyKey",
                                "expectedVersion",
                                "contentDigest",
                                "reason"
                            };
                    default -> throw FinanceException.invalid("操作无效");
                };
        fields(in, keys);
        if (!SCHEMA.equals(in.get("schemaVersion"))) throw FinanceException.invalid("协议版本无效");
        stable(in.get("idempotencyKey"), 128);
        var result = new LinkedHashMap<>(in);
        for (String k :
                List.of(
                        "expectedVersion",
                        "expectedDocumentVersion",
                        "expectedBasisVersion",
                        "expectedAllocationVersion")) if (in.containsKey(k)) version(in.get(k));
        for (String k : List.of("contentDigest", "allocationContentDigest"))
            if (in.containsKey(k)) digest(in.get(k));
        for (String k :
                List.of("originalId", "documentId", "purposeEvidenceOriginalId", "basisOriginalId"))
            if (in.containsKey(k)) uuid(in.get(k));
        if (in.containsKey("fields")) result.put("fields", review(in.get("fields")));
        if (in.containsKey("reason")) text(in.get("reason"), 1000, true);
        if (in.containsKey("amount")) money(in.get("amount"), true);
        if (in.containsKey("occurredDate")) {
            Object value = in.get("occurredDate");
            if (!(value instanceof String s) || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw FinanceException.invalid("发生日期无效");
            try {
                LocalDate.parse(s);
            } catch (RuntimeException e) {
                throw FinanceException.invalid("发生日期无效");
            }
        }
        if (in.containsKey("description")) text(in.get("description"), 1000, true);
        if (op.equals("create")) {
            if (!SYSTEM.equals(in.get("sourceSystem")) || !PURPOSE.equals(in.get("purpose")))
                throw new FinanceException("EVIDENCE_USAGE_PURPOSE_UNSUPPORTED", "首片只支持组织手工费用归属份额");
            result.put("businessChainKey", businessId(in.get("businessChainKey"), 128));
            result.put("sourceOperationKey", businessId(in.get("sourceOperationKey"), 128));
        }
        if (op.equals("reverse-create") && !REVERSE_SCOPE.equals(in.get("scopeOfEffect")))
            throw FinanceException.invalid("反向范围须明确仅为单据额度，不代表银行退款或总账冲销");
        return result;
    }
}
