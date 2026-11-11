package com.lumira.finance;

import static com.lumira.finance.payroll.PayrollCalculation.*;
import static com.lumira.finance.payroll.PayrollService.*;

import com.lumira.finance.payroll.*;
import com.lumira.platform.common.AuthorizationContextProjectionPort;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runtime adapter for PAY01/PAY02 payroll calculation and withholding candidate. */
@Service
public final class FinancePayroll extends FinanceProfitClosingAccess {
    private static final Set<String> BAD_INPUT_CODES =
            Set.of(
                    "SCOPE",
                    "COMMAND",
                    "BOOK_SCOPE",
                    "DRAFT",
                    "DRAFT_HEADER",
                    "EMPLOYMENT_INPUT",
                    "LINES_REQUIRED",
                    "LINE_LIMIT",
                    "DUPLICATE_EMPLOYEE",
                    "CNY_PRECISION_OR_RANGE",
                    "EXEMPT_EXCEEDS_INCOME",
                    "EMPLOYMENT_MONTHS",
                    "DEDUCTION_MODE",
                    "CORRECTION_REASON");
    private final PayrollService payroll;
    private final Optional<PayrollService.Evidence> evidenceAdapter;
    private final ThreadLocal<HttpServletRequest> currentRequest = new ThreadLocal<>();

    public FinancePayroll(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager transactions,
            AuthorizationContextProjectionPort authorization,
            Optional<PayrollService.Evidence> evidenceAdapter) {
        super(db, finance, gate, transactions, authorization);
        this.evidenceAdapter = evidenceAdapter;
        this.payroll = new PayrollService(accessAdapter(), defaultEvidence(), storeAdapter());
    }

    private Access accessAdapter() {
        return (principal, scope, book, permission, employees) -> {
            String perm =
                    permission.startsWith("verify.")
                            ? "post"
                            : switch (permission) {
                                case "read" -> "read";
                                case "review", "approve" -> "approve";
                                case "lock" -> "post";
                                default -> "create";
                            };
            var b = finance.originalBookScope(principal, book, perm);
            if (!scope.tenant().equals(id(b.get("tenant_id"))) || !allowed(b, perm))
                throw error("SCOPE_DENIED", "当前岗位不允许该薪酬动作");
            verifyTaxEntity(scope.tenant(), id(b.get("legal_entity_id")), scope.entity());
            if ("read".equals(permission)) {
                UUID actorId = id(b.get("actor_id"));
                return new Actor(actorId, actorId, actorId);
            }
            var who =
                    gate.current(
                            id(b.get("actor_id")),
                            scope.tenant(),
                            Set.of("review", "approve", "lock").contains(permission)
                                    || permission.startsWith("verify."),
                            currentRequest.get());
            return new Actor(
                    id(who.get("accountId")), id(who.get("personId")), id(who.get("bindingId")));
        };
    }

    @SuppressWarnings("unchecked")
    private Evidence defaultEvidence() {
        return new Evidence() {
            @Override
            public RulePack rule(YearScope scope, UUID ruleId, LocalDate date) {
                if (evidenceAdapter.isPresent())
                    return evidenceAdapter.get().rule(scope, ruleId, date);
                var rows =
                        db.list(
                                "SELECT version,effective_from,effective_through,body::text AS"
                                    + " body,digest,approval_evidence,approved_by_person FROM"
                                    + " plugin_finance_payroll_rule_pack WHERE tenant_id=? AND"
                                    + " id=? AND kind='RESIDENT_WAGE'",
                                scope.tenant(),
                                ruleId);
                if (rows.isEmpty())
                    throw error("PAYROLL_RULE_PACK_UNAVAILABLE", "个税累计预扣规则包不存在或尚未由独立审签人批准");
                var r = rows.getFirst();
                var bodyMap = finance.decode(r.get("body").toString());
                String canonicalBodyDigest =
                        FinanceService.hash(finance.encode(finance.canonical(bodyMap)));
                if (!canonicalBodyDigest.equals(r.get("digest")))
                    throw error("PAYROLL_RULE_PACK_CORRUPT", "个税规则包正文摘要校验失败");
                BigDecimal monthlyBasic =
                        new BigDecimal(bodyMap.get("monthlyBasic").toString())
                                .setScale(2, RoundingMode.UNNECESSARY);
                var rawBrackets = (List<Map<String, Object>>) bodyMap.get("brackets");
                List<Bracket> brackets = new ArrayList<>();
                for (var bm : rawBrackets) {
                    BigDecimal upper =
                            bm.get("upper") == null
                                    ? null
                                    : new BigDecimal(bm.get("upper").toString());
                    brackets.add(
                            new Bracket(
                                    upper,
                                    new BigDecimal(bm.get("rate").toString()),
                                    new BigDecimal(bm.get("quick").toString())));
                }
                Approval approval =
                        new Approval(
                                id(r.get("approved_by_person")),
                                id(r.get("approval_evidence")),
                                canonicalBodyDigest);
                RulePack pack =
                        new RulePack(
                                r.get("version").toString(),
                                canonicalBodyDigest,
                                LocalDate.parse(r.get("effective_from").toString()),
                                LocalDate.parse(r.get("effective_through").toString()),
                                approval,
                                brackets,
                                monthlyBasic);
                pack.effective(date);
                return pack;
            }

            @Override
            public ExceptionEvidence exception(YearScope scope, UUID employee, UUID reference) {
                if (evidenceAdapter.isPresent())
                    return evidenceAdapter.get().exception(scope, employee, reference);
                throw error("PAYROLL_EXCEPTION_EVIDENCE_UNAVAILABLE", "特定扣除减除模式证据尚未由独立治理契约批准接入");
            }

            @Override
            public String validateLine(YearScope scope, Draft draft, Line line) {
                if (evidenceAdapter.isPresent())
                    return evidenceAdapter.get().validateLine(scope, draft, line);
                var rows =
                        db.list(
                                "SELECT id,starts_on,ends_on,residency,evidence_id,evidence_digest"
                                    + " FROM plugin_finance_payroll_employment WHERE tenant_id=?"
                                    + " AND tax_entity_id=? AND employee_id=? AND (id=? OR"
                                    + " evidence_id=?) ORDER BY revision DESC LIMIT 1",
                                scope.tenant(),
                                scope.entity(),
                                line.employee(),
                                line.employmentRevision(),
                                line.employmentRevision());
                if (rows.isEmpty())
                    throw error(
                            "PAYROLL_EMPLOYMENT_EVIDENCE_UNAVAILABLE",
                            "员工任职受雇与社保核定修订证据不存在或不属于当前扣缴义务人");
                var emp = rows.getFirst();
                if (!"RESIDENT".equals(emp.get("residency")))
                    throw error("PAYROLL_NONRESIDENT_UNSUPPORTED", "当前受限切片仅支持居民个人工资薪金累计预扣");
                LocalDate startsOn = LocalDate.parse(emp.get("starts_on").toString());
                LocalDate endsOn =
                        emp.get("ends_on") == null
                                ? null
                                : LocalDate.parse(emp.get("ends_on").toString());
                if (line.earnedFrom().isBefore(startsOn)
                        || (endsOn != null && line.earnedThrough().isAfter(endsOn)))
                    throw error("PAYROLL_EARNED_INTERVAL_OUTSIDE_EMPLOYMENT", "计薪所属期超出员工任职受雇有效区间");
                if (draft.kind() == Kind.NORMAL
                        && endsOn != null
                        && endsOn.isBefore(draft.payDate().withDayOfMonth(1)))
                    throw error("PAYROLL_TERMINATED_REQUIRES_ARREARS", "离职后跨期补发须使用ARREARS批次");
                var att =
                        db.list(
                                "SELECT sha256 FROM plugin_finance_original WHERE tenant_id=? AND"
                                    + " book_id=? AND id=? AND sha256=encode(sha256(content),'hex')",
                                scope.tenant(),
                                draft.book(),
                                line.attendanceEvidence());
                if (att.isEmpty())
                    throw error("PAYROLL_ATTENDANCE_EVIDENCE_INVALID", "考勤与工时核定原件不存在或摘要不符");
                return storeAdapter()
                        .digest(
                                List.of(
                                        emp.get("id").toString(),
                                        emp.get("evidence_digest").toString(),
                                        att.getFirst().get("sha256").toString(),
                                        line));
            }

            @Override
            public Cumulative opening(YearScope scope, UUID employee, LocalDate payDate) {
                if (evidenceAdapter.isPresent())
                    return evidenceAdapter.get().opening(scope, employee, payDate);
                var rows =
                        db.list(
                                "SELECT min(starts_on) AS first_start FROM"
                                    + " plugin_finance_payroll_employment WHERE tenant_id=? AND"
                                    + " tax_entity_id=? AND employee_id=?",
                                scope.tenant(),
                                scope.entity(),
                                employee);
                Object fs = rows.isEmpty() ? null : rows.getFirst().get("first_start");
                if (fs != null) {
                    LocalDate firstStart = LocalDate.parse(fs.toString());
                    if (firstStart.getYear() == scope.year() || payDate.getMonthValue() == 1)
                        return new Cumulative(
                                "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00");
                }
                throw error("PAYROLL_OPENING_BASELINE_UNAVAILABLE", "年中迁入员工缺少已核定期初累计预扣基准");
            }

            @Override
            public String dependencies(YearScope scope, Draft draft) {
                if (evidenceAdapter.isPresent())
                    return evidenceAdapter.get().dependencies(scope, draft);
                var ruleRows =
                        db.list(
                                "SELECT digest FROM plugin_finance_payroll_rule_pack WHERE"
                                        + " tenant_id=? AND id=?",
                                scope.tenant(),
                                draft.rulePack());
                String ruleDigest =
                        ruleRows.isEmpty() ? "MISSING" : ruleRows.getFirst().get("digest").toString();
                List<String> empDigests = new ArrayList<>();
                for (Line line : draft.lines()) {
                    var empRows =
                            db.list(
                                    "SELECT residency,evidence_digest FROM"
                                        + " plugin_finance_payroll_employment WHERE tenant_id=?"
                                        + " AND tax_entity_id=? AND employee_id=? AND (id=? OR"
                                        + " evidence_id=?) ORDER BY revision DESC LIMIT 1",
                                    scope.tenant(),
                                    scope.entity(),
                                    line.employee(),
                                    line.employmentRevision(),
                                    line.employmentRevision());
                    empDigests.add(
                            empRows.isEmpty()
                                    ? "MISSING"
                                    : empRows.getFirst().get("residency")
                                            + ":"
                                            + empRows.getFirst().get("evidence_digest"));
                }
                return storeAdapter()
                        .digest(List.of(scope, draft.rulePack(), ruleDigest, empDigests, draft.lines()));
            }

            @Override
            public Fact fact(YearScope scope, Batch batch, FactKind kind, UUID reference) {
                if (evidenceAdapter.isPresent())
                    return evidenceAdapter.get().fact(scope, batch, kind, reference);
                if (kind == FactKind.ACCRUAL_POSTED) {
                    var rows =
                            db.list(
                                    "SELECT p.digest,(SELECT COALESCE(SUM(l.debit),0) FROM"
                                        + " plugin_finance_line l WHERE"
                                        + " (l.tenant_id,l.book_id,l.year_id,l.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id))"
                                        + " AS total_debit FROM plugin_finance_journal j JOIN"
                                        + " plugin_finance_posting p ON"
                                        + " (p.tenant_id,p.book_id,p.year_id,p.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id)"
                                        + " WHERE j.tenant_id=? AND j.book_id=? AND j.id=? AND"
                                        + " j.status='POSTED' AND extract(year FROM j.date)=?",
                                    scope.tenant(),
                                    batch.draft().book(),
                                    reference,
                                    scope.year());
                    if (rows.isEmpty())
                        throw error("PAYROLL_FACT_PRODUCER_UNAVAILABLE", "未找到当前账套年度已正式记账的工资计提凭证");
                    var r = rows.getFirst();
                    BigDecimal debitTotal =
                            new BigDecimal(r.get("total_debit").toString())
                                    .setScale(2, RoundingMode.UNNECESSARY);
                    return new Fact(kind, reference, r.get("digest").toString(), debitTotal);
                }
                var evRows =
                        db.list(
                                "SELECT status,original_sha256,amount FROM"
                                    + " plugin_finance_payroll_fact_evidence WHERE tenant_id=?"
                                    + " AND tax_entity_id=? AND tax_year=? AND batch_id=? AND id=?"
                                    + " AND kind=?",
                                scope.tenant(),
                                scope.entity(),
                                scope.year(),
                                batch.draft().id(),
                                reference,
                                kind.name());
                if (evRows.isEmpty() || !"APPROVED".equals(evRows.getFirst().get("status")))
                    throw error("PAYROLL_FACT_PRODUCER_UNAVAILABLE", "薪酬发放、扣缴或申报受理回执尚未登记或尚未独立批准");
                var ev = evRows.getFirst();
                return new Fact(
                        kind,
                        reference,
                        ev.get("original_sha256").toString(),
                        new BigDecimal(ev.get("amount").toString())
                                .setScale(2, RoundingMode.UNNECESSARY));
            }
        };
    }

