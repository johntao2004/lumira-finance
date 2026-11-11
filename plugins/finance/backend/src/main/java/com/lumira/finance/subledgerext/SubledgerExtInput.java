package com.lumira.finance.subledgerext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import static com.lumira.finance.subledgerext.SubledgerExtRules.*;

/**
 * Closed command schema validator and normalizer.
 * Rejects any extraneous properties with INVALID_INPUT.
 */
public final class SubledgerExtInput {
    private SubledgerExtInput() {}

    private static void checkExactKeys(Map<String, Object> raw, Set<String> expected) {
        require(raw != null, "INVALID_INPUT", "Input body cannot be null");
        if (!raw.keySet().equals(expected)) {
            Set<String> unexpected = new TreeSet<>(raw.keySet());
            unexpected.removeAll(expected);
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(raw.keySet());
            throw new SubledgerException("INVALID_INPUT",
                    "Keys mismatch. Unexpected: " + unexpected + ", Missing: " + missing);
        }
    }

    private static UUID optionalUuid(Object input) {
        if (input == null) return null;
        if (input instanceof String s && s.isBlank()) return null;
        return uuid(input);
    }

    private static String optionalText(Object input, int maxLen) {
        if (input == null) return "";
        if (input instanceof String s && s.isBlank()) return "";
        return text(input, maxLen);
    }

    public static Map<String, Object> normalizePoCreate(Map<String, Object> raw) {
        Set<String> keys = Set.of("idempotencyKey", "poNumber", "supplierId", "supplierRevisionId",
                "orderDate", "terms", "originalId", "lines");
        checkExactKeys(raw, keys);

        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("idempotencyKey", text(raw.get("idempotencyKey"), 128));
        norm.put("poNumber", text(raw.get("poNumber"), 64));
        norm.put("supplierId", uuid(raw.get("supplierId")));
        norm.put("supplierRevisionId", uuid(raw.get("supplierRevisionId")));
        norm.put("orderDate", date(raw.get("orderDate")));
        norm.put("terms", optionalText(raw.get("terms"), 4000));
        norm.put("originalId", optionalUuid(raw.get("originalId")));

        require(raw.get("lines") instanceof List<?>, "INVALID_INPUT", "lines must be a list");
        List<?> rawLines = (List<?>) raw.get("lines");
        require(!rawLines.isEmpty() && rawLines.size() <= 100, "INVALID_INPUT", "lines count must be between 1 and 100");

        List<Map<String, Object>> normLines = new ArrayList<>();
        BigDecimal totalNet = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal totalGross = BigDecimal.ZERO;

        int lineNo = 1;
        for (Object item : rawLines) {
            require(item instanceof Map<?, ?>, "INVALID_INPUT", "each line must be a map");
            @SuppressWarnings("unchecked")
            Map<String, Object> lineMap = (Map<String, Object>) item;
            checkExactKeys(lineMap, Set.of("itemCode", "itemName", "quantity", "unitPrice", "taxRate"));

            Map<String, Object> nLine = new LinkedHashMap<>();
            nLine.put("lineNo", lineNo++);
            nLine.put("itemCode", text(lineMap.get("itemCode"), 64));
            nLine.put("itemName", text(lineMap.get("itemName"), 256));

            BigDecimal qty = money(lineMap.get("quantity"), false);
            BigDecimal uPrice = money(lineMap.get("unitPrice"), true);
            BigDecimal tRate = rate(lineMap.get("taxRate"));

            BigDecimal net = qty.multiply(uPrice).setScale(8, RoundingMode.HALF_UP);
            BigDecimal tax = net.multiply(tRate).setScale(8, RoundingMode.HALF_UP);
            BigDecimal gross = net.add(tax).setScale(8, RoundingMode.HALF_UP);

            nLine.put("quantity", qty);
            nLine.put("unitPrice", uPrice);
            nLine.put("netAmount", net);
            nLine.put("taxRate", tRate);
            nLine.put("taxAmount", tax);
            nLine.put("grossAmount", gross);

            totalNet = totalNet.add(net);
            totalTax = totalTax.add(tax);
            totalGross = totalGross.add(gross);
            normLines.add(nLine);
        }

        norm.put("lines", normLines);
        norm.put("totalNetAmount", totalNet.setScale(8, RoundingMode.HALF_UP));
        norm.put("totalTaxAmount", totalTax.setScale(8, RoundingMode.HALF_UP));
        norm.put("totalGrossAmount", totalGross.setScale(8, RoundingMode.HALF_UP));
        return norm;
    }

