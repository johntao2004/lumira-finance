package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import org.springframework.transaction.PlatformTransactionManager;

import java.math.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Small annual account projections; no journal documents or closing source snapshots are hydrated.
 */
abstract class FinanceCarryReadinessProjection extends FinanceProfitClosingAccess {
    FinanceCarryReadinessProjection(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager tx,
            AuthorizationContextProjectionPort auth) {
        super(db, finance, gate, tx, auth);
    }

    String digest(Object value) {
        return FinanceService.hash(finance.encode(finance.canonical(value)));
    }

    static String counter(Object value) {
        if (value == null || !value.toString().matches("0|[1-9][0-9]{0,18}")) throw unavailable();
        try {
            Long.parseLong(value.toString());
        } catch (NumberFormatException bad) {
            throw unavailable();
        }
        return value.toString();
    }

    static FinanceException unavailable() {
        return error("CARRY_READINESS_OBSERVATION_UNAVAILABLE", "缺少真实年度观察基线，不能伪造零代次");
    }

    static FinanceException limit() {
        return error("CARRY_READINESS_LIMIT_EXCEEDED", "年度账户、计划或标量超出本只读预检预算");
    }

    static BigDecimal decimal(Object value) {
        if (value == null) throw new IllegalArgumentException();
        var n = new BigDecimal(value.toString()).setScale(8, RoundingMode.UNNECESSARY);
        if (n.precision() > 28 || n.abs().compareTo(BigDecimal.TEN.pow(20)) >= 0)
            throw new IllegalArgumentException();
        return n.signum() == 0 ? BigDecimal.ZERO.setScale(8) : n;
    }

    static Map<String, Object> amount(String state, BigDecimal n) {
        return map(
                "state",
                state,
                "amount",
                state.equals("KNOWN") ? n.toPlainString() : null,
                "reason",
                state.equals("KNOWN") ? null : "当前年度数据或缺失能力不允许推定金额");
    }

    static Map<String, Object> unknown(String state) {
        return amount(state, null);
    }

    static Map<String, Object> known(BigDecimal n) {
        try {
            return amount("KNOWN", decimal(n));
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return unknown("UNKNOWN_INVALID_VALUE");
        }
    }

    static Map<String, Object> account(Map<String, Object> row) {
        for (var key : List.of("code", "name")) {
            Object text = row.get(key);
            if (!(text instanceof String s)
                    || s.isEmpty()
                    || s.length() > (key.equals("code") ? 100 : 500)) throw limit();
        }
        if (!Set.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE")
                        .contains(row.get("category"))
                || !Set.of("DEBIT", "CREDIT").contains(row.get("normal_side"))
                || !Set.of("NONE", "CURRENT_YEAR_PROFIT", "RETAINED_EARNINGS")
                        .contains(row.get("purpose"))
                || !(row.get("enabled") instanceof Boolean)) throw limit();
        return map(
                "id",
                row.get("id"),
                "code",
                row.get("code"),
                "name",
                row.get("name"),
                "category",
                row.get("category"),
                "normalSide",
                row.get("normal_side"),
                "purpose",
                row.get("purpose"),
                "enabled",
                row.get("enabled"));
    }

    final class YearData {
        UUID id;
        int number;
        String state = "KNOWN";
        Map<String, Object> year, vector, observation;
        List<Map<String, Object>> accounts, periods;
        String openingDigest;
        Map<UUID, BigDecimal[]> values = new HashMap<>();
        Set<UUID> openingAccounts = new HashSet<>();
        Map<String, Object> journals, closings;

        Map<String, Object> value(UUID account, int column) {
            return account == null
                    ? unknown("MISSING_ACCOUNT")
                    : !state.equals("KNOWN") ? unknown(state) : known(values.get(account)[column]);
        }
    }

    Map<String, Object> baseline(Call call, String table, Object tenant, Object book, UUID year) {
        var rows =
                call.list(
                        "SELECT * FROM " + table + " WHERE tenant_id=? AND book_id=? AND year_id=?",
                        tenant,
                        book,
                        year);
        if (rows.size() != 1) throw unavailable();
        return rows.getFirst();
    }

