package com.lumira.finance.archiveai;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Scoped External ERP Connector, AccountingEvent Ingestion, Idempotency & Anti-CRM Guard (INT02 & PLT03).
 */
public final class ErpEventEngine {

    public static final Set<String> CRM_PROHIBITED_FIELDS = Set.of(
            "lead_id",
            "leadId",
            "opportunity_id",
            "opportunityId",
            "pipeline_stage",
            "pipelineStage",
            "crm_contact",
            "crmContact",
            "sales_funnel",
            "salesFunnel",
            "deal_stage",
            "dealStage",
            "campaign_id",
            "campaignId"
    );

    private static final Set<String> NORMALIZED_CRM_KEYS = Set.of(
            "leadid",
            "opportunityid",
            "pipelinestage",
            "crmcontact",
            "salesfunnel",
            "dealstage",
            "campaignid"
    );

    private static final java.util.regex.Pattern JSON_KEY_PATTERN =
            java.util.regex.Pattern.compile("[\"']([a-zA-Z0-9_\\-]+)[\"']\\s*:");

    public static void runAntiCrmGuard(String rawPayloadJson) {
        if (rawPayloadJson == null || rawPayloadJson.isBlank()) return;

        // 1. Check extracted JSON keys with normalization (stripping underscores/hyphens and lowercasing)
        java.util.regex.Matcher matcher = JSON_KEY_PATTERN.matcher(rawPayloadJson);
        while (matcher.find()) {
            String rawKey = matcher.group(1);
            String normalizedKey = rawKey.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
            if (NORMALIZED_CRM_KEYS.contains(normalizedKey)) {
                throw new IllegalStateException("CRM_PAYLOAD_REJECTED: Anti-CRM guard detected non-accounting field '"
                        + rawKey + "'. Ingestion of CRM lead/deal pipelines into accounting is strictly forbidden.");
            }
        }

        // 2. Fallback substring search across prohibited field forms
        String lower = rawPayloadJson.toLowerCase(Locale.ROOT);
        for (String crmField : CRM_PROHIBITED_FIELDS) {
            String lowerField = crmField.toLowerCase(Locale.ROOT);
            if (lower.contains("\"" + lowerField + "\"") || lower.contains("'" + lowerField + "'")) {
                throw new IllegalStateException("CRM_PAYLOAD_REJECTED: Anti-CRM guard detected non-accounting field '"
                        + crmField + "'. Ingestion of CRM lead/deal pipelines into accounting is strictly forbidden.");
            }
        }
    }

    public static void validateConnectorAuth(ErpConnector connector, String providedApiKey, String requiredScope) {
        Objects.requireNonNull(connector, "connector must not be null");
        if (!"ACTIVE".equals(connector.status())) {
            throw new IllegalStateException("CONNECTOR_INACTIVE: Connector " + connector.id() + " is " + connector.status());
        }
        String keyHash = sha256Hex(providedApiKey != null ? providedApiKey : "");
        if (!keyHash.equalsIgnoreCase(connector.authKeyHash())) {
            throw new IllegalStateException("UNAUTHENTICATED: Invalid API key for connector " + connector.id());
        }
        if (!connector.allowedScopes().contains(requiredScope)) {
            throw new IllegalStateException("SCOPE_DENIED: Connector lacks required scope " + requiredScope);
        }
    }

    public static IngestionResult processAccountingEvent(
            AccountingEvent event,
            long latestWatermark,
            long incomingWatermark,
            Map<String, String> existingEventDigests, // sourceEventId -> payloadDigest
            Map<String, UUID> existingDraftMap // sourceEventId -> draftJournalId
    ) {
        Objects.requireNonNull(event, "event must not be null");
        if (!"1.0.0".equals(event.schemaVersion())) {
            throw new IllegalArgumentException("Unsupported schema_version: " + event.schemaVersion());
        }
        if (!"CREATE_JOURNAL_DRAFT".equals(event.effectType())) {
            throw new IllegalArgumentException("Unsupported effect_type: " + event.effectType());
        }

        // Idempotency check
        if (existingEventDigests.containsKey(event.sourceEventId())) {
            String storedDigest = existingEventDigests.get(event.sourceEventId());
            if (storedDigest.equalsIgnoreCase(event.payloadDigest())) {
                UUID existingDraft = existingDraftMap.get(event.sourceEventId());
                return new IngestionResult(event.eventId(), existingDraft, "DUPLICATE_IGNORED",
                        latestWatermark, "Replayed identical source event; existing draft retained.");
            } else {
                throw new IllegalStateException("EFFECT_PAYLOAD_CONFLICT: Source event " + event.sourceEventId()
                        + " already ingested with a different payload digest.");
            }
        }

        // Watermark check
        if (incomingWatermark <= latestWatermark) {
            return new IngestionResult(event.eventId(), null, "WATERMARK_OUT_OF_ORDER",
                    latestWatermark, "Incoming watermark " + incomingWatermark + " <= latest watermark " + latestWatermark);
        }

        // Event processing
        UUID generatedDraftId = UUID.randomUUID();
        if ("REVERSAL".equalsIgnoreCase(event.eventType())) {
            if (event.reversesSourceEventId() == null || event.reversesSourceEventId().isBlank()) {
                throw new IllegalArgumentException("reverses_source_event_id is required for REVERSAL event");
            }
            return new IngestionResult(event.eventId(), generatedDraftId, "REVERSAL_CREATED",
                    incomingWatermark, "Reversal draft created referencing predecessor " + event.reversesSourceEventId());
        }

        return new IngestionResult(event.eventId(), generatedDraftId, "DRAFT_CREATED",
                incomingWatermark, "Journal draft created in DRAFT state.");
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public record ErpConnector(
            UUID tenantId,
            UUID id,
            String connectorCode,
            String connectorType,
            Set<String> allowedScopes,
            String authKeyHash,
            String status
    ) {
        public String connectorName() {
            return connectorCode;
        }
    }

    public record EventLine(
            String lineId,
            String lineType,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            String taxRateCode
    ) {}

    public record AccountingEvent(
            String schemaVersion,
            UUID eventId,
            UUID tenantId,
            String sourceSystem,
            String sourceEventId,
            String sourceDocumentId,
            int sourceDocumentVersion,
            String eventType,
            String effectType,
            UUID legalEntityId,
            UUID bookId,
            LocalDate eventDate,
            LocalDate accountingDate,
            Instant occurredAt,
            String currency,
            List<EventLine> lines,
            String mappingVersion,
            List<String> tagIds,
            String reversesSourceEventId,
            String summary,
            String payloadDigest
    ) {
        public String businessDocRef() {
            return sourceDocumentId;
        }
    }

    public record IngestionResult(
            UUID eventId,
            UUID draftJournalId,
            String status,
            long latestWatermark,
            String message
    ) {}
}
