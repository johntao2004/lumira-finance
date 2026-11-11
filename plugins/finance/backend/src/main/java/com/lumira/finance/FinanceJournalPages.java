package com.lumira.finance;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuthorizationContextProjectionPort;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bounded keyset reads. No financial aggregation, snapshot consumer or legacy-array changes. */
@Service
public class FinanceJournalPages {
    static final String SCHEMA = "journal-ledger-pages/1";
    static final long MAX_SAFE = 9007199254740991L;
    static final Set<String> STATUS =
            Set.of("DRAFT", "SUBMITTED", "APPROVED", "POSTED", "CANCELLED");
    static final Set<String> TYPES =
            Set.of(
                    "NORMAL",
                    "REVERSAL",
                    "PROFIT_CLOSE",
                    "YEAR_END",
                    "AP_EXPENSE",
                    "AP_EXPENSE_REVERSAL",
                    "AR_REVENUE",
                    "AR_RECEIPT",
                    "AR_ALLOCATION",
                    "AR_REVERSAL",
                    "ASSET_ACQUIRE",
                    "ASSET_DEPRECIATE",
                    "ASSET_DISPOSE",
                    "ASSET_REVERSE",
                    "ASSET_CORRECT_DEPRECIATION");
    static final Set<String> CURSOR_KEYS =
            Set.of(
                    "schemaVersion",
                    "resource",
                    "actorId",
                    "authorizationContextDigest",
                    "tenantId",
                    "bookId",
                    "yearId",
                    "readEpoch",
                    "journalGeneration",
                    "ledgerWatermarkDigest",
                    "filterDigest",
                    "contextDigest",
                    "limit",
                    "direction",
                    "anchorDate",
                    "anchorJournalId",
                    "anchorLineNo");
    static final List<String> LIMITATIONS =
            List.of(
                    "有界只读页，totalCount未统计；不能从本页条数/金额推算全账总量或余额",
                    "技术generation仅V11之后的列表变动基线，不是凭证数或历史postingSeq",
                    "同范围水位/授权变化须整组重取；未持有跨请求固定快照",
                    "literal ILIKE受3秒语句/10秒读取预算，超预算不是零结果；无百万生产容量认证",
                    "旧array接口仍兼容其200条界限；此分页不替代完整导出、试算、结账或正式报表");
    final FinanceStore db;
    final FinanceService finance;
    final AdministrationPort guard;
    final AuthorizationContextProjectionPort authorization;
    final PlatformTransactionManager transactions;

    public FinanceJournalPages(
            FinanceStore db,
            FinanceService finance,
            AdministrationPort guard,
            AuthorizationContextProjectionPort authorization,
            PlatformTransactionManager transactions) {
        this.db = db;
        this.finance = finance;
        this.guard = guard;
        this.authorization = authorization;
        this.transactions = transactions;
    }

    static FinanceException error(String code, String message) {
        return new FinanceException(code, message);
    }

    static FinanceException unsupported() {
        return error("LIST_VALUE_UNSUPPORTED", "当前页存在不可安全表示的版本/编号、金额或格式，未删行或填零");
    }

    static FinanceException exceeded() {
        return error("QUERY_BUDGET_EXCEEDED", "查询已取消或读取预算到期，未完成此页；请缩小日期/筛选后显式重取");
    }

