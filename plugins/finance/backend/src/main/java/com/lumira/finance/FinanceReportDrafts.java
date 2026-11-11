package com.lumira.finance;

import com.lumira.finance.domain.ReportSourceEngine;
import com.lumira.finance.domain.ReportSourceEngine.*;
import com.lumira.platform.common.AuthorizationContextProjectionPort;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Bounded PostgreSQL snapshot extractor & governance adapter for ReportSourceEngine (RP01/RPT02-RPT05).
 * Formal statutory /reports remains gated at RULE_VERSION_UNAVAILABLE; this adapter emits BLOCKED_DRAFT only.
 */
@Service
public final class FinanceReportDrafts extends FinanceProfitClosingAccess {

    public record AccountMappingItem(
            UUID accountId,
            String targetKind,
            Semantic semantic,
            ContraType contraType,
            UUID targetAccountId) {
        public AccountMappingItem(UUID accountId, String targetKind, Semantic semantic) {
            this(accountId, targetKind, semantic, null, null);
        }
    }

    public record CertifiedRowItem(Kind kind, String rowId) {}

    public record CreateMappingInput(
            String mappingVersion,
            List<AccountMappingItem> mappings,
            List<CertifiedRowItem> certifiedRows,
            String reason) {}

    public record ApproveMappingInput(long expectedVersion, String reason) {}

    public record EvaluateDraftInput(UUID mappingId, LocalDate from, LocalDate through) {}

    public FinanceReportDrafts(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager tx,
            AuthorizationContextProjectionPort authorization) {
        super(db, finance, gate, tx, authorization);
    }

    private Standard resolveStandard(Map<String, Object> book) {
        String std = book.get("standard").toString();
        return switch (std) {
            case "CAS" -> Standard.CAS_2019;
            case "SMALL" -> Standard.SBE_2011;
            default -> throw error("REPORT_UNSUPPORTED_REGIME", "当前账套会计制度暂不支持三表底稿映射");
        };
    }

    public Object listMappings(String principal, UUID book, UUID year) {
        return transaction(
                false,
                c -> {
                    var b = c.scope(principal, book, year, null);
                    var rows =
                            c.list(
                                    "SELECT id,mapping_version,standard,mappings,certified_rows,"
                                        + "content_digest,status,version,template_approved,reason,created_at "
                                        + "FROM plugin_finance_report_mapping "
                                        + "WHERE tenant_id=? AND book_id=? AND year_id=? "
                                        + "ORDER BY created_at DESC, id DESC LIMIT 50",
                                    b.get("tenant_id"),
                                    book,
                                    year);
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (var r : rows) {
                        items.add(mappingView(r));
                    }
                    return map(
                            "items",
                            items,
                            "formalStatutoryReportStatus",
                            "RULE_VERSION_UNAVAILABLE",
                            "templateApproved",
                            false);
                });
    }

