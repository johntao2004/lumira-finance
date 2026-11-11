package com.lumira.finance;

import com.lumira.platform.identity.api.AccountOwnerPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/** Bounded trial storage of exact evidence bytes; no authenticity or malware verdict is implied. */
@Service
public class FinanceOriginals {
    static final int MAXIMUM_BYTES = 5 * 1024 * 1024;
    private final FinanceStore db;
    private final FinanceService finance;
    private final AccountOwnerPort owners;

    public FinanceOriginals(FinanceStore db, FinanceService finance, AccountOwnerPort owners) {
        this.db = db;
        this.finance = finance;
        this.owners = owners;
    }

    static String filename(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 255
                || !value.equals(value.strip())
                || value.equals(".")
                || value.equals("..")
                || value.endsWith(".")
                || value.codePoints()
                        .anyMatch(
                                c ->
                                        Character.isISOControl(c)
                                                || Character.getType(c) == Character.FORMAT
                                                || "<>:\"/\\|?*".indexOf(c) >= 0)) {
            throw FinanceException.invalid("无效原件文件名");
        }
        String stem = value.split("\\.", 2)[0].strip().toUpperCase(Locale.ROOT);
        if (Set.of(
                        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6",
                        "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6",
                        "LPT7", "LPT8", "LPT9")
                .contains(stem)) {
            throw FinanceException.invalid("无效原件文件名");
        }
        return value;
    }

    static byte[] bytes(InputStream stream, long length) throws IOException {
        if (length > MAXIMUM_BYTES) throw FinanceException.invalid("原件上限5MiB");
        byte[] value = stream.readNBytes(MAXIMUM_BYTES + 1);
        if (value.length == 0
                || value.length > MAXIMUM_BYTES
                || length >= 0 && length != value.length)
            throw FinanceException.invalid("原件为空、长度不一致或超过5MiB");
        return value;
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String mediaType(String filename) {
        String extension =
                filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "xml", "xbrl" -> "application/xml";
            case "ofd" -> "application/ofd";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            default -> "application/octet-stream";
        };
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> upload(
            String actor,
            UUID book,
            UUID year,
            String name,
            String key,
            InputStream stream,
            long length)
            throws IOException {
        var scope = finance.originalScope(actor, book, year, "create");
        UUID user = owners.id(actor, false);
        String safeName = filename(name);
        if (key == null || key.length() < 8 || key.length() > 100 || !key.equals(key.strip()))
            throw FinanceException.invalid("幂等键须8至100字符");
        byte[] content = bytes(stream, length);
        String sha = digest(content), mime = mediaType(safeName);
        String route = book + "/" + year + "/originals";
        String payload =
                FinanceService.hash(finance.encode(List.of(safeName, mime, sha, content.length)));
        db.jdbc
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")
                .param(user + ":" + key)
                .query((rs, n) -> 0)
                .list();
        db.one(
                "SELECT id FROM plugin_finance_book WHERE tenant_id=? AND id=? FOR UPDATE",
                scope.get("tenant_id"),
                book);
        scope = finance.originalScope(actor, book, year, "create");
        finance.lockYearWrites(scope, List.of(year));
        var previous =
                db.list(
                        "SELECT route,payload_hash,response FROM plugin_finance_idempotency WHERE"
                                + " actor_id=? AND key=?",
                        user,
                        key);
        if (!previous.isEmpty()) {
            var old = previous.getFirst();
            if (!route.equals(old.get("route")) || !payload.equals(old.get("payload_hash")))
                throw new FinanceException("IDEMPOTENCY_CONFLICT", "幂等键原件内容冲突");
            return finance.decode(FinanceService.s(old, "response"));
        }
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " plugin_finance_original(tenant_id,book_id,year_id,id,filename,media_type,content,size_bytes,sha256,created_by)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                scope.get("tenant_id"),
                book,
                year,
                id,
                safeName,
                mime,
                content,
                content.length,
                sha,
                user);
        var result = metadata(scope, book, year, id);
        finance.originalAudit(
                user,
                scope,
                year,
                "ORIGINAL_UPLOAD",
                id,
                Map.of("filename", safeName, "sha256", sha, "sizeBytes", content.length));
        db.update(
                "INSERT INTO plugin_finance_idempotency VALUES(?,?,?,?,?)",
                user,
                key,
                route,
                payload,
                finance.encode(result));
        return result;
    }

    private Map<String, Object> metadata(Map<String, Object> scope, UUID book, UUID year, UUID id) {
        var row =
                db.one(
                        "SELECT id,filename,media_type AS \"mediaType\",size_bytes AS"
                                + " \"sizeBytes\",sha256,created_by AS \"createdBy\",created_at AS"
                                + " \"createdAt\",version FROM plugin_finance_original WHERE"
                                + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        scope.get("tenant_id"),
                        book,
                        year,
                        id);
        row.put("verificationStatus", "UNVERIFIED_ORIGINAL_BYTES");
        return row;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String actor, UUID book, UUID year) {
        var scope = finance.originalScope(actor, book, year, "read");
        return db.list(
                "SELECT id,filename,media_type AS \"mediaType\",size_bytes AS"
                    + " \"sizeBytes\",sha256,created_by AS \"createdBy\",created_at AS"
                    + " \"createdAt\",version,'UNVERIFIED_ORIGINAL_BYTES' AS \"verificationStatus\""
                    + " FROM plugin_finance_original WHERE tenant_id=? AND book_id=? AND year_id=?"
                    + " ORDER BY created_at DESC,id LIMIT 200",
                scope.get("tenant_id"),
                book,
                year);
    }

    public record Download(String filename, String sha256, byte[] content) {}

    @Transactional(readOnly = true)
    public Download download(String actor, UUID book, UUID year, UUID id) {
        var scope = finance.originalScope(actor, book, year, "read");
        var row =
                db.one(
                        "SELECT filename,sha256,content,size_bytes FROM plugin_finance_original"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        scope.get("tenant_id"),
                        book,
                        year,
                        id);
        byte[] content = (byte[]) row.get("content");
        String sha = FinanceService.s(row, "sha256");
        if (content.length != ((Number) row.get("size_bytes")).intValue()
                || !digest(content).equals(sha))
            throw new FinanceException("ORIGINAL_INTEGRITY_FAILED", "原件完整性校验失败，禁止交付");
        return new Download(FinanceService.s(row, "filename"), sha, content);
    }
}
