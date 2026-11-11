package com.lumira.finance.fapiao;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * EV02 & FP01: Structured normalized invoice representation.
 */
public record FapiaoStructuredData(
        String invoiceCode,
        String fapiaoNumber,
        FapiaoType fapiaoType,
        LocalDate issueDate,
        String buyerTaxNo,
        String buyerName,
        String sellerTaxNo,
        String sellerName,
        BigDecimal untaxedAmount,
        BigDecimal taxRate,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        List<Item> items,
        String payloadDigest
) {
    public record Item(
            String goodsServiceName,
            String goodsTaxCode,
            BigDecimal unitPrice,
            BigDecimal quantity,
            BigDecimal untaxedAmount,
            BigDecimal taxRate,
            BigDecimal taxAmount,
            BigDecimal totalAmount
    ) {
        public Item {
            Objects.requireNonNull(goodsServiceName, "goodsServiceName cannot be null");
            untaxedAmount = untaxedAmount != null ? untaxedAmount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            taxAmount = taxAmount != null ? taxAmount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            totalAmount = totalAmount != null ? totalAmount.setScale(2, RoundingMode.HALF_UP) : untaxedAmount.add(taxAmount);
        }
    }

    public FapiaoStructuredData {
        invoiceCode = invoiceCode != null ? invoiceCode.trim() : "";
        fapiaoNumber = Objects.requireNonNull(fapiaoNumber, "fapiaoNumber cannot be null").trim();
        fapiaoType = fapiaoType != null ? fapiaoType : FapiaoType.ELECTRONIC_GENERAL;
        issueDate = Objects.requireNonNull(issueDate, "issueDate cannot be null");
        buyerTaxNo = Objects.requireNonNull(buyerTaxNo, "buyerTaxNo cannot be null").trim();
        buyerName = Objects.requireNonNull(buyerName, "buyerName cannot be null").trim();
        sellerTaxNo = Objects.requireNonNull(sellerTaxNo, "sellerTaxNo cannot be null").trim();
        sellerName = Objects.requireNonNull(sellerName, "sellerName cannot be null").trim();
        untaxedAmount = Objects.requireNonNull(untaxedAmount, "untaxedAmount cannot be null").setScale(2, RoundingMode.HALF_UP);
        taxRate = taxRate != null ? taxRate : BigDecimal.ZERO;
        taxAmount = Objects.requireNonNull(taxAmount, "taxAmount cannot be null").setScale(2, RoundingMode.HALF_UP);
        totalAmount = Objects.requireNonNull(totalAmount, "totalAmount cannot be null").setScale(2, RoundingMode.HALF_UP);
        items = items != null ? Collections.unmodifiableList(new ArrayList<>(items)) : List.of();
        if (payloadDigest == null || payloadDigest.isBlank()) {
            payloadDigest = computeDigest(invoiceCode, fapiaoNumber, issueDate, buyerTaxNo, sellerTaxNo, untaxedAmount, taxAmount, totalAmount);
        }
    }

    public boolean isMathValid() {
        return untaxedAmount.add(taxAmount).compareTo(totalAmount) == 0;
    }

    public static String computeDigest(
            String invoiceCode,
            String fapiaoNumber,
            LocalDate issueDate,
            String buyerTaxNo,
            String sellerTaxNo,
            BigDecimal untaxed,
            BigDecimal tax,
            BigDecimal total
    ) {
        String normalized = String.join("|",
                invoiceCode != null ? invoiceCode : "",
                fapiaoNumber,
                issueDate.toString(),
                buyerTaxNo,
                sellerTaxNo,
                untaxed.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                tax.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                total.setScale(2, RoundingMode.HALF_UP).toPlainString()
        );
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