    public static Map<String, Object> normalizeAcceptanceCreate(Map<String, Object> raw) {
        Set<String> keys = Set.of("idempotencyKey", "poId", "acceptanceNumber", "acceptedDate",
                "acceptanceNote", "originalId", "lines");
        checkExactKeys(raw, keys);

        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("idempotencyKey", text(raw.get("idempotencyKey"), 128));
        norm.put("poId", uuid(raw.get("poId")));
        norm.put("acceptanceNumber", text(raw.get("acceptanceNumber"), 64));
        norm.put("acceptedDate", date(raw.get("acceptedDate")));
        norm.put("acceptanceNote", optionalText(raw.get("acceptanceNote"), 2000));
        norm.put("originalId", optionalUuid(raw.get("originalId")));

        require(raw.get("lines") instanceof List<?>, "INVALID_INPUT", "lines must be a list");
        List<?> rawLines = (List<?>) raw.get("lines");
        require(!rawLines.isEmpty() && rawLines.size() <= 100, "INVALID_INPUT", "lines count must be between 1 and 100");

        List<Map<String, Object>> normLines = new ArrayList<>();
        int lineNo = 1;
        for (Object item : rawLines) {
            require(item instanceof Map<?, ?>, "INVALID_INPUT", "each line must be a map");
            @SuppressWarnings("unchecked")
            Map<String, Object> lineMap = (Map<String, Object>) item;
            checkExactKeys(lineMap, Set.of("poLineId", "acceptedQuantity", "unitCostEstimate"));

            Map<String, Object> nLine = new LinkedHashMap<>();
            nLine.put("lineNo", lineNo++);
            nLine.put("poLineId", uuid(lineMap.get("poLineId")));
            BigDecimal qty = money(lineMap.get("acceptedQuantity"), false);
            BigDecimal estUnit = money(lineMap.get("unitCostEstimate"), true);
            BigDecimal estNet = qty.multiply(estUnit).setScale(8, RoundingMode.HALF_UP);

            nLine.put("acceptedQuantity", qty);
            nLine.put("unitCostEstimate", estUnit);
            nLine.put("estimatedNetAmount", estNet);
            normLines.add(nLine);
        }
        norm.put("lines", normLines);
        return norm;
    }

    public static Map<String, Object> normalizeProvisionalAccrualCreate(Map<String, Object> raw) {
        Set<String> keys = Set.of("idempotencyKey", "acceptanceId", "poId", "accrualPeriod",
                "accrualDate", "estimatedNetAmount", "reason");
        checkExactKeys(raw, keys);

        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("idempotencyKey", text(raw.get("idempotencyKey"), 128));
        norm.put("acceptanceId", uuid(raw.get("acceptanceId")));
        norm.put("poId", uuid(raw.get("poId")));
        norm.put("accrualPeriod", integer(raw.get("accrualPeriod"), 1, 12));
        norm.put("accrualDate", date(raw.get("accrualDate")));
        norm.put("estimatedNetAmount", money(raw.get("estimatedNetAmount"), false));
        norm.put("reason", text(raw.get("reason"), 1000));
        return norm;
    }

    private static void checkAllowedKeys(Map<String, Object> raw, Set<String> required, Set<String> optional) {
        require(raw != null, "INVALID_INPUT", "Input body cannot be null");
        Set<String> allAllowed = new HashSet<>(required);
        allAllowed.addAll(optional);
        if (!allAllowed.containsAll(raw.keySet()) || !raw.keySet().containsAll(required)) {
            Set<String> unexpected = new TreeSet<>(raw.keySet());
            unexpected.removeAll(allAllowed);
            Set<String> missing = new TreeSet<>(required);
            missing.removeAll(raw.keySet());
            throw new SubledgerException("INVALID_INPUT",
                    "Keys mismatch. Unexpected: " + unexpected + ", Missing: " + missing);
        }
    }