    static <T> T boundary(Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException failure) {
            for (Throwable c = failure; c != null; c = c.getCause())
                if (c instanceof QueryTimeoutException
                        || c instanceof TransactionTimedOutException
                        || c instanceof java.sql.SQLTimeoutException
                        || c instanceof SQLException sql && "57014".equals(sql.getSQLState()))
                    throw exceeded();
            throw failure;
        }
    }

    static Map<String, Object> map(Object... p) {
        return FinanceCashLedgerCoverage.map(p);
    }

    String hash(Object v) {
        return FinanceService.hash(finance.encode(finance.canonical(v)));
    }

    static UUID uuid(Object v) {
        return FinanceCashLedgerCoverage.uuid(v);
    }

    static String id(Object v) {
        return v == null ? null : v.toString();
    }

    static String counter(Object v) {
        if (!(v instanceof String s) || !s.matches("(?:0|[1-9][0-9]{0,18})")) throw unsupported();
        try {
            if (Long.parseLong(s) < 0) throw unsupported();
        } catch (NumberFormatException e) {
            throw unsupported();
        }
        return s;
    }

    static long safe(Object v, long maximum) {
        if (!(v instanceof Integer || v instanceof Long)
                || ((Number) v).longValue() < 1
                || ((Number) v).longValue() > maximum) throw unsupported();
        return ((Number) v).longValue();
    }

    static String string(Object v, int max) {
        if (!(v instanceof String s) || s.codePointCount(0, s.length()) > max || s.indexOf(0) >= 0)
            throw unsupported();
        return s;
    }

    static String accountString(Object value, int maximum) {
        String s = string(value, maximum);
        if (s.isEmpty()) throw unsupported();
        return s;
    }

    static String amount(Object v, String format) {
        try {
            if (!(v instanceof String s) || !s.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))
                throw unsupported();
            var d = new BigDecimal(s);
            if (d.signum() < 0 || d.precision() - d.scale() > 20) throw unsupported();
            int scale =
                    switch (format) {
                        case "LEGACY_6" -> 6;
                        case "V4_8" -> 8;
                        default -> throw unsupported();
                    };
            return d.setScale(scale, java.math.RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException bad) {
            throw unsupported();
        }
    }

    static LocalDate date(Object value) {
        try {
            if (!(value instanceof String s) || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw new IllegalArgumentException();
            var d = LocalDate.parse(s);
            if (!d.toString().equals(s)) throw new IllegalArgumentException();
            return d;
        } catch (RuntimeException invalid) {
            throw FinanceException.invalid("日期须为真实YYYY-MM-DD");
        }
    }

    static String literal(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.length() > 100 || value.indexOf(0) >= 0)
            throw FinanceException.invalid("keyword超过100 UTF16单位或不能表示");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i)))
                    throw FinanceException.invalid("keyword不能表示");
            } else if (Character.isLowSurrogate(c)) throw FinanceException.invalid("keyword不能表示");
        }
        return value;
    }

    static String pattern(String keyword) {
        return "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    record Query(
            String resource,
            UUID account,
            String status,
            LocalDate from,
            LocalDate to,
            String keyword,
            int limit,
            String cursor) {}

    static Query query(String resource, MultiValueMap<String, String> input) {
        boolean ledger = resource.equals("ledger");
        if (!ledger && !resource.equals("journals")) throw FinanceException.invalid("未知分页资源");
        var allowed =
                ledger
                        ? Set.of("accountId", "dateFrom", "dateTo", "limit", "cursor")
                        : Set.of("status", "dateFrom", "dateTo", "keyword", "limit", "cursor");
        if (!allowed.containsAll(input.keySet())
                || input.values().stream()
                        .anyMatch(v -> v == null || v.size() != 1 || v.getFirst() == null)
                || ledger && !input.containsKey("accountId"))
            throw FinanceException.invalid("分页query字段缺失、重复或未知");
        for (var e : input.entrySet())
            if (!e.getKey().equals("keyword") && e.getValue().getFirst().isEmpty())
                throw FinanceException.invalid("非keyword可选字段应省略而非空串");
        int limit = 20;
        if (input.containsKey("limit")) {
            String s = input.getFirst("limit");
            if (!s.matches("[1-9][0-9]{0,2}") || (limit = Integer.parseInt(s)) > 100)
                throw FinanceException.invalid("limit须为1至100");
        }
        String status = input.getFirst("status");
        if (status != null && !STATUS.contains(status)) throw FinanceException.invalid("状态无效");
        String keyword = literal(input.getFirst("keyword"));
        if (input.containsKey("dateFrom") != input.containsKey("dateTo")
                || keyword != null && !input.containsKey("dateFrom"))
            throw FinanceException.invalid("日期成对提供，非空keyword须显式给同年日期范围");
        LocalDate from = input.containsKey("dateFrom") ? date(input.getFirst("dateFrom")) : null,
                to = input.containsKey("dateTo") ? date(input.getFirst("dateTo")) : null;
        if (from != null && from.isAfter(to)) throw FinanceException.invalid("起止日期顺序无效");
        String cursor = input.getFirst("cursor");
        if (cursor != null && cursor.length() > 4000) throw FinanceException.invalid("cursor过长");
        return new Query(
                resource,
                ledger ? uuid(input.getFirst("accountId")) : null,
                status,
                from,
                to,
                keyword,
                limit,
                cursor);
    }

    class Budget {
        final LongSupplier clock;
        final long start;

        Budget(LongSupplier clock) {
            this.clock = clock;
            this.start = clock.getAsLong();
        }

        long remaining() {
            long value = 10000000000L - (clock.getAsLong() - start);
            if (value < 1000000L) throw exceeded();
            return value;
        }

        void check() {
            remaining();
        }

        void configure() {
            long millis = Math.min(3000, remaining() / 1000000L);
            db.list("SELECT set_config('statement_timeout',?,true) value", millis + "ms");
            check();
        }

        List<Map<String, Object>> rows(String sql, Object... args) {
            configure();
            var result = db.list(sql, args);
            check();
            return result;
        }

        Map<String, Object> one(String sql, Object... args) {
            var rows = rows(sql, args);
            if (rows.isEmpty()) throw error("SCOPE_DENIED", "范围不可访问");
            return rows.getFirst();
        }
    }

    String authorization(String actor, Map<String, Object> b, Budget budget) {
        budget.configure();
        Set<String> held = guard.require(actor, "platform:finance:read");
        guard.require(actor, "platform:finance:access");
        var snap =
                authorization
                        .current(actor)
                        .orElseThrow(() -> error("SCOPE_DENIED", "缺少服务器验证的授权上下文"));
        budget.check();
        if (!snap.effectiveAuthorities().equals(held)
                || !held.containsAll(Set.of("platform:finance:access", "platform:finance:read")))
            throw error("SCOPE_DENIED", "当前授权上下文不一致");
        var member =
                budget.one(
                        "SELECT user_id,role,enabled,year_scope FROM plugin_finance_member WHERE"
                                + " tenant_id=? AND book_id=? AND user_id=? AND enabled",
                        b.get("tenant_id"),
                        b.get("id"),
                        b.get("actor_id"));
        var years =
                budget.rows(
                        "SELECT year_id FROM plugin_finance_member_year WHERE tenant_id=? AND"
                                + " book_id=? AND user_id=? ORDER BY year_id",
                        b.get("tenant_id"),
                        b.get("id"),
                        b.get("actor_id"));
        return hash(
                map(
                        "actorId",
                        b.get("actor_id"),
                        "member",
                        member,
                        "yearIds",
                        years,
                        "effective",
                        new TreeSet<>(snap.effectiveAuthorities()),
                        "global",
                        new TreeSet<>(snap.effectiveGlobalAuthorities()),
                        "previewActive",
                        snap.previewActive(),
                        "targetRoleId",
                        snap.targetRoleId(),
                        "targetRoleVersion",
                        snap.targetRoleVersion()));
    }

    public Map<String, Object> page(
            String actor,
            UUID book,
            UUID year,
            String resource,
            MultiValueMap<String, String> input) {
        var budget = new Budget(System::nanoTime);
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
        return boundary(
                () -> {
                    var result =
                            tx.execute(
                                    status ->
                                            read(
                                                    actor,
                                                    book,
                                                    year,
                                                    query(resource, input),
                                                    budget));
                    budget.check();
                    return result;
                });
    }

    static final String JOURNAL_FIELDS =
            "j.id,j.date,j.summary,j.type,j.status,j.version,j.created_by AS"
                    + " \"createdBy\",j.approved_by AS \"approvedBy\",j.number,j.period_id AS"
                    + " \"periodId\"";
    static final String LEDGER_FIELDS =
            "j.id AS \"journalId\",l.line_no AS \"lineNo\",l.account_id AS"
                + " \"accountId\",j.date,j.number,j.summary,l.debit::text debit,l.credit::text"
                + " credit,j.amount_format_version AS \"amountFormatVersion\",p.posting_seq::text"
                + " AS \"postingSeq\",p.digest AS \"postingDigest\",j.version AS"
                + " \"journalVersion\"";

    Map<String, Object> read(String actor, UUID book, UUID year, Query q, Budget budget) {
        budget.configure();
        var b = finance.originalScope(actor, book, year, "read");
        budget.check();
        String auth = authorization(actor, b, budget);
        var yr =
                budget.one(
                        "SELECT year,status,precision_profile,precision_review_required FROM"
                                + " plugin_finance_year WHERE tenant_id=? AND book_id=? AND id=?",
                        b.get("tenant_id"),
                        book,
                        year);
        int yearNumber = ((Number) yr.get("year")).intValue();
        var from = q.from() == null ? LocalDate.of(yearNumber, 1, 1) : q.from();
        var to = q.to() == null ? LocalDate.of(yearNumber, 12, 31) : q.to();
        if (from.getYear() != yearNumber || to.getYear() != yearNumber)
            throw FinanceException.invalid("日期必须属于路径年度");
        var periods =
                budget.rows(
                        "SELECT month,status,version FROM plugin_finance_period WHERE tenant_id=?"
                                + " AND book_id=? AND year_id=? AND month BETWEEN ? AND ? ORDER BY"
                                + " month",
                        b.get("tenant_id"),
                        book,
                        year,
                        from.getMonthValue(),
                        to.getMonthValue());
        var scope =
                map(
                        "tenantId",
                        id(b.get("tenant_id")),
                        "legalEntityId",
                        id(b.get("legal_entity_id")),
                        "bookId",
                        book.toString(),
                        "yearId",
                        year.toString(),
                        "precisionProfile",
                        yr.get("precision_profile"),
                        "precisionReviewRequired",
                        yr.get("precision_review_required"),
                        "yearStatus",
                        yr.get("status"),
                        "periodStateDigest",
                        hash(periods),
                        "dateFrom",
                        from.toString(),
                        "dateTo",
                        to.toString());
        if (budget.one("SELECT to_regclass('plugin_finance_journal_list_guard')::text name")
                        .get("name")
                == null) throw error("LIST_BASELINE_UNAVAILABLE", "列表技术基线尚不可用，不能把缺失当零");
        boolean ledger = q.resource().equals("ledger");
        var guards =
                budget.rows(
                        "SELECT read_epoch"
                                + (ledger ? "" : ",generation::text generation")
                                + " FROM plugin_finance_journal_list_guard WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=?",
                        b.get("tenant_id"),
                        book,
                        year);
        if (guards.isEmpty()) throw error("LIST_BASELINE_UNAVAILABLE", "本年度缺少技术读基线，不能伪造零代次");
        String epoch = id(guards.getFirst().get("read_epoch"));
        Map<String, Object> asOf;
        String ledgerDigest = null;
        if (!ledger)
            asOf =
                    map(
                            "readEpoch",
                            epoch,
                            "generation",
                            counter(guards.getFirst().get("generation")),
                            "authorizationContextDigest",
                            auth);
        else {
            var account =
                    budget.one(
                            "SELECT"
                                + " id,code,name,enabled,category,normal_side,purpose,cash_flow_category"
                                + " FROM plugin_finance_account WHERE tenant_id=? AND book_id=? AND"
                                + " year_id=? AND id=?",
                            b.get("tenant_id"),
                            book,
                            year,
                            q.account());
            String accountDigest = hash(account);
            scope.putAll(
                    map(
                            "accountId",
                            q.account().toString(),
                            "accountCode",
                            accountString(account.get("code"), 100),
                            "accountName",
                            accountString(account.get("name"), 200),
                            "accountEnabled",
                            account.get("enabled"),
                            "accountDigest",
                            accountDigest));
            var mark =
                    budget.one(
                            "SELECT posting_seq::text AS \"postingSeq\",opening_version::text AS"
                                    + " \"openingVersion\",baseline_version::text AS"
                                    + " \"baselineVersion\",source_close_version::text AS"
                                    + " \"sourceCloseVersion\" FROM plugin_finance_year_guard WHERE"
                                    + " tenant_id=? AND book_id=? AND year_id=?",
                            b.get("tenant_id"),
                            book,
                            year);
            for (String key :
                    List.of(
                            "postingSeq",
                            "openingVersion",
                            "baselineVersion",
                            "sourceCloseVersion")) counter(mark.get(key));
            mark.put(
                    "legacyUnsequencedFacts",
                    Boolean.TRUE.equals(yr.get("precision_review_required")));
            asOf =
                    map(
                            "readEpoch",
                            epoch,
                            "year",
                            mark,
                            "accountDigest",
                            accountDigest,
                            "authorizationContextDigest",
                            auth);
            ledgerDigest = hash(map("scope", scope, "year", mark, "accountDigest", accountDigest));
        }
        if (q.keyword() != null
                && !Boolean.TRUE.equals(
                        budget.one(
                                        "SELECT c.collisdeterministic deterministic FROM"
                                            + " pg_attribute a JOIN pg_collation c ON"
                                            + " c.oid=a.attcollation WHERE"
                                            + " a.attrelid='plugin_finance_journal'::regclass AND"
                                            + " a.attname='summary' AND NOT a.attisdropped")
                                .get("deterministic")))
            throw error("LIST_BASELINE_UNAVAILABLE", "当前摘要collation不支持既定ILIKE语义");
        String filterDigest =
                hash(
                        map(
                                "resource",
                                q.resource(),
                                "accountId",
                                q.account(),
                                "status",
                                q.status(),
                                "dateFrom",
                                from.toString(),
                                "dateTo",
                                to.toString(),
                                "keyword",
                                q.keyword(),
                                "limit",
                                q.limit()));
        String context =
                hash(
                        map(
                                "schemaVersion",
                                SCHEMA,
                                "resource",
                                q.resource(),
                                "scope",
                                scope,
                                "filterDigest",
                                filterDigest,
                                "asOf",
                                asOf));
        var params = new ArrayList<Object>(List.of(b.get("tenant_id"), book, year, from, to));
        String base =
                " FROM plugin_finance_journal j"
                        + (ledger
                                ? " JOIN plugin_finance_line l"
                                      + " ON(l.tenant_id,l.book_id,l.year_id,l.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id)"
                                      + " LEFT JOIN plugin_finance_posting p"
                                      + " ON(p.tenant_id,p.book_id,p.year_id,p.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id)"
                                : "")
                        + " WHERE j.tenant_id=? AND j.book_id=? AND j.year_id=? AND j.date BETWEEN"
                        + " ? AND ?";
        if (ledger) {
            base += " AND j.status='POSTED' AND l.account_id=?";
            params.add(q.account());
        } else {
            if (q.status() != null) {
                base += " AND j.status=?";
                params.add(q.status());
            }
            if (q.keyword() != null) {
                base += " AND j.summary ILIKE ? ESCAPE E'\\\\'";
                params.add(pattern(q.keyword()));
            }
        }
        String tuple = ledger ? "(j.date,j.id,l.line_no)" : "(j.date,j.id)",
                order = ledger ? "j.date,j.id,l.line_no" : "j.date,j.id";
        Map<String, Object> cursor = null;
        String seek = "";
        List<Object> pageParams = new ArrayList<>(params);
        boolean reverse = false;
        if (q.cursor() != null) {
            cursor = cursor(q.cursor());
            for (String key :
                    List.of(
                            "resource",
                            "actorId",
                            "tenantId",
                            "bookId",
                            "yearId",
                            "filterDigest",
                            "limit")) {
                Object expected =
                        switch (key) {
                            case "resource" -> q.resource();
                            case "actorId" -> id(b.get("actor_id"));
                            case "tenantId" -> id(b.get("tenant_id"));
                            case "bookId" -> book.toString();
                            case "yearId" -> year.toString();
                            case "filterDigest" -> filterDigest;
                            default -> q.limit();
                        };
                if (!expected.equals(cursor.get(key)))
                    throw FinanceException.invalid("游标账号、范围、资源或筛选不匹配");
            }
            if (!epoch.equals(cursor.get("readEpoch"))
                    || !auth.equals(cursor.get("authorizationContextDigest"))
                    || !context.equals(cursor.get("contextDigest"))
                    || !Objects.equals(
                            ledger ? null : asOf.get("generation"), cursor.get("journalGeneration"))
                    || !Objects.equals(ledgerDigest, cursor.get("ledgerWatermarkDigest")))
                throw error("LIST_PAGE_STALE", "列表或授权上下文已变，需丢弃旧页并显式重新读取首屏");
            var anchorParams = anchor(params, cursor, ledger);
            String equality = ledger ? "(?,?,?)" : "(?,?)";
            if (budget.rows(
                            "SELECT 1 value" + base + " AND " + tuple + "=" + equality + " LIMIT 1",
                            anchorParams.toArray())
                    .isEmpty()) throw FinanceException.invalid("游标锚点不在当前筛选范围");
            reverse = "PREVIOUS".equals(cursor.get("direction"));
            seek = " AND " + tuple + (reverse ? "<" : ">") + equality;
            pageParams = anchor(params, cursor, ledger);
        }
        var raw =
                budget.rows(
                        "SELECT "
                                + (ledger ? LEDGER_FIELDS : JOURNAL_FIELDS)
                                + base
                                + seek
                                + " ORDER BY "
                                + order.replace(",", reverse ? " DESC," : " ASC,")
                                + (reverse ? " DESC" : " ASC")
                                + " LIMIT ?",
                        append(pageParams, q.limit() + 1));
        if (cursor != null && raw.isEmpty()) throw FinanceException.invalid("游标不指向一个可读取页");
        if (raw.size() > q.limit()) raw = new ArrayList<>(raw.subList(0, q.limit()));
        else raw = new ArrayList<>(raw);
        if (reverse) Collections.reverse(raw);
        var rows = new ArrayList<Map<String, Object>>();
        for (var r : raw) rows.add(ledger ? ledger(r) : journal(r));
        boolean hasNext = false, hasPrevious = false;
        String next = null, previous = null;
        if (!rows.isEmpty()) {
            var first = rows.getFirst();
            var last = rows.getLast();
            String place = ledger ? "(?,?,?)" : "(?,?)";
            var firstAnchor = rowAnchor(first, ledger);
            var lastAnchor = rowAnchor(last, ledger);
            hasPrevious =
                    !budget.rows(
                                    "SELECT 1 value"
                                            + base
                                            + " AND "
                                            + tuple
                                            + "<"
                                            + place
                                            + " LIMIT 1",
                                    anchor(params, firstAnchor, ledger).toArray())
                            .isEmpty();
            hasNext =
                    !budget.rows(
                                    "SELECT 1 value"
                                            + base
                                            + " AND "
                                            + tuple
                                            + ">"
                                            + place
                                            + " LIMIT 1",
                                    anchor(params, lastAnchor, ledger).toArray())
                            .isEmpty();
            if (hasNext)
                next =
                        token(
                                b,
                                year,
                                q,
                                asOf,
                                filterDigest,
                                context,
                                ledgerDigest,
                                lastAnchor,
                                "NEXT");
            if (hasPrevious)
                previous =
                        token(
                                b,
                                year,
                                q,
                                asOf,
                                filterDigest,
                                context,
                                ledgerDigest,
                                firstAnchor,
                                "PREVIOUS");
        }
        budget.check();
        return map(
                "schemaVersion",
                SCHEMA,
                "resource",
                q.resource(),
                "scope",
                scope,
                "asOf",
                asOf,
                "contextDigest",
                context,
                "items",
                rows,
                "returnedCount",
                rows.size(),
                "nextCursor",
                next,
                "previousCursor",
                previous,
                "hasNext",
                hasNext,
                "hasPrevious",
                hasPrevious,
                "totalCount",
                null,
                "totalStatus",
                "NOT_COMPUTED",
                "limitations",
                LIMITATIONS);
    }

    static Object[] append(List<Object> params, Object last) {
        var p = new ArrayList<>(params);
        p.add(last);
        return p.toArray();
    }

    static List<Object> anchor(List<Object> base, Map<String, Object> a, boolean ledger) {
        var p = new ArrayList<>(base);
        p.add(date(a.get("anchorDate")));
        p.add(uuid(a.get("anchorJournalId")));
        if (ledger) p.add(((Number) a.get("anchorLineNo")).intValue());
        return p;
    }

    static Map<String, Object> rowAnchor(Map<String, Object> r, boolean ledger) {
        return map(
                "anchorDate",
                r.get("date"),
                "anchorJournalId",
                r.get(ledger ? "journalId" : "id"),
                "anchorLineNo",
                ledger ? r.get("lineNo") : null);
    }

    String token(
            Map<String, Object> b,
            UUID year,
            Query q,
            Map<String, Object> asOf,
            String filter,
            String context,
            String ledgerDigest,
            Map<String, Object> anchor,
            String direction) {
        var c =
                map(
                        "schemaVersion",
                        SCHEMA,
                        "resource",
                        q.resource(),
                        "actorId",
                        id(b.get("actor_id")),
                        "authorizationContextDigest",
                        asOf.get("authorizationContextDigest"),
                        "tenantId",
                        id(b.get("tenant_id")),
                        "bookId",
                        id(b.get("id")),
                        "yearId",
                        year.toString(),
                        "readEpoch",
                        asOf.get("readEpoch"),
                        "journalGeneration",
                        asOf.get("generation"),
                        "ledgerWatermarkDigest",
                        ledgerDigest,
                        "filterDigest",
                        filter,
                        "contextDigest",
                        context,
                        "limit",
                        q.limit(),
                        "direction",
                        direction);
        c.putAll(anchor);
        String token =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                finance.encode(finance.canonical(c))
                                        .getBytes(StandardCharsets.UTF_8));
        if (token.length() > 4000) throw unsupported();
        return token;
    }

    Map<String, Object> cursor(String text) {
        try {
            if (text.isEmpty() || text.length() > 4000 || !text.matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(text);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(text))
                throw new IllegalArgumentException();
            var c = finance.decode(new String(bytes, StandardCharsets.UTF_8));
            if (!c.keySet().equals(CURSOR_KEYS)
                    || !SCHEMA.equals(c.get("schemaVersion"))
                    || !Set.of("journals", "ledger").contains(c.get("resource"))
                    || !Set.of("NEXT", "PREVIOUS").contains(c.get("direction")))
                throw new IllegalArgumentException();
            for (String k :
                    List.of(
                            "actorId",
                            "tenantId",
                            "bookId",
                            "yearId",
                            "readEpoch",
                            "anchorJournalId")) uuid(c.get(k));
            for (String k : List.of("authorizationContextDigest", "filterDigest", "contextDigest"))
                if (!(c.get(k) instanceof String s) || !s.matches("[a-f0-9]{64}"))
                    throw new IllegalArgumentException();
            date(c.get("anchorDate"));
            safe(c.get("limit"), 100);
            if (c.get("resource").equals("journals")) {
                counter(c.get("journalGeneration"));
                if (c.get("ledgerWatermarkDigest") != null || c.get("anchorLineNo") != null)
                    throw new IllegalArgumentException();
            } else {
                if (c.get("journalGeneration") != null
                        || !(c.get("ledgerWatermarkDigest") instanceof String s)
                        || !s.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
                safe(c.get("anchorLineNo"), 500);
            }
            return c;
        } catch (RuntimeException bad) {
            throw FinanceException.invalid("分页游标类型、格式或资源分支无效");
        }
    }

    static String rowDate(Object value) {
        try {
            return date(value).toString();
        } catch (FinanceException invalid) {
            throw unsupported();
        }
    }

    static Map<String, Object> journal(Map<String, Object> r) {
        if (r.get("type") == null
                || r.get("status") == null
                || !TYPES.contains(r.get("type"))
                || !STATUS.contains(r.get("status"))) throw unsupported();
        return map(
                "id",
                id(r.get("id")),
                "date",
                rowDate(r.get("date")),
                "summary",
                string(r.get("summary"), 2000),
                "type",
                r.get("type"),
                "status",
                r.get("status"),
                "version",
                safe(r.get("version"), MAX_SAFE),
                "createdBy",
                id(r.get("createdBy")),
                "approvedBy",
                id(r.get("approvedBy")),
                "number",
                r.get("number") == null ? null : safe(r.get("number"), MAX_SAFE),
                "periodId",
                id(r.get("periodId")));
    }

    static Map<String, Object> ledger(Map<String, Object> r) {
        String format = id(r.get("amountFormatVersion"));
        if (!"LEGACY_6".equals(format) && !"V4_8".equals(format)) throw unsupported();
        if (r.get("postingDigest") != null
                && (!(r.get("postingDigest") instanceof String d) || !d.matches("[a-f0-9]{64}")))
            throw unsupported();
        return map(
                "journalId",
                id(r.get("journalId")),
                "lineNo",
                safe(r.get("lineNo"), 500),
                "accountId",
                id(r.get("accountId")),
                "date",
                rowDate(r.get("date")),
                "number",
                r.get("number") == null ? null : safe(r.get("number"), MAX_SAFE),
                "summary",
                string(r.get("summary"), 2000),
                "debit",
                amount(r.get("debit"), format),
                "credit",
                amount(r.get("credit"), format),
                "amountFormatVersion",
                format,
                "postingSeq",
                r.get("postingSeq") == null ? null : counter(r.get("postingSeq")),
                "postingDigest",
                r.get("postingDigest"),
                "journalVersion",
                safe(r.get("journalVersion"), MAX_SAFE));
    }
}
