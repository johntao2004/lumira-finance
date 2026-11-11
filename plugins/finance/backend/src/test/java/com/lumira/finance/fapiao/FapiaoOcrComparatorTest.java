package com.lumira.finance.fapiao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FapiaoOcrComparatorTest {

    private FapiaoStructuredData createSampleInvoice() {
        return new FapiaoStructuredData(
                "1100223344",
                "24110000000000000001",
                FapiaoType.ELECTRONIC_SPECIAL,
                LocalDate.of(2026, 11, 10),
                "91110000MA00112233",
                "测试采购方科技有限公司",
                "91310000MA99887766",
                "测试销售方软件有限公司",
                new BigDecimal("1000.00"),
                new BigDecimal("0.13"),
                new BigDecimal("130.00"),
                new BigDecimal("1130.00"),
                List.of(),
                null
        );
    }

    @Test
    @DisplayName("EV02: Exact match returns VERIFIED_MATCH and NOT_VERIFIED for tax bureau")
    void testExactMatch() {
        FapiaoStructuredData structured = createSampleInvoice();
        FapiaoOcrComparator.OcrResult ocr = new FapiaoOcrComparator.OcrResult(
                "24110000000000000001",
                "91110000MA00112233",
                "91310000MA99887766",
                new BigDecimal("1000.00"),
                new BigDecimal("130.00"),
                new BigDecimal("1130.00"),
                "ocr-digest-12345"
        );

        FapiaoOcrComparator.ComparisonResult cmp = FapiaoOcrComparator.compare(structured, ocr);
        assertTrue(cmp.isMatch());
        assertEquals("VERIFIED_MATCH", cmp.status());
        assertEquals("NOT_VERIFIED", cmp.taxBureauVerification());
        assertTrue(cmp.discrepancies().isEmpty());
    }

    @Test
    @DisplayName("EV02: Tax amount discrepancy triggers MISMATCH_MANUAL_REVIEW")
    void testTaxAmountMismatch() {
        FapiaoStructuredData structured = createSampleInvoice();
        FapiaoOcrComparator.OcrResult ocrWithTaxMismatch = new FapiaoOcrComparator.OcrResult(
                "24110000000000000001",
                "91110000MA00112233",
                "91310000MA99887766",
                new BigDecimal("1000.00"),
                new BigDecimal("120.00"), // Mismatch: 120 vs 130
                new BigDecimal("1130.00"),
                "ocr-digest-mismatch"
        );

        FapiaoOcrComparator.ComparisonResult cmp = FapiaoOcrComparator.compare(structured, ocrWithTaxMismatch);
        assertFalse(cmp.isMatch());
        assertEquals("MISMATCH_MANUAL_REVIEW", cmp.status());
        assertEquals("NOT_VERIFIED", cmp.taxBureauVerification());
        assertTrue(cmp.discrepancies().containsKey("taxAmount"));
        assertEquals("130.00", cmp.discrepancies().get("taxAmount").structuredValue());
        assertEquals("120.00", cmp.discrepancies().get("taxAmount").ocrValue());
    }

    @Test
    @DisplayName("EV02: Buyer tax number discrepancy triggers MISMATCH_MANUAL_REVIEW")
    void testBuyerTaxNoMismatch() {
        FapiaoStructuredData structured = createSampleInvoice();
        FapiaoOcrComparator.OcrResult ocrWithTaxNoMismatch = new FapiaoOcrComparator.OcrResult(
                "24110000000000000001",
                "91110000MA00119999", // Mismatch tax No
                "91310000MA99887766",
                new BigDecimal("1000.00"),
                new BigDecimal("130.00"),
                new BigDecimal("1130.00"),
                "ocr-digest-taxno-mismatch"
        );

        FapiaoOcrComparator.ComparisonResult cmp = FapiaoOcrComparator.compare(structured, ocrWithTaxNoMismatch);
        assertFalse(cmp.isMatch());
        assertEquals("MISMATCH_MANUAL_REVIEW", cmp.status());
        assertTrue(cmp.discrepancies().containsKey("buyerTaxNo"));
    }
}