    public static Map<String, Object> normalizeProvisionalSettlementCreate(Map<String, Object> raw) {
        Set<String> requiredKeys = Set.of("idempotencyKey", "provisionalId", "invoiceNumber", "invoiceDate",
                "actualNetAmount", "actualTaxAmount", "actualGrossAmount");
        Set<String> optionalKeys = Set.of("invoicedQuantity");
        checkAllowedKeys(raw, requiredKeys, optionalKeys);

        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("idempotencyKey", text(raw.get("idempotencyKey"), 128));
        norm.put("provisionalId", uuid(raw.get("provisionalId")));
        norm.put("invoiceNumber", text(raw.get("invoiceNumber"), 64));
        norm.put("invoiceDate", date(raw.get("invoiceDate")));

        BigDecimal net = money(raw.get("actualNetAmount"), true);
        BigDecimal tax = money(raw.get("actualTaxAmount"), true);
        BigDecimal gross = money(raw.get("actualGrossAmount"), true);

        require(net.add(tax).compareTo(gross) == 0, "INVALID_INPUT",
                "actualNetAmount + actualTaxAmount must equal actualGrossAmount");

        norm.put("actualNetAmount", net);
        norm.put("actualTaxAmount", tax);
        norm.put("actualGrossAmount", gross);

        if (raw.containsKey("invoicedQuantity") && raw.get("invoicedQuantity") != null) {
            norm.put("invoicedQuantity", money(raw.get("invoicedQuantity"), false));
        } else {
            norm.put("invoicedQuantity", null);
        }
        return norm;
    }

    public static Map<String, Object> normalizeContractAssetCreate(Map<String, Object> raw) {
        Set<String> keys = Set.of("idempotencyKey", "contractId", "customerId", "stageName",
                "milestoneDate", "assetAmount", "conditionDescription");
        checkExactKeys(raw, keys);

        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("idempotencyKey", text(raw.get("idempotencyKey"), 128));
        norm.put("contractId", uuid(raw.get("contractId")));
        norm.put("customerId", uuid(raw.get("customerId")));
        norm.put("stageName", text(raw.get("stageName"), 128));
        norm.put("milestoneDate", date(raw.get("milestoneDate")));
        norm.put("assetAmount", money(raw.get("assetAmount"), false));
        norm.put("conditionDescription", text(raw.get("conditionDescription"), 2000));
        return norm;
    }

    public static Map<String, Object> normalizeContractAssetReclassify(Map<String, Object> raw) {
        Set<String> keys = Set.of("idempotencyKey", "reclassifiedDate", "reason");
        checkExactKeys(raw, keys);

        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("idempotencyKey", text(raw.get("idempotencyKey"), 128));
        norm.put("reclassifiedDate", date(raw.get("reclassifiedDate")));
        norm.put("reason", text(raw.get("reason"), 1000));
        return norm;
    }

    public static Map<String, Object> normalizeVariableConsiderationCreate(Map<String, Object> raw) {
        Set<String> keys = Set.of("idempotencyKey", "contractId", "variableKind",
                "estimatedAmount", "confidenceScore", "constrainedCapAmount", "capRationale");
        checkExactKeys(raw, keys);

        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("idempotencyKey", text(raw.get("idempotencyKey"), 128));
        norm.put("contractId", uuid(raw.get("contractId")));
        norm.put("variableKind", VariableKind.valueOf(text(raw.get("variableKind"), 32)));

        BigDecimal est = money(raw.get("estimatedAmount"), true);
        BigDecimal conf = confidence(raw.get("confidenceScore"));
        BigDecimal cap = money(raw.get("constrainedCapAmount"), true);

        // Highly probable condition check: cap cannot exceed estimated * confidence
        BigDecimal maxAllowedCap = est.multiply(conf).setScale(8, RoundingMode.HALF_UP);
        require(cap.compareTo(maxAllowedCap) <= 0, "VARIABLE_CAP_EXCEEDS_CONSTRAINED_LIMIT",
                "Constrained cap amount " + cap.toPlainString() + " exceeds maximum allowed limit " + maxAllowedCap.toPlainString());

        norm.put("estimatedAmount", est);
        norm.put("confidenceScore", conf);
        norm.put("constrainedCapAmount", cap);
        norm.put("capRationale", text(raw.get("capRationale"), 2000));
        return norm;
    }

    public static Map<String, Object> normalizeVariableConsiderationRevise(Map<String, Object> raw) {
        Set<String> keys = Set.of("idempotencyKey", "revisedCapAmount", "revisionReason");
        checkExactKeys(raw, keys);

        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("idempotencyKey", text(raw.get("idempotencyKey"), 128));
        norm.put("revisedCapAmount", money(raw.get("revisedCapAmount"), true));
        norm.put("revisionReason", text(raw.get("revisionReason"), 2000));
        return norm;
    }

