package com.lumira.finance.payroll;

import static com.lumira.finance.payroll.PayrollCalculation.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/** Internal payroll engine. Dependencies are authoritative server adapters, never HTTP flags. */
public final class PayrollService {
    public enum State { DRAFT, CALCULATED, REVIEWED, APPROVED, LOCKED, CANCELLED }
    public enum Kind { NORMAL, ARREARS, CORRECTION }
    public enum Action { CALCULATE, REVIEW, APPROVE, LOCK, CANCEL }
    public enum FactKind { ACCRUAL_POSTED, PAYMENT_VERIFIED, WITHHOLDING_CONFIRMED, DECLARATION_ACCEPTED }
    public record YearScope(UUID tenant, UUID entity, int year) {
        public YearScope { if (tenant == null || entity == null || year < 2000 || year > 9999) throw invalid("SCOPE"); }
    }
    public record Actor(UUID account, UUID person, UUID binding) {
        public Actor { Objects.requireNonNull(account); Objects.requireNonNull(person); Objects.requireNonNull(binding); }
    }
    public record Line(UUID employee, UUID employmentRevision, String gross, String exempt,
            String employeeSocial, String employerSocial, String special, String other,
            String donation, String reduction, String otherWithheld, int employmentMonths,
            DeductionMode deductionMode, UUID exceptionEvidence, UUID attendanceEvidence,
            LocalDate earnedFrom, LocalDate earnedThrough) {}
    public record Draft(UUID id, UUID book, LocalDate payDate, Kind kind, UUID predecessor,
            String reason, UUID rulePack, List<Line> lines) {
        public Draft { lines = List.copyOf(lines); }
    }
    public record Computed(UUID employee, Result tax,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal gross,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal employerSocial,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netPay, String inputDigest) {}
    public record Fact(FactKind kind, UUID reference, String digest,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount) {}
    public record Batch(Draft draft, State state, long version, Actor preparer, Actor reviewer,
            Actor approver, String dependencyDigest, List<Computed> computed, List<Fact> facts) {
        public Batch { computed = List.copyOf(computed); facts = List.copyOf(facts); }
    }
    public record YearData(Map<UUID, Batch> batches) {
        public YearData { batches = Map.copyOf(batches); }
    }
    public record Command(UUID key, String client, long expectedVersion) {
        public Command {
            if (key == null || client == null || !client.matches("[A-Za-z0-9._-]{1,64}") || expectedVersion < 0)
                throw invalid("COMMAND");
        }
    }
    /** Must perform current object, salary-field, employee-row, actor binding and year-write checks. */
    public interface Access {
        Actor require(String principal, YearScope scope, UUID book, String permission, Collection<UUID> employees);
    }
    /** All values are resolved from persisted approved records under transaction locks. */
    public interface Evidence {
        RulePack rule(YearScope scope, UUID ruleId, LocalDate date);
        ExceptionEvidence exception(YearScope scope, UUID employee, UUID reference);
        // Validate residency=resident, income=salary, employment revision and earned interval, attendance,
        // wage items, confirmed deductions (exclude annual medical), distinct employment months,
        // social/housing regional calculation evidence, arrears reason and source privacy access.
        String validateLine(YearScope scope, Draft draft, Line line);
        // Frozen opening cumulative baseline for this unit/year/employee only, independently approved.
        // Return explicit all-zero baseline only when evidence establishes January start/no migration.
        Cumulative opening(YearScope scope, UUID employee, LocalDate payDate);
        // Revalidate all dependent records/watermarks; must include current identity and rules versions.
        String dependencies(YearScope scope, Draft draft);
        // Resolve accounting/treasury/tax record, authenticate evidence and bind entity/book/batch digest.
        // DECLARATION_ACCEPTED also verifies predecessor relation for a correction.
        Fact fact(YearScope scope, Batch batch, FactKind kind, UUID reference);
    }
    public interface Store {
        <T> T transaction(YearScope scope, Function<Tx, T> work);
        interface Tx {
            YearData load();
            Batch replay(String receiptKey, String digest);
            void save(YearData data, String receiptKey, String digest, Batch result, Actor actor, String action);
        }
        String digest(Object value);
    }
    private final Access access;
    private final Evidence evidence;
    private final Store store;
    public PayrollService(Access access, Evidence evidence, Store store) {
        this.access = Objects.requireNonNull(access); this.evidence = Objects.requireNonNull(evidence);
        this.store = Objects.requireNonNull(store);
    }
    private static Collection<UUID> employees(Draft draft) { return draft.lines().stream().map(Line::employee).toList(); }
    private static String receipt(Actor actor, Command cmd, UUID id, String action) {
        return actor.account() + ":" + cmd.client() + ":" + action + ":" + id + ":" + cmd.key();
    }
    public Batch read(String principal, YearScope scope, UUID id) {
        return store.transaction(scope, tx -> {
            Batch b = required(tx.load(), id);
            access.require(principal, scope, b.draft().book(), "read", employees(b.draft()));
            return b;
        });
    }
    public Batch create(String principal, YearScope scope, Command cmd, Draft draft) {
        validate(scope, draft);
        return createValidated(principal, scope, cmd, normalize(draft));
    }
    private Batch createValidated(String principal, YearScope scope, Command cmd, Draft draft) {
        if (cmd.expectedVersion() != 0) throw invalid("VERSION_CONFLICT");
        return store.transaction(scope, tx -> {
            Actor who = access.require(principal, scope, draft.book(), draft.kind() == Kind.CORRECTION ? "correction" : "prepare", employees(draft));
            String hash = store.digest(draft), key = receipt(who, cmd, draft.id(), "CREATE");
            Batch replay = tx.replay(key, hash); if (replay != null) return replay;
            YearData data = tx.load();
            if (data.batches().containsKey(draft.id())) throw invalid("PERMANENT_BATCH_ID_CONFLICT");
            if (draft.kind() == Kind.CORRECTION) {
                Batch old = required(data, draft.predecessor());
                access.require(principal, scope, old.draft().book(), "read", employees(old.draft()));
                if (old.state() != State.LOCKED || !old.draft().book().equals(draft.book())
                        || !old.draft().payDate().equals(draft.payDate())
                        || !new HashSet<>(employees(old.draft())).equals(new HashSet<>(employees(draft))))
                    throw invalid("CORRECTION_SCOPE");
                if (data.batches().values().stream().anyMatch(b -> b.state() != State.CANCELLED && draft.predecessor().equals(b.draft().predecessor())))
                    throw invalid("CORRECTION_ALREADY_EXISTS");
            }
            for (Line line : draft.lines()) evidence.validateLine(scope, draft, line);
            Batch b = new Batch(draft, State.DRAFT, 1, who, null, null, null, List.of(), List.of());
            persist(tx, data, b, key, hash, who, "CREATE"); return b;
        });
    }
    public Batch act(String principal, YearScope scope, UUID id, Command cmd, Action action) {
        return store.transaction(scope, tx -> {
            YearData data = tx.load(); Batch old = required(data, id);
            Actor who = access.require(principal, scope, old.draft().book(), action.name().toLowerCase(Locale.ROOT), employees(old.draft()));
            String hash = store.digest(List.of(id, cmd.expectedVersion(), action)), key = receipt(who, cmd, id, action.name());
            Batch replay = tx.replay(key, hash); if (replay != null) return replay;
            if (old.version() != cmd.expectedVersion()) throw invalid("VERSION_CONFLICT");
            if (action == Action.CANCEL) {
                if (old.state() == State.LOCKED || old.state() == State.CANCELLED) throw invalid("STATE_CONFLICT");
                Batch cancelled = new Batch(old.draft(), State.CANCELLED, old.version()+1, old.preparer(), old.reviewer(),
                        old.approver(), old.dependencyDigest(), old.computed(), old.facts());
                persist(tx, data, cancelled, key, hash, who, "CANCEL"); return cancelled;
            }
            State expected = switch (action) { case CALCULATE -> State.DRAFT; case REVIEW -> State.CALCULATED;
                case APPROVE -> State.REVIEWED; case LOCK -> State.APPROVED; case CANCEL -> throw new IllegalStateException(); };
            if (old.state() != expected && !(action == Action.CALCULATE && old.state() == State.CALCULATED)) throw invalid("STATE_CONFLICT");
            Set<UUID> requestedEmployees = new HashSet<>(employees(old.draft()));
            for (Batch source : prior(data, old.draft()))
                access.require(principal, scope, source.draft().book(), "read",
                        employees(source.draft()).stream().filter(requestedEmployees::contains).toList());
            if ((action == Action.REVIEW || action == Action.APPROVE) && who.person().equals(old.preparer().person()))
                throw invalid("NATURAL_PERSON_SOD");
            if (action == Action.APPROVE && who.person().equals(old.reviewer().person())) throw invalid("NATURAL_PERSON_SOD");
            String dependencies = dependencies(scope, old.draft(), data);
            if (action != Action.CALCULATE && !dependencies.equals(old.dependencyDigest())) throw invalid("DEPENDENCIES_CHANGED_RECALCULATE_NEW_BATCH");
            List<Computed> computed = action == Action.CALCULATE ? compute(scope, old.draft(), data) : old.computed();
            Batch next = new Batch(old.draft(), State.values()[expected.ordinal() + 1], old.version() + 1,
                    old.preparer(), action == Action.REVIEW ? who : old.reviewer(), action == Action.APPROVE ? who : old.approver(),
                    dependencies, computed, old.facts());
            persist(tx, data, next, key, hash, who, action.name()); return next;
        });
    }
    public Batch attachFact(String principal, YearScope scope, UUID id, Command cmd, FactKind kind, UUID reference) {
        Objects.requireNonNull(kind); Objects.requireNonNull(reference);
        return store.transaction(scope, tx -> {
            YearData data = tx.load(); Batch old = required(data, id);
            Actor who = access.require(principal, scope, old.draft().book(), "verify." + kind.name().toLowerCase(Locale.ROOT), employees(old.draft()));
            String hash = store.digest(List.of(id, cmd.expectedVersion(), kind, reference)), key = receipt(who, cmd, id, "FACT:" + kind);
            Batch replay = tx.replay(key, hash); if (replay != null) return replay;
            if (old.version() != cmd.expectedVersion() || old.state() != State.LOCKED) throw invalid("LOCKED_VERSION_REQUIRED");
            // Full replacement totals are not safe incremental financial instructions.
            // A dedicated delta settlement/accrual adapter is required for financial correction facts.
            if (old.draft().kind() == Kind.CORRECTION && kind != FactKind.DECLARATION_ACCEPTED)
                throw invalid("CORRECTION_DELTA_ADAPTER_REQUIRED");
            if (old.facts().stream().anyMatch(f -> f.kind() == kind)) throw invalid("FACT_IMMUTABLE");
            Fact fact = evidence.fact(scope, old, kind, reference);
            if (fact == null || fact.kind() != kind || !reference.equals(fact.reference()) || fact.digest() == null
                    || !fact.digest().matches("[a-f0-9]{64}") || fact.amount() == null) throw invalid("VERIFIED_FACT_REQUIRED");
            BigDecimal amount = old.computed().stream().map(c -> switch (kind) {
                case ACCRUAL_POSTED -> c.gross().add(c.employerSocial());
                case PAYMENT_VERIFIED -> c.netPay(); case WITHHOLDING_CONFIRMED, DECLARATION_ACCEPTED -> c.tax().currentWithholding();
            }).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (fact.amount().compareTo(amount) != 0) throw invalid("RECONCILIATION_MISMATCH");
            List<Fact> facts = new ArrayList<>(old.facts()); facts.add(fact);
            Batch next = new Batch(old.draft(), old.state(), old.version()+1, old.preparer(), old.reviewer(), old.approver(),
                    old.dependencyDigest(), old.computed(), facts);
            persist(tx, data, next, key, hash, who, "FACT:" + kind); return next;
        });
    }
    private String dependencies(YearScope scope, Draft draft, YearData data) {
        // Canonical list order; only actual preceding locked batches, not unrelated draft edits.
        var previous = prior(data, draft).stream().map(b -> List.of(b.draft().id(), b.dependencyDigest(), b.computed(), b.facts())).toList();
        return store.digest(List.of(evidence.dependencies(scope, draft), previous));
    }
    private List<Batch> prior(YearData data, Draft draft) {
        Set<UUID> superseded = new HashSet<>();
        data.batches().values().stream().filter(b -> b.state() == State.LOCKED && b.draft().predecessor() != null)
                .forEach(b -> superseded.add(b.draft().predecessor()));
        Set<UUID> people = new HashSet<>(employees(draft));
        List<Batch> selected = data.batches().values().stream()
                .filter(b -> b.state() == State.LOCKED && !superseded.contains(b.draft().id())
                        && !b.draft().id().equals(draft.id()) && !b.draft().id().equals(draft.predecessor())
                        && b.draft().lines().stream().anyMatch(l -> people.contains(l.employee())))
                .sorted(Comparator.comparing((Batch b) -> b.draft().payDate()).thenComparing(b -> b.draft().id())).toList();
        // Later locked payroll must be corrected in dependency order; never silently rewrite it.
        if (selected.stream().anyMatch(b -> !b.draft().payDate().isBefore(draft.payDate())))
            throw invalid("LATER_OR_SAME_DATE_LOCKED_PAYROLL_REQUIRES_RESTATEMENT");
        return selected;
    }
    private List<Computed> compute(YearScope scope, Draft draft, YearData data) {
        RulePack rule = evidence.rule(scope, draft.rulePack(), draft.payDate());
        List<Batch> previous = prior(data, draft); List<Computed> results = new ArrayList<>();
        for (Line line : draft.lines()) {
            String inputDigest = evidence.validateLine(scope, draft, line);
            Cumulative start = evidence.opening(scope, line.employee(), draft.payDate());
            BigDecimal[] sums = { money(start.income()), money(start.exempt()), money(start.social()), money(start.special()),
                money(start.other()), money(start.donation()), money(start.reduction()), money(start.alreadyWithheld()) };
            for (Batch b : previous) {
                if (b.facts().stream().noneMatch(f -> f.kind() == FactKind.WITHHOLDING_CONFIRMED))
                    throw invalid("PRIOR_WITHHOLDING_NOT_CONFIRMED");
                for (Line p : b.draft().lines()) if (p.employee().equals(line.employee())) add(sums, p);
                b.computed().stream().filter(c -> c.employee().equals(line.employee())).forEach(c -> sums[7] = sums[7].add(c.tax().currentWithholding()));
            }
            add(sums, line);
            Cumulative cumulative = new Cumulative(sums[0].toPlainString(), sums[1].toPlainString(), sums[2].toPlainString(),
                sums[3].toPlainString(), sums[4].toPlainString(), sums[5].toPlainString(), sums[6].toPlainString(), sums[7].toPlainString());
            Result tax = calculate(new Scope(scope.tenant(), scope.entity(), line.employee(), scope.year()), draft.payDate(),
                    line.employmentMonths(), line.deductionMode(), line.deductionMode() == DeductionMode.NORMAL ? null :
                    evidence.exception(scope, line.employee(), line.exceptionEvidence()), rule, cumulative);
            BigDecimal net = money(line.gross()).subtract(money(line.employeeSocial())).subtract(money(line.otherWithheld())).subtract(tax.currentWithholding());
            if (net.signum() < 0) throw invalid("NEGATIVE_NET_PAY_REQUIRES_SEPARATE_RECOVERY");
            results.add(new Computed(line.employee(), tax, money(line.gross()), money(line.employerSocial()), net, inputDigest));
        }
        return List.copyOf(results);
    }
    private static void add(BigDecimal[] sums, Line line) {
        String[] values = {line.gross(), line.exempt(), line.employeeSocial(), line.special(), line.other(), line.donation(), line.reduction()};
        for (int i = 0; i < values.length; i++) sums[i] = sums[i].add(money(values[i]));
    }
    private static void validate(YearScope scope, Draft d) {
        if (d.id() == null || d.book() == null || d.rulePack() == null || d.payDate() == null || d.payDate().getYear() != scope.year()
                || d.kind() == null || d.lines().isEmpty() || d.lines().size() > 500
                || (d.kind() == Kind.CORRECTION) != (d.predecessor() != null)
                || (d.kind() != Kind.NORMAL && (d.reason() == null || d.reason().isBlank()))
                || (d.reason() != null && d.reason().length() > 1000)) throw invalid("DRAFT");
        Set<UUID> seen = new HashSet<>();
        for (Line l : d.lines()) {
            if (l.employee() == null || !seen.add(l.employee()) || l.employmentRevision() == null || l.attendanceEvidence() == null
                    || l.earnedFrom() == null || l.earnedThrough() == null || l.earnedThrough().isBefore(l.earnedFrom())
                    || l.earnedThrough().isAfter(d.payDate()) || l.deductionMode() == null) throw invalid("EMPLOYMENT_INPUT");
            for (String amount : List.of(l.gross(), l.exempt(), l.employeeSocial(), l.employerSocial(), l.special(), l.other(), l.donation(), l.reduction(), l.otherWithheld())) money(amount);
            if (money(l.exempt()).compareTo(money(l.gross())) > 0) throw invalid("EXEMPT_EXCEEDS_INCOME");
        }
    }
    private static Draft normalize(Draft d) {
        return new Draft(d.id(),d.book(),d.payDate(),d.kind(),d.predecessor(),d.reason(),d.rulePack(),
            d.lines().stream().map(l -> new Line(l.employee(),l.employmentRevision(),money(l.gross()).toPlainString(),
                money(l.exempt()).toPlainString(),money(l.employeeSocial()).toPlainString(),money(l.employerSocial()).toPlainString(),
                money(l.special()).toPlainString(),money(l.other()).toPlainString(),money(l.donation()).toPlainString(),
                money(l.reduction()).toPlainString(),money(l.otherWithheld()).toPlainString(),l.employmentMonths(),l.deductionMode(),
                l.exceptionEvidence(),l.attendanceEvidence(),l.earnedFrom(),l.earnedThrough())).toList());
    }
    private static Batch required(YearData data, UUID id) {
        Batch result = data.batches().get(id); if (result == null) throw invalid("RESOURCE_UNAVAILABLE"); return result;
    }
    private static void persist(Store.Tx tx, YearData data, Batch b, String key, String hash, Actor actor, String action) {
        Map<UUID, Batch> updated = new HashMap<>(data.batches()); updated.put(b.draft().id(), b);
        tx.save(new YearData(updated), key, hash, b, actor, action);
    }
}