    public Object createMapping(
            String principal,
            UUID book,
            UUID year,
            CreateMappingInput in,
            HttpServletRequest request) {
        if (in.mappingVersion() == null
                || in.mappingVersion().isBlank()
                || in.mappingVersion().length() > 128
                || in.mappings() == null
                || in.mappings().isEmpty()
                || in.mappings().size() > 200
                || in.reason() == null
                || in.reason().isBlank()
                || in.reason().length() > 1000)
            throw FinanceException.invalid("报表取数映射请求参数无效");
        return transaction(
                true,
                c -> {
                    var b = c.scope(principal, book, year, "manage");
                    if (!allowed(b, "manage"))
                        throw error("SCOPE_DENIED", "需要账套管理权限配置报表科目语义映射");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, false, request);
                    Standard std = resolveStandard(b);

                    Set<String> seenPairs = new HashSet<>();
                    List<Map<String, Object>> normalizedMappings = new ArrayList<>();
                    for (AccountMappingItem item : in.mappings()) {
                        if (item == null
                                || item.accountId() == null
                                || item.targetKind() == null
                                || !Set.of("BS", "PL", "CONTRA").contains(item.targetKind()))
                            throw FinanceException.invalid("科目语义映射项不完整");
                        if (!seenPairs.add(item.accountId() + ":" + item.targetKind()))
                            throw FinanceException.invalid("同一科目在同一表类中不得重复映射");
                        var acc =
                                c.list(
                                        "SELECT id FROM plugin_finance_account WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                        tenant,
                                        book,
                                        year,
                                        item.accountId());
                        if (acc.isEmpty())
                            throw FinanceException.invalid("映射引用的科目不属于当前年度");
                        if ("CONTRA".equals(item.targetKind())) {
                            if (item.contraType() == null
                                    || item.targetAccountId() == null
                                    || item.accountId().equals(item.targetAccountId()))
                                throw FinanceException.invalid("备抵科目映射必须指定有效的 contraType 与主资产科目 targetAccountId");
                            var targetAcc =
                                    c.list(
                                            "SELECT id FROM plugin_finance_account WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                            tenant,
                                            book,
                                            year,
                                            item.targetAccountId());
                            if (targetAcc.isEmpty())
                                throw FinanceException.invalid("备抵科目关联的主资产科目不属于当前年度");
                            Semantic resolvedSem =
                                    item.semantic() != null
                                            ? item.semantic()
                                            : (item.contraType() == ContraType.AMORTIZATION
                                                    ? Semantic.INTANGIBLE
                                                    : Semantic.FIXED_ASSET);
                            normalizedMappings.add(
                                    map(
                                            "accountId",
                                            item.accountId().toString(),
                                            "targetKind",
                                            "CONTRA",
                                            "semantic",
                                            resolvedSem.name(),
                                            "contraType",
                                            item.contraType().name(),
                                            "targetAccountId",
                                            item.targetAccountId().toString()));
                        } else {
                            if (item.semantic() == null
                                    || item.contraType() != null
                                    || item.targetAccountId() != null)
                                throw FinanceException.invalid("科目语义映射项不完整或包含非法备抵字段");
                            normalizedMappings.add(
                                    map(
                                            "accountId",
                                            item.accountId().toString(),
                                            "targetKind",
                                            item.targetKind(),
                                            "semantic",
                                            item.semantic().name()));
                        }
                    }

                    List<Map<String, Object>> normalizedCerts = new ArrayList<>();
                    if (in.certifiedRows() != null) {
                        Set<String> seenCert = new HashSet<>();
                        for (CertifiedRowItem cr : in.certifiedRows()) {
                            if (cr == null
                                    || cr.kind() == null
                                    || cr.rowId() == null
                                    || cr.rowId().isBlank()
                                    || !seenCert.add(cr.kind() + ":" + cr.rowId()))
                                throw FinanceException.invalid("完备行声明无效或重复");
                            normalizedCerts.add(
                                    map("kind", cr.kind().name(), "rowId", cr.rowId()));
                        }
                    }

                    var dup =
                            c.list(
                                    "SELECT id FROM plugin_finance_report_mapping WHERE tenant_id=? AND book_id=? AND year_id=? AND mapping_version=?",
                                    tenant,
                                    book,
                                    year,
                                    in.mappingVersion());
                    if (!dup.isEmpty())
                        throw error("REPORT_MAPPING_VERSION_EXISTS", "该年度已存在同名报表映射版本");

                    UUID id = UUID.randomUUID();
                    String digest =
                            FinanceService.hash(
                                    finance.encode(
                                            finance.canonical(
                                                    map(
                                                            "mappingVersion",
                                                            in.mappingVersion(),
                                                            "standard",
                                                            std.name(),
                                                            "mappings",
                                                            normalizedMappings,
                                                            "certifiedRows",
                                                            normalizedCerts))));
                    c.update(
                            "INSERT INTO plugin_finance_report_mapping("
                                + "tenant_id,book_id,year_id,id,mapping_version,standard,mappings,"
                                + "certified_rows,content_digest,status,version,template_approved,reason,prepared_by) "
                                + "VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb,?,'PENDING',1,false,?,?::jsonb)",
                            tenant,
                            book,
                            year,
                            id,
                            in.mappingVersion(),
                            std.name(),
                            finance.encode(normalizedMappings),
                            finance.encode(normalizedCerts),
                            digest,
                            in.reason(),
                            finance.encode(who));
                    return mappingView(
                            c.one(
                                    "SELECT * FROM plugin_finance_report_mapping WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                    tenant,
                                    book,
                                    year,
                                    id));
                });
    }

    public Object approveMapping(
            String principal,
            UUID book,
            UUID year,
            UUID mappingId,
            ApproveMappingInput in,
            HttpServletRequest request) {
        if (in.reason() == null || in.reason().isBlank() || in.reason().length() > 1000)
            throw FinanceException.invalid("批准原因无效");
        return transaction(
                true,
                c -> {
                    var b = c.scope(principal, book, year, "approve");
                    if (!allowed(b, "approve"))
                        throw error("SCOPE_DENIED", "需要独立审签权限批准报表取数映射");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, true, request);
                    var row =
                            c.one(
                                    "SELECT * FROM plugin_finance_report_mapping WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    book,
                                    year,
                                    mappingId);
                    if (((Number) row.get("version")).longValue() != in.expectedVersion())
                        throw error("VERSION_CONFLICT", "报表取数映射版本冲突");
                    if (!"PENDING".equals(row.get("status")))
                        throw error("REPORT_MAPPING_STATE_CONFLICT", "仅待审报表取数映射可批准");
                    independent(who, json(row.get("prepared_by")));
                    c.update(
                            "UPDATE plugin_finance_report_mapping SET status='APPROVED',version=2,"
                                + "approved_by=?::jsonb,approved_at=? WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            finance.encode(who),
                            Timestamp.from(Instant.now()),
                            tenant,
                            book,
                            year,
                            mappingId);
                    return mappingView(
                            c.one(
                                    "SELECT * FROM plugin_finance_report_mapping WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                    tenant,
                                    book,
                                    year,
                                    mappingId));
                });
    }

    @SuppressWarnings("unchecked")
    public Object evaluate(
            String principal, UUID book, UUID year, EvaluateDraftInput in) {
        if (in.mappingId() == null || in.from() == null || in.through() == null)
            throw FinanceException.invalid("报表底稿试算必须指定 mappingId、from 与 through");
        return transaction(
                false,
                c -> {
                    var b = c.scope(principal, book, year, null);
                    UUID tenant = id(b.get("tenant_id"));
                    UUID entityId = id(b.get("legal_entity_id"));
                    var yr =
                            c.one(
                                    "SELECT year,precision_profile FROM plugin_finance_year WHERE tenant_id=? AND book_id=? AND id=?",
                                    tenant,
                                    book,
                                    year);
                    int yearNum = ((Number) yr.get("year")).intValue();
                    if (in.from().getYear() != yearNum
                            || in.through().getYear() != yearNum
                            || in.from().isAfter(in.through()))
                        throw FinanceException.invalid("试算起止日期必须位于所选会计年度内且起日不晚于止日");
                    if ("LEGACY_V3".equals(yr.get("precision_profile")))
                        throw error(
                                "LEGACY_PRECISION_REVIEW_REQUIRED",
                                "旧精度年度未完成V4精度确认，不执行V4三表取数试算");

                    var mpRow =
                            c.one(
                                    "SELECT * FROM plugin_finance_report_mapping WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                    tenant,
                                    book,
                                    year,
                                    in.mappingId());
                    if (!"APPROVED".equals(mpRow.get("status")))
                        throw error("REPORT_MAPPING_NOT_APPROVED", "报表取数映射尚未完成独立自然人批准");
                    Standard std = Standard.valueOf(mpRow.get("standard").toString());
                    if (std != resolveStandard(b))
                        throw error("REPORT_UNSUPPORTED_REGIME", "报表映射准则与账套当前准则不一致");

                    var guard =
                            c.one(
                                    "SELECT posting_seq,opening_version,baseline_version FROM plugin_finance_year_guard WHERE tenant_id=? AND book_id=? AND year_id=?",
                                    tenant,
                                    book,
                                    year);
                    String watermark =
                            "postingSeq:"
                                    + guard.get("posting_seq")
                                    + ":opening:"
                                    + guard.get("opening_version")
                                    + ":baseline:"
                                    + guard.get("baseline_version");
                    String snapshotId =
                            "snap:"
                                    + FinanceService.hash(
                                                    tenant
                                                            + ":"
                                                            + book
                                                            + ":"
                                                            + year
                                                            + ":"
                                                            + watermark
                                                            + ":"
                                                            + mpRow.get("content_digest")
                                                            + ":"
                                                            + in.through())
                                            .substring(0, 24);

                    List<Map<String, Object>> mappings =
                            gate.json.readValue(mpRow.get("mappings").toString(), List.class);
                    List<Map<String, Object>> certRows =
                            gate.json.readValue(mpRow.get("certified_rows").toString(), List.class);

                    List<Balance> balances = new ArrayList<>();
                    List<Contra> contras = new ArrayList<>();
                    List<Movement> movements = new ArrayList<>();
                    for (Map<String, Object> m : mappings) {
                        UUID accountId = id(m.get("accountId"));
                        String targetKind = m.get("targetKind").toString();
                        Semantic sem = Semantic.valueOf(m.get("semantic").toString());
                        if ("BS".equals(targetKind)) {
                            var balRow =
                                    c.one(
                                            "SELECT COALESCE((SELECT balance FROM plugin_finance_opening o "
                                                + "WHERE o.tenant_id=? AND o.book_id=? AND o.year_id=? AND o.account_id=?),0) "
                                                + "+ COALESCE((SELECT SUM(l.debit-l.credit) FROM plugin_finance_line l "
                                                + "JOIN plugin_finance_journal j ON (j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id) "
                                                + "WHERE l.tenant_id=? AND l.book_id=? AND l.year_id=? AND l.account_id=? AND j.status='POSTED' AND j.date<=?),0) AS b",
                                            tenant,
                                            book,
                                            year,
                                            accountId,
                                            tenant,
                                            book,
                                            year,
                                            accountId,
                                            in.through());
                            BigDecimal bVal =
                                    new BigDecimal(balRow.get("b").toString())
                                            .setScale(8, RoundingMode.UNNECESSARY);
                            balances.add(
                                    new Balance(
                                            "bs:" + accountId,
                                            snapshotId,
                                            accountId.toString(),
                                            "unit:" + accountId,
                                            "obj:" + accountId,
                                            sem,
                                            bVal.stripTrailingZeros().toPlainString()));
                        } else if ("CONTRA".equals(targetKind)) {
                            UUID targetAccountId = id(m.get("targetAccountId"));
                            ContraType contraType = ContraType.valueOf(m.get("contraType").toString());
                            var contraRow =
                                    c.one(
                                            "SELECT COALESCE(-(SELECT balance FROM plugin_finance_opening o "
                                                + "WHERE o.tenant_id=? AND o.book_id=? AND o.year_id=? AND o.account_id=?),0) "
                                                + "+ COALESCE((SELECT SUM(l.credit-l.debit) FROM plugin_finance_line l "
                                                + "JOIN plugin_finance_journal j ON (j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id) "
                                                + "WHERE l.tenant_id=? AND l.book_id=? AND l.year_id=? AND l.account_id=? AND j.status='POSTED' AND j.date<=?),0) AS c",
                                            tenant,
                                            book,
                                            year,
                                            accountId,
                                            tenant,
                                            book,
                                            year,
                                            accountId,
                                            in.through());
                            BigDecimal cVal =
                                    new BigDecimal(contraRow.get("c").toString())
                                            .setScale(8, RoundingMode.UNNECESSARY);
                            contras.add(
                                    new Contra(
                                            "contra:" + accountId,
                                            snapshotId,
                                            "unit:" + targetAccountId,
                                            "obj:" + targetAccountId,
                                            contraType,
                                            cVal.stripTrailingZeros().toPlainString()));
                        } else {
                            var lineRows =
                                    c.list(
                                            "SELECT j.id AS journal_id, j.date, j.type, j.reversal_of, l.line_no, l.debit, l.credit "
                                                + "FROM plugin_finance_line l "
                                                + "JOIN plugin_finance_journal j ON (j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id) "
                                                + "WHERE l.tenant_id=? AND l.book_id=? AND l.year_id=? AND l.account_id=? "
                                                + "AND j.status='POSTED' AND j.date>=? AND j.date<=? "
                                                + "ORDER BY j.date, j.id, l.line_no",
                                            tenant,
                                            book,
                                            year,
                                            accountId,
                                            in.from(),
                                            in.through());
                            for (var lr : lineRows) {
                                String jType = lr.get("type").toString();
                                Closing closing = Closing.NONE;
                                Closing revOrigin = null;
                                if ("PROFIT_CLOSE".equals(jType)) {
                                    closing = Closing.MONTH_PROFIT_TRANSFER;
                                } else if ("YEAR_END".equals(jType)) {
                                    closing = Closing.ANNUAL_PROFIT_TRANSFER;
                                } else if ("REVERSAL".equals(jType) && lr.get("reversal_of") != null) {
                                    var origJ =
                                            c.one(
                                                    "SELECT type FROM plugin_finance_journal WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                                    tenant,
                                                    book,
                                                    year,
                                                    lr.get("reversal_of"));
                                    if ("PROFIT_CLOSE".equals(origJ.get("type"))) {
                                        closing = Closing.TRANSFER_REVERSAL;
                                        revOrigin = Closing.MONTH_PROFIT_TRANSFER;
                                    } else if ("YEAR_END".equals(origJ.get("type"))) {
                                        closing = Closing.TRANSFER_REVERSAL;
                                        revOrigin = Closing.ANNUAL_PROFIT_TRANSFER;
                                    }
                                }
                                BigDecimal d =
                                        new BigDecimal(lr.get("debit").toString()).stripTrailingZeros();
                                BigDecimal cr =
                                        new BigDecimal(lr.get("credit").toString()).stripTrailingZeros();
                                movements.add(
                                        new Movement(
                                                "pl:" + lr.get("journal_id") + ":" + lr.get("line_no"),
                                                snapshotId,
                                                accountId.toString(),
                                                sem,
                                                LocalDate.parse(lr.get("date").toString()),
                                                d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString(),
                                                cr.scale() < 0 ? cr.setScale(0).toPlainString() : cr.toPlainString(),
                                                closing,
                                                revOrigin));
                            }
                        }
                    }

                    List<Coverage> coverages = new ArrayList<>();
                    for (Map<String, Object> cr : certRows) {
                        coverages.add(
                                new Coverage(
                                        Kind.valueOf(cr.get("kind").toString()),
                                        cr.get("rowId").toString(),
                                        snapshotId,
                                        "mapping:" + in.mappingId()));
                    }

                    Scope engineScope =
                            new Scope(
                                    tenant.toString(),
                                    entityId.toString(),
                                    book.toString(),
                                    year.toString(),
                                    std,
                                    "INDIVIDUAL",
                                    "CNY",
                                    in.from(),
                                    in.through(),
                                    watermark,
                                    mpRow.get("mapping_version").toString(),
                                    in.mappingId().toString(),
                                    snapshotId);
                    try {
                        Result result =
                                ReportSourceEngine.calculate(
                                        new Input(
                                                engineScope,
                                                "FULL_HISTORY",
                                                balances,
                                                contras,
                                                movements,
                                                coverages));
                        return gate.json.readValue(gate.json.writeValueAsString(result), Map.class);
                    } catch (IllegalArgumentException iae) {
                        throw new FinanceException(
                                "REPORT_SEMANTIC_BLOCKED",
                                "报表领域语义校验阻断（" + iae.getMessage() + "）");
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mappingView(Map<String, Object> r) {
        return map(
                "id",
                r.get("id").toString(),
                "mappingVersion",
                r.get("mapping_version"),
                "standard",
                r.get("standard"),
                "mappings",
                gate.json.readValue(r.get("mappings").toString(), List.class),
                "certifiedRows",
                gate.json.readValue(r.get("certified_rows").toString(), List.class),
                "contentDigest",
                r.get("content_digest"),
                "status",
                r.get("status"),
                "version",
                r.get("version").toString(),
                "templateApproved",
                false,
                "reason",
                r.get("reason"));
    }
}