    public static Map<String, Object> normalizeMultiYearSync(Map<String, Object> raw) {
        Set<String> keys = Set.of("idempotencyKey", "contractId", "startYear", "endYear",
                "totalContractValue", "cumulativeProgressPct", "syncYear");
        checkExactKeys(raw, keys);

        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("idempotencyKey", text(raw.get("idempotencyKey"), 128));
        norm.put("contractId", uuid(raw.get("contractId")));
        int sy = integer(raw.get("startYear"), 2000, 2099);
        int ey = integer(raw.get("endYear"), 2000, 2099);
        require(ey >= sy, "INVALID_INPUT", "endYear must be >= startYear");
        norm.put("startYear", sy);
        norm.put("endYear", ey);
        norm.put("totalContractValue", money(raw.get("totalContractValue"), true));
        norm.put("cumulativeProgressPct", rate(raw.get("cumulativeProgressPct")));
        norm.put("syncYear", integer(raw.get("syncYear"), 2000, 2099));
        return norm;
    }

    public static Map<String, Object> normalizeEclCreate(Map<String, Object> raw) {
        Set<String> keys = Set.of("idempotencyKey", "period", "assessmentType", "customerId",
                "receivableAmount", "agingBucket", "lossRate", "priorAllowance", "operationKind", "evidenceId");
        checkExactKeys(raw, keys);

        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("idempotencyKey", text(raw.get("idempotencyKey"), 128));
        norm.put("period", integer(raw.get("period"), 1, 12));
        norm.put("assessmentType", EclAssessmentType.valueOf(text(raw.get("assessmentType"), 32)));
        norm.put("customerId", uuid(raw.get("customerId")));
        norm.put("receivableAmount", money(raw.get("receivableAmount"), true));
        norm.put("agingBucket", AgingBucket.valueOf(text(raw.get("agingBucket"), 32)));
        norm.put("lossRate", rate(raw.get("lossRate")));
        norm.put("priorAllowance", money(raw.get("priorAllowance"), true));
        norm.put("operationKind", EclOperationKind.valueOf(text(raw.get("operationKind"), 32)));
        norm.put("evidenceId", optionalUuid(raw.get("evidenceId")));
        return norm;
    }

    public static Map<String, Object> normalizeAllowanceAdjustmentCreate(Map<String, Object> raw) {
        Set<String> keys = Set.of("idempotencyKey", "customerId", "contractId", "receivableId",
                "adjustmentType", "creditNoteNumber", "adjustmentDate", "originalReceivableAmount",
                "netAdjustmentAmount", "taxAdjustmentAmount", "grossAdjustmentAmount",
                "vatRedInvoiceInfo", "reason");
        checkExactKeys(raw, keys);

        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("idempotencyKey", text(raw.get("idempotencyKey"), 128));
        norm.put("customerId", uuid(raw.get("customerId")));
        norm.put("contractId", optionalUuid(raw.get("contractId")));
        norm.put("receivableId", optionalUuid(raw.get("receivableId")));
        norm.put("adjustmentType", AllowanceType.valueOf(text(raw.get("adjustmentType"), 32)));
        norm.put("creditNoteNumber", text(raw.get("creditNoteNumber"), 64));
        norm.put("adjustmentDate", date(raw.get("adjustmentDate")));
        norm.put("originalReceivableAmount", money(raw.get("originalReceivableAmount"), true));

        BigDecimal net = money(raw.get("netAdjustmentAmount"), true);
        BigDecimal tax = money(raw.get("taxAdjustmentAmount"), true);
        BigDecimal gross = money(raw.get("grossAdjustmentAmount"), true);
        require(net.add(tax).compareTo(gross) == 0, "INVALID_INPUT",
                "netAdjustmentAmount + taxAdjustmentAmount must equal grossAdjustmentAmount");

        norm.put("netAdjustmentAmount", net);
        norm.put("taxAdjustmentAmount", tax);
        norm.put("grossAdjustmentAmount", gross);
        norm.put("vatRedInvoiceInfo", optionalText(raw.get("vatRedInvoiceInfo"), 128));
        norm.put("reason", text(raw.get("reason"), 1000));
        return norm;
    }

    public static Map<String, Object> normalizeApproval(Map<String, Object> raw) {
        Set<String> keys = Set.of("idempotencyKey", "reason");
        checkExactKeys(raw, keys);

        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("idempotencyKey", text(raw.get("idempotencyKey"), 128));
        norm.put("reason", optionalText(raw.get("reason"), 1000));
        return norm;
    }
}
