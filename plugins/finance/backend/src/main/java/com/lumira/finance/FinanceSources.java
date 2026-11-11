package com.lumira.finance;

import com.lumira.finance.domain.PrecisionRules;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Trusted owner intake with permanent source/effect identity; it creates drafts only. */
@Service
public class FinanceSources {
    static final String CONSUMER = "finance-journal-draft-v1";
    static final String EFFECT = "CREATE_JOURNAL_DRAFT";
    private final FinanceStore db;
    private final FinanceService finance;

    public FinanceSources(FinanceStore db, FinanceService finance) {
        this.db = db;
        this.finance = finance;
    }

    static void keys(Map<String, Object> value, Set<String> allowed, Set<String> required) {
        if (!allowed.containsAll(value.keySet()) || !value.keySet().containsAll(required))
            throw FinanceException.invalid("来源字段缺失或含不支持的字段");
    }

    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw FinanceException.invalid("来源字段必须为对象");
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    static String exact(Map<String, Object> value, String key, int max) {
        if (!(value.get(key) instanceof String raw))
            throw FinanceException.invalid("来源字段必须为字符串: " + key);
        if (!raw.equals(raw.strip())
                || raw.isEmpty()
                || raw.length() > max
                || raw.codePoints().anyMatch(Character::isISOControl))
            throw FinanceException.invalid("无效来源字段: " + key);
        return raw;
    }

    static String optionalText(Map<String, Object> value, String key, int max) {
        if (!(value.get(key) instanceof String raw)
                || raw.length() > max
                || raw.indexOf('\u0000') >= 0) throw FinanceException.invalid("无效来源字段: " + key);
        return raw;
    }

    static String amount(Map<String, Object> row, String field) {
        String value = exact(row, field, 128);
        try {
            BigDecimal n = PrecisionRules.money(value);
            if (n.signum() < 0) throw new IllegalArgumentException();
            return n.toPlainString();
        } catch (IllegalArgumentException invalid) {
            throw FinanceException.invalid("来源金额/数量须为非负28,8十进制字符串");
        }
    }

