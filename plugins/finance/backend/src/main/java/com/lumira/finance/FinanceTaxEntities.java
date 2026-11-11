package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.*;

/** Persistent organization-reviewed registration; no filing or tax eligibility engine. */
@Service
public class FinanceTaxEntities extends FinanceTaxEntityProjection {
    @Autowired
    public FinanceTaxEntities(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager transactions,
            AuthorizationContextProjectionPort projection) {
        super(db, finance, gate, transactions, projection);
    }

    public Object read(
            String principal,
            UUID book,
            String operation,
            UUID entity,
            UUID revision,
            HttpServletRequest request) {
        if (!Set.of("catalog", "list", "detail", "revision-detail").contains(operation))
            throw FinanceException.invalid("操作无效");
        var q = query(operation, request);
        return run(
                false,
                c -> {
                    var b = scope(c, principal, book, null);
                    return switch (operation) {
                        case "catalog" -> catalog(c, b, request);
                        case "list" -> page(c, principal, b, q);
                        case "detail" -> detail(c, principal, b, entity, request);
                        default -> revisionDetail(c, principal, b, entity, revision, request);
                    };
                });
    }

    List<Map<String, Object>> duplicates(
            Call c, String principal, Map<String, Object> b, Map<String, Object> n) {
        var rows =
                c.list(
                        "SELECT * FROM plugin_finance_tax_entity_registry WHERE tenant_id=?"
                            + " AND(operation_id=? OR registration_no=?)",
                        b.get("tenant_id"),
                        FinanceTaxEntityInput.uuid(n.get("operationId")),
                        FinanceTaxEntityInput.object(n.get("identity"))
                                .get("canonicalTaxRegistrationNo"));
        for (var p : rows) homeRead(c, principal, b, p);
        return rows;
    }

    Map<String, Object> commandResult(
            Call c,
            String principal,
            Map<String, Object> b,
            UUID receipt,
            boolean replayed,
            HttpServletRequest request) {
        var rc = receipt(c, b, receipt);
        return map(
                "commandReceipt",
                rc,
                "current",
                revisionDetail(
                        c,
                        principal,
                        b,
                        id(rc.get("taxEntityId")),
                        id(rc.get("revisionId")),
                        request),
                "replayed",
                replayed);
    }

