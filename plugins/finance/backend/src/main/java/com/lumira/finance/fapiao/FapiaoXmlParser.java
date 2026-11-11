package com.lumira.finance.fapiao;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * EV02: Deterministic, secure XML & JSON structured invoice parser.
 * Immune to XXE and entity expansion vulnerabilities.
 */
public final class FapiaoXmlParser {
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private FapiaoXmlParser() {}

    public static String computeRawSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static FapiaoStructuredData parseXml(String xmlContent) {
        if (xmlContent == null || xmlContent.isBlank()) {
            throw new IllegalArgumentException("XML invoice content cannot be empty");
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();
            Element root = doc.getDocumentElement();

            String invoiceCode = getTagValue(root, "InvoiceCode", "invoiceCode", "Fpdm");
            String fapiaoNumber = getRequiredTagValue(root, "InvoiceNumber", "fapiaoNumber", "Fphm", "FpNumber");
            String issueDateStr = getRequiredTagValue(root, "IssueDate", "issueDate", "Kprq");
            LocalDate issueDate = parseDate(issueDateStr);

            String buyerTaxNo = getRequiredTagValue(root, "BuyerTaxNo", "buyerTaxNo", "GfSh", "BuyerTaxpayerId");
            String buyerName = getRequiredTagValue(root, "BuyerName", "buyerName", "GfMc");
            String sellerTaxNo = getRequiredTagValue(root, "SellerTaxNo", "sellerTaxNo", "XfSh", "SellerTaxpayerId");
            String sellerName = getRequiredTagValue(root, "SellerName", "sellerName", "XfMc");

            BigDecimal untaxedAmount = parseDecimal(getRequiredTagValue(root, "UntaxedAmount", "untaxedAmount", "Hjbhsje"));
            BigDecimal taxAmount = parseDecimal(getRequiredTagValue(root, "TaxAmount", "taxAmount", "Hjse"));
            BigDecimal totalAmount = parseDecimal(getRequiredTagValue(root, "TotalAmount", "totalAmount", "Jshj"));

            String taxRateStr = getTagValue(root, "TaxRate", "taxRate", "Sl");
            BigDecimal taxRate = taxRateStr != null && !taxRateStr.isBlank() ? new BigDecimal(taxRateStr.trim()) : BigDecimal.ZERO;

            String typeStr = getTagValue(root, "InvoiceType", "fapiaoType", "Fplx");
            FapiaoType fapiaoType = parseFapiaoType(typeStr);

            List<FapiaoStructuredData.Item> items = new ArrayList<>();
            NodeList itemNodes = root.getElementsByTagName("Item");
            if (itemNodes.getLength() == 0) itemNodes = root.getElementsByTagName("InvoiceItem");
            for (int i = 0; i < itemNodes.getLength(); i++) {
                if (itemNodes.item(i) instanceof Element itemElem) {
                    String name = getRequiredTagValue(itemElem, "GoodsServiceName", "goodsServiceName", "Xmmc");
                    String code = getTagValue(itemElem, "GoodsTaxCode", "goodsTaxCode", "Spbm");
                    BigDecimal itemUntaxed = parseDecimal(getRequiredTagValue(itemElem, "UntaxedAmount", "untaxedAmount", "Je"));
                    BigDecimal itemTax = parseDecimal(getRequiredTagValue(itemElem, "TaxAmount", "taxAmount", "Se"));
                    BigDecimal itemTotal = parseDecimal(getTagValue(itemElem, "TotalAmount", "totalAmount", "Jshj"));
                    if (itemTotal == null) itemTotal = itemUntaxed.add(itemTax);
                    BigDecimal itemRate = parseDecimal(getTagValue(itemElem, "TaxRate", "taxRate", "Sl"));
                    BigDecimal unitPrice = parseDecimal(getTagValue(itemElem, "UnitPrice", "unitPrice", "Dj"));
                    BigDecimal quantity = parseDecimal(getTagValue(itemElem, "Quantity", "quantity", "Sl"));

                    items.add(new FapiaoStructuredData.Item(name, code, unitPrice, quantity, itemUntaxed, itemRate, itemTax, itemTotal));
                }
            }

            return new FapiaoStructuredData(
                    invoiceCode,
                    fapiaoNumber,
                    fapiaoType,
                    issueDate,
                    buyerTaxNo,
                    buyerName,
                    sellerTaxNo,
                    sellerName,
                    untaxedAmount,
                    taxRate,
                    taxAmount,
                    totalAmount,
                    items,
                    null
            );
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse invoice XML: " + e.getMessage(), e);
        }
    }

