package com.lumira.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/** Scope-bound immutable audit evidence, paged by the database timestamp and UUID tuple. */
@Service
public class FinanceAudit {
    private final FinanceStore db;
    private final FinanceService finance;

    public FinanceAudit(FinanceStore db, FinanceService finance) {
        this.db = db;
        this.finance = finance;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(String actor, UUID book, UUID year, int limit, String cursor) {
        var scope = finance.originalScope(actor, book, year, "manage");
        if (limit < 1 || limit > 100) throw FinanceException.invalid("审计每页数量须为1至100");
        Map<String, Object> anchor = cursor == null ? null : cursor(cursor, book, year);
        OffsetDateTime stamp =
                anchor == null ? null : OffsetDateTime.parse((String) anchor.get("createdAt"));
        UUID anchorId = anchor == null ? null : UUID.fromString((String) anchor.get("id"));
        if (anchor != null
                && db.list(
                                "SELECT id FROM plugin_finance_audit WHERE tenant_id=? AND"
                                        + " book_id=? AND year_id=? AND id=? AND created_at=?",
                                scope.get("tenant_id"),
                                book,
                                year,
                                anchorId,
                                stamp)
                        .isEmpty()) throw FinanceException.invalid("审计游标不属于当前范围或已失效");
        String sql =
                "SELECT id,actor_id,action,target_id,payload_hash,created_at,payload FROM"
                        + " plugin_finance_audit WHERE tenant_id=:tenant AND book_id=:book AND"
                        + " year_id=:year"
                        + (anchor == null ? "" : " AND (created_at,id)<(:stamp,:anchor)")
                        + " ORDER BY created_at DESC,id DESC LIMIT :size";
        var query =
                db.jdbc
                        .sql(sql)
                        .param("tenant", scope.get("tenant_id"))
                        .param("book", book)
                        .param("year", year)
                        .param("size", limit + 1);
        if (anchor != null) query = query.param("stamp", stamp).param("anchor", anchorId);
        var rows =
                query.query(
                                (rs, n) -> {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    var payload = finance.decode(rs.getString("payload"));
                                    String digest = rs.getString("payload_hash");
                                    if (!digest.equals(
                                            FinanceService.hash(
                                                    finance.encode(finance.canonical(payload)))))
                                        throw new FinanceException(
                                                "AUDIT_INTEGRITY_FAILED", "审计证据与摘要不一致，停止读取");
                                    row.put("id", rs.getObject("id", UUID.class));
                                    row.put("actorId", rs.getObject("actor_id", UUID.class));
                                    row.put("action", rs.getString("action"));
                                    row.put("targetId", rs.getObject("target_id", UUID.class));
                                    row.put("payloadHash", digest);
                                    row.put(
                                            "createdAt",
                                            rs.getObject("created_at", OffsetDateTime.class)
                                                    .toInstant()
                                                    .toString());
                                    row.put("payload", payload);
                                    return row;
                                })
                        .list();
        boolean more = rows.size() > limit;
        var items = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("nextCursor", more ? encodeCursor(book, year, items.getLast()) : null);
        return result;
    }

    String encodeCursor(UUID book, UUID year, Map<String, Object> last) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        finance.encode(
                                        Map.of(
                                                "v",
                                                1,
                                                "bookId",
                                                book.toString(),
                                                "yearId",
                                                year.toString(),
                                                "createdAt",
                                                last.get("createdAt"),
                                                "id",
                                                last.get("id").toString()))
                                .getBytes(StandardCharsets.UTF_8));
    }

    Map<String, Object> cursor(String raw, UUID book, UUID year) {
        try {
            if (raw.length() > 1024 || !raw.matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            var value =
                    finance.decode(
                            new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8));
            if (!value.keySet().equals(Set.of("v", "bookId", "yearId", "createdAt", "id"))
                    || !(value.get("v") instanceof Integer version)
                    || version != 1) throw new IllegalArgumentException();
            for (String key : List.of("bookId", "yearId", "createdAt", "id"))
                if (!(value.get(key) instanceof String)) throw new IllegalArgumentException();
            if (!book.toString().equals(value.get("bookId"))
                    || !year.toString().equals(value.get("yearId")))
                throw new IllegalArgumentException();
            String id = (String) value.get("id");
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            String time = (String) value.get("createdAt");
            if (!OffsetDateTime.parse(time).toInstant().toString().equals(time))
                throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) {
            throw FinanceException.invalid("审计游标无效");
        }
    }
}
