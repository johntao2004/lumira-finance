package com.lumira.finance;

import static com.lumira.finance.tax.TaxObligationModel.*;

import com.lumira.finance.tax.TaxObligationModel;
import com.lumira.platform.common.AuthorizationContextProjectionPort;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Production PostgreSQL + V19 host adapter for TAX01/TAX05/TAX06 TaxObligationModel.
 * External official filing/payment channels remain NOT_VERIFIED / NOT_ASSESSED.
 */
@Service
public final class FinanceTaxObligations extends FinanceProfitClosingAccess {

    public record BookBindingInput(
            UUID taxEntityId,
            String sourceRole,
            String mappingVersion,
            LocalDate effectiveFrom,
            String idempotencyKey) {}

    public record ApproveBindingInput(long expectedVersion, String idempotencyKey) {}

    public record SourceRefInput(UUID bookId, UUID yearId) {}

    public record BuildObligationInput(
            UUID taxEntityId,
            String authority,
            String filingUnit,
            Role role,
            LocalDate periodStart,
            LocalDate periodEnd,
            String occurrence,
            String scopeCode,
            Kind kind,
            UUID predecessorDeclarationId,
            UUID formVersion,
            List<UUID> rules,
            List<UUID> adjustments,
            String worksheetDigest,
            List<SourceRefInput> sources,
            String reason,
            String idempotencyKey) {}

    public record TransitionInput(
            long expectedObligationVersion, String reason, String idempotencyKey) {}

    public record ReconcileReceiptInput(
            long expectedObligationVersion,
            UUID receiptYearId,
            UUID receiptOriginalId,
            String providerReference,
            Outcome outcome,
            String evidenceRef,
            String idempotencyKey) {}

    public record CreateWorksheetInput(
            UUID yearId,
            UUID taxEntityId,
            String worksheetCode,
            String kind,
            LocalDate periodStart,
            LocalDate periodEnd,
            String ruleVersion,
            UUID basisOriginalId,
            Map<String, Object> inputs,
            String reason,
            String idempotencyKey) {}

    public record ApproveWorksheetInput(
            UUID yearId, long expectedVersion, String reason, String idempotencyKey) {}

