package com.lumira.finance.archiveai;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.*;

/**
 * Approved Recurring Task Definitions, Calendar Rules & Missed-Window Detection (INT03).
 */
public final class ScheduleRuleEngine {

    public enum TaskType {
        ASSET_DEPRECIATION,
        EXPENSE_ACCRUAL,
        FX_REVALUATION,
        MONTH_END_CHECK;

        public static TaskType fromString(String val) {
            for (TaskType t : values()) {
                if (t.name().equalsIgnoreCase(val)) return t;
            }
            throw new IllegalArgumentException("Unknown TaskType: " + val);
        }
    }

    public record ScheduleDefinition(
            UUID tenantId,
            UUID bookId,
            UUID id,
            String taskCode,
            String taskName,
            TaskType taskType,
            String scheduleType, // MONTH_END, PERIOD_END, DAY_OF_MONTH
            Integer dayOfMonth,
            String leapYearFebRule, // LAST_DAY_OF_MONTH, EXACT_28_ALWAYS
            String status, // ACTIVE, PAUSED, CANCELLED
            UUID approvedBy
    ) {}

    public record PeriodInfo(
            UUID periodId,
            int periodNumber, // 1 to 12
            int year,
            LocalDate startDate,
            LocalDate endDate
    ) {}

    public record MissedWindow(
            PeriodInfo period,
            LocalDate expectedExecutionDate,
            String executionType // CATCH_UP
    ) {}

    public static LocalDate calculateMonthEndDate(int year, int month, String leapYearFebRule) {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("Invalid month: " + month);
        }
        if (month == 2) {
            boolean isLeap = Year.of(year).isLeap();
            if ("EXACT_28_ALWAYS".equalsIgnoreCase(leapYearFebRule)) {
                return LocalDate.of(year, 2, 28);
            }
            return LocalDate.of(year, 2, isLeap ? 29 : 28);
        }
        YearMonth ym = YearMonth.of(year, month);
        return ym.atEndOfMonth();
    }

    public static LocalDate calculateExecutionDate(ScheduleDefinition sched, int year, int month) {
        Objects.requireNonNull(sched, "sched must not be null");
        if ("MONTH_END".equalsIgnoreCase(sched.scheduleType()) || "PERIOD_END".equalsIgnoreCase(sched.scheduleType())) {
            return calculateMonthEndDate(year, month, sched.leapYearFebRule());
        }
        if ("DAY_OF_MONTH".equalsIgnoreCase(sched.scheduleType())) {
            int dom = (sched.dayOfMonth() != null && sched.dayOfMonth() > 0) ? sched.dayOfMonth() : 1;
            int lastDayOfMonth;
            if (month == 2) {
                boolean isLeap = Year.of(year).isLeap();
                if ("EXACT_28_ALWAYS".equalsIgnoreCase(sched.leapYearFebRule())) {
                    lastDayOfMonth = 28;
                } else {
                    lastDayOfMonth = isLeap ? 29 : 28;
                }
            } else {
                lastDayOfMonth = YearMonth.of(year, month).lengthOfMonth();
            }
            int actualDay = Math.min(dom, lastDayOfMonth);
            return LocalDate.of(year, month, actualDay);
        }
        return calculateMonthEndDate(year, month, sched.leapYearFebRule());
    }

    public static List<MissedWindow> detectMissedWindows(
            List<PeriodInfo> sortedPeriodsInYear,
            Set<UUID> executedPeriodIds,
            int currentPeriodNumber,
            String leapYearFebRule
    ) {
        Objects.requireNonNull(sortedPeriodsInYear, "sortedPeriodsInYear must not be null");
        Objects.requireNonNull(executedPeriodIds, "executedPeriodIds must not be null");

        List<MissedWindow> missed = new ArrayList<>();
        for (PeriodInfo p : sortedPeriodsInYear) {
            if (p.periodNumber() < currentPeriodNumber) {
                if (!executedPeriodIds.contains(p.periodId())) {
                    LocalDate expectedDate = calculateMonthEndDate(p.year(), p.periodNumber(), leapYearFebRule);
                    missed.add(new MissedWindow(p, expectedDate, "CATCH_UP"));
                }
            }
        }
        return Collections.unmodifiableList(missed);
    }

    public static void validateDeduplication(
            UUID scheduleId,
            UUID periodId,
            Set<String> executedSchedulePeriodKeys // Set of "scheduleId:periodId"
    ) {
        String key = scheduleId.toString() + ":" + periodId.toString();
        if (executedSchedulePeriodKeys.contains(key)) {
            throw new IllegalStateException("DUPLICATE_SCHEDULED_RUN: Task " + scheduleId
                    + " has already been executed for period " + periodId + ".");
        }
    }
}
