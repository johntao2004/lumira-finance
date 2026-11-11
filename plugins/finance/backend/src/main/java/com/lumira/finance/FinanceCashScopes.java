package com.lumira.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** Monthly qualification boundary only; no movement, allocation, coverage or report is created. */
@Service
public class FinanceCashScopes {
    static final String SCHEMA = "cashflow-working-paper/1";
    private final FinanceStore db;
    private final FinanceService finance;

    public FinanceCashScopes(FinanceStore db, FinanceService finance) {
        this.db = db;
        this.finance = finance;
    }

    static void fields(Map<String, Object> in, Set<String> fields) {
        if (!in.keySet().equals(fields)) throw FinanceException.invalid("现金范围字段缺失或含不支持的字段");
    }

    static String text(Map<String, Object> in, String key, int max) {
        if (!(in.get(key) instanceof String value)
                || value.strip().isEmpty()
                || value.length() > max
                || value.indexOf('\u0000') >= 0) throw FinanceException.invalid("无效现金范围字段: " + key);
        return value.strip();
    }

    static long positive(Map<String, Object> in, String key) {
        Object value = in.get(key);
        try {
            if (!(value instanceof Integer || value instanceof Long || value instanceof String))
                throw new IllegalArgumentException();
            long result = Long.parseLong(value.toString());
            if (result < 1) throw new IllegalArgumentException();
            return result;
        } catch (IllegalArgumentException invalid) {
            throw FinanceException.invalid(key + "必须为正整数");
        }
    }

    static LocalDate date(Map<String, Object> in, String key) {
        try {
            return LocalDate.parse(text(in, key, 10));
        } catch (java.time.DateTimeException invalid) {
            throw FinanceException.invalid("现金范围日期必须为YYYY-MM-DD");
        }
    }

    String digest(Object value) {
        return FinanceService.hash(finance.encode(finance.canonical(value)));
    }

    Map<String, Object> authorize(String actor, UUID book, UUID year, String operation) {
        if (operation.equals("create") || operation.equals("submit")) {
            var b = finance.originalScope(actor, book, year, "manage");
            finance.originalScope(actor, book, year, "create");
            return b;
        }
        return finance.originalScope(actor, book, year, "approve");
    }

