package com.lumira.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Immutable, independently reviewed match-only corrections. Source assertions stay VOIDED. */
@Service
public class FinanceCashMatchCorrections {
    static final String SCHEMA = "cash-match-correction/1";
    private final FinanceStore db;
    private final FinanceService finance;
    private final FinanceCashMovements movements;
    private final FinanceCashScopes scopes;

    public FinanceCashMatchCorrections(
            FinanceStore db,
            FinanceService finance,
            FinanceCashMovements movements,
            FinanceCashScopes scopes) {
        this.db = db;
        this.finance = finance;
        this.movements = movements;
        this.scopes = scopes;
    }

    static final List<String> LIMITATIONS =
            List.of(
                    "仅更正原来源的现金腿匹配；Raw永久VOIDED，不更改来源事实",
                    "来源未经银行验真；不代表覆盖、分配或现金流报表贡献",
                    "独立性按账号UUID；同自然人双账号尚未解决",
                    "无下游使用创建端点；真实typed消费协议须另行实现后才能开放",
                    "原制备人离职或跨已关闭期间处理尚不支持");

    static FinanceException fail(String code, String message) {
        return new FinanceException(code, message);
    }

    String digest(Object v) {
        return scopes.digest(v);
    }

    static long number(Map<String, Object> in, String key, boolean zero) {
        Object v = in.get(key);
        try {
            if (!(v instanceof Integer || v instanceof Long)) throw new IllegalArgumentException();
            long n = Long.parseLong(v.toString());
            if (n < (zero ? 0 : 1)) throw new IllegalArgumentException();
            return n;
        } catch (IllegalArgumentException bad) {
            throw FinanceException.invalid(key + "整数无效");
        }
    }

    static UUID uuid(Map<String, Object> in, String key) {
        return FinanceService.id(in, key);
    }

    static String text(Map<String, Object> in, String key, int max) {
        return FinanceCashScopes.text(in, key, max);
    }

    static final String CORRECTION =
            "SELECT id,raw_registration_id AS \"originalRegistrationId\",raw_version_at_proposal AS"
                + " \"rawVersionAtProposal\",raw_content_digest AS"
                + " \"rawContentDigest\",status,version,content_version AS"
                + " \"contentVersion\",content_digest AS"
                + " \"contentDigest\",expected_match_generation AS"
                + " \"expectedSourceMatchGeneration\",prepared_by AS \"preparedBy\",last_edited_by"
                + " AS \"lastEditedBy\",created_at AS \"createdAt\",submitted_at AS"
                + " \"submittedAt\",approved_by AS \"approvedBy\",approved_at AS"
                + " \"approvedAt\",approved_claim_id AS"
                + " \"approvedClaimId\",approved_match_generation AS"
                + " \"approvedMatchGeneration\",decision_by AS \"decisionBy\",decision_at AS"
                + " \"decisionAt\",decision_reason AS \"decisionReason\",withdrawn_at AS"
                + " \"withdrawnAt\",withdrawn_by AS \"withdrawnBy\",target_journal_id AS"
                + " \"targetJournalId\",target_line_no AS \"targetLineNo\",target_journal_version"
                + " AS \"targetJournalVersion\",target_posting_seq AS \"targetPostingSeq\" FROM"
                + " plugin_finance_cash_match_correction WHERE tenant_id=? AND book_id=? AND"
                + " year_id=?";
    static final String WITHDRAWAL =
            "SELECT id,correction_id AS \"correctionId\",raw_registration_id AS"
                    + " \"originalRegistrationId\",active_claim_id AS"
                    + " \"activeClaimId\",expected_correction_version AS"
                    + " \"expectedCorrectionVersion\",expected_source_generation AS"
                    + " \"expectedSourceMatchGeneration\",status,version,content_digest AS"
                    + " \"contentDigest\",reason,requested_by AS \"requestedBy\",requested_at AS"
                    + " \"requestedAt\",reviewed_by AS \"reviewedBy\",reviewed_at AS"
                    + " \"reviewedAt\",decision_reason AS \"decisionReason\" FROM"
                    + " plugin_finance_cash_match_withdrawal WHERE tenant_id=? AND book_id=? AND"
                    + " year_id=?";

    Map<String, Object> correction(Map<String, Object> b, UUID year, UUID id) {
        return db.one(
                "SELECT *,content::text AS content_text FROM plugin_finance_cash_match_correction"
                        + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                b.get("tenant_id"),
                b.get("id"),
                year,
                id);
    }

