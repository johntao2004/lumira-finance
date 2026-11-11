package com.lumira.finance;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Deterministic POSIX ustar writer producing streams byte-compatible with
 * plugins/finance/archive/archive-package.mjs and verify-archive.mjs.
 */
public final class FinanceArchiveUstar {
    private static final DateTimeFormatter ISO_MS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public record OriginalPayload(
            String id, String path, String filename, String mediaType, byte[] bytes, String sha256) {}

    public record BuiltArchive(byte[] tarBytes, String artifactSha256, String manifestSha256) {}

    private FinanceArchiveUstar() {}

    public static String formatInstantMs(Instant instant) {
        return ISO_MS.format(instant);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonicalJson(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(value, sb, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeCanonical(Object value, StringBuilder sb, int depth) {
        if (depth > 32) throw new IllegalArgumentException("JSON_DEPTH_LIMIT");
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long) {
            sb.append(value);
        } else if (value instanceof String s) {
            escapeJsonString(s, sb);
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                writeCanonical(list.get(i), sb, depth + 1);
            }
            sb.append(']');
        } else if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object k : map.keySet()) keys.add(Objects.requireNonNull(k).toString());
            keys.sort(String::compareTo);
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                String k = keys.get(i);
                escapeJsonString(k, sb);
                sb.append(':');
                writeCanonical(((Map<String, Object>) map).get(k), sb, depth + 1);
            }
            sb.append('}');
        } else {
            throw new IllegalArgumentException("INVALID_JSON_VALUE:" + value.getClass().getName());
        }
    }

    private static void escapeJsonString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static byte[] ustarHeader(String entryPath, int size) {
        if (entryPath == null
                || entryPath.length() > 100
                || !entryPath.matches("^[a-z0-9][a-z0-9_./-]*$")
                || size < 0) {
            throw new IllegalArgumentException("UNSAFE_PATH_OR_SIZE");
        }
        byte[] b = new byte[512];
        writeAscii(b, 0, entryPath);
        writeAscii(b, 100, "0000600\0");
        writeAscii(b, 108, "0000000\0");
        writeAscii(b, 116, "0000000\0");
        String octSize = String.format("%011o\0", size);
        writeAscii(b, 124, octSize);
        writeAscii(b, 136, "00000000000\0");
        Arrays.fill(b, 148, 156, (byte) 32);
        b[156] = 48; // '0' regular file
        writeAscii(b, 257, "ustar\0");
        writeAscii(b, 263, "00");
        int sum = 0;
        for (byte x : b) sum += (x & 0xff);
        String octSum = String.format("%06o\0 ", sum);
        writeAscii(b, 148, octSum);
        return b;
    }

    private static void writeAscii(byte[] dst, int offset, String s) {
        byte[] src = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, dst, offset, src.length);
    }

    private static int padding(int size) {
        return (512 - (size % 512)) % 512;
    }

    public static BuiltArchive build(
            Map<String, Object> indexMap, List<OriginalPayload> originals) {
        byte[] metadataBytes = canonicalJson(indexMap).getBytes(StandardCharsets.UTF_8);
        if (metadataBytes.length > 4 * 1024 * 1024) {
            throw new FinanceException("ARCHIVE_METADATA_LIMIT", "电子档案索引超过单包上限");
        }
        String manifestSha256 = sha256Hex(metadataBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream(metadataBytes.length + 4096);
        out.writeBytes(ustarHeader("index.json", metadataBytes.length));
        out.writeBytes(metadataBytes);
        int pad0 = padding(metadataBytes.length);
        if (pad0 > 0) out.writeBytes(new byte[pad0]);

        for (OriginalPayload orig : originals) {
            if (orig.bytes() == null || orig.bytes().length == 0) {
                throw new FinanceException("ARCHIVE_EMPTY_ORIGINAL", "原件字节为空");
            }
            String actualSha = sha256Hex(orig.bytes());
            if (!actualSha.equals(orig.sha256())) {
                throw new FinanceException("ARCHIVE_ORIGINAL_HASH_MISMATCH", "原件字节哈希不一致");
            }
            out.writeBytes(ustarHeader(orig.path(), orig.bytes().length));
            out.writeBytes(orig.bytes());
            int pad = padding(orig.bytes().length);
            if (pad > 0) out.writeBytes(new byte[pad]);
        }
        out.writeBytes(new byte[1024]);
        byte[] tar = out.toByteArray();
        return new BuiltArchive(tar, sha256Hex(tar), manifestSha256);
    }
}
