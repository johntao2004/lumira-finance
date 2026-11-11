package com.lumira.finance.payrolltax;

import java.io.Serializable;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

/**
 * TAX05: Regional statutory filing calendar generator with versioned holiday deferral pack.
 * Accurately determines filing deadlines taking weekends and public holidays into account.
 */
public final class TaxFilingCalendarGenerator {

    private TaxFilingCalendarGenerator() {}

    public record FilingDeadline(
            String taxType,
            int year,
            int month,
            LocalDate baseDeadline,
            LocalDate adjustedDeadline,
            boolean isDeferred,
            String deferralReason,
            String calendarPackVersion
    ) implements Serializable {}

    /**
     * Computes the official filing deadline for a given tax type and period.
     *
     * @param taxType e.g. "VAT", "CIT", "IIT"
     * @param year filing year
     * @param month filing month (1-12)
     * @param holidayOverrides Map of holiday dates to reasons (e.g. 2026-02-15 -> "SPRING_FESTIVAL_HOLIDAY_DEFERRAL")
     * @param packVersion version of the calendar pack (e.g. "CAL_CN_2026_V1")
     */
    public static FilingDeadline generate(
            String taxType,
            int year,
            int month,
            Map<LocalDate, String> holidayOverrides,
            String packVersion
    ) {
        if (taxType == null || taxType.isBlank()) throw new IllegalArgumentException("TAX_TYPE_REQUIRED");
        if (month < 1 || month > 12) throw new IllegalArgumentException("INVALID_MONTH");
        if (packVersion == null || packVersion.isBlank()) throw new IllegalArgumentException("PACK_VERSION_REQUIRED");

        // Base deadline is the 15th of the month
        LocalDate base = LocalDate.of(year, month, 15);
        LocalDate current = base;
        boolean deferred = false;
        StringBuilder reasons = new StringBuilder();

        // Advance through consecutive weekend or holiday days until a regular working business day
        while (true) {
            boolean shifted = false;

            // Check if current date is explicitly in holiday overrides
            if (holidayOverrides != null && holidayOverrides.containsKey(current)) {
                String reason = holidayOverrides.get(current);
                if (reasons.length() > 0) reasons.append("; ");
                reasons.append(reason);
                current = current.plusDays(1);
                deferred = true;
                shifted = true;
            }

            // Check if current date falls on Saturday or Sunday
            if (current.getDayOfWeek() == DayOfWeek.SATURDAY) {
                if (reasons.length() > 0) reasons.append("; ");
                reasons.append("SATURDAY_WEEKEND_DEFERRAL");
                current = current.plusDays(2); // Jump to Monday
                deferred = true;
                shifted = true;
            } else if (current.getDayOfWeek() == DayOfWeek.SUNDAY) {
                if (reasons.length() > 0) reasons.append("; ");
                reasons.append("SUNDAY_WEEKEND_DEFERRAL");
                current = current.plusDays(1); // Jump to Monday
                deferred = true;
                shifted = true;
            }

            if (!shifted) {
                break;
            }
        }

        String finalReason = deferred ? reasons.toString() : "REGULAR_STATUTORY_DEADLINE";
        return new FilingDeadline(
                taxType,
                year,
                month,
                base,
                current,
                deferred,
                finalReason,
                packVersion
        );
    }
}
