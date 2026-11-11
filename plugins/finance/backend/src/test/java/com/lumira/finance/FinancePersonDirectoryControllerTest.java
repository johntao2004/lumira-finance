package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.*;

class FinancePersonDirectoryControllerTest {
    static final EffectiveAuthenticationPolicyPort.EffectivePolicy POLICY =
            new EffectiveAuthenticationPolicyPort.EffectivePolicy(
                    UUID.randomUUID(),
                    Set.of(PrimaryMethod.PASSWORD),
                    Set.of(PrimaryMethod.PASSWORD),
                    true,
                    List.of(),
                    false,
                    UUID.randomUUID(),
                    UUID.randomUUID());

    static AuthenticationRequestContext.Snapshot context(String name, UUID account) {
        return new AuthenticationRequestContext.Snapshot(
                name,
                account,
                POLICY,
                new RequestAuthorizationContext(
                        name,
                        account,
                        "opaque",
                        Set.of(),
                        Set.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(),
                        "revision",
                        "revision",
                        false,
                        null));
    }

    static MockHttpServletRequest request(Instant at) {
        var r = new MockHttpServletRequest();
        var s = r.getSession();
        s.setAttribute(AuthenticationSession.AUTHENTICATED_AT, at);
        s.setAttribute(AuthenticationSession.PRIMARY_METHOD, "PASSWORD");
        s.setAttribute(AuthenticationSession.VERIFIED_FACTORS, List.of("TOTP"));
        s.setAttribute(AuthenticationSession.AUTHENTICATION_REVISION, POLICY.revision().toString());
        s.setAttribute(
                AuthenticationSession.PRIMARY_CREDENTIAL_REVISION,
                POLICY.primaryCredentialRevision().toString());
        s.setAttribute(
                AuthenticationSession.FACTOR_CREDENTIAL_REVISION,
                POLICY.factorCredentialRevision().toString());
        return r;
    }

    static Map<String, Object> create() {
        return FinancePersonDirectory.map(
                "schemaVersion",
                "1",
                "idempotencyKey",
                "request-key",
                "operation",
                "CREATE_PERSON_AND_BIND",
                "accountId",
                UUID.randomUUID().toString(),
                "personId",
                null,
                "displayName",
                "组织人员",
                "sourceReference",
                "ORG/attestation/1",
                "sourceSha256",
                "a".repeat(64),
                "reason",
                "人工核验",
                "noAccountTransferConfirmed",
                true,
                "allKnownAccountsDeclared",
                true);
    }

    @Test
    void strictFullBodyAndDeclarations() {
        var good = create();
        assertDoesNotThrow(() -> FinancePersonDirectory.normalized("create", good));
        for (String k : good.keySet()) {
            var bad = new LinkedHashMap<>(good);
            bad.remove(k);
            assertThrows(
                    FinanceException.class, () -> FinancePersonDirectory.normalized("create", bad));
        }
        for (String k : List.of("noAccountTransferConfirmed", "allKnownAccountsDeclared")) {
            var bad = new LinkedHashMap<>(good);
            bad.put(k, false);
            assertThrows(
                    FinanceException.class, () -> FinancePersonDirectory.normalized("create", bad));
        }
        var bad = new LinkedHashMap<>(good);
        bad.put("mfaVerified", true);
        assertThrows(
                FinanceException.class, () -> FinancePersonDirectory.normalized("create", bad));
    }

    @Test
    void recentLoginTotpAndEpochsFailClosed() {
        var now = Instant.now();
        var c = context("operator", UUID.randomUUID());
        assertDoesNotThrow(
                () -> FinancePersonDirectory.assurance("operator", c, request(now), now));
        for (var at : List.of(now.minusSeconds(301), now.plusSeconds(31)))
            assertThrows(
                    FinanceException.class,
                    () -> FinancePersonDirectory.assurance("operator", c, request(at), now));
        var r = request(now);
        r.getSession()
                .setAttribute(AuthenticationSession.VERIFIED_FACTORS, List.of("RECOVERY_CODE"));
        assertThrows(
                FinanceException.class,
                () -> FinancePersonDirectory.assurance("operator", c, r, now));
        r.getSession().setAttribute(AuthenticationSession.VERIFIED_FACTORS, List.of("TOTP", 1));
        assertThrows(
                FinanceException.class,
                () -> FinancePersonDirectory.assurance("operator", c, r, now));
        r.getSession().setAttribute(AuthenticationSession.VERIFIED_FACTORS, List.of("TOTP"));
        r.getSession()
                .setAttribute(
                        AuthenticationSession.FACTOR_CREDENTIAL_REVISION,
                        UUID.randomUUID().toString());
        assertThrows(
                FinanceException.class,
                () -> FinancePersonDirectory.assurance("operator", c, r, now));
        assertThrows(
                FinanceException.class,
                () ->
                        FinancePersonDirectory.assurance(
                                "operator", c, new MockHttpServletRequest(), now));
    }

    @Test
    void nineRoutesAndStructuredErrors() {
        long count =
                Arrays.stream(FinancePersonDirectoryController.class.getDeclaredMethods())
                        .filter(
                                m ->
                                        m.isAnnotationPresent(
                                                        org.springframework.web.bind.annotation
                                                                .GetMapping.class)
                                                || m.isAnnotationPresent(
                                                        org.springframework.web.bind.annotation
                                                                .PostMapping.class))
                        .count();
        assertEquals(9, count);
        for (String code :
                List.of(
                        "PERSON_DIRECTORY_PAGE_STALE",
                        "PERSON_PROPOSAL_EXPIRED",
                        "PERSON_SEED_PROTECTED")) {
            var r =
                    FinanceHttp.respond(
                            200,
                            () -> {
                                throw new FinanceException(code, "冲突");
                            });
            assertEquals(409, r.getStatusCode().value());
            assertEquals(code, ((Map<?, ?>) r.getBody()).get("detail"));
        }
        var r =
                FinanceHttp.respond(
                        200,
                        () -> {
                            throw new FinanceException("PERSON_AUTH_ASSURANCE_REQUIRED", "需TOTP");
                        });
        assertEquals(403, r.getStatusCode().value());
    }

    @Test
    void versionStringsNeverCoerceNumbers() {
        for (Object v : List.of(1, 1L, "01", "-1", "9223372036854775808"))
            assertThrows(RuntimeException.class, () -> FinancePersonDirectory.counter(v));
        assertEquals("0", FinancePersonDirectory.counter("0"));
    }
}