    public static FapiaoStructuredData parseJson(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            throw new IllegalArgumentException("JSON invoice content cannot be empty");
        }
        try {
            JsonNode root = JSON_MAPPER.readTree(jsonContent);

            String invoiceCode = getJsonText(root, "invoiceCode", "InvoiceCode", "fpdm");
            String fapiaoNumber = getRequiredJsonText(root, "fapiaoNumber", "InvoiceNumber", "fphm");
            String issueDateStr = getRequiredJsonText(root, "issueDate", "IssueDate", "kprq");
            LocalDate issueDate = parseDate(issueDateStr);

            String buyerTaxNo = getRequiredJsonText(root, "buyerTaxNo", "BuyerTaxNo", "gfSh");
            String buyerName = getRequiredJsonText(root, "buyerName", "BuyerName", "gfMc");
            String sellerTaxNo = getRequiredJsonText(root, "sellerTaxNo", "SellerTaxNo", "xfSh");
            String sellerName = getRequiredJsonText(root, "sellerName", "SellerName", "xfMc");

            BigDecimal untaxedAmount = parseDecimal(getRequiredJsonText(root, "untaxedAmount", "UntaxedAmount", "hjbhsje"));
            BigDecimal taxAmount = parseDecimal(getRequiredJsonText(root, "taxAmount", "TaxAmount", "hjse"));
            BigDecimal totalAmount = parseDecimal(getRequiredJsonText(root, "totalAmount", "TotalAmount", "jshj"));

            String taxRateStr = getJsonText(root, "taxRate", "TaxRate", "sl");
            BigDecimal taxRate = taxRateStr != null && !taxRateStr.isBlank() ? new BigDecimal(taxRateStr.trim()) : BigDecimal.ZERO;

            String typeStr = getJsonText(root, "fapiaoType", "InvoiceType", "fplx");
            FapiaoType fapiaoType = parseFapiaoType(typeStr);

            List<FapiaoStructuredData.Item> items = new ArrayList<>();
            JsonNode itemsNode = root.get("items");
            if (itemsNode != null && itemsNode.isArray()) {
                for (JsonNode itemNode : itemsNode) {
                    String name = getRequiredJsonText(itemNode, "goodsServiceName", "name");
                    String code = getJsonText(itemNode, "goodsTaxCode", "code");
                    BigDecimal itemUntaxed = parseDecimal(getRequiredJsonText(itemNode, "untaxedAmount", "amount"));
                    BigDecimal itemTax = parseDecimal(getRequiredJsonText(itemNode, "taxAmount", "tax"));
                    BigDecimal itemTotal = parseDecimal(getJsonText(itemNode, "totalAmount", "total"));
                    if (itemTotal == null) itemTotal = itemUntaxed.add(itemTax);
                    BigDecimal itemRate = parseDecimal(getJsonText(itemNode, "taxRate", "rate"));
                    BigDecimal unitPrice = parseDecimal(getJsonText(itemNode, "unitPrice", "price"));
                    BigDecimal quantity = parseDecimal(getJsonText(itemNode, "quantity", "qty"));

                    items.add(new FapiaoStructuredData.Item(name, code, unitPrice, quantity, itemUntaxed, itemRate, itemTax, itemTotal));
                }
            }

            return new FapiaoStructuredData(
                    invoiceCode,
                    fapiaoNumber,
                    fapiaoType,
                    issueDate,
                    buyerTaxNo,
                    buyerName,
                    sellerTaxNo,
                    sellerName,
                    untaxedAmount,
                    taxRate,
                    taxAmount,
                    totalAmount,
                    items,
                    null
            );
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse invoice JSON: " + e.getMessage(), e);
        }
    }

    private static String getTagValue(Element parent, String... tagNames) {
        for (String tagName : tagNames) {
            NodeList list = parent.getElementsByTagName(tagName);
            if (list.getLength() > 0 && list.item(0).getTextContent() != null) {
                String val = list.item(0).getTextContent().trim();
                if (!val.isEmpty()) return val;
            }
        }
        return null;
    }

    private static String getRequiredTagValue(Element parent, String... tagNames) {
        String val = getTagValue(parent, tagNames);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException("Missing required XML tag: " + tagNames[0]);
        }
        return val;
    }

    private static String getJsonText(JsonNode node, String... propNames) {
        for (String p : propNames) {
            if (node.hasNonNull(p)) {
                String val = node.get(p).asText().trim();
                if (!val.isEmpty()) return val;
            }
        }
        return null;
    }

    private static String getRequiredJsonText(JsonNode node, String... propNames) {
        String val = getJsonText(node, propNames);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException("Missing required JSON property: " + propNames[0]);
        }
        return val;
    }

    private static BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        return new BigDecimal(s.trim()).setScale(2, RoundingMode.HALF_UP);
    }

    private static LocalDate parseDate(String s) {
        String clean = s.trim();
        if (clean.length() == 8 && clean.matches("\\d{8}")) {
            return LocalDate.parse(clean, DateTimeFormatter.ofPattern("yyyyMMdd"));
        }
        if (clean.contains("T")) {
            clean = clean.substring(0, clean.indexOf("T"));
        }
        return LocalDate.parse(clean);
    }

    private static FapiaoType parseFapiaoType(String s) {
        if (s == null || s.isBlank()) return FapiaoType.ELECTRONIC_GENERAL;
        String upper = s.trim().toUpperCase();
        if (upper.contains("SPECIAL") || upper.contains("专票") || upper.equals("01") || upper.equals("81")) {
            return upper.contains("ELECTRONIC") || upper.contains("数电") ? FapiaoType.ELECTRONIC_SPECIAL : FapiaoType.SPECIAL_VAT;
        }
        if (upper.contains("TOLL") || upper.contains("通行费")) {
            return FapiaoType.TOLL_INVOICE;
        }
        if (upper.contains("ELECTRONIC") || upper.contains("数电")) {
            return FapiaoType.ELECTRONIC_GENERAL;
        }
        return FapiaoType.GENERAL_VAT;
    }
}
