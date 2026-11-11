package com.lumira.finance.fapiao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FapiaoXmlParserTest {

    private static final String SAMPLE_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Invoice>
            <InvoiceCode>1100223344</InvoiceCode>
            <InvoiceNumber>24110000000000000001</InvoiceNumber>
            <IssueDate>2026-11-10</IssueDate>
            <BuyerTaxNo>91110000MA00112233</BuyerTaxNo>
            <BuyerName>测试采购方科技有限公司</BuyerName>
            <SellerTaxNo>91310000MA99887766</SellerTaxNo>
            <SellerName>测试销售方软件有限公司</SellerName>
            <UntaxedAmount>1000.00</UntaxedAmount>
            <TaxRate>0.13</TaxRate>
            <TaxAmount>130.00</TaxAmount>
            <TotalAmount>1130.00</TotalAmount>
            <InvoiceType>ELECTRONIC_SPECIAL</InvoiceType>
            <Items>
                <Item>
                    <GoodsServiceName>*信息技术服务*软件开发</GoodsServiceName>
                    <GoodsTaxCode>3040201010000000000</GoodsTaxCode>
                    <UntaxedAmount>1000.00</UntaxedAmount>
                    <TaxRate>0.13</TaxRate>
                    <TaxAmount>130.00</TaxAmount>
                    <TotalAmount>1130.00</TotalAmount>
                    <Quantity>1</Quantity>
                    <UnitPrice>1000.00</UnitPrice>
                </Item>
            </Items>
        </Invoice>
        """;

    private static final String SAMPLE_JSON = """
        {
            "invoiceCode": "1100223344",
            "fapiaoNumber": "24110000000000000002",
            "issueDate": "2026-11-10",
            "buyerTaxNo": "91110000MA00112233",
            "buyerName": "测试采购方科技有限公司",
            "sellerTaxNo": "91310000MA99887766",
            "sellerName": "测试销售方软件有限公司",
            "untaxedAmount": "5000.00",
            "taxRate": "0.06",
            "taxAmount": "300.00",
            "totalAmount": "5300.00",
            "fapiaoType": "ELECTRONIC_GENERAL",
            "items": [
                {
                    "goodsServiceName": "*咨询服务*企业管理咨询",
                    "goodsTaxCode": "3040301010000000000",
                    "untaxedAmount": "5000.00",
                    "taxRate": "0.06",
                    "taxAmount": "300.00",
                    "totalAmount": "5300.00"
                }
            ]
        }
        """;

    @Test
    @DisplayName("EV02: Successfully parse standard e-invoice XML and verify decimal math and digests")
    void testParseXmlSuccess() {
        FapiaoStructuredData data = FapiaoXmlParser.parseXml(SAMPLE_XML);
        assertNotNull(data);
        assertEquals("1100223344", data.invoiceCode());
        assertEquals("24110000000000000001", data.fapiaoNumber());
        assertEquals(LocalDate.of(2026, 11, 10), data.issueDate());
        assertEquals("91110000MA00112233", data.buyerTaxNo());
        assertEquals("测试采购方科技有限公司", data.buyerName());
        assertEquals("91310000MA99887766", data.sellerTaxNo());
        assertEquals(new BigDecimal("1000.00"), data.untaxedAmount());
        assertEquals(new BigDecimal("130.00"), data.taxAmount());
        assertEquals(new BigDecimal("1130.00"), data.totalAmount());
        assertEquals(FapiaoType.ELECTRONIC_SPECIAL, data.fapiaoType());
        assertTrue(data.isMathValid());
        assertNotNull(data.payloadDigest());
        assertEquals(64, data.payloadDigest().length());

        assertEquals(1, data.items().size());
        assertEquals("*信息技术服务*软件开发", data.items().get(0).goodsServiceName());
    }

    @Test
    @DisplayName("EV02: Successfully parse structured JSON invoice")
    void testParseJsonSuccess() {
        FapiaoStructuredData data = FapiaoXmlParser.parseJson(SAMPLE_JSON);
        assertNotNull(data);
        assertEquals("24110000000000000002", data.fapiaoNumber());
        assertEquals(new BigDecimal("5000.00"), data.untaxedAmount());
        assertEquals(new BigDecimal("300.00"), data.taxAmount());
        assertEquals(new BigDecimal("5300.00"), data.totalAmount());
        assertTrue(data.isMathValid());
        assertEquals(FapiaoType.ELECTRONIC_GENERAL, data.fapiaoType());
    }

    @Test
    @DisplayName("EV02: Math integrity failure when untaxed + tax != total")
    void testMathIntegrityFailure() {
        String invalidMathXml = SAMPLE_XML.replace("<TaxAmount>130.00</TaxAmount>", "<TaxAmount>120.00</TaxAmount>");
        FapiaoStructuredData data = FapiaoXmlParser.parseXml(invalidMathXml);
        assertFalse(data.isMathValid(), "1000.00 + 120.00 != 1130.00 must fail math validation");
    }

    @Test
    @DisplayName("EV02: XXE injection attempt is rejected by secure XML parser")
    void testXxeInjectionRejected() {
        String xxeXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <Invoice>
                <InvoiceNumber>&xxe;</InvoiceNumber>
            </Invoice>
            """;
        assertThrows(IllegalArgumentException.class, () -> FapiaoXmlParser.parseXml(xxeXml));
    }

    @Test
    @DisplayName("EV02: Missing required tags throws descriptive exception")
    void testMissingRequiredTags() {
        String incompleteXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Invoice>
                <InvoiceCode>1100223344</InvoiceCode>
            </Invoice>
            """;
        assertThrows(IllegalArgumentException.class, () -> FapiaoXmlParser.parseXml(incompleteXml));
    }
}
