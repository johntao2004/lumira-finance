package com.lumira.finance.payroll;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Deterministic supplied-package reference arithmetic; never activates a policy. */
public final class PayrollCalculation {
    private PayrollCalculation() {}
    public enum DeductionMode { NORMAL, FIRST_INCOME_YEAR, PRIOR_YEAR_LE60K }
    public record Scope(UUID tenant, UUID withholdingEntity, UUID employee, int year) {
        public Scope {
            if (tenant == null || withholdingEntity == null || employee == null || year < 2000 || year > 9999)
                throw invalid("SCOPE");
        }
    }
    public record Approval(UUID person, UUID evidence, String digest) {
        public Approval {
            if (person == null || evidence == null || digest == null || !digest.matches("[a-f0-9]{64}"))
                throw invalid("APPROVAL_EVIDENCE");
        }
    }
    public record RulePack(String id, String digest, LocalDate from, LocalDate through,
            Approval approval, List<Bracket> brackets,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal monthlyBasic) {
        public RulePack {
            brackets = List.copyOf(brackets);
            if (id == null || id.isBlank() || digest == null || !digest.matches("[a-f0-9]{64}")
                    || from == null || through == null || through.isBefore(from) || approval == null
                    || brackets.size() != 7 || monthlyBasic == null || monthlyBasic.signum() <= 0)
                throw invalid("RULE_PACK_NOT_APPROVED");
            BigDecimal prior = BigDecimal.ZERO;
            BigDecimal priorRate = BigDecimal.ZERO;
            BigDecimal priorQuick = BigDecimal.ZERO;
            for (int i = 0; i < brackets.size(); i++) {
                Bracket b = brackets.get(i);
                if (b.rate() == null || b.quick() == null || b.rate().compareTo(priorRate) < 0
                        || b.rate().compareTo(BigDecimal.ONE) > 0 || b.quick().signum() < 0
                        || (i < 6 && (b.upper() == null || b.upper().compareTo(prior) <= 0))
                        || (i == 6 && b.upper() != null)) throw invalid("RULE_BRACKETS");
                if (i > 0 && prior.multiply(b.rate()).subtract(b.quick()).compareTo(
                        prior.multiply(priorRate).subtract(priorQuick)) != 0) throw invalid("RULE_DISCONTINUITY");
                priorRate = b.rate(); priorQuick = b.quick();
                if (b.upper() != null) prior = b.upper();
            }
        }
        public void effective(LocalDate date) {
            if (date.isBefore(from) || date.isAfter(through)) throw invalid("RULE_OUTSIDE_EFFECTIVE_PERIOD");
        }
    }
    public record Bracket(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal upper,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rate,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quick) {}
    public record ExceptionEvidence(Scope scope, DeductionMode mode, Approval approval,
            LocalDate validThrough, String declarationDigest, String eligibilityDecisionDigest) {
        public void require(Scope expected, DeductionMode requested, LocalDate date) {
            if (!expected.equals(scope) || requested != mode || approval == null || validThrough == null
                    || validThrough.isBefore(date) || declarationDigest == null
                    || !declarationDigest.matches("[a-f0-9]{64}") || eligibilityDecisionDigest == null
                    || !eligibilityDecisionDigest.matches("[a-f0-9]{64}")) throw invalid("DEDUCTION_EXCEPTION_EVIDENCE");
        }
    }
    public record Cumulative(String income, String exempt, String social, String special,
            String other, String donation, String reduction, String alreadyWithheld) {}
    public record Result(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal taxable,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal basic,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rate,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quick,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cumulativeTax,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currentWithholding,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal overwithheld) {}

    public static BigDecimal money(String raw) {
        if (raw == null || !raw.matches("(?:0|[1-9][0-9]{0,15})(?:\\.[0-9]{1,2})?"))
            throw invalid("CNY_PRECISION_OR_RANGE");
        return new BigDecimal(raw).setScale(2, RoundingMode.UNNECESSARY);
    }
    public static Result calculate(Scope scope, LocalDate payDate, int employmentMonths,
            DeductionMode mode, ExceptionEvidence exception, RulePack rule, Cumulative input) {
        if (payDate.getYear() != scope.year() || employmentMonths < 1 || employmentMonths > payDate.getMonthValue())
            throw invalid("EMPLOYMENT_MONTHS");
        rule.effective(payDate);
        if (mode == null) throw invalid("DEDUCTION_MODE");
        if (mode != DeductionMode.NORMAL) {
            if (exception == null) throw invalid("DEDUCTION_EXCEPTION_EVIDENCE");
            exception.require(scope, mode, payDate);
        }
        int months = switch (mode) {
            case NORMAL -> employmentMonths;
            case FIRST_INCOME_YEAR -> payDate.getMonthValue();
            case PRIOR_YEAR_LE60K -> 12;
        };
        BigDecimal basic = rule.monthlyBasic().multiply(BigDecimal.valueOf(months));
        BigDecimal income = money(input.income()), exempt = money(input.exempt());
        if (exempt.compareTo(income) > 0) throw invalid("EXEMPT_EXCEEDS_INCOME");
        BigDecimal taxable = income.subtract(exempt).subtract(basic).subtract(money(input.social()))
                .subtract(money(input.special())).subtract(money(input.other())).subtract(money(input.donation()))
                .max(BigDecimal.ZERO);
        Bracket selected = rule.brackets().stream().filter(b -> b.upper() == null || taxable.compareTo(b.upper()) <= 0)
                .findFirst().orElseThrow();
        BigDecimal tax = taxable.multiply(selected.rate()).subtract(selected.quick()).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = tax.subtract(money(input.reduction())).subtract(money(input.alreadyWithheld()));
        return new Result(taxable, basic, selected.rate(), selected.quick(), tax,
                net.max(BigDecimal.ZERO).setScale(2), net.negate().max(BigDecimal.ZERO).setScale(2));
    }
    public record ContributionRule(String region, String category, LocalDate from, LocalDate through,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal floor,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal ceiling,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal employeeRate,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal employerRate,
            Approval approval, String digest) {}
    public record Contributions(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal base,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal employee,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal employer) {}
    public static Contributions contribution(LocalDate month, String region, String category,
            String wageBase, ContributionRule rule) {
        if (rule == null || rule.approval() == null || !region.equals(rule.region()) || !category.equals(rule.category())
                || rule.from() == null || rule.through() == null || month.isBefore(rule.from()) || month.isAfter(rule.through())
                || rule.digest() == null || !rule.digest().matches("[a-f0-9]{64}")) throw invalid("REGIONAL_RULE_REQUIRED");
        if (rule.floor() == null || rule.ceiling() == null || rule.floor().signum() < 0
                || rule.ceiling().compareTo(rule.floor()) < 0 || rule.employeeRate() == null || rule.employerRate() == null
                || rule.employeeRate().signum() < 0 || rule.employerRate().signum() < 0
                || rule.employeeRate().compareTo(BigDecimal.ONE) > 0 || rule.employerRate().compareTo(BigDecimal.ONE) > 0)
            throw invalid("REGIONAL_RULE_INVALID");
        BigDecimal base = money(wageBase).max(rule.floor()).min(rule.ceiling());
        return new Contributions(base, base.multiply(rule.employeeRate()).setScale(2, RoundingMode.HALF_UP),
                base.multiply(rule.employerRate()).setScale(2, RoundingMode.HALF_UP));
    }
    static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
