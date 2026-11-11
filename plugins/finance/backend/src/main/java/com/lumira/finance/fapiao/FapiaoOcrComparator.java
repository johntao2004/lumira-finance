package com.lumira.finance.fapiao;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * EV02: OCR vs Structured field cross-check comparator.
 * Detects discrepancies and enqueues to manual review queue.
 */
public final class FapiaoOcrComparator {

    public record OcrResult(
            String fapiaoNumber,
            String buyerTaxNo,
            String sellerTaxNo,
            BigDecimal untaxedAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            String rawOcrDigest
    ) {}

    public record Discrepancy(
            String fieldName,
            String structuredValue,
            String ocrValue,
            String deltaDescription
    ) {}

    public record ComparisonResult(
            String status, // VERIFIED_MATCH, MISMATCH_MANUAL_REVIEW, FAILED
            String verificationMethod,
            String taxBureauVerification, // Strictly NOT_VERIFIED
            Map<String, Discrepancy> discrepancies,
            String structuralDigest,
            String ocrDigest
    ) {
        public boolean isMatch() {
            return "VERIFIED_MATCH".equals(status);
        }
    }

    private FapiaoOcrComparator() {}

    public static ComparisonResult compare(FapiaoStructuredData structured, OcrResult ocr) {
        Objects.requireNonNull(structured, "structured data cannot be null");
        Objects.requireNonNull(ocr, "ocr data cannot be null");

        Map<String, Discrepancy> discrepancies = new LinkedHashMap<>();

        // 1. Compare fapiao number
        if (ocr.fapiaoNumber != null && !ocr.fapiaoNumber.isBlank()) {
            if (!structured.fapiaoNumber().equalsIgnoreCase(ocr.fapiaoNumber.trim())) {
                discrepancies.put("fapiaoNumber", new Discrepancy(
                        "fapiaoNumber",
                        structured.fapiaoNumber(),
                        ocr.fapiaoNumber.trim(),
                        "发票号码不匹配"
                ));
            }
        }

        // 2. Compare buyer tax number
        if (ocr.buyerTaxNo != null && !ocr.buyerTaxNo.isBlank()) {
            if (!structured.buyerTaxNo().equalsIgnoreCase(ocr.buyerTaxNo.trim())) {
                discrepancies.put("buyerTaxNo", new Discrepancy(
                        "buyerTaxNo",
                        structured.buyerTaxNo(),
                        ocr.buyerTaxNo.trim(),
                        "购买方税号不匹配"
                ));
            }
        }

        // 3. Compare seller tax number
        if (ocr.sellerTaxNo != null && !ocr.sellerTaxNo.isBlank()) {
            if (!structured.sellerTaxNo().equalsIgnoreCase(ocr.sellerTaxNo.trim())) {
                discrepancies.put("sellerTaxNo", new Discrepancy(
                        "sellerTaxNo",
                        structured.sellerTaxNo(),
                        ocr.sellerTaxNo.trim(),
                        "销售方税号不匹配"
                ));
            }
        }

        // 4. Compare total amount
        if (ocr.totalAmount != null) {
            BigDecimal ocrTotal = ocr.totalAmount.setScale(2, RoundingMode.HALF_UP);
            if (structured.totalAmount().compareTo(ocrTotal) != 0) {
                BigDecimal diff = structured.totalAmount().subtract(ocrTotal).abs();
                discrepancies.put("totalAmount", new Discrepancy(
                        "totalAmount",
                        structured.totalAmount().toPlainString(),
                        ocrTotal.toPlainString(),
                        "价税合计差异: " + diff.toPlainString()
                ));
            }
        }

        // 5. Compare tax amount
        if (ocr.taxAmount != null) {
            BigDecimal ocrTax = ocr.taxAmount.setScale(2, RoundingMode.HALF_UP);
            if (structured.taxAmount().compareTo(ocrTax) != 0) {
                BigDecimal diff = structured.taxAmount().subtract(ocrTax).abs();
                discrepancies.put("taxAmount", new Discrepancy(
                        "taxAmount",
                        structured.taxAmount().toPlainString(),
                        ocrTax.toPlainString(),
                        "税额差异: " + diff.toPlainString()
                ));
            }
        }

        // 6. Compare untaxed amount
        if (ocr.untaxedAmount != null) {
            BigDecimal ocrUntaxed = ocr.untaxedAmount.setScale(2, RoundingMode.HALF_UP);
            if (structured.untaxedAmount().compareTo(ocrUntaxed) != 0) {
                BigDecimal diff = structured.untaxedAmount().subtract(ocrUntaxed).abs();
                discrepancies.put("untaxedAmount", new Discrepancy(
                        "untaxedAmount",
                        structured.untaxedAmount().toPlainString(),
                        ocrUntaxed.toPlainString(),
                        "不含税金额差异: " + diff.toPlainString()
                ));
            }
        }

        String status = discrepancies.isEmpty() ? "VERIFIED_MATCH" : "MISMATCH_MANUAL_REVIEW";
        return new ComparisonResult(
                status,
                "OCR_FIELD_CROSS_CHECK",
                "NOT_VERIFIED", // Rule: Never claim live government tax verification
                Collections.unmodifiableMap(discrepancies),
                structured.payloadDigest(),
                ocr.rawOcrDigest != null ? ocr.rawOcrDigest : ""
        );
    }
}
