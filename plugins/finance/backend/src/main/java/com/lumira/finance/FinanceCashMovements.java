package com.lumira.finance;

import com.lumira.finance.domain.PrecisionRules;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Unverified retained-source assertions. Never creates a journal or report contribution. */
@Service
public class FinanceCashMovements {
    static final String SCHEMA = "cash-movement-registration/1";
    static final Map<String, String> SYSTEMS =
            Map.of(
                    "BANK_STATEMENT_MANUAL_V1",
                    "BANK_STATEMENT",
                    "CASH_BOOK_MANUAL_V1",
                    "CASH_BOOK",
                    "CASH_RECEIPT_MANUAL_V1",
                    "CASH_RECEIPT");
    static final Map<String, String> KINDS =
            Map.of(
                    "ORDINARY_CUSTOMER_RECEIPT",
                    "IN",
                    "ORDINARY_SUPPLIER_PAYMENT",
                    "OUT",
                    "ORDINARY_PAYROLL",
                    "OUT",
                    "ORDINARY_TAX_PAYMENT",
                    "OUT",
                    "ORDINARY_BORROWING_RECEIPT",
                    "IN",
                    "ORDINARY_PRINCIPAL_REPAYMENT",
                    "OUT",
                    "ORDINARY_CAPITAL_RECEIPT",
                    "IN");
    private final FinanceStore db;
    private final FinanceService finance;
    private final FinanceCashScopes scopes;

    public FinanceCashMovements(FinanceStore db, FinanceService finance, FinanceCashScopes scopes) {
        this.db = db;
        this.finance = finance;
        this.scopes = scopes;
    }

    static FinanceException conflict(String code, String message) {
        return new FinanceException(code, message);
    }

    String digest(Object value) {
        return scopes.digest(value);
    }

    static String text(Map<String, Object> value, String key, int max) {
        return FinanceCashScopes.text(value, key, max);
    }

    static long positive(Map<String, Object> value, String key) {
        return FinanceCashScopes.positive(value, key);
    }

    static UUID uuid(Map<String, Object> value, String key) {
        return FinanceService.id(value, key);
    }

    static String instrument(String system) {
        return "BANK_STATEMENT_MANUAL_V1".equals(system) ? "DEMAND_DEPOSIT" : "CASH_ON_HAND";
    }

    static String sourceLabel(String system) {
        return switch (system) {
            case "BANK_STATEMENT_MANUAL_V1" -> "手工银行流水来源（未核实）";
            case "CASH_BOOK_MANUAL_V1" -> "手工现金簿来源（未核实）";
            default -> "手工现金收据来源（未核实）";
        };
    }

    static String kindLabel(String kind) {
        return switch (kind) {
            case "ORDINARY_CUSTOMER_RECEIPT" -> "普通客户收款";
            case "ORDINARY_SUPPLIER_PAYMENT" -> "普通供应商付款";
            case "ORDINARY_PAYROLL" -> "普通工资付款";
            case "ORDINARY_TAX_PAYMENT" -> "普通税费付款";
            case "ORDINARY_BORROWING_RECEIPT" -> "普通借款收款";
            case "ORDINARY_PRINCIPAL_REPAYMENT" -> "普通偿还借款本金";
            default -> "普通资本投入收款";
        };
    }

    static String money(Map<String, Object> input) {
        text(input, "amount", 100);
        String literal = (String) input.get("amount");
        try {
            var amount = PrecisionRules.formalCurrencyMoney(literal, 2);
            if (amount.signum() <= 0) throw FinanceException.invalid("现金来源金额必须大于零");
            return amount.toPlainString();
        } catch (IllegalArgumentException invalid) {
            throw conflict(
                    "DECIMAL_OVERFLOW".equals(invalid.getMessage())
                            ? "AMOUNT_OVERFLOW"
                            : "INVALID_CURRENCY_SCALE",
                    "现金来源金额须为容量内人民币正数，最多两位小数");
        }
    }

