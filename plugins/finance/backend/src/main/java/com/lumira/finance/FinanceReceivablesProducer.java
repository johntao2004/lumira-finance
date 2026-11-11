package com.lumira.finance;

import static com.lumira.finance.FinanceReceivablesRules.*;

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
 * Native same-transaction AR accounting/tax decision registry and typed AR_* journal producer.
 * Participates strictly in the caller's Spring transaction; never performs network I/O or REQUIRES_NEW.
 */
@Service
public final class FinanceReceivablesProducer extends FinanceProfitClosingAccess
        implements FinanceReceivablesDependencies {
    private static final Set<String> DECISION_KINDS =
            Set.of("RECOGNITION", "RECEIPT", "ALLOCATION", "REVERSAL");
    private static final Set<String> CREATE_KEYS =
            Set.of(
                    "idempotencyKey",
                    "kind",
                    "policyVersion",
                    "accountingDate",
                    "gross",
                    "net",
                    "taxAmount",
                    "taxEntityId",
                    "taxTreatment",
                    "taxRuleVersion",
                    "basisOriginalId",
                    "lines",
                    "reason");
    private static final Set<String> APPROVE_KEYS =
            Set.of("idempotencyKey", "expectedVersion", "reason");
    private static final Set<String> LINE_KEYS =
            Set.of("accountId", "role", "debit", "credit");

    public FinanceReceivablesProducer(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager tx,
            AuthorizationContextProjectionPort authorization) {
        super(db, finance, gate, tx, authorization);
    }

    private String hashValue(Object value) {
        return FinanceService.hash(finance.encode(finance.canonical(value)));
    }

    private UUID decisionIdFromRequest(
            FinanceReceivablesRules.Kind kind, Map<String, Object> request) {
        String key =
                switch (kind) {
                    case RECOGNITION -> "decisionId";
                    case RECEIPT -> "receiptDecisionId";
                    case ALLOCATION -> "allocationDecisionId";
                    case REVERSAL -> "correctionDecisionId";
                    default -> throw error("INVALID_INPUT", "非财务应收类型不解析会计决定");
                };
        return id(request.get(key));
    }

    private String nativeTypeFor(String kind) {
        return switch (kind) {
            case "RECOGNITION" -> "AR_REVENUE";
            case "RECEIPT" -> "AR_RECEIPT";
            case "ALLOCATION" -> "AR_ALLOCATION";
            case "REVERSAL" -> "AR_REVERSAL";
            default -> throw error("INVALID_INPUT", "不支持的应收决定类型");
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public Plan resolve(
            Scope scope,
            FinanceReceivablesRules.Kind kind,
            Map<String, Object> request,
            Map<String, Object> verifiedSources) {
        UUID decisionId = decisionIdFromRequest(kind, request);
        var rows =
                db.list(
                        "SELECT * FROM plugin_finance_ar_decision WHERE tenant_id=? AND book_id=?"
                                + " AND year_id=? AND id=? FOR SHARE",
                        scope.tenantId(),
                        scope.bookId(),
                        scope.yearId(),
                        decisionId);
        if (rows.isEmpty())
            throw error("AR_DECISION_UNAVAILABLE", "未找到已批准的应收会计与税务判断记录");
        var d = rows.getFirst();
        if (!"APPROVED".equals(d.get("status")) || !kind.name().equals(d.get("kind")))
            throw error("AR_DECISION_UNAVAILABLE", "应收会计判断尚未独立批准或类型不匹配");
        Instant expiresAt = ((Timestamp) d.get("expires_at")).toInstant();
        if (!expiresAt.isAfter(Instant.now()))
            throw error("AR_DECISION_UNAVAILABLE", "应收会计判断批准已过期");
        LocalDate accountingDate = LocalDate.parse(d.get("accounting_date").toString());
        BigDecimal gross = new BigDecimal(d.get("gross").toString()).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal net = new BigDecimal(d.get("net").toString()).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal taxAmount =
                new BigDecimal(d.get("tax_amount").toString()).setScale(2, RoundingMode.UNNECESSARY);
        UUID approvedByPerson = id(json(d.get("approved_by")).get("personId"));

        var rawLines =
                gate.json.readValue(d.get("lines").toString(), List.class);
        List<Line> lines = new ArrayList<>();
        for (Object item : rawLines) {
            var m = (Map<String, Object>) item;
            UUID accountId = id(m.get("accountId"));
            verifyAccount(scope.tenantId(), scope.bookId(), scope.yearId(), accountId);
            lines.add(
                    new Line(
                            accountId,
                            1L,
                            AccountRole.valueOf(m.get("role").toString()),
                            new BigDecimal(m.get("debit").toString()).setScale(2, RoundingMode.UNNECESSARY),
                            new BigDecimal(m.get("credit").toString()).setScale(2, RoundingMode.UNNECESSARY)));
        }

        if (kind == FinanceReceivablesRules.Kind.REVERSAL) {
            var origMap = (Map<String, Object>) verifiedSources.get("originalPlan");
            Plan origPlan = gate.json.readValue(finance.encode(origMap), Plan.class);
            if (lines.size() != origPlan.lines().size())
                throw error("AR_REVERSAL_LINES_MISMATCH", "更正决定分录行数与原已记账对象不一致");
            for (int i = 0; i < lines.size(); i++) {
                Line cur = lines.get(i), old = origPlan.lines().get(i);
                if (!cur.accountId().equals(old.accountId())
                        || cur.role() != old.role()
                        || cur.debit().compareTo(old.credit()) != 0
                        || cur.credit().compareTo(old.debit()) != 0)
                    throw error("AR_REVERSAL_LINES_MISMATCH", "更正决定分录借贷方向或科目与原已记账对象不匹配");
            }
        }

        TaxFact taxFact = null;
        if (kind == FinanceReceivablesRules.Kind.RECOGNITION) {
            UUID taxEntityId = id(d.get("tax_entity_id"));
            UUID taxEntityRevId = id(d.get("tax_entity_revision_id"));
            verifyTaxEntityRevision(scope.tenantId(), scope.legalEntityId(), taxEntityId, taxEntityRevId);
            UUID basisOriginalId = id(d.get("basis_original_id"));
            verifyOriginal(scope.tenantId(), scope.bookId(), scope.yearId(), basisOriginalId);
            TaxTreatment treatment = TaxTreatment.valueOf(d.get("tax_treatment").toString());
            String ruleVer = d.get("tax_rule_version").toString();
            String taxDigest =
                    hashValue(
                            List.of(
                                    decisionId.toString(),
                                    taxEntityId.toString(),
                                    taxEntityRevId.toString(),
                                    treatment.name(),
                                    taxAmount.toPlainString(),
                                    ruleVer,
                                    basisOriginalId.toString()));
            taxFact =
                    new TaxFact(
                            decisionId,
                            ((Number) d.get("version")).longValue(),
                            taxEntityId,
                            taxEntityRevId,
                            treatment,
                            taxAmount,
                            ruleVer,
                            basisOriginalId,
                            approvedByPerson,
                            taxDigest);
        }

        return new Plan(
                decisionId,
                ((Number) d.get("version")).longValue(),
                approvedByPerson,
                expiresAt,
                d.get("content_digest").toString(),
                d.get("policy_version").toString(),
                nativeTypeFor(kind.name()),
                accountingDate,
                gross,
                net,
                taxFact,
                lines,
                Map.of(
                        "recognitionClass",
                        "UNCONDITIONAL_RECEIVABLE",
                        "verificationMode",
                        "INTERNAL_MANUAL_RECEIPT"));
    }

    @Override
    public void revalidate(Scope scope, Plan plan, Map<String, Object> verifiedSources) {
        var rows =
                db.list(
                        "SELECT * FROM plugin_finance_ar_decision WHERE tenant_id=? AND book_id=?"
                                + " AND year_id=? AND id=? FOR SHARE",
                        scope.tenantId(),
                        scope.bookId(),
                        scope.yearId(),
                        plan.decisionId());
        if (rows.isEmpty()) throw error("AR_DECISION_UNAVAILABLE", "应收会计判断不存在");
        var d = rows.getFirst();
        if (!"APPROVED".equals(d.get("status"))
                || ((Number) d.get("version")).longValue() != plan.decisionVersion()
                || !plan.decisionDigest().equals(d.get("content_digest"))
                || !((Timestamp) d.get("expires_at")).toInstant().isAfter(Instant.now()))
            throw error("AR_DECISION_UNAVAILABLE", "应收会计判断已失效或过期");
        for (Line line : plan.lines()) {
            verifyAccount(scope.tenantId(), scope.bookId(), scope.yearId(), line.accountId());
        }
        if (plan.taxFact() != null) {
            verifyTaxEntityRevision(
                    scope.tenantId(),
                    scope.legalEntityId(),
                    plan.taxFact().taxEntityId(),
                    plan.taxFact().taxEntityRevisionId());
            verifyOriginal(
                    scope.tenantId(),
                    scope.bookId(),
                    scope.yearId(),
                    plan.taxFact().basisOriginalId());
        }
    }

    @Override
    public Posted post(Scope scope, Producer producer, Actor poster) {
        var existingEffect =
                db.list(
                        "SELECT id FROM plugin_finance_ar_effect WHERE tenant_id=? AND"
                                + " effect_key=?",
                        scope.tenantId(),
                        producer.effectKey());
        if (!existingEffect.isEmpty())
            throw error("AR_DUPLICATE_EFFECT", "应收稳定业务效果键已存在");

        var periodRows =
                db.list(
                        "SELECT id FROM plugin_finance_period WHERE tenant_id=? AND book_id=? AND"
                                + " year_id=? AND month=? AND status='OPEN' FOR UPDATE",
                        scope.tenantId(),
                        scope.bookId(),
                        scope.yearId(),
                        producer.plan().accountingDate().getMonthValue());
        if (periodRows.isEmpty()) throw error("PERIOD_CLOSED", "应收记账期间已关闭或不存在");
        UUID periodId = id(periodRows.getFirst().get("id"));
        UUID journalId = UUID.randomUUID();
        String summary = "AR:" + producer.nativeType() + ":" + producer.id();

        db.update(
                "INSERT INTO"
                    + " plugin_finance_journal(tenant_id,book_id,year_id,id,period_id,date,summary,type,status,version,created_by,amount_format_version)"
                    + " VALUES(?,?,?,?,?,?,?,?,'DRAFT',1,?,'V4_8')",
                scope.tenantId(),
                scope.bookId(),
                scope.yearId(),
                journalId,
                periodId,
                producer.plan().accountingDate(),
                summary,
                producer.nativeType(),
                producer.preparedBy().accountId());

        int lineNo = 1;
        for (Line line : producer.plan().lines()) {
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit,memo)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)",
                    scope.tenantId(),
                    scope.bookId(),
                    scope.yearId(),
                    journalId,
                    lineNo++,
                    line.accountId(),
                    line.debit(),
                    line.credit(),
                    line.role().name());
        }

        db.update(
                "UPDATE plugin_finance_journal SET"
                    + " status='APPROVED',version=2,approved_by=?,approved_version=2 WHERE"
                    + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                producer.approvedBy().accountId(),
                scope.tenantId(),
                scope.bookId(),
                scope.yearId(),
                journalId);

        long seq =
                ((Number)
                                db.one(
                                                "UPDATE plugin_finance_year_guard SET"
                                                    + " posting_seq=posting_seq+1 WHERE"
                                                    + " tenant_id=? AND book_id=? AND year_id=?"
                                                    + " RETURNING posting_seq",
                                                scope.tenantId(),
                                                scope.bookId(),
                                                scope.yearId())
                                        .get("posting_seq"))
                        .longValue();
        long journalNo =
                ((Number)
                                db.one(
                                                "SELECT COALESCE(MAX(number),0)+1 AS n FROM"
                                                    + " plugin_finance_journal WHERE tenant_id=?"
                                                    + " AND book_id=? AND year_id=?",
                                                scope.tenantId(),
                                                scope.bookId(),
                                                scope.yearId())
                                        .get("n"))
                        .longValue();

        db.update(
                "INSERT INTO"
                    + " plugin_finance_posting(tenant_id,book_id,year_id,journal_id,journal_version,posting_seq,digest,posted_by)"
                    + " VALUES(?,?,?,?,2,?,?,?)",
                scope.tenantId(),
                scope.bookId(),
                scope.yearId(),
                journalId,
                seq,
                producer.contentDigest(),
                poster.accountId());

        db.update(
                "UPDATE plugin_finance_journal SET"
                    + " status='POSTED',version=3,number=?,reversal_of=? WHERE tenant_id=? AND"
                    + " book_id=? AND year_id=? AND id=?",
                journalNo,
                producer.reversesJournalId(),
                scope.tenantId(),
                scope.bookId(),
                scope.yearId(),
                journalId);

        UUID effectId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " plugin_finance_ar_effect(tenant_id,book_id,year_id,id,object_id,business_chain_id,stable_consumer,native_type,effect_key,content_digest,journal_id,posting_seq)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                scope.tenantId(),
                scope.bookId(),
                scope.yearId(),
                effectId,
                producer.id(),
                producer.businessChainId(),
                producer.stableConsumer(),
                producer.nativeType(),
                producer.effectKey(),
                producer.contentDigest(),
                journalId,
                seq);
        db.update(
                "INSERT INTO"
                    + " plugin_finance_ar_effect_receipt(tenant_id,id,effect_id,stable_consumer,effect_key,content_digest)"
                    + " VALUES(?,?,?,?,?,?)",
                scope.tenantId(),
                receiptId,
                effectId,
                producer.stableConsumer(),
                producer.effectKey(),
                producer.contentDigest());

        UUID outboxId = UUID.randomUUID();
        Map<String, Object> bookMap =
                Map.of("tenant_id", scope.tenantId(), "id", scope.bookId());
        var snap = FinanceOutbox.snapshot(db, bookMap, scope.yearId(), journalId);
        String encodedOutbox = finance.encode(finance.canonical(snap));
        db.update(
                "INSERT INTO"
                    + " plugin_finance_outbox(tenant_id,book_id,year_id,id,event_type,aggregate_id,aggregate_version,payload,payload_digest,created_by)"
                    + " VALUES(?,?,?,?,?,?,?,?::jsonb,?,?)",
                scope.tenantId(),
                scope.bookId(),
                scope.yearId(),
                outboxId,
                "JOURNAL_POSTED",
                journalId,
                snap.get("sourceVersion"),
                encodedOutbox,
                FinanceService.hash(encodedOutbox),
                poster.accountId());

        return new Posted(
                journalId,
                effectId,
                receiptId,
                outboxId,
                seq,
                producer.contentDigest(),
                producer.nativeType());
    }

    @Override
    public void verifyPosted(Scope scope, Producer producer, Posted posted) {
        var j =
                db.one(
                        "SELECT j.status,j.type,j.version,p.posting_seq,p.digest FROM"
                            + " plugin_finance_journal j JOIN plugin_finance_posting p ON"
                            + " (p.tenant_id,p.book_id,p.year_id,p.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id)"
                            + " WHERE j.tenant_id=? AND j.book_id=? AND j.year_id=? AND j.id=?",
                        scope.tenantId(),
                        scope.bookId(),
                        scope.yearId(),
                        posted.journalId());
        if (!"POSTED".equals(j.get("status"))
                || !producer.nativeType().equals(j.get("type"))
                || ((Number) j.get("version")).longValue() != 3L
                || ((Number) j.get("posting_seq")).longValue() != posted.postingSequence()
                || !producer.contentDigest().equals(j.get("digest")))
            throw error("AR_POSTING_PROOF_MISSING", "应收原生凭证记账事实核对不符");

        var actualLines =
                db.list(
                        "SELECT account_id,debit,credit,memo FROM plugin_finance_line WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=? AND journal_id=? ORDER BY"
                            + " line_no",
                        scope.tenantId(),
                        scope.bookId(),
                        scope.yearId(),
                        posted.journalId());
        if (actualLines.size() != producer.plan().lines().size())
            throw error("AR_POSTING_PROOF_MISSING", "应收原生凭证分录行数不符");
        for (int i = 0; i < actualLines.size(); i++) {
            var actual = actualLines.get(i);
            var expected = producer.plan().lines().get(i);
            if (!expected.accountId().equals(actual.get("account_id"))
                    || expected.debit().compareTo(new BigDecimal(actual.get("debit").toString()))
                            != 0
                    || expected.credit().compareTo(new BigDecimal(actual.get("credit").toString()))
                            != 0
                    || !expected.role().name().equals(actual.get("memo")))
                throw error("AR_POSTING_PROOF_MISSING", "应收原生凭证分录金额或角色不符");
        }

        var eff =
                db.list(
                        "SELECT e.id FROM plugin_finance_ar_effect e JOIN"
                            + " plugin_finance_ar_effect_receipt r ON"
                            + " (r.tenant_id,r.effect_id,r.stable_consumer,r.effect_key,r.content_digest)=(e.tenant_id,e.id,e.stable_consumer,e.effect_key,e.content_digest)"
                            + " WHERE e.tenant_id=? AND e.id=? AND r.id=? AND e.journal_id=? AND"
                            + " e.effect_key=?",
                        scope.tenantId(),
                        posted.businessEffectId(),
                        posted.consumptionReceiptId(),
                        posted.journalId(),
                        producer.effectKey());
        if (eff.isEmpty()) throw error("AR_POSTING_PROOF_MISSING", "应收业务效果或消费回执缺失");

        var ob =
                db.list(
                        "SELECT id FROM plugin_finance_outbox WHERE tenant_id=? AND book_id=? AND"
                            + " year_id=? AND id=? AND aggregate_id=? AND"
                            + " event_type='JOURNAL_POSTED'",
                        scope.tenantId(),
                        scope.bookId(),
                        scope.yearId(),
                        posted.outboxId(),
                        posted.journalId());
        if (ob.isEmpty()) throw error("AR_POSTING_PROOF_MISSING", "应收记账Outbox事件缺失");
    }

    private void verifyAccount(UUID tenantId, UUID bookId, UUID yearId, UUID accountId) {
        var rows =
                db.list(
                        "SELECT enabled FROM plugin_finance_account WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND id=?",
                        tenantId,
                        bookId,
                        yearId,
                        accountId);
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("enabled")))
            throw error("AR_ACCOUNT_UNAVAILABLE", "应收分录映射科目不存在或已停用");
    }

    private UUID verifyTaxEntityActive(UUID tenantId, UUID legalEntityId, UUID taxEntityId) {
        var rows =
                db.list(
                        "SELECT g.approved_revision_id,g.pending_revision_id,r.desired_status,r.status"
                            + " FROM plugin_finance_tax_entity_registry g JOIN"
                            + " plugin_finance_tax_entity_revision r ON"
                            + " (r.tenant_id,r.legal_entity_id,r.tax_entity_id,r.id)=(g.tenant_id,g.legal_entity_id,g.id,g.approved_revision_id)"
                            + " WHERE g.tenant_id=? AND g.legal_entity_id=? AND g.id=?",
                        tenantId,
                        legalEntityId,
                        taxEntityId);
        if (rows.isEmpty()
                || rows.getFirst().get("pending_revision_id") != null
                || !"ACTIVE".equals(rows.getFirst().get("desired_status"))
                || !"APPROVED".equals(rows.getFirst().get("status")))
            throw error("AR_TAX_FACT_UNAVAILABLE", "纳税主体不存在、存在待决修订或未处于激活批准状态");
        return id(rows.getFirst().get("approved_revision_id"));
    }

    private void verifyTaxEntityRevision(
            UUID tenantId, UUID legalEntityId, UUID taxEntityId, UUID revisionId) {
        UUID currentRev = verifyTaxEntityActive(tenantId, legalEntityId, taxEntityId);
        if (!currentRev.equals(revisionId))
            throw error("AR_TAX_FACT_UNAVAILABLE", "纳税主体批准修订已变化，须重新复核税务事实");
    }

    private void verifyOriginal(UUID tenantId, UUID bookId, UUID yearId, UUID originalId) {
        var rows =
                db.list(
                        "SELECT id FROM plugin_finance_original WHERE tenant_id=? AND book_id=?"
                                + " AND year_id=? AND id=? AND sha256=encode(sha256(content),'hex')",
                        tenantId,
                        bookId,
                        yearId,
                        originalId);
        if (rows.isEmpty()) throw error("AR_ORIGINAL_INVALID", "应收会计判断依据原件不存在或摘要不符");
    }

    private Map<String, Object> decisionView(Map<String, Object> r) {
        return map(
                "id",
                r.get("id").toString(),
                "kind",
                r.get("kind"),
                "status",
                r.get("status"),
                "version",
                r.get("version").toString(),
                "policyVersion",
                r.get("policy_version"),
                "accountingDate",
                r.get("accounting_date").toString(),
                "gross",
                stored(new BigDecimal(r.get("gross").toString())),
                "net",
                stored(new BigDecimal(r.get("net").toString())),
                "taxAmount",
                stored(new BigDecimal(r.get("tax_amount").toString())),
                "taxEntityId",
                r.get("tax_entity_id") == null ? null : r.get("tax_entity_id").toString(),
                "taxTreatment",
                r.get("tax_treatment"),
                "basisOriginalId",
                r.get("basis_original_id").toString(),
                "contentDigest",
                r.get("content_digest"),
                "expiresAt",
                r.get("expires_at") == null ? null : ((Timestamp) r.get("expires_at")).toInstant().toString());
    }

    public Object listDecisions(String principal, UUID book, UUID year) {
        return transaction(
                false,
                c -> {
                    var b = c.scope(principal, book, year, null);
                    var rows =
                            c.list(
                                    "SELECT * FROM plugin_finance_ar_decision WHERE tenant_id=?"
                                            + " AND book_id=? AND year_id=? ORDER BY created_at"
                                            + " DESC,id DESC LIMIT 100",
                                    b.get("tenant_id"),
                                    book,
                                    year);
                    return map(
                            "items",
                            rows.stream().map(this::decisionView).toList());
                });
    }

    @SuppressWarnings("unchecked")
    public Object createDecision(
            String principal,
            UUID book,
            UUID year,
            Map<String, Object> raw,
            HttpServletRequest request) {
        if (raw == null || !raw.keySet().equals(CREATE_KEYS))
            throw FinanceException.invalid("应收会计判断请求字段无效");
        String reqKey;
        String kindStr;
        String policyVersion;
        LocalDate accountingDate;
        BigDecimal gross;
        BigDecimal net;
        BigDecimal taxAmount;
        String reason;
        UUID basisOriginalId;
        try {
            reqKey = text(raw.get("idempotencyKey"), 128);
            kindStr = text(raw.get("kind"), 30);
            if (!DECISION_KINDS.contains(kindStr)) throw new Rejected("INVALID_INPUT");
            policyVersion = text(raw.get("policyVersion"), 80);
            accountingDate = date(raw.get("accountingDate"));
            gross = money(raw.get("gross"), false).setScale(2, RoundingMode.UNNECESSARY);
            net = money(raw.get("net"), true).setScale(2, RoundingMode.UNNECESSARY);
            taxAmount = money(raw.get("taxAmount"), true).setScale(2, RoundingMode.UNNECESSARY);
            reason = text(raw.get("reason"), 1000);
            basisOriginalId = uuid(raw.get("basisOriginalId"));
        } catch (Rejected ex) {
            throw error(ex.code, "应收会计判断参数或金额格式无效");
        }
        if (net.add(taxAmount).compareTo(gross) != 0)
            throw FinanceException.invalid("应收含税总额必须等于不含税净额加税额");
        if (!(raw.get("lines") instanceof List<?> rawList)
                || rawList.size() < 2
                || rawList.size() > 100)
            throw FinanceException.invalid("应收会计判断分录行数必须在2至100之间");

        return transaction(
                true,
                c -> {
                    var b = c.locked(principal, book, year, "ar-decision-create", reqKey);
                    if (!allowed(b, "create")) throw error("SCOPE_DENIED", "当前岗位无权创建应收会计判断");
                    var who = gate.current(id(b.get("actor_id")), id(b.get("tenant_id")), false, request);
                    UUID tenantId = id(b.get("tenant_id"));
                    UUID legalEntityId = id(b.get("legal_entity_id"));
                    var payload = new LinkedHashMap<>(raw);
                    payload.remove("idempotencyKey");
                    String payloadHash = hashValue(payload);
                    String route = "ar-decision-create/" + book + "/" + year;
                    var cached =
                            c.list(
                                    "SELECT * FROM plugin_finance_v4_governance_request WHERE"
                                            + " actor_id=? AND request_key=?",
                                    b.get("actor_id"),
                                    reqKey);
                    if (!cached.isEmpty()) {
                        var old = cached.getFirst();
                        if (!route.equals(old.get("route"))
                                || !payloadHash.equals(old.get("payload_hash")))
                            throw error("IDEMPOTENCY_CONFLICT", "同幂等键正文或路由不一致");
                        return json(old.get("response"));
                    }

                    verifyOriginal(tenantId, book, year, basisOriginalId);
                    var normalizedLines = new ArrayList<Map<String, Object>>();
                    BigDecimal debitSum = BigDecimal.ZERO, creditSum = BigDecimal.ZERO;
                    for (Object item : rawList) {
                        if (!(item instanceof Map<?, ?> m) || !m.keySet().equals(LINE_KEYS))
                            throw FinanceException.invalid("应收分录行字段无效");
                        UUID accountId;
                        AccountRole role;
                        BigDecimal debit;
                        BigDecimal credit;
                        try {
                            accountId = uuid(m.get("accountId"));
                            role = AccountRole.valueOf(text(m.get("role"), 40));
                            debit = money(m.get("debit"), true).setScale(2, RoundingMode.UNNECESSARY);
                            credit = money(m.get("credit"), true).setScale(2, RoundingMode.UNNECESSARY);
                        } catch (RuntimeException ex) {
                            throw FinanceException.invalid("应收分录行科目、角色或金额无效");
                        }
                        if (!(debit.signum() > 0 ^ credit.signum() > 0))
                            throw FinanceException.invalid("单条分录必须单边大于0");
                        verifyAccount(tenantId, book, year, accountId);
                        debitSum = debitSum.add(debit);
                        creditSum = creditSum.add(credit);
                        normalizedLines.add(
                                map(
                                        "accountId",
                                        accountId.toString(),
                                        "role",
                                        role.name(),
                                        "debit",
                                        debit.toPlainString(),
                                        "credit",
                                        credit.toPlainString()));
                    }
                    if (debitSum.compareTo(creditSum) != 0 || debitSum.compareTo(gross) != 0)
                        throw FinanceException.invalid("应收分录借贷必须平衡且等于总额");

                    UUID taxEntityId = null;
                    UUID taxEntityRevId = null;
                    String taxTreatment = null;
                    String taxRuleVersion = null;
                    if ("RECOGNITION".equals(kindStr)) {
                        try {
                            taxEntityId = uuid(raw.get("taxEntityId"));
                            taxTreatment = TaxTreatment.valueOf(text(raw.get("taxTreatment"), 30)).name();
                            taxRuleVersion = text(raw.get("taxRuleVersion"), 80);
                        } catch (RuntimeException ex) {
                            throw FinanceException.invalid("收入确认必须提供有效的纳税主体、税务处理分类与规则版本");
                        }
                        if (!"TAXABLE".equals(taxTreatment) && taxAmount.signum() != 0)
                            throw FinanceException.invalid("非应税/免税/零税率收入税额必须为0.00");
                        taxEntityRevId = verifyTaxEntityActive(tenantId, legalEntityId, taxEntityId);
                    } else if (raw.get("taxEntityId") != null
                            || raw.get("taxTreatment") != null
                            || raw.get("taxRuleVersion") != null
                            || taxAmount.signum() != 0) {
                        throw FinanceException.invalid("非收入确认决定不得附带税额或税务分类");
                    }

                    UUID decisionId = UUID.randomUUID();
                    String digest =
                            hashValue(
                                    map(
                                            "kind",
                                            kindStr,
                                            "policyVersion",
                                            policyVersion,
                                            "accountingDate",
                                            accountingDate.toString(),
                                            "gross",
                                            gross.toPlainString(),
                                            "net",
                                            net.toPlainString(),
                                            "taxAmount",
                                            taxAmount.toPlainString(),
                                            "taxEntityId",
                                            taxEntityId == null ? null : taxEntityId.toString(),
                                            "taxEntityRevisionId",
                                            taxEntityRevId == null ? null : taxEntityRevId.toString(),
                                            "taxTreatment",
                                            taxTreatment,
                                            "taxRuleVersion",
                                            taxRuleVersion,
                                            "basisOriginalId",
                                            basisOriginalId.toString(),
                                            "lines",
                                            normalizedLines));
                    c.update(
                            "INSERT INTO"
                                + " plugin_finance_ar_decision(tenant_id,legal_entity_id,book_id,year_id,id,kind,policy_version,accounting_date,gross,net,tax_amount,tax_entity_id,tax_entity_revision_id,tax_treatment,tax_rule_version,basis_original_id,lines,content_digest,prepared_by,reason)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?)",
                            tenantId,
                            legalEntityId,
                            book,
                            year,
                            decisionId,
                            kindStr,
                            policyVersion,
                            accountingDate,
                            gross,
                            net,
                            taxAmount,
                            taxEntityId,
                            taxEntityRevId,
                            taxTreatment,
                            taxRuleVersion,
                            basisOriginalId,
                            finance.encode(normalizedLines),
                            digest,
                            finance.encode(who),
                            reason);
                    var response =
                            decisionView(
                                    c.one(
                                            "SELECT * FROM plugin_finance_ar_decision WHERE"
                                                    + " tenant_id=? AND book_id=? AND year_id=?"
                                                    + " AND id=?",
                                            tenantId,
                                            book,
                                            year,
                                            decisionId));
                    c.update(
                            "INSERT INTO"
                                + " plugin_finance_v4_governance_request(actor_id,request_key,tenant_id,route,payload_hash,response)"
                                + " VALUES(?,?,?,?,?,?::jsonb)",
                            b.get("actor_id"),
                            reqKey,
                            tenantId,
                            route,
                            payloadHash,
                            finance.encode(response));
                    return response;
                });
    }

    public Object approveDecision(
            String principal,
            UUID book,
            UUID year,
            UUID decisionId,
            Map<String, Object> raw,
            HttpServletRequest request) {
        if (raw == null || !raw.keySet().equals(APPROVE_KEYS))
            throw FinanceException.invalid("应收会计判断审签请求字段无效");
        String reqKey;
        long expectedVersion;
        String reason;
        try {
            reqKey = text(raw.get("idempotencyKey"), 128);
            expectedVersion = version(raw.get("expectedVersion"));
            reason = text(raw.get("reason"), 1000);
        } catch (Rejected ex) {
            throw error(ex.code, "应收会计判断审签参数无效");
        }
        return transaction(
                true,
                c -> {
                    var b = c.locked(principal, book, year, "approve", reqKey);
                    if (!allowed(b, "approve")) throw error("SCOPE_DENIED", "当前岗位无权审签应收会计判断");
                    var who = gate.current(id(b.get("actor_id")), id(b.get("tenant_id")), true, request);
                    UUID tenantId = id(b.get("tenant_id"));
                    var payload = new LinkedHashMap<>(raw);
                    payload.remove("idempotencyKey");
                    String payloadHash = hashValue(payload);
                    String route = "ar-decision-approve/" + book + "/" + year + "/" + decisionId;
                    var cached =
                            c.list(
                                    "SELECT * FROM plugin_finance_v4_governance_request WHERE"
                                            + " actor_id=? AND request_key=?",
                                    b.get("actor_id"),
                                    reqKey);
                    if (!cached.isEmpty()) {
                        var old = cached.getFirst();
                        if (!route.equals(old.get("route"))
                                || !payloadHash.equals(old.get("payload_hash")))
                            throw error("IDEMPOTENCY_CONFLICT", "同幂等键正文或路由不一致");
                        return json(old.get("response"));
                    }
                    var row =
                            c.one(
                                    "SELECT * FROM plugin_finance_ar_decision WHERE tenant_id=? AND"
                                            + " book_id=? AND year_id=? AND id=? FOR UPDATE",
                                    tenantId,
                                    book,
                                    year,
                                    decisionId);
                    if (((Number) row.get("version")).longValue() != expectedVersion)
                        throw error("VERSION_CONFLICT", "应收会计判断版本不匹配");
                    if (!"PENDING".equals(row.get("status")))
                        throw error("AR_STATE_CONFLICT", "应收会计判断已处于终态");
                    independent(who, json(row.get("prepared_by")));
                    c.update(
                            "UPDATE plugin_finance_ar_decision SET"
                                + " status='APPROVED',version=2,approved_by=?::jsonb,expires_at=CURRENT_TIMESTAMP+interval"
                                + " '7 days',decision_reason=?,decided_at=CURRENT_TIMESTAMP WHERE"
                                + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            finance.encode(who),
                            reason,
                            tenantId,
                            book,
                            year,
                            decisionId);
                    var response =
                            decisionView(
                                    c.one(
                                            "SELECT * FROM plugin_finance_ar_decision WHERE"
                                                    + " tenant_id=? AND book_id=? AND year_id=?"
                                                    + " AND id=?",
                                            tenantId,
                                            book,
                                            year,
                                            decisionId));
                    c.update(
                            "INSERT INTO"
                                + " plugin_finance_v4_governance_request(actor_id,request_key,tenant_id,route,payload_hash,response)"
                                + " VALUES(?,?,?,?,?,?::jsonb)",
                            b.get("actor_id"),
                            reqKey,
                            tenantId,
                            route,
                            payloadHash,
                            finance.encode(response));
                    return response;
                });
    }
}