    Map<String, Object> period(Map<String, Object> b, UUID year, LocalDate start, boolean lock) {
        var yr =
                db.one(
                        "SELECT year,status,precision_profile,precision_review_required FROM"
                            + " plugin_finance_year WHERE tenant_id=? AND book_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year);
        if (!"V4".equals(yr.get("precision_profile"))
                || Boolean.TRUE.equals(yr.get("precision_review_required")))
            throw new FinanceException("LEGACY_PRECISION_REVIEW_REQUIRED", "旧精度年度仅可查阅，不能建立现金范围");
        if (start.getYear() != ((Number) yr.get("year")).intValue())
            throw FinanceException.invalid("现金范围须属于所选年度");
        if (!"OPEN".equals(yr.get("status")))
            throw new FinanceException("PERIOD_CLOSED", "现金范围复核要求年度开放");
        var p =
                db.one(
                        "SELECT id,status FROM plugin_finance_period WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=? AND month=?"
                                + (lock ? " FOR UPDATE" : ""),
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        start.getMonthValue());
        if (!"OPEN".equals(p.get("status")))
            throw new FinanceException("PERIOD_CLOSED", "现金范围复核要求对应月份开放");
        return p;
    }

    Map<String, Object> account(Map<String, Object> b, UUID year, UUID id) {
        var account =
                db.one(
                        "SELECT id,code,name,normal_side AS"
                            + " \"normalSide\",category,cash_flow_category AS"
                            + " \"cashFlowCategory\",enabled,purpose FROM plugin_finance_account"
                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        id);
        if (!Boolean.TRUE.equals(account.get("enabled"))
                || !"ASSET".equals(account.get("category"))
                || !"DEBIT".equals(account.get("normalSide")))
            throw new FinanceException("CASH_SCOPE_REVIEW_REQUIRED", "本片仅接收启用的借向资产年度科目");
        return account;
    }

    Map<String, Object> evidence(Map<String, Object> b, UUID year, UUID id) {
        var original =
                db.one(
                        "SELECT id,filename,sha256,size_bytes,content,version FROM"
                            + " plugin_finance_original WHERE tenant_id=? AND book_id=? AND"
                            + " year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        id);
        if (!(original.get("content") instanceof byte[] bytes)
                || bytes.length != ((Number) original.get("size_bytes")).intValue()
                || !FinanceOriginals.digest(bytes).equals(original.get("sha256")))
            throw new FinanceException("CASH_SCOPE_STALE", "原件字节或服务端摘要不匹配，不能作为范围依据");
        return Map.of(
                "id",
                id.toString(),
                "filename",
                original.get("filename"),
                "sha256",
                original.get("sha256"),
                "version",
                original.get("version"));
    }

    List<Map<String, Object>> prepareItems(Map<String, Object> b, UUID year, Object input) {
        if (!(input instanceof List<?> values) || values.isEmpty() || values.size() > 20)
            throw FinanceException.invalid("现金范围必须包含1至20项");
        List<Map<String, Object>> result = new ArrayList<>();
        Set<UUID> accounts = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?>)) throw FinanceException.invalid("现金范围项必须为对象");
            var item = new LinkedHashMap<>((Map<String, Object>) value);
            fields(
                    item,
                    Set.of(
                            "accountId",
                            "instrumentKind",
                            "singleCashInstrument",
                            "qualification",
                            "restrictionKind",
                            "availableOnDemand",
                            "eligibilityBasis",
                            "evidenceOriginalId"));
            if (!Boolean.TRUE.equals(item.get("singleCashInstrument")))
                throw new FinanceException(
                        "CASH_SCOPE_REVIEW_REQUIRED", "必须明确确认本科目仅对应一个独立现金工具或账户；混合科目不支持");
            UUID id = FinanceService.id(item, "accountId");
            if (!accounts.add(id)) throw FinanceException.invalid("同一年度科目不能在范围中重复出现");
            String instrument = text(item, "instrumentKind", 30),
                    qualification = text(item, "qualification", 20),
                    restriction = text(item, "restrictionKind", 20);
            if (!Set.of("CASH_ON_HAND", "DEMAND_DEPOSIT").contains(instrument))
                throw new FinanceException("RULE_VERSION_UNAVAILABLE", "货币投资、现金等价物及其他复杂现金种类尚未开放");
            if (!Set.of("INCLUDED", "EXCLUDED").contains(qualification)
                    || !Set.of("NONE", "RESTRICTED", "OTHER").contains(restriction)
                    || !(item.get("availableOnDemand") instanceof Boolean available))
                throw FinanceException.invalid("现金资格、限制类别或可随时支取标志无效");
            if (qualification.equals("INCLUDED") && (!restriction.equals("NONE") || !available))
                throw new FinanceException("CASH_SCOPE_REVIEW_REQUIRED", "纳入现金范围必须无使用限制且可随时支取");
            var snapshot = account(b, year, id);
            var original = evidence(b, year, FinanceService.id(item, "evidenceOriginalId"));
            Map<String, Object> prepared = new LinkedHashMap<>();
            prepared.put("accountId", id.toString());
            prepared.put("instrumentKind", instrument);
            prepared.put("singleCashInstrument", item.get("singleCashInstrument"));
            prepared.put("qualification", qualification);
            prepared.put("restrictionKind", restriction);
            prepared.put("availableOnDemand", available);
            prepared.put("eligibilityBasis", text(item, "eligibilityBasis", 2000));
            prepared.put("evidenceOriginalId", original.get("id"));
            prepared.put("originalSha256", original.get("sha256"));
            prepared.put("originalFilename", original.get("filename"));
            prepared.put("originalVersion", original.get("version"));
            prepared.put("accountSnapshot", snapshot);
            prepared.put("accountVersionDigest", digest(snapshot));
            result.add(prepared);
        }
        return result;
    }

    void revalidate(Map<String, Object> b, UUID year, Map<String, Object> content) {
        for (var item : (List<Map<String, Object>>) content.get("items")) {
            try {
                var current = account(b, year, UUID.fromString((String) item.get("accountId")));
                var original =
                        evidence(b, year, UUID.fromString((String) item.get("evidenceOriginalId")));
                if (!digest(current).equals(item.get("accountVersionDigest"))
                        || !original.get("sha256").equals(item.get("originalSha256"))
                        || !original.get("filename").equals(item.get("originalFilename"))
                        || ((Number) original.get("version")).longValue()
                                != ((Number) item.get("originalVersion")).longValue())
                    throw new FinanceException("CASH_SCOPE_STALE", "科目或原件版本与不可编辑范围快照不一致，请重新准备范围");
            } catch (FinanceException denied) {
                throw new FinanceException("CASH_SCOPE_STALE", "科目或原件不再符合提交快照，请重新准备范围");
            }
        }
    }

    void preparer(Map<String, Object> b, UUID year, Map<String, Object> row) {
        try {
            String name = (String) row.get("prepared_username");
            var scope = finance.originalScope(name, (UUID) b.get("id"), year, "manage");
            finance.originalScope(name, (UUID) b.get("id"), year, "create");
            if (!Objects.equals(scope.get("actor_id"), row.get("prepared_by")))
                throw new IllegalArgumentException();
        } catch (RuntimeException stale) {
            throw new FinanceException("APPROVAL_STALE", "制备人的当前管理、制单或年度授权已失效");
        }
    }

    Map<String, Object> content(Map<String, Object> row) {
        var value = finance.decode((String) row.get("content_text"));
        if (!digest(value).equals(row.get("content_digest")))
            throw new FinanceException("CASH_SCOPE_STALE", "现金范围内容与摘要不一致");
        return value;
    }

    static final String SELECT =
            "SELECT id,schema_version AS \"schemaVersion\",standard,report_scope AS"
                + " \"reportScope\",currency,template_id AS \"templateId\",period_start AS"
                + " \"periodStart\",period_end AS \"periodEnd\",status,version,content_version AS"
                + " \"contentVersion\",content_digest AS \"contentDigest\",prepared_by AS"
                + " \"preparedBy\",last_edited_by AS \"lastEditedBy\",approved_by AS"
                + " \"approvedBy\",approved_scope_version AS \"scopeVersion\",created_at AS"
                + " \"createdAt\",submitted_at AS \"submittedAt\",approved_at AS"
                + " \"approvedAt\",decision_by AS \"decisionBy\",decided_at AS \"decidedAt\" FROM"
                + " plugin_finance_cash_scope_revision WHERE tenant_id=? AND book_id=? AND"
                + " year_id=?";

    Map<String, Object> watermark(Map<String, Object> b, UUID year) {
        var rows =
                db.list(
                        "SELECT scope_version FROM plugin_finance_cash_scope_guard WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year);
        return Map.of(
                "scopeVersion",
                rows.isEmpty() ? 0L : rows.getFirst().get("scope_version"),
                "year",
                finance.watermark(b, year));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(String actor, UUID book, UUID year, int limit, String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        var page =
                FinancePages.page(
                        db, finance, b, year, FinancePages.Kind.CASH_SCOPE, limit, cursor, SELECT);
        page.put("asOfWatermark", watermark(b, year));
        return page;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(String actor, UUID book, UUID year, UUID id) {
        var b = finance.originalScope(actor, book, year, "read");
        return detail(b, year, id);
    }

    Map<String, Object> detail(Map<String, Object> b, UUID year, UUID id) {
        var summary = db.one(SELECT + " AND id=?", b.get("tenant_id"), b.get("id"), year, id);
        var row =
                db.one(
                        "SELECT content::text AS content_text,content_digest,decision_reason FROM"
                            + " plugin_finance_cash_scope_revision WHERE tenant_id=? AND book_id=?"
                            + " AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        id);
        var c = content(row);
        summary.put("tenantId", b.get("tenant_id"));
        summary.put("bookId", b.get("id"));
        summary.put("yearId", year);
        summary.put("legalEntityId", c.get("legalEntityId"));
        summary.put("reason", c.get("reason"));
        summary.put("items", c.get("items"));
        summary.put("decisionReason", row.get("decision_reason"));
        summary.put("asOfWatermark", watermark(b, year));
        return summary;
    }

    @Transactional
    public Map<String, Object> write(
            String actor, UUID book, UUID year, String op, UUID id, Map<String, Object> input) {
        if (!Set.of("create", "submit", "approve", "reject").contains(op))
            throw FinanceException.invalid("未知现金范围动作");
        var b = authorize(actor, book, year, op);
        Set<String> allowed =
                op.equals("create")
                        ? Set.of(
                                "schemaVersion",
                                "idempotencyKey",
                                "reportScope",
                                "currency",
                                "templateId",
                                "periodStart",
                                "periodEnd",
                                "reason",
                                "items")
                        : Set.of(
                                "schemaVersion",
                                "idempotencyKey",
                                "expectedVersion",
                                "targetContentVersion",
                                "contentDigest",
                                "reason");
        fields(input, allowed);
        if (!SCHEMA.equals(input.get("schemaVersion")))
            throw FinanceException.invalid("现金范围schemaVersion无效");
        String key = text(input, "idempotencyKey", 100);
        if (key.length() < 8) throw FinanceException.invalid("幂等键至少8字符");
        String reason = text(input, "reason", 1000);
        UUID user = (UUID) b.get("actor_id");
        db.jdbc
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")
                .param(user + ":" + key)
                .query((rs, n) -> 0)
                .list();
        db.one(
                "SELECT id FROM plugin_finance_book WHERE tenant_id=? AND id=? FOR UPDATE",
                b.get("tenant_id"),
                book);
        b = authorize(actor, book, year, op);
        finance.lockYearWrites(b, List.of(year));
        db.update(
                "INSERT INTO plugin_finance_cash_scope_guard(tenant_id,book_id,year_id)"
                    + " VALUES(?,?,?) ON CONFLICT DO NOTHING",
                b.get("tenant_id"),
                book,
                year);
        db.one(
                "SELECT scope_version FROM plugin_finance_cash_scope_guard WHERE tenant_id=? AND"
                    + " book_id=? AND year_id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year);
        Map<String, Object> old = null;
        LocalDate start, end;
        if (op.equals("create")) {
            start = date(input, "periodStart");
            end = date(input, "periodEnd");
            if (start.getDayOfMonth() != 1
                    || !end.equals(start.withDayOfMonth(start.lengthOfMonth())))
                throw FinanceException.invalid("现金范围必须恰好覆盖一个完整自然月");
        } else {
            old =
                    db.one(
                            "SELECT *,content::text AS content_text FROM"
                                + " plugin_finance_cash_scope_revision WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND id=?",
                            b.get("tenant_id"),
                            book,
                            year,
                            id);
            start = LocalDate.parse(old.get("period_start").toString());
            end = LocalDate.parse(old.get("period_end").toString());
            if (op.equals("submit") && !user.equals(old.get("prepared_by")))
                throw new FinanceException("SCOPE_DENIED", "仅原制备人可提交不可编辑范围");
            if (!op.equals("submit")) {
                if (user.equals(old.get("prepared_by")) || user.equals(old.get("last_edited_by")))
                    throw new FinanceException("SELF_APPROVAL_FORBIDDEN", "现金范围必须由独立账号复核");
                if (op.equals("approve")) preparer(b, year, old);
            }
        }
        var period = period(b, year, start, true);
        var request = new LinkedHashMap<>(input);
        request.remove("idempotencyKey");
        if (!op.equals("create")) {
            request.put("expectedVersion", positive(input, "expectedVersion"));
            request.put("targetContentVersion", positive(input, "targetContentVersion"));
        }
        String route = book + "/" + year + "/cashflow/scope-revisions/" + id + "/" + op,
                requestDigest = digest(request);
        var cached =
                db.list(
                        "SELECT route,payload_hash,response FROM plugin_finance_idempotency WHERE"
                            + " actor_id=? AND key=?",
                        user,
                        key);
        if (!cached.isEmpty()) {
            var prior = cached.getFirst();
            if (!route.equals(prior.get("route"))
                    || !requestDigest.equals(prior.get("payload_hash")))
                throw new FinanceException("IDEMPOTENCY_CONFLICT", "请求键已用于不同现金范围内容");
            return finance.decode((String) prior.get("response"));
        }
        if (op.equals("create")) {
            if (!"SEPARATE".equals(input.get("reportScope"))
                    || !"CNY".equals(input.get("currency")))
                throw new FinanceException("RULE_VERSION_UNAVAILABLE", "本片只支持个别主体人民币范围");
            String template = "CAS".equals(b.get("standard")) ? "CAS-CF-2019-A2" : "SBE-CF-2011";
            if (!template.equals(input.get("templateId")))
                throw new FinanceException("RULE_VERSION_UNAVAILABLE", "现金范围模板必须与账套CAS/SMALL制度一致");
            if (!db.list(
                            "SELECT id FROM plugin_finance_cash_scope_revision WHERE tenant_id=?"
                                + " AND book_id=? AND year_id=? AND period_start=? AND"
                                + " status='APPROVED'",
                            b.get("tenant_id"),
                            book,
                            year,
                            start)
                    .isEmpty())
                throw new FinanceException(
                        "CASH_SCOPE_CHANGE_UNSUPPORTED", "该月份已有批准范围，本片不支持替换或期中范围变更");
            var items = prepareItems(b, year, input.get("items"));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("schemaVersion", SCHEMA);
            c.put("tenantId", b.get("tenant_id").toString());
            c.put("bookId", book.toString());
            c.put("yearId", year.toString());
            c.put("legalEntityId", b.get("legal_entity_id").toString());
            c.put("standard", b.get("standard"));
            c.put("reportScope", "SEPARATE");
            c.put("currency", "CNY");
            c.put("templateId", template);
            c.put("periodStart", start.toString());
            c.put("periodEnd", end.toString());
            c.put("preparedBy", user.toString());
            c.put("lastEditedBy", user.toString());
            c.put("reason", reason);
            c.put("items", items);
            id = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_cash_scope_revision(tenant_id,book_id,year_id,id,period_id,schema_version,standard,report_scope,currency,template_id,period_start,period_end,content_digest,content,prepared_by,prepared_username,last_edited_by,reason)"
                        + " VALUES(?,?,?,?,?,?,?,'SEPARATE','CNY',?,?,?,?,?::jsonb,?,?,?,?)",
                    b.get("tenant_id"),
                    book,
                    year,
                    id,
                    period.get("id"),
                    SCHEMA,
                    b.get("standard"),
                    template,
                    start,
                    end,
                    digest(c),
                    finance.encode(finance.canonical(c)),
                    user,
                    actor,
                    user,
                    reason);
            int no = 0;
            for (var item : items)
                db.update(
                        "INSERT INTO"
                            + " plugin_finance_cash_scope_item(tenant_id,book_id,year_id,scope_revision_id,item_no,account_id,instrument_kind,single_cash_instrument,qualification,restriction_kind,available_on_demand,eligibility_basis,evidence_original_id,original_sha256,original_filename,account_version_digest,account_snapshot)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                        b.get("tenant_id"),
                        book,
                        year,
                        id,
                        ++no,
                        UUID.fromString((String) item.get("accountId")),
                        item.get("instrumentKind"),
                        item.get("singleCashInstrument"),
                        item.get("qualification"),
                        item.get("restrictionKind"),
                        item.get("availableOnDemand"),
                        item.get("eligibilityBasis"),
                        UUID.fromString((String) item.get("evidenceOriginalId")),
                        item.get("originalSha256"),
                        item.get("originalFilename"),
                        item.get("accountVersionDigest"),
                        finance.encode(finance.canonical(item.get("accountSnapshot"))));
        } else {
            old =
                    db.one(
                            "SELECT *,content::text AS content_text FROM"
                                + " plugin_finance_cash_scope_revision WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND id=? FOR UPDATE",
                            b.get("tenant_id"),
                            book,
                            year,
                            id);
            if (((Number) old.get("version")).longValue() != positive(input, "expectedVersion"))
                throw new FinanceException("VERSION_CONFLICT", "现金范围版本已变化");
            var c = content(old);
            String supplied = text(input, "contentDigest", 64);
            if (!supplied.matches("[a-f0-9]{64}")
                    || !supplied.equals(old.get("content_digest"))
                    || positive(input, "targetContentVersion")
                            != ((Number) old.get("content_version")).longValue())
                throw new FinanceException("APPROVAL_STALE", "复核未绑定当前不可编辑内容版本/摘要");
            if (!op.equals("reject")) revalidate(b, year, c);
            String status = (String) old.get("status");
            if (op.equals("submit")) {
                if (!status.equals("DRAFT"))
                    throw new FinanceException("VERSION_CONFLICT", "仅DRAFT可提交");
                db.update(
                        "UPDATE plugin_finance_cash_scope_revision SET"
                            + " status='SUBMITTED',submitted_at=now(),version=version+1 WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        book,
                        year,
                        id);
            } else {
                if (!status.equals("SUBMITTED"))
                    throw new FinanceException("VERSION_CONFLICT", "仅SUBMITTED可复核");
                if (op.equals("approve")) {
                    if (!db.list(
                                    "SELECT id FROM plugin_finance_cash_scope_revision WHERE"
                                        + " tenant_id=? AND book_id=? AND year_id=? AND"
                                        + " period_start=? AND status='APPROVED'",
                                    b.get("tenant_id"),
                                    book,
                                    year,
                                    start)
                            .isEmpty())
                        throw new FinanceException(
                                "CASH_SCOPE_CHANGE_UNSUPPORTED", "该月份已有批准范围，不能覆盖");
                    long generation =
                            ((Number)
                                            db.one(
                                                            "UPDATE plugin_finance_cash_scope_guard"
                                                                + " SET scope_version=scope_version+1"
                                                                + " WHERE tenant_id=? AND book_id=?"
                                                                + " AND year_id=? RETURNING"
                                                                + " scope_version",
                                                            b.get("tenant_id"),
                                                            book,
                                                            year)
                                                    .get("scope_version"))
                                    .longValue();
                    db.update(
                            "UPDATE plugin_finance_cash_scope_revision SET"
                                + " status='APPROVED',approved_by=?,approved_at=now(),approved_scope_version=?,decision_by=?,decided_at=now(),decision_reason=?,version=version+1"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            user,
                            generation,
                            user,
                            reason,
                            b.get("tenant_id"),
                            book,
                            year,
                            id);
                } else
                    db.update(
                            "UPDATE plugin_finance_cash_scope_revision SET"
                                + " status='REJECTED',decision_by=?,decided_at=now(),decision_reason=?,version=version+1"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            user,
                            reason,
                            b.get("tenant_id"),
                            book,
                            year,
                            id);
            }
        }
        var result = detail(b, year, id);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("request", request);
        audit.put(
                "before",
                old == null
                        ? null
                        : Map.of(
                                "id",
                                old.get("id"),
                                "status",
                                old.get("status"),
                                "version",
                                old.get("version"),
                                "contentVersion",
                                old.get("content_version"),
                                "contentDigest",
                                old.get("content_digest"),
                                "content",
                                content(old)));
        audit.put("after", result);
        finance.originalAudit(
                user, b, year, "CASH_SCOPE_" + op.toUpperCase(Locale.ROOT), id, audit);
        db.update(
                "INSERT INTO plugin_finance_idempotency VALUES(?,?,?,?,?)",
                user,
                key,
                route,
                requestDigest,
                finance.encode(result));
        return result;
    }
}
