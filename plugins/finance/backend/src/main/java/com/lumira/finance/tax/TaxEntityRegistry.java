package com.lumira.finance.tax;

import static com.lumira.finance.tax.TaxObligationModel.*;
import java.util.*;

/** Stable taxpayer identity with append-only, independently confirmed registration revisions. */
public final class TaxEntityRegistry {
    public enum Status { PENDING_VERIFICATION, ACTIVE, INACTIVE }
    public record Registration(UUID legalEntity, String canonicalTaxNumber, String name,
            String authority, String jurisdiction, String evidenceDigest) {
        public Registration {
            Objects.requireNonNull(legalEntity); text(canonicalTaxNumber); text(name);
            text(authority); text(jurisdiction); digest(evidenceDigest);
        }
    }
    public record Revision(long number, Registration registration, UUID proposedBy,
            UUID confirmedBy, Status requestedStatus) {}
    public record Entity(UUID tenant, UUID id, long version, Status status,
            Revision effective, Revision pending, List<Revision> history) {
        public Entity { history = List.copyOf(history); }
    }
    public interface Authority {
        /** Validate current tenant/legal-entity permission and canonical registration-number evidence. */
        void check(Actor actor, UUID entityId, Registration registration, String operation);
        /** Must authenticate original registration evidence, not trust a request boolean. */
        void verify(Actor actor, Registration registration);
    }
    private final Authority authority;
    private final Map<String, UUID> numbers = new HashMap<>();
    private final Map<String, Entity> entities = new HashMap<>();
    public TaxEntityRegistry(Authority authority) { this.authority = Objects.requireNonNull(authority); }
    private static String key(UUID tenant, UUID id) { return tenant + "/" + id; }
    private static void ensure(boolean value, String code) { if (!value) throw new Failure(code); }
    public synchronized Entity register(Actor actor, Registration registration) {
        authority.check(actor, null, registration, "REGISTER");
        String natural = hash(actor.tenant().toString(), registration.canonicalTaxNumber());
        UUID existing = numbers.get(natural);
        if (existing != null) {
            Entity e = read(actor, existing);
            Registration current = e.pending != null ? e.pending.registration : e.effective.registration;
            ensure(current.equals(registration), "REGISTRATION_CONFLICT"); return e;
        }
        UUID id = UUID.randomUUID();
        Revision r = new Revision(1, registration, actor.person(), null, Status.ACTIVE);
        Entity e = new Entity(actor.tenant(), id, 1, Status.PENDING_VERIFICATION, null, r, List.of());
        numbers.put(natural, id); entities.put(key(actor.tenant(), id), e); return e;
    }
    public synchronized Entity read(Actor actor, UUID id) {
        authority.check(actor, id, null, "READ");
        Entity e = entities.get(key(actor.tenant(), id)); ensure(e != null, "NOT_FOUND");
        authority.check(actor, id, e.pending != null ? e.pending.registration : e.effective.registration, "READ");
        return e;
    }
    public synchronized Entity propose(Actor actor, UUID id, long expected, Registration registration, Status status) {
        Entity e = read(actor, id); authority.check(actor, id, registration, "PROPOSE");
        ensure(e.version == expected, "VERSION_CONFLICT"); ensure(e.pending == null, "REVISION_PENDING");
        ensure(status == Status.ACTIVE || status == Status.INACTIVE, "INVALID_STATE");
        ensure(e.effective.registration.canonicalTaxNumber.equals(registration.canonicalTaxNumber), "TAX_NUMBER_RECONCILIATION_REQUIRED");
        ensure(e.effective.registration.legalEntity.equals(registration.legalEntity), "LEGAL_ENTITY_RECONCILIATION_REQUIRED");
        Revision r = new Revision(e.history.size() + 1L, registration, actor.person(), null, status);
        Entity next = new Entity(e.tenant, id, e.version + 1, e.status, e.effective, r, e.history);
        entities.put(key(e.tenant, id), next); return next;
    }
    public synchronized Entity confirm(Actor actor, UUID id, long expected) {
        Entity e = read(actor, id); ensure(e.version == expected, "VERSION_CONFLICT");
        ensure(e.pending != null, "NO_PENDING_REVISION");
        authority.check(actor, id, e.pending.registration, "CONFIRM");
        ensure(!e.pending.proposedBy.equals(actor.person()), "INDEPENDENT_VERIFIER_REQUIRED");
        authority.verify(actor, e.pending.registration);
        Revision r = new Revision(e.pending.number, e.pending.registration, e.pending.proposedBy, actor.person(), e.pending.requestedStatus);
        var history = new ArrayList<>(e.history); history.add(r);
        Entity next = new Entity(e.tenant, id, e.version + 1, r.requestedStatus, r, null, history);
        entities.put(key(e.tenant, id), next); return next;
    }
}
