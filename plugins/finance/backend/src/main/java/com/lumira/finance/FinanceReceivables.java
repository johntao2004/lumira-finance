package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.lumira.finance.FinanceReceivablesRules.*;
import static com.lumira.finance.FinanceReceivablesDependencies.*;

/** AR candidate. All persistent changes share the inherited bounded Spring transaction. */
@Service
public final class FinanceReceivables extends FinanceProfitClosingAccess {
    private final Optional<FinanceReceivablesDependencies> dependencies;
    private static final String WHERE = " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?";

    public FinanceReceivables(FinanceStore db, FinanceService finance, FinanceJournalPersonGate gate,
            PlatformTransactionManager tx, AuthorizationContextProjectionPort authorization,
            Optional<FinanceReceivablesDependencies> dependencies) {
        super(db, finance, gate, tx, authorization);
        this.dependencies = dependencies;
    }

    private FinanceReceivablesDependencies dependency() {
        return dependencies.orElseThrow(() -> error("AR_DEPENDENCY_UNAVAILABLE",
                "收入政策、税务事实、科目映射或原生AR记账契约尚未接入；未产生会计效果"));
    }

    private Scope typedScope(Map<String, Object> b, UUID year) {
        return new Scope(id(b.get("tenant_id")), id(b.get("legal_entity_id")), id(b.get("id")), year,
                b.get("standard").toString());
    }
    private Actor typedActor(Map<String, Object> a) {
        return new Actor(id(a.get("accountId")), id(a.get("personId")), id(a.get("bindingId")));
    }
    private String digest(Object value) { return FinanceService.hash(finance.encode(finance.canonical(value))); }
    private Map<String, Object> row(Call c, Map<String, Object> b, UUID y, UUID target) {
        return c.one("SELECT * FROM plugin_finance_ar_object" + WHERE, b.get("tenant_id"), b.get("id"), y, target);
    }
    private BigDecimal amount(Object value) { return new BigDecimal(value.toString()); }
    private Plan plan(Map<String, Object> content) {
        return gate.json.readValue(finance.encode(content.get("plan")), Plan.class);
    }

    /** Current authority is checked even before durable idempotency replay. */
    public Object write(String principal, UUID book, UUID year, String action, UUID target,
            Map<String, Object> raw, HttpServletRequest request) {
        final Map<String, Object> input;
        try { input = FinanceReceivablesInput.normalize(action, raw); }
        catch (Rejected ex) { throw error(ex.code, "应收命令字段、金额或版本无效"); }
        if (action.equals("create") != (target == null)) throw FinanceException.invalid("目标与动作不一致");
        return transaction(true, c -> {
            c.one("SELECT set_config('lock_timeout','2000',true) value");
            var b = c.locked(principal, book, year, action, input.get("idempotencyKey").toString());
            c.one("SELECT legal_entity_id FROM plugin_finance_counterparty_entity_guard WHERE tenant_id=? AND legal_entity_id=? FOR SHARE",
                    b.get("tenant_id"), b.get("legal_entity_id"));
            String permission = Set.of("approve", "reject").contains(action) ? "approve"
                    : action.equals("post") ? "post" : "create";
            if (!allowed(b, permission)) throw error("SCOPE_DENIED", "当前岗位不允许该应收动作");
            var who = gate.current(id(b.get("actor_id")), id(b.get("tenant_id")), true, request);
            c.check();
            var payload = new LinkedHashMap<>(input);
            payload.remove("idempotencyKey");
            String hash = digest(payload), route = action + "/" + target;
            var cached = c.list("SELECT * FROM plugin_finance_ar_request WHERE actor_id=? AND request_key=?",
                    b.get("actor_id"), input.get("idempotencyKey"));
            if (!cached.isEmpty()) {
                var old = cached.getFirst();
                if (!Objects.equals(old.get("tenant_id"), b.get("tenant_id"))
                        || !Objects.equals(old.get("book_id"), book) || !Objects.equals(old.get("year_id"), year)
                        || !route.equals(old.get("route")) || !hash.equals(old.get("payload_hash")))
                    throw error("IDEMPOTENCY_CONFLICT", "同键范围或正文不同");
                var existing = row(c, b, year, id(old.get("object_id")));
                customer(c, principal, b, id(existing.get("customer_id")), id(existing.get("customer_revision_id")));
                return json(old.get("response"));
            }
            Object response;
            try {
                response = action.equals("create") ? create(c, principal, b, year, who, payload)
                        : change(c, principal, b, year, who, action, target, payload);
            } catch (Rejected ex) { throw error(ex.code, "应收状态、额度或业务依赖不满足条件"); }
            var result = (Map<?, ?>) response;
            c.update("INSERT INTO plugin_finance_ar_request(actor_id,request_key,tenant_id,book_id,year_id,route,payload_hash,object_id,response) VALUES(?,?,?,?,?,?,?,?,?::jsonb)",
                    b.get("actor_id"), input.get("idempotencyKey"), b.get("tenant_id"), book, year, route,
                    hash, id(result.get("id")), finance.encode(response));
            return response;
        });
    }

