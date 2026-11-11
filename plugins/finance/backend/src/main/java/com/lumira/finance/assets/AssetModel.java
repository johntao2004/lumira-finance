package com.lumira.finance.assets;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Immutable, accounting-only snapshots. No inferred tax qualification. */
public final class AssetModel {
    private AssetModel() {}
    public record Scope(UUID tenant, UUID book, UUID year, UUID period, LocalDate date) {}
    public record Actor(UUID account, UUID person) {}
    public record Context(Scope scope, Actor actor, String client) {}
    public record Policy(String framework, String kind, String method, String firstPeriod,
                         int months, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal residual, UUID assessmentId,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal basisAccumulated, int basisElapsed) {}
    public record Accounts(UUID cost, UUID accumulated, UUID expense) {}
    public record Card(UUID id, String name, String classification, LocalDate acquired,
                       LocalDate ready, String location, UUID custodian, Policy policy,
                       Accounts accounts, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cost, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal accumulated,
                       @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal impairment, int elapsed, String lastPeriod,
                       long version, String status) {}
    public record InputPolicy(String framework, String kind, String method, String firstPeriod,
                              int months, String residual, UUID assessmentId) {}
    public record InputCard(String name, String classification, LocalDate acquired,
                            LocalDate ready, String location, UUID custodian, InputPolicy policy,
                            Accounts accounts, String cost) {}
    public record Line(UUID account, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal debit, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal credit) {}
    public record Trace(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal base, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal unrounded, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal recognized,
                        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal roundingAdjustment, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal remaining, int remainingMonths) {}
    public record Change(Card before, Card after, Trace trace) {}
    public record ScheduleRow(String period, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal remaining) {}
    public record EstimatePreview(List<ScheduleRow> oldSchedule, List<ScheduleRow> newSchedule) {}
    public record Plan(UUID id, Scope scope, String kind, String businessKey, UUID sourceId,
                       String reason, Actor maker, List<Change> changes, List<Line> lines,
                       EstimatePreview estimate, UUID reverses, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal sourceAmount) {}
    public record StoredPlan(Plan plan, String digest, String status, Actor approver,
                             UUID journal, long version) {}
    public record Receipt(UUID planId, String status, UUID journalId, long version) {}
    public record PostReceipt(UUID journalId) {}
    public static IllegalArgumentException fail(String code) { return new IllegalArgumentException(code); }
}