    Map<String, Object> counts(Call call, String table, Object tenant, Object book, UUID year) {
        var rows =
                call.list(
                        "SELECT status,count(*)::text n FROM "
                                + table
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? GROUP BY status",
                        tenant,
                        book,
                        year);
        var result =
                map(
                        "draft",
                        "0",
                        "submitted",
                        "0",
                        "approved",
                        "0",
                        "posted",
                        "0",
                        "cancelled",
                        "0");
        for (var row : rows) {
            String key = row.get("status").toString().toLowerCase(Locale.ROOT);
            if (!result.containsKey(key)) throw unavailable();
            result.put(key, counter(row.get("n")));
        }
        return result;
    }

    YearData yearData(Call call, Map<String, Object> scope, UUID yearId) {
        Object tenant = scope.get("tenant_id"), book = scope.get("id");
        var d = new YearData();
        d.id = yearId;
        d.year =
                call.one(
                        "SELECT * FROM plugin_finance_year WHERE tenant_id=? AND book_id=? AND"
                                + " id=?",
                        tenant,
                        book,
                        yearId);
        d.number = ((Number) d.year.get("year")).intValue();
        var rows =
                call.list(
                        "SELECT id,code,name,category,normal_side,purpose,enabled FROM"
                                + " plugin_finance_account WHERE tenant_id=? AND book_id=? AND"
                                + " year_id=? ORDER BY code COLLATE \"C\",id LIMIT 5001",
                        tenant,
                        book,
                        yearId);
        if (rows.size() > 5000) throw limit();
        d.accounts = rows.stream().map(FinanceCarryReadinessProjection::account).toList();
        d.periods =
                call.list(
                        "SELECT id,month,status,version::text version FROM plugin_finance_period"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? ORDER BY month",
                        tenant,
                        book,
                        yearId);
        if (d.periods.size() != 12) throw unavailable();
        for (int i = 0; i < 12; i++) {
            var p = d.periods.get(i);
            if (((Number) p.get("month")).intValue() != i + 1
                    || !Set.of("OPEN", "CLOSED").contains(p.get("status"))) throw unavailable();
            counter(p.get("version"));
        }
        var guard = baseline(call, "plugin_finance_year_guard", tenant, book, yearId);
        var list = baseline(call, "plugin_finance_journal_list_guard", tenant, book, yearId);
        var mode = baseline(call, "plugin_finance_journal_person_mode", tenant, book, yearId);
        var closing = baseline(call, "plugin_finance_profit_closing_guard", tenant, book, yearId);
        String profile = Objects.toString(d.year.get("precision_profile"), "UNKNOWN");
        if (!Set.of("V4", "LEGACY_V3").contains(profile)) profile = "UNKNOWN";
        d.vector =
                map(
                        "yearId",
                        yearId,
                        "yearNumber",
                        d.number,
                        "yearVersion",
                        counter(d.year.get("version")),
                        "yearStatus",
                        d.year.get("status"),
                        "precisionProfile",
                        profile,
                        "precisionReviewRequired",
                        d.year.get("precision_review_required"),
                        "currencyMinorUnit",
                        d.year.get("currency_minor_unit"),
                        "personMode",
                        mode.get("mode"),
                        "modeEpoch",
                        mode.get("epoch"),
                        "modeGeneration",
                        counter(mode.get("generation")),
                        "postingSeq",
                        counter(guard.get("posting_seq")),
                        "openingVersion",
                        counter(guard.get("opening_version")),
                        "baselineVersion",
                        counter(guard.get("baseline_version")),
                        "sourceCloseVersion",
                        counter(guard.get("source_close_version")),
                        "journalReadEpoch",
                        list.get("read_epoch"),
                        "journalGeneration",
                        counter(list.get("generation")),
                        "closingGeneration",
                        counter(closing.get("generation")),
                        "accountsDigest",
                        digest(d.accounts),
                        "periodsDigest",
                        digest(d.periods));
        if (!profile.equals("V4")
                || Boolean.TRUE.equals(d.year.get("precision_review_required"))
                || !Objects.equals(d.year.get("currency_minor_unit"), 2))
            d.state = "UNSUPPORTED_PRECISION";
        else if (!counter(guard.get("baseline_version")).equals("0"))
            d.state = "UNSUPPORTED_BASELINE";
        var invalid =
                call.one(
                        "SELECT count(*) FILTER(WHERE j.date IS NULL OR j.date<?::date OR"
                            + " j.date>?::date OR j.amount_format_version IS DISTINCT FROM 'V4_8'"
                            + " OR p.journal_id IS NULL OR p.posting_seq IS NULL OR"
                            + " p.posting_seq>?)::text n FROM plugin_finance_journal j LEFT JOIN"
                            + " plugin_finance_posting p"
                            + " ON(p.tenant_id,p.book_id,p.year_id,p.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id)"
                            + " WHERE j.tenant_id=? AND j.book_id=? AND j.year_id=? AND"
                            + " j.status='POSTED'",
                        LocalDate.of(d.number, 1, 1).toString(),
                        LocalDate.of(d.number, 12, 31).toString(),
                        guard.get("posting_seq"),
                        tenant,
                        book,
                        yearId);
        if (d.state.equals("KNOWN") && !counter(invalid.get("n")).equals("0"))
            d.state = "UNKNOWN_INVALID_VALUE";
        var opening =
                call.list(
                        "SELECT account_id,balance::text balance,amount_format_version FROM"
                                + " plugin_finance_opening WHERE tenant_id=? AND book_id=? AND"
                                + " year_id=? ORDER BY account_id",
                        tenant,
                        book,
                        yearId);
        if (opening.size() > 5000) throw limit();
        d.openingDigest = digest(opening);
        var movements =
                call.list(
                        "SELECT l.account_id,SUM(l.debit)::text debit,SUM(l.credit)::text"
                            + " credit,bool_or(l.debit::text IN('NaN','Infinity','-Infinity') OR"
                            + " l.credit::text IN('NaN','Infinity','-Infinity') OR l.debit<0 OR"
                            + " l.credit<0) bad FROM plugin_finance_line l JOIN"
                            + " plugin_finance_journal j"
                            + " ON(j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id)"
                            + " WHERE l.tenant_id=? AND l.book_id=? AND l.year_id=? AND"
                            + " j.status='POSTED' GROUP BY l.account_id",
                        tenant,
                        book,
                        yearId);
        for (var a : d.accounts)
            d.values.put(
                    id(a.get("id")),
                    new BigDecimal[] {
                        BigDecimal.ZERO.setScale(8),
                        BigDecimal.ZERO.setScale(8),
                        BigDecimal.ZERO.setScale(8),
                        BigDecimal.ZERO.setScale(8)
                    });
        for (var o : opening) d.openingAccounts.add(id(o.get("account_id")));
        try {
            for (var o : opening) {
                UUID aid = id(o.get("account_id"));
                if (!d.values.containsKey(aid) || !"V4_8".equals(o.get("amount_format_version")))
                    throw new IllegalArgumentException();
                d.values.get(aid)[0] = decimal(o.get("balance"));
            }
            for (var m : movements) {
                UUID aid = id(m.get("account_id"));
                if (!d.values.containsKey(aid) || Boolean.TRUE.equals(m.get("bad")))
                    throw new IllegalArgumentException();
                d.values.get(aid)[1] = decimal(m.get("debit"));
                d.values.get(aid)[2] = decimal(m.get("credit"));
            }
            for (var a : d.values.values()) a[3] = decimal(a[0].add(a[1]).subtract(a[2]));
        } catch (IllegalArgumentException | ArithmeticException bad) {
            if (d.state.equals("KNOWN")) d.state = "UNKNOWN_INVALID_VALUE";
        }
        d.journals = counts(call, "plugin_finance_journal", tenant, book, yearId);
        d.closings = counts(call, "plugin_finance_profit_closing_run", tenant, book, yearId);
        d.observation = observe(d, opening.size());
        return d;
    }

