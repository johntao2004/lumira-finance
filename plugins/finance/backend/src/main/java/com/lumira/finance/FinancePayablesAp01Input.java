package com.lumira.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/** Strict AP intent; generated accounts, amounts, identities and journal lines are server-owned. */
final class FinancePayablesAp01Input {
    static final String SCHEMA = "payables-ap01/1";
    static final Map<String, Set<String>> KEYS =
            Map.ofEntries(
                    Map.entry(
                            "DocumentExpectation",
                            Set.of(
                                    "documentId",
                                    "documentVersion",
                                    "currentReviewId",
                                    "basisVersion",
                                    "basisContentDigest",
                                    "sourceOriginalVersion",
                                    "sourceOriginalSha256")),
                    Map.entry(
                            "SupplierExpectation",
                            Set.of(
                                    "counterpartyId",
                                    "governanceHomeBookId",
                                    "registryVersion",
                                    "approvedRevisionId",
                                    "approvedRevisionDigest")),
                    Map.entry(
                            "ParentExpectation",
                            Set.of(
                                    "allocationId",
                                    "documentId",
                                    "chainId",
                                    "version",
                                    "contentVersion",
                                    "contentDigest",
                                    "submissionId",
                                    "usageId",
                                    "confirmationId",
                                    "confirmedAmount",
                                    "approvedReversedAmount",
                                    "netConfirmedAmount",
                                    "documentExpectation")),
                    Map.entry(
                            "BasisExpectation",
                            Set.of(
                                    "basisId",
                                    "basisVersion",
                                    "contentDigest",
                                    "documentExpectation")),
                    Map.entry(
                            "BasisCreateRequest",
                            Set.of(
                                    "schemaVersion",
                                    "idempotencyKey",
                                    "reason",
                                    "documentExpectation",
                                    "expectedCurrentBasisId",
                                    "expectedCurrentBasisVersion",
                                    "accountingCapacity",
                                    "basisOriginalId",
                                    "recognitionEligibilityReason",
                                    "organizationReviewedNotTaxVerified")),
                    Map.entry(
                            "BasisActionRequest",
                            Set.of(
                                    "schemaVersion",
                                    "idempotencyKey",
                                    "reason",
                                    "expectedVersion",
                                    "contentDigest")),
                    Map.entry(
                            "CreateRequest",
                            Set.of(
                                    "schemaVersion",
                                    "idempotencyKey",
                                    "reason",
                                    "operationId",
                                    "expectedPredecessorId",
                                    "expectedPredecessorVersion",
                                    "expectedParent",
                                    "expectedSupplier",
                                    "expectedAccountingBasis",
                                    "accountingDate",
                                    "dueDate",
                                    "expenseAccountId",
                                    "payableAccountId",
                                    "serviceAcceptanceOriginalId",
                                    "recognitionOriginalId",
                                    "recognitionReason",
                                    "summary",
                                    "ordinaryServiceExpenseConfirmed",
                                    "supplierMatchesDocumentConfirmed",
                                    "serviceAcceptedConfirmed",
                                    "noTaxAssetInventoryPayrollConfirmed",
                                    "payableAccountUseConfirmed")),
                    Map.entry(
                            "ActionRequest",
                            Set.of(
                                    "schemaVersion",
                                    "idempotencyKey",
                                    "reason",
                                    "expectedVersion",
                                    "expectedContentVersion",
                                    "contentDigest",
                                    "sourceDigest")),
                    Map.entry(
                            "SubmitRequest",
                            Set.of(
                                    "schemaVersion",
                                    "idempotencyKey",
                                    "reason",
                                    "expectedVersion",
                                    "expectedContentVersion",
                                    "contentDigest",
                                    "sourceDigest",
                                    "requestedExpiresAt")),
                    Map.entry(
                            "ApproveRequest",
                            Set.of(
                                    "schemaVersion",
                                    "idempotencyKey",
                                    "reason",
                                    "expectedVersion",
                                    "expectedContentVersion",
                                    "contentDigest",
                                    "sourceDigest",
                                    "submissionId",
                                    "requestedExpiresAt")),
                    Map.entry(
                            "PostRequest",
                            Set.of(
                                    "schemaVersion",
                                    "idempotencyKey",
                                    "reason",
                                    "expectedVersion",
                                    "expectedContentVersion",
                                    "contentDigest",
                                    "sourceDigest",
                                    "submissionId",
                                    "approvalId")),
                    Map.entry(
                            "FullReversalRequest",
                            Set.of(
                                    "schemaVersion",
                                    "idempotencyKey",
                                    "reason",
                                    "operationId",
                                    "expectedPredecessorId",
                                    "expectedPredecessorVersion",
                                    "expectedOriginalVersion",
                                    "expectedOriginalContentDigest",
                                    "originalPostReceiptId",
                                    "originalOpenItemId",
                                    "expectedOpenItemVersion",
                                    "accountingReasonOriginalId")));

    private FinancePayablesAp01Input() {}

    static UUID uuid(Object v) {
        try {
            return FinancePersonDirectory.uuid(v);
        } catch (IllegalArgumentException e) {
            throw FinanceException.invalid("UUID须为规范格式");
        }
    }

    static void fields(Map<String, Object> v, String schema) {
        if (v == null || !KEYS.get(schema).equals(v.keySet()))
            throw FinanceException.invalid("字段缺失或未知");
    }

    static String counter(Object v, boolean zero) {
        FinanceProfitClosingInput.counter(v, zero);
        return v.toString();
    }

