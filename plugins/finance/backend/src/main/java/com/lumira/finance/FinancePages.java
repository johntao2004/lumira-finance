package com.lumira.finance;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Scope-bound keyset pages; table and column names are server constants, never user input. */
final class FinancePages {
    enum Kind {
        MOVEMENT_ALL(
                "plugin_finance_cash_movement_registration",
                "id",
                "created_at",
                "id",
                "created_at",
                "createdAt"),
        MOVEMENT_REGISTERED(
                "plugin_finance_cash_movement_registration",
                "id",
                "created_at",
                "id",
                "created_at",
                "createdAt"),
        MOVEMENT_VOIDED(
                "plugin_finance_cash_movement_registration",
                "id",
                "created_at",
                "id",
                "created_at",
                "createdAt"),
        CASH_SCOPE(
                "plugin_finance_cash_scope_revision",
                "id",
                "created_at",
                "id",
                "created_at",
                "createdAt"),
        SOURCE(
                "plugin_finance_source_event",
                "id",
                "created_at",
                "s.id",
                "s.created_at",
                "createdAt"),
        OUTBOX("plugin_finance_outbox", "id", "created_at", "id", "created_at", "createdAt"),
        PROJECTION(
                "plugin_finance_journal_projection",
                "journal_id",
                "updated_at",
                "journal_id",
                "updated_at",
                "updatedAt");
        final String table, id, stamp, queryId, queryStamp, outputStamp;

        Kind(String t, String i, String s, String qi, String qs, String os) {
            table = t;
            id = i;
            stamp = s;
            queryId = qi;
            queryStamp = qs;
            outputStamp = os;
        }
    }

    static Map<String, Object> page(
            FinanceStore db,
            FinanceService finance,
            Map<String, Object> b,
            UUID year,
            Kind kind,
            int limit,
            String cursor,
            String query) {
        if (limit < 1 || limit > 100) throw FinanceException.invalid("每页数量须为1至100");
        List<Object> params = new ArrayList<>(List.of(b.get("tenant_id"), b.get("id"), year));
        if (cursor != null) {
            var c = decode(finance, cursor, (UUID) b.get("id"), year, kind);
            var stamp = OffsetDateTime.parse((String) c.get("stamp"));
            var id = UUID.fromString((String) c.get("id"));
            if (db.list(
                            "SELECT "
                                    + kind.id
                                    + " FROM "
                                    + kind.table
                                    + " WHERE tenant_id=? AND book_id=? AND year_id=? AND "
                                    + kind.id
                                    + "=? AND "
                                    + kind.stamp
                                    + "=?",
                            b.get("tenant_id"),
                            b.get("id"),
                            year,
                            id,
                            stamp)
                    .isEmpty()) throw FinanceException.invalid("分页游标锚点已变化，请刷新列表");
            query += " AND (" + kind.queryStamp + "," + kind.queryId + ") < (?,?)";
            params.add(stamp);
            params.add(id);
        }
        query += " ORDER BY " + kind.queryStamp + " DESC," + kind.queryId + " DESC LIMIT ?";
        params.add(limit + 1);
        var rows = db.list(query, params.toArray());
        boolean more = rows.size() > limit;
        var items = new ArrayList<>(rows.subList(0, Math.min(rows.size(), limit)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put(
                "nextCursor",
                more ? encode(finance, (UUID) b.get("id"), year, kind, items.getLast()) : null);
        return result;
    }

    static String time(Object v) {
        if (v instanceof Timestamp t) return t.toInstant().toString();
        if (v instanceof OffsetDateTime t) return t.toInstant().toString();
        return Instant.parse(v.toString()).toString();
    }

    static String encode(
            FinanceService f, UUID book, UUID year, Kind kind, Map<String, Object> row) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        f.encode(
                                        Map.of(
                                                "v",
                                                1,
                                                "bookId",
                                                book.toString(),
                                                "yearId",
                                                year.toString(),
                                                "kind",
                                                kind.name(),
                                                "stamp",
                                                time(row.get(kind.outputStamp)),
                                                "id",
                                                row.get("id").toString()))
                                .getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Object> decode(
            FinanceService f, String raw, UUID book, UUID year, Kind kind) {
        try {
            if (raw.length() > 1024 || !raw.matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            var c =
                    f.decode(
                            new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8));
            if (!c.keySet().equals(Set.of("v", "bookId", "yearId", "kind", "stamp", "id"))
                    || !(c.get("v") instanceof Integer v)
                    || v != 1) throw new IllegalArgumentException();
            for (String k : List.of("bookId", "yearId", "kind", "stamp", "id"))
                if (!(c.get(k) instanceof String)) throw new IllegalArgumentException();
            if (!book.toString().equals(c.get("bookId"))
                    || !year.toString().equals(c.get("yearId"))
                    || !kind.name().equals(c.get("kind"))) throw new IllegalArgumentException();
            if (!UUID.fromString((String) c.get("id")).toString().equals(c.get("id"))
                    || !Instant.parse((String) c.get("stamp")).toString().equals(c.get("stamp")))
                throw new IllegalArgumentException();
            return c;
        } catch (RuntimeException invalid) {
            throw FinanceException.invalid("分页游标无效或不属于当前范围");
        }
    }
}