    Map<String, Object> total(YearData d, int column, int direction) {
        if (!d.state.equals("KNOWN")) return unknown(d.state);
        BigDecimal result = BigDecimal.ZERO.setScale(8);
        for (var v : d.values.values())
            result =
                    result.add(
                            direction == 0
                                    ? v[column]
                                    : direction > 0
                                            ? v[column].max(BigDecimal.ZERO)
                                            : v[column].negate().max(BigDecimal.ZERO));
        return known(result);
    }

    Map<String, Object> observe(YearData d, int openingCount) {
        BigDecimal profit = BigDecimal.ZERO.setScale(8);
        int profitAccounts = 0, nonzero = 0;
        for (var a : d.accounts) {
            var v = d.values.get(id(a.get("id")))[3];
            if (a.get("purpose").equals("CURRENT_YEAR_PROFIT")) {
                profitAccounts++;
                profit = profit.add(v);
            }
            if (Set.of("REVENUE", "EXPENSE").contains(a.get("category")) && v.signum() != 0)
                nonzero++;
        }
        var debit = total(d, 3, 1);
        var credit = total(d, 3, -1);
        boolean totalsKnown =
                List.of(
                                total(d, 0, 1),
                                total(d, 0, -1),
                                total(d, 1, 0),
                                total(d, 2, 0),
                                debit,
                                credit)
                        .stream()
                        .allMatch(x -> x.get("state").equals("KNOWN"));
        // An overflow invalidates every monetary total of this year; never publish a known subset.
        if (!totalsKnown && d.state.equals("KNOWN")) d.state = "UNKNOWN_INVALID_VALUE";
        String qualification =
                switch (d.state) {
                    case "KNOWN" -> "SUPPORTED_V4";
                    case "UNSUPPORTED_PRECISION" -> "UNSUPPORTED_PRECISION";
                    case "UNSUPPORTED_BASELINE" -> "UNSUPPORTED_BASELINE";
                    default -> "UNKNOWN_INVALID_DATA";
                };
        return map(
                "yearId",
                d.id,
                "yearNumber",
                d.number,
                "rangeStart",
                LocalDate.of(d.number, 1, 1).toString(),
                "rangeEnd",
                LocalDate.of(d.number, 12, 31).toString(),
                "dataQualification",
                qualification,
                "accountCount",
                d.accounts.size(),
                "periods",
                d.periods,
                "openingRowCount",
                Integer.toString(openingCount),
                "openingFactModel",
                "PRODUCT_BALANCE_ROWS_ONLY",
                "formalOpeningQualification",
                "NOT_IMPLEMENTED",
                "openingDebit",
                total(d, 0, 1),
                "openingCredit",
                total(d, 0, -1),
                "postedMovementsDefinition",
                "FULL_CALENDAR_YEAR_POSTED_GROSS_INCLUDING_CLOSINGS_NOT_ECONOMIC_PROFIT",
                "postedDebit",
                total(d, 1, 0),
                "postedCredit",
                total(d, 2, 0),
                "endingDebitBalances",
                total(d, 3, 1),
                "endingCreditBalances",
                total(d, 3, -1),
                "trialBalanced",
                d.state.equals("KNOWN") ? debit.get("amount").equals(credit.get("amount")) : null,
                "journalCounts",
                d.journals,
                "journalCountsIncludeClosingJournals",
                true,
                "closingRunCounts",
                d.closings,
                "nonzeroProfitLossAccountCount",
                d.state.equals("KNOWN") ? nonzero : null,
                "currentYearProfitAccountCount",
                profitAccounts,
                "currentYearProfitResidual",
                d.state.equals("KNOWN") ? known(profit) : unknown(d.state),
                "importedYtdBaselineQualification",
                "NOT_IMPLEMENTED",
                "importedYtdBaselineAmount",
                unknown("NOT_IMPLEMENTED"));
    }
}