    static String digest(Object v) {
        return FinanceEvidenceInput.digest(v);
    }

    static String text(Object v, int max) {
        if (!(v instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.codePoints()
                        .anyMatch(c -> Character.isISOControl(c) && c != 9 && c != 10 && c != 13))
            throw FinanceException.invalid("文本为空、过长或包含不支持控制符");
        return s;
    }

    static String money(Object v) {
        if (!(v instanceof String s) || !s.matches("(0|[1-9][0-9]{0,19})(\\.[0-9]{1,2})?"))
            throw new FinanceException("INVALID_CURRENCY_SCALE", "CNY金额须为最多两位规范十进制字符串");
        try {
            return new BigDecimal(s).setScale(8, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException e) {
            throw FinanceException.invalid("金额不可表示");
        }
    }

    static String date(Object v) {
        if (!(v instanceof String s) || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw FinanceException.invalid("日期格式无效");
        try {
            LocalDate.parse(s);
            return s;
        } catch (DateTimeException e) {
            throw FinanceException.invalid("日期无效");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> expectation(Object v, String schema) {
        if (!(v instanceof Map<?, ?>)) throw FinanceException.invalid("预期快照须为对象");
        var m = (Map<String, Object>) v;
        fields(m, schema);
        for (var e : m.entrySet()) {
            String k = e.getKey();
            Object x = e.getValue();
            if (k.equals("documentExpectation")) {
                expectation(x, "DocumentExpectation");
                continue;
            }
            if (k.endsWith("Id")) {
                uuid(x);
                continue;
            }
            if (k.endsWith("Digest") || k.endsWith("Sha256")) {
                digest(x);
                continue;
            }
            if (k.endsWith("Amount")) {
                if (!(x instanceof String s) || !s.matches("(0|[1-9][0-9]{0,19})\\.[0-9]{8}"))
                    throw FinanceException.invalid("预期金额须为内部八位格式");
                continue;
            }
            counter(x, true);
        }
        return new LinkedHashMap<>(m);
    }

    static Map<String, Object> normalized(String op, Map<String, Object> input) {
        String schema =
                switch (op) {
                    case "basis-create" -> "BasisCreateRequest";
                    case "basis-approve", "basis-reject", "basis-cancel" -> "BasisActionRequest";
                    case "create" -> "CreateRequest";
                    case "full-reversal" -> "FullReversalRequest";
                    case "submit" -> "SubmitRequest";
                    case "approve" -> "ApproveRequest";
                    case "post" -> "PostRequest";
                    case "withdraw", "reject", "cancel", "admin-terminate" -> "ActionRequest";
                    default -> throw FinanceException.invalid("未知命令");
                };
        fields(input, schema);
        var n = new LinkedHashMap<String, Object>(input);
        if (!SCHEMA.equals(n.get("schemaVersion"))) throw FinanceException.invalid("协议版本无效");
        for (var e : n.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k.equals("schemaVersion")) continue;
            if (Set.of(
                                    "expectedPredecessorId",
                                    "expectedPredecessorVersion",
                                    "expectedCurrentBasisId",
                                    "expectedCurrentBasisVersion",
                                    "dueDate")
                            .contains(k)
                    && v == null) continue;
            if (k.equals("idempotencyKey")) {
                String s = text(v, 128);
                if (!s.equals(s.strip()) || s.codePoints().anyMatch(Character::isISOControl))
                    throw FinanceException.invalid("请求键须为严格文本");
                continue;
            }
            if (k.equals("documentExpectation")) {
                expectation(v, "DocumentExpectation");
                continue;
            }
            if (k.equals("expectedParent")) {
                expectation(v, "ParentExpectation");
                continue;
            }
            if (k.equals("expectedSupplier")) {
                expectation(v, "SupplierExpectation");
                continue;
            }
            if (k.equals("expectedAccountingBasis")) {
                expectation(v, "BasisExpectation");
                continue;
            }
            if (k.equals("requestedExpiresAt")) {
                if (v == null) continue;
                try {
                    if (!(v instanceof String)) throw FinanceException.invalid("有效期格式无效");
                    Instant.parse((String) v);
                } catch (DateTimeException ex) {
                    throw FinanceException.invalid("有效期格式无效");
                }
                continue;
            }
            if (k.endsWith("Confirmed") || k.equals("organizationReviewedNotTaxVerified")) {
                if (!Boolean.TRUE.equals(v)) throw FinanceException.invalid("须明确确认组织声明");
                continue;
            }
            if (k.endsWith("Id")) {
                uuid(v);
                continue;
            }
            if (k.endsWith("Version")) {
                counter(v, false);
                continue;
            }
            if (k.endsWith("Digest")) {
                digest(v);
                continue;
            }
            if (k.equals("accountingCapacity")) {
                money(v);
                continue;
            }
            if (k.equals("accountingDate") || k.equals("dueDate")) {
                date(v);
                continue;
            }
            text(v, k.equals("summary") ? 500 : 1000);
        }
        if (schema.equals("CreateRequest") || schema.equals("FullReversalRequest")) {
            if ((n.get("expectedPredecessorId") == null)
                    != (n.get("expectedPredecessorVersion") == null))
                throw FinanceException.invalid("前驱ID与版本须同时提供或同时为空");
        }
        if (schema.equals("BasisCreateRequest")
                && (n.get("expectedCurrentBasisId") == null)
                        != (n.get("expectedCurrentBasisVersion") == null))
            throw FinanceException.invalid("当前基准ID与版本须同时提供或同时为空");
        return n;
    }
}
