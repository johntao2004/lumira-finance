package com.lumira.finance;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.UserDirectoryPort;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.AuthenticationSession;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Organization-attested directory only. No existing financial workflow person enforcement. */
@Service
public class FinancePersonDirectory {
    static final Set<String> CREATE =
            Set.of(
                    "schemaVersion",
                    "idempotencyKey",
                    "operation",
                    "accountId",
                    "personId",
                    "displayName",
                    "sourceReference",
                    "sourceSha256",
                    "reason",
                    "noAccountTransferConfirmed",
                    "allKnownAccountsDeclared");
    static final Set<String> CONFIRM =
            Set.of(
                    "schemaVersion",
                    "idempotencyKey",
                    "expectedVersion",
                    "contentDigest",
                    "decision",
                    "reason");
    static final Set<String> CANCEL =
            Set.of("schemaVersion", "idempotencyKey", "expectedVersion", "contentDigest", "reason");
    static final List<String> LIMITATIONS =
            List.of(
                    "组织人工核验断言，不是政府KYC或物理自然人实时验真",
                    "目录本身不启用全局财务职责分离；凭证以各年度人员审签模式为准；cash/year-close未接入，历史批准未回填",
                    "近期最终登录+TOTP会话门槛，不是因子独立时间或action-bound step-up",
                    "账号禁止转让、撤销后不可重绑；合并/纠错/恢复暂不支持");
    final FinanceStore db;
    final FinanceService json;
    final AdministrationPort permissions;
    final UserDirectoryPort users;
    final AccountOwnerPort owners;
    final PlatformTransactionManager transactions;

    final FinancePersonDirectoryBudget budget;
    final LongSupplier clock;

    @org.springframework.beans.factory.annotation.Autowired
    public FinancePersonDirectory(
            FinanceStore db,
            FinanceService json,
            AdministrationPort permissions,
            UserDirectoryPort users,
            AccountOwnerPort owners,
            PlatformTransactionManager transactions) {
        this(db, json, permissions, users, owners, transactions, System::nanoTime, null);
    }

    private FinancePersonDirectory(
            FinanceStore db,
            FinanceService json,
            AdministrationPort permissions,
            UserDirectoryPort users,
            AccountOwnerPort owners,
            PlatformTransactionManager transactions,
            LongSupplier clock,
            FinancePersonDirectoryBudget budget) {
        this.budget = budget;
        this.clock = clock;
        this.db = db;
        this.json = json;
        this.permissions = permissions;
        this.users = users;
        this.owners = owners;
        this.transactions = transactions;
    }

    FinancePersonDirectory withClock(LongSupplier clock) {
        return new FinancePersonDirectory(
                db, json, permissions, users, owners, transactions, clock, null);
    }

    <T> T spi(Supplier<T> work) {
        return budget == null ? work.get() : budget.spi(work);
    }