    Map<String, Object> event(Map<String, Object> input) {
        var e = object(input);
        var fields =
                Set.of(
                        "schemaVersion",
                        "eventId",
                        "sourceSystem",
                        "sourceEventId",
                        "sourceDocumentId",
                        "sourceDocumentVersion",
                        "eventType",
                        "effectType",
                        "businessDate",
                        "accountingDate",
                        "occurredAt",
                        "currency",
                        "reason",
                        "lines",
                        "evidenceIds",
                        "reversesSourceEventId");
        keys(e, fields, fields);
        if (!"1.0.0".equals(e.get("schemaVersion"))
                || !EFFECT.equals(e.get("effectType"))
                || !"CNY".equals(e.get("currency")))
            throw FinanceException.invalid("本片仅支持schema1.0.0/CNY/草稿效果");
        FinanceService.id(e, "eventId");
        exact(e, "sourceSystem", 80);
        exact(e, "sourceEventId", 160);
        exact(e, "sourceDocumentId", 160);
        Object version = e.get("sourceDocumentVersion");
        if (!(version instanceof Integer || version instanceof Long)
                || ((Number) version).longValue() < 1
                || ((Number) version).longValue() > Integer.MAX_VALUE)
            throw FinanceException.invalid("sourceDocumentVersion必须为正整数");
        String type = exact(e, "eventType", 40);
        if (type.equals("REVERSAL") || e.get("reversesSourceEventId") != null)
            throw new FinanceException("RULE_VERSION_UNAVAILABLE", "来源冲销需专属分配/原效果链，本片不开放");
        if (!Set.of(
                        "REVENUE_RECOGNIZED",
                        "PURCHASE_ACCEPTED",
                        "CASH_RECEIVED",
                        "CASH_PAID",
                        "ASSET_DEPRECIATED",
                        "PAYROLL_ACCRUED",
                        "ADJUSTMENT")
                .contains(type)) throw FinanceException.invalid("不支持的来源事件类型");
        try {
            e.put("businessDate", LocalDate.parse(exact(e, "businessDate", 10)).toString());
            e.put("accountingDate", LocalDate.parse(exact(e, "accountingDate", 10)).toString());
            e.put(
                    "occurredAt",
                    OffsetDateTime.parse(exact(e, "occurredAt", 40)).toInstant().toString());
        } catch (java.time.DateTimeException invalid) {
            throw FinanceException.invalid("来源日期/时间无效");
        }
        optionalText(e, "reason", 1000);
        if (!(e.get("evidenceIds") instanceof List<?> evidence) || !evidence.isEmpty())
            throw new FinanceException("RULE_VERSION_UNAVAILABLE", "来源原件额度/用途联动尚未接入，请在草稿原件流程关联");
        if (!(e.get("lines") instanceof List<?> raw) || raw.size() != 1)
            throw FinanceException.invalid("本片来源业务行必须恰好1条，多业务行路由尚未开放");
        List<Map<String, Object>> lines = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object value : raw) {
            var l = object(value);
            var required =
                    Set.of(
                            "sourceLineId",
                            "businessSemantic",
                            "quantity",
                            "netAmount",
                            "taxAmount",
                            "grossAmount",
                            "dimensions",
                            "taxTreatment");
            var allowed = new HashSet<>(required);
            allowed.add("description");
            keys(l, allowed, required);
            if (!ids.add(exact(l, "sourceLineId", 80))) throw FinanceException.invalid("来源行业务ID重复");
            exact(l, "businessSemantic", 80);
            if (!l.containsKey("description")) l.put("description", "");
            optionalText(l, "description", 500);
            if (!(l.get("dimensions") instanceof List<?> dims) || !dims.isEmpty())
                throw new FinanceException("RULE_VERSION_UNAVAILABLE", "来源辅助核算尚未开放");
            if (!Set.of(
                            "GENERAL_TAXABLE",
                            "SIMPLIFIED_TAXABLE",
                            "EXEMPT",
                            "ZERO_RATE",
                            "OUT_OF_SCOPE",
                            "UNDETERMINED")
                    .contains(l.get("taxTreatment"))) throw FinanceException.invalid("无效来源税务标签");
            for (String field : List.of("quantity", "netAmount", "taxAmount", "grossAmount"))
                l.put(field, amount(l, field));
            if (new BigDecimal((String) l.get("netAmount"))
                            .add(new BigDecimal((String) l.get("taxAmount")))
                            .compareTo(new BigDecimal((String) l.get("grossAmount")))
                    != 0) throw FinanceException.invalid("来源净额加税额必须等于价税合计");
            lines.add(l);
        }
        e.put("lines", lines);
        return e;
    }

    Map<String, Object> journal(Map<String, Object> input, Map<String, Object> event) {
        var j = object(input);
        keys(j, Set.of("summary", "lines"), Set.of("summary", "lines"));
        exact(j, "summary", 500);
        if (!(j.get("lines") instanceof List<?> raw) || raw.size() < 2 || raw.size() > 500)
            throw FinanceException.invalid("映射分录须为2至500条");
        Set<String> sourceIds = new HashSet<>();
        BigDecimal gross = BigDecimal.ZERO;
        for (var l : (List<Map<String, Object>>) event.get("lines")) {
            sourceIds.add((String) l.get("sourceLineId"));
            gross = gross.add(new BigDecimal((String) l.get("grossAmount")));
        }
        Set<String> used = new HashSet<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO;
        for (Object value : raw) {
            var l = object(value);
            keys(
                    l,
                    Set.of("sourceLineId", "accountId", "debit", "credit", "memo"),
                    Set.of("sourceLineId", "accountId", "debit", "credit"));
            String id = exact(l, "sourceLineId", 80);
            if (!sourceIds.contains(id)) throw FinanceException.invalid("映射引用了未知来源行");
            used.add(id);
            l.put("accountId", FinanceService.id(l, "accountId").toString());
            if (!l.containsKey("memo")) l.put("memo", "");
            optionalText(l, "memo", 500);
            finance.validateFormalInput(Map.of("lines", List.of(l)));
            debit = debit.add(new BigDecimal((String) l.get("debit")));
            lines.add(l);
        }
        if (!used.equals(sourceIds) || debit.compareTo(gross) != 0)
            throw FinanceException.invalid("每条来源行须被映射，借方总额须等于来源价税合计");
        j.put("lines", lines);
        j.put("date", event.get("accountingDate"));
        return j;
    }

    String digest(Object value) {
        return FinanceService.hash(finance.encode(finance.canonical(value)));
    }

    static final String SELECT =
            "SELECT s.id,s.source_system AS \"sourceSystem\",s.source_event_key AS"
                + " \"sourceEventId\",s.source_document_id AS"
                + " \"sourceDocumentId\",s.source_document_version AS"
                + " \"sourceDocumentVersion\",s.event_type AS \"eventType\",s.accounting_date AS"
                + " \"accountingDate\",s.created_at AS \"createdAt\",e.journal_id AS"
                + " \"journalId\",e.effect_type AS \"effectType\",e.effect_key AS"
                + " \"effectKey\",s.payload_digest AS \"payloadDigest\",e.mapping_rule_version AS"
                + " \"mappingRuleVersion\",e.handler_version AS \"handlerVersion\",'DRAFT_CREATED'"
                + " AS status,j.status AS \"journalStatus\" FROM plugin_finance_source_event s JOIN"
                + " plugin_finance_business_effect e ON e.tenant_id=s.tenant_id AND"
                + " e.source_event_id=s.id JOIN plugin_finance_journal j ON j.tenant_id=e.tenant_id"
                + " AND j.book_id=e.book_id AND j.year_id=e.year_id AND j.id=e.journal_id WHERE"
                + " s.tenant_id=? AND s.book_id=? AND s.year_id=?";

    @Transactional(readOnly = true)
    public Map<String, Object> list(String actor, UUID book, UUID year, int limit, String cursor) {
        var b = finance.originalScope(actor, book, year, "manage");
        return FinancePages.page(
                db, finance, b, year, FinancePages.Kind.SOURCE, limit, cursor, SELECT);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String actor, UUID book, UUID year, UUID id) {
        var b = finance.originalScope(actor, book, year, "manage");
        var row = db.one(SELECT + " AND s.id=?", b.get("tenant_id"), book, year, id);
        var payload =
                db.one(
                        "SELECT payload::text AS payload,payload_digest FROM"
                                + " plugin_finance_source_event WHERE tenant_id=? AND book_id=? AND"
                                + " year_id=? AND id=?",
                        b.get("tenant_id"),
                        book,
                        year,
                        id);
        var e = finance.decode((String) payload.get("payload"));
        var business = new LinkedHashMap<>(e);
        business.remove("eventId");
        if (!digest(business).equals(payload.get("payload_digest")))
            throw new FinanceException("SOURCE_INTEGRITY_FAILED", "来源证据摘要校验失败");
        var effect =
                db.one(
                        "SELECT mapping_payload::text AS mapping_payload,payload_digest FROM"
                            + " plugin_finance_business_effect WHERE tenant_id=? AND book_id=? AND"
                            + " year_id=? AND source_event_id=?",
                        b.get("tenant_id"),
                        book,
                        year,
                        id);
        var mapping = finance.decode((String) effect.get("mapping_payload"));
        if (!digest(List.of(payload.get("payload_digest"), mapping))
                .equals(effect.get("payload_digest")))
            throw new FinanceException("SOURCE_INTEGRITY_FAILED", "来源映射快照摘要校验失败");
        row.put("mappingSnapshot", mapping);
        row.put("event", e);
        row.put("journal", finance.journal(b, year, (UUID) row.get("journalId")));
        return row;
    }

    @Transactional
    public Map<String, Object> ingest(String actor, UUID book, UUID year, Map<String, Object> in) {
        keys(
                in,
                Set.of(
                        "idempotencyKey",
                        "mappingRuleVersion",
                        "handlerVersion",
                        "event",
                        "journal"),
                Set.of(
                        "idempotencyKey",
                        "mappingRuleVersion",
                        "handlerVersion",
                        "event",
                        "journal"));
        String key = exact(in, "idempotencyKey", 100);
        if (key.length() < 8) throw FinanceException.invalid("幂等键至少8字符");
        String mapping = exact(in, "mappingRuleVersion", 80),
                handler = exact(in, "handlerVersion", 80);
        var b = finance.originalScope(actor, book, year, "manage");
        finance.originalScope(actor, book, year, "create");
        var e = event(object(in.get("event")));
        var mapped = journal(object(in.get("journal")), e);
        UUID user = (UUID) b.get("actor_id");
        Object tenant = b.get("tenant_id");
        finance.personGate().prelock((UUID) tenant);
        String system = (String) e.get("sourceSystem"), external = (String) e.get("sourceEventId");
        String effectKey = digest(List.of(tenant.toString(), CONSUMER, system, external, EFFECT));
        db.jdbc
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")
                .param("finance-source:" + effectKey)
                .query((rs, n) -> 0)
                .list();
        db.jdbc
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")
                .param(user + ":" + key)
                .query((rs, n) -> 0)
                .list();
        db.one(
                "SELECT id FROM plugin_finance_book WHERE tenant_id=? AND id=? FOR UPDATE",
                tenant,
                book);
        b = finance.originalScope(actor, book, year, "manage");
        finance.originalScope(actor, book, year, "create");
        finance.lockYearWrites(b, List.of(year));
        finance.personGate().lockModes(b, List.of(year));
        finance.personGate().unsupported(b, year);
        var selectedYear =
                db.one(
                        "SELECT year FROM plugin_finance_year WHERE tenant_id=? AND book_id=? AND"
                                + " id=?",
                        tenant,
                        book,
                        year);
        if (LocalDate.parse((String) e.get("businessDate")).getYear()
                != ((Number) selectedYear.get("year")).intValue())
            throw new FinanceException("RULE_VERSION_UNAVAILABLE", "本片未开放跨年来源/迁移更正，业务日期须属于目标年度");
        finance.validateLines(b, year, mapped);
        var business = new LinkedHashMap<>(e);
        business.remove("eventId");
        String sourceDigest = digest(business);
        Map<String, Object> mappedDigest = new LinkedHashMap<>(mapped);
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (var l : (List<Map<String, Object>>) mapped.get("lines")) {
            var n = new LinkedHashMap<>(l);
            for (String amount : List.of("debit", "credit"))
                n.put(amount, PrecisionRules.money((String) n.get(amount)).toPlainString());
            normalized.add(n);
        }
        mappedDigest.put("lines", normalized);
        String effectDigest = digest(List.of(sourceDigest, mappedDigest));
        String route = book + "/" + year + "/source-events";
        var cached =
                db.list(
                        "SELECT route,payload_hash,response FROM plugin_finance_idempotency WHERE"
                                + " actor_id=? AND key=?",
                        user,
                        key);
        if (!cached.isEmpty()) {
            var old = cached.getFirst();
            if (!route.equals(old.get("route")) || !effectDigest.equals(old.get("payload_hash")))
                throw new FinanceException("IDEMPOTENCY_CONFLICT", "请求键已用于不同来源内容或目标");
            return finance.decode((String) old.get("response"));
        }
        var existing =
                db.list(
                        "SELECT id,book_id,year_id,payload_digest FROM plugin_finance_source_event"
                                + " WHERE tenant_id=? AND source_system=? AND source_event_key=?",
                        tenant,
                        system,
                        external);
        UUID sourceId;
        if (!existing.isEmpty()) {
            var old = existing.getFirst();
            UUID oldBook = (UUID) old.get("book_id"), oldYear = (UUID) old.get("year_id");
            if (!book.equals(oldBook) || !year.equals(oldYear)) {
                finance.originalScope(actor, oldBook, oldYear, "manage");
                throw new FinanceException("SOURCE_SCOPE_CONFLICT", "稳定来源已绑定其他已授权范围，不能换账套或年度再制证");
            }
            if (!sourceDigest.equals(old.get("payload_digest")))
                throw new FinanceException("SOURCE_PAYLOAD_CONFLICT", "同一稳定来源携带不同业务内容");
            sourceId = (UUID) old.get("id");
            var effect =
                    db.one(
                            "SELECT payload_digest FROM plugin_finance_business_effect WHERE"
                                    + " tenant_id=? AND effect_key=?",
                            tenant,
                            effectKey);
            if (!effectDigest.equals(effect.get("payload_digest")))
                throw new FinanceException("EFFECT_PAYLOAD_CONFLICT", "同一效果的实际分录已变化，版本标签不能产生第二结果");
        } else {
            sourceId = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_source_event(tenant_id,book_id,year_id,id,source_system,source_event_key,source_document_id,source_document_version,event_type,accounting_date,payload_digest,payload,created_by)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)",
                    tenant,
                    book,
                    year,
                    sourceId,
                    system,
                    external,
                    e.get("sourceDocumentId"),
                    e.get("sourceDocumentVersion"),
                    e.get("eventType"),
                    LocalDate.parse((String) e.get("accountingDate")),
                    sourceDigest,
                    finance.encode(finance.canonical(e)),
                    user);
            var plain = new LinkedHashMap<>(mapped);
            List<Map<String, Object>> plainLines = new ArrayList<>();
            for (var l : (List<Map<String, Object>>) mapped.get("lines")) {
                var n = new LinkedHashMap<>(l);
                n.remove("sourceLineId");
                plainLines.add(n);
            }
            plain.put("lines", plainLines);
            var draft = finance.createJournal(user, b, year, plain, null, "NORMAL");
            UUID journal = (UUID) draft.get("id");
            db.update(
                    "UPDATE plugin_finance_journal SET source_event_id=? WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=? AND id=?",
                    sourceId,
                    tenant,
                    book,
                    year,
                    journal);
            UUID effectId = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_business_effect(tenant_id,book_id,year_id,id,source_event_id,source_system,source_event_key,stable_consumer,effect_type,effect_key,payload_digest,journal_id,mapping_payload,mapping_rule_version,handler_version)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)",
                    tenant,
                    book,
                    year,
                    effectId,
                    sourceId,
                    system,
                    external,
                    CONSUMER,
                    EFFECT,
                    effectKey,
                    effectDigest,
                    journal,
                    finance.encode(finance.canonical(mappedDigest)),
                    mapping,
                    handler);
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_consumer_receipt(tenant_id,effect_id,stable_consumer,effect_key,payload_digest,handler_version)"
                        + " VALUES(?,?,?,?,?,?)",
                    tenant,
                    effectId,
                    CONSUMER,
                    effectKey,
                    effectDigest,
                    handler);
            finance.originalAudit(
                    user,
                    b,
                    year,
                    "SOURCE_EFFECT_CREATED",
                    journal,
                    Map.of(
                            "sourceEventId",
                            sourceId,
                            "effectKey",
                            effectKey,
                            "sourceDigest",
                            sourceDigest,
                            "effectDigest",
                            effectDigest,
                            "mappingRuleVersion",
                            mapping,
                            "handlerVersion",
                            handler));
            FinanceOutbox.enqueue(
                    db, finance, b, year, "SOURCE_JOURNAL_DRAFT_CREATED", journal, user);
        }
        var response = db.one(SELECT + " AND s.id=?", tenant, book, year, sourceId);
        db.update(
                "INSERT INTO plugin_finance_idempotency VALUES(?,?,?,?,?)",
                user,
                key,
                route,
                effectDigest,
                finance.encode(response));
        return response;
    }
}