    private Map<String, Object> customer(Call c, String principal, Map<String, Object> b, UUID customer, UUID revision) {
        var r = c.one("SELECT p.home_book_id,p.approved_revision_id,p.pending_revision_id,r.content,r.content_digest,r.desired_status,r.status FROM plugin_finance_counterparty_registry p JOIN plugin_finance_counterparty_revision r ON (r.tenant_id,r.legal_entity_id,r.counterparty_id,r.id)=(p.tenant_id,p.legal_entity_id,p.id,p.approved_revision_id) WHERE p.tenant_id=? AND p.legal_entity_id=? AND p.id=?",
                b.get("tenant_id"), b.get("legal_entity_id"), customer);
        c.timeout();
        finance.originalBookScope(principal, id(r.get("home_book_id")), "read");
        c.check();
        var content = json(r.get("content"));
        if (!revision.equals(r.get("approved_revision_id")) || r.get("pending_revision_id") != null
                || !"ACTIVE".equals(r.get("desired_status")) || !"APPROVED".equals(r.get("status"))
                || !(content.get("roles") instanceof List<?> roles) || !roles.contains("CUSTOMER"))
            throw error("AR_CUSTOMER_STALE", "客户当前修订未批准、已冻结或不再是客户");
        return map("id", customer.toString(), "revisionId", revision.toString(),
                "digest", r.get("content_digest"), "homeBookId", r.get("home_book_id").toString());
    }

    private Map<String, Object> original(Call c, Map<String, Object> b, UUID year, UUID original) {
        var o = c.one("SELECT id,sha256,created_by,version,filename FROM plugin_finance_original WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? AND sha256=encode(sha256(content),'hex')",
                b.get("tenant_id"), b.get("id"), year, original);
        return map("id", original.toString(), "sha256", o.get("sha256"),
                "createdBy", o.get("created_by").toString(), "version", o.get("version").toString(),
                "authenticity", "UNVERIFIED");
    }

