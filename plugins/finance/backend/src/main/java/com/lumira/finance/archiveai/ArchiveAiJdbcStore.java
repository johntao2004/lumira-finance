package com.lumira.finance.archiveai;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * JDBC persistence store for Archive Governance, Import Batch, ERP Events,
 * Recurring Schedules, AI Suggestions/ActionPlans, and Offline Capture Leases.
 */
@Repository
public final class ArchiveAiJdbcStore {

    @FunctionalInterface
    public interface SqlFunction<T, R> {
        R apply(T t) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer<T> {
        void accept(T t) throws SQLException;
    }

    private final Connection fixedConnection;
    private final DataSource dataSource;

    public ArchiveAiJdbcStore(Connection connection) {
        this.fixedConnection = Objects.requireNonNull(connection, "connection must not be null");
        this.dataSource = null;
    }

    @Autowired
    public ArchiveAiJdbcStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.fixedConnection = null;
    }

    private <T> T withConnection(SqlFunction<Connection, T> function) throws SQLException {
        if (fixedConnection != null) {
            return function.apply(fixedConnection);
        }
        Connection c = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try {
            return function.apply(c);
        } finally {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(c, dataSource);
            }
        }
    }

    private void withConnectionVoid(SqlConsumer<Connection> consumer) throws SQLException {
        withConnection(c -> {
            consumer.accept(c);
            return null;
        });
    }

    // --- 1. Archive Governance ---
    public void insertRetentionRule(UUID tenantId, String category, int statutoryYears, String description) throws SQLException {
        String sql = "INSERT INTO plugin_finance_archive_gov_retention_rule(tenant_id, category, statutory_years, description) VALUES(?, ?, ?, ?) "
                + "ON CONFLICT (tenant_id, category) DO UPDATE SET statutory_years=EXCLUDED.statutory_years, description=EXCLUDED.description";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setString(2, category);
                ps.setInt(3, statutoryYears);
                ps.setString(4, description);
                ps.executeUpdate();
            }
        });
    }

    public void insertArchiveRecord(ArchiveGovModel.ArchiveRecord r) throws SQLException {
        String sql = "INSERT INTO plugin_finance_archive_gov_record("
                + "tenant_id, book_id, id, category, fiscal_year_id, title, statutory_years, archived_at, "
                + "retention_until_date, custodian_person_id, legal_hold, hold_reason, status) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (tenant_id, book_id, id) DO UPDATE SET "
                + "title=EXCLUDED.title, legal_hold=EXCLUDED.legal_hold, hold_reason=EXCLUDED.hold_reason, "
                + "custodian_person_id=EXCLUDED.custodian_person_id";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, r.tenantId());
                ps.setObject(2, r.bookId());
                ps.setObject(3, r.id());
                ps.setString(4, r.category().name());
                ps.setObject(5, r.fiscalYearId());
                ps.setString(6, r.title());
                ps.setInt(7, r.statutoryYears());
                ps.setTimestamp(8, Timestamp.from(r.archivedAt()));
                ps.setDate(9, java.sql.Date.valueOf(r.retentionUntilDate()));
                ps.setObject(10, r.custodianPersonId());
                ps.setBoolean(11, r.legalHold());
                ps.setString(12, r.holdReason());
                ps.setString(13, r.status());
                ps.executeUpdate();
            }
        });
    }

    public Optional<ArchiveGovModel.ArchiveRecord> findArchiveRecord(UUID tenantId, UUID bookId, UUID id) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_archive_gov_record WHERE tenant_id=? AND book_id=? AND id=?";
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, bookId);
                ps.setObject(3, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new ArchiveGovModel.ArchiveRecord(
                                (UUID) rs.getObject("tenant_id"),
                                (UUID) rs.getObject("book_id"),
                                (UUID) rs.getObject("id"),
                                ArchiveGovModel.RetentionCategory.fromString(rs.getString("category")),
                                (UUID) rs.getObject("fiscal_year_id"),
                                rs.getString("title"),
                                rs.getInt("statutory_years"),
                                rs.getTimestamp("archived_at").toInstant(),
                                rs.getDate("retention_until_date").toLocalDate(),
                                (UUID) rs.getObject("custodian_person_id"),
                                rs.getBoolean("legal_hold"),
                                rs.getString("hold_reason"),
                                rs.getString("status")
                        ));
                    }
                }
            }
            return Optional.empty();
        });
    }

    public void insertBorrowLog(ArchiveGovModel.BorrowLog log) throws SQLException {
        String sql = "INSERT INTO plugin_finance_archive_gov_borrow_log("
                + "tenant_id, book_id, id, record_id, borrower_person_id, purpose, borrowed_at, "
                + "expected_return_date, returned_at, return_inspector_person_id, status) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, log.tenantId());
                ps.setObject(2, log.bookId());
                ps.setObject(3, log.id());
                ps.setObject(4, log.recordId());
                ps.setObject(5, log.borrowerPersonId());
                ps.setString(6, log.purpose());
                ps.setTimestamp(7, Timestamp.from(log.borrowedAt()));
                ps.setDate(8, java.sql.Date.valueOf(log.expectedReturnDate()));
                ps.setTimestamp(9, log.returnedAt() != null ? Timestamp.from(log.returnedAt()) : null);
                ps.setObject(10, log.returnInspectorPersonId());
                ps.setString(11, log.status());
                ps.executeUpdate();
            }
        });
    }

    public void insertCustodyTransfer(ArchiveGovModel.CustodyTransfer t) throws SQLException {
        String sql = "INSERT INTO plugin_finance_archive_gov_custody_transfer("
                + "tenant_id, book_id, id, batch_no, record_ids, transferor_person_id, "
                + "recipient_person_id, supervisor_person_id, transfer_date, manifest_digest, created_at) "
                + "VALUES(?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, t.tenantId());
                ps.setObject(2, t.bookId());
                ps.setObject(3, t.id());
                ps.setString(4, t.batchNo());
                ps.setString(5, "[" + String.join(",", t.recordIds().stream().map(id -> "\"" + id + "\"").toList()) + "]");
                ps.setObject(6, t.transferorPersonId());
                ps.setObject(7, t.recipientPersonId());
                ps.setObject(8, t.supervisorPersonId());
                ps.setDate(9, java.sql.Date.valueOf(t.transferDate()));
                ps.setString(10, t.manifestDigest());
                ps.setTimestamp(11, Timestamp.from(t.createdAt()));
                ps.executeUpdate();
            }
        });
    }

    public void insertDestructionManifest(ArchiveGovModel.DestructionManifest m) throws SQLException {
        String sql = "INSERT INTO plugin_finance_archive_gov_destruction_manifest("
                + "tenant_id, book_id, id, record_ids, appraiser1_person_id, appraiser2_person_id, "
                + "supervisor1_person_id, supervisor2_person_id, appraisal_date, destruction_date, "
                + "method, status, manifest_digest, created_at) "
                + "VALUES(?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, m.tenantId());
                ps.setObject(2, m.bookId());
                ps.setObject(3, m.id());
                ps.setString(4, "[" + String.join(",", m.recordIds().stream().map(id -> "\"" + id + "\"").toList()) + "]");
                ps.setObject(5, m.appraiser1PersonId());
                ps.setObject(6, m.appraiser2PersonId());
                ps.setObject(7, m.supervisor1PersonId());
                ps.setObject(8, m.supervisor2PersonId());
                ps.setDate(9, java.sql.Date.valueOf(m.appraisalDate()));
                ps.setDate(10, m.destructionDate() != null ? java.sql.Date.valueOf(m.destructionDate()) : null);
                ps.setString(11, m.method());
                ps.setString(12, m.status());
                ps.setString(13, m.manifestDigest());
                ps.setTimestamp(14, Timestamp.from(m.createdAt()));
                ps.executeUpdate();
            }
        });
    }

    public void updateArchiveRecordStatus(UUID tenantId, UUID bookId, UUID id, String status) throws SQLException {
        String sql = "UPDATE plugin_finance_archive_gov_record SET status=? WHERE tenant_id=? AND book_id=? AND id=?";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setObject(2, tenantId);
                ps.setObject(3, bookId);
                ps.setObject(4, id);
                ps.executeUpdate();
            }
        });
    }

    public Optional<ArchiveGovModel.BorrowLog> findBorrowLog(UUID tenantId, UUID bookId, UUID id) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_archive_gov_borrow_log WHERE tenant_id=? AND book_id=? AND id=?";
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, bookId);
                ps.setObject(3, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new ArchiveGovModel.BorrowLog(
                                (UUID) rs.getObject("tenant_id"),
                                (UUID) rs.getObject("book_id"),
                                (UUID) rs.getObject("id"),
                                (UUID) rs.getObject("record_id"),
                                (UUID) rs.getObject("borrower_person_id"),
                                rs.getString("purpose"),
                                rs.getTimestamp("borrowed_at").toInstant(),
                                rs.getDate("expected_return_date").toLocalDate(),
                                rs.getTimestamp("returned_at") != null ? rs.getTimestamp("returned_at").toInstant() : null,
                                (UUID) rs.getObject("return_inspector_person_id"),
                                rs.getString("status")
                        ));
                    }
                }
            }
            return Optional.empty();
        });
    }

    public void updateBorrowLogReturn(UUID tenantId, UUID bookId, UUID id, UUID returnInspectorPersonId, Instant returnedAt) throws SQLException {
        String sql = "UPDATE plugin_finance_archive_gov_borrow_log SET status='RETURNED', return_inspector_person_id=?, returned_at=? WHERE tenant_id=? AND book_id=? AND id=?";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, returnInspectorPersonId);
                ps.setTimestamp(2, Timestamp.from(returnedAt));
                ps.setObject(3, tenantId);
                ps.setObject(4, bookId);
                ps.setObject(5, id);
                ps.executeUpdate();
            }
        });
    }

    // --- 2. Import Template & Batch ---
    public void insertImportTemplate(UUID tenantId, UUID id, String templateCode, long version, String targetEntity, String mappingsJson, String delimiter, UUID createdBy) throws SQLException {
        String sql = "INSERT INTO plugin_finance_import_template(tenant_id, id, template_code, version, target_entity, field_mappings, delimiter, created_by) "
                + "VALUES(?, ?, ?, ?, ?, ?::jsonb, ?, ?)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, id);
                ps.setString(3, templateCode);
                ps.setLong(4, version);
                ps.setString(5, targetEntity);
                ps.setString(6, mappingsJson);
                ps.setString(7, delimiter);
                ps.setObject(8, createdBy);
                ps.executeUpdate();
            }
        });
    }

    public void insertImportBatch(
            UUID tenantId, UUID bookId, UUID id, UUID templateId, String fileName, String fileSha256,
            String status, int totalRows, int validRows, int errorRows, BigDecimal debit, BigDecimal credit,
            String errorsJson, UUID preparerId
    ) throws SQLException {
        String sql = "INSERT INTO plugin_finance_import_batch("
                + "tenant_id, book_id, id, template_id, file_name, file_sha256, status, total_rows, valid_rows, "
                + "error_rows, control_total_debit, control_total_credit, row_errors, preparer_person_id) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, bookId);
                ps.setObject(3, id);
                ps.setObject(4, templateId);
                ps.setString(5, fileName);
                ps.setString(6, fileSha256);
                ps.setString(7, status);
                ps.setInt(8, totalRows);
                ps.setInt(9, validRows);
                ps.setInt(10, errorRows);
                ps.setBigDecimal(11, debit);
                ps.setBigDecimal(12, credit);
                ps.setString(13, errorsJson != null ? errorsJson : "[]");
                ps.setObject(14, preparerId);
                ps.executeUpdate();
            }
        });
    }

    public void approveImportBatch(UUID tenantId, UUID bookId, UUID id, UUID approverId, String reason) throws SQLException {
        String sql = "UPDATE plugin_finance_import_batch SET status='APPROVED', approver_person_id=?, approval_reason=? WHERE tenant_id=? AND book_id=? AND id=?";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, approverId);
                ps.setString(2, reason);
                ps.setObject(3, tenantId);
                ps.setObject(4, bookId);
                ps.setObject(5, id);
                ps.executeUpdate();
            }
        });
    }

    // --- 3. ERP Connector & Event Ingestion ---
    public void insertConnector(UUID tenantId, UUID id, String name, String systemType, String scopesJson, String keyHash, String status) throws SQLException {
        String sql = "INSERT INTO plugin_finance_erp_connector(tenant_id, id, connector_name, system_type, allowed_scopes, auth_key_hash, status) "
                + "VALUES(?, ?, ?, ?, ?::jsonb, ?, ?)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, id);
                ps.setString(3, name);
                ps.setString(4, systemType);
                ps.setString(5, scopesJson);
                ps.setString(6, keyHash);
                ps.setString(7, status);
                ps.executeUpdate();
            }
        });
    }

    public long getLatestWatermark(UUID tenantId, String sourceSystem, UUID bookId) throws SQLException {
        String sql = "SELECT latest_watermark FROM plugin_finance_erp_event_watermark WHERE tenant_id=? AND source_system=? AND book_id=?";
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setString(2, sourceSystem);
                ps.setObject(3, bookId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong("latest_watermark");
                }
            }
            return 0L;
        });
    }

    public void updateWatermark(UUID tenantId, String sourceSystem, UUID bookId, long newWatermark) throws SQLException {
        String sql = "INSERT INTO plugin_finance_erp_event_watermark(tenant_id, source_system, book_id, latest_watermark, updated_at) "
                + "VALUES(?, ?, ?, ?, CURRENT_TIMESTAMP) "
                + "ON CONFLICT (tenant_id, source_system, book_id) DO UPDATE SET latest_watermark=EXCLUDED.latest_watermark, updated_at=CURRENT_TIMESTAMP";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setString(2, sourceSystem);
                ps.setObject(3, bookId);
                ps.setLong(4, newWatermark);
                ps.executeUpdate();
            }
        });
    }

    public void insertEventInbox(
            UUID tenantId, UUID eventId, String sourceSystem, String sourceEventId, String sourceDocId, int sourceDocVersion,
            String eventType, UUID legalEntityId, UUID bookId, long watermark, String payloadDigest, String payloadJson,
            UUID draftJournalId, String reversesSourceEventId, String status
    ) throws SQLException {
        String sql = "INSERT INTO plugin_finance_erp_event_inbox("
                + "tenant_id, event_id, source_system, source_event_id, source_document_id, source_document_version, "
                + "event_type, effect_type, legal_entity_id, book_id, watermark, payload_digest, payload, "
                + "draft_journal_id, reverses_source_event_id, status) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, 'CREATE_JOURNAL_DRAFT', ?, ?, ?, ?, ?::jsonb, ?, ?, ?)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, eventId);
                ps.setString(3, sourceSystem);
                ps.setString(4, sourceEventId);
                ps.setString(5, sourceDocId);
                ps.setInt(6, sourceDocVersion);
                ps.setString(7, eventType);
                ps.setObject(8, legalEntityId);
                ps.setObject(9, bookId);
                ps.setLong(10, watermark);
                ps.setString(11, payloadDigest);
                ps.setString(12, payloadJson);
                ps.setObject(13, draftJournalId);
                ps.setString(14, reversesSourceEventId);
                ps.setString(15, status);
                ps.executeUpdate();
            }
        });
    }

    public Optional<ErpEventEngine.ErpConnector> findConnector(UUID tenantId, UUID id) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_erp_connector WHERE tenant_id=? AND id=?";
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String scopesRaw = rs.getString("allowed_scopes");
                        Set<String> scopes = new HashSet<>();
                        if (scopesRaw != null) {
                            for (String s : scopesRaw.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
                                if (!s.trim().isEmpty()) scopes.add(s.trim());
                            }
                        }
                        return Optional.of(new ErpEventEngine.ErpConnector(
                                (UUID) rs.getObject("tenant_id"),
                                (UUID) rs.getObject("id"),
                                rs.getString("connector_name"),
                                rs.getString("system_type"),
                                scopes,
                                rs.getString("auth_key_hash"),
                                rs.getString("status")
                        ));
                    }
                }
            }
            return Optional.empty();
        });
    }

    public record InboxEntry(
            UUID tenantId,
            UUID eventId,
            String sourceSystem,
            String sourceEventId,
            String payloadDigest,
            UUID draftJournalId,
            String status
    ) {}

    public Optional<InboxEntry> findEventInbox(UUID tenantId, String sourceSystem, String sourceEventId) throws SQLException {
        String sql = "SELECT tenant_id, event_id, source_system, source_event_id, payload_digest, draft_journal_id, status "
                + "FROM plugin_finance_erp_event_inbox WHERE tenant_id=? AND source_system=? AND source_event_id=?";
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setString(2, sourceSystem);
                ps.setString(3, sourceEventId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new InboxEntry(
                                (UUID) rs.getObject("tenant_id"),
                                (UUID) rs.getObject("event_id"),
                                rs.getString("source_system"),
                                rs.getString("source_event_id"),
                                rs.getString("payload_digest"),
                                (UUID) rs.getObject("draft_journal_id"),
                                rs.getString("status")
                        ));
                    }
                }
            }
            return Optional.empty();
        });
    }

    public void logAntiCrmRejection(UUID tenantId, UUID id, String sourceSystem, String sample, String detectedFieldsJson, String reason) throws SQLException {
        String sql = "INSERT INTO plugin_finance_erp_anti_crm_log(tenant_id, id, source_system, rejected_payload_sample, detected_crm_fields, reason) "
                + "VALUES(?, ?, ?, ?, ?::jsonb, ?)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, id);
                ps.setString(3, sourceSystem);
                ps.setString(4, sample);
                ps.setString(5, detectedFieldsJson);
                ps.setString(6, reason);
                ps.executeUpdate();
            }
        });
    }

    // --- 4. Recurring Schedules ---
    public void insertScheduleDefinition(ScheduleRuleEngine.ScheduleDefinition s) throws SQLException {
        String sql = "INSERT INTO plugin_finance_schedule_definition("
                + "tenant_id, book_id, id, task_code, task_name, task_type, schedule_type, day_of_month, "
                + "leap_year_feb_rule, status, approved_by) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, s.tenantId());
                ps.setObject(2, s.bookId());
                ps.setObject(3, s.id());
                ps.setString(4, s.taskCode());
                ps.setString(5, s.taskName());
                ps.setString(6, s.taskType().name());
                ps.setString(7, s.scheduleType());
                if (s.dayOfMonth() != null) ps.setInt(8, s.dayOfMonth()); else ps.setNull(8, Types.INTEGER);
                ps.setString(9, s.leapYearFebRule());
                ps.setString(10, s.status());
                ps.setObject(11, s.approvedBy());
                ps.executeUpdate();
            }
        });
    }

    public void recordScheduleExecution(
            UUID tenantId, UUID bookId, UUID id, UUID scheduleId, UUID fiscalYearId, UUID periodId,
            LocalDate scheduledDate, String executionType, String status, String resultSummaryJson
    ) throws SQLException {
        String sql = "INSERT INTO plugin_finance_schedule_execution("
                + "tenant_id, book_id, id, schedule_id, fiscal_year_id, period_id, scheduled_date, "
                + "execution_type, status, result_summary) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, bookId);
                ps.setObject(3, id);
                ps.setObject(4, scheduleId);
                ps.setObject(5, fiscalYearId);
                ps.setObject(6, periodId);
                ps.setDate(7, java.sql.Date.valueOf(scheduledDate));
                ps.setString(8, executionType);
                ps.setString(9, status);
                ps.setString(10, resultSummaryJson != null ? resultSummaryJson : "{}");
                ps.executeUpdate();
            }
        });
    }

    // --- 5. AI Suggestions, Anomaly Detection & ActionPlan ---
    public void insertAiAccountSuggestion(
            UUID tenantId, UUID bookId, UUID id, String ocrText, String tokensJson,
            String suggestedAccountCode, BigDecimal confidence
    ) throws SQLException {
        String sql = "INSERT INTO plugin_finance_ai_account_suggestion("
                + "tenant_id, book_id, id, ocr_text, extracted_tokens, suggested_account_code, "
                + "confidence, external_model_provider, mandatory_human_review, review_status) "
                + "VALUES(?, ?, ?, ?, ?::jsonb, ?, ?, 'NOT_CONFIGURED', true, 'PENDING_REVIEW')";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, bookId);
                ps.setObject(3, id);
                ps.setString(4, ocrText);
                ps.setString(5, tokensJson);
                ps.setString(6, suggestedAccountCode);
                ps.setBigDecimal(7, confidence);
                ps.executeUpdate();
            }
        });
    }

    public void insertAnomalyAlert(UUID tenantId, UUID bookId, FinanceAiEngine.AnomalyAlert alert) throws SQLException {
        String sql = "INSERT INTO plugin_finance_ai_anomaly_alert("
                + "tenant_id, book_id, id, rule_code, severity, target_entity_type, target_entity_id, "
                + "details, status) VALUES(?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, bookId);
                ps.setObject(3, alert.id());
                ps.setString(4, alert.ruleCode());
                ps.setString(5, alert.severity());
                ps.setString(6, alert.targetEntityType());
                ps.setObject(7, alert.targetEntityId());
                ps.setString(8, "{\"description\": \"" + alert.description() + "\"}");
                ps.setString(9, alert.status());
                ps.executeUpdate();
            }
        });
    }

    public void insertActionPlan(FinanceAiEngine.ActionPlan plan, String actionsJson, String budgetJson) throws SQLException {
        String sql = "INSERT INTO plugin_finance_ai_action_plan("
                + "tenant_id, plan_id, schema_version, requester_id, version, content_version, status, "
                + "policy_version, currency, expected_total_amount, actions, payload_digest, dry_run_digest, "
                + "execution_budget, expires_at) "
                + "VALUES(?, ?, '1.0.0', ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, plan.tenantId());
                ps.setObject(2, plan.planId());
                ps.setObject(3, plan.requesterId());
                ps.setLong(4, plan.version());
                ps.setLong(5, plan.contentVersion());
                ps.setString(6, plan.status());
                ps.setString(7, plan.policyVersion());
                ps.setString(8, plan.currency());
                ps.setBigDecimal(9, plan.expectedTotalAmount());
                ps.setString(10, actionsJson);
                ps.setString(11, plan.payloadDigest());
                ps.setString(12, plan.dryRunDigest());
                ps.setString(13, budgetJson);
                ps.setTimestamp(14, Timestamp.from(plan.expiresAt()));
                ps.executeUpdate();
            }
        });
    }

    public void insertActionExecutionLog(
            UUID tenantId, UUID planId, UUID actionId, String command, String risk, String status, String resultJson
    ) throws SQLException {
        String sql = "INSERT INTO plugin_finance_ai_action_execution_log(tenant_id, plan_id, action_id, command, risk, status, result) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?::jsonb)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, planId);
                ps.setObject(3, actionId);
                ps.setString(4, command);
                ps.setString(5, risk);
                ps.setString(6, status);
                ps.setString(7, resultJson != null ? resultJson : "{}");
                ps.executeUpdate();
            }
        });
    }

    // --- 6. Offline Capture Lease & Encrypted Envelope ---
    public void insertOfflineLease(OfflineLeaseEngine.OfflineLease lease) throws SQLException {
        String sql = "INSERT INTO plugin_finance_offline_lease("
                + "tenant_id, lease_id, actor_id, device_fingerprint, client_type, issued_at, "
                + "expires_at, max_seconds, renewable, status) "
                + "VALUES(?, ?, ?, ?, 'WEB_ONLY_CLIENT', ?, ?, ?, false, ?)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, lease.tenantId());
                ps.setObject(2, lease.leaseId());
                ps.setObject(3, lease.actorId());
                ps.setString(4, lease.deviceFingerprint());
                ps.setTimestamp(5, Timestamp.from(lease.issuedAt()));
                ps.setTimestamp(6, Timestamp.from(lease.expiresAt()));
                ps.setInt(7, lease.maxSeconds());
                ps.setString(8, lease.status());
                ps.executeUpdate();
            }
        });
    }

    public Optional<OfflineLeaseEngine.OfflineLease> findOfflineLease(UUID tenantId, UUID leaseId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_offline_lease WHERE tenant_id=? AND lease_id=?";
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, leaseId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new OfflineLeaseEngine.OfflineLease(
                                (UUID) rs.getObject("tenant_id"),
                                (UUID) rs.getObject("lease_id"),
                                (UUID) rs.getObject("actor_id"),
                                rs.getString("device_fingerprint"),
                                rs.getString("client_type"),
                                rs.getTimestamp("issued_at").toInstant(),
                                rs.getTimestamp("expires_at").toInstant(),
                                rs.getInt("max_seconds"),
                                rs.getBoolean("renewable"),
                                rs.getString("status"),
                                rs.getTimestamp("revoked_at") != null ? rs.getTimestamp("revoked_at").toInstant() : null,
                                rs.getString("revocation_reason")
                        ));
                    }
                }
            }
            return Optional.empty();
        });
    }

    public void revokeOfflineLease(UUID tenantId, UUID leaseId, String reason) throws SQLException {
        String sql = "UPDATE plugin_finance_offline_lease SET status='REVOKED', revoked_at=CURRENT_TIMESTAMP, revocation_reason=? WHERE tenant_id=? AND lease_id=?";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, reason != null ? reason : "Administrative revocation");
                ps.setObject(2, tenantId);
                ps.setObject(3, leaseId);
                ps.executeUpdate();
            }
        });
    }

    public void insertDraftEnvelope(OfflineLeaseEngine.DraftEnvelope env) throws SQLException {
        String sql = "INSERT INTO plugin_finance_offline_draft_envelope("
                + "tenant_id, envelope_id, lease_id, client_ciphertext, iv, auth_tag, client_signature, "
                + "payload_sha256, sync_status, synced_at) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        withConnectionVoid(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, env.tenantId());
                ps.setObject(2, env.envelopeId());
                ps.setObject(3, env.leaseId());
                ps.setString(4, env.clientCiphertext());
                ps.setString(5, env.iv());
                ps.setString(6, env.authTag());
                ps.setString(7, env.clientSignature());
                ps.setString(8, env.payloadSha256());
                ps.setString(9, env.syncStatus());
                ps.setTimestamp(10, Timestamp.from(env.syncedAt()));
                ps.executeUpdate();
            }
        });
    }

    public Optional<OfflineLeaseEngine.DraftEnvelope> findDraftEnvelope(UUID tenantId, UUID envelopeId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_offline_draft_envelope WHERE tenant_id=? AND envelope_id=?";
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, tenantId);
                ps.setObject(2, envelopeId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new OfflineLeaseEngine.DraftEnvelope(
                                (UUID) rs.getObject("tenant_id"),
                                (UUID) rs.getObject("envelope_id"),
                                (UUID) rs.getObject("lease_id"),
                                rs.getString("client_ciphertext"),
                                rs.getString("iv"),
                                rs.getString("auth_tag"),
                                rs.getString("client_signature"),
                                rs.getString("payload_sha256"),
                                rs.getString("sync_status"),
                                rs.getTimestamp("synced_at").toInstant()
                        ));
                    }
                }
            }
            return Optional.empty();
        });
    }
}
