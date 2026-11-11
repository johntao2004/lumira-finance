package com.lumira.finance.statutory;

import static com.lumira.finance.statutory.StatutoryModels.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

@Repository
public class StatutoryStore {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public StatutoryStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization error", e);
        }
    }

    // -------------------------------------------------------------------------
    // 1. Report Package Persistence
    // -------------------------------------------------------------------------
    public void savePackage(ReportPackage pkg) {
        Scope s = pkg.scope();
        jdbc.sql("""
            INSERT INTO plugin_finance_statutory_package (
                tenant_id, legal_entity_id, book_id, year_id, id,
                package_type, standard, period_start, period_end, filing_target,
                status, version, preparer, reviewer, approver,
                publication_gaps, content_digest, created_at, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, 1, ?::jsonb, ?::jsonb, ?::jsonb,
                ?::jsonb, ?, ?, ?
            ) ON CONFLICT (tenant_id, id) DO UPDATE SET
                status = EXCLUDED.status,
                reviewer = EXCLUDED.reviewer,
                approver = EXCLUDED.approver,
                publication_gaps = EXCLUDED.publication_gaps,
                content_digest = EXCLUDED.content_digest,
                updated_at = EXCLUDED.updated_at
        """)
        .params(
                s.tenantId(), s.entityId(), s.bookId(), s.yearId(), pkg.id(),
                pkg.packageType().name(), s.standard().name(), Date.valueOf(s.from()), Date.valueOf(s.through()), pkg.filingTarget().name(),
                pkg.status().name(), toJson(pkg.preparer()), pkg.reviewer() == null ? null : toJson(pkg.reviewer()), pkg.approver() == null ? null : toJson(pkg.approver()),
                toJson(pkg.publicationGaps()), pkg.contentDigest(), Timestamp.from(pkg.createdAt()), Timestamp.from(pkg.updatedAt())
        ).update();

        // Save sheets
        for (StatementSheet sheet : pkg.sheets()) {
            UUID sheetId = UUID.nameUUIDFromBytes((pkg.id().toString() + ":" + sheet.kind()).getBytes());
            jdbc.sql("""
                INSERT INTO plugin_finance_statutory_statement (
                    tenant_id, package_id, id, kind, template_code, cells, articulation_status, articulation_details
                ) VALUES (
                    ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb
                ) ON CONFLICT (tenant_id, package_id, id) DO UPDATE SET
                    cells = EXCLUDED.cells,
                    articulation_status = EXCLUDED.articulation_status,
                    articulation_details = EXCLUDED.articulation_details
            """)
            .params(
                    s.tenantId(), pkg.id(), sheetId, sheet.kind().name(), sheet.templateCode(),
                    toJson(sheet.cells()), sheet.articulationStatus(), toJson(sheet.articulationDetails())
            ).update();
        }

        // Save notes if present
        if (pkg.notes() != null) {
            UUID notesId = UUID.nameUUIDFromBytes((pkg.id().toString() + ":NOTES").getBytes());
            jdbc.sql("""
                INSERT INTO plugin_finance_statutory_notes (
                    tenant_id, package_id, id, sections, mandatory_complete, missing_sections
                ) VALUES (
                    ?, ?, ?, ?::jsonb, ?, ?::jsonb
                ) ON CONFLICT (tenant_id, package_id, id) DO UPDATE SET
                    sections = EXCLUDED.sections,
                    mandatory_complete = EXCLUDED.mandatory_complete,
                    missing_sections = EXCLUDED.missing_sections
            """)
            .params(
                    s.tenantId(), pkg.id(), notesId, toJson(pkg.notes().sections()),
                    pkg.notes().mandatoryComplete(), toJson(pkg.notes().missingSections())
            ).update();
        }
    }

    public Optional<ReportPackage> findPackageById(UUID tenantId, UUID packageId) {
        Optional<ReportPackage> pkgOpt = jdbc.sql("""
            SELECT id, legal_entity_id, book_id, year_id, package_type, standard,
                   period_start, period_end, filing_target, status, content_digest,
                   preparer, reviewer, approver, publication_gaps, created_at, updated_at
            FROM plugin_finance_statutory_package
            WHERE tenant_id = ? AND id = ?
        """)
        .params(tenantId, packageId)
        .query((rs, rowNum) -> {
            try {
                Scope scope = new Scope(
                        tenantId,
                        UUID.fromString(rs.getString("legal_entity_id")),
                        UUID.fromString(rs.getString("book_id")),
                        UUID.fromString(rs.getString("year_id")),
                        Standard.valueOf(rs.getString("standard")),
                        rs.getDate("period_start").toLocalDate(),
                        rs.getDate("period_end").toLocalDate(),
                        "CNY"
                );
                Actor prep = mapper.readValue(rs.getString("preparer"), Actor.class);
                Actor rev = rs.getString("reviewer") == null ? null : mapper.readValue(rs.getString("reviewer"), Actor.class);
                Actor app = rs.getString("approver") == null ? null : mapper.readValue(rs.getString("approver"), Actor.class);
                List<String> gaps = mapper.readValue(rs.getString("publication_gaps"), List.class);

                return new ReportPackage(
                        UUID.fromString(rs.getString("id")),
                        scope,
                        PackageType.valueOf(rs.getString("package_type")),
                        FilingTarget.valueOf(rs.getString("filing_target")),
                        PackageStatus.valueOf(rs.getString("status")),
                        List.of(),
                        null,
                        null,
                        gaps,
                        rs.getString("content_digest"),
                        prep,
                        rev,
                        app,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                );
            } catch (Exception e) {
                throw new RuntimeException("Error mapping ReportPackage", e);
            }
        }).optional();

        if (pkgOpt.isEmpty()) return Optional.empty();
        ReportPackage base = pkgOpt.get();

        // Load sheets from plugin_finance_statutory_statement
        List<StatementSheet> sheets = jdbc.sql("""
            SELECT kind, template_code, cells, articulation_status, articulation_details
            FROM plugin_finance_statutory_statement
            WHERE tenant_id = ? AND package_id = ?
            ORDER BY created_at ASC
        """)
        .params(tenantId, packageId)
        .query((rs, rowNum) -> {
            try {
                StatementKind kind = StatementKind.valueOf(rs.getString("kind"));
                String templateCode = rs.getString("template_code");
                Map<String, Object> rawCells = mapper.readValue(rs.getString("cells"), Map.class);
                Map<String, ReportCell> cellMap = new LinkedHashMap<>();
                for (var e : rawCells.entrySet()) {
                    String cellJson = mapper.writeValueAsString(e.getValue());
                    cellMap.put(e.getKey(), mapper.readValue(cellJson, ReportCell.class));
                }
                String articulationStatus = rs.getString("articulation_status");
                Map<String, Object> articulationDetails = mapper.readValue(rs.getString("articulation_details"), Map.class);
                return new StatementSheet(kind, templateCode, cellMap, articulationStatus, articulationDetails, List.of());
            } catch (Exception e) {
                throw new RuntimeException("Error mapping StatementSheet", e);
            }
        }).list();

        // Load structured notes
        StructuredNotes notes = jdbc.sql("""
            SELECT sections, mandatory_complete, missing_sections
            FROM plugin_finance_statutory_notes
            WHERE tenant_id = ? AND package_id = ?
        """)
        .params(tenantId, packageId)
        .query((rs, rowNum) -> {
            try {
                Map<String, String> sections = mapper.readValue(rs.getString("sections"), Map.class);
                boolean mandatoryComplete = rs.getBoolean("mandatory_complete");
                List<String> missing = mapper.readValue(rs.getString("missing_sections"), List.class);
                return new StructuredNotes(sections, mandatoryComplete, missing);
            } catch (Exception e) {
                throw new RuntimeException("Error mapping StructuredNotes", e);
            }
        }).optional().orElse(null);

        // Load latest worksheet if present
        Worksheet worksheet = findLatestWorksheet(tenantId, packageId).orElse(null);

        return Optional.of(new ReportPackage(
                base.id(),
                base.scope(),
                base.packageType(),
                base.filingTarget(),
                base.status(),
                sheets,
                worksheet,
                notes,
                base.publicationGaps(),
                base.contentDigest(),
                base.preparer(),
                base.reviewer(),
                base.approver(),
                base.createdAt(),
                base.updatedAt()
        ));
    }

    // -------------------------------------------------------------------------
    // 2. Post-Extraction Adjustment Worksheet Persistence
    // -------------------------------------------------------------------------
    public void saveWorksheet(UUID tenantId, Worksheet ws) {
        jdbc.sql("""
            INSERT INTO plugin_finance_statutory_worksheet (
                tenant_id, package_id, id, name, adjustments,
                before_digest, after_digest, reason, status, preparer, approver
            ) VALUES (
                ?, ?, ?, ?, ?::jsonb,
                ?, ?, ?, ?, ?::jsonb, ?::jsonb
            ) ON CONFLICT (tenant_id, package_id, id) DO UPDATE SET
                adjustments = EXCLUDED.adjustments,
                after_digest = EXCLUDED.after_digest,
                status = EXCLUDED.status,
                approver = EXCLUDED.approver
        """)
        .params(
                tenantId, ws.packageId(), ws.id(), ws.name(), toJson(ws.adjustments()),
                ws.beforeDigest(), ws.afterDigest(), ws.name(), ws.status(),
                toJson(ws.preparer()), ws.approver() == null ? null : toJson(ws.approver())
        ).update();
    }

    public Optional<Worksheet> findWorksheetById(UUID tenantId, UUID packageId, UUID worksheetId) {
        return jdbc.sql("""
            SELECT id, package_id, name, adjustments, before_digest, after_digest, status, preparer, approver
            FROM plugin_finance_statutory_worksheet
            WHERE tenant_id = ? AND package_id = ? AND id = ?
        """)
        .params(tenantId, packageId, worksheetId)
        .query((rs, rowNum) -> mapWorksheet(rs))
        .optional();
    }

    public Optional<Worksheet> findLatestWorksheet(UUID tenantId, UUID packageId) {
        return jdbc.sql("""
            SELECT id, package_id, name, adjustments, before_digest, after_digest, status, preparer, approver
            FROM plugin_finance_statutory_worksheet
            WHERE tenant_id = ? AND package_id = ?
            ORDER BY created_at DESC
            LIMIT 1
        """)
        .params(tenantId, packageId)
        .query((rs, rowNum) -> mapWorksheet(rs))
        .optional();
    }

    private Worksheet mapWorksheet(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            UUID id = UUID.fromString(rs.getString("id"));
            UUID pkgId = UUID.fromString(rs.getString("package_id"));
            String name = rs.getString("name");
            List<Object> rawList = mapper.readValue(rs.getString("adjustments"), List.class);
            List<AdjustmentEntry> adjustments = new ArrayList<>();
            for (Object item : rawList) {
                String itemJson = mapper.writeValueAsString(item);
                adjustments.add(mapper.readValue(itemJson, AdjustmentEntry.class));
            }
            String beforeDigest = rs.getString("before_digest");
            String afterDigest = rs.getString("after_digest");
            String status = rs.getString("status");
            Actor preparer = mapper.readValue(rs.getString("preparer"), Actor.class);
            Actor approver = rs.getString("approver") == null ? null : mapper.readValue(rs.getString("approver"), Actor.class);
            return new Worksheet(id, pkgId, name, adjustments, beforeDigest, afterDigest, status, preparer, approver);
        } catch (Exception e) {
            throw new RuntimeException("Error mapping Worksheet", e);
        }
    }

    // -------------------------------------------------------------------------
    // 3. KPI Snapshot Persistence
    // -------------------------------------------------------------------------
    public void saveKpiSnapshot(UUID tenantId, UUID entityId, UUID bookId, UUID yearId, UUID kpiId, LocalDate start, LocalDate end, KpiMetrics kpi) {
        jdbc.sql("""
            INSERT INTO plugin_finance_kpi_snapshot (
                tenant_id, legal_entity_id, book_id, year_id, id,
                period_start, period_end, burn_rate, runway_months,
                rd_intensity, gross_margin, dso_days, dpo_days,
                metrics_detail, prepared_by, approved_by, status
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?::jsonb, ?::jsonb, ?::jsonb, ?
            )
        """)
        .params(
                tenantId, entityId, bookId, yearId, kpiId,
                Date.valueOf(start), Date.valueOf(end), kpi.burnRate(), kpi.runwayMonths(),
                kpi.rdIntensity(), kpi.grossMargin(), kpi.dsoDays(), kpi.dpoDays(),
                toJson(kpi.details()), toJson(kpi.preparer()), kpi.approver() == null ? null : toJson(kpi.approver()), kpi.status()
        ).update();
    }

    // -------------------------------------------------------------------------
    // 4. Cash Calendar Persistence
    // -------------------------------------------------------------------------
    public void saveCashCalendar(CashCalendar cal) {
        Scope s = cal.scope();
        jdbc.sql("""
            INSERT INTO plugin_finance_cash_calendar (
                tenant_id, legal_entity_id, book_id, id,
                projection_date, horizon_days, opening_cash_balance,
                safe_buffer_amount, daily_forecasts, min_projected_balance,
                buffer_breached, status, publisher
            ) VALUES (
                ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?::jsonb, ?,
                ?, ?, ?::jsonb
            ) ON CONFLICT (tenant_id, id) DO UPDATE SET
                status = EXCLUDED.status,
                publisher = EXCLUDED.publisher
        """)
        .params(
                s.tenantId(), s.entityId(), s.bookId(), cal.id(),
                Date.valueOf(cal.projectionDate()), cal.horizonDays(), cal.openingBalance(),
                cal.safeBufferAmount(), toJson(cal.dailyForecasts()), cal.minProjectedBalance(),
                cal.bufferBreached(), cal.status(), cal.publisher() == null ? null : toJson(cal.publisher())
        ).update();
    }

    // -------------------------------------------------------------------------
    // 5. Consolidation Scope & Worksheet Persistence
    // -------------------------------------------------------------------------
    public void saveConsolidationScope(UUID tenantId, ConsolidationScope scope, Actor creator) {
        ensureConsolidationScope(tenantId, scope, creator);
    }

    public void ensureConsolidationScope(UUID tenantId, ConsolidationScope scope, Actor creator) {
        Integer count = jdbc.sql("SELECT count(*) FROM plugin_finance_consolidation_scope WHERE tenant_id = ? AND id = ?")
                .params(tenantId, scope.id())
                .query(Integer.class)
                .single();
        if (count != null && count > 0) {
            jdbc.sql("""
                UPDATE plugin_finance_consolidation_scope
                SET scope_name = ?, members = ?::jsonb, status = ?
                WHERE tenant_id = ? AND id = ?
            """)
            .params(scope.scopeName(), toJson(scope.members()), scope.status(), tenantId, scope.id())
            .update();
            return;
        }

        Integer codeCount = jdbc.sql("SELECT count(*) FROM plugin_finance_consolidation_scope WHERE tenant_id = ? AND scope_code = ?")
                .params(tenantId, scope.scopeCode())
                .query(Integer.class)
                .single();
        String effectiveCode = scope.scopeCode();
        if (codeCount != null && codeCount > 0) {
            effectiveCode = scope.scopeCode() + "_" + UUID.randomUUID().toString().substring(0, 8);
        }

        UUID parentId = scope.parentEntityId() != null ? scope.parentEntityId() : UUID.randomUUID();
        Actor safeCreator = creator != null ? creator : new Actor(UUID.randomUUID(), "system", "SYSTEM");

        jdbc.sql("""
            INSERT INTO plugin_finance_consolidation_scope (
                tenant_id, id, scope_code, parent_entity_id, scope_name, members, version, status, created_by
            ) VALUES (
                ?, ?, ?, ?, ?, ?::jsonb, 1, ?, ?::jsonb
            ) ON CONFLICT (tenant_id, id) DO NOTHING
        """)
        .params(
                tenantId, scope.id(), effectiveCode, parentId,
                scope.scopeName() != null ? scope.scopeName() : "Consolidation Scope",
                toJson(scope.members() != null ? scope.members() : List.of()),
                scope.status() != null ? scope.status() : "ACTIVE",
                toJson(safeCreator)
        ).update();
    }

    public void saveConsolidationWorksheet(UUID tenantId, ConsolidationWorksheet ws) {
        jdbc.sql("""
            INSERT INTO plugin_finance_consolidation_worksheet (
                tenant_id, scope_id, id, period_start, period_end,
                is_management_rollup, rollup_data, elimination_entries,
                consolidated_sheets, minority_interests, status, preparer, approver
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?::jsonb, ?::jsonb,
                ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb
            ) ON CONFLICT (tenant_id, id) DO UPDATE SET
                status = EXCLUDED.status,
                approver = EXCLUDED.approver
        """)
        .params(
                tenantId, ws.scopeId(), ws.id(), Date.valueOf(ws.periodStart()), Date.valueOf(ws.periodEnd()),
                ws.isManagementRollup(), toJson(ws.rollupData()), toJson(ws.eliminations()),
                toJson(ws.consolidatedSheets()), toJson(ws.minorityInterests()), ws.status(),
                toJson(ws.preparer()), ws.approver() == null ? null : toJson(ws.approver())
        ).update();
    }
}
