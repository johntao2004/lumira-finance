package com.lumira.finance.payroll;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import static com.lumira.finance.payroll.PayrollCalculation.*;

/** Stable employee/dated employment model, separate from authentication and approval persons. */
public final class PayrollEmployment {
    private PayrollEmployment() {}
    public enum Residency { RESIDENT, NONRESIDENT, UNKNOWN }
    public record Employee(UUID tenant,UUID id,String employeeNumber,UUID identitySecretRef,String identityHmac) {
        public Employee {
            if(tenant==null || id==null || employeeNumber==null || !employeeNumber.matches("[A-Za-z0-9._-]{1,64}")
                || identitySecretRef==null || identityHmac==null || !identityHmac.matches("[a-f0-9]{64}")) throw invalid("EMPLOYEE_IDENTITY");
        }
    }
    public record Employment(UUID tenant,UUID employee,UUID entity,UUID revision,LocalDate from,LocalDate through,
            Residency residency,Approval approval,String digest) {
        public Employment {
            if(tenant==null || employee==null || entity==null || revision==null || from==null
                || (through!=null && through.isBefore(from)) || residency==null || approval==null
                || digest==null || !digest.matches("[a-f0-9]{64}")) throw invalid("EMPLOYMENT_REVISION");
        }
    }
    public static int distinctMonths(Scope scope,LocalDate payDate,List<Employment> employments) {
        if(payDate.getYear()!=scope.year()) throw invalid("TAX_YEAR");
        Set<YearMonth> months=new HashSet<>();
        for(Employment e:employments) {
            requireScope(scope,e);
            LocalDate from=e.from().isBefore(LocalDate.of(scope.year(),1,1))?LocalDate.of(scope.year(),1,1):e.from();
            LocalDate to=e.through()==null || e.through().isAfter(payDate)?payDate:e.through();
            if(from.isAfter(to)) continue;
            for(YearMonth m=YearMonth.from(from);!m.isAfter(YearMonth.from(to));m=m.plusMonths(1)) months.add(m);
        }
        return months.size();
    }
    public static void requireWage(Scope scope,Employment employment,LocalDate earnedFrom,LocalDate earnedThrough,
            LocalDate payDate,PayrollService.Kind kind,String reason) {
        requireScope(scope,employment);
        if(employment.residency()!=Residency.RESIDENT) throw invalid("RESIDENCY_REVIEW_REQUIRED");
        if(earnedFrom==null || earnedThrough==null || earnedThrough.isBefore(earnedFrom)
            || earnedFrom.isBefore(employment.from()) || (employment.through()!=null && earnedThrough.isAfter(employment.through()))
            || earnedThrough.isAfter(payDate) || payDate.getYear()!=scope.year()) throw invalid("EARNING_PERIOD");
        boolean terminated=employment.through()!=null && payDate.isAfter(employment.through());
        if(terminated && kind!=PayrollService.Kind.ARREARS && kind!=PayrollService.Kind.CORRECTION) throw invalid("ARREARS_PERIOD_REQUIRED");
        if(terminated && (reason==null || reason.isBlank())) throw invalid("ARREARS_REASON");
        // Prior-year arrears can be classified here; basic-deduction eligibility still requires a
        // professionally resolved exception if current-unit current-year employment months are zero.
    }
    private static void requireScope(Scope s,Employment e) {
        if(!s.tenant().equals(e.tenant()) || !s.employee().equals(e.employee()) || !s.withholdingEntity().equals(e.entity()))
            throw invalid("EMPLOYMENT_SCOPE");
    }
}
