package com.lumira.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Candidate: exact, single-column individual-report projection. No IO or publication authority. */
public final class ReportSourceEngine {
    private ReportSourceEngine() {}
    public enum Standard { CAS_2019, SBE_2011 }
    public enum Kind { BS, PL, CF }
    public enum Closing { NONE, MONTH_PROFIT_TRANSFER, ANNUAL_PROFIT_TRANSFER, TRANSFER_REVERSAL }
    public enum Semantic {
        CASH, RECEIVABLE, RECEIVABLE_NONCONTRACT_ADVANCE, RECEIVABLE_CONTRACT_ADVANCE,
        ADVANCE_RECEIPT, PAYABLE, PREPAYMENT,
        OTHER_RECEIVABLE, OTHER_PAYABLE, DIVIDEND_RECEIVABLE, INTEREST_RECEIVABLE,
        DIVIDEND_PAYABLE, INTEREST_PAYABLE, INVENTORY, RAW_MATERIAL, WORK_IN_PROGRESS,
        FINISHED_GOODS, TURNOVER_MATERIAL, FIXED_ASSET, FIXED_CLEARING,
        CONSTRUCTION, ENGINEERING_SUPPLIES, INTANGIBLE, DEVELOPMENT, LONG_PREPAID,
        SHORT_BORROWING, PAYROLL_PAYABLE, CAPITAL, CAPITAL_RESERVE, SURPLUS,
        RETAINED, CURRENT_PROFIT, REMAINING_PROFIT,
        REVENUE, COST, TAX_SURCHARGE, SELLING, MANAGEMENT, RESEARCH,
        FINANCE, INTEREST_EXPENSE, INTEREST_INCOME, INVESTMENT_GAIN,
        OTHER_GAIN, HEDGE_GAIN, FAIR_VALUE_GAIN, CREDIT_LOSS, ASSET_LOSS,
        DISPOSAL_GAIN, NONOPERATING_INCOME, NONOPERATING_EXPENSE, INCOME_TAX,
        GOVERNMENT_GRANT, ACTUAL_BAD_DEBT_LOSS
    }
    public enum ContraType { DEPRECIATION, AMORTIZATION, IMPAIRMENT }
    public record Scope(String tenantId, String entityId, String bookId, String fiscalYearId,
                        Standard standard, String reportScope, String currency, LocalDate from, LocalDate through,
                        String ledgerWatermark, String mappingVersion, String mappingApprovalId, String snapshotId) {}
    /** debitBalance is B; objectId/debt/asset identity is stable, detailId is the netting unit. */
    public record Balance(String sourceId, String snapshotId, String accountId, String detailId,
                          String objectId, Semantic semantic, String debitBalance) {}
    public record Contra(String sourceId, String snapshotId, String targetDetailId,
                         String objectId, ContraType type, String creditBalance) {}
    public record Movement(String sourceId, String snapshotId, String accountId,
                           Semantic semantic, LocalDate date, String debit, String credit,
                           Closing closingType, Closing reversedClosingType) {}
    /** Server-issued completeness evidence, never a client assertion or an implicit empty-list zero. */
    public record Coverage(Kind kind, String rowId, String snapshotId, String evidenceId) {}
    public record Input(Scope scope, String migrationMode, List<Balance> balances,
                        List<Contra> contras, List<Movement> movements, List<Coverage> coverage) {
        public Input {
            Objects.requireNonNull(scope);
            balances = List.copyOf(balances); contras = List.copyOf(contras);
            movements = List.copyOf(movements); coverage = List.copyOf(coverage);
        }
    }
    public record Trace(String operation, String sourceId, String accountId, String detailId,
                        String objectId, String fromRow, String toRow, String rawValue,
                        String evidenceId) {}
    public record Cell(String rowId, String rawValue, String status, List<Trace> trace,
                       List<String> gaps) {
        public Cell { trace = List.copyOf(trace); gaps = List.copyOf(gaps); }
    }
    public record Sheet(String template, List<Cell> cells) {
        public Sheet { cells = List.copyOf(cells); }
    }
    public record Result(String schema, Scope scope, String status, List<Sheet> sheets,
                         List<String> publicationGaps) {
        public Result { sheets = List.copyOf(sheets); publicationGaps = List.copyOf(publicationGaps); }
    }
    private record Route(String primary, int sign, String subset, int subsetSign) {}
    private static Route r(String row, int sign) { return new Route(row, sign, null, sign); }
    private static Route subset(String row, int sign, String child, int childSign) {
        return new Route(row, sign, child, childSign);
    }
    private static boolean small(Scope scope) { return scope.standard() == Standard.SBE_2011; }
    private static String template(boolean s, Kind k) {
        return s ? "SBE-" + k + "-2011" : "CAS-" + k + "-2019-A2";
    }
    private static void require(boolean ok, String code) {
        if (!ok) throw new IllegalArgumentException(code);
    }
    private static boolean text(String s) { return s != null && !s.isBlank(); }
    private static BigDecimal amount(String value, boolean posted) {
        require(value != null && value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]{1,8})?"), "INVALID_DECIMAL");
        BigDecimal n = new BigDecimal(value);
        require(n.precision() - n.scale() <= 20, "AMOUNT_OVERFLOW");
        require(!posted || n.stripTrailingZeros().scale() <= 2, "POSTED_CNY_PRECISION");
        return n;
    }
    private static String raw(BigDecimal value) { return value.toPlainString(); }
    private static void source(Set<String> seen, String id, String snap, Scope scope) {
        require(text(id) && seen.add(id), "DUPLICATE_OR_MISSING_SOURCE");
        require(scope.snapshotId().equals(snap), "MIXED_SNAPSHOT");
    }
    private static Route balanceRoute(Semantic sem, BigDecimal value, boolean s) {
        boolean debit = value.signum() >= 0;
        return switch (sem) {
            case CASH -> r(s ? "1" : "A01", 1);
            case RECEIVABLE -> {
                require(s || debit, "CAS_ADVANCE_NATURE_REQUIRED");
                yield r(debit ? (s ? "4" : "A05") : "34", debit ? 1 : -1);
            }
            case RECEIVABLE_NONCONTRACT_ADVANCE, ADVANCE_RECEIPT ->
                    r(debit ? (s ? "4" : "A05") : (s ? "34" : "L06"), debit ? 1 : -1);
            case RECEIVABLE_CONTRACT_ADVANCE -> {
                require(!s, "SBE_CONTRACT_ADVANCE_SEMANTIC_FORBIDDEN");
                yield r(debit ? "A05" : "L07", debit ? 1 : -1);
            }
            case PAYABLE, PREPAYMENT -> r(debit ? (s ? "5" : "A07") : (s ? "33" : "L05"), debit ? 1 : -1);
            case OTHER_RECEIVABLE, OTHER_PAYABLE -> r(debit ? (s ? "8" : "A08") : (s ? "39" : "L10"), debit ? 1 : -1);
            case DIVIDEND_RECEIVABLE -> r(s ? "6" : "A08", 1);
            case INTEREST_RECEIVABLE -> r(s ? "7" : "A08", 1);
            case DIVIDEND_PAYABLE -> r(s ? "38" : "L10", -1);
            case INTEREST_PAYABLE -> r(s ? "37" : "L10", -1);
            case INVENTORY -> r(s ? "9" : "A09", 1);
            case RAW_MATERIAL -> s ? subset("9", 1, "10", 1) : r("A09", 1);
            case WORK_IN_PROGRESS -> s ? subset("9", 1, "11", 1) : r("A09", 1);
            case FINISHED_GOODS -> s ? subset("9", 1, "12", 1) : r("A09", 1);
            case TURNOVER_MATERIAL -> s ? subset("9", 1, "13", 1) : r("A09", 1);
            case FIXED_ASSET -> r(s ? "18" : "A22", 1);
            case FIXED_CLEARING -> r(s ? "23" : "A22", 1);
            case CONSTRUCTION -> r(s ? "21" : "A23", 1);
            case ENGINEERING_SUPPLIES -> r(s ? "22" : "A23", 1);
            case INTANGIBLE -> r(s ? "25" : "A27", 1);
            case DEVELOPMENT -> r(s ? "26" : "A28", 1);
            case LONG_PREPAID -> r(s ? "27" : "A30", 1);
            case SHORT_BORROWING -> r(s ? "31" : "L01", -1);
            case PAYROLL_PAYABLE -> r(s ? "35" : "L08", -1);
            case CAPITAL -> r(s ? "48" : "E01", -1);
            case CAPITAL_RESERVE -> r(s ? "49" : "E03", -1);
            case SURPLUS -> r(s ? "50" : "E07", -1);
            case RETAINED, CURRENT_PROFIT, REMAINING_PROFIT -> r(s ? "51" : "E08", -1);
            default -> throw new IllegalArgumentException("UNSUPPORTED_BALANCE_SEMANTIC:" + sem);
        };
    }
    private static Route movementRoute(Semantic sem, boolean s) {
        return switch (sem) {
            case REVENUE -> r(s ? "1" : "P01", -1);
            case COST -> r(s ? "2" : "P02", 1);
            case TAX_SURCHARGE -> r(s ? "3" : "P03", 1);
            case SELLING -> r(s ? "11" : "P04", 1);
            case MANAGEMENT -> r(s ? "14" : "P05", 1);
            case RESEARCH -> s ? subset("14", 1, "17", 1) : r("P06", 1);
            case FINANCE -> r(s ? "18" : "P07", 1);
            case INTEREST_EXPENSE -> s ? subset("18", 1, "19", 1) : subset("P07", 1, "P07a", 1);
            case INTEREST_INCOME -> s ? subset("18", 1, "19", 1) : subset("P07", 1, "P07b", -1);
            case INVESTMENT_GAIN -> r(s ? "20" : "P09", -1);
            case NONOPERATING_INCOME -> r(s ? "22" : "P16", -1);
            case GOVERNMENT_GRANT -> {
                require(s, "CAS_GRANT_NATURE_REQUIRED"); yield subset("22", -1, "23", -1);
            }
            case NONOPERATING_EXPENSE -> r(s ? "24" : "P17", 1);
            case ACTUAL_BAD_DEBT_LOSS -> {
                require(s, "CAS_LOSS_RULE_REQUIRED"); yield subset("24", 1, "25", 1);
            }
            case INCOME_TAX -> r(s ? "31" : "P19", 1);
            case OTHER_GAIN, HEDGE_GAIN, FAIR_VALUE_GAIN, CREDIT_LOSS, ASSET_LOSS, DISPOSAL_GAIN -> {
                require(!s, "SBE_CAS_SEMANTIC_FORBIDDEN");
                yield r(switch (sem) {
                    case OTHER_GAIN -> "P08"; case HEDGE_GAIN -> "P10";
                    case FAIR_VALUE_GAIN -> "P11"; case CREDIT_LOSS -> "P12";
                    case ASSET_LOSS -> "P13"; default -> "P14";
                }, -1);
            }
            default -> throw new IllegalArgumentException("UNSUPPORTED_MOVEMENT_SEMANTIC:" + sem);
        };
    }
    private static Set<String> supported(boolean s, Kind kind) {
        Set<String> rows = new HashSet<>();
        if (kind == Kind.CF) return rows;
        for (Semantic sem : Semantic.values()) {
            for (BigDecimal sign : List.of(BigDecimal.ONE, BigDecimal.ONE.negate())) {
                try {
                    Route route = kind == Kind.BS ? balanceRoute(sem, sign, s) : movementRoute(sem, s);
                    rows.add(route.primary()); if (route.subset() != null) rows.add(route.subset());
                } catch (IllegalArgumentException ignored) { /* Not a declared supported semantic. */ }
            }
        }
        if (s && kind == Kind.BS) rows.add("19");
        return rows;
    }
    private static void add(Map<String, BigDecimal> values, Map<String, List<Trace>> traces,
                            String row, BigDecimal value, Trace trace) {
        values.merge(row, value, BigDecimal::add);
        traces.computeIfAbsent(row, ignored -> new ArrayList<>()).add(trace);
    }
    private static void route(Map<String, BigDecimal> values, Map<String, List<Trace>> traces,
                              Route route, BigDecimal value, String op, String id, String account,
                              String detail, String object, String from, String approval) {
        BigDecimal contribution = value.multiply(BigDecimal.valueOf(route.sign()));
        add(values, traces, route.primary(), contribution,
                new Trace(op, id, account, detail, object, from, route.primary(), raw(contribution), approval));
        if (route.subset() != null) {
            BigDecimal part = value.multiply(BigDecimal.valueOf(route.subsetSign()));
            add(values, traces, route.subset(), part,
                    new Trace("DISCLOSURE_SUBSET", id, account, detail, object,
                            route.primary(), route.subset(), raw(part), approval));
        }
    }
    public static Result calculate(Input input) {
        Scope c = input.scope(); boolean s = small(c);
        require(c.standard() != null && "CNY".equals(c.currency()), "UNSUPPORTED_STANDARD_OR_CURRENCY");
        require("INDIVIDUAL".equals(c.reportScope()), "UNSUPPORTED_REPORT_SCOPE");
        for (String id : Arrays.asList(c.tenantId(), c.entityId(), c.bookId(), c.fiscalYearId(),
                c.ledgerWatermark(), c.mappingVersion(), c.mappingApprovalId(), c.snapshotId())) require(text(id), "INCOMPLETE_SCOPE");
        require(c.from() != null && c.through() != null && !c.from().isAfter(c.through())
                && c.from().getYear() == c.through().getYear(), "INVALID_PERIOD");
        require("FULL_HISTORY".equals(input.migrationMode()), "MIGRATION_BASELINE_NOT_SUPPORTED");
        Map<String, BigDecimal> bs = new LinkedHashMap<>(), pl = new LinkedHashMap<>();
        Map<String, List<Trace>> bt = new LinkedHashMap<>(), pt = new LinkedHashMap<>();
        Map<String, Balance> details = new LinkedHashMap<>(); Set<String> seen = new HashSet<>();
        for (Balance b : input.balances()) {
            source(seen, b.sourceId(), b.snapshotId(), c);
            require(text(b.accountId()) && text(b.detailId()) && text(b.objectId()) && b.semantic() != null,
                    "STABLE_DETAIL_REQUIRED");
            require(details.putIfAbsent(b.detailId(), b) == null, "DUPLICATE_NETTING_UNIT");
            BigDecimal value = amount(b.debitBalance(), false);
            Route dest = balanceRoute(b.semantic(), value, s);
            String origin = balanceRoute(b.semantic(),
                    Set.of(Semantic.ADVANCE_RECEIPT, Semantic.PAYABLE, Semantic.OTHER_PAYABLE)
                            .contains(b.semantic()) ? BigDecimal.ONE.negate() : BigDecimal.ONE, s).primary();
            route(bs, bt, dest, value, origin.equals(dest.primary()) ? "BALANCE" : "RECLASSIFY",
                    b.sourceId(), b.accountId(), b.detailId(), b.objectId(), origin, c.mappingApprovalId());
        }
        Map<String, BigDecimal> deductions = new HashMap<>();
        for (Contra contra : input.contras()) {
            source(seen, contra.sourceId(), contra.snapshotId(), c);
            Balance target = details.get(contra.targetDetailId());
            require(target != null && target.objectId().equals(contra.objectId()), "ORPHAN_OR_WRONG_OBJECT_CONTRA");
            require(contra.type() != null, "CONTRA_TYPE_REQUIRED");
            BigDecimal value = amount(contra.creditBalance(), false);
            BigDecimal gross = amount(target.debitBalance(), false);
            require(value.signum() >= 0 && gross.signum() >= 0, "CONTRA_DIRECTION_INVALID");
            Set<Semantic> impairable = Set.of(Semantic.RECEIVABLE,
                    Semantic.RECEIVABLE_NONCONTRACT_ADVANCE, Semantic.RECEIVABLE_CONTRACT_ADVANCE,
                    Semantic.OTHER_RECEIVABLE,
                    Semantic.DIVIDEND_RECEIVABLE, Semantic.INTEREST_RECEIVABLE, Semantic.INVENTORY,
                    Semantic.RAW_MATERIAL, Semantic.WORK_IN_PROGRESS, Semantic.FINISHED_GOODS,
                    Semantic.TURNOVER_MATERIAL, Semantic.FIXED_ASSET, Semantic.CONSTRUCTION,
                    Semantic.ENGINEERING_SUPPLIES, Semantic.INTANGIBLE);
            require(switch (contra.type()) {
                case DEPRECIATION -> target.semantic() == Semantic.FIXED_ASSET;
                case AMORTIZATION -> target.semantic() == Semantic.INTANGIBLE;
                case IMPAIRMENT -> !s && impairable.contains(target.semantic());
            }, "CONTRA_NOT_APPLICABLE");
            BigDecimal total = deductions.merge(target.detailId(), value, BigDecimal::add);
            require(total.compareTo(gross) <= 0, "CONTRA_EXCEEDS_MATCHED_ASSET");
            String row = s && contra.type() == ContraType.DEPRECIATION ? "19"
                    : balanceRoute(target.semantic(), gross, s).primary();
            BigDecimal contribution = s && contra.type() == ContraType.DEPRECIATION ? value : value.negate();
            add(bs, bt, row, contribution, new Trace("MATCHED_CONTRA", contra.sourceId(),
                    target.accountId(), target.detailId(), target.objectId(), row, row, raw(contribution), c.mappingApprovalId()));
        }
        for (Movement m : input.movements()) {
            source(seen, m.sourceId(), m.snapshotId(), c);
            require(text(m.accountId()) && m.semantic() != null && m.date() != null
                    && !m.date().isBefore(c.from()) && !m.date().isAfter(c.through()), "MOVEMENT_SCOPE_INVALID");
            BigDecimal d = amount(m.debit(), true), cr = amount(m.credit(), true);
            require(d.signum() >= 0 && cr.signum() >= 0 && (d.signum() == 0) != (cr.signum() == 0),
                    "AUTHORITATIVE_SIDES_REQUIRED");
            require(m.closingType() != null, "CLOSING_TYPE_REQUIRED");
            if (m.closingType() == Closing.TRANSFER_REVERSAL)
                require(m.reversedClosingType() == Closing.MONTH_PROFIT_TRANSFER
                        || m.reversedClosingType() == Closing.ANNUAL_PROFIT_TRANSFER, "REVERSAL_ORIGIN_REQUIRED");
            else require(m.reversedClosingType() == null, "UNEXPECTED_REVERSAL_ORIGIN");
            if (m.closingType() != Closing.NONE) continue;
            route(pl, pt, movementRoute(m.semantic(), s), d.subtract(cr), "MOVEMENT",
                    m.sourceId(), m.accountId(), null, null, null, c.mappingApprovalId());
        }
        Map<Kind, Map<String, String>> coverage = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) coverage.put(kind, new HashMap<>());
        for (Coverage v : input.coverage()) {
            require(v.kind() != null && c.snapshotId().equals(v.snapshotId()) && text(v.evidenceId()),
                    "INVALID_COVERAGE_EVIDENCE");
            require(supported(s, v.kind()).contains(v.rowId()), "UNSUPPORTED_ROW_CANNOT_BE_CERTIFIED");
            require(coverage.get(v.kind()).putIfAbsent(v.rowId(), v.evidenceId()) == null,
                    "DUPLICATE_COVERAGE");
        }
        List<String> gaps = new ArrayList<>(); List<Sheet> sheets = new ArrayList<>();
        sheets.add(sheet(s, Kind.BS, bs, bt, coverage.get(Kind.BS), gaps));
        sheets.add(sheet(s, Kind.PL, pl, pt, coverage.get(Kind.PL), gaps));
        sheets.add(sheet(s, Kind.CF, Map.of(), Map.of(), Map.of(), gaps));
        gaps.addAll(List.of("RULE_PACKAGE_UNREVIEWED", "COMPARATIVE_COLUMN_REQUIRED",
                "CASH_SOURCE_COVERAGE_REQUIRED", "CASH_SCOPE_VERSION_REQUIRED", "CASH_CLASSIFICATION_REQUIRED",
                "CASH_OPENING_CLOSING_BRIDGE_REQUIRED", "PROFIT_TO_OPERATING_CASH_BRIDGE_REQUIRED",
                "EQUITY_PROFIT_BRIDGE_REQUIRED", "NOTES_REQUIRED", "PERSISTED_SNAPSHOT_AND_REVIEW_REQUIRED"));
        if (!s) gaps.add("EQUITY_CHANGES_STATEMENT_REQUIRED");
        return new Result("finance-report-source/1", c, "BLOCKED_DRAFT", sheets, gaps);
    }
    private static Sheet sheet(boolean s, Kind kind, Map<String, BigDecimal> partial,
                               Map<String, List<Trace>> sourceTraces, Map<String, String> complete,
                               List<String> gaps) {
        String template = template(s, kind);
        List<ReportCalculator.Formula> formulas = switch (kind) {
            case BS -> ReportCalculator.balanceSheetFormulas(s);
            case PL -> ReportCalculator.profitFormulas(s);
            case CF -> ReportCalculator.cashflowFormulas(s);
        };
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        Map<String, List<Trace>> traces = new LinkedHashMap<>();
        sourceTraces.forEach((key, value) -> traces.put(key, new ArrayList<>(value)));
        for (var entry : complete.entrySet()) {
            values.put(entry.getKey(), partial.getOrDefault(entry.getKey(), BigDecimal.ZERO));
            traces.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(
                    new Trace("COMPLETE_POPULATION", null, null, null, null, null,
                            entry.getKey(), null, entry.getValue()));
        }
        ReportCalculator.validateDependencies(template, values, formulas);
        boolean changed;
        do {
            changed = false;
            for (var f : formulas) if (!values.containsKey(f.rowId())
                    && f.terms().stream().allMatch(t -> values.containsKey(t.rowId()))) {
                BigDecimal value = BigDecimal.ZERO; List<Trace> lineage = new ArrayList<>();
                for (var term : f.terms()) {
                    BigDecimal part = values.get(term.rowId()).multiply(BigDecimal.valueOf(term.sign()));
                    value = value.add(part);
                    lineage.add(new Trace("SIGNED_ROW_SUM", null, null, null, null,
                            term.rowId(), f.rowId(), raw(part), "ReportCalculator:" + template));
                }
                values.put(f.rowId(), value); traces.put(f.rowId(), lineage); changed = true;
            }
        } while (changed);
        Set<String> supported = supported(s, kind); Map<String, ReportCalculator.Formula> graph = new HashMap<>();
        for (var f : formulas) graph.put(f.rowId(), f);
        List<Cell> cells = new ArrayList<>();
        for (var row : ReportDictionary.rows()) if (row.template().equals(template)) {
            BigDecimal value = values.get(row.id()); List<String> missing = new ArrayList<>();
            if (value == null) {
                if (graph.containsKey(row.id())) {
                    for (var term : graph.get(row.id()).terms()) if (!values.containsKey(term.rowId()))
                        missing.add("DEPENDENCY:" + term.rowId());
                } else missing.add(supported.contains(row.id()) ? "COMPLETE_POPULATION_REQUIRED"
                        : missingFact(s, kind, row.id()));
                for (String gap : missing) gaps.add(template + ":" + row.id() + ":" + gap);
            }
            cells.add(new Cell(row.id(), value == null ? null : raw(value), value == null ? "MISSING"
                    : value.signum() == 0 ? "ZERO" : "DRAFT", traces.getOrDefault(row.id(), List.of()), missing));
        }
        return new Sheet(template, cells);
    }
    private static String missingFact(boolean s, Kind kind, String row) {
        if (kind == Kind.CF) return "CASHFLOW_SOURCE_REQUIRED";
        if (kind == Kind.PL) {
            if (!s && row.startsWith("P21")) return "TAX_NET_OCI_MOVEMENT_AND_BRIDGE_REQUIRED";
            if (!s && row.startsWith("P23")) return "EPS_APPLICABILITY_WEIGHTED_SHARES_AND_DILUTION_REQUIRED";
            if (!s && (row.equals("P20a") || row.equals("P20b"))) return "CONTINUING_DISCONTINUED_TAX_ALLOCATION_REQUIRED";
            return "DISCLOSURE_NATURE_AND_EFFECTIVE_RULE_REQUIRED";
        }
        if (s) return switch (row) {
            case "2", "17" -> "COST_MEASUREMENT_AND_INVESTMENT_FACTS_REQUIRED";
            case "14", "16", "28", "40", "42", "43", "45" -> "APPROVED_MATURITY_AND_NATURE_ROUTE_REQUIRED";
            case "3", "32" -> "BILL_REGISTER_AND_OUTSTANDING_FACTS_REQUIRED";
            case "24" -> "BIOLOGICAL_ASSET_AND_MATCHED_DEPRECIATION_REQUIRED";
            case "36" -> "TAX_DETAIL_AND_APPROVED_SBE_VAT_PRESENTATION_REQUIRED";
            case "44" -> "DEFERRED_INCOME_FACTS_REQUIRED";
            default -> "SPECIAL_RULE_OR_FACT_REQUIRED";
        };
        return switch (row) {
            case "A02", "A03", "A06", "A15", "A16", "A17", "A18", "A19", "A20", "L02", "L03" ->
                    "FINANCIAL_MEASUREMENT_CLASSIFICATION_AND_MATCHED_ADJUSTMENTS_REQUIRED";
            case "A04", "L04" -> "BILL_REGISTER_AND_OUTSTANDING_FACTS_REQUIRED";
            case "A10", "A32", "L22" -> "CONTRACT_NETTING_AND_CURRENT_NONCURRENT_FACTS_REQUIRED";
            case "A11", "L11" -> "HELD_FOR_SALE_CLASSIFICATION_AND_EXCLUSIONS_REQUIRED";
            case "A12", "L12", "L15", "L16", "L17", "L18" -> "APPROVED_MATURITY_AND_FINANCING_SCHEDULE_REQUIRED";
            case "A13", "L09", "L13" -> "TAX_OR_OTHER_NATURE_AND_APPROVED_PRESENTATION_REQUIRED";
            case "A21", "A24", "A25", "A26", "A29" -> "SPECIAL_ASSET_MEASUREMENT_AND_MATCHED_CONTRA_REQUIRED";
            case "A31", "L21" -> "DEFERRED_TAX_RECOGNITION_AND_LEGAL_OFFSET_FACTS_REQUIRED";
            case "L19", "L20" -> "OBLIGATION_NATURE_AND_CLASSIFICATION_REQUIRED";
            case "E02", "E02a", "E02b", "L16a", "L16b" -> "INSTRUMENT_TERMS_AND_EQUITY_LIABILITY_CLASSIFICATION_REQUIRED";
            case "E04", "E05", "E06" -> "EQUITY_COMPONENT_FACTS_AND_BRIDGE_REQUIRED";
            default -> "SPECIAL_RULE_OR_FACT_REQUIRED";
        };
    }
}