    private Map<String, Object> create(Call c, String principal, Map<String, Object> b, UUID year,
            Map<String, Object> who, Map<String, Object> payload) {
        Kind kind = Kind.valueOf(payload.get("kind").toString());
        UUID customerId = id(payload.get("customerId")), revision = id(payload.get("customerRevisionId"));
        var customer = customer(c, principal, b, customerId, revision);
        var evidence = original(c, b, year, id(payload.get("originalId")));
        checkDate(c, b, year, payload.get("date").toString());
        var oldOperation = c.list("SELECT id,initial_payload FROM plugin_finance_ar_object WHERE tenant_id=? AND book_id=? AND year_id=? AND operation_id=?",
                b.get("tenant_id"), b.get("id"), year, id(payload.get("operationId")));
        if (!oldOperation.isEmpty()) {
            if (!json(oldOperation.getFirst().get("initial_payload")).equals(payload))
                throw error("AR_BUSINESS_IDENTITY_CONFLICT", "业务操作身份正文冲突");
            return summary(row(c, b, year, id(oldOperation.getFirst().get("id"))));
        }
        var sources = sources(c, b, year, kind, payload, null);
        var content = map("schema", "AR_CNY_V1", "request", payload, "customer", customer,
                "original", evidence, "sources", sources);
        if (financial(kind)) {
            var p = dependency().resolve(typedScope(b, year), kind, payload, sources);
            validatePlan(kind, payload, p);
            content.put("plan", p);
        }
        UUID rid = UUID.randomUUID();
        UUID chain = kind == Kind.CONTRACT || kind == Kind.RECEIPT ? rid
                : id(sources.get("businessChainId"));
        UUID reversal = kind == Kind.REVERSAL ? id(payload.get("originalObjectId")) : null;
        c.update("INSERT INTO plugin_finance_ar_object(tenant_id,legal_entity_id,book_id,year_id,id,operation_id,business_key,business_chain_id,kind,customer_id,customer_revision_id,original_id,accounting_date,amount,content,content_digest,initial_payload,prepared_by,reverses_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?::jsonb,?)",
                b.get("tenant_id"), b.get("legal_entity_id"), b.get("id"), year, rid,
                id(payload.get("operationId")), payload.get("businessKey"), chain, kind.name(),
                customerId, revision, id(payload.get("originalId")), LocalDate.parse(payload.get("date").toString()),
                amount(payload.get("amount")), finance.encode(content), digest(content), finance.encode(payload), finance.encode(who), reversal);
        audit(c, b, year, rid, "CREATE", who, payload.get("reason").toString());
        return summary(row(c, b, year, rid));
    }

    private boolean financial(Kind k) { return k != Kind.CONTRACT && k != Kind.PERFORMANCE; }