    Map<String, Object> withdrawal(Map<String, Object> b, UUID year, UUID correction, UUID id) {
        return db.one(
                WITHDRAWAL + " AND correction_id=? AND id=?",
                b.get("tenant_id"),
                b.get("id"),
                year,
                correction,
                id);
    }

    Map<String, Object> active(Map<String, Object> b, UUID year, UUID raw) {
        var rows =
                db.list(
                        "SELECT id,claim_kind AS kind,raw_registration_id AS"
                            + " \"rawRegistrationId\",correction_id AS"
                            + " \"correctionId\",claim_generation AS generation,journal_id AS"
                            + " \"journalId\",line_no AS \"lineNo\",account_id AS"
                            + " \"accountId\",journal_version AS \"journalVersion\",posting_seq AS"
                            + " \"postingSeq\",posting_digest AS"
                            + " \"postingDigest\",claim_content_digest AS"
                            + " \"contentDigest\",state,created_at AS \"createdAt\" FROM"
                            + " plugin_finance_cash_leg_claim WHERE tenant_id=? AND book_id=? AND"
                            + " year_id=? AND raw_registration_id=? AND state='ACTIVE'",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        raw);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    long generation(Map<String, Object> b, UUID year, UUID raw) {
        return ((Number)
                        db.one(
                                        "SELECT source_generation FROM"
                                                + " plugin_finance_cash_source_match_guard WHERE"
                                                + " tenant_id=? AND book_id=? AND year_id=? AND"
                                                + " raw_registration_id=?",
                                        b.get("tenant_id"),
                                        b.get("id"),
                                        year,
                                        raw)
                                .get("source_generation"))
                .longValue();
    }

    Map<String, Object> watermark(Map<String, Object> b, UUID year, UUID raw) {
        return Map.of(
                "yearMatchVersion",
                db.one(
                                "SELECT match_version FROM plugin_finance_cash_match_guard WHERE"
                                        + " tenant_id=? AND book_id=? AND year_id=?",
                                b.get("tenant_id"),
                                b.get("id"),
                                year)
                        .get("match_version"),
                "sourceMatchGeneration",
                generation(b, year, raw),
                "year",
                finance.watermark(b, year));
    }

    Map<String, Object> verifiedContent(Map<String, Object> row) {
        var c = finance.decode((String) row.get("content_text"));
        if (!digest(c).equals(row.get("content_digest")))
            throw fail("CASH_MATCH_SOURCE_CHANGED", "不可变更正内容摘要不一致");
        return c;
    }

    Map<String, Object> detail(Map<String, Object> b, UUID year, UUID id) {
        var row = correction(b, year, id);
        var c = verifiedContent(row);
        var result = db.one(CORRECTION + " AND id=?", b.get("tenant_id"), b.get("id"), year, id);
        UUID raw = (UUID) row.get("raw_registration_id");
        for (String key :
                List.of(
                        "tenantId",
                        "bookId",
                        "yearId",
                        "legalEntityId",
                        "reason",
                        "rawSnapshot",
                        "originalMatchSnapshot",
                        "targetLegSnapshot",
                        "sourceIdentityDigest")) result.put(key, c.get(key));
        result.put("effectiveClaim", active(b, year, raw));
        result.put("currentSourceMatchGeneration", generation(b, year, raw));
        result.put("asOfWatermark", watermark(b, year, raw));
        var pending =
                db.list(
                        WITHDRAWAL + " AND correction_id=? AND status='REQUESTED'",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        id);
        result.put("pendingWithdrawal", pending.isEmpty() ? null : pending.getFirst());
        result.put("approvalEnabled", true);
        result.put("limitations", LIMITATIONS);
        result.put("rawStatus", "VOIDED");
        result.put("verificationStatus", "UNREVIEWED");
        result.put("classificationStatus", "UNCLASSIFIED");
        result.put("reportContributionEnabled", false);
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(String actor, UUID book, UUID year, UUID id) {
        return detail(finance.originalScope(actor, book, year, "read"), year, id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> effective(String actor, UUID book, UUID year, UUID id) {
        var b = finance.originalScope(actor, book, year, "read");
        var raw = movements.row(b, year, id);
        var r = new LinkedHashMap<String, Object>();
        r.put("originalRegistrationId", id);
        r.put("rawStatus", raw.get("status"));
        r.put("rawVersion", raw.get("version"));
        r.put("rawContentDigest", raw.get("content_digest"));
        r.put("effectiveClaim", active(b, year, id));
        r.put("currentSourceMatchGeneration", generation(b, year, id));
        r.put("asOfWatermark", watermark(b, year, id));
        r.put("verificationStatus", "UNREVIEWED");
        r.put("classificationStatus", "UNCLASSIFIED");
        r.put("reportContributionEnabled", false);
        return r;
    }

    Map<String, Object> page(
            Map<String, Object> b,
            UUID year,
            String kind,
            String sql,
            List<Object> params,
            String stamp,
            String outputStamp,
            int limit,
            String cursor) {
        if (limit < 1 || limit > 100) throw FinanceException.invalid("每页数量须1至100");
        if (cursor != null) {
            try {
                if (cursor.length() > 2000) throw new IllegalArgumentException();
                var c =
                        finance.decode(
                                new String(
                                        Base64.getUrlDecoder().decode(cursor),
                                        StandardCharsets.UTF_8));
                if (!c.keySet().equals(Set.of("v", "book", "year", "kind", "stamp", "id"))
                        || !Integer.valueOf(1).equals(c.get("v"))
                        || !b.get("id").toString().equals(c.get("book"))
                        || !year.toString().equals(c.get("year"))
                        || !kind.equals(c.get("kind"))) throw new IllegalArgumentException();
                var id = UUID.fromString((String) c.get("id"));
                var time = Instant.parse((String) c.get("stamp"));
                if (!id.toString().equals(c.get("id")) || !time.toString().equals(c.get("stamp")))
                    throw new IllegalArgumentException();
                if (db.list(
                                sql + " AND id=? AND " + stamp + "=?",
                                FinanceCashMovements.concat(
                                        params, id, time.atOffset(ZoneOffset.UTC)))
                        .isEmpty()) throw new IllegalArgumentException();
                sql += " AND (" + stamp + ",id)<(?,?)";
                params.add(time.atOffset(ZoneOffset.UTC));
                params.add(id);
            } catch (RuntimeException bad) {
                throw FinanceException.invalid("更正分页游标不属于当前范围或类型无效");
            }
        }
        var rows =
                db.list(
                        sql + " ORDER BY " + stamp + " DESC,id DESC LIMIT ?",
                        FinanceCashMovements.concat(params, limit + 1));
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
                                                            b.get("id").toString(),
                                                            "year",
                                                            year.toString(),
                                                            "kind",
                                                            kind,
                                                            "stamp",
                                                            FinancePages.time(
                                                                    last.get(outputStamp)),
                                                            "id",
                                                            last.get("id").toString()))
                                            .getBytes(StandardCharsets.UTF_8));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("nextCursor", next);
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(
            String actor, UUID book, UUID year, String status, int limit, String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        if (status != null
                && !Set.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED", "WITHDRAWN")
                        .contains(status)) throw FinanceException.invalid("更正状态无效");
        var p = new ArrayList<Object>(List.of(b.get("tenant_id"), book, year));
        String sql = CORRECTION;
        if (status != null) {
            sql += " AND status=?";
            p.add(status);
        }
        return page(
                b, year, "CORRECTIONS:" + status, sql, p, "created_at", "createdAt", limit, cursor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> withdrawals(
            String actor, UUID book, UUID year, UUID id, int limit, String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        correction(b, year, id);
        return page(
                b,
                year,
                "WITHDRAWALS:" + id,
                WITHDRAWAL + " AND correction_id=?",
                new ArrayList<>(List.of(b.get("tenant_id"), book, year, id)),
                "requested_at",
                "requestedAt",
                limit,
                cursor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> eligible(
            String actor, UUID book, UUID year, UUID rawId, int limit, String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        var raw = movements.row(b, year, rawId);
        if (limit < 1 || limit > 100) throw FinanceException.invalid("每页数量须1至100");
        movements.scopeItem(
                b,
                year,
                (UUID) raw.get("cash_scope_revision_id"),
                (UUID) raw.get("account_id"),
                LocalDate.parse(raw.get("occurred_date").toString()),
                true);
        String sql =
                FinanceCashMovements.LEGS
                        + " AND l.account_id=? AND j.date=? AND CASE WHEN l.debit>0 THEN 'IN' ELSE"
                        + " 'OUT' END=? AND CASE WHEN l.debit>0 THEN l.debit ELSE l.credit END=?"
                        + " AND NOT (j.id=? AND l.line_no=?) AND r.id IS NULL";
        var p =
                new ArrayList<Object>(
                        List.of(
                                b.get("tenant_id"),
                                book,
                                year,
                                raw.get("account_id"),
                                LocalDate.parse(raw.get("occurred_date").toString()),
                                raw.get("direction"),
                                new java.math.BigDecimal(raw.get("amount").toString()),
                                raw.get("journal_id"),
                                raw.get("line_no")));
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
                                                "v",
                                                "book",
                                                "year",
                                                "raw",
                                                "rawDigest",
                                                "journal",
                                                "line"))
                        || !Integer.valueOf(1).equals(c.get("v"))
                        || !book.toString().equals(c.get("book"))
                        || !year.toString().equals(c.get("year"))
                        || !rawId.toString().equals(c.get("raw"))
                        || !raw.get("content_digest").equals(c.get("rawDigest"))
                        || !(c.get("line") instanceof Integer || c.get("line") instanceof Long))
                    throw new IllegalArgumentException();
                UUID journal = UUID.fromString((String) c.get("journal"));
                long line = number(c, "line", false);
                if (!journal.toString().equals(c.get("journal"))
                        || line > 500
                        || db.list(
                                        sql + " AND j.id=? AND l.line_no=?",
                                        FinanceCashMovements.concat(p, journal, line))
                                .isEmpty()) throw new IllegalArgumentException();
                sql += " AND (j.id,l.line_no)<(?,?)";
                p.add(journal);
                p.add(line);
            } catch (RuntimeException invalid) {
                throw FinanceException.invalid("目标现金腿游标不属于当前原来源或锚点已变化");
            }
        }
        var rows =
                db.list(
                        sql + " ORDER BY j.id DESC,l.line_no DESC LIMIT ?",
                        FinanceCashMovements.concat(p, limit + 1));
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
                                                            "raw",
                                                            rawId.toString(),
                                                            "rawDigest",
                                                            raw.get("content_digest"),
                                                            "journal",
                                                            last.get("journalId").toString(),
                                                            "line",
                                                            last.get("lineNo")))
                                            .getBytes(StandardCharsets.UTF_8));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("nextCursor", next);
        result.put("asOfWatermark", watermark(b, year, rawId));
        return result;
    }

    static final Set<String> CREATE =
            Set.of(
                    "schemaVersion",
                    "idempotencyKey",
                    "originalRegistrationId",
                    "expectedRawVersion",
                    "rawContentDigest",
                    "expectedSourceMatchGeneration",
                    "targetJournalId",
                    "targetLineNo",
                    "expectedTargetJournalVersion",
                    "expectedTargetPostingSeq",
                    "reason");

    Map<String, Object> normalize(String op, Map<String, Object> input) {
        Set<String> keys =
                op.equals("create")
                        ? CREATE
                        : op.equals("withdraw")
                                ? Set.of(
                                        "schemaVersion",
                                        "idempotencyKey",
                                        "expectedVersion",
                                        "contentDigest",
                                        "activeClaimId",
                                        "expectedSourceMatchGeneration",
                                        "reason")
                                : op.startsWith("withdraw-")
                                        ? Set.of(
                                                "schemaVersion",
                                                "idempotencyKey",
                                                "expectedVersion",
                                                "contentDigest",
                                                "expectedSourceMatchGeneration",
                                                "reason")
                                        : Set.of(
                                                "schemaVersion",
                                                "idempotencyKey",
                                                "expectedVersion",
                                                "targetContentVersion",
                                                "contentDigest",
                                                "expectedSourceMatchGeneration",
                                                "reason");
        FinanceCashScopes.fields(input, keys);
        if (!SCHEMA.equals(input.get("schemaVersion")))
            throw FinanceException.invalid("现金匹配更正schemaVersion无效");
        var n = new LinkedHashMap<>(input);
        n.remove("idempotencyKey");
        n.put("reason", text(input, "reason", 1000));
        n.put(
                "expectedSourceMatchGeneration",
                number(input, "expectedSourceMatchGeneration", true));
        for (String k :
                List.of(
                        "expectedVersion",
                        "targetContentVersion",
                        "expectedRawVersion",
                        "targetLineNo",
                        "expectedTargetJournalVersion",
                        "expectedTargetPostingSeq"))
            if (input.containsKey(k)) n.put(k, number(input, k, false));
        for (String k : List.of("originalRegistrationId", "targetJournalId", "activeClaimId"))
            if (input.containsKey(k)) n.put(k, uuid(input, k).toString());
        return n;
    }

    void originalAuthor(String actor, Map<String, Object> b, UUID year, Map<String, Object> raw) {
        var current = finance.originalScope(actor, (UUID) b.get("id"), year, "create");
        if (!Objects.equals(current.get("actor_id"), raw.get("prepared_by")))
            throw fail("SCOPE_DENIED", "仅原来源制备人可制备、提交、取消或请求撤回更正");
    }

    void preparer(Map<String, Object> b, UUID year, Map<String, Object> raw) {
        try {
            var current =
                    finance.originalScope(
                            (String) raw.get("prepared_username"),
                            (UUID) b.get("id"),
                            year,
                            "create");
            if (!Objects.equals(current.get("actor_id"), raw.get("prepared_by")))
                throw new IllegalArgumentException();
        } catch (RuntimeException stale) {
            throw fail("APPROVAL_STALE", "原制备人的当前制单或年度授权已失效");
        }
    }

    void independent(UUID actor, Map<String, Object> raw) {
        if (actor.equals(raw.get("prepared_by")))
            throw fail("SELF_APPROVAL_FORBIDDEN", "现金匹配更正及撤回须由独立账号复核");
    }

    void unused(Map<String, Object> b, UUID year, UUID raw) {
        if (!db.list(
                        "SELECT claim_id FROM plugin_finance_cash_claim_usage WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=? AND raw_registration_id=? AND"
                            + " state='ACTIVE'",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        raw)
                .isEmpty()) throw fail("CASH_REGISTRATION_IN_USE", "来源匹配已有有效下游使用");
    }

    Map<String, Object> target(
            Map<String, Object> b,
            UUID year,
            Map<String, Object> raw,
            UUID journal,
            int line,
            long version,
            long seq) {
        if (journal.equals(raw.get("journal_id"))
                && line == ((Number) raw.get("line_no")).intValue())
            throw fail("CASH_MATCH_TARGET_UNCHANGED", "更正目标不能仍是原错误匹配");
        movements.scopeItem(
                b,
                year,
                (UUID) raw.get("cash_scope_revision_id"),
                (UUID) raw.get("account_id"),
                LocalDate.parse(raw.get("occurred_date").toString()),
                true);
        var original = scopes.evidence(b, year, (UUID) raw.get("source_original_id"));
        if (!original.get("sha256").equals(raw.get("source_original_sha256")))
            throw fail("CASH_MATCH_SOURCE_CHANGED", "原来源证据摘要已失效");
        db.one(
                "SELECT line_no FROM plugin_finance_line WHERE tenant_id=? AND book_id=? AND"
                        + " year_id=? AND journal_id=? AND line_no=? FOR UPDATE",
                b.get("tenant_id"),
                b.get("id"),
                year,
                journal,
                line);
        var rows =
                db.list(
                        FinanceCashMovements.LEGS + " AND j.id=? AND l.line_no=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        journal,
                        line);
        if (rows.isEmpty()) throw fail("CASH_MATCH_TARGET_INVALID", "目标须为真实完整已记账现金腿");
        var leg = rows.getFirst();
        if (leg.get("activeClaimId") != null)
            throw fail("CASH_LEG_ALREADY_REGISTERED", "目标已被来源或更正占用");
        if (!leg.get("accountId").equals(raw.get("account_id"))
                || !leg.get("date").equals(raw.get("occurred_date"))
                || !leg.get("direction").equals(raw.get("direction"))
                || new java.math.BigDecimal(leg.get("normalizedAmount").toString())
                                .compareTo(new java.math.BigDecimal(raw.get("amount").toString()))
                        != 0
                || ((Number) leg.get("journalVersion")).longValue() != version
                || ((Number) leg.get("postingSeq")).longValue() != seq)
            throw fail("CASH_MATCH_TARGET_INVALID", "目标科目、日期、方向、全额、版本或过账水位不符原来源");
        for (String key :
                List.of(
                        "activeRegistrationId",
                        "activeClaimId",
                        "activeClaimKind",
                        "activeCorrectionId")) leg.remove(key);
        return leg;
    }

    @Transactional
    public Map<String, Object> write(
            String actor,
            UUID book,
            UUID year,
            String op,
            UUID id,
            UUID withdrawalId,
            Map<String, Object> input) {
        if (!Set.of(
                        "create",
                        "submit",
                        "approve",
                        "reject",
                        "cancel",
                        "withdraw",
                        "withdraw-approve",
                        "withdraw-reject")
                .contains(op)) throw FinanceException.invalid("未知匹配更正动作");
        boolean review =
                Set.of("approve", "reject", "withdraw-approve", "withdraw-reject").contains(op);
        var b = finance.originalScope(actor, book, year, review ? "approve" : "create");
        var n = normalize(op, input);
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
        b = finance.originalScope(actor, book, year, review ? "approve" : "create");
        finance.lockYearWrites(b, List.of(year));
        Map<String, Object> corr = op.equals("create") ? null : correction(b, year, id);
        UUID rawId =
                op.equals("create")
                        ? uuid(n, "originalRegistrationId")
                        : (UUID) corr.get("raw_registration_id");
        var raw = movements.row(b, year, rawId);
        if (review) independent(user, raw);
        else originalAuthor(actor, b, year, raw);
        db.one(
                "SELECT registration_version FROM plugin_finance_cash_registration_guard WHERE"
                        + " tenant_id=? AND book_id=? AND year_id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year);
        scopes.period(b, year, LocalDate.parse(raw.get("occurred_date").toString()), true);
        db.one(
                "SELECT match_version FROM plugin_finance_cash_match_guard WHERE tenant_id=? AND"
                        + " book_id=? AND year_id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year);
        raw =
                db.one(
                        "SELECT *,content::text AS content_text FROM"
                                + " plugin_finance_cash_movement_registration WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND id=? FOR UPDATE",
                        b.get("tenant_id"),
                        book,
                        year,
                        rawId);
        movements.detail(b, year, rawId);
        if (!"VOIDED".equals(raw.get("status")))
            throw fail("CASH_MATCH_RAW_NOT_VOIDED", "仅可更正永久作废且未使用的原来源匹配");
        if (op.equals("approve") || op.equals("withdraw-approve")) preparer(b, year, raw);
        String
                route =
                        book
                                + "/"
                                + year
                                + "/match-corrections/"
                                + id
                                + "/"
                                + op
                                + "/"
                                + withdrawalId,
                hash = digest(n);
        var cached =
                db.list(
                        "SELECT route,payload_hash,response FROM plugin_finance_idempotency WHERE"
                                + " actor_id=? AND key=?",
                        user,
                        key);
        if (!cached.isEmpty()) {
            var c = cached.getFirst();
            if (!route.equals(c.get("route")) || !hash.equals(c.get("payload_hash")))
                throw fail("IDEMPOTENCY_CONFLICT", "请求键已用于不同更正命令");
            var prior = finance.decode((String) c.get("response"));
            if (op.startsWith("withdraw"))
                return withdrawal(
                        b,
                        year,
                        UUID.fromString(prior.get("correctionId").toString()),
                        UUID.fromString(prior.get("id").toString()));
            return detail(b, year, UUID.fromString(prior.get("id").toString()));
        }
        if (number(n, "expectedSourceMatchGeneration", true) != generation(b, year, rawId))
            throw fail("CASH_MATCH_GENERATION_STALE", "来源匹配代次已变化，请重新读取当前有效匹配");
        unused(b, year, rawId);
        Map<String, Object> result;
        if (op.equals("create")) {
            if (number(n, "expectedRawVersion", false) != ((Number) raw.get("version")).longValue()
                    || !raw.get("content_digest").equals(n.get("rawContentDigest")))
                throw fail("CASH_MATCH_SOURCE_CHANGED", "原来源版本或摘要不符");
            if (active(b, year, rawId) != null)
                throw fail("CASH_MATCH_EFFECTIVE_EXISTS", "已有有效更正，须先独立审签撤回");
            if (!db.list(
                            "SELECT id FROM plugin_finance_cash_match_correction WHERE tenant_id=?"
                                    + " AND book_id=? AND year_id=? AND raw_registration_id=? AND"
                                    + " status IN('DRAFT','SUBMITTED')",
                            b.get("tenant_id"),
                            book,
                            year,
                            rawId)
                    .isEmpty()) throw fail("CASH_MATCH_PENDING_EXISTS", "已有待处理更正提案");
            long requestedLine = number(n, "targetLineNo", false);
            if (requestedLine > 500) throw FinanceException.invalid("目标行号须1至500");
            int line = (int) requestedLine;
            var leg =
                    target(
                            b,
                            year,
                            raw,
                            uuid(n, "targetJournalId"),
                            line,
                            number(n, "expectedTargetJournalVersion", false),
                            number(n, "expectedTargetPostingSeq", false));
            var content = new LinkedHashMap<String, Object>(n);
            content.put("tenantId", b.get("tenant_id").toString());
            content.put("bookId", book.toString());
            content.put("yearId", year.toString());
            content.put("legalEntityId", raw.get("legal_entity_id").toString());
            content.put("preparedBy", user.toString());
            content.put("rawSnapshot", finance.decode((String) raw.get("content_text")));
            content.put(
                    "originalMatchSnapshot",
                    ((Map<String, Object>) content.get("rawSnapshot")).get("matchedLegSnapshot"));
            content.put("targetLegSnapshot", leg);
            String sourceDigest =
                    digest(
                            Map.of(
                                    "rawRegistrationId",
                                    rawId.toString(),
                                    "rawContentDigest",
                                    raw.get("content_digest")));
            content.put("sourceIdentityDigest", sourceDigest);
            id = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_cash_match_correction(tenant_id,book_id,year_id,id,legal_entity_id,raw_registration_id,account_id,raw_version_at_proposal,raw_content_digest,source_identity_digest,target_journal_id,target_line_no,target_journal_version,target_posting_seq,target_posting_digest,expected_match_generation,content_digest,content,reason,prepared_by,prepared_username,last_edited_by)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)",
                    b.get("tenant_id"),
                    book,
                    year,
                    id,
                    raw.get("legal_entity_id"),
                    rawId,
                    raw.get("account_id"),
                    raw.get("version"),
                    raw.get("content_digest"),
                    sourceDigest,
                    leg.get("journalId"),
                    line,
                    leg.get("journalVersion"),
                    leg.get("postingSeq"),
                    leg.get("postingDigest"),
                    n.get("expectedSourceMatchGeneration"),
                    digest(content),
                    finance.encode(content),
                    n.get("reason"),
                    user,
                    actor,
                    user);
            result = detail(b, year, id);
        } else if (op.startsWith("withdraw")) {
            result = withdrawCommand(actor, b, year, op, corr, raw, withdrawalId, n, user);
        } else {
            verifiedContent(corr);
            if (number(n, "expectedVersion", false) != ((Number) corr.get("version")).longValue()
                    || number(n, "targetContentVersion", false) != 1
                    || !corr.get("content_digest").equals(n.get("contentDigest")))
                throw fail("VERSION_CONFLICT", "更正版本、内容版本或摘要不匹配");
            String state = (String) corr.get("status");
            if (op.equals("cancel")) {
                if (!Set.of("DRAFT", "SUBMITTED").contains(state))
                    throw fail("VERSION_CONFLICT", "仅可取消尚未生效提案");
                db.update(
                        "UPDATE plugin_finance_cash_match_correction SET"
                            + " status='CANCELLED',version=version+1,decision_by=?,decision_at=now(),decision_reason=?"
                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        user,
                        n.get("reason"),
                        b.get("tenant_id"),
                        book,
                        year,
                        id);
            } else if (op.equals("submit")) {
                if (!"DRAFT".equals(state)) throw fail("VERSION_CONFLICT", "仅草稿可提交");
                db.update(
                        "UPDATE plugin_finance_cash_match_correction SET"
                                + " status='SUBMITTED',version=version+1,submitted_at=now() WHERE"
                                + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        book,
                        year,
                        id);
            } else {
                if (!"SUBMITTED".equals(state)) throw fail("VERSION_CONFLICT", "仅已提交提案可审签");
                if (op.equals("approve")) {
                    preparer(b, year, raw);
                    target(
                            b,
                            year,
                            raw,
                            (UUID) corr.get("target_journal_id"),
                            ((Number) corr.get("target_line_no")).intValue(),
                            ((Number) corr.get("target_journal_version")).longValue(),
                            ((Number) corr.get("target_posting_seq")).longValue());
                    db.update(
                            "UPDATE plugin_finance_cash_match_correction SET"
                                + " status='APPROVED',version=version+1,approved_by=?,approved_at=now(),approved_claim_id=?,approved_match_generation=?,decision_by=?,decision_at=now(),decision_reason=?"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            user,
                            UUID.randomUUID(),
                            generation(b, year, rawId) + 1,
                            user,
                            n.get("reason"),
                            b.get("tenant_id"),
                            book,
                            year,
                            id);
                } else
                    db.update(
                            "UPDATE plugin_finance_cash_match_correction SET"
                                + " status='REJECTED',version=version+1,decision_by=?,decision_at=now(),decision_reason=?"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            user,
                            n.get("reason"),
                            b.get("tenant_id"),
                            book,
                            year,
                            id);
            }
            result = detail(b, year, id);
        }
        finance.originalAudit(
                user,
                b,
                year,
                "CASH_MATCH_" + op.toUpperCase(Locale.ROOT).replace('-', '_'),
                (UUID) result.get("id"),
                result);
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

    Map<String, Object> withdrawCommand(
            String actor,
            Map<String, Object> b,
            UUID year,
            String op,
            Map<String, Object> corr,
            Map<String, Object> raw,
            UUID withdrawalId,
            Map<String, Object> n,
            UUID user) {
        UUID id = (UUID) corr.get("id"), rawId = (UUID) raw.get("id");
        var claim = active(b, year, rawId);
        if (!"APPROVED".equals(corr.get("status"))
                || claim == null
                || !id.equals(claim.get("correctionId")))
            throw fail("VERSION_CONFLICT", "更正已非当前有效匹配");
        if (op.equals("withdraw")) {
            if (number(n, "expectedVersion", false) != ((Number) corr.get("version")).longValue()
                    || !corr.get("content_digest").equals(n.get("contentDigest"))
                    || !claim.get("id").equals(uuid(n, "activeClaimId")))
                throw fail("VERSION_CONFLICT", "撤回须绑定当前更正版本、摘要及有效claim");
            if (!db.list(
                            WITHDRAWAL + " AND correction_id=? AND status='REQUESTED'",
                            b.get("tenant_id"),
                            b.get("id"),
                            year,
                            id)
                    .isEmpty()) throw fail("CASH_MATCH_WITHDRAWAL_PENDING", "已有待审撤回请求，当前占用保持");
            var c = new LinkedHashMap<String, Object>();
            c.put("schemaVersion", SCHEMA);
            c.put("correctionId", id.toString());
            c.put("originalRegistrationId", rawId.toString());
            c.put("activeClaimId", claim.get("id").toString());
            c.put("expectedCorrectionVersion", corr.get("version"));
            c.put("expectedSourceMatchGeneration", n.get("expectedSourceMatchGeneration"));
            c.put("reason", n.get("reason"));
            c.put("requestedBy", user.toString());
            c.put("claimSnapshot", claim);
            withdrawalId = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_cash_match_withdrawal(tenant_id,book_id,year_id,id,correction_id,raw_registration_id,active_claim_id,claim_generation,claim_content_digest,expected_correction_version,expected_source_generation,content_digest,content,reason,requested_by,requested_username)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)",
                    b.get("tenant_id"),
                    b.get("id"),
                    year,
                    withdrawalId,
                    id,
                    rawId,
                    claim.get("id"),
                    claim.get("generation"),
                    claim.get("contentDigest"),
                    corr.get("version"),
                    n.get("expectedSourceMatchGeneration"),
                    digest(c),
                    finance.encode(c),
                    n.get("reason"),
                    user,
                    actor);
        } else {
            var row =
                    db.one(
                            "SELECT *,content::text AS content_text FROM"
                                    + " plugin_finance_cash_match_withdrawal WHERE tenant_id=? AND"
                                    + " book_id=? AND year_id=? AND correction_id=? AND id=? FOR"
                                    + " UPDATE",
                            b.get("tenant_id"),
                            b.get("id"),
                            year,
                            id,
                            withdrawalId);
            verifiedContent(row);
            if (!"REQUESTED".equals(row.get("status"))
                    || number(n, "expectedVersion", false)
                            != ((Number) row.get("version")).longValue()
                    || !row.get("content_digest").equals(n.get("contentDigest")))
                throw fail("VERSION_CONFLICT", "撤回请求版本或摘要不匹配");
            if (op.equals("withdraw-approve")) preparer(b, year, raw);
            db.update(
                    "UPDATE plugin_finance_cash_match_withdrawal SET"
                        + " status=?,version=version+1,reviewed_by=?,reviewed_at=now(),decision_reason=?"
                        + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                    op.equals("withdraw-approve") ? "APPROVED" : "REJECTED",
                    user,
                    n.get("reason"),
                    b.get("tenant_id"),
                    b.get("id"),
                    year,
                    withdrawalId);
        }
        return withdrawal(b, year, id, withdrawalId);
    }
}
