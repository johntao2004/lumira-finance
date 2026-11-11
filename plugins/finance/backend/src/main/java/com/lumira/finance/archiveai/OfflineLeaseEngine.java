package com.lumira.finance.archiveai;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Offline Capture Lease & Client-Ciphertext Draft Envelope Storage (PLT01).
 * Invariants: Max 8 hours (28,800s), non-self-renewable, immediate revocation on reconnect, WEB_ONLY_CLIENT.
 */
public final class OfflineLeaseEngine {

    public static final int MAX_LEASE_SECONDS = 28800; // 8 hours
    public static final String CLIENT_TYPE_WEB_ONLY = "WEB_ONLY_CLIENT";

    public record OfflineLease(
            UUID tenantId,
            UUID leaseId,
            UUID actorId,
            String deviceFingerprint,
            String clientType, // WEB_ONLY_CLIENT
            Instant issuedAt,
            Instant expiresAt,
            int maxSeconds,
            boolean renewable, // strictly false
            String status, // ACTIVE, EXPIRED, REVOKED
            Instant revokedAt,
            String revocationReason
    ) {}

    public record DraftEnvelope(
            UUID tenantId,
            UUID envelopeId,
            UUID leaseId,
            String clientCiphertext,
            String iv,
            String authTag,
            String clientSignature,
            String payloadSha256,
            String syncStatus, // STORED, DECRYPTED_FOR_REVIEW, REJECTED_LEASE_REVOKED, MERGED_INTO_DRAFT
            Instant syncedAt
    ) {}

    public static OfflineLease issueLease(
            UUID tenantId,
            UUID actorId,
            String deviceFingerprint,
            int requestedSeconds
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(deviceFingerprint, "deviceFingerprint must not be null");

        if (requestedSeconds <= 0 || requestedSeconds > MAX_LEASE_SECONDS) {
            throw new IllegalArgumentException("LEASE_DURATION_EXCEEDED: Requested lease seconds " + requestedSeconds
                    + " exceeds maximum allowed 8 hours (" + MAX_LEASE_SECONDS + " seconds).");
        }

        Instant now = Instant.now();
        Instant expires = now.plus(requestedSeconds, ChronoUnit.SECONDS);

        return new OfflineLease(
                tenantId,
                UUID.randomUUID(),
                actorId,
                deviceFingerprint,
                CLIENT_TYPE_WEB_ONLY,
                now,
                expires,
                requestedSeconds,
                false, // non-renewable
                "ACTIVE",
                null,
                null
        );
    }

    public static void validateReconnection(OfflineLease lease) {
        Objects.requireNonNull(lease, "lease must not be null");

        if ("REVOKED".equalsIgnoreCase(lease.status())) {
            throw new IllegalStateException("LEASE_REVOKED: Offline lease " + lease.leaseId()
                    + " was revoked. Reason: " + lease.revocationReason());
        }
        if ("EXPIRED".equalsIgnoreCase(lease.status()) || Instant.now().isAfter(lease.expiresAt())) {
            throw new IllegalStateException("LEASE_EXPIRED: Offline lease " + lease.leaseId()
                    + " expired on " + lease.expiresAt() + ". Client must re-authenticate online.");
        }
    }

    public static DraftEnvelope storeEnvelope(
            OfflineLease lease,
            String clientCiphertext,
            String iv,
            String authTag,
            String clientSignature,
            String payloadSha256
    ) {
        validateReconnection(lease);
        Objects.requireNonNull(clientCiphertext, "clientCiphertext must not be null");
        Objects.requireNonNull(iv, "iv must not be null");
        Objects.requireNonNull(authTag, "authTag must not be null");
        Objects.requireNonNull(clientSignature, "clientSignature must not be null");
        Objects.requireNonNull(payloadSha256, "payloadSha256 must not be null");

        return new DraftEnvelope(
                lease.tenantId(),
                UUID.randomUUID(),
                lease.leaseId(),
                clientCiphertext,
                iv,
                authTag,
                clientSignature,
                payloadSha256,
                "STORED",
                Instant.now()
        );
    }

    public static OfflineLease revokeLease(OfflineLease lease, String reason) {
        Objects.requireNonNull(lease, "lease must not be null");
        return new OfflineLease(
                lease.tenantId(),
                lease.leaseId(),
                lease.actorId(),
                lease.deviceFingerprint(),
                lease.clientType(),
                lease.issuedAt(),
                lease.expiresAt(),
                lease.maxSeconds(),
                false,
                "REVOKED",
                Instant.now(),
                reason != null ? reason : "Administrative security revocation"
        );
    }
}