    private Map<String, Object> source(Call c, Map<String, Object> b, UUID y, UUID id,
            Kind expected, UUID customer, UUID current) {
        var r = row(c, b, y, id);
        State expectedState = financial(expected) ? State.POSTED : State.CONFIRMED;
        if (!expected.name().equals(r.get("kind")) || !expectedState.name().equals(r.get("state"))
                || !customer.equals(r.get("customer_id")) || r.get("reversed_by") != null)
            throw error("AR_SOURCE_UNAVAILABLE", "来源类型、状态、客户或更正链不满足条件");
        var pending = c.list("SELECT id FROM plugin_finance_ar_object WHERE tenant_id=? AND book_id=? AND year_id=? AND reverses_id=? AND state IN('DRAFT','SUBMITTED','APPROVED') AND id<>?",
                b.get("tenant_id"), b.get("id"), y, id, current == null ? new UUID(0, 0) : current);
        if (!pending.isEmpty()) throw error("AR_PENDING_CORRECTION", "来源存在待处理更正");
        return map("id", id.toString(), "kind", r.get("kind"), "version", r.get("version").toString(),
                "contentDigest", r.get("content_digest"), "businessChainId", r.get("business_chain_id").toString(),
                "amount", stored(amount(r.get("amount"))), "journalId", r.get("journal_id") == null ? null : r.get("journal_id").toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sources(Call c, Map<String, Object> b, UUID y, Kind kind,
            Map<String, Object> payload, UUID current) {
        UUID customer = id(payload.get("customerId"));
        var out = new LinkedHashMap<String, Object>();
        switch (kind) {
            case CONTRACT, RECEIPT -> { }
            case PERFORMANCE, RECOGNITION -> {
                var src = source(c, b, y, id(payload.get(kind == Kind.PERFORMANCE ? "contractId" : "performanceId")),
                        kind == Kind.PERFORMANCE ? Kind.CONTRACT : Kind.PERFORMANCE, customer, current);
                out.put("parent", src); out.put("businessChainId", src.get("businessChainId"));
                var parent = row(c, b, y, id(src.get("id")));
                require(!LocalDate.parse(payload.get("date").toString()).isBefore(LocalDate.parse(parent.get("accounting_date").toString())),
                        "AR_SOURCE_DATE_AFTER_EFFECT");
            }
            case ALLOCATION -> {
                var receipt = source(c, b, y, id(payload.get("receiptId")), Kind.RECEIPT, customer, current);
                out.put("receipt", receipt); out.put("businessChainId", receipt.get("businessChainId"));
                var items = new ArrayList<Map<String, Object>>();
                for (var item : (List<Map<String, Object>>) payload.get("items"))
                    items.add(source(c, b, y, id(item.get("recognitionId")), Kind.RECOGNITION, customer, current));
                out.put("items", items);
            }
            case REVERSAL -> {
                UUID originalId = id(payload.get("originalObjectId"));
                var original = row(c, b, y, originalId);
                Kind originalKind = Kind.valueOf(original.get("kind").toString());
                if (!Set.of(Kind.RECOGNITION, Kind.RECEIPT, Kind.ALLOCATION).contains(originalKind))
                    throw error("AR_UNSUPPORTED_CORRECTION", "仅支持收入、收款或核销的完整反向");
                var src = source(c, b, y, originalId, originalKind, customer, current);
                require(amount(payload.get("amount")).compareTo(amount(original.get("amount"))) == 0,
                        "AR_FULL_REVERSAL_REQUIRED");
                var active = c.list("SELECT 1 FROM plugin_finance_ar_usage WHERE tenant_id=? AND book_id=? AND year_id=? AND parent_id=? AND state IN('RESERVED','COMMITTED') LIMIT 1",
                        b.get("tenant_id"), b.get("id"), y, originalId);
                require(active.isEmpty(), "AR_DEPENDENCY_PRESENT");
                out.put("original", src); out.put("businessChainId", src.get("businessChainId"));
                out.put("originalPlan", json(original.get("content")).get("plan"));
            }
        }
        return out;
    }

    private void validatePlan(Kind kind, Map<String, Object> payload, Plan p) {
        require(p != null && p.decisionId() != null && p.decisionVersion() > 0
                && p.approvedByPersonId() != null && p.expiresAt() != null && p.expiresAt().isAfter(Instant.now())
                && p.decisionDigest() != null && p.decisionDigest().matches("[a-f0-9]{64}")
                && p.policyVersion() != null && !p.policyVersion().isBlank(), "AR_DECISION_UNAVAILABLE");
        String expected = switch (kind) {
            case RECOGNITION -> "AR_REVENUE"; case RECEIPT -> "AR_RECEIPT";
            case ALLOCATION -> "AR_ALLOCATION"; case REVERSAL -> "AR_REVERSAL";
            default -> throw new Rejected("INVALID_INPUT");
        };
        require(expected.equals(p.nativeType()) && p.accountingDate() != null && p.gross() != null
                && p.accountingDate().equals(LocalDate.parse(payload.get("date").toString()))
                && p.gross().compareTo(amount(payload.get("amount"))) == 0, "AR_DECISION_MISMATCH");
        require(p.lines().size() >= 2 && p.lines().size() <= 100, "AR_DECISION_MISMATCH");
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (var line : p.lines()) {
            require(line.accountId() != null && line.accountVersion() > 0 && line.role() != null
                    && line.debit() != null && line.credit() != null && line.debit().signum() >= 0
                    && line.credit().signum() >= 0 && (line.debit().signum() > 0 ^ line.credit().signum() > 0),
                    "AR_DECISION_MISMATCH");
            require(line.debit().stripTrailingZeros().scale() <= 2 && line.credit().stripTrailingZeros().scale() <= 2,
                    "INVALID_CURRENCY_SCALE");
            debit = debit.add(line.debit()); credit = credit.add(line.credit());
        }
        require(debit.compareTo(credit) == 0 && debit.compareTo(p.gross()) == 0, "AR_UNBALANCED_PLAN");
        if (kind == Kind.RECOGNITION) {
            var tax = p.taxFact();
            require(tax != null && tax.taxEntityId() != null && tax.taxEntityRevisionId() != null
                    && tax.decisionId() != null && tax.version() > 0 && tax.treatment() != null
                    && tax.basisOriginalId() != null && tax.approvedByPersonId() != null
                    && tax.ruleVersion() != null && !tax.ruleVersion().isBlank()
                    && tax.digest() != null && tax.digest().matches("[a-f0-9]{64}")
                    && tax.taxAmount() != null && tax.taxAmount().signum() >= 0 && p.net() != null && p.net().signum() > 0,
                    "AR_TAX_FACT_UNAVAILABLE");
            require(p.net().add(tax.taxAmount()).compareTo(p.gross()) == 0, "AR_DECISION_MISMATCH");
            require(tax.treatment() == TaxTreatment.TAXABLE || tax.taxAmount().signum() == 0, "AR_DECISION_MISMATCH");
            require("UNCONDITIONAL_RECEIVABLE".equals(p.immutableDependencies().get("recognitionClass")), "AR_CONTRACT_ASSET_UNSUPPORTED");
            checkRoles(p, Map.of(AccountRole.RECEIVABLE, p.gross()),
                    Map.of(AccountRole.REVENUE, p.net(), AccountRole.OUTPUT_TAX, tax.taxAmount()));
        } else if (kind == Kind.RECEIPT) {
            require("INTERNAL_MANUAL_RECEIPT".equals(p.immutableDependencies().get("verificationMode")), "AR_BANK_CHANNEL_UNVERIFIED");
            checkRoles(p, Map.of(AccountRole.CASH, p.gross()), Map.of(AccountRole.CUSTOMER_ADVANCE, p.gross()));
        } else if (kind == Kind.ALLOCATION)
            checkRoles(p, Map.of(AccountRole.CUSTOMER_ADVANCE, p.gross()), Map.of(AccountRole.RECEIVABLE, p.gross()));
    }

    private void checkRoles(Plan p, Map<AccountRole, BigDecimal> expectedDebits, Map<AccountRole, BigDecimal> expectedCredits) {
        var debits = new EnumMap<AccountRole, BigDecimal>(AccountRole.class);
        var credits = new EnumMap<AccountRole, BigDecimal>(AccountRole.class);
        for (var line : p.lines()) {
            if (line.debit().signum() > 0) debits.merge(line.role(), line.debit(), BigDecimal::add);
            if (line.credit().signum() > 0) credits.merge(line.role(), line.credit(), BigDecimal::add);
        }
        for (var role : AccountRole.values()) {
            require(debits.getOrDefault(role, BigDecimal.ZERO).compareTo(expectedDebits.getOrDefault(role, BigDecimal.ZERO)) == 0
                    && credits.getOrDefault(role, BigDecimal.ZERO).compareTo(expectedCredits.getOrDefault(role, BigDecimal.ZERO)) == 0,
                    "AR_ACCOUNT_ROLE_MISMATCH");
        }
    }

    private void checkDate(Call c, Map<String, Object> b, UUID y, String date) {
        c.one("SELECT p.id FROM plugin_finance_period p JOIN plugin_finance_year y ON(y.tenant_id,y.book_id,y.id)=(p.tenant_id,p.book_id,p.year_id) WHERE p.tenant_id=? AND p.book_id=? AND p.year_id=? AND y.year=extract(year from ?::date) AND p.month=extract(month from ?::date) AND p.status='OPEN' AND y.status='OPEN' FOR UPDATE OF p",
                b.get("tenant_id"), b.get("id"), y, date, date);
    }

    private void recheckPartyActor(Call c, Map<String, Object> b, UUID y, Map<String, Object> actor,
            String action, UUID customer, UUID revision) {
        recheck(c, b, y, actor, action);
        var member = c.one("SELECT username FROM plugin_finance_member WHERE tenant_id=? AND book_id=? AND user_id=? AND enabled",
                b.get("tenant_id"), b.get("id"), id(actor.get("accountId")));
        customer(c, member.get("username").toString(), b, customer, revision);
    }

    private Map<String, Object> change(Call c, String principal, Map<String, Object> b, UUID y,
            Map<String, Object> who, String action, UUID target, Map<String, Object> command) {
        var r = row(c, b, y, target);
        require(Long.parseLong(r.get("version").toString()) == version(command.get("expectedVersion")), "VERSION_CONFLICT");
        Kind kind = Kind.valueOf(r.get("kind").toString());
        State state = State.valueOf(r.get("state").toString());
        State next = transition(kind, state, action);
        var maker = json(r.get("prepared_by"));
        if (Set.of("submit", "withdraw", "cancel").contains(action)) originalAccount(who, maker);
        if (Set.of("approve", "reject").contains(action)) independent(who, maker);
        UUID customerId = id(r.get("customer_id")), revision = id(r.get("customer_revision_id"));
        // Exit actions remain possible after source/customer revocation, but current user scope/identity still applies.
        boolean advancing = Set.of("submit", "approve", "post").contains(action);
        var content = json(r.get("content"));
        var payload = (Map<String, Object>) content.get("request");
        if (advancing) {
            recheckPartyActor(c, b, y, maker, "create", customerId, revision);
            require(content.get("customer").equals(customer(c, principal, b, customerId, revision)), "AR_CUSTOMER_STALE");
            require(content.get("original").equals(original(c, b, y, id(r.get("original_id")))), "AR_SOURCE_STALE");
            require(content.get("sources").equals(sources(c, b, y, kind, payload, target)), "AR_SOURCE_STALE");
            checkDate(c, b, y, payload.get("date").toString());
            if (financial(kind)) {
                Plan p = plan(content); validatePlan(kind, payload, p);
                dependency().revalidate(typedScope(b, y), p, (Map<String, Object>) content.get("sources"));
            }
        }
        if (action.equals("submit")) reserve(c, b, y, target, kind, payload);
        if (Set.of("withdraw", "reject", "cancel").contains(action))
            c.update("UPDATE plugin_finance_ar_usage SET state='RELEASED' WHERE tenant_id=? AND book_id=? AND year_id=? AND consumer_id=? AND state='RESERVED'",
                    b.get("tenant_id"), b.get("id"), y, target);
        UUID journal = null;
        if (action.equals("post")) {
            var approved = json(r.get("approved_by"));
            recheckPartyActor(c, b, y, approved, "approve", customerId, revision);
            independent(approved, maker);
            var expires = ((java.sql.Timestamp) r.get("approval_expires_at")).toInstant();
            require(expires.isAfter(Instant.now()), "AR_APPROVAL_EXPIRED");
            Plan p = plan(content);
            UUID reversedJournal = null;
            if (kind == Kind.REVERSAL) {
                var original = row(c, b, y, id(r.get("reverses_id")));
                reversedJournal = id(original.get("journal_id"));
                var originalPlan = plan(json(original.get("content")));
                require(p.lines().size() == originalPlan.lines().size(), "AR_REVERSAL_LINES_MISMATCH");
                for (int i = 0; i < p.lines().size(); i++) {
                    var a = p.lines().get(i); var old = originalPlan.lines().get(i);
                    require(a.accountId().equals(old.accountId()) && a.accountVersion() == old.accountVersion()
                            && a.role() == old.role() && a.debit().compareTo(old.credit()) == 0
                            && a.credit().compareTo(old.debit()) == 0, "AR_REVERSAL_LINES_MISMATCH");
                }
            }
            String key = digest(List.of("finance.ar.v1", b.get("tenant_id").toString(), bookString(b), y.toString(), target.toString(), kind.name()));
            var producer = new Producer(target, id(r.get("business_chain_id")), target, "finance.ar.v1", key,
                    r.get("content_digest").toString(), p.nativeType(), p, typedActor(maker), typedActor(approved), reversedJournal);
            var proof = dependency().post(typedScope(b, y), producer, typedActor(who));
            require(proof != null && proof.journalId() != null && proof.businessEffectId() != null
                    && proof.consumptionReceiptId() != null && proof.outboxId() != null && proof.postingSequence() > 0
                    && producer.contentDigest().equals(proof.contentDigest()) && p.nativeType().equals(proof.nativeType()),
                    "AR_POSTING_PROOF_MISSING");
            dependency().verifyPosted(typedScope(b, y), producer, proof);
            c.one("SELECT j.id FROM plugin_finance_journal j JOIN plugin_finance_posting p ON(p.tenant_id,p.book_id,p.year_id,p.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id) WHERE j.tenant_id=? AND j.book_id=? AND j.year_id=? AND j.id=? AND j.status='POSTED' AND j.type=?",
                    b.get("tenant_id"), b.get("id"), y, proof.journalId(), p.nativeType());
            journal = proof.journalId();
            contentProof(c, b, y, target, proof);
        }
        c.update("UPDATE plugin_finance_ar_object SET state=?,version=version+1,approved_by=?::jsonb,approval_expires_at=CASE WHEN ?='APPROVED' THEN CURRENT_TIMESTAMP+interval '24 hours' ELSE NULL END,journal_id=? WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                next.name(), next == State.APPROVED || next == State.CONFIRMED ? finance.encode(who)
                        : next == State.POSTED ? finance.encode(json(r.get("approved_by"))) : null,
                next.name(), journal, b.get("tenant_id"), b.get("id"), y, target);
        if (next == State.CONFIRMED || next == State.POSTED) {
            c.update("UPDATE plugin_finance_ar_usage SET state='COMMITTED' WHERE tenant_id=? AND book_id=? AND year_id=? AND consumer_id=? AND state='RESERVED'",
                    b.get("tenant_id"), b.get("id"), y, target);
            String purpose = switch (kind) {
                case CONTRACT -> "PERFORMANCE"; case PERFORMANCE -> "ACCOUNTING_CONFIRMATION";
                case RECOGNITION -> "SETTLEMENT"; case RECEIPT -> "RECEIPT_ALLOCATION"; default -> null;
            };
            if (purpose != null) c.update("INSERT INTO plugin_finance_ar_capacity(tenant_id,book_id,year_id,parent_id,purpose,capacity) VALUES(?,?,?,?,?,?)",
                    b.get("tenant_id"), b.get("id"), y, target, purpose, amount(r.get("amount")));
            if (kind == Kind.REVERSAL) {
                c.update("UPDATE plugin_finance_ar_usage SET state='REVERSED',reversal_id=? WHERE tenant_id=? AND book_id=? AND year_id=? AND consumer_id=? AND state='COMMITTED'",
                        target, b.get("tenant_id"), b.get("id"), y, r.get("reverses_id"));
                c.update("UPDATE plugin_finance_ar_object SET reversed_by=? WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        target, b.get("tenant_id"), b.get("id"), y, r.get("reverses_id"));
            }
        }
        audit(c, b, y, target, action.toUpperCase(Locale.ROOT), who, command.get("reason").toString());
        return summary(row(c, b, y, target));
    }

    private String bookString(Map<String, Object> b) { return b.get("id").toString(); }
    private void contentProof(Call c, Map<String, Object> b, UUID y, UUID target, Posted proof) {
        c.update("INSERT INTO plugin_finance_ar_posted(tenant_id,book_id,year_id,object_id,journal_id,proof) VALUES(?,?,?,?,?,?::jsonb)",
                b.get("tenant_id"), b.get("id"), y, target, proof.journalId(), finance.encode(proof));
    }
    @SuppressWarnings("unchecked")
    private void reserve(Call c, Map<String, Object> b, UUID y, UUID target, Kind kind, Map<String, Object> p) {
        if (kind == Kind.PERFORMANCE) usage(c, b, y, id(p.get("contractId")), target, "PERFORMANCE", p.get("amount"));
        if (kind == Kind.RECOGNITION) usage(c, b, y, id(p.get("performanceId")), target, "ACCOUNTING_CONFIRMATION", p.get("amount"));
        if (kind == Kind.ALLOCATION) {
            usage(c, b, y, id(p.get("receiptId")), target, "RECEIPT_ALLOCATION", p.get("amount"));
            for (var item : (List<Map<String, Object>>) p.get("items"))
                usage(c, b, y, id(item.get("recognitionId")), target, "SETTLEMENT", item.get("amount"));
        }
    }
    private void usage(Call c, Map<String, Object> b, UUID y, UUID parent, UUID consumer, String purpose, Object value) {
        c.update("INSERT INTO plugin_finance_ar_usage(tenant_id,book_id,year_id,id,parent_id,consumer_id,purpose,amount,state) VALUES(?,?,?,?,?,?,?,?,'RESERVED')",
                b.get("tenant_id"), b.get("id"), y, UUID.randomUUID(), parent, consumer, purpose, amount(value));
    }
    private void audit(Call c, Map<String, Object> b, UUID y, UUID target, String action, Map<String, Object> who, String reason) {
        var snapshot = summary(row(c, b, y, target));
        c.update("INSERT INTO plugin_finance_ar_audit(tenant_id,book_id,year_id,id,object_id,action,actor,reason,snapshot) VALUES(?,?,?,?,?,?,?::jsonb,?,?::jsonb)",
                b.get("tenant_id"), b.get("id"), y, UUID.randomUUID(), target, action, finance.encode(who), reason, finance.encode(snapshot));
    }
    private Map<String, Object> summary(Map<String, Object> r) {
        return map("id", r.get("id").toString(), "kind", r.get("kind"), "state", r.get("state"),
                "version", r.get("version").toString(), "amount", stored(amount(r.get("amount"))),
                "customerId", r.get("customer_id").toString(), "businessChainId", r.get("business_chain_id").toString(),
                "journalId", r.get("journal_id") == null ? null : r.get("journal_id").toString(),
                "reversedBy", r.get("reversed_by") == null ? null : r.get("reversed_by").toString());
    }

    public Object read(String principal, UUID book, UUID year, UUID target, String after, int limit, LocalDate asOf) {
        if (limit < 1 || limit > 100 || asOf == null) throw FinanceException.invalid("查询范围无效");
        return transaction(false, c -> {
            var b = c.scope(principal, book, year, null);
            if (target != null) {
                var r = row(c, b, year, target);
                var out = summary(r); out.put("content", json(r.get("content")));
                out.put("balances", balances(c, b, year, r, asOf));
                return out;
            }
            UUID cursor = after == null ? new UUID(0, 0) : id(after);
            var rows = c.list("SELECT * FROM plugin_finance_ar_object WHERE tenant_id=? AND book_id=? AND year_id=? AND id>? ORDER BY id LIMIT ?",
                    b.get("tenant_id"), book, year, cursor, limit + 1);
            boolean more = rows.size() > limit;
            var selected = more ? rows.subList(0, limit) : rows;
            var items = new ArrayList<Map<String, Object>>();
            for (var r : selected) { var s = summary(r); s.put("balances", balances(c, b, year, r, asOf)); items.add(s); }
            return map("items", items, "nextCursor", more ? selected.getLast().get("id").toString() : null,
                    "asOf", asOf.toString(), "total", null, "nativeProducerAvailable", dependencies.isPresent(),
                    "bankChannel", "NOT_VERIFIED", "acceptance", "CANDIDATE_NOT_VERIFIED");
        });
    }
    private Object balances(Call c, Map<String, Object> b, UUID y, Map<String, Object> r, LocalDate asOf) {
        var rows = c.list("SELECT purpose,capacity,reserved,committed FROM plugin_finance_ar_capacity WHERE tenant_id=? AND book_id=? AND year_id=? AND parent_id=? ORDER BY purpose",
                b.get("tenant_id"), b.get("id"), y, r.get("id"));
        var result = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            BigDecimal open = amount(row.get("capacity")).subtract(amount(row.get("committed")));
            var value = map("purpose", row.get("purpose"), "capacity", stored(amount(row.get("capacity"))),
                    "outstanding", r.get("reversed_by") == null ? stored(open) : "0.00000000",
                    "reserved", stored(amount(row.get("reserved"))),
                    "available", r.get("reversed_by") == null ? stored(open.subtract(amount(row.get("reserved")))) : "0.00000000");
            if ("RECOGNITION".equals(r.get("kind"))) {
                var request = (Map<?, ?>) json(r.get("content")).get("request");
                value.put("aging", r.get("reversed_by") != null ? "REVERSED" : aging(LocalDate.parse(request.get("dueDate").toString()), asOf, open));
            }
            result.add(value);
        }
        return result;
    }
}
