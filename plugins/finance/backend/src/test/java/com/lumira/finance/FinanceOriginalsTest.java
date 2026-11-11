package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

class FinanceOriginalsTest {
    @Test
    void keepsExactBytesAndHashesServerSide() throws Exception {
        byte[] original = {0, 1, 2, 13, 10, (byte) 255};
        assertArrayEquals(
                original,
                FinanceOriginals.bytes(new ByteArrayInputStream(original), original.length));
        assertEquals(
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                FinanceOriginals.digest(new byte[] {1, 2, 3}));
    }

    @Test
    void rejectsUnsafeNamesAndUnboundedOrEmptyUploads() {
        for (String name :
                new String[] {
                    "../invoice.pdf",
                    "invoice\r\n.txt",
                    "CON.pdf",
                    "CON .pdf",
                    "invoice.",
                    "a/b",
                    "a\\b",
                    " ",
                    "a:b",
                    "invoice\u202epdf.exe"
                }) assertThrows(FinanceException.class, () -> FinanceOriginals.filename(name));
        assertEquals("电子发票.xml", FinanceOriginals.filename("电子发票.xml"));
        assertThrows(
                FinanceException.class,
                () -> FinanceOriginals.bytes(new ByteArrayInputStream(new byte[0]), 0));
        assertThrows(
                FinanceException.class,
                () -> FinanceOriginals.bytes(new ByteArrayInputStream(new byte[] {1}), 2));
        assertThrows(
                FinanceException.class,
                () ->
                        FinanceOriginals.bytes(
                                new ByteArrayInputStream(
                                        new byte[FinanceOriginals.MAXIMUM_BYTES + 1]),
                                -1));
    }
}