    private Store storeAdapter() {
        var txTemplate = new TransactionTemplate(transactions);
        return new Store() {
            @Override
            public String digest(Object value) {
                try {
                    return HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    gate.json
                                                            .writeValueAsString(value)
                                                            .getBytes(StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    throw new IllegalStateException("PAYROLL_SERIALIZATION", e);
                }
            }

            private String encodeJson(Object value) {
                String out = gate.json.writeValueAsString(value);
                if (out.getBytes(StandardCharsets.UTF_8).length > 16 * 1024 * 1024)
                    throw new FinanceException(
                            "PAYROLL_YEAR_CAPACITY_REQUIRES_PARTITIONED_STORE", "薪酬年度聚合超出候选容量上限");
                return out;
            }

            @Override
            public <T> T transaction(YearScope scope, Function<Store.Tx, T> work) {
                return txTemplate.execute(
                        status -> {
                            db.one(
                                    "SELECT set_config('app.tenant_id', ?, true) value",
                                    scope.tenant().toString());
                            db.list(
                                    "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                                    "payroll:"
                                            + scope.tenant()
                                            + ":"
                                            + scope.entity()
                                            + ":"
                                            + scope.year());
                            return work.apply(
                                    new Store.Tx() {
                                        @Override
                                        public YearData load() {
                                            var rows =
                                                    db.list(
                                                            "SELECT payload::text AS payload FROM"
                                                                + " plugin_finance_payroll_year"
                                                                + " WHERE tenant_id=? AND"
                                                                + " tax_entity_id=? AND tax_year=?"
                                                                + " FOR UPDATE",
                                                            scope.tenant(),
                                                            scope.entity(),
                                                            scope.year());
                                            return rows.isEmpty()
                                                    ? new YearData(Map.of())
                                                    : gate.json.readValue(
                                                            rows.getFirst()
                                                                    .get("payload")
                                                                    .toString(),
                                                            YearData.class);
                                        }

                                        @Override
                                        public Batch replay(String receiptKey, String digest) {
                                            var rows =
                                                    db.list(
                                                            "SELECT digest,result::text AS result"
                                                                + " FROM"
                                                                + " plugin_finance_payroll_receipt"
                                                                + " WHERE tenant_id=? AND"
                                                                + " tax_entity_id=? AND tax_year=?"
                                                                + " AND receipt_key=?",
                                                            scope.tenant(),
                                                            scope.entity(),
                                                            scope.year(),
                                                            receiptKey);
                                            if (rows.isEmpty()) return null;
                                            if (!digest.equals(rows.getFirst().get("digest")))
                                                throw error(
                                                        "IDEMPOTENCY_CONFLICT", "薪酬幂等键已绑定不同请求摘要");
                                            return gate.json.readValue(
                                                    rows.getFirst().get("result").toString(),
                                                    Batch.class);
                                        }

                                        @Override
                                        public void save(
                                                YearData data,
                                                String receiptKey,
                                                String digest,
                                                Batch result,
                                                Actor actor,
                                                String action) {
                                            db.update(
                                                    "INSERT INTO"
                                                        + " plugin_finance_payroll_year(tenant_id,tax_entity_id,tax_year,payload)"
                                                        + " VALUES(?,?,?,?::jsonb) ON"
                                                        + " CONFLICT(tenant_id,tax_entity_id,tax_year)"
                                                        + " DO UPDATE SET"
                                                        + " payload=excluded.payload,version=plugin_finance_payroll_year.version+1",
                                                    scope.tenant(),
                                                    scope.entity(),
                                                    scope.year(),
                                                    encodeJson(data));
                                            db.update(
                                                    "INSERT INTO"
                                                        + " plugin_finance_payroll_receipt(tenant_id,tax_entity_id,tax_year,receipt_key,digest,result)"
                                                        + " VALUES(?,?,?,?,?,?::jsonb)",
                                                    scope.tenant(),
                                                    scope.entity(),
                                                    scope.year(),
                                                    receiptKey,
                                                    digest,
                                                    encodeJson(result));
                                            String resultDigest = digest(result);
                                            db.update(
                                                    "INSERT INTO"
                                                        + " plugin_finance_payroll_event(tenant_id,tax_entity_id,tax_year,batch_id,batch_version,actor_account,actor_person,actor_binding,action,payload_digest)"
                                                        + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                                                    scope.tenant(),
                                                    scope.entity(),
                                                    scope.year(),
                                                    result.draft().id(),
                                                    result.version(),
                                                    actor.account(),
                                                    actor.person(),
                                                    actor.binding(),
                                                    action,
                                                    resultDigest);
                                            db.update(
                                                    "INSERT INTO"
                                                        + " plugin_finance_payroll_outbox(tenant_id,tax_entity_id,tax_year,batch_id,batch_version,action,payload_digest)"
                                                        + " VALUES(?,?,?,?,?,?,?)",
                                                    scope.tenant(),
                                                    scope.entity(),
                                                    scope.year(),
                                                    result.draft().id(),
                                                    result.version(),
                                                    action,
                                                    resultDigest);
                                        }
                                    });
                        });
            }
        };
    }

    private void verifyTaxEntity(UUID tenantId, UUID legalEntityId, UUID taxEntityId) {
        var rows =
                db.list(
                        "SELECT g.approved_revision_id,r.desired_status,r.status FROM"
                            + " plugin_finance_tax_entity_registry g JOIN"
                            + " plugin_finance_tax_entity_revision r ON"
                            + " (r.tenant_id,r.legal_entity_id,r.tax_entity_id,r.id)=(g.tenant_id,g.legal_entity_id,g.id,g.approved_revision_id)"
                            + " WHERE g.tenant_id=? AND g.legal_entity_id=? AND g.id=?",
                        tenantId,
                        legalEntityId,
                        taxEntityId);
        if (rows.isEmpty()
                || !"ACTIVE".equals(rows.getFirst().get("desired_status"))
                || !"APPROVED".equals(rows.getFirst().get("status")))
            throw error("SCOPE_DENIED", "扣缴义务人税务主体不存在、不属于当前法人或尚未独立确认激活");
    }

    public YearScope resolveScope(String principal, UUID book, UUID entity, int year) {
        if (year < 2000 || year > 9999) throw FinanceException.invalid("薪酬纳税年度无效");
        var b = finance.originalBookScope(principal, book, "read");
        UUID tenant = id(b.get("tenant_id"));
        verifyTaxEntity(tenant, id(b.get("legal_entity_id")), entity);
        return new YearScope(tenant, entity, year);
    }

    private RuntimeException translate(RuntimeException e) {
        if (e instanceof FinanceException f) return f;
        if (e instanceof IllegalArgumentException iae) {
            String code = iae.getMessage() == null ? "INVALID_INPUT" : iae.getMessage();
            if ("NOT_FOUND".equals(code))
                return error("SCOPE_DENIED", "薪酬批次不存在或当前范围不可访问");
            if ("INDEPENDENT_REVIEWER_REQUIRED".equals(code)
                    || "INDEPENDENT_APPROVER_REQUIRED".equals(code)
                    || "NATURAL_PERSON_SOD".equals(code))
                return error("SELF_APPROVAL_FORBIDDEN", "同一自然人不得自审或自批薪酬批次（" + code + "）");
            if ("IDEMPOTENCY_CONTENT_CONFLICT".equals(code))
                return error("IDEMPOTENCY_CONFLICT", "薪酬幂等键已绑定不同请求摘要");
            if (BAD_INPUT_CODES.contains(code))
                return FinanceException.invalid("薪酬请求参数无效（" + code + "）");
            return error("PAYROLL_" + code, "薪酬批次状态、版本或证据不满足条件（" + code + "）");
        }
        return e;
    }

    public PayrollView read(
            String principal, UUID book, UUID entity, int year, UUID batchId) {
        try {
            YearScope scope = resolveScope(principal, book, entity, year);
            Batch batch = payroll.read(principal, scope, batchId);
            if (!batch.draft().book().equals(book))
                throw FinanceException.invalid("薪酬批次账套与请求路径不一致");
            return PayrollView.from(batch);
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    public PayrollView create(
            String principal,
            UUID book,
            UUID entity,
            int year,
            PayrollController.CreateRequest body,
            HttpServletRequest request) {
        currentRequest.set(request);
        try {
            if (body == null || body.command() == null || body.draft() == null)
                throw FinanceException.invalid("薪酬创建请求不能为空");
            if (!book.equals(body.draft().book()))
                throw FinanceException.invalid("薪酬批次账套与请求路径不一致");
            YearScope scope = resolveScope(principal, book, entity, year);
            return PayrollView.from(
                    payroll.create(principal, scope, body.command(), body.draft()));
        } catch (RuntimeException e) {
            throw translate(e);
        } finally {
            currentRequest.remove();
        }
    }

    public PayrollView act(
            String principal,
            UUID book,
            UUID entity,
            int year,
            UUID batchId,
            PayrollController.ActionRequest body,
            HttpServletRequest request) {
        currentRequest.set(request);
        try {
            if (body == null || body.command() == null || body.action() == null)
                throw FinanceException.invalid("薪酬动作请求不能为空");
            YearScope scope = resolveScope(principal, book, entity, year);
            if (!payroll.read(principal, scope, batchId).draft().book().equals(book))
                throw FinanceException.invalid("薪酬批次账套与请求路径不一致");
            return PayrollView.from(
                    payroll.act(principal, scope, batchId, body.command(), body.action()));
        } catch (RuntimeException e) {
            throw translate(e);
        } finally {
            currentRequest.remove();
        }
    }

    public PayrollView fact(
            String principal,
            UUID book,
            UUID entity,
            int year,
            UUID batchId,
            PayrollController.FactRequest body,
            HttpServletRequest request) {
        currentRequest.set(request);
        try {
            if (body == null
                    || body.command() == null
                    || body.kind() == null
                    || body.reference() == null)
                throw FinanceException.invalid("薪酬核验事实请求不能为空");
            YearScope scope = resolveScope(principal, book, entity, year);
            if (!payroll.read(principal, scope, batchId).draft().book().equals(book))
                throw FinanceException.invalid("薪酬批次账套与请求路径不一致");
            return PayrollView.from(
                    payroll.attachFact(
                            principal,
                            scope,
                            batchId,
                            body.command(),
                            body.kind(),
                            body.reference()));
        } catch (RuntimeException e) {
            throw translate(e);
        } finally {
            currentRequest.remove();
        }
    }

    private String hashCanonical(Object value) {
        return FinanceService.hash(finance.encode(finance.canonical(value)));
    }

    private static String hmacSha256(UUID tenantId, String subjectRef) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            ("lumira-payroll-subject-hmac:" + tenantId)
                                    .getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(subjectRef.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("PAYROLL_HMAC_FAILURE", e);
        }
    }

    private UUID yearIdForCalendarYear(UUID tenantId, UUID bookId, int year) {
        var rows =
                db.list(
                        "SELECT id FROM plugin_finance_year WHERE tenant_id=? AND book_id=? AND"
                                + " year=?",
                        tenantId,
                        bookId,
                        year);
        if (rows.isEmpty()) throw FinanceException.invalid("账套内不存在对应的薪酬会计年度");
        return id(rows.getFirst().get("id"));
    }

    public Object listSetup(String principal, UUID book, UUID entity, int year) {
        YearScope scope = resolveScope(principal, book, entity, year);
        return new TransactionTemplate(transactions)
                .execute(
                        status -> {
                            db.one(
                                    "SELECT set_config('app.tenant_id', ?, true) value",
                                    scope.tenant().toString());
                            var empRows =
                                    db.list(
                                            "SELECT e.id AS employee_id,e.employee_number,m.id AS"
                                                + " employment_id,m.revision,m.residency,m.starts_on,m.ends_on,m.evidence_id"
                                                + " FROM plugin_finance_payroll_employee e JOIN"
                                                + " plugin_finance_payroll_employment m ON"
                                                + " (m.tenant_id,m.employee_id)=(e.tenant_id,e.id)"
                                                + " WHERE m.tenant_id=? AND m.tax_entity_id=? ORDER"
                                                + " BY e.employee_number,m.revision DESC LIMIT 200",
                                            scope.tenant(),
                                            scope.entity());
                            var ruleRows =
                                    db.list(
                                            "SELECT id,version,kind,effective_from,effective_through,digest"
                                                + " FROM plugin_finance_payroll_rule_pack WHERE"
                                                + " tenant_id=? AND kind='RESIDENT_WAGE' ORDER BY"
                                                + " effective_from DESC,id DESC LIMIT 50",
                                            scope.tenant());
                            var factRows =
                                    db.list(
                                            "SELECT id,batch_id,kind,status,version,amount FROM"
                                                + " plugin_finance_payroll_fact_evidence WHERE"
                                                + " tenant_id=? AND tax_entity_id=? AND tax_year=?"
                                                + " AND book_id=? ORDER BY created_at DESC,id DESC"
                                                + " LIMIT 100",
                                            scope.tenant(),
                                            scope.entity(),
                                            scope.year(),
                                            book);
                            var yrRows =
                                    db.list(
                                            "SELECT payload::text AS payload FROM"
                                                + " plugin_finance_payroll_year WHERE tenant_id=?"
                                                + " AND tax_entity_id=? AND tax_year=?",
                                            scope.tenant(),
                                            scope.entity(),
                                            scope.year());
                            List<Map<String, Object>> batchSummaries = new ArrayList<>();
                            if (!yrRows.isEmpty()) {
                                YearData yd =
                                        gate.json.readValue(
                                                yrRows.getFirst().get("payload").toString(),
                                                YearData.class);
                                yd.batches().values().stream()
                                        .filter(b -> book.equals(b.draft().book()))
                                        .sorted(
                                                Comparator.comparing(
                                                                (Batch b) -> b.draft().payDate())
                                                        .thenComparing(b -> b.draft().id()))
                                        .forEach(
                                                b ->
                                                        batchSummaries.add(
                                                                map(
                                                                        "id",
                                                                        b.draft().id().toString(),
                                                                        "payDate",
                                                                        b.draft()
                                                                                .payDate()
                                                                                .toString(),
                                                                        "kind",
                                                                        b.draft().kind().name(),
                                                                        "state",
                                                                        b.state().name(),
                                                                        "version",
                                                                        b.version())));
                            }
                            return map(
                                    "batches",
                                    batchSummaries,
                                    "employments",
                                    empRows.stream()
                                            .map(
                                                    r ->
                                                            map(
                                                                    "employeeId",
                                                                    r.get("employee_id").toString(),
                                                                    "employeeNumber",
                                                                    r.get("employee_number"),
                                                                    "employmentRevisionId",
                                                                    r.get("employment_id")
                                                                            .toString(),
                                                                    "revision",
                                                                    ((Number) r.get("revision"))
                                                                            .longValue(),
                                                                    "residency",
                                                                    r.get("residency"),
                                                                    "startsOn",
                                                                    r.get("starts_on").toString(),
                                                                    "endsOn",
                                                                    r.get("ends_on") == null
                                                                            ? null
                                                                            : r.get("ends_on")
                                                                                    .toString(),
                                                                    "evidenceId",
                                                                    r.get("evidence_id")
                                                                            .toString()))
                                            .toList(),
                                    "rulePacks",
                                    ruleRows.stream()
                                            .map(
                                                    r ->
                                                            map(
                                                                    "id",
                                                                    r.get("id").toString(),
                                                                    "version",
                                                                    r.get("version"),
                                                                    "kind",
                                                                    r.get("kind"),
                                                                    "effectiveFrom",
                                                                    r.get("effective_from")
                                                                            .toString(),
                                                                    "effectiveThrough",
                                                                    r.get("effective_through")
                                                                            .toString(),
                                                                    "digest",
                                                                    r.get("digest")))
                                            .toList(),
                                    "factEvidences",
                                    factRows.stream()
                                            .map(
                                                    r ->
                                                            map(
                                                                    "id",
                                                                    r.get("id").toString(),
                                                                    "batchId",
                                                                    r.get("batch_id").toString(),
                                                                    "kind",
                                                                    r.get("kind"),
                                                                    "status",
                                                                    r.get("status"),
                                                                    "version",
                                                                    r.get("version").toString(),
                                                                    "amount",
                                                                    new BigDecimal(
                                                                                    r.get("amount")
                                                                                            .toString())
                                                                            .setScale(
                                                                                    2,
                                                                                    RoundingMode
                                                                                            .UNNECESSARY)
                                                                            .toPlainString()))
                                            .toList());
                        });
    }

    public Object registerEmployee(
            String principal,
            UUID book,
            UUID entity,
            int year,
            Map<String, Object> raw,
            HttpServletRequest request) {
        Set<String> allowedKeys =
                Set.of(
                        "idempotencyKey",
                        "employeeNumber",
                        "identitySecretRef",
                        "startsOn",
                        "endsOn",
                        "residency",
                        "evidenceOriginalId");
        if (raw == null || !raw.keySet().equals(allowedKeys))
            throw FinanceException.invalid("员工任职登记请求字段无效");
        String reqKey;
        String empNo;
        UUID secretRef;
        LocalDate startsOn;
        LocalDate endsOn;
        String residency;
        UUID evidenceOrigId;
        try {
            reqKey = FinanceReceivablesRules.text(raw.get("idempotencyKey"), 128);
            empNo = FinanceReceivablesRules.text(raw.get("employeeNumber"), 64);
            secretRef = FinanceReceivablesRules.uuid(raw.get("identitySecretRef"));
            startsOn = FinanceReceivablesRules.date(raw.get("startsOn"));
            endsOn =
                    raw.get("endsOn") == null
                            ? null
                            : FinanceReceivablesRules.date(raw.get("endsOn"));
            residency = FinanceReceivablesRules.text(raw.get("residency"), 20);
            if (!Set.of("RESIDENT", "NONRESIDENT", "UNKNOWN").contains(residency))
                throw FinanceException.invalid("员工纳税居民属性无效");
            if (endsOn != null && endsOn.isBefore(startsOn))
                throw FinanceException.invalid("员工离职日期不得早于入职日期");
            evidenceOrigId = FinanceReceivablesRules.uuid(raw.get("evidenceOriginalId"));
        } catch (FinanceReceivablesRules.Rejected ex) {
            throw error(ex.code, "员工任职登记字段或日期格式无效");
        }
        var b = finance.originalBookScope(principal, book, "create");
        if (!allowed(b, "create")) throw error("SCOPE_DENIED", "当前岗位无权登记薪酬员工任职");
        UUID tenantId = id(b.get("tenant_id"));
        UUID legalEntityId = id(b.get("legal_entity_id"));
        verifyTaxEntity(tenantId, legalEntityId, entity);
        gate.current(id(b.get("actor_id")), tenantId, false, request);
        UUID yearId = yearIdForCalendarYear(tenantId, book, year);

        var payload = new LinkedHashMap<>(raw);
        payload.remove("idempotencyKey");
        String payloadHash = hashCanonical(payload);
        String route = "payroll-employee-register/" + book + "/" + entity + "/" + year;

        return new TransactionTemplate(transactions)
                .execute(
                        status -> {
                            db.one(
                                    "SELECT set_config('app.tenant_id', ?, true) value",
                                    tenantId.toString());
                            var cached =
                                    db.list(
                                            "SELECT * FROM plugin_finance_v4_governance_request"
                                                    + " WHERE actor_id=? AND request_key=?",
                                            b.get("actor_id"),
                                            reqKey);
                            if (!cached.isEmpty()) {
                                var old = cached.getFirst();
                                if (!route.equals(old.get("route"))
                                        || !payloadHash.equals(old.get("payload_hash")))
                                    throw error("IDEMPOTENCY_CONFLICT", "同幂等键正文或路由不一致");
                                return json(old.get("response"));
                            }
                            var origRows =
                                    db.list(
                                            "SELECT sha256 FROM plugin_finance_original WHERE"
                                                + " tenant_id=? AND book_id=? AND year_id=? AND"
                                                + " id=? AND sha256=encode(sha256(content),'hex')",
                                            tenantId,
                                            book,
                                            yearId,
                                            evidenceOrigId);
                            if (origRows.isEmpty())
                                throw error("PAYROLL_EMPLOYMENT_EVIDENCE_UNAVAILABLE", "员工任职核定原件不存在或摘要不符");
                            String origSha = origRows.getFirst().get("sha256").toString();

                            var existingEmp =
                                    db.list(
                                            "SELECT id FROM plugin_finance_payroll_employee WHERE"
                                                    + " tenant_id=? AND employee_number=?",
                                            tenantId,
                                            empNo);
                            UUID employeeId;
                            if (existingEmp.isEmpty()) {
                                employeeId = UUID.randomUUID();
                                String idDigest = hmacSha256(tenantId, secretRef.toString());
                                db.update(
                                        "INSERT INTO"
                                            + " plugin_finance_payroll_employee(tenant_id,id,employee_number,identity_secret_ref,identity_digest)"
                                            + " VALUES(?,?,?,?,?)",
                                        tenantId,
                                        employeeId,
                                        empNo,
                                        secretRef,
                                        idDigest);
                            } else {
                                employeeId = id(existingEmp.getFirst().get("id"));
                            }
                            long nextRev =
                                    ((Number)
                                                    db.one(
                                                                    "SELECT COALESCE(MAX(revision),0)+1"
                                                                        + " AS rev FROM"
                                                                        + " plugin_finance_payroll_employment"
                                                                        + " WHERE tenant_id=? AND"
                                                                        + " tax_entity_id=? AND"
                                                                        + " employee_id=?",
                                                                    tenantId,
                                                                    entity,
                                                                    employeeId)
                                                            .get("rev"))
                                            .longValue();
                            UUID employmentId = UUID.randomUUID();
                            db.update(
                                    "INSERT INTO"
                                        + " plugin_finance_payroll_employment(tenant_id,id,employee_id,tax_entity_id,legal_entity_id,starts_on,ends_on,revision,residency,evidence_id,evidence_digest)"
                                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                                    tenantId,
                                    employmentId,
                                    employeeId,
                                    entity,
                                    legalEntityId,
                                    startsOn,
                                    endsOn,
                                    nextRev,
                                    residency,
                                    evidenceOrigId,
                                    origSha);
                            var response =
                                    map(
                                            "employeeId",
                                            employeeId.toString(),
                                            "employeeNumber",
                                            empNo,
                                            "employmentRevisionId",
                                            employmentId.toString(),
                                            "revision",
                                            nextRev,
                                            "residency",
                                            residency,
                                            "startsOn",
                                            startsOn.toString(),
                                            "endsOn",
                                            endsOn == null ? null : endsOn.toString(),
                                            "evidenceId",
                                            evidenceOrigId.toString());
                            db.update(
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

    @SuppressWarnings("unchecked")
    public Object registerRulePack(
            String principal,
            UUID book,
            UUID entity,
            int year,
            Map<String, Object> raw,
            HttpServletRequest request) {
        Set<String> allowedKeys =
                Set.of(
                        "idempotencyKey",
                        "version",
                        "effectiveFrom",
                        "effectiveThrough",
                        "monthlyBasic",
                        "brackets",
                        "sourceEvidenceId",
                        "approvalEvidenceId");
        if (raw == null || !raw.keySet().equals(allowedKeys))
            throw FinanceException.invalid("个税累计预扣规则包请求字段无效");
        String reqKey;
        String versionStr;
        LocalDate fromDate;
        LocalDate throughDate;
        BigDecimal monthlyBasic;
        UUID sourceEvId;
        UUID approvalEvId;
        try {
            reqKey = FinanceReceivablesRules.text(raw.get("idempotencyKey"), 128);
            versionStr = FinanceReceivablesRules.text(raw.get("version"), 64);
            fromDate = FinanceReceivablesRules.date(raw.get("effectiveFrom"));
            throughDate = FinanceReceivablesRules.date(raw.get("effectiveThrough"));
            monthlyBasic = money(String.valueOf(raw.get("monthlyBasic")));
            sourceEvId = FinanceReceivablesRules.uuid(raw.get("sourceEvidenceId"));
            approvalEvId = FinanceReceivablesRules.uuid(raw.get("approvalEvidenceId"));
        } catch (RuntimeException ex) {
            throw FinanceException.invalid("个税规则包日期、金额或证据编号格式无效");
        }
        if (!(raw.get("brackets") instanceof List<?> rawBrackets) || rawBrackets.size() != 7)
            throw FinanceException.invalid("居民工资累计预扣税率表必须包含7级超额累进级距");

        List<Bracket> parsedBrackets = new ArrayList<>();
        List<Map<String, Object>> canonicalBrackets = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            if (!(rawBrackets.get(i) instanceof Map<?, ?> bm)
                    || !bm.keySet().equals(Set.of("upper", "rate", "quick")))
                throw FinanceException.invalid("税率级距项必须仅包含 upper、rate 和 quick");
            BigDecimal upper =
                    bm.get("upper") == null ? null : money(bm.get("upper").toString());
            BigDecimal rate = new BigDecimal(bm.get("rate").toString());
            BigDecimal quick = money(bm.get("quick").toString());
            parsedBrackets.add(new Bracket(upper, rate, quick));
            canonicalBrackets.add(
                    map(
                            "upper",
                            upper == null ? null : upper.toPlainString(),
                            "rate",
                            rate.toPlainString(),
                            "quick",
                            quick.toPlainString()));
        }

        var b = finance.originalBookScope(principal, book, "approve");
        if (!allowed(b, "approve"))
            throw error("SCOPE_DENIED", "当前岗位无权独立批准并发布薪酬个税规则包");
        UUID tenantId = id(b.get("tenant_id"));
        UUID legalEntityId = id(b.get("legal_entity_id"));
        verifyTaxEntity(tenantId, legalEntityId, entity);
        var approverWho = gate.current(id(b.get("actor_id")), tenantId, true, request);
        UUID approverPerson = id(approverWho.get("personId"));
        UUID yearId = yearIdForCalendarYear(tenantId, book, year);

        var bodyMap =
                map(
                        "monthlyBasic",
                        monthlyBasic.toPlainString(),
                        "brackets",
                        canonicalBrackets);
        String bodyDigest = hashCanonical(bodyMap);
        // Validate mathematical continuity of the 7-band table via PayrollCalculation.RulePack
        new RulePack(
                versionStr,
                bodyDigest,
                fromDate,
                throughDate,
                new Approval(approverPerson, approvalEvId, bodyDigest),
                parsedBrackets,
                monthlyBasic);

        var payload = new LinkedHashMap<>(raw);
        payload.remove("idempotencyKey");
        String payloadHash = hashCanonical(payload);
        String route = "payroll-rule-pack-register/" + book + "/" + entity + "/" + year;

        return new TransactionTemplate(transactions)
                .execute(
                        status -> {
                            db.one(
                                    "SELECT set_config('app.tenant_id', ?, true) value",
                                    tenantId.toString());
                            var cached =
                                    db.list(
                                            "SELECT * FROM plugin_finance_v4_governance_request"
                                                    + " WHERE actor_id=? AND request_key=?",
                                            b.get("actor_id"),
                                            reqKey);
                            if (!cached.isEmpty()) {
                                var old = cached.getFirst();
                                if (!route.equals(old.get("route"))
                                        || !payloadHash.equals(old.get("payload_hash")))
                                    throw error("IDEMPOTENCY_CONFLICT", "同幂等键正文或路由不一致");
                                return json(old.get("response"));
                            }
                            var srcOrig =
                                    db.list(
                                            "SELECT created_by FROM plugin_finance_original WHERE"
                                                + " tenant_id=? AND book_id=? AND year_id=? AND"
                                                + " id=? AND sha256=encode(sha256(content),'hex')",
                                            tenantId,
                                            book,
                                            yearId,
                                            sourceEvId);
                            var appOrig =
                                    db.list(
                                            "SELECT id FROM plugin_finance_original WHERE"
                                                + " tenant_id=? AND book_id=? AND year_id=? AND"
                                                + " id=? AND sha256=encode(sha256(content),'hex')",
                                            tenantId,
                                            book,
                                            yearId,
                                            approvalEvId);
                            if (srcOrig.isEmpty() || appOrig.isEmpty())
                                throw error("PAYROLL_RULE_EVIDENCE_INVALID", "规则包来源原件或批准原件不存在或摘要不符");
                            var makerWho =
                                    gate.identity(
                                            tenantId, id(srcOrig.getFirst().get("created_by")));
                            if (approverPerson.equals(id(makerWho.get("personId"))))
                                throw error(
                                        "SELF_APPROVAL_FORBIDDEN",
                                        "个税规则包批准人不得与来源草案上传自然人为同一人");

                            UUID rulePackId = UUID.randomUUID();
                            db.update(
                                    "INSERT INTO"
                                        + " plugin_finance_payroll_rule_pack(tenant_id,id,version,kind,region,effective_from,effective_through,body,digest,source_evidence,approval_evidence,approved_by_person,approved_at)"
                                        + " VALUES(?,?,'"
                                        + versionStr.replace("'", "''")
                                        + "','RESIDENT_WAGE',NULL,?,?,?::jsonb,?,?,?,?,CURRENT_TIMESTAMP)",
                                    tenantId,
                                    rulePackId,
                                    fromDate,
                                    throughDate,
                                    finance.encode(finance.canonical(bodyMap)),
                                    bodyDigest,
                                    sourceEvId,
                                    approvalEvId,
                                    approverPerson);
                            var response =
                                    map(
                                            "id",
                                            rulePackId.toString(),
                                            "version",
                                            versionStr,
                                            "kind",
                                            "RESIDENT_WAGE",
                                            "effectiveFrom",
                                            fromDate.toString(),
                                            "effectiveThrough",
                                            throughDate.toString(),
                                            "digest",
                                            bodyDigest);
                            db.update(
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

    public Object registerFactEvidence(
            String principal,
            UUID book,
            UUID entity,
            int year,
            Map<String, Object> raw,
            HttpServletRequest request) {
        Set<String> allowedKeys =
                Set.of("idempotencyKey", "batchId", "kind", "originalId", "amount", "reason");
        if (raw == null || !raw.keySet().equals(allowedKeys))
            throw FinanceException.invalid("薪酬核验回执证据登记字段无效");
        String reqKey;
        UUID batchId;
        String kindStr;
        UUID origId;
        BigDecimal amount;
        String reason;
        try {
            reqKey = FinanceReceivablesRules.text(raw.get("idempotencyKey"), 128);
            batchId = FinanceReceivablesRules.uuid(raw.get("batchId"));
            kindStr = FinanceReceivablesRules.text(raw.get("kind"), 40);
            if (!Set.of("PAYMENT_VERIFIED", "WITHHOLDING_CONFIRMED", "DECLARATION_ACCEPTED")
                    .contains(kindStr))
                throw FinanceException.invalid("薪酬回执核验类型无效");
            origId = FinanceReceivablesRules.uuid(raw.get("originalId"));
            amount = money(String.valueOf(raw.get("amount")));
            reason = FinanceReceivablesRules.text(raw.get("reason"), 1000);
        } catch (FinanceReceivablesRules.Rejected ex) {
            throw error(ex.code, "薪酬核验回执参数无效");
        }
        var b = finance.originalBookScope(principal, book, "create");
        if (!allowed(b, "create")) throw error("SCOPE_DENIED", "当前岗位无权登记薪酬核验回执");
        UUID tenantId = id(b.get("tenant_id"));
        verifyTaxEntity(tenantId, id(b.get("legal_entity_id")), entity);
        var who = gate.current(id(b.get("actor_id")), tenantId, false, request);
        UUID yearId = yearIdForCalendarYear(tenantId, book, year);

        var payload = new LinkedHashMap<>(raw);
        payload.remove("idempotencyKey");
        String payloadHash = hashCanonical(payload);
        String route = "payroll-fact-evidence-create/" + book + "/" + entity + "/" + year;

        return new TransactionTemplate(transactions)
                .execute(
                        status -> {
                            db.one(
                                    "SELECT set_config('app.tenant_id', ?, true) value",
                                    tenantId.toString());
                            var cached =
                                    db.list(
                                            "SELECT * FROM plugin_finance_v4_governance_request"
                                                    + " WHERE actor_id=? AND request_key=?",
                                            b.get("actor_id"),
                                            reqKey);
                            if (!cached.isEmpty()) {
                                var old = cached.getFirst();
                                if (!route.equals(old.get("route"))
                                        || !payloadHash.equals(old.get("payload_hash")))
                                    throw error("IDEMPOTENCY_CONFLICT", "同幂等键正文或路由不一致");
                                return json(old.get("response"));
                            }
                            var origRows =
                                    db.list(
                                            "SELECT sha256 FROM plugin_finance_original WHERE"
                                                + " tenant_id=? AND book_id=? AND year_id=? AND"
                                                + " id=? AND sha256=encode(sha256(content),'hex')",
                                            tenantId,
                                            book,
                                            yearId,
                                            origId);
                            if (origRows.isEmpty())
                                throw error("PAYROLL_ORIGINAL_INVALID", "薪酬核验回执原件不存在或摘要不符");
                            String sha = origRows.getFirst().get("sha256").toString();

                            UUID evId = UUID.randomUUID();
                            db.update(
                                    "INSERT INTO"
                                        + " plugin_finance_payroll_fact_evidence(tenant_id,tax_entity_id,tax_year,book_id,year_id,id,batch_id,kind,original_id,original_sha256,amount,prepared_by_account,prepared_by_person,prepared_by_binding,reason)"
                                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                    tenantId,
                                    entity,
                                    year,
                                    book,
                                    yearId,
                                    evId,
                                    batchId,
                                    kindStr,
                                    origId,
                                    sha,
                                    amount,
                                    id(who.get("accountId")),
                                    id(who.get("personId")),
                                    id(who.get("bindingId")),
                                    reason);
                            var response =
                                    map(
                                            "id",
                                            evId.toString(),
                                            "batchId",
                                            batchId.toString(),
                                            "kind",
                                            kindStr,
                                            "status",
                                            "PENDING",
                                            "version",
                                            "1",
                                            "amount",
                                            amount.toPlainString());
                            db.update(
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

    public Object approveFactEvidence(
            String principal,
            UUID book,
            UUID entity,
            int year,
            UUID evidenceId,
            Map<String, Object> raw,
            HttpServletRequest request) {
        Set<String> allowedKeys = Set.of("idempotencyKey", "expectedVersion", "reason");
        if (raw == null || !raw.keySet().equals(allowedKeys))
            throw FinanceException.invalid("薪酬核验回执审签字段无效");
        String reqKey;
        long expectedVersion;
        String reason;
        try {
            reqKey = FinanceReceivablesRules.text(raw.get("idempotencyKey"), 128);
            expectedVersion = FinanceReceivablesRules.version(raw.get("expectedVersion"));
            reason = FinanceReceivablesRules.text(raw.get("reason"), 1000);
        } catch (FinanceReceivablesRules.Rejected ex) {
            throw error(ex.code, "薪酬核验回执审签参数无效");
        }
        var b = finance.originalBookScope(principal, book, "approve");
        if (!allowed(b, "approve")) throw error("SCOPE_DENIED", "当前岗位无权审签薪酬核验回执");
        UUID tenantId = id(b.get("tenant_id"));
        verifyTaxEntity(tenantId, id(b.get("legal_entity_id")), entity);
        var who = gate.current(id(b.get("actor_id")), tenantId, true, request);

        var payload = new LinkedHashMap<>(raw);
        payload.remove("idempotencyKey");
        String payloadHash = hashCanonical(payload);
        String route =
                "payroll-fact-evidence-approve/"
                        + book
                        + "/"
                        + entity
                        + "/"
                        + year
                        + "/"
                        + evidenceId;

        return new TransactionTemplate(transactions)
                .execute(
                        status -> {
                            db.one(
                                    "SELECT set_config('app.tenant_id', ?, true) value",
                                    tenantId.toString());
                            var cached =
                                    db.list(
                                            "SELECT * FROM plugin_finance_v4_governance_request"
                                                    + " WHERE actor_id=? AND request_key=?",
                                            b.get("actor_id"),
                                            reqKey);
                            if (!cached.isEmpty()) {
                                var old = cached.getFirst();
                                if (!route.equals(old.get("route"))
                                        || !payloadHash.equals(old.get("payload_hash")))
                                    throw error("IDEMPOTENCY_CONFLICT", "同幂等键正文或路由不一致");
                                return json(old.get("response"));
                            }
                            var rows =
                                    db.list(
                                            "SELECT * FROM plugin_finance_payroll_fact_evidence"
                                                + " WHERE tenant_id=? AND tax_entity_id=? AND"
                                                + " tax_year=? AND book_id=? AND id=? FOR UPDATE",
                                            tenantId,
                                            entity,
                                            year,
                                            book,
                                            evidenceId);
                            if (rows.isEmpty()) throw error("SCOPE_DENIED", "薪酬核验回执不存在");
                            var row = rows.getFirst();
                            if (((Number) row.get("version")).longValue() != expectedVersion)
                                throw error("VERSION_CONFLICT", "薪酬核验回执版本不匹配");
                            if (!"PENDING".equals(row.get("status")))
                                throw error("PAYROLL_STATE_CONFLICT", "薪酬核验回执已处于终态");
                            if (id(who.get("personId")).equals(id(row.get("prepared_by_person"))))
                                throw error("SELF_APPROVAL_FORBIDDEN", "同一自然人不得自审薪酬核验回执");
                            db.update(
                                    "UPDATE plugin_finance_payroll_fact_evidence SET"
                                        + " status='APPROVED',version=2,approved_by_account=?,approved_by_person=?,approved_by_binding=?,decision_reason=?,decided_at=CURRENT_TIMESTAMP"
                                        + " WHERE tenant_id=? AND id=?",
                                    id(who.get("accountId")),
                                    id(who.get("personId")),
                                    id(who.get("bindingId")),
                                    reason,
                                    tenantId,
                                    evidenceId);
                            var response =
                                    map(
                                            "id",
                                            evidenceId.toString(),
                                            "batchId",
                                            row.get("batch_id").toString(),
                                            "kind",
                                            row.get("kind"),
                                            "status",
                                            "APPROVED",
                                            "version",
                                            "2",
                                            "amount",
                                            new BigDecimal(row.get("amount").toString())
                                                    .setScale(2, RoundingMode.UNNECESSARY)
                                                    .toPlainString());
                            db.update(
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

    public Object listRestatementPlans(String principal, UUID book, UUID entity, int year) {
        var b = finance.originalBookScope(principal, book, "read");
        if (!allowed(b, "read")) throw error("SCOPE_DENIED", "当前岗位无权读取薪酬跨期重算方案");
        YearScope scope = new YearScope(id(b.get("tenant_id")), entity, year);
        verifyTaxEntity(scope.tenant(), id(b.get("legal_entity_id")), scope.entity());
        return new TransactionTemplate(transactions)
                .execute(
                        status -> {
                            db.one(
                                    "SELECT set_config('app.tenant_id', ?, true) value",
                                    scope.tenant().toString());
                            var rows =
                                    db.list(
                                            "SELECT * FROM plugin_finance_payroll_restatement_plan"
                                                + " WHERE tenant_id=? AND tax_entity_id=? AND"
                                                + " tax_year=? AND book_id=? ORDER BY created_at"
                                                + " DESC,id DESC LIMIT 50",
                                            scope.tenant(),
                                            scope.entity(),
                                            scope.year(),
                                            book);
                            return map(
                                    "items",
                                    rows.stream().map(this::restatementPlanView).toList());
                        });
    }

    @SuppressWarnings("unchecked")
    public Object createRestatementPlan(
            String principal,
            UUID book,
            UUID entity,
            int year,
            Map<String, Object> raw,
            HttpServletRequest request) {
        Set<String> allowedKeys =
                Set.of("idempotencyKey", "employeeId", "rulePackId", "periods", "reason");
        if (raw == null || !raw.keySet().equals(allowedKeys))
            throw FinanceException.invalid("薪酬跨期重算方案请求字段无效");
        String reqKey;
        UUID employeeId;
        UUID rulePackId;
        String reason;
        try {
            reqKey = FinanceReceivablesRules.text(raw.get("idempotencyKey"), 128);
            employeeId = FinanceReceivablesRules.uuid(raw.get("employeeId"));
            rulePackId = FinanceReceivablesRules.uuid(raw.get("rulePackId"));
            reason = FinanceReceivablesRules.text(raw.get("reason"), 1000);
        } catch (FinanceReceivablesRules.Rejected ex) {
            throw error(ex.code, "薪酬跨期重算方案参数无效");
        }
        if (!(raw.get("periods") instanceof List<?> rawPeriods)
                || rawPeriods.isEmpty()
                || rawPeriods.size() > 36)
            throw FinanceException.invalid("薪酬跨期重算方案必须包含1至36个受影响期间");

        Set<String> periodAllowedKeys =
                Set.of(
                        "batchId",
                        "cumulativeIncome",
                        "cumulativeExempt",
                        "cumulativeSocial",
                        "cumulativeSpecial",
                        "cumulativeOther",
                        "cumulativeDonation",
                        "cumulativeReduction",
                        "alreadyWithheld");
        record ParsedPeriod(UUID batchId, Cumulative cumulative) {}
        List<ParsedPeriod> parsedPeriods = new ArrayList<>();
        for (Object item : rawPeriods) {
            if (!(item instanceof Map<?, ?> pm) || !pm.keySet().equals(periodAllowedKeys))
                throw FinanceException.invalid("跨期重算期间项字段无效");
            try {
                UUID bId = FinanceReceivablesRules.uuid(pm.get("batchId"));
                Cumulative cum =
                        new Cumulative(
                                money(String.valueOf(pm.get("cumulativeIncome"))).toPlainString(),
                                money(String.valueOf(pm.get("cumulativeExempt"))).toPlainString(),
                                money(String.valueOf(pm.get("cumulativeSocial"))).toPlainString(),
                                money(String.valueOf(pm.get("cumulativeSpecial"))).toPlainString(),
                                money(String.valueOf(pm.get("cumulativeOther"))).toPlainString(),
                                money(String.valueOf(pm.get("cumulativeDonation"))).toPlainString(),
                                money(String.valueOf(pm.get("cumulativeReduction")))
                                        .toPlainString(),
                                money(String.valueOf(pm.get("alreadyWithheld"))).toPlainString());
                parsedPeriods.add(new ParsedPeriod(bId, cum));
            } catch (RuntimeException ex) {
                throw FinanceException.invalid("跨期重算期间累计金额或批次编号格式无效");
            }
        }

        var b = finance.originalBookScope(principal, book, "create");
        if (!allowed(b, "create")) throw error("SCOPE_DENIED", "当前岗位无权生成薪酬跨期重算方案");
        UUID tenantId = id(b.get("tenant_id"));
        verifyTaxEntity(tenantId, id(b.get("legal_entity_id")), entity);
        var who = gate.current(id(b.get("actor_id")), tenantId, false, request);
        UUID yearId = yearIdForCalendarYear(tenantId, book, year);
        YearScope yearScope = new YearScope(tenantId, entity, year);

        var payload = new LinkedHashMap<>(raw);
        payload.remove("idempotencyKey");
        String payloadHash = hashCanonical(payload);
        String route = "payroll-restatement-create/" + book + "/" + entity + "/" + year;

        return new TransactionTemplate(transactions)
                .execute(
                        status -> {
                            db.one(
                                    "SELECT set_config('app.tenant_id', ?, true) value",
                                    tenantId.toString());
                            var cached =
                                    db.list(
                                            "SELECT * FROM plugin_finance_v4_governance_request"
                                                    + " WHERE actor_id=? AND request_key=?",
                                            b.get("actor_id"),
                                            reqKey);
                            if (!cached.isEmpty()) {
                                var old = cached.getFirst();
                                if (!route.equals(old.get("route"))
                                        || !payloadHash.equals(old.get("payload_hash")))
                                    throw error("IDEMPOTENCY_CONFLICT", "同幂等键正文或路由不一致");
                                return json(old.get("response"));
                            }
                            var yrRows =
                                    db.list(
                                            "SELECT payload::text AS payload FROM"
                                                + " plugin_finance_payroll_year WHERE tenant_id=?"
                                                + " AND tax_entity_id=? AND tax_year=? FOR UPDATE",
                                            tenantId,
                                            entity,
                                            year);
                            if (yrRows.isEmpty())
                                throw error("PAYROLL_BATCH_NOT_FOUND", "当前纳税年度尚无已锁定薪酬批次");
                            YearData yd =
                                    gate.json.readValue(
                                            yrRows.getFirst().get("payload").toString(),
                                            YearData.class);

                            List<PayrollRestatement.Period> restatementPeriods = new ArrayList<>();
                            for (ParsedPeriod pp : parsedPeriods) {
                                Batch batch = yd.batches().get(pp.batchId());
                                if (batch == null
                                        || !book.equals(batch.draft().book())
                                        || batch.state() != State.LOCKED)
                                    throw error(
                                            "PAYROLL_RESTATEMENT_REQUIRES_LOCKED_BATCH",
                                            "跨期重算仅可针对当前账套已锁定薪酬批次生成下游差额方案");
                                Line empLine =
                                        batch.draft().lines().stream()
                                                .filter(l -> employeeId.equals(l.employee()))
                                                .findFirst()
                                                .orElseThrow(
                                                        () ->
                                                                error(
                                                                        "PAYROLL_EMPLOYEE_NOT_IN_BATCH",
                                                                        "指定员工不在目标已锁定薪酬批次中"));
                                Computed empComp =
                                        batch.computed().stream()
                                                .filter(c -> employeeId.equals(c.employee()))
                                                .findFirst()
                                                .orElseThrow(
                                                        () ->
                                                                error(
                                                                        "PAYROLL_EMPLOYEE_NOT_IN_BATCH",
                                                                        "指定员工缺少目标薪酬批次计算结果"));
                                RulePack rp =
                                        defaultEvidence()
                                                .rule(yearScope, rulePackId, batch.draft().payDate());
                                UUID acceptedDecl =
                                        batch.facts().stream()
                                                .filter(f -> f.kind() == FactKind.DECLARATION_ACCEPTED)
                                                .map(Fact::reference)
                                                .findFirst()
                                                .orElse(null);
                                ExceptionEvidence exc =
                                        empLine.deductionMode() == DeductionMode.NORMAL
                                                ? null
                                                : defaultEvidence()
                                                        .exception(
                                                                yearScope,
                                                                employeeId,
                                                                empLine.exceptionEvidence());
                                restatementPeriods.add(
                                        new PayrollRestatement.Period(
                                                pp.batchId(),
                                                batch.draft().payDate(),
                                                empLine.employmentMonths(),
                                                empLine.deductionMode(),
                                                exc,
                                                rp,
                                                pp.cumulative(),
                                                empComp.tax().currentWithholding(),
                                                acceptedDecl,
                                                empComp.inputDigest()));
                            }

                            PayrollRestatement.Plan plan;
                            try {
                                plan =
                                        PayrollRestatement.preview(
                                                new Scope(tenantId, entity, employeeId, year),
                                                restatementPeriods);
                            } catch (IllegalArgumentException ex) {
                                throw error("PAYROLL_" + ex.getMessage(), "薪酬跨期重算校验未通过: " + ex.getMessage());
                            }

                            BigDecimal totalTaxDelta =
                                    plan.totalDifference().setScale(2, RoundingMode.UNNECESSARY);
                            BigDecimal totalOverwithheld =
                                    plan.changes().stream()
                                            .map(ch -> ch.revised().overwithheld())
                                            .reduce(
                                                    BigDecimal.ZERO.setScale(
                                                            2, RoundingMode.UNNECESSARY),
                                                    BigDecimal::add);
                            boolean requiresPriorAmendment =
                                    plan.changes().stream()
                                            .anyMatch(
                                                    ch ->
                                                            ch.acceptedDeclaration() != null
                                                                    && ch.signedDifference()
                                                                                    .signum()
                                                                            != 0);

                            List<Map<String, Object>> serializedChanges = new ArrayList<>();
                            for (PayrollRestatement.Change ch : plan.changes()) {
                                serializedChanges.add(
                                        map(
                                                "batchId",
                                                ch.batch().toString(),
                                                "acceptedDeclaration",
                                                ch.acceptedDeclaration() == null
                                                        ? null
                                                        : ch.acceptedDeclaration().toString(),
                                                "originalWithholding",
                                                ch.originalWithholding()
                                                        .setScale(2, RoundingMode.UNNECESSARY)
                                                        .toPlainString(),
                                                "revisedTaxable",
                                                ch.revised()
                                                        .taxable()
                                                        .setScale(2, RoundingMode.UNNECESSARY)
                                                        .toPlainString(),
                                                "revisedCumulativeTax",
                                                ch.revised()
                                                        .cumulativeTax()
                                                        .setScale(2, RoundingMode.UNNECESSARY)
                                                        .toPlainString(),
                                                "revisedCurrentWithholding",
                                                ch.revised()
                                                        .currentWithholding()
                                                        .setScale(2, RoundingMode.UNNECESSARY)
                                                        .toPlainString(),
                                                "overwithheld",
                                                ch.revised()
                                                        .overwithheld()
                                                        .setScale(2, RoundingMode.UNNECESSARY)
                                                        .toPlainString(),
                                                "signedDifference",
                                                ch.signedDifference()
                                                        .setScale(2, RoundingMode.UNNECESSARY)
                                                        .toPlainString(),
                                                "sourceDigest",
                                                ch.sourceDigest()));
                            }
                            UUID targetBatchId = parsedPeriods.getFirst().batchId();
                            var planPayload =
                                    map(
                                            "employeeId",
                                            employeeId.toString(),
                                            "targetBatchId",
                                            targetBatchId.toString(),
                                            "rulePackId",
                                            rulePackId.toString(),
                                            "changes",
                                            serializedChanges,
                                            "totalTaxDelta",
                                            totalTaxDelta.toPlainString(),
                                            "totalOverwithheld",
                                            totalOverwithheld.toPlainString(),
                                            "requiresPriorAmendment",
                                            requiresPriorAmendment);
                            String planDigest = hashCanonical(planPayload);
                            UUID planId = UUID.randomUUID();
                            db.update(
                                    "INSERT INTO plugin_finance_payroll_restatement_plan("
                                        + "tenant_id,tax_entity_id,tax_year,book_id,year_id,id,"
                                        + "target_batch_id,rule_pack_id,plan_payload,plan_digest,"
                                        + "total_tax_delta,total_overwithheld,requires_prior_amendment,"
                                        + "status,version,prepared_by_account,prepared_by_person,prepared_by_binding,reason) "
                                        + "VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,'PENDING',1,?,?,?,?)",
                                    tenantId,
                                    entity,
                                    year,
                                    book,
                                    yearId,
                                    planId,
                                    targetBatchId,
                                    rulePackId,
                                    finance.encode(finance.canonical(planPayload)),
                                    planDigest,
                                    totalTaxDelta,
                                    totalOverwithheld,
                                    requiresPriorAmendment,
                                    id(who.get("accountId")),
                                    id(who.get("personId")),
                                    id(who.get("bindingId")),
                                    reason);
                            var response =
                                    restatementPlanView(
                                            db.one(
                                                    "SELECT * FROM"
                                                        + " plugin_finance_payroll_restatement_plan"
                                                        + " WHERE tenant_id=? AND id=?",
                                                    tenantId,
                                                    planId));
                            db.update(
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

    public Object approveRestatementPlan(
            String principal,
            UUID book,
            UUID entity,
            int year,
            UUID planId,
            Map<String, Object> raw,
            HttpServletRequest request) {
        Set<String> allowedKeys = Set.of("idempotencyKey", "expectedVersion", "reason");
        if (raw == null || !raw.keySet().equals(allowedKeys))
            throw FinanceException.invalid("薪酬跨期重算方案审签字段无效");
        String reqKey;
        long expectedVersion;
        String reason;
        try {
            reqKey = FinanceReceivablesRules.text(raw.get("idempotencyKey"), 128);
            expectedVersion = FinanceReceivablesRules.version(raw.get("expectedVersion"));
            reason = FinanceReceivablesRules.text(raw.get("reason"), 1000);
        } catch (FinanceReceivablesRules.Rejected ex) {
            throw error(ex.code, "薪酬跨期重算方案审签参数无效");
        }
        var b = finance.originalBookScope(principal, book, "approve");
        if (!allowed(b, "approve")) throw error("SCOPE_DENIED", "当前岗位无权审签薪酬跨期重算方案");
        UUID tenantId = id(b.get("tenant_id"));
        verifyTaxEntity(tenantId, id(b.get("legal_entity_id")), entity);
        var who = gate.current(id(b.get("actor_id")), tenantId, true, request);

        var payload = new LinkedHashMap<>(raw);
        payload.remove("idempotencyKey");
        String payloadHash = hashCanonical(payload);
        String route =
                "payroll-restatement-approve/"
                        + book
                        + "/"
                        + entity
                        + "/"
                        + year
                        + "/"
                        + planId;

        return new TransactionTemplate(transactions)
                .execute(
                        status -> {
                            db.one(
                                    "SELECT set_config('app.tenant_id', ?, true) value",
                                    tenantId.toString());
                            var cached =
                                    db.list(
                                            "SELECT * FROM plugin_finance_v4_governance_request"
                                                    + " WHERE actor_id=? AND request_key=?",
                                            b.get("actor_id"),
                                            reqKey);
                            if (!cached.isEmpty()) {
                                var old = cached.getFirst();
                                if (!route.equals(old.get("route"))
                                        || !payloadHash.equals(old.get("payload_hash")))
                                    throw error("IDEMPOTENCY_CONFLICT", "同幂等键正文或路由不一致");
                                return json(old.get("response"));
                            }
                            var rows =
                                    db.list(
                                            "SELECT * FROM plugin_finance_payroll_restatement_plan"
                                                + " WHERE tenant_id=? AND tax_entity_id=? AND"
                                                + " tax_year=? AND book_id=? AND id=? FOR UPDATE",
                                            tenantId,
                                            entity,
                                            year,
                                            book,
                                            planId);
                            if (rows.isEmpty()) throw error("SCOPE_DENIED", "薪酬跨期重算方案不存在");
                            var row = rows.getFirst();
                            if (((Number) row.get("version")).longValue() != expectedVersion)
                                throw error("VERSION_CONFLICT", "薪酬跨期重算方案版本不匹配");
                            if (!"PENDING".equals(row.get("status")))
                                throw error("PAYROLL_STATE_CONFLICT", "薪酬跨期重算方案已处于终态");
                            if (id(who.get("personId")).equals(id(row.get("prepared_by_person"))))
                                throw error("SELF_APPROVAL_FORBIDDEN", "同一自然人不得自审薪酬跨期重算方案");
                            db.update(
                                    "UPDATE plugin_finance_payroll_restatement_plan SET"
                                        + " status='APPROVED',version=2,approved_by_account=?,approved_by_person=?,approved_by_binding=?,decision_reason=?,decided_at=CURRENT_TIMESTAMP"
                                        + " WHERE tenant_id=? AND id=?",
                                    id(who.get("accountId")),
                                    id(who.get("personId")),
                                    id(who.get("bindingId")),
                                    reason,
                                    tenantId,
                                    planId);
                            var response =
                                    restatementPlanView(
                                            db.one(
                                                    "SELECT * FROM"
                                                        + " plugin_finance_payroll_restatement_plan"
                                                        + " WHERE tenant_id=? AND id=?",
                                                    tenantId,
                                                    planId));
                            db.update(
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

    private Map<String, Object> restatementPlanView(Map<String, Object> r) {
        return map(
                "id",
                r.get("id").toString(),
                "targetBatchId",
                r.get("target_batch_id").toString(),
                "rulePackId",
                r.get("rule_pack_id").toString(),
                "planPayload",
                json(r.get("plan_payload")),
                "planDigest",
                r.get("plan_digest"),
                "totalTaxDelta",
                new BigDecimal(r.get("total_tax_delta").toString())
                        .setScale(2, RoundingMode.UNNECESSARY)
                        .toPlainString(),
                "totalOverwithheld",
                new BigDecimal(r.get("total_overwithheld").toString())
                        .setScale(2, RoundingMode.UNNECESSARY)
                        .toPlainString(),
                "requiresPriorAmendment",
                Boolean.TRUE.equals(r.get("requires_prior_amendment")),
                "status",
                r.get("status"),
                "version",
                r.get("version").toString(),
                "reason",
                r.get("reason"));
    }
}