    Object invoke(
            boolean readOnly, java.util.function.Function<FinancePersonDirectory, Object> work) {
        var deadline = new FinancePersonDirectoryBudget(db, clock);
        var scoped =
                new FinancePersonDirectory(
                        deadline.store(),
                        json,
                        permissions,
                        users,
                        owners,
                        transactions,
                        clock,
                        deadline);
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(readOnly);
        tx.setTimeout(10);
        if (readOnly) tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            Object result =
                    tx.execute(
                            status -> {
                                org.springframework.transaction.support
                                        .TransactionSynchronizationManager.registerSynchronization(
                                        new org.springframework.transaction.support
                                                .TransactionSynchronization() {
                                            @Override
                                            public void beforeCommit(boolean ignored) {
                                                deadline.configure();
                                            }
                                        });
                                Object value = work.apply(scoped);
                                deadline.check();
                                return value;
                            });
            // No post-commit write deadline failure: a successful commit remains successful.
            if (readOnly) deadline.check();
            return result;
        } catch (RuntimeException e) {
            throw FinancePersonDirectoryBudget.translate(e);
        }
    }

    static Map<String, Object> map(Object... v) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < v.length; i += 2) m.put(v[i].toString(), v[i + 1]);
        return m;
    }

    static FinanceException error(String code, String text) {
        return new FinanceException(code, text);
    }

    static String text(Object v, int max) {
        if (!(v instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.chars().anyMatch(Character::isISOControl)
                || !s.equals(s.strip())) throw FinanceException.invalid("字段须为无控制符的规范文本");
        return s;
    }

    static UUID uuid(Object v) {
        String s = text(v, 36);
        UUID id = UUID.fromString(s);
        if (!id.toString().equals(s)) throw FinanceException.invalid("UUID无效");
        return id;
    }

    static String digest(Object v) {
        String s = text(v, 64);
        if (!s.matches("[0-9a-f]{64}")) throw FinanceException.invalid("摘要无效");
        return s;
    }

    static String counter(Object v) {
        String s = text(v, 19);
        if (!s.matches("0|[1-9][0-9]*")) throw FinanceException.invalid("版本须为非负十进制字符串");
        try {
            Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw FinanceException.invalid("版本溢出");
        }
        return s;
    }

    String hash(Object v) {
        return FinanceService.hash(json.encode(json.canonical(v)));
    }

    static Map<String, Object> normalized(String action, Map<String, Object> body) {
        if (body == null
                || !body.keySet()
                        .equals(
                                action.equals("create")
                                        ? CREATE
                                        : action.equals("confirm") ? CONFIRM : CANCEL))
            throw FinanceException.invalid("字段缺失或未知");
        Map<String, Object> n = new LinkedHashMap<>(body);
        if (!"1".equals(n.get("schemaVersion"))) throw FinanceException.invalid("schemaVersion无效");
        text(n.get("idempotencyKey"), 128);
        text(n.get("reason"), 1000);
        if (action.equals("create")) {
            String op = text(n.get("operation"), 32);
            if (!Set.of("CREATE_PERSON_AND_BIND", "BIND_EXISTING_PERSON", "REVOKE_BINDING")
                    .contains(op))
                throw error("PERSON_OPERATION_UNSUPPORTED", "仅支持创建并绑定、绑定已有人员或撤销绑定");
            uuid(n.get("accountId"));
            text(n.get("sourceReference"), 500);
            digest(n.get("sourceSha256"));
            if (!Boolean.TRUE.equals(n.get("noAccountTransferConfirmed"))
                    || !Boolean.TRUE.equals(n.get("allKnownAccountsDeclared")))
                throw FinanceException.invalid("须主动声明禁止账号转让及所有已知账号");
            if (op.equals("CREATE_PERSON_AND_BIND")) {
                if (n.get("personId") != null) throw FinanceException.invalid("新人员ID由服务端创建");
                text(n.get("displayName"), 120);
            } else {
                uuid(n.get("personId"));
                if (n.get("displayName") != null) throw FinanceException.invalid("已有人员不可改名");
            }
        } else {
            counter(n.get("expectedVersion"));
            digest(n.get("contentDigest"));
            if (action.equals("confirm")
                    && !Set.of("APPROVE", "REJECT").contains(n.get("decision")))
                throw FinanceException.invalid("decision无效");
        }
        return n;
    }

    record Actor(
            UUID account, UUID person, String name, List<String> abilities, String authDigest) {}

    Actor actor(
            String principal,
            UUID tenant,
            String ability,
            boolean recent,
            HttpServletRequest request) {
        spi(() -> permissions.require(principal, "platform:finance:access"));
        spi(
                () ->
                        permissions.require(
                                principal,
                                "platform:finance:person-" + ability.toLowerCase(Locale.ROOT)));
        var c = AuthenticationRequestContext.current();
        if (c == null
                || !Objects.equals(c.principalName(), principal)
                || c.userId() == null
                || c.policy() == null
                || c.authorization() == null
                || !principal.equals(c.authorization().principalName())
                || !c.userId().equals(c.authorization().actorId()))
            throw error("SCOPE_DENIED", "缺少宿主已验证请求上下文");
        var rows =
                db.list(
                        "SELECT b.person_id,g.abilities FROM plugin_finance_person_binding b JOIN"
                            + " plugin_finance_person_governance g ON"
                            + " (g.tenant_id,g.account_id,g.binding_id,g.person_id)=(b.tenant_id,b.account_id,b.id,b.person_id)"
                            + " WHERE b.tenant_id=? AND b.account_id=? AND b.status='ACTIVE' AND"
                            + " ?=ANY(g.abilities)",
                        tenant,
                        c.userId(),
                        ability);
        if (rows.isEmpty()) throw error("SCOPE_DENIED", "缺少租户人员治理授权");
        if (recent) assurance(principal, c, request, Instant.now());
        var policy = c.policy();
        var auth =
                map(
                        "account",
                        c.userId(),
                        "person",
                        rows.getFirst().get("person_id"),
                        "policy",
                        policy.revision(),
                        "primary",
                        policy.primaryCredentialRevision(),
                        "factor",
                        policy.factorCredentialRevision(),
                        "authorities",
                        c.authorization() == null
                                ? null
                                : c.authorization().effectiveAuthorities().stream()
                                        .sorted()
                                        .toList(),
                        "global",
                        c.authorization() == null
                                ? null
                                : c.authorization().effectiveGlobalAuthorities().stream()
                                        .sorted()
                                        .toList(),
                        "preview",
                        c.authorization() == null ? null : c.authorization().previewActive(),
                        "authorizationRevision",
                        c.authorization() == null ? null : c.authorization().revision(),
                        "targetRole",
                        c.authorization() == null ? null : c.authorization().targetRoleId(),
                        "targetRoleVersion",
                        c.authorization() == null || c.authorization().preview() == null
                                ? null
                                : c.authorization().preview().roleVersion());
        return new Actor(
                c.userId(),
                (UUID) rows.getFirst().get("person_id"),
                principal,
                List.of("READ", "PROPOSE", "CONFIRM"),
                hash(auth));
    }

    static void assurance(
            String name,
            AuthenticationRequestContext.Snapshot c,
            HttpServletRequest request,
            Instant now) {
        var session = request == null ? null : request.getSession(false);
        var p = c.policy();
        if (session == null
                || !name.equals(c.principalName())
                || !(session.getAttribute(AuthenticationSession.AUTHENTICATED_AT)
                        instanceof Instant at)
                || at.isAfter(now.plusSeconds(30))
                || at.isBefore(now.minusSeconds(300))
                || !(session.getAttribute(AuthenticationSession.PRIMARY_METHOD)
                        instanceof String method)
                || !p.allowedMethods().stream().anyMatch(x -> x.name().equals(method))
                || !p.usableMethods().stream().anyMatch(x -> x.name().equals(method))
                || !Objects.equals(
                        session.getAttribute(AuthenticationSession.AUTHENTICATION_REVISION),
                        p.revision().toString())
                || !Objects.equals(
                        session.getAttribute(AuthenticationSession.PRIMARY_CREDENTIAL_REVISION),
                        p.primaryCredentialRevision().toString())
                || !Objects.equals(
                        session.getAttribute(AuthenticationSession.FACTOR_CREDENTIAL_REVISION),
                        p.factorCredentialRevision().toString()))
            throw error("PERSON_AUTH_ASSURANCE_REQUIRED", "需五分钟内最终登录及当前有效TOTP会话");
        Object f = session.getAttribute(AuthenticationSession.VERIFIED_FACTORS);
        if (!(f instanceof List<?> list)
                || list.stream().anyMatch(x -> !(x instanceof String))
                || !list.contains("TOTP"))
            throw error("PERSON_AUTH_ASSURANCE_REQUIRED", "需TOTP会话，恢复码不能替代本门槛");
    }

    Map<String, Object> realm(UUID tenant, boolean lock) {
        var list =
                db.list(
                        "SELECT * FROM plugin_finance_person_realm WHERE tenant_id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        tenant);
        if (list.isEmpty()) throw error("PERSON_DIRECTORY_UNAVAILABLE", "该租户尚未由部署责任人初始化人员目录");
        return list.getFirst();
    }

    Map<String, Object> proposal(UUID tenant, UUID id) {
        return db.one(
                "SELECT * FROM plugin_finance_person_proposal WHERE tenant_id=? AND id=?",
                tenant,
                id);
    }

    Map<String, Object> summary(Map<String, Object> p) {
        return map(
                "id",
                p.get("id"),
                "operation",
                p.get("operation"),
                "status",
                p.get("status"),
                "accountId",
                p.get("account_id"),
                "personId",
                p.get("person_id"),
                "displayName",
                p.get("display_name"),
                "preparedBy",
                p.get("prepared_by"),
                "preparedPersonId",
                p.get("prepared_person_id"),
                "createdAt",
                p.get("created_at"),
                "expiresAt",
                p.get("expires_at"),
                "version",
                p.get("version").toString(),
                "contentDigest",
                p.get("content_digest"),
                "decidedBy",
                p.get("decided_by"),
                "decidedPersonId",
                p.get("decided_person_id"),
                "decidedAt",
                p.get("decided_at"));
    }

    Map<String, Object> detail(UUID tenant, UUID id) {
        var p = proposal(tenant, id);
        return map(
                "proposal",
                summary(p),
                "sourceReference",
                p.get("source_reference"),
                "sourceSha256",
                p.get("source_sha256"),
                "reason",
                p.get("reason"),
                "decisionReason",
                p.get("decision_reason"),
                "noAccountTransferConfirmed",
                json.decode(p.get("content").toString()).get("noAccountTransferConfirmed"),
                "allKnownAccountsDeclared",
                json.decode(p.get("content").toString()).get("allKnownAccountsDeclared"),
                "effectPersonId",
                p.get("effect_person_id"),
                "effectBindingId",
                p.get("effect_binding_id"),
                "generation",
                realm(tenant, false).get("generation").toString(),
                "businessSodEnforcement",
                "NOT_ENFORCED");
    }

    Map<String, Object> personSummary(Map<String, Object> p) {
        return map(
                "id",
                p.get("id"),
                "displayName",
                p.get("display_name"),
                "personReference",
                p.get("person_reference"),
                "assurance",
                "ORG_ATTESTED",
                "createdAt",
                p.get("created_at"),
                "version",
                p.get("version").toString());
    }

    public Object read(
            String principal,
            UUID tenant,
            String resource,
            UUID id,
            MultiValueMap<String, String> query) {
        return invoke(true, scoped -> scoped.readInside(principal, tenant, resource, id, query));
    }

    public Object write(
            String principal,
            UUID tenant,
            String action,
            UUID id,
            Map<String, Object> body,
            HttpServletRequest request) {
        return invoke(
                false, scoped -> scoped.writeInside(principal, tenant, action, id, body, request));
    }

    private Object readInside(
            String principal,
            UUID tenant,
            String resource,
            UUID id,
            MultiValueMap<String, String> query) {

        var a = actor(principal, tenant, "READ", false, null);
        var r = realm(tenant, false);
        if (id != null) {
            if (!query.isEmpty()) throw FinanceException.invalid("未知query");
            if (resource.equals("proposals")) return detail(tenant, id);
            var p =
                    db.one(
                            "SELECT * FROM plugin_finance_person WHERE tenant_id=? AND" + " id=?",
                            tenant,
                            id);
            var rows =
                    db.list(
                            "SELECT * FROM plugin_finance_person_binding WHERE"
                                    + " tenant_id=? AND person_id=? ORDER BY created_at,id"
                                    + " LIMIT 101",
                            tenant,
                            id);
            boolean more = rows.size() > 100;
            var bindings =
                    rows.stream()
                            .limit(100)
                            .map(
                                    b ->
                                            map(
                                                    "id",
                                                    b.get("id"),
                                                    "accountId",
                                                    b.get("account_id"),
                                                    "personId",
                                                    b.get("person_id"),
                                                    "status",
                                                    b.get("status"),
                                                    "governanceSeed",
                                                    b.get("governance_seed"),
                                                    "createdAt",
                                                    b.get("created_at"),
                                                    "revokedAt",
                                                    b.get("revoked_at"),
                                                    "proposalId",
                                                    b.get("proposal_id"),
                                                    "version",
                                                    b.get("version").toString()))
                            .toList();
            return map(
                    "person",
                    personSummary(p),
                    "bindings",
                    bindings,
                    "bindingsTruncated",
                    more,
                    "sourceReference",
                    p.get("source_reference"),
                    "sourceSha256",
                    p.get("source_sha256"));
        }
        if (resource.equals("configuration")) {
            if (!query.isEmpty()) throw FinanceException.invalid("未知query");
            return map(
                    "schemaVersion",
                    "1",
                    "tenantId",
                    tenant,
                    "authorityId",
                    r.get("authority_id"),
                    "authorityName",
                    r.get("authority_name"),
                    "assurance",
                    "ORG_ATTESTED",
                    "bootstrapManifestSha256",
                    r.get("manifest_sha256"),
                    "generation",
                    r.get("generation").toString(),
                    "businessSodEnforcement",
                    "NOT_ENFORCED",
                    "authenticationGate",
                    map(
                            "type",
                            "RECENT_FINAL_LOGIN_WITH_TOTP",
                            "maximumLoginAgeSeconds",
                            300,
                            "futureToleranceSeconds",
                            30,
                            "actionBound",
                            false),
                    "supportedOperations",
                    List.of("CREATE_PERSON_AND_BIND", "BIND_EXISTING_PERSON", "REVOKE_BINDING"),
                    "limitations",
                    LIMITATIONS,
                    "currentActorAccountId",
                    a.account(),
                    "currentActorPersonId",
                    a.person(),
                    "currentActorAbilities",
                    a.abilities());
        }
        if (resource.equals("readiness")) {
            if (!query.isEmpty()) throw FinanceException.invalid("未知query");
            var counts =
                    db.one(
                            "SELECT (SELECT count(DISTINCT person_id)::text FROM"
                                    + " plugin_finance_person_binding WHERE tenant_id=? AND"
                                    + " status='ACTIVE') persons,(SELECT count(*)::text"
                                    + " FROM plugin_finance_person_binding WHERE"
                                    + " tenant_id=? AND status='ACTIVE') bindings",
                            tenant,
                            tenant);
            return map(
                    "tenantId",
                    tenant,
                    "directoryConfigured",
                    true,
                    "assurance",
                    "ORG_ATTESTED",
                    "generation",
                    r.get("generation").toString(),
                    "activePersonCount",
                    counts.get("persons"),
                    "activeBindingCount",
                    counts.get("bindings"),
                    "businessSodEnforcement",
                    "NOT_ENFORCED",
                    "legacyApprovalPersonProof",
                    "NOT_BACKFILLED",
                    "actionBoundStepUp",
                    false,
                    "limitations",
                    LIMITATIONS);
        }
        return page(tenant, resource, a, r, query);
    }

    Object page(
            UUID tenant,
            String resource,
            Actor a,
            Map<String, Object> r,
            MultiValueMap<String, String> query) {
        if (!Set.of("persons", "proposals").contains(resource)
                || !Set.of("limit", "cursor").containsAll(query.keySet())
                || query.values().stream().anyMatch(x -> x.size() != 1))
            throw FinanceException.invalid("query无效");
        int limit = 20;
        String l = query.getFirst("limit");
        if (l != null) {
            if (!l.matches("[1-9][0-9]{0,2}")) throw FinanceException.invalid("limit无效");
            limit = Integer.parseInt(l);
            if (limit > 100) throw FinanceException.invalid("limit上限100");
        }
        String generation = r.get("generation").toString();
        Map<String, Object> ctx =
                map(
                        "tenant",
                        tenant.toString(),
                        "resource",
                        resource,
                        "actor",
                        a.account().toString(),
                        "auth",
                        a.authDigest(),
                        "generation",
                        generation,
                        "limit",
                        limit);
        Map<String, Object> c = null;
        if (query.containsKey("cursor")) {
            String token = text(query.getFirst("cursor"), 4096);
            try {
                c =
                        json.decode(
                                new String(
                                        Base64.getUrlDecoder().decode(token),
                                        StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw FinanceException.invalid("cursor无效");
            }
            if (!c.keySet().equals(Set.of("context", "at", "id")))
                throw FinanceException.invalid("cursor字段无效");
            if (!Objects.equals(c.get("context"), hash(ctx)))
                throw error("PERSON_DIRECTORY_PAGE_STALE", "目录或授权上下文变化，请从首屏重取");
            uuid(c.get("id"));
            try {
                Instant.parse(text(c.get("at"), 40));
            } catch (RuntimeException e) {
                throw FinanceException.invalid("cursor时间无效");
            }
        }
        String table =
                resource.equals("persons")
                        ? "plugin_finance_person"
                        : "plugin_finance_person_proposal";
        var rows =
                c == null
                        ? db.list(
                                "SELECT * FROM "
                                        + table
                                        + " WHERE tenant_id=? ORDER BY created_at DESC,id DESC"
                                        + " LIMIT ?",
                                tenant,
                                limit + 1)
                        : db.list(
                                "SELECT * FROM "
                                        + table
                                        + " WHERE tenant_id=? AND"
                                        + " (created_at,id)<(?::timestamptz,?::uuid) ORDER BY"
                                        + " created_at DESC,id DESC LIMIT ?",
                                tenant,
                                c.get("at"),
                                c.get("id"),
                                limit + 1);
        boolean more = rows.size() > limit;
        var visible = rows.subList(0, Math.min(limit, rows.size()));
        String next = null;
        if (more) {
            var last = visible.getLast();
            next =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    json.encode(
                                                    map(
                                                            "context",
                                                            hash(ctx),
                                                            "at",
                                                            ((java.sql.Timestamp)
                                                                            last.get("created_at"))
                                                                    .toInstant()
                                                                    .toString(),
                                                            "id",
                                                            last.get("id").toString()))
                                            .getBytes(StandardCharsets.UTF_8));
        }
        return map(
                "items",
                visible.stream()
                        .map(p -> resource.equals("persons") ? personSummary(p) : summary(p))
                        .toList(),
                "nextCursor",
                next,
                "generation",
                generation);
    }

    void target(UUID account) {
        try {
            spi(() -> users.versionSnapshot(account));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            if (e.getStatusCode().value() == 404) throw FinanceException.invalid("目标账号不存在");
            throw e;
        }
    }

    void freshTarget(UUID tenant, Map<String, Object> p) {
        UUID account = uuid(p.get("account_id").toString());
        target(account);
        String op = p.get("operation").toString();
        var old =
                db.list(
                        "SELECT * FROM plugin_finance_person_binding WHERE tenant_id=? AND"
                                + " account_id=?",
                        tenant,
                        account);
        if (op.equals("REVOKE_BINDING")) {
            if (old.isEmpty()
                    || !old.getFirst().get("person_id").equals(p.get("person_id"))
                    || !old.getFirst().get("status").equals("ACTIVE"))
                throw error("PERSON_PROPOSAL_STALE", "待撤销绑定已变化");
            if (Boolean.TRUE.equals(old.getFirst().get("governance_seed")))
                throw error("PERSON_SEED_PROTECTED", "治理种子不可撤销");
        } else {
            if (!old.isEmpty()) throw error("PERSON_BINDING_CONFLICT", "账号已有永久绑定历史，禁止转让/重绑");
            if (p.get("person_id") != null)
                db.one(
                        "SELECT id FROM plugin_finance_person WHERE tenant_id=? AND id=?",
                        tenant,
                        p.get("person_id"));
        }
    }

    void maker(UUID tenant, Map<String, Object> p) {
        try {
            String name = p.get("prepared_username").toString();
            spi(() -> permissions.require(name, "platform:finance:access"));
            spi(() -> permissions.require(name, "platform:finance:person-propose"));
            if (!spi(() -> owners.id(name, false)).equals(p.get("prepared_by")))
                throw new IllegalArgumentException();
            var b =
                    db.list(
                            "SELECT b.id FROM plugin_finance_person_binding b JOIN"
                                + " plugin_finance_person_governance g ON"
                                + " (g.tenant_id,g.account_id,g.binding_id,g.person_id)=(b.tenant_id,b.account_id,b.id,b.person_id)"
                                + " WHERE b.tenant_id=? AND b.account_id=? AND b.person_id=? AND"
                                + " b.status='ACTIVE' AND 'PROPOSE'=ANY(g.abilities)",
                            tenant,
                            p.get("prepared_by"),
                            p.get("prepared_person_id"));
            if (b.isEmpty()) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            RuntimeException mapped = FinancePersonDirectoryBudget.translate(e);
            if (FinancePersonDirectoryBudget.budgetFailure(mapped)) throw mapped;
            throw error("PERSON_PROPOSAL_STALE", "原制备人当前治理权限或身份失效");
        }
    }

    void independent(UUID tenant, Actor a, Map<String, Object> p) {
        if (a.account().equals(p.get("account_id"))
                || a.person().equals(p.get("prepared_person_id"))
                || a.person().equals(p.get("person_id"))
                || !db.list(
                                "SELECT id FROM plugin_finance_person_binding WHERE tenant_id=? AND"
                                        + " account_id=? AND person_id=?",
                                tenant,
                                p.get("account_id"),
                                a.person())
                        .isEmpty()) throw error("SELF_APPROVAL_FORBIDDEN", "制备人或目标本人不得确认");
    }

    private Object writeInside(
            String principal,
            UUID tenant,
            String action,
            UUID id,
            Map<String, Object> body,
            HttpServletRequest request) {
        var n = normalized(action, body);
        if (json.encode(n).getBytes(StandardCharsets.UTF_8).length
                > (action.equals("create") ? 16384 : 8192))
            throw FinanceException.invalid("请求体超出限制");
        String ability = action.equals("confirm") ? "CONFIRM" : "PROPOSE";
        actor(principal, tenant, ability, true, request);

        realm(tenant, true);
        var a = actor(principal, tenant, ability, true, request);
        String route = action + ":" + id;
        String h = hash(n);
        Map<String, Object> p = id == null ? null : proposal(tenant, id);
        if (p != null) {
            if (action.equals("cancel")) {
                if (!a.account().equals(p.get("prepared_by")))
                    throw error("SCOPE_DENIED", "仅原制备账号可取消");
            } else {
                independent(tenant, a, p);
                if ("APPROVE".equals(n.get("decision"))) maker(tenant, p);
            }
        }
        var cached =
                db.list(
                        "SELECT * FROM plugin_finance_person_request WHERE tenant_id=?"
                                + " AND actor_id=? AND request_key=?",
                        tenant,
                        a.account(),
                        n.get("idempotencyKey"));
        if (!cached.isEmpty()) {
            var old = cached.getFirst();
            if (!old.get("request_hash").equals(h) || !old.get("route").equals(route))
                throw error("IDEMPOTENCY_CONFLICT", "请求键内容不一致");
            return detail(tenant, (UUID) old.get("proposal_id"));
        }
        UUID result = id;
        if (action.equals("create")) {
            result = UUID.randomUUID();
            var target =
                    map(
                            "account_id",
                            uuid(n.get("accountId")),
                            "person_id",
                            n.get("personId") == null ? null : uuid(n.get("personId")),
                            "operation",
                            n.get("operation"));
            freshTarget(tenant, target);
            var content = new LinkedHashMap<>(n);
            content.remove("idempotencyKey");
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_person_proposal(tenant_id,id,operation,account_id,person_id,display_name,source_reference,source_sha256,content,content_text,content_digest,reason,prepared_by,prepared_username,prepared_person_id)"
                        + " VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?)",
                    tenant,
                    result,
                    n.get("operation"),
                    target.get("account_id"),
                    target.get("person_id"),
                    n.get("displayName"),
                    n.get("sourceReference"),
                    n.get("sourceSha256"),
                    json.encode(json.canonical(content)),
                    json.encode(json.canonical(content)),
                    hash(content),
                    n.get("reason"),
                    a.account(),
                    principal,
                    a.person());
        } else {
            if (!"PENDING".equals(p.get("status"))
                    || !p.get("version").toString().equals(n.get("expectedVersion"))
                    || !p.get("content_digest").equals(n.get("contentDigest")))
                throw error("VERSION_CONFLICT", "提案版本或状态已变化");
            String state =
                    action.equals("cancel")
                            ? "CANCELLED"
                            : "APPROVE".equals(n.get("decision")) ? "APPROVED" : "REJECTED";
            UUID ep = null, eb = null;
            if (state.equals("APPROVED")) {
                if (!((java.sql.Timestamp) p.get("expires_at")).toInstant().isAfter(Instant.now()))
                    throw error("PERSON_PROPOSAL_EXPIRED", "七天运营审签期限已过，请驳回或取消");
                freshTarget(tenant, p);
                ep = p.get("person_id") == null ? UUID.randomUUID() : (UUID) p.get("person_id");
                eb =
                        p.get("operation").equals("REVOKE_BINDING")
                                ? (UUID)
                                        db.one(
                                                        "SELECT id FROM"
                                                                + " plugin_finance_person_binding"
                                                                + " WHERE tenant_id=? AND"
                                                                + " account_id=?",
                                                        tenant,
                                                        p.get("account_id"))
                                                .get("id")
                                : UUID.randomUUID();
            }
            db.update(
                    "UPDATE plugin_finance_person_proposal SET"
                        + " status=?,version=version+1,decided_by=?,decided_person_id=?,decided_at=CURRENT_TIMESTAMP,decision_reason=?,effect_person_id=?,effect_binding_id=?"
                        + " WHERE tenant_id=? AND id=?",
                    state,
                    a.account(),
                    a.person(),
                    n.get("reason"),
                    ep,
                    eb,
                    tenant,
                    id);
        }
        db.update(
                "INSERT INTO"
                    + " plugin_finance_person_request(tenant_id,actor_id,request_key,request_hash,route,proposal_id)"
                    + " VALUES(?,?,?,?,?,?)",
                tenant,
                a.account(),
                n.get("idempotencyKey"),
                h,
                route,
                result);
        return detail(tenant, result);
    }
}
