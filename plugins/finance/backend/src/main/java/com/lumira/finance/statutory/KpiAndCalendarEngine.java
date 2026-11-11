package com.lumira.finance.statutory;

import static com.lumira.finance.statutory.StatutoryModels.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

public final class KpiAndCalendarEngine {
    private KpiAndCalendarEngine() {}

    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");

    public static KpiMetrics computeKpis(
            BigDecimal availableCash,
            BigDecimal operatingCashOutflow,
            BigDecimal operatingCashInflow,
            BigDecimal capexOutflow,
            int periodMonths,
            BigDecimal revenue,
            BigDecimal cogs,
            BigDecimal rdExpense,
            BigDecimal avgReceivables,
            BigDecimal avgPayables,
            Actor preparer,
            Actor approver) {

        if (periodMonths <= 0) periodMonths = 12;
        BigDecimal months = BigDecimal.valueOf(periodMonths);

        // 1. Monthly Burn Rate = (Operating cash outflow + Capex - Operating cash inflow) / months
        BigDecimal netCashDrain = (operatingCashOutflow == null ? BigDecimal.ZERO : operatingCashOutflow)
                .add(capexOutflow == null ? BigDecimal.ZERO : capexOutflow)
                .subtract(operatingCashInflow == null ? BigDecimal.ZERO : operatingCashInflow);
        BigDecimal burnRate = netCashDrain.divide(months, 2, RoundingMode.HALF_UP).max(BigDecimal.ZERO);

        // 2. Runway (Months) = Available cash / Monthly burn rate
        BigDecimal runwayMonths;
        if (burnRate.compareTo(BigDecimal.ZERO) > 0 && availableCash != null && availableCash.signum() > 0) {
            runwayMonths = availableCash.divide(burnRate, 1, RoundingMode.HALF_UP);
        } else if (availableCash != null && availableCash.signum() > 0) {
            runwayMonths = new BigDecimal("999.0"); // Unlimited / cash positive
        } else {
            runwayMonths = BigDecimal.ZERO;
        }

        // 3. R&D Intensity = (R&D Expense / Revenue) * 100%
        BigDecimal rdIntensity = BigDecimal.ZERO;
        if (revenue != null && revenue.signum() > 0 && rdExpense != null) {
            rdIntensity = rdExpense.multiply(new BigDecimal("100")).divide(revenue, 4, RoundingMode.HALF_UP);
        }

        // 4. Gross Margin = ((Revenue - COGS) / Revenue) * 100%
        BigDecimal grossMargin = BigDecimal.ZERO;
        if (revenue != null && revenue.signum() > 0 && cogs != null) {
            BigDecimal grossProfit = revenue.subtract(cogs);
            grossMargin = grossProfit.multiply(new BigDecimal("100")).divide(revenue, 4, RoundingMode.HALF_UP);
        }

        // 5. DSO (Days Sales Outstanding) = (Average Receivables / Revenue) * 365
        BigDecimal dsoDays = BigDecimal.ZERO;
        if (revenue != null && revenue.signum() > 0 && avgReceivables != null) {
            dsoDays = avgReceivables.multiply(DAYS_IN_YEAR).divide(revenue, 1, RoundingMode.HALF_UP);
        }

        // 6. DPO (Days Payables Outstanding) = (Average Payables / COGS) * 365
        BigDecimal dpoDays = BigDecimal.ZERO;
        if (cogs != null && cogs.signum() > 0 && avgPayables != null) {
            dpoDays = avgPayables.multiply(DAYS_IN_YEAR).divide(cogs, 1, RoundingMode.HALF_UP);
        }

        Map<String, Object> details = Map.of(
                "availableCash", availableCash == null ? "0.00" : availableCash.toPlainString(),
                "periodMonths", periodMonths,
                "netCashDrain", netCashDrain.toPlainString(),
                "burnRateDescription", "月度净现金流出速度",
                "runwayDescription", "当前现金跑道可支撑月数",
                "rdIntensityDescription", "研发投入占营业收入比例(%)",
                "grossMarginDescription", "产品综合毛利率(%)",
                "dsoDescription", "应收账款周转天数(天)",
                "dpoDescription", "应付账款周转天数(天)"
        );

        String status = (approver != null) ? "APPROVED" : "DRAFT";
        return new KpiMetrics(
                burnRate,
                runwayMonths,
                rdIntensity,
                grossMargin,
                dsoDays,
                dpoDays,
                details,
                preparer,
                approver,
                status
        );
    }

    public static CashCalendar generateCashCalendar(
            UUID calendarId,
            Scope scope,
            LocalDate projectionDate,
            int horizonDays,
            BigDecimal openingCash,
            BigDecimal safeBufferAmount,
            Map<LocalDate, BigDecimal> scheduledInflows,
            Map<LocalDate, BigDecimal> scheduledOutflows,
            Actor publisher) {

        if (horizonDays != 30 && horizonDays != 60 && horizonDays != 90) {
            horizonDays = 30;
        }

        List<DailyCashForecast> forecasts = new ArrayList<>();
        BigDecimal currentBalance = openingCash == null ? BigDecimal.ZERO : openingCash;
        BigDecimal minBalance = currentBalance;
        boolean bufferBreached = false;

        for (int i = 1; i <= horizonDays; i++) {
            LocalDate date = projectionDate.plusDays(i);
            BigDecimal inflow = scheduledInflows == null ? BigDecimal.ZERO : scheduledInflows.getOrDefault(date, BigDecimal.ZERO);
            BigDecimal outflow = scheduledOutflows == null ? BigDecimal.ZERO : scheduledOutflows.getOrDefault(date, BigDecimal.ZERO);
            BigDecimal netChange = inflow.subtract(outflow);
            currentBalance = currentBalance.add(netChange);

            if (currentBalance.compareTo(minBalance) < 0) {
                minBalance = currentBalance;
            }

            boolean below = currentBalance.compareTo(safeBufferAmount) < 0;
            if (below) bufferBreached = true;

            forecasts.add(new DailyCashForecast(date, inflow, outflow, netChange, currentBalance, below));
        }

        String status = (publisher != null) ? "PUBLISHED" : "DRAFT";

        return new CashCalendar(
                calendarId,
                scope,
                projectionDate,
                horizonDays,
                openingCash,
                safeBufferAmount,
                minBalance,
                bufferBreached,
                forecasts,
                status,
                publisher
        );
    }
}