    public Object write(
            String principal,
            UUID book,
            String operation,
            UUID entity,
            UUID revision,
            Map<String, Object> body,
            HttpServletRequest request) {
        var n = FinanceTaxEntityInput.normalized(operation, body);
        if (!request.getParameterMap().isEmpty()) throw FinanceException.invalid("写入不接受查询参数");
        return run(
                true,
                c -> {
                    var b = scope(c, principal, book, operation);
                    who(c, b, request);
                    if (operation.equals("create")) {
                        duplicates(c, principal, b, n);
                        inputEvidenceRead(c, principal, b, n);
                    } else {
                        var p = party(c, b, entity);
                        homeRead(c, principal, b, p);
                        if (!id(p.get("home_book_id")).equals(book))
                            throw error("SCOPE_DENIED", "仅在永久治理账套写入");
                        if (operation.equals("propose-revision"))
                            inputEvidenceRead(c, principal, b, n);
                        else {
                            var r = revision(c, p, revision);
                            contentRead(c, principal, b, p, r);
                        }
                    }
                    if (c.list(
                                    "SELECT tenant_id FROM plugin_finance_person_realm WHERE"
                                        + " tenant_id=? FOR SHARE",
                                    b.get("tenant_id"))
                            .isEmpty()) throw error("SCOPE_DENIED", "组织人员目录未配置");
                    c.one(
                            "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) value",
                            b.get("actor_id") + ":" + n.get("idempotencyKey"));
                    c.one(
                            "SELECT plugin_finance_tax_entity_lock(?,?,?) value",
                            b.get("tenant_id"),
                            b.get("legal_entity_id"),
                            book);
                    b = scope(c, principal, book, operation);
                    var who = who(c, b, request);
                    var dups =
                            operation.equals("create")
                                    ? duplicates(c, principal, b, n)
                                    : List.<Map<String, Object>>of();
                    if (!Set.of("create", "propose-revision").contains(operation))
                        actionIdentity(
                                c, b, operation, revision(c, party(c, b, entity), revision), who);
                    // Public authority and source-year read are current even for old receipts;
                    // bytes/state
                    // freshness only belongs to a new effect, after the cache/permanent-operation
                    // branch.
                    if (Set.of("create", "propose-revision").contains(operation))
                        inputEvidenceRead(c, principal, b, n);
                    else {
                        var p = party(c, b, entity);
                        contentRead(c, principal, b, p, revision(c, p, revision));
                    }
                    String route = operation + "/" + entity + "/" + revision;
                    var payload = new LinkedHashMap<>(n);
                    payload.remove("idempotencyKey");
                    String payloadHash =
                            c.one(
                                            "SELECT plugin_finance_tax_entity_digest(?::jsonb)"
                                                + " value",
                                            finance.encode(payload))
                                    .get("value")
                                    .toString();
                    var old =
                            c.list(
                                    "SELECT * FROM plugin_finance_tax_entity_request WHERE"
                                        + " actor_id=? AND request_key=?",
                                    b.get("actor_id"),
                                    n.get("idempotencyKey"));
                    UUID receipt;
                    if (!old.isEmpty()) {
                        var x = old.getFirst();
                        if (!Objects.equals(x.get("tenant_id"), b.get("tenant_id"))
                                || !Objects.equals(
                                        x.get("legal_entity_id"), b.get("legal_entity_id"))
                                || !Objects.equals(x.get("home_book_id"), book)
                                || !Objects.equals(x.get("route"), route)
                                || !Objects.equals(x.get("payload_hash"), payloadHash))
                            throw error("IDEMPOTENCY_CONFLICT", "请求键永久绑定不同资源或内容");
                        return commandResult(
                                c, principal, b, id(x.get("receipt_id")), true, request);
                    }
                    if (operation.equals("create")) {
                        boolean sameOperation =
                                dups.stream()
                                        .anyMatch(
                                                p ->
                                                        id(p.get("operation_id"))
                                                                .equals(id(n.get("operationId"))));
                        if (!sameOperation && !dups.isEmpty()) {
                            var p = dups.getFirst();
                            throw new FinanceException(
                                    "TAX_ENTITY_IDENTITY_EXISTS",
                                    "已有纳税主体 " + p.get("id") + "，治理账套 " + p.get("home_book_id"),
                                    List.of(
                                            map(
                                                    "row",
                                                    1,
                                                    "field",
                                                    "existingTaxEntityId",
                                                    "reason",
                                                    p.get("id").toString()),
                                            map(
                                                    "row",
                                                    1,
                                                    "field",
                                                    "governanceHomeBookId",
                                                    "reason",
                                                    p.get("home_book_id").toString())));
                        }
                    }
                    var result =
                            json(
                                    c.one(
                                                    "SELECT"
                                                        + " plugin_finance_tax_entity_command(?,?,?,?::jsonb,?,?,?,?::jsonb)::text"
                                                        + " value",
                                                    b.get("tenant_id"),
                                                    b.get("legal_entity_id"),
                                                    book,
                                                    finance.encode(who),
                                                    operation
                                                            .toUpperCase(Locale.ROOT)
                                                            .replace('-', '_'),
                                                    entity,
                                                    revision,
                                                    finance.encode(n))
                                            .get("value"));
                    receipt = id(result.get("commandReceiptId"));
                    c.update(
                            "INSERT INTO"
                                + " plugin_finance_tax_entity_request(actor_id,request_key,tenant_id,legal_entity_id,home_book_id,tax_entity_id,revision_id,receipt_id,route,payload_hash)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                            b.get("actor_id"),
                            n.get("idempotencyKey"),
                            b.get("tenant_id"),
                            b.get("legal_entity_id"),
                            book,
                            id(result.get("taxEntityId")),
                            id(result.get("revisionId")),
                            receipt,
                            route,
                            payloadHash);
                    return commandResult(
                            c,
                            principal,
                            b,
                            receipt,
                            !Boolean.TRUE.equals(result.get("newEffect")),
                            request);
                });
    }
}
