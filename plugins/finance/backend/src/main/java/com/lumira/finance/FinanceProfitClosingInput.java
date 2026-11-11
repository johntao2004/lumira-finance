package com.lumira.finance;

import java.time.*;
import java.util.*;

/** Client supplies intent and expected server source, never generated journal lines or actors. */
final class FinanceProfitClosingInput {
    static final String SCHEMA = "profit-closing/1";
    static final Set<String> KINDS = Set.of("PROFIT_LOSS", "YEAR_END", "TRANSFER_REVERSAL");
    static final Set<String> SOURCE_KEYS =
            Set.of(
                    "tenantId",
                    "bookId",
                    "yearId",
                    "cutoff",
                    "modeEpoch",
                    "modeGeneration",
                    "periodId",
                    "periodVersion",
                    "includedPostingSeq",
                    "openingVersion",
                    "baselineVersion",
                    "sourceCloseVersion",
                    "accountSemanticDigest");

    private FinanceProfitClosingInput() {}

    static void fields(Map<String, Object> input, Set<String> fields) {
        if (input == null || !fields.equals(input.keySet()))
            throw FinanceException.invalid("字段缺失或未知");
    }

    static UUID uuid(Object value) {
        return FinancePersonDirectory.uuid(value);
    }

    static String digest(Object value) {
        return FinanceEvidenceInput.digest(value);
    }

    static String text(Object value, int max) {
        return FinanceEvidenceInput.text(value, max, true);
    }

    static long counter(Object value, boolean zero) {
        if (!(value instanceof String s)
                || !s.matches(zero ? "(0|[1-9][0-9]{0,18})" : "[1-9][0-9]{0,18}"))
            throw FinanceException.invalid("版本须为规范十进制字符串");
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw FinanceException.invalid("版本超出范围");
        }
    }

    static String kind(Object value) {
        if (!(value instanceof String s) || !KINDS.contains(s))
            throw new FinanceException("PROFIT_CLOSING_UNSUPPORTED_KIND", "只支持损益、本年利润转存及其受控完整反向");
        return s;
    }

    static LocalDate cutoff(Object value) {
        if (!(value instanceof String s) || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw FinanceException.invalid("截止日期须为完整日期");
        try {
            var date = LocalDate.parse(s);
            if (date.getDayOfMonth() != date.lengthOfMonth())
                throw FinanceException.invalid("截止日须为自然月末");
            return date;
        } catch (DateTimeException invalid) {
            throw FinanceException.invalid("截止日期无效");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> source(Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw FinanceException.invalid("预期来源须为对象");
        var source = (Map<String, Object>) raw;
        fields(source, SOURCE_KEYS);
        for (String key : List.of("tenantId", "bookId", "yearId", "modeEpoch", "periodId"))
            uuid(source.get(key));
        cutoff(source.get("cutoff"));
        for (String key :
                List.of(
                        "modeGeneration",
                        "periodVersion",
                        "includedPostingSeq",
                        "openingVersion",
                        "baselineVersion",
                        "sourceCloseVersion")) counter(source.get(key), true);
        digest(source.get("accountSemanticDigest"));
        return new LinkedHashMap<>(source);
    }

    static Map<String, Object> normalized(String operation, Map<String, Object> input) {
        Set<String> fields =
                operation.equals("create")
                        ? Set.of(
                                "schemaVersion",
                                "idempotencyKey",
                                "operationId",
                                "producerKind",
                                "cutoff",
                                "expectedSource",
                                "expectedSourceDigest",
                                "expectedGeneratedDigest",
                                "predecessorPostedRunId",
                                "supersedesCancelledAttemptId",
                                "expectedSupersededVersion",
                                "reversesRunId",
                                "reason")
                        : operation.equals("approve")
                                ? Set.of(
                                        "schemaVersion",
                                        "idempotencyKey",
                                        "expectedVersion",
                                        "expectedContentVersion",
                                        "contentDigest",
                                        "reason",
                                        "requestedExpiresAt")
                                : operation.equals("post")
                                        ? Set.of(
                                                "schemaVersion",
                                                "idempotencyKey",
                                                "expectedVersion",
                                                "expectedContentVersion",
                                                "contentDigest",
                                                "reason",
                                                "approvalId")
                                        : Set.of(
                                                "schemaVersion",
                                                "idempotencyKey",
                                                "expectedVersion",
                                                "expectedContentVersion",
                                                "contentDigest",
                                                "reason");
        if (!Set.of(
                        "create",
                        "submit",
                        "approve",
                        "post",
                        "reject",
                        "withdraw",
                        "cancel",
                        "admin-terminate")
                .contains(operation)) throw FinanceException.invalid("操作无效");
        fields(input, fields);
        if (!SCHEMA.equals(input.get("schemaVersion"))) throw FinanceException.invalid("协议版本无效");
        FinanceEvidenceInput.stable(input.get("idempotencyKey"), 128);
        text(input.get("reason"), 1000);
        var out = new LinkedHashMap<>(input);
        if (operation.equals("create")) {
            uuid(input.get("operationId"));
            String kind = kind(input.get("producerKind"));
            var date = cutoff(input.get("cutoff"));
            if (kind.equals("YEAR_END") && date.getMonthValue() != 12)
                throw FinanceException.invalid("本年利润转存仅支持12月31日");
            out.put("expectedSource", source(input.get("expectedSource")));
            digest(input.get("expectedSourceDigest"));
            digest(input.get("expectedGeneratedDigest"));
            for (String key :
                    List.of(
                            "predecessorPostedRunId",
                            "supersedesCancelledAttemptId",
                            "reversesRunId")) if (input.get(key) != null) uuid(input.get(key));
            if ((input.get("supersedesCancelledAttemptId") == null)
                    != (input.get("expectedSupersededVersion") == null))
                throw FinanceException.invalid("显式重建须同时绑定已取消attempt及其版本");
            if (input.get("expectedSupersededVersion") != null)
                counter(input.get("expectedSupersededVersion"), false);
            if (kind.equals("TRANSFER_REVERSAL") != (input.get("reversesRunId") != null))
                throw FinanceException.invalid("完整反向须且仅须指定真实原结转Run");
        } else {
            counter(input.get("expectedVersion"), false);
            counter(input.get("expectedContentVersion"), false);
            digest(input.get("contentDigest"));
            if (operation.equals("post")) uuid(input.get("approvalId"));
            if (operation.equals("approve") && input.get("requestedExpiresAt") != null) {
                if (!(input.get("requestedExpiresAt") instanceof String expires)
                        || expires.length() > 64) throw FinanceException.invalid("批准期限无效");
                try {
                    OffsetDateTime.parse(expires).toInstant();
                } catch (DateTimeException invalid) {
                    throw FinanceException.invalid("批准期限须为带时区完整时间");
                }
            }
        }
        return out;
    }
}
