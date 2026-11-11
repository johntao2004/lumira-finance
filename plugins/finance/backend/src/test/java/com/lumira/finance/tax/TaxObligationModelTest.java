package com.lumira.finance.tax;

import static com.lumira.finance.tax.TaxObligationModel.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Dependency-free executable tests. Run main after root allocates JDK 25 compilation resources. */
public final class TaxObligationModelTest {
    static final UUID TENANT = UUID.randomUUID(), ENTITY = UUID.randomUUID();
    static final UUID BOOK1 = UUID.randomUUID(), BOOK2 = UUID.randomUUID(), YEAR = UUID.randomUUID();
    static final Actor PREPARER = new Actor(TENANT, "web", UUID.randomUUID());
    static final Actor APPROVER = new Actor(TENANT, "web", UUID.randomUUID());
    static final Actor VERIFIER = new Actor(TENANT, "web", UUID.randomUUID());
    static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
    static final Identity IDENTITY = new Identity(TENANT, ENTITY, "authority", "VAT_SELF_PACKAGE",
            Role.SELF, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "PERIODIC", "AGGREGATED");
    static final class FakePorts implements Ports {
        boolean revoked, duplicate, gap, badReceipt, unverified;
        Outcome outcome = Outcome.ACCEPTED;
        String provider = "official-001";
        public void authorize(Actor actor, Identity id, Action action, List<Source> sources) {
            if (revoked && sources.stream().anyMatch(s -> s.book().equals(BOOK2))) throw new Failure("SCOPE_DENIED");
        }
        public Set<String> verifySource(Identity id, Source source) {
            return Set.of(duplicate ? "same-economic-event" : "event/" + source.book());
        }
        public List<String> evidenceGaps(Identity id, Content content) {
            return gap ? List.of("OFFICIAL_FORM_EVIDENCE_UNVERIFIED") : List.of();
        }
        public VerifiedReceipt verifyReceipt(Actor actor, Identity id, Declaration d, Receipt r) {
            if (unverified) return null;
            return new VerifiedReceipt(id, d.id(), badReceipt ? UUID.randomUUID() : d.command(),
                    d.dataDigest(), r.bytesDigest(), provider, outcome, actor.person(), "verified-original-evidence");
        }
    }
    static Source source(UUID book) {
        return new Source(TENANT, ENTITY, book, YEAR, 15, 2, null, "mapping-1", hash(book.toString()));
    }
    static Content content(Kind kind, UUID previous) {
        return new Content(UUID.nameUUIDFromBytes("form-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new TreeSet<>(Set.of(UUID.nameUUIDFromBytes("rule-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8)))),
                List.of(source(BOOK1), source(BOOK2)), kind, previous,
                kind == Kind.INITIAL ? "" : "verified correction reason", new TreeSet<>(), hash("worksheet"));
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void fails(String code, Runnable r) {
        try { r.run(); throw new AssertionError("expected " + code); }
        catch (Failure ex) { if (!code.equals(ex.code)) throw new AssertionError(ex); }
    }
    static View approved(Ledger ledger, long version, Content content, String prefix) {
        View b = ledger.build(PREPARER, prefix + "build", version, content);
        View r = ledger.review(PREPARER, prefix + "review", b.obligationVersion(), b.declaration().id());
        return ledger.approve(APPROVER, prefix + "approve", r.obligationVersion(), r.declaration().id(), NOW.plusSeconds(300), NOW);
    }
    static Receipt receipt() { return new Receipt("original/receipt", hash("original receipt bytes"), PREPARER.person()); }
    static void identityAndSourceTests() {
        check(IDENTITY.key().equals(new Identity(TENANT, ENTITY, "authority", "VAT_SELF_PACKAGE", Role.SELF,
                IDENTITY.start(), IDENTITY.end(), "PERIODIC", "AGGREGATED").key()));
        check(!hash("a|b", "c").equals(hash("a", "b|c")));
        check(!hash((String) null).equals(hash("")));
        check(!IDENTITY.key().equals(new Identity(TENANT, ENTITY, "authority", "VAT_SELF_PACKAGE", Role.WITHHOLDING,
                IDENTITY.start(), IDENTITY.end(), "PERIODIC", "AGGREGATED").key()));
        var p = new FakePorts(); var ledger = new Ledger(IDENTITY, p);
        p.duplicate = true;
        fails("DUPLICATE_ECONOMIC_SOURCE", () -> ledger.build(PREPARER, "same", 1, content(Kind.INITIAL, null)));
        p.duplicate = false; // Failed command did not consume version/idempotency key.
        View b = ledger.build(PREPARER, "same", 1, content(Kind.INITIAL, null));
        check(b.obligationVersion() == 2);
        p.revoked = true;
        fails("SCOPE_DENIED", () -> ledger.build(PREPARER, "same", 1, content(Kind.INITIAL, null)));
        fails("SCOPE_DENIED", () -> ledger.read(PREPARER, b.declaration().id()));
        fails("SCOPE_DENIED", () -> ledger.read(new Actor(UUID.randomUUID(), "web", PREPARER.person()), UUID.randomUUID()));
    }
    static void evidenceAndApprovalTests() {
        var p = new FakePorts(); var ledger = new Ledger(IDENTITY, p); p.gap = true;
        View b = ledger.build(PREPARER, "build", 1, content(Kind.INITIAL, null));
        check(b.declaration().state() == State.BLOCKED);
        fails("INVALID_STATE", () -> ledger.approve(APPROVER, "approve", 2, b.declaration().id(), NOW.plusSeconds(30), NOW));
        p.gap = false;
        View r = ledger.review(PREPARER, "review", 2, b.declaration().id());
        fails("INDEPENDENT_APPROVER_REQUIRED", () -> ledger.approve(PREPARER, "self", 3, r.declaration().id(), NOW.plusSeconds(30), NOW));
        View a = ledger.approve(APPROVER, "approve", 3, r.declaration().id(), NOW.plusSeconds(30), NOW);
        fails("APPROVAL_STALE", () -> ledger.handoff(PREPARER, "handoff", 4, a.declaration().id(), NOW.plusSeconds(31)));
        p.gap = true;
        fails("EVIDENCE_GAPS", () -> ledger.handoff(PREPARER, "handoff", 4, a.declaration().id(), NOW));
    }
    static void receiptAndChainTests() {
        var p = new FakePorts(); var ledger = new Ledger(IDENTITY, p);
        View a = approved(ledger, 1, content(Kind.INITIAL, null), "a"); UUID id = a.declaration().id();
        View h = ledger.handoff(PREPARER, "handoff", a.obligationVersion(), id, NOW);
        check(h.declaration().state() == State.UNKNOWN && h.declaration().receiptRef() == null);
        check(ledger.handoff(PREPARER, "handoff", a.obligationVersion(), id, NOW).equals(h));
        fails("IDEMPOTENCY_CONFLICT", () -> ledger.handoff(PREPARER, "handoff", h.obligationVersion(), id, NOW));
        fails("EXTERNAL_OUTCOME_UNKNOWN", () -> ledger.handoff(PREPARER, "different-key", h.obligationVersion(), id, NOW));
        fails("CANNOT_CANCEL", () -> ledger.cancel(PREPARER, "cancel", h.obligationVersion(), id));
        fails("EXTERNAL_OUTCOME_UNKNOWN", () -> ledger.build(PREPARER, "second", h.obligationVersion(), content(Kind.INITIAL, null)));
        p.unverified = true;
        fails("RECEIPT_MISMATCH", () -> ledger.reconcile(VERIFIER, "receipt", h.obligationVersion(), id, receipt()));
        p.unverified = false; p.badReceipt = true;
        fails("RECEIPT_MISMATCH", () -> ledger.reconcile(VERIFIER, "receipt", h.obligationVersion(), id, receipt()));
        p.badReceipt = false;
        fails("INDEPENDENT_VERIFIER_REQUIRED", () -> ledger.reconcile(PREPARER, "self-receipt", h.obligationVersion(), id, receipt()));
        View accepted = ledger.reconcile(VERIFIER, "receipt", h.obligationVersion(), id, receipt());
        check(accepted.accepted().equals(id));
        check(accepted.declaration().paymentState().equals("NOT_ASSESSED"));
        check(accepted.declaration().officialCapability().equals("NOT_VERIFIED"));
        check(ledger.timeout(VERIFIER, id, h.declaration().command()).declaration().state() == State.ACCEPTED);
        fails("STALE_PREDECESSOR", () -> ledger.build(PREPARER, "new-initial", accepted.obligationVersion(), content(Kind.INITIAL, null)));
        fails("STALE_PREDECESSOR", () -> ledger.build(PREPARER, "bad-chain", accepted.obligationVersion(), content(Kind.CORRECTION, UUID.randomUUID())));
        View c = approved(ledger, accepted.obligationVersion(), content(Kind.CORRECTION, id), "c");
        View ch = ledger.handoff(PREPARER, "correction-handoff", c.obligationVersion(), c.declaration().id(), NOW);
        fails("RECEIPT_ALREADY_USED", () -> ledger.reconcile(VERIFIER, "duplicate-receipt", ch.obligationVersion(), ch.declaration().id(), receipt()));
        p.provider = "official-002";
        View ca = ledger.reconcile(VERIFIER, "correction-receipt", ch.obligationVersion(), ch.declaration().id(), receipt());
        check(ca.accepted().equals(c.declaration().id()));
        check(ledger.read(VERIFIER, id).declaration().state() == State.ACCEPTED);
    }
    static void concurrentHandoffTest() throws Exception {
        var p = new FakePorts(); var ledger = new Ledger(IDENTITY, p);
        View a = approved(ledger, 1, content(Kind.INITIAL, null), "x");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> first = () -> { start.await(); try {
                return ledger.handoff(PREPARER, Thread.currentThread().getName(), a.obligationVersion(), a.declaration().id(), NOW).declaration().command().toString();
            } catch (Failure ex) { return ex.code; } };
            Future<String> f1 = pool.submit(first), f2 = pool.submit(first); start.countDown();
            List<String> results = List.of(f1.get(), f2.get());
            check(results.stream().filter("VERSION_CONFLICT"::equals).count() == 1);
            check(ledger.read(PREPARER, a.declaration().id()).declaration().state() == State.UNKNOWN);
        }
    }
    static void registrationTests() {
        var registry = new TaxEntityRegistry(new TaxEntityRegistry.Authority() {
            public void check(Actor actor, UUID entity, TaxEntityRegistry.Registration registration, String op) {}
            public void verify(Actor actor, TaxEntityRegistry.Registration registration) {}
        });
        var registration = new TaxEntityRegistry.Registration(UUID.randomUUID(), "REGISTERED-NUMBER", "Entity",
                "authority", "jurisdiction", hash("registration bytes"));
        var first = registry.register(PREPARER, registration);
        check(first.status() == TaxEntityRegistry.Status.PENDING_VERIFICATION);
        check(registry.register(PREPARER, registration).id().equals(first.id()));
        fails("INDEPENDENT_VERIFIER_REQUIRED", () -> registry.confirm(PREPARER, first.id(), 1));
        var confirmed = registry.confirm(VERIFIER, first.id(), 1);
        check(confirmed.status() == TaxEntityRegistry.Status.ACTIVE);
        var pending = registry.propose(PREPARER, first.id(), confirmed.version(), registration, TaxEntityRegistry.Status.INACTIVE);
        check(pending.status() == TaxEntityRegistry.Status.ACTIVE);
        var inactive = registry.confirm(VERIFIER, first.id(), pending.version());
        check(inactive.id().equals(first.id()) && inactive.history().size() == 2);
        check(inactive.status() == TaxEntityRegistry.Status.INACTIVE);
    }
    public static void main(String[] args) throws Exception {
        identityAndSourceTests(); evidenceAndApprovalTests(); receiptAndChainTests(); concurrentHandoffTest(); registrationTests();
        System.out.println("TaxObligationModelTest: 5 scenario groups passed");
    }
}