    public FinanceTaxObligations(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager tx,
            AuthorizationContextProjectionPort authorization) {
        super(db, finance, gate, tx, authorization);
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}"))
            throw FinanceException.invalid("幂等请求键格式无效");
    }

    private void verifyActiveTaxEntity(Call c, UUID tenant, UUID legalEntity, UUID taxEntityId) {
        var rows =
                c.list(
                        "SELECT g.approved_revision_id,r.desired_status,r.status "
                            + "FROM plugin_finance_tax_entity_registry g "
                            + "JOIN plugin_finance_tax_entity_revision r "
                            + "ON (r.tenant_id,r.legal_entity_id,r.tax_entity_id,r.id)=(g.tenant_id,g.legal_entity_id,g.id,g.approved_revision_id) "
                            + "WHERE g.tenant_id=? AND g.legal_entity_id=? AND g.id=?",
                        tenant,
                        legalEntity,
                        taxEntityId);
        if (rows.isEmpty()
                || !"ACTIVE".equals(rows.getFirst().get("desired_status"))
                || !"APPROVED".equals(rows.getFirst().get("status")))
            throw error("TAX_ENTITY_INACTIVE", "纳税主体不存在、不属于当前法人或尚未独立确认激活");
    }

    public Object list(String principal, UUID book) {
        return transaction(
                false,
                c -> {
                    var b = finance.originalBookScope(principal, book, "read");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID legalEntity = id(b.get("legal_entity_id"));
                    var bindings =
                            c.list(
                                    "SELECT tax_entity_id,book_id,source_role,mapping_version,effective_from,status,version "
                                        + "FROM plugin_finance_tax_entity_book_auth "
                                        + "WHERE tenant_id=? AND legal_entity_id=? ORDER BY created_at DESC",
                                    tenant,
                                    legalEntity);
                    var obligations =
                            c.list(
                                    "SELECT * FROM plugin_finance_tax_obligation "
                                        + "WHERE tenant_id=? AND legal_entity_id=? ORDER BY created_at DESC, id DESC LIMIT 50",
                                    tenant,
                                    legalEntity);
                    List<Map<String, Object>> obItems = new ArrayList<>();
                    for (var ob : obligations) {
                        UUID obId = id(ob.get("id"));
                        var decls =
                                c.list(
                                        "SELECT id,revision,version,kind,predecessor_id,state,data_digest,"
                                            + "active_command_id,receipt_ref,payment_state,official_capability,gaps,reason "
                                            + "FROM plugin_finance_tax_declaration "
                                            + "WHERE tenant_id=? AND obligation_id=? ORDER BY revision DESC",
                                        tenant,
                                        obId);
                        List<Map<String, Object>> declViews = new ArrayList<>();
                        for (var d : decls) {
                            declViews.add(
                                    map(
                                            "id",
                                            d.get("id").toString(),
                                            "revision",
                                            d.get("revision").toString(),
                                            "version",
                                            d.get("version").toString(),
                                            "kind",
                                            d.get("kind"),
                                            "predecessorId",
                                            d.get("predecessor_id") == null
                                                    ? null
                                                    : d.get("predecessor_id").toString(),
                                            "state",
                                            d.get("state"),
                                            "dataDigest",
                                            d.get("data_digest"),
                                            "activeCommandId",
                                            d.get("active_command_id") == null
                                                    ? null
                                                    : d.get("active_command_id").toString(),
                                            "receiptRef",
                                            d.get("receipt_ref"),
                                            "paymentState",
                                            d.get("payment_state"),
                                            "officialCapability",
                                            d.get("official_capability"),
                                            "reason",
                                            d.get("reason")));
                        }
                        obItems.add(
                                map(
                                        "id",
                                        obId.toString(),
                                        "taxEntityId",
                                        ob.get("tax_entity_id").toString(),
                                        "authority",
                                        ob.get("authority"),
                                        "filingUnit",
                                        ob.get("filing_unit"),
                                        "role",
                                        ob.get("role"),
                                        "periodStart",
                                        ob.get("period_start").toString(),
                                        "periodEnd",
                                        ob.get("period_end").toString(),
                                        "occurrence",
                                        ob.get("occurrence"),
                                        "scopeCode",
                                        ob.get("scope_code"),
                                        "obligationStatus",
                                        ob.get("obligation_status"),
                                        "version",
                                        ob.get("version").toString(),
                                        "acceptedDeclarationId",
                                        ob.get("accepted_declaration_id") == null
                                                ? null
                                                : ob.get("accepted_declaration_id").toString(),
                                        "declarations",
                                        declViews));
                    }
                    List<Map<String, Object>> bindViews = new ArrayList<>();
                    for (var bd : bindings) {
                        bindViews.add(
                                map(
                                        "taxEntityId",
                                        bd.get("tax_entity_id").toString(),
                                        "bookId",
                                        bd.get("book_id").toString(),
                                        "sourceRole",
                                        bd.get("source_role"),
                                        "mappingVersion",
                                        bd.get("mapping_version"),
                                        "effectiveFrom",
                                        bd.get("effective_from").toString(),
                                        "status",
                                        bd.get("status"),
                                        "version",
                                        bd.get("version").toString()));
                    }
                    return map(
                            "bindings",
                            bindViews,
                            "obligations",
                            obItems,
                            "officialCapability",
                            "NOT_VERIFIED",
                            "paymentState",
                            "NOT_ASSESSED");
                });
    }

    public Object proposeBinding(
            String principal, UUID book, BookBindingInput in, HttpServletRequest request) {
        requireKey(in.idempotencyKey());
        if (in.taxEntityId() == null
                || in.sourceRole() == null
                || !Set.of("STATUTORY_SOURCE", "AUTHORIZED_ADJUSTMENT_SOURCE", "READ_ONLY_REFERENCE")
                        .contains(in.sourceRole())
                || in.mappingVersion() == null
                || in.mappingVersion().isBlank()
                || in.mappingVersion().length() > 128
                || in.effectiveFrom() == null)
            throw FinanceException.invalid("纳税主体账套授权参数无效");
        return transaction(
                true,
                c -> {
                    var b = finance.originalBookScope(principal, book, "manage");
                    if (!allowed(b, "manage")) throw error("SCOPE_DENIED", "需要管理权限登记账套税务映射");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID legalEntity = id(b.get("legal_entity_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, false, request);
                    verifyActiveTaxEntity(c, tenant, legalEntity, in.taxEntityId());
                    String op = "BIND_PROPOSE";
                    String resKey = in.taxEntityId() + ":" + book;
                    String pHash =
                            FinanceService.hash(
                                    in.sourceRole()
                                            + ":"
                                            + in.mappingVersion()
                                            + ":"
                                            + in.effectiveFrom());
                    var cached = replay(c, tenant, actorId, op, resKey, in.idempotencyKey(), pHash);
                    if (cached != null) return cached;

                    var existing =
                            c.list(
                                    "SELECT status FROM plugin_finance_tax_entity_book_auth WHERE tenant_id=? AND tax_entity_id=? AND book_id=?",
                                    tenant,
                                    in.taxEntityId(),
                                    book);
                    if (!existing.isEmpty())
                        throw error("TAX_BOOK_BINDING_EXISTS", "该纳税主体与账套授权绑定已存在");
                    c.update(
                            "INSERT INTO plugin_finance_tax_entity_book_auth("
                                + "tenant_id,legal_entity_id,tax_entity_id,book_id,source_role,"
                                + "mapping_version,effective_from,prepared_by,status,version) "
                                + "VALUES(?,?,?,?,?,?,?,?::jsonb,'PENDING',1)",
                            tenant,
                            legalEntity,
                            in.taxEntityId(),
                            book,
                            in.sourceRole(),
                            in.mappingVersion(),
                            in.effectiveFrom(),
                            finance.encode(who));
                    var res =
                            map(
                                    "taxEntityId",
                                    in.taxEntityId().toString(),
                                    "bookId",
                                    book.toString(),
                                    "sourceRole",
                                    in.sourceRole(),
                                    "mappingVersion",
                                    in.mappingVersion(),
                                    "effectiveFrom",
                                    in.effectiveFrom().toString(),
                                    "status",
                                    "PENDING",
                                    "version",
                                    "1");
                    saveReplay(c, tenant, actorId, op, resKey, in.idempotencyKey(), pHash, res);
                    return res;
                });
    }

    public Object approveBinding(
            String principal,
            UUID book,
            UUID taxEntityId,
            ApproveBindingInput in,
            HttpServletRequest request) {
        requireKey(in.idempotencyKey());
        return transaction(
                true,
                c -> {
                    var b = finance.originalBookScope(principal, book, "approve");
                    if (!allowed(b, "approve")) throw error("SCOPE_DENIED", "需要独立审签权限批准账套税务映射");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID legalEntity = id(b.get("legal_entity_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, true, request);
                    verifyActiveTaxEntity(c, tenant, legalEntity, taxEntityId);
                    String op = "BIND_APPROVE";
                    String resKey = taxEntityId + ":" + book;
                    String pHash = FinanceService.hash("approve:" + in.expectedVersion());
                    var cached = replay(c, tenant, actorId, op, resKey, in.idempotencyKey(), pHash);
                    if (cached != null) return cached;

                    var row =
                            c.one(
                                    "SELECT * FROM plugin_finance_tax_entity_book_auth WHERE tenant_id=? AND tax_entity_id=? AND book_id=? FOR UPDATE",
                                    tenant,
                                    taxEntityId,
                                    book);
                    if (((Number) row.get("version")).longValue() != in.expectedVersion())
                        throw error("VERSION_CONFLICT", "账套税务映射版本冲突");
                    if (!"PENDING".equals(row.get("status")))
                        throw error("TAX_STATE_CONFLICT", "账套税务映射非待审状态");
                    independent(who, json(row.get("prepared_by")));

                    c.update(
                            "UPDATE plugin_finance_tax_entity_book_auth SET status='APPROVED',version=2,approved_by=?::jsonb "
                                + "WHERE tenant_id=? AND tax_entity_id=? AND book_id=?",
                            finance.encode(who),
                            tenant,
                            taxEntityId,
                            book);
                    var res =
                            map(
                                    "taxEntityId",
                                    taxEntityId.toString(),
                                    "bookId",
                                    book.toString(),
                                    "sourceRole",
                                    row.get("source_role"),
                                    "mappingVersion",
                                    row.get("mapping_version"),
                                    "effectiveFrom",
                                    row.get("effective_from").toString(),
                                    "status",
                                    "APPROVED",
                                    "version",
                                    "2");
                    saveReplay(c, tenant, actorId, op, resKey, in.idempotencyKey(), pHash, res);
                    return res;
                });
    }

    public Object build(
            String principal, UUID book, BuildObligationInput in, HttpServletRequest request) {
        requireKey(in.idempotencyKey());
        if (in.taxEntityId() == null
                || in.role() == null
                || in.kind() == null
                || in.formVersion() == null
                || in.rules() == null
                || in.rules().isEmpty()
                || in.sources() == null
                || in.sources().isEmpty()
                || in.worksheetDigest() == null)
            throw FinanceException.invalid("申报义务构建字段不完整");
        return transaction(
                true,
                c -> {
                    var b = finance.originalBookScope(principal, book, "create");
                    if (!allowed(b, "create")) throw error("SCOPE_DENIED", "需要制单权限构建税务申报底稿");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID legalEntity = id(b.get("legal_entity_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, false, request);
                    verifyActiveTaxEntity(c, tenant, legalEntity, in.taxEntityId());

                    Identity identity;
                    try {
                        identity =
                                new Identity(
                                        tenant,
                                        in.taxEntityId(),
                                        in.authority(),
                                        in.filingUnit(),
                                        in.role(),
                                        in.periodStart(),
                                        in.periodEnd(),
                                        in.occurrence(),
                                        in.scopeCode());
                    } catch (Failure f) {
                        throw FinanceException.invalid("申报义务身份无效: " + f.code);
                    }

                    List<Source> domainSources = new ArrayList<>();
                    for (SourceRefInput sr : in.sources()) {
                        if (sr == null || sr.bookId() == null || sr.yearId() == null)
                            throw FinanceException.invalid("来源账套年度引用不能为空");
                        finance.originalScope(principal, sr.bookId(), sr.yearId(), "read");
                        var bindRows =
                                c.list(
                                        "SELECT source_role,mapping_version,status FROM plugin_finance_tax_entity_book_auth "
                                            + "WHERE tenant_id=? AND tax_entity_id=? AND book_id=?",
                                        tenant,
                                        in.taxEntityId(),
                                        sr.bookId());
                        if (bindRows.isEmpty() || !"APPROVED".equals(bindRows.getFirst().get("status")))
                            throw error("TAX_SOURCE_BOOK_UNAUTHORIZED", "来源账套尚未与该纳税主体完成独立批准绑定");
                        if ("READ_ONLY_REFERENCE".equals(bindRows.getFirst().get("source_role")))
                            throw error("TAX_READ_ONLY_SOURCE_FORBIDDEN", "只读参考账套不得计入申报汇总来源");
                        var g =
                                c.one(
                                        "SELECT posting_seq,opening_version,baseline_version FROM plugin_finance_year_guard "
                                            + "WHERE tenant_id=? AND book_id=? AND year_id=?",
                                        tenant,
                                        sr.bookId(),
                                        sr.yearId());
                        long wm = ((Number) g.get("posting_seq")).longValue();
                        long ov = ((Number) g.get("opening_version")).longValue();
                        long bv = ((Number) g.get("baseline_version")).longValue();
                        String srcDigest =
                                TaxObligationModel.hash(
                                        tenant.toString(),
                                        sr.bookId().toString(),
                                        sr.yearId().toString(),
                                        String.valueOf(wm),
                                        String.valueOf(ov));
                        domainSources.add(
                                new Source(
                                        tenant,
                                        in.taxEntityId(),
                                        sr.bookId(),
                                        sr.yearId(),
                                        wm,
                                        ov,
                                        bv > 0 ? bv : null,
                                        bindRows.getFirst().get("mapping_version").toString(),
                                        srcDigest));
                    }

                    Content content;
                    try {
                        content =
                                new Content(
                                        in.formVersion(),
                                        new TreeSet<>(in.rules()),
                                        domainSources,
                                        in.kind(),
                                        in.predecessorDeclarationId(),
                                        in.reason() == null ? "" : in.reason(),
                                        in.adjustments() == null
                                                ? new TreeSet<>()
                                                : new TreeSet<>(in.adjustments()),
                                        in.worksheetDigest());
                    } catch (Failure f) {
                        throw FinanceException.invalid("申报底稿内容无效: " + f.code);
                    }

                    String dataDigest =
                            new Declaration(
                                            UUID.randomUUID(),
                                            1,
                                            1,
                                            content,
                                            id(who.get("personId")),
                                            State.DRAFT,
                                            List.of(),
                                            null,
                                            null,
                                            null)
                                    .dataDigest();
                    String pHash = TaxObligationModel.hash(identity.key(), dataDigest);
                    var cached =
                            replay(
                                    c,
                                    tenant,
                                    actorId,
                                    "BUILD",
                                    identity.key(),
                                    in.idempotencyKey(),
                                    pHash);
                    if (cached != null) return cached;

                    var obRows =
                            c.list(
                                    "SELECT * FROM plugin_finance_tax_obligation WHERE tenant_id=? AND tax_entity_id=? "
                                        + "AND authority=? AND filing_unit=? AND role=? AND period_start=? AND period_end=? "
                                        + "AND occurrence=? AND scope_code=? FOR UPDATE",
                                    tenant,
                                    in.taxEntityId(),
                                    in.authority(),
                                    in.filingUnit(),
                                    in.role().name(),
                                    in.periodStart(),
                                    in.periodEnd(),
                                    in.occurrence(),
                                    in.scopeCode());
                    UUID obId;
                    long nextObVersion;
                    UUID currentAccepted = null;
                    if (obRows.isEmpty()) {
                        if (in.kind() != Kind.INITIAL)
                            throw error("STALE_PREDECESSOR", "首次申报必须为 INITIAL");
                        obId = UUID.randomUUID();
                        nextObVersion = 1L;
                        c.update(
                                "INSERT INTO plugin_finance_tax_obligation("
                                    + "tenant_id,legal_entity_id,tax_entity_id,id,authority,filing_unit,role,"
                                    + "period_start,period_end,occurrence,scope_code,identity_key,version,created_by) "
                                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,1,?::jsonb)",
                                tenant,
                                legalEntity,
                                in.taxEntityId(),
                                obId,
                                in.authority(),
                                in.filingUnit(),
                                in.role().name(),
                                in.periodStart(),
                                in.periodEnd(),
                                in.occurrence(),
                                in.scopeCode(),
                                identity.key(),
                                finance.encode(who));
                    } else {
                        var ob = obRows.getFirst();
                        obId = id(ob.get("id"));
                        currentAccepted =
                                ob.get("accepted_declaration_id") == null
                                        ? null
                                        : id(ob.get("accepted_declaration_id"));
                        if (in.kind() == Kind.INITIAL && currentAccepted != null)
                            throw error("STALE_PREDECESSOR", "已存在受理申报，须发起更正或作废版本");
                        if (in.kind() != Kind.INITIAL
                                && !Objects.equals(currentAccepted, in.predecessorDeclarationId()))
                            throw error("STALE_PREDECESSOR", "更正前序版本不是当前已受理版本");
                        var liveDrafts =
                                c.list(
                                        "SELECT id FROM plugin_finance_tax_declaration "
                                            + "WHERE tenant_id=? AND obligation_id=? AND state NOT IN('ACCEPTED','REJECTED','CANCELLED')",
                                        tenant,
                                        obId);
                        if (!liveDrafts.isEmpty())
                            throw error("DRAFT_ALREADY_EXISTS", "当前申报义务已有进行中的底稿或未知外部回执槽位");
                        nextObVersion = ((Number) ob.get("version")).longValue() + 1;
                        c.update(
                                "UPDATE plugin_finance_tax_obligation SET version=? WHERE tenant_id=? AND id=?",
                                nextObVersion,
                                tenant,
                                obId);
                    }

                    long rev =
                            ((Number)
                                                    c.one(
                                                                    "SELECT COALESCE(MAX(revision),0) r FROM plugin_finance_tax_declaration WHERE tenant_id=? AND obligation_id=?",
                                                                    tenant,
                                                                    obId)
                                                            .get("r"))
                                            .longValue()
                                    + 1;
                    UUID declId = UUID.randomUUID();
                    c.update(
                            "INSERT INTO plugin_finance_tax_declaration("
                                + "tenant_id,tax_entity_id,obligation_id,id,revision,version,kind,predecessor_id,"
                                + "state,form_version,rules,adjustments,worksheet_digest,data_digest,reason,preparer) "
                                + "VALUES(?,?,?,?,?,1,?,?,'DRAFT',?,?::jsonb,?::jsonb,?,?,?,?::jsonb)",
                            tenant,
                            in.taxEntityId(),
                            obId,
                            declId,
                            rev,
                            in.kind().name(),
                            in.predecessorDeclarationId(),
                            in.formVersion(),
                            finance.encode(content.rules().stream().map(UUID::toString).toList()),
                            finance.encode(
                                    content.adjustments().stream().map(UUID::toString).toList()),
                            in.worksheetDigest(),
                            dataDigest,
                            in.reason(),
                            finance.encode(who));
                    for (Source s : content.sources()) {
                        c.update(
                                "INSERT INTO plugin_finance_tax_declaration_source("
                                    + "tenant_id,declaration_id,tax_entity_id,book_id,year_id,watermark,"
                                    + "opening_version,migration_version,mapping_version,source_digest) "
                                    + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                                tenant,
                                declId,
                                in.taxEntityId(),
                                s.book(),
                                s.year(),
                                s.watermark(),
                                s.openingVersion(),
                                s.migrationVersion(),
                                s.mappingVersion(),
                                s.sourceDigest());
                    }
                    var res = declarationView(c, tenant, obId, declId);
                    saveReplay(
                            c,
                            tenant,
                            actorId,
                            "BUILD",
                            identity.key(),
                            in.idempotencyKey(),
                            pHash,
                            res);
                    return res;
                });
    }

    public Object transition(
            String principal,
            UUID book,
            UUID obligationId,
            UUID declarationId,
            String action,
            TransitionInput in,
            HttpServletRequest request) {
        requireKey(in.idempotencyKey());
        if (in.reason() == null || in.reason().isBlank() || in.reason().length() > 1000)
            throw FinanceException.invalid("申报流转原因无效");
        String perm =
                switch (action) {
                    case "approve" -> "approve";
                    case "handoff" -> "post";
                    default -> "create";
                };
        return transaction(
                true,
                c -> {
                    var b = finance.originalBookScope(principal, book, perm);
                    if (!allowed(b, perm)) throw error("SCOPE_DENIED", "当前岗位无权执行该申报动作");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID legalEntity = id(b.get("legal_entity_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who =
                            gate.current(
                                    actorId,
                                    tenant,
                                    Set.of("approve", "handoff").contains(action),
                                    request);
                    String op = action.toUpperCase(Locale.ROOT);
                    String resKey = obligationId + ":" + declarationId;
                    String pHash =
                            FinanceService.hash(
                                    action + ":" + in.expectedObligationVersion() + ":" + in.reason());
                    var cached = replay(c, tenant, actorId, op, resKey, in.idempotencyKey(), pHash);
                    if (cached != null) return cached;

                    var ob =
                            c.one(
                                    "SELECT * FROM plugin_finance_tax_obligation WHERE tenant_id=? AND legal_entity_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    legalEntity,
                                    obligationId);
                    verifyActiveTaxEntity(c, tenant, legalEntity, id(ob.get("tax_entity_id")));
                    if (((Number) ob.get("version")).longValue() != in.expectedObligationVersion())
                        throw error("VERSION_CONFLICT", "申报义务版本冲突");
                    var d =
                            c.one(
                                    "SELECT * FROM plugin_finance_tax_declaration WHERE tenant_id=? AND obligation_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    obligationId,
                                    declarationId);
                    reauthorizeDeclarationSources(c, principal, tenant, declarationId);

                    String curState = d.get("state").toString();
                    switch (action) {
                        case "review" -> {
                            if (!Set.of("DRAFT", "BLOCKED").contains(curState))
                                throw error("TAX_STATE_CONFLICT", "当前状态不允许提交复核");
                            c.update(
                                    "UPDATE plugin_finance_tax_declaration SET state='READY_FOR_REVIEW',version=version+1 WHERE tenant_id=? AND obligation_id=? AND id=?",
                                    tenant,
                                    obligationId,
                                    declarationId);
                        }
                        case "approve" -> {
                            if (!"READY_FOR_REVIEW".equals(curState))
                                throw error("TAX_STATE_CONFLICT", "申报底稿尚未进入待批准状态");
                            independent(who, json(d.get("preparer")));
                            Instant exp = Instant.now().plus(24, ChronoUnit.HOURS);
                            c.update(
                                    "UPDATE plugin_finance_tax_declaration SET state='APPROVED',version=version+1,"
                                        + "approver=?::jsonb,approval_expires_at=? WHERE tenant_id=? AND obligation_id=? AND id=?",
                                    finance.encode(who),
                                    Timestamp.from(exp),
                                    tenant,
                                    obligationId,
                                    declarationId);
                        }
                        case "handoff" -> {
                            if (!"APPROVED".equals(curState))
                                throw error("TAX_STATE_CONFLICT", "仅已独立批准的申报底稿可进入线下交接槽位");
                            Timestamp exp = (Timestamp) d.get("approval_expires_at");
                            if (exp == null || !exp.toInstant().isAfter(Instant.now()))
                                throw error("APPROVAL_EXPIRED", "申报批准已过期");
                            UUID cmdId = UUID.randomUUID();
                            c.update(
                                    "INSERT INTO plugin_finance_tax_attempt("
                                        + "tenant_id,obligation_id,declaration_id,command_id,operation,status,data_digest,actor) "
                                        + "VALUES(?,?,?,?,'MANUAL_HANDOFF','UNKNOWN',?,?::jsonb)",
                                    tenant,
                                    obligationId,
                                    declarationId,
                                    cmdId,
                                    d.get("data_digest"),
                                    finance.encode(who));
                            c.update(
                                    "UPDATE plugin_finance_tax_declaration SET state='UNKNOWN',version=version+1,active_command_id=? "
                                        + "WHERE tenant_id=? AND obligation_id=? AND id=?",
                                    cmdId,
                                    tenant,
                                    obligationId,
                                    declarationId);
                        }
                        case "cancel" -> {
                            if (d.get("active_command_id") != null
                                    || !Set.of("DRAFT", "BLOCKED", "READY_FOR_REVIEW", "APPROVED")
                                            .contains(curState))
                                throw error("TAX_CANNOT_CANCEL", "已进入未知外部结果或终态的申报不得直接作废");
                            originalAccount(who, json(d.get("preparer")));
                            c.update(
                                    "UPDATE plugin_finance_tax_declaration SET state='CANCELLED',version=version+1 "
                                        + "WHERE tenant_id=? AND obligation_id=? AND id=?",
                                    tenant,
                                    obligationId,
                                    declarationId);
                        }
                        default -> throw FinanceException.invalid("不支持的申报动作");
                    }
                    c.update(
                            "UPDATE plugin_finance_tax_obligation SET version=version+1 WHERE tenant_id=? AND id=?",
                            tenant,
                            obligationId);
                    var res = declarationView(c, tenant, obligationId, declarationId);
                    saveReplay(c, tenant, actorId, op, resKey, in.idempotencyKey(), pHash, res);
                    return res;
                });
    }

    public Object reconcileReceipt(
            String principal,
            UUID book,
            UUID obligationId,
            UUID declarationId,
            ReconcileReceiptInput in,
            HttpServletRequest request) {
        requireKey(in.idempotencyKey());
        if (in.receiptYearId() == null
                || in.receiptOriginalId() == null
                || in.providerReference() == null
                || in.providerReference().isBlank()
                || in.providerReference().length() > 256
                || in.outcome() == null
                || in.evidenceRef() == null
                || in.evidenceRef().isBlank()
                || in.evidenceRef().length() > 500)
            throw FinanceException.invalid("税务回执核销字段无效");
        return transaction(
                true,
                c -> {
                    var b = finance.originalScope(principal, book, in.receiptYearId(), "approve");
                    if (!allowed(b, "approve"))
                        throw error("SCOPE_DENIED", "需要独立复核权限核验真实税务回执原件");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID legalEntity = id(b.get("legal_entity_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var verifier = gate.current(actorId, tenant, true, request);
                    UUID verifierPerson = id(verifier.get("personId"));

                    String op = "RECONCILE";
                    String resKey = obligationId + ":" + declarationId;
                    String pHash =
                            FinanceService.hash(
                                    in.expectedObligationVersion()
                                            + ":"
                                            + in.receiptOriginalId()
                                            + ":"
                                            + in.providerReference()
                                            + ":"
                                            + in.outcome());
                    var cached = replay(c, tenant, actorId, op, resKey, in.idempotencyKey(), pHash);
                    if (cached != null) return cached;

                    var ob =
                            c.one(
                                    "SELECT * FROM plugin_finance_tax_obligation WHERE tenant_id=? AND legal_entity_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    legalEntity,
                                    obligationId);
                    verifyActiveTaxEntity(c, tenant, legalEntity, id(ob.get("tax_entity_id")));
                    if (((Number) ob.get("version")).longValue() != in.expectedObligationVersion())
                        throw error("VERSION_CONFLICT", "申报义务版本冲突");
                    var d =
                            c.one(
                                    "SELECT * FROM plugin_finance_tax_declaration WHERE tenant_id=? AND obligation_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    obligationId,
                                    declarationId);
                    if (!"UNKNOWN".equals(d.get("state")) || d.get("active_command_id") == null)
                        throw error("TAX_STATE_CONFLICT", "仅处于 UNKNOWN 外部结果待核销槽位的申报可登记回执");
                    reauthorizeDeclarationSources(c, principal, tenant, declarationId);

                    UUID preparerPerson = id(json(d.get("preparer")).get("personId"));
                    if (verifierPerson.equals(preparerPerson))
                        throw error("SELF_APPROVAL_FORBIDDEN", "回执核验人不得与申报制单人为同一自然人");
                    if (d.get("approver") != null) {
                        UUID approverPerson = id(json(d.get("approver")).get("personId"));
                        if (verifierPerson.equals(approverPerson))
                            throw error("SELF_APPROVAL_FORBIDDEN", "回执核验人不得与申报批准人为同一自然人");
                    }

                    var orig =
                            c.one(
                                    "SELECT id,sha256,created_by FROM plugin_finance_original "
                                        + "WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? "
                                        + "AND sha256=encode(sha256(content),'hex')",
                                    tenant,
                                    book,
                                    in.receiptYearId(),
                                    in.receiptOriginalId());
                    var uploaderBinding = gate.identity(tenant, id(orig.get("created_by")));
                    UUID uploaderPerson = id(uploaderBinding.get("personId"));
                    if (verifierPerson.equals(uploaderPerson))
                        throw error("SELF_APPROVAL_FORBIDDEN", "回执核验人不得与回执上传人为同一自然人");

                    String authority = ob.get("authority").toString();
                    var dupReceipt =
                            c.list(
                                    "SELECT id FROM plugin_finance_tax_receipt WHERE tenant_id=? AND authority=? AND provider_ref=?",
                                    tenant,
                                    authority,
                                    in.providerReference());
                    if (!dupReceipt.isEmpty())
                        throw error("TAX_RECEIPT_ALREADY_USED", "同一税务机关回执编号已被其他申报占用");

                    UUID cmdId = id(d.get("active_command_id"));
                    UUID receiptId = UUID.randomUUID();
                    c.update(
                            "INSERT INTO plugin_finance_tax_receipt("
                                + "tenant_id,id,obligation_id,declaration_id,command_id,authority,provider_ref,"
                                + "outcome,book_id,year_id,original_id,bytes_digest,data_digest,uploader_person,verifier,evidence_ref) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)",
                            tenant,
                            receiptId,
                            obligationId,
                            declarationId,
                            cmdId,
                            authority,
                            in.providerReference(),
                            in.outcome().name(),
                            book,
                            in.receiptYearId(),
                            in.receiptOriginalId(),
                            orig.get("sha256"),
                            d.get("data_digest"),
                            uploaderPerson,
                            finance.encode(verifier),
                            in.evidenceRef());
                    c.update(
                            "UPDATE plugin_finance_tax_attempt SET status=?,resolved_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND command_id=?",
                            in.outcome().name(),
                            tenant,
                            cmdId);
                    c.update(
                            "UPDATE plugin_finance_tax_declaration SET state=?,version=version+1,receipt_ref=? "
                                + "WHERE tenant_id=? AND obligation_id=? AND id=?",
                            in.outcome().name(),
                            in.providerReference(),
                            tenant,
                            obligationId,
                            declarationId);
                    c.update(
                            "UPDATE plugin_finance_tax_obligation SET version=version+1,"
                                + "accepted_declaration_id=CASE WHEN ?='ACCEPTED' THEN ?::uuid ELSE accepted_declaration_id END "
                                + "WHERE tenant_id=? AND id=?",
                            in.outcome().name(),
                            declarationId,
                            tenant,
                            obligationId);
                    var res = declarationView(c, tenant, obligationId, declarationId);
                    saveReplay(c, tenant, actorId, op, resKey, in.idempotencyKey(), pHash, res);
                    return res;
                });
    }

    private void reauthorizeDeclarationSources(
            Call c, String principal, UUID tenant, UUID declarationId) {
        var srcs =
                c.list(
                        "SELECT book_id,year_id FROM plugin_finance_tax_declaration_source WHERE tenant_id=? AND declaration_id=?",
                        tenant,
                        declarationId);
        for (var s : srcs) {
            finance.originalScope(principal, id(s.get("book_id")), id(s.get("year_id")), "read");
        }
    }

    private Map<String, Object> declarationView(
            Call c, UUID tenant, UUID obligationId, UUID declarationId) {
        var ob =
                c.one(
                        "SELECT * FROM plugin_finance_tax_obligation WHERE tenant_id=? AND id=?",
                        tenant,
                        obligationId);
        var d =
                c.one(
                        "SELECT * FROM plugin_finance_tax_declaration WHERE tenant_id=? AND obligation_id=? AND id=?",
                        tenant,
                        obligationId,
                        declarationId);
        return map(
                "obligationId",
                obligationId.toString(),
                "obligationVersion",
                ob.get("version").toString(),
                "obligationStatus",
                ob.get("obligation_status"),
                "acceptedDeclarationId",
                ob.get("accepted_declaration_id") == null
                        ? null
                        : ob.get("accepted_declaration_id").toString(),
                "declaration",
                map(
                        "id",
                        declarationId.toString(),
                        "revision",
                        d.get("revision").toString(),
                        "version",
                        d.get("version").toString(),
                        "kind",
                        d.get("kind"),
                        "state",
                        d.get("state"),
                        "dataDigest",
                        d.get("data_digest"),
                        "activeCommandId",
                        d.get("active_command_id") == null
                                ? null
                                : d.get("active_command_id").toString(),
                        "receiptRef",
                        d.get("receipt_ref"),
                        "paymentState",
                        d.get("payment_state"),
                        "officialCapability",
                        d.get("official_capability")));
    }

    public Object listWorksheets(String principal, UUID book, UUID yearId) {
        return transaction(
                false,
                c -> {
                    var b = finance.originalScope(principal, book, yearId, "read");
                    UUID tenant = id(b.get("tenant_id"));
                    var rows =
                            c.list(
                                    "SELECT * FROM plugin_finance_tax_worksheet "
                                        + "WHERE tenant_id=? AND book_id=? AND year_id=? ORDER BY created_at DESC, id DESC LIMIT 50",
                                    tenant,
                                    book,
                                    yearId);
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (var r : rows) {
                        items.add(worksheetView(r));
                    }
                    return map(
                            "items",
                            items,
                            "officialCapability",
                            "NOT_VERIFIED",
                            "paymentState",
                            "NOT_ASSESSED");
                });
    }

    @SuppressWarnings("unchecked")
    public Object createWorksheet(
            String principal, UUID book, CreateWorksheetInput in, HttpServletRequest request) {
        requireKey(in.idempotencyKey());
        if (in.yearId() == null
                || in.taxEntityId() == null
                || in.worksheetCode() == null
                || in.worksheetCode().isBlank()
                || in.worksheetCode().length() > 128
                || in.kind() == null
                || !Set.of("VAT_SMALL", "VAT_GENERAL_SURCHARGE", "CIT_ADJUSTMENT_AND_PREPAY")
                        .contains(in.kind())
                || in.periodStart() == null
                || in.periodEnd() == null
                || in.periodEnd().isBefore(in.periodStart())
                || in.ruleVersion() == null
                || in.ruleVersion().isBlank()
                || in.ruleVersion().length() > 128
                || in.basisOriginalId() == null
                || in.inputs() == null
                || in.reason() == null
                || in.reason().isBlank()
                || in.reason().length() > 1000)
            throw FinanceException.invalid("税务计税底稿构建参数无效");
        return transaction(
                true,
                c -> {
                    var b = finance.originalScope(principal, book, in.yearId(), "create");
                    if (!allowed(b, "create")) throw error("SCOPE_DENIED", "需要制单权限创建税务计税底稿");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID legalEntity = id(b.get("legal_entity_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, false, request);
                    verifyActiveTaxEntity(c, tenant, legalEntity, in.taxEntityId());

                    String op = "WORKSHEET_CREATE";
                    String resKey = book + ":" + in.yearId() + ":" + in.worksheetCode().trim();
                    String pHash =
                            FinanceService.hash(
                                    in.taxEntityId()
                                            + ":"
                                            + in.kind()
                                            + ":"
                                            + in.periodStart()
                                            + ":"
                                            + in.periodEnd()
                                            + ":"
                                            + in.ruleVersion().trim()
                                            + ":"
                                            + in.basisOriginalId()
                                            + ":"
                                            + finance.encode(finance.canonical(in.inputs())));
                    var cached = replay(c, tenant, actorId, op, resKey, in.idempotencyKey(), pHash);
                    if (cached != null) return cached;

                    var dup =
                            c.list(
                                    "SELECT id FROM plugin_finance_tax_worksheet WHERE tenant_id=? AND book_id=? AND year_id=? AND worksheet_code=?",
                                    tenant,
                                    book,
                                    in.yearId(),
                                    in.worksheetCode().trim());
                    if (!dup.isEmpty())
                        throw error("TAX_WORKSHEET_EXISTS", "该账套年度下同名计税底稿编码已存在");

                    var origRows =
                            c.list(
                                    "SELECT sha256 FROM plugin_finance_original "
                                        + "WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? "
                                        + "AND sha256=encode(sha256(content),'hex')",
                                    tenant,
                                    book,
                                    in.yearId(),
                                    in.basisOriginalId());
                    if (origRows.isEmpty())
                        throw error("TAX_WORKSHEET_ORIGINAL_INVALID", "计税依据原件不存在或字节哈希校验不符");
                    String origSha = origRows.getFirst().get("sha256").toString();

                    Map<String, Object> computedOutputs;
                    String worksheetDigest;
                    try {
                        switch (in.kind()) {
                            case "VAT_SMALL" -> {
                                var parsedIn =
                                        FinanceStrictJson.convert(
                                                in.inputs(),
                                                com.lumira.finance.domain.TaxCalculationRules
                                                        .SmallVatInput.class,
                                                "小规模增值税计税参数");
                                var out =
                                        com.lumira.finance.domain.TaxCalculationRules
                                                .calculateSmallVat(parsedIn);
                                worksheetDigest = out.worksheetDigest();
                                computedOutputs =
                                        gate.json.readValue(
                                                gate.json.writeValueAsString(out), Map.class);
                            }
                            case "VAT_GENERAL_SURCHARGE" -> {
                                var parsedIn =
                                        FinanceStrictJson.convert(
                                                in.inputs(),
                                                com.lumira.finance.domain.TaxCalculationRules
                                                        .VatGeneralSurchargeInput.class,
                                                "一般纳税人增值税及附加税计税参数");
                                var out =
                                        com.lumira.finance.domain.TaxCalculationRules
                                                .calculateVatGeneralAndSurcharge(parsedIn);
                                worksheetDigest = out.worksheetDigest();
                                computedOutputs =
                                        gate.json.readValue(
                                                gate.json.writeValueAsString(out), Map.class);
                            }
                            case "CIT_ADJUSTMENT_AND_PREPAY" -> {
                                var parsedIn =
                                        FinanceStrictJson.convert(
                                                in.inputs(),
                                                com.lumira.finance.domain.TaxCalculationRules
                                                        .CitAdjustmentInput.class,
                                                "企业所得税纳税调整与预缴底稿参数");
                                var out =
                                        com.lumira.finance.domain.TaxCalculationRules
                                                .calculateCitAdjustmentAndPrepay(parsedIn);
                                worksheetDigest = out.worksheetDigest();
                                computedOutputs =
                                        gate.json.readValue(
                                                gate.json.writeValueAsString(out), Map.class);
                            }
                            default -> throw FinanceException.invalid("不支持的计税底稿类型");
                        }
                    } catch (IllegalArgumentException iae) {
                        throw error(
                                "TAX_RULE_CALCULATION_REJECTED",
                                "计税规则校验未通过: " + iae.getMessage());
                    }

                    UUID wsId = UUID.randomUUID();
                    c.update(
                            "INSERT INTO plugin_finance_tax_worksheet("
                                + "tenant_id,legal_entity_id,book_id,year_id,tax_entity_id,id,"
                                + "worksheet_code,kind,period_start,period_end,rule_version,"
                                + "basis_original_id,basis_original_sha256,inputs,outputs,"
                                + "worksheet_digest,status,version,prepared_by,reason) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,'PENDING',1,?::jsonb,?)",
                            tenant,
                            legalEntity,
                            book,
                            in.yearId(),
                            in.taxEntityId(),
                            wsId,
                            in.worksheetCode().trim(),
                            in.kind(),
                            in.periodStart(),
                            in.periodEnd(),
                            in.ruleVersion().trim(),
                            in.basisOriginalId(),
                            origSha,
                            finance.encode(finance.canonical(in.inputs())),
                            finance.encode(finance.canonical(computedOutputs)),
                            worksheetDigest,
                            finance.encode(who),
                            in.reason().trim());

                    var res =
                            worksheetView(
                                    c.one(
                                            "SELECT * FROM plugin_finance_tax_worksheet WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                            tenant,
                                            book,
                                            in.yearId(),
                                            wsId));
                    saveReplay(c, tenant, actorId, op, resKey, in.idempotencyKey(), pHash, res);
                    return res;
                });
    }

    public Object approveWorksheet(
            String principal,
            UUID book,
            UUID worksheetId,
            ApproveWorksheetInput in,
            HttpServletRequest request) {
        requireKey(in.idempotencyKey());
        if (in.yearId() == null
                || in.reason() == null
                || in.reason().isBlank()
                || in.reason().length() > 1000)
            throw FinanceException.invalid("税务计税底稿批准参数无效");
        return transaction(
                true,
                c -> {
                    var b = finance.originalScope(principal, book, in.yearId(), "approve");
                    if (!allowed(b, "approve"))
                        throw error("SCOPE_DENIED", "需要独立审核权限批准税务计税底稿");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID legalEntity = id(b.get("legal_entity_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, true, request);

                    String op = "WORKSHEET_APPROVE";
                    String resKey = book + ":" + in.yearId() + ":" + worksheetId;
                    String pHash =
                            FinanceService.hash(in.expectedVersion() + ":" + in.reason().trim());
                    var cached = replay(c, tenant, actorId, op, resKey, in.idempotencyKey(), pHash);
                    if (cached != null) return cached;

                    var row =
                            c.one(
                                    "SELECT * FROM plugin_finance_tax_worksheet WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    book,
                                    in.yearId(),
                                    worksheetId);
                    if (((Number) row.get("version")).longValue() != in.expectedVersion())
                        throw error("VERSION_CONFLICT", "税务计税底稿版本冲突");
                    if (!"PENDING".equals(row.get("status")))
                        throw error("TAX_STATE_CONFLICT", "税务计税底稿非待审状态");
                    verifyActiveTaxEntity(c, tenant, legalEntity, id(row.get("tax_entity_id")));
                    independent(who, json(row.get("prepared_by")));

                    c.update(
                            "UPDATE plugin_finance_tax_worksheet SET status='APPROVED',version=2,"
                                + "approved_by=?::jsonb,decision_reason=?,decided_at=CURRENT_TIMESTAMP "
                                + "WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            finance.encode(who),
                            in.reason().trim(),
                            tenant,
                            book,
                            in.yearId(),
                            worksheetId);

                    var res =
                            worksheetView(
                                    c.one(
                                            "SELECT * FROM plugin_finance_tax_worksheet WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                            tenant,
                                            book,
                                            in.yearId(),
                                            worksheetId));
                    saveReplay(c, tenant, actorId, op, resKey, in.idempotencyKey(), pHash, res);
                    return res;
                });
    }

    private Map<String, Object> worksheetView(Map<String, Object> r) {
        return map(
                "id",
                r.get("id").toString(),
                "yearId",
                r.get("year_id").toString(),
                "taxEntityId",
                r.get("tax_entity_id").toString(),
                "worksheetCode",
                r.get("worksheet_code"),
                "kind",
                r.get("kind"),
                "periodStart",
                r.get("period_start").toString(),
                "periodEnd",
                r.get("period_end").toString(),
                "ruleVersion",
                r.get("rule_version"),
                "basisOriginalId",
                r.get("basis_original_id").toString(),
                "basisOriginalSha256",
                r.get("basis_original_sha256"),
                "inputs",
                json(r.get("inputs")),
                "outputs",
                json(r.get("outputs")),
                "worksheetDigest",
                r.get("worksheet_digest"),
                "status",
                r.get("status"),
                "version",
                r.get("version").toString(),
                "reason",
                r.get("reason"),
                "officialCapability",
                r.get("official_capability"),
                "paymentState",
                r.get("payment_state"));
    }

    private Object replay(
            Call c,
            UUID tenant,
            UUID actorId,
            String operation,
            String resourceKey,
            String idempotencyKey,
            String payloadHash) {
        var rows =
                c.list(
                        "SELECT payload_hash,response FROM plugin_finance_tax_obligation_request "
                            + "WHERE tenant_id=? AND actor_id=? AND operation=? AND resource_key=? AND idempotency_key=?",
                        tenant,
                        actorId,
                        operation,
                        resourceKey,
                        idempotencyKey);
        if (rows.isEmpty()) return null;
        if (!payloadHash.equals(rows.getFirst().get("payload_hash")))
            throw error("IDEMPOTENCY_CONFLICT", "税务申报幂等键绑定了不同请求正文");
        return json(rows.getFirst().get("response"));
    }

    private void saveReplay(
            Call c,
            UUID tenant,
            UUID actorId,
            String operation,
            String resourceKey,
            String idempotencyKey,
            String payloadHash,
            Object response) {
        c.update(
                "INSERT INTO plugin_finance_tax_obligation_request("
                    + "tenant_id,actor_id,operation,resource_key,idempotency_key,payload_hash,response) "
                    + "VALUES(?,?,?,?,?,?,?::jsonb)",
                tenant,
                actorId,
                operation,
                resourceKey,
                idempotencyKey,
                payloadHash,
                finance.encode(response));
    }
}