    Map<String, Object> watermark(Map<String, Object> b, UUID year) {
        var rows =
                db.list(
                        "SELECT registration_version FROM plugin_finance_cash_registration_guard"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year);
        return Map.of(
                "registrationVersion",
                rows.isEmpty() ? 0L : rows.getFirst().get("registration_version"),
                "year",
                finance.watermark(b, year));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> catalog(String actor, UUID book, UUID year) {
        finance.originalScope(actor, book, year, "read");
        var systems = new ArrayList<Map<String, Object>>();
        SYSTEMS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        e ->
                                systems.add(
                                        Map.of(
                                                "id",
                                                e.getKey(),
                                                "evidenceKind",
                                                e.getValue(),
                                                "instrumentKinds",
                                                List.of(instrument(e.getKey())),
                                                "label",
                                                sourceLabel(e.getKey()),
                                                "channelVerificationStatus",
                                                "NOT_VERIFIED")));
        var kinds = new ArrayList<Map<String, Object>>();
        KINDS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        e ->
                                kinds.add(
                                        Map.of(
                                                "id",
                                                e.getKey(),
                                                "direction",
                                                e.getValue(),
                                                "label",
                                                kindLabel(e.getKey()))));
        return Map.of(
                "schemaVersion",
                SCHEMA,
                "catalogVersion",
                "manual-cash-source-catalog/1",
                "sourceSystems",
                systems,
                "transactionKinds",
                kinds);
    }

    static final String SELECT =
            "SELECT id,schema_version AS \"schemaVersion\",source_system AS"
                + " \"sourceSystem\",source_movement_key AS"
                + " \"sourceMovementKey\",source_document_id AS"
                + " \"sourceDocumentId\",source_document_version AS"
                + " \"sourceDocumentVersion\",source_row_reference AS"
                + " \"sourceRowReference\",account_id AS \"accountId\",cash_scope_revision_id AS"
                + " \"cashScopeRevisionId\",approved_scope_version AS"
                + " \"approvedScopeVersion\",occurred_date AS \"occurredDate\",direction,amount AS"
                + " \"normalizedAmount\",currency,transaction_kind AS"
                + " \"transactionKind\",journal_id AS \"journalId\",line_no AS"
                + " \"lineNo\",posting_seq AS \"postingSeq\",status,'UNREVIEWED' AS"
                + " \"verificationStatus\",'UNCLASSIFIED' AS \"classificationStatus\",false AS"
                + " \"reportContributionEnabled\",version,content_version AS"
                + " \"contentVersion\",content_digest AS \"contentDigest\",prepared_by AS"
                + " \"preparedBy\",created_at AS \"createdAt\",voided_by AS \"voidedBy\",voided_at"
                + " AS \"voidedAt\",void_reason AS \"voidReason\" FROM"
                + " plugin_finance_cash_movement_registration WHERE tenant_id=? AND book_id=? AND"
                + " year_id=?";

    Map<String, Object> row(Map<String, Object> b, UUID year, UUID id) {
        return db.one(
                "SELECT *,content::text AS content_text FROM"
                    + " plugin_finance_cash_movement_registration WHERE tenant_id=? AND book_id=?"
                    + " AND year_id=? AND id=?",
                b.get("tenant_id"),
                b.get("id"),
                year,
                id);
    }

    Map<String, Object> detail(Map<String, Object> b, UUID year, UUID id) {
        var result = db.one(SELECT + " AND id=?", b.get("tenant_id"), b.get("id"), year, id);
        var r = row(b, year, id);
        var c = finance.decode((String) r.get("content_text"));
        if (!digest(c).equals(r.get("content_digest")))
            throw conflict("CASH_MOVEMENT_SCOPE_STALE", "现金来源不可变快照摘要不匹配");
        for (String key :
                List.of(
                        "tenantId",
                        "bookId",
                        "yearId",
                        "legalEntityId",
                        "description",
                        "reason",
                        "evidenceKind",
                        "originalSnapshot",
                        "scopeContentDigest",
                        "scopeItemSnapshot",
                        "accountSnapshot",
                        "matchedLegSnapshot")) result.put(key, c.get(key));
        result.put("asOfWatermark", watermark(b, year));
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(String actor, UUID book, UUID year, UUID id) {
        return detail(finance.originalScope(actor, book, year, "read"), year, id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(
            String actor, UUID book, UUID year, String status, int limit, String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        if (status != null && !Set.of("REGISTERED", "VOIDED").contains(status))
            throw FinanceException.invalid("现金来源状态无效");
        var kind =
                status == null
                        ? FinancePages.Kind.MOVEMENT_ALL
                        : status.equals("REGISTERED")
                                ? FinancePages.Kind.MOVEMENT_REGISTERED
                                : FinancePages.Kind.MOVEMENT_VOIDED;
        var page =
                FinancePages.page(
                        db,
                        finance,
                        b,
                        year,
                        kind,
                        limit,
                        cursor,
                        SELECT + (status == null ? "" : " AND status='" + status + "'"));
        page.put("asOfWatermark", watermark(b, year));
        return page;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> scopeItem(
            Map<String, Object> b,
            UUID year,
            UUID scope,
            UUID account,
            LocalDate date,
            boolean current) {
        var r =
                db.one(
                        "SELECT *,content::text AS content_text FROM"
                            + " plugin_finance_cash_scope_revision WHERE tenant_id=? AND book_id=?"
                            + " AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        scope);
        if (!"APPROVED".equals(r.get("status"))
                || date.isBefore(LocalDate.parse(r.get("period_start").toString()))
                || date.isAfter(LocalDate.parse(r.get("period_end").toString())))
            throw conflict("CASH_MOVEMENT_SCOPE_STALE", "必须使用现金发生月份的已批准范围");
        var c = scopes.content(r);
        for (var item : (List<Map<String, Object>>) c.get("items"))
            if (account.toString().equals(item.get("accountId"))) {
                if (!"INCLUDED".equals(item.get("qualification"))
                        || !Boolean.TRUE.equals(item.get("singleCashInstrument"))
                        || !"NONE".equals(item.get("restrictionKind"))
                        || !Boolean.TRUE.equals(item.get("availableOnDemand")))
                    throw conflict("CASH_MOVEMENT_SCOPE_STALE", "科目不属于已批准纳入的独立现金工具");
                if (current) {
                    if (!digest(scopes.account(b, year, account))
                            .equals(item.get("accountVersionDigest")))
                        throw conflict("CASH_MOVEMENT_SCOPE_STALE", "当前科目已偏离现金范围批准快照");
                    var evidence =
                            scopes.evidence(
                                    b,
                                    year,
                                    UUID.fromString((String) item.get("evidenceOriginalId")));
                    if (!evidence.get("sha256").equals(item.get("originalSha256")))
                        throw conflict("CASH_MOVEMENT_SCOPE_STALE", "范围资格原件摘要已失效");
                }
                var result = new LinkedHashMap<String, Object>(item);
                result.put("approvedScopeVersion", r.get("approved_scope_version"));
                result.put("scopeContentDigest", r.get("content_digest"));
                return result;
            }
        throw conflict("CASH_MOVEMENT_SCOPE_STALE", "现金范围不含所选年度科目");
    }

    static final String LEGS =
            "SELECT j.id AS \"journalId\",l.line_no AS \"lineNo\",l.account_id AS"
                + " \"accountId\",j.date,j.version AS \"journalVersion\",p.posting_seq AS"
                + " \"postingSeq\",p.digest AS \"postingDigest\",j.number,j.summary,CASE WHEN"
                + " l.debit>0 THEN 'IN' ELSE 'OUT' END AS direction,CASE WHEN l.debit>0 THEN"
                + " l.debit ELSE l.credit END AS \"normalizedAmount\",r.raw_registration_id AS"
                + " \"activeRegistrationId\",r.id AS \"activeClaimId\",r.claim_kind AS"
                + " \"activeClaimKind\",r.correction_id AS \"activeCorrectionId\" FROM"
                + " plugin_finance_journal j JOIN plugin_finance_line l ON"
                + " (l.tenant_id,l.book_id,l.year_id,l.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id)"
                + " JOIN plugin_finance_posting p ON"
                + " (p.tenant_id,p.book_id,p.year_id,p.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id)"
                + " LEFT JOIN plugin_finance_cash_leg_claim r ON"
                + " (r.tenant_id,r.book_id,r.year_id,r.journal_id,r.line_no)=(j.tenant_id,j.book_id,j.year_id,j.id,l.line_no)"
                + " AND r.state='ACTIVE' WHERE j.tenant_id=? AND j.book_id=? AND j.year_id=? AND"
                + " j.status='POSTED' AND p.posting_seq IS NOT NULL AND ((l.debit>0 AND l.credit=0)"
                + " OR (l.credit>0 AND l.debit=0))";

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> eligible(
            String actor,
            UUID book,
            UUID year,
            UUID scope,
            UUID account,
            int limit,
            String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        if (limit < 1 || limit > 100) throw FinanceException.invalid("每页数量须1至100");
        var header =
                db.one(
                        "SELECT period_start,period_end FROM plugin_finance_cash_scope_revision"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        book,
                        year,
                        scope);
        LocalDate start = LocalDate.parse(header.get("period_start").toString()),
                end = LocalDate.parse(header.get("period_end").toString());
        scopeItem(b, year, scope, account, start, true);
        var params =
                new ArrayList<Object>(List.of(b.get("tenant_id"), book, year, account, start, end));
        String sql = LEGS + " AND l.account_id=? AND j.date BETWEEN ? AND ?";
        if (cursor != null) {
            try {
                if (cursor.length() > 2000) throw new IllegalArgumentException();
                var c =
                        finance.decode(
                                new String(
                                        Base64.getUrlDecoder().decode(cursor),
                                        StandardCharsets.UTF_8));
                if (!c.keySet()
                                .equals(
                                        Set.of(
                                                "v", "book", "year", "scope", "account", "date",
                                                "journal", "line"))
                        || !Integer.valueOf(1).equals(c.get("v"))
                        || !book.toString().equals(c.get("book"))
                        || !year.toString().equals(c.get("year"))
                        || !scope.toString().equals(c.get("scope"))
                        || !account.toString().equals(c.get("account")))
                    throw new IllegalArgumentException();
                var date = LocalDate.parse((String) c.get("date"));
                var journal = UUID.fromString((String) c.get("journal"));
                if (!date.toString().equals(c.get("date"))
                        || !journal.toString().equals(c.get("journal"))
                        || !(c.get("line") instanceof Integer || c.get("line") instanceof Long))
                    throw new IllegalArgumentException();
                long line = positive(c, "line");
                if (line > 500) throw new IllegalArgumentException();
                if (db.list(
                                sql + " AND j.date=? AND j.id=? AND l.line_no=?",
                                concat(params, date, journal, line))
                        .isEmpty()) throw new IllegalArgumentException();
                sql += " AND (j.date,j.id,l.line_no)<(?,?,?)";
                params.addAll(List.of(date, journal, line));
            } catch (RuntimeException invalid) {
                throw FinanceException.invalid("现金腿游标类型、范围或锚点无效");
            }
        }
        sql += " ORDER BY j.date DESC,j.id DESC,l.line_no DESC LIMIT ?";
        params.add(limit + 1);
        var rows = db.list(sql, params.toArray());
        boolean more = rows.size() > limit;
        var items = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        String next = null;
        if (more) {
            var last = items.getLast();
            next =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    finance.encode(
                                                    Map.of(
                                                            "v",
                                                            1,
                                                            "book",
                                                            book.toString(),
                                                            "year",
                                                            year.toString(),
                                                            "scope",
                                                            scope.toString(),
                                                            "account",
                                                            account.toString(),
                                                            "date",
                                                            last.get("date"),
                                                            "journal",
                                                            last.get("journalId").toString(),
                                                            "line",
                                                            last.get("lineNo")))
                                            .getBytes(StandardCharsets.UTF_8));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("nextCursor", next);
        result.put("asOfWatermark", watermark(b, year));
        return result;
    }

    static Object[] concat(List<Object> values, Object... suffix) {
        var all = new ArrayList<>(values);
        all.addAll(Arrays.asList(suffix));
        return all.toArray();
    }

    static final Set<String> CREATE =
            Set.of(
                    "schemaVersion",
                    "idempotencyKey",
                    "sourceSystem",
                    "sourceMovementKey",
                    "sourceDocumentId",
                    "sourceDocumentVersion",
                    "evidenceKind",
                    "evidenceOriginalId",
                    "sourceRowReference",
                    "cashScopeRevisionId",
                    "expectedScopeVersion",
                    "scopeContentDigest",
                    "accountId",
                    "occurredDate",
                    "direction",
                    "amount",
                    "currency",
                    "transactionKind",
                    "description",
                    "journalId",
                    "lineNo",
                    "expectedJournalVersion",
                    "expectedPostingSeq",
                    "reason");

    Map<String, Object> normalized(Map<String, Object> input, boolean create) {
        FinanceCashScopes.fields(
                input,
                create
                        ? CREATE
                        : Set.of(
                                "schemaVersion",
                                "idempotencyKey",
                                "expectedVersion",
                                "targetContentVersion",
                                "contentDigest",
                                "reason"));
        if (!SCHEMA.equals(input.get("schemaVersion")))
            throw FinanceException.invalid("现金来源schemaVersion无效");
        var n = new LinkedHashMap<String, Object>(input);
        n.remove("idempotencyKey");
        n.put("reason", text(input, "reason", 1000));
        if (!create) {
            n.put("expectedVersion", positive(input, "expectedVersion"));
            n.put("targetContentVersion", positive(input, "targetContentVersion"));
            text(input, "contentDigest", 64);
            return n;
        }
        for (String k :
                List.of("evidenceOriginalId", "cashScopeRevisionId", "accountId", "journalId"))
            n.put(k, uuid(input, k).toString());
        for (String k :
                List.of(
                        "sourceDocumentVersion",
                        "expectedScopeVersion",
                        "lineNo",
                        "expectedJournalVersion",
                        "expectedPostingSeq")) n.put(k, positive(input, k));
        if ((long) n.get("lineNo") > 500
                || (long) n.get("sourceDocumentVersion") > Integer.MAX_VALUE)
            throw FinanceException.invalid("来源版本或行号越界");
        for (String k : List.of("sourceMovementKey", "sourceDocumentId"))
            n.put(k, FinanceSources.exact(input, k, 160));
        n.put("sourceRowReference", FinanceSources.exact(input, "sourceRowReference", 120));
        n.put("description", text(input, "description", 1000));
        n.put("occurredDate", FinanceCashScopes.date(input, "occurredDate").toString());
        n.put("amount", money(input));
        if (!(input.get("sourceSystem") instanceof String system)
                || !(input.get("transactionKind") instanceof String kind)
                || !SYSTEMS.containsKey(system)
                || !KINDS.containsKey(kind)
                || !"CNY".equals(input.get("currency"))
                || !Objects.equals(SYSTEMS.get(system), input.get("evidenceKind"))
                || !Objects.equals(KINDS.get(kind), input.get("direction")))
            throw conflict("RULE_VERSION_UNAVAILABLE", "仅开放目录中明确的人工普通人民币现金来源；内部转账、退款及复杂交易尚未支持");
        return n;
    }

    Map<String, Object> duplicate(
            String actor,
            Map<String, Object> b,
            UUID year,
            Map<String, Object> n,
            String business) {
        var rows =
                db.list(
                        "SELECT book_id,year_id,id,business_digest,prepared_by FROM"
                                + " plugin_finance_cash_movement_registration WHERE tenant_id=? AND"
                                + " source_system=? AND source_movement_key=?",
                        b.get("tenant_id"),
                        n.get("sourceSystem"),
                        n.get("sourceMovementKey"));
        if (rows.isEmpty()) return null;
        var old = rows.getFirst();
        if (!b.get("id").equals(old.get("book_id")) || !year.equals(old.get("year_id"))) {
            finance.originalScope(
                    actor, (UUID) old.get("book_id"), (UUID) old.get("year_id"), "create");
            throw conflict("SOURCE_SCOPE_CONFLICT", "来源永久身份已用于其他账套或年度，不能跨范围重投");
        }
        if (!business.equals(old.get("business_digest")))
            throw conflict("SOURCE_PAYLOAD_CONFLICT", "来源永久身份的业务内容不能变更");
        if (!Objects.equals(old.get("prepared_by"), b.get("actor_id")))
            finance.originalScope(actor, (UUID) b.get("id"), year, "read");
        return detail(b, year, (UUID) old.get("id"));
    }

    @Transactional
    public Map<String, Object> write(
            String actor, UUID book, UUID year, UUID id, Map<String, Object> input) {
        boolean create = id == null;
        var b = finance.originalScope(actor, book, year, "create");
        var n = normalized(input, create);
        String key = text(input, "idempotencyKey", 100);
        if (key.length() < 8) throw FinanceException.invalid("幂等键至少8字符");
        UUID user = (UUID) b.get("actor_id");
        db.jdbc
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")
                .param(user + ":" + key)
                .query((rs, i) -> 0)
                .list();
        db.one(
                "SELECT id FROM plugin_finance_book WHERE tenant_id=? AND id=? FOR UPDATE",
                b.get("tenant_id"),
                book);
        b = finance.originalScope(actor, book, year, "create");
        finance.lockYearWrites(b, List.of(year));
        Map<String, Object> old = create ? null : row(b, year, id);
        if (!create && !user.equals(old.get("prepared_by")))
            throw conflict("SCOPE_DENIED", "仅原制备人可作废现金来源");
        LocalDate date =
                LocalDate.parse(
                        (create ? n.get("occurredDate") : old.get("occurred_date")).toString());
        db.update(
                "INSERT INTO plugin_finance_cash_registration_guard(tenant_id,book_id,year_id)"
                        + " VALUES(?,?,?) ON CONFLICT DO NOTHING",
                b.get("tenant_id"),
                book,
                year);
        db.one(
                "SELECT registration_version FROM plugin_finance_cash_registration_guard WHERE"
                        + " tenant_id=? AND book_id=? AND year_id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year);
        scopes.period(b, year, date, true);
        String route = book + "/" + year + "/cashflow/movement-registrations/" + id,
                hash = digest(n);
        var cache =
                db.list(
                        "SELECT route,payload_hash,response FROM plugin_finance_idempotency WHERE"
                                + " actor_id=? AND key=?",
                        user,
                        key);
        if (!cache.isEmpty()) {
            var c = cache.getFirst();
            if (!route.equals(c.get("route")) || !hash.equals(c.get("payload_hash")))
                throw conflict("IDEMPOTENCY_CONFLICT", "请求键已用于不同现金来源命令");
            var prior = finance.decode((String) c.get("response"));
            return detail(b, year, UUID.fromString(prior.get("id").toString()));
        }
        Map<String, Object> result;
        if (create) {
            result = duplicate(actor, b, year, n, hash);
            if (result == null) {
                id = create(b, year, n, hash, user, actor, date);
                result = detail(b, year, id);
            }
        } else {
            if (!"REGISTERED".equals(old.get("status")))
                throw conflict("SOURCE_ALREADY_VOIDED", "来源已永久作废，不支持复活或替代登记");
            if (positive(input, "expectedVersion") != ((Number) old.get("version")).longValue()
                    || positive(input, "targetContentVersion") != 1
                    || !Objects.equals(input.get("contentDigest"), old.get("content_digest")))
                throw conflict("VERSION_CONFLICT", "作废版本或原内容摘要不匹配");
            detail(b, year, id);
            if (!db.list(
                            "SELECT id FROM plugin_finance_cash_claim_usage WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND raw_registration_id=? AND"
                                + " state='ACTIVE'",
                            b.get("tenant_id"),
                            book,
                            year,
                            id)
                    .isEmpty())
                throw conflict("CASH_REGISTRATION_IN_USE", "来源已有实际ACTIVE typed使用，须先独立释放人审");
            if (((Number) old.get("downstream_use_count")).longValue() != 0)
                throw conflict("CASH_REGISTRATION_IN_USE", "现金来源已有下游有效使用，不得作废");
            db.update(
                    "UPDATE plugin_finance_cash_movement_registration SET"
                        + " status='VOIDED',version=version+1,voided_by=?,voided_at=now(),void_reason=?"
                        + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                    user,
                    n.get("reason"),
                    b.get("tenant_id"),
                    book,
                    year,
                    id);
            bump(b, year);
            result = detail(b, year, id);
            finance.originalAudit(
                    user,
                    b,
                    year,
                    "CASH_MOVEMENT_VOID",
                    id,
                    Map.of("before", oldSnapshot(old), "after", result));
        }
        db.update(
                "INSERT INTO plugin_finance_idempotency(actor_id,key,route,payload_hash,response)"
                        + " VALUES(?,?,?,?,?)",
                user,
                key,
                route,
                hash,
                finance.encode(result));
        return result;
    }

    Map<String, Object> oldSnapshot(Map<String, Object> row) {
        return Map.of(
                "id",
                row.get("id"),
                "status",
                row.get("status"),
                "version",
                row.get("version"),
                "contentDigest",
                row.get("content_digest"));
    }

    void bump(Map<String, Object> b, UUID year) {
        db.update(
                "UPDATE plugin_finance_cash_registration_guard SET"
                    + " registration_version=registration_version+1 WHERE tenant_id=? AND book_id=?"
                    + " AND year_id=?",
                b.get("tenant_id"),
                b.get("id"),
                year);
    }

    void checkFileRow(
            String actor, Map<String, Object> b, UUID year, Object sha, Object reference) {
        var rows =
                db.list(
                        "SELECT book_id,year_id,status FROM"
                                + " plugin_finance_cash_movement_registration WHERE tenant_id=? AND"
                                + " source_original_sha256=? AND source_row_reference=?",
                        b.get("tenant_id"),
                        sha,
                        reference);
        if (rows.isEmpty()) return;
        var prior = rows.getFirst();
        if (!b.get("id").equals(prior.get("book_id")) || !year.equals(prior.get("year_id")))
            finance.originalScope(
                    actor, (UUID) prior.get("book_id"), (UUID) prior.get("year_id"), "create");
        throw conflict(
                "VOIDED".equals(prior.get("status"))
                        ? "SOURCE_ALREADY_VOIDED"
                        : "CASH_SOURCE_DUPLICATE_CONFLICT",
                "同一原件全文件行已有永久登记身份；禁止换键重建");
    }

    UUID create(
            Map<String, Object> b,
            UUID year,
            Map<String, Object> n,
            String business,
            UUID user,
            String actor,
            LocalDate date) {
        UUID account = uuid(n, "accountId"),
                scope = uuid(n, "cashScopeRevisionId"),
                journal = uuid(n, "journalId");
        var item = scopeItem(b, year, scope, account, date, true);
        if (((Number) item.get("approvedScopeVersion")).longValue()
                        != positive(n, "expectedScopeVersion")
                || !item.get("scopeContentDigest").equals(n.get("scopeContentDigest"))
                || !instrument((String) n.get("sourceSystem")).equals(item.get("instrumentKind")))
            throw conflict("CASH_MOVEMENT_SCOPE_STALE", "须绑定本月范围自身批准代次、摘要和现金工具");
        var original = scopes.evidence(b, year, uuid(n, "evidenceOriginalId"));
        var metadata =
                db.one(
                        "SELECT media_type,size_bytes FROM plugin_finance_original WHERE"
                                + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        uuid(n, "evidenceOriginalId"));
        var evidence = new LinkedHashMap<>(original);
        evidence.put("mediaType", metadata.get("media_type"));
        evidence.put("sizeBytes", metadata.get("size_bytes"));
        checkFileRow(actor, b, year, original.get("sha256"), n.get("sourceRowReference"));
        db.one(
                "SELECT line_no FROM plugin_finance_line WHERE tenant_id=? AND book_id=? AND"
                        + " year_id=? AND journal_id=? AND line_no=? FOR UPDATE",
                b.get("tenant_id"),
                b.get("id"),
                year,
                journal,
                positive(n, "lineNo"));
        var legs =
                db.list(
                        LEGS + " AND j.id=? AND l.line_no=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        journal,
                        positive(n, "lineNo"));
        if (legs.isEmpty()) throw conflict("CASH_MOVEMENT_MATCH_INVALID", "必须匹配真实已记账现金分录");
        var leg = legs.getFirst();
        if (leg.get("activeRegistrationId") != null)
            throw conflict("CASH_LEG_ALREADY_REGISTERED", "现金分录已有活跃来源登记");
        if (!account.equals(leg.get("accountId"))
                || !date.toString().equals(leg.get("date"))
                || !n.get("direction").equals(leg.get("direction"))
                || new BigDecimal(n.get("amount").toString())
                                .compareTo(new BigDecimal(leg.get("normalizedAmount").toString()))
                        != 0
                || positive(n, "expectedJournalVersion")
                        != ((Number) leg.get("journalVersion")).longValue()
                || positive(n, "expectedPostingSeq")
                        != ((Number) leg.get("postingSeq")).longValue())
            throw conflict("CASH_MOVEMENT_MATCH_INVALID", "日期、方向、全额、科目、凭证版本或过账水位不匹配");
        var snapshot = new LinkedHashMap<>(leg);
        snapshot.remove("activeRegistrationId");
        snapshot.remove("activeClaimId");
        snapshot.remove("activeClaimKind");
        snapshot.remove("activeCorrectionId");
        var c = new LinkedHashMap<String, Object>(n);
        c.remove("amount");
        c.put("normalizedAmount", n.get("amount"));
        c.put("tenantId", b.get("tenant_id").toString());
        c.put("bookId", b.get("id").toString());
        c.put("yearId", year.toString());
        c.put("legalEntityId", b.get("legal_entity_id").toString());
        c.put("originalSnapshot", evidence);
        c.put("scopeItemSnapshot", item);
        c.put("accountSnapshot", item.get("accountSnapshot"));
        c.put("matchedLegSnapshot", snapshot);
        c.put("preparedBy", user.toString());
        String contentDigest = digest(c);
        UUID id = UUID.randomUUID();
        var inserted =
                db.list(
                        "INSERT INTO"
                            + " plugin_finance_cash_movement_registration(tenant_id,book_id,year_id,id,legal_entity_id,source_system,source_movement_key,source_document_id,source_document_version,source_row_reference,evidence_kind,source_original_id,source_original_sha256,cash_scope_revision_id,approved_scope_version,scope_content_digest,account_id,occurred_date,direction,amount,transaction_kind,journal_id,line_no,matched_journal_version,posting_seq,posting_digest,business_digest,content_digest,content,prepared_by,prepared_username)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)"
                            + " ON CONFLICT DO NOTHING RETURNING id",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        id,
                        b.get("legal_entity_id"),
                        n.get("sourceSystem"),
                        n.get("sourceMovementKey"),
                        n.get("sourceDocumentId"),
                        n.get("sourceDocumentVersion"),
                        n.get("sourceRowReference"),
                        n.get("evidenceKind"),
                        uuid(n, "evidenceOriginalId"),
                        original.get("sha256"),
                        scope,
                        n.get("expectedScopeVersion"),
                        n.get("scopeContentDigest"),
                        account,
                        date,
                        n.get("direction"),
                        new BigDecimal(n.get("amount").toString()),
                        n.get("transactionKind"),
                        journal,
                        n.get("lineNo"),
                        n.get("expectedJournalVersion"),
                        n.get("expectedPostingSeq"),
                        leg.get("postingDigest"),
                        business,
                        contentDigest,
                        finance.encode(c),
                        user,
                        actor);
        if (inserted.isEmpty()) {
            var same = duplicate(actor, b, year, n, business);
            if (same != null) return UUID.fromString(same.get("id").toString());
            checkFileRow(actor, b, year, original.get("sha256"), n.get("sourceRowReference"));
            throw conflict("CASH_LEG_ALREADY_REGISTERED", "现金腿被并发登记，不能重复");
        }
        bump(b, year);
        finance.originalAudit(user, b, year, "CASH_MOVEMENT_REGISTER", id, detail(b, year, id));
        return id;
    }
}
