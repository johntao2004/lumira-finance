package com.lumira.finance.tax;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/** Pure domain candidate. Ports are trusted server adapters, not request-supplied assertions. */
public final class TaxObligationModel {
    private TaxObligationModel() {}
    public enum Role { SELF, WITHHOLDING, COLLECTION_AGENT }
    public enum Kind { INITIAL, CORRECTION, CANCELLATION }
    public enum State { DRAFT, BLOCKED, READY_FOR_REVIEW, APPROVED, UNKNOWN, ACCEPTED, REJECTED, CANCELLED }
    public enum Action { READ, BUILD, REVIEW, APPROVE, HANDOFF, RECONCILE, CANCEL }
    public enum Outcome { ACCEPTED, REJECTED }

    public static final class Failure extends RuntimeException {
        public final String code;
        public Failure(String code) { super(code); this.code = code; }
    }
    private static void require(boolean value, String code) { if (!value) throw new Failure(code); }
    static String text(String value) {
        require(value != null && !value.isBlank() && value.equals(value.strip())
                && value.length() <= 4096 && value.codePoints().noneMatch(Character::isISOControl), "INVALID_TEXT");
        return value;
    }
    static String digest(String value) {
        require(value != null && value.matches("[0-9a-f]{64}"), "INVALID_DIGEST"); return value;
    }
    /** Length framing distinguishes separators, null, empty and Unicode; order is explicit. */
    public static String hash(String... values) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            for (String v : values) {
                if (v == null) out.writeInt(-1);
                else { byte[] b = v.getBytes(StandardCharsets.UTF_8); out.writeInt(b.length); out.write(b); }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
    public record Identity(UUID tenant, UUID entity, String authority, String filingUnit,
            Role role, LocalDate start, LocalDate end, String occurrence, String scope) {
        public Identity {
            Objects.requireNonNull(tenant); Objects.requireNonNull(entity); Objects.requireNonNull(role);
            Objects.requireNonNull(start); Objects.requireNonNull(end);
            text(authority); text(filingUnit); text(occurrence); text(scope);
            require(!start.isAfter(end), "INVALID_PERIOD");
        }
        public String key() {
            return hash("tax-obligation-v1", tenant.toString(), entity.toString(), authority,
                    filingUnit, role.name(), start.toString(), end.toString(), occurrence, scope);
        }
    }
    public record Actor(UUID tenant, String client, UUID person) {
        public Actor { Objects.requireNonNull(tenant); Objects.requireNonNull(person); text(client); }
    }
    public record Source(UUID tenant, UUID entity, UUID book, UUID year, long watermark,
            long openingVersion, Long migrationVersion, String mappingVersion, String sourceDigest) {
        public Source {
            Objects.requireNonNull(tenant); Objects.requireNonNull(entity);
            Objects.requireNonNull(book); Objects.requireNonNull(year);
            require(watermark >= 0 && openingVersion >= 0 && (migrationVersion == null || migrationVersion > 0), "INVALID_WATERMARK");
            text(mappingVersion); digest(sourceDigest);
        }
        String key() { return book + "/" + year; }
        String content() { return hash(tenant.toString(), entity.toString(), key(), "" + watermark,
                "" + openingVersion, migrationVersion == null ? null : migrationVersion.toString(), mappingVersion, sourceDigest); }
    }
    public record Content(UUID formVersion, SortedSet<UUID> rules, List<Source> sources,
            Kind kind, UUID predecessor, String reason, SortedSet<UUID> adjustments, String worksheetDigest) {
        public Content {
            Objects.requireNonNull(formVersion); Objects.requireNonNull(kind); Objects.requireNonNull(reason);
            require(reason.length() <= 1000, "INVALID_REASON");
            rules = Collections.unmodifiableSortedSet(new TreeSet<>(rules));
            adjustments = Collections.unmodifiableSortedSet(new TreeSet<>(adjustments));
            require(!rules.isEmpty() && !sources.isEmpty(), "MISSING_DEPENDENCIES");
            sources = sources.stream().sorted(Comparator.comparing(Source::key)).toList();
            require(sources.stream().map(Source::key).distinct().count() == sources.size(), "DUPLICATE_SOURCE");
            require(kind == Kind.INITIAL ? predecessor == null : predecessor != null && !reason.isBlank(), "INVALID_PREDECESSOR");
            digest(worksheetDigest);
        }
        String digestValue() {
            var parts = new ArrayList<String>();
            parts.add("tax-content-v1"); parts.add(formVersion.toString()); parts.add(kind.name());
            parts.add(predecessor == null ? null : predecessor.toString()); parts.add(reason); parts.add(worksheetDigest);
            parts.add("rules"); rules.forEach(v -> parts.add(v.toString()));
            parts.add("sources"); sources.forEach(v -> parts.add(v.content()));
            parts.add("adjustments"); adjustments.forEach(v -> parts.add(v.toString()));
            return hash(parts.toArray(String[]::new));
        }
    }
    public record Approval(UUID person, String digest, Instant expires) {}
    public record Declaration(UUID id, long revision, long version, Content content, UUID preparer,
            State state, List<String> gaps, Approval approval, UUID command, String receiptRef) {
        public Declaration { gaps = List.copyOf(gaps); }
        public String paymentState() { return "NOT_ASSESSED"; }
        public String officialCapability() { return "NOT_VERIFIED"; }
        public String dataDigest() { return content.digestValue(); }
    }
    public record View(long obligationVersion, UUID accepted, Declaration declaration) {}
    /** Raw uploaded receipt; upload itself never establishes authenticity. */
    public record Receipt(String objectRef, String bytesDigest, UUID uploader) {
        public Receipt { text(objectRef); digest(bytesDigest); Objects.requireNonNull(uploader); }
    }
    /** Must only be constructed by an authenticated verifier, after inspecting original bytes. */
    public record VerifiedReceipt(Identity identity, UUID declaration, UUID command, String dataDigest,
            String bytesDigest, String providerReference, Outcome outcome, UUID verifier, String evidenceRef) {
        public VerifiedReceipt {
            Objects.requireNonNull(identity); Objects.requireNonNull(declaration); Objects.requireNonNull(command);
            digest(dataDigest); digest(bytesDigest); text(providerReference); Objects.requireNonNull(outcome);
            Objects.requireNonNull(verifier); text(evidenceRef);
        }
    }
    public interface Ports {
        /** Must reject pending/inactive entity, unregistered filing unit or unsupported split scope on writes. */
        void authorize(Actor actor, Identity obligation, Action action, List<Source> sources);
        /** Verify frozen bytes/watermarks/mapping and completeness; return globally stable economic event keys. */
        Set<String> verifySource(Identity obligation, Source source);
        /** Validate required source-set completeness, pinned form/rules, qualification, trace and reconciliation. */
        List<String> evidenceGaps(Identity obligation, Content content);
        VerifiedReceipt verifyReceipt(Actor actor, Identity obligation, Declaration declaration, Receipt receipt);
    }

    /** Single-process reference aggregate. Production persistence requires the documented transaction adapter. */
    public static final class Ledger {
        private final Identity identity;
        private final Ports ports;
        private final Map<UUID, Declaration> declarations = new LinkedHashMap<>();
        private final Map<String, Replay> replays = new HashMap<>();
        private final Map<String, UUID> receiptOwners = new HashMap<>();
        private long version = 1;
        private UUID accepted;
        private UUID active;
        private record Replay(String digest, View result) {}
        public Ledger(Identity identity, Ports ports) {
            this.identity = Objects.requireNonNull(identity); this.ports = Objects.requireNonNull(ports);
        }
        private void auth(Actor actor, Action action, List<Source> sources) {
            require(identity.tenant.equals(actor.tenant), "SCOPE_DENIED");
            for (Source s : sources) require(s.tenant.equals(identity.tenant) && s.entity.equals(identity.entity), "SCOPE_DENIED");
            ports.authorize(actor, identity, action, sources);
        }
        private View command(Actor actor, Action action, String requestKey, String payload,
                List<Source> sources, Supplier<View> operation) {
            auth(actor, action, sources); text(requestKey);
            String key = hash(identity.tenant.toString(), actor.client, action.name(), identity.key(), requestKey);
            Replay replay = replays.get(key);
            if (replay != null) {
                require(replay.digest.equals(payload), "IDEMPOTENCY_CONFLICT");
                auth(actor, action, replay.result.declaration.content.sources);
                return replay.result;
            }
            View result = operation.get();
            replays.put(key, new Replay(payload, result));
            return result;
        }
        private Declaration find(Actor a, UUID id, Action action) {
            // Entity authorization before lookup avoids cross-tenant existence disclosure.
            auth(a, action, List.of());
            Declaration d = declarations.get(id); require(d != null, "NOT_FOUND");
            auth(a, action, d.content.sources); return d;
        }
        private void expected(long expected) { require(version == expected, "VERSION_CONFLICT"); }
        private void chain(Content c) {
            require(c.kind == Kind.INITIAL ? accepted == null : accepted != null && accepted.equals(c.predecessor), "STALE_PREDECESSOR");
        }
        private List<String> evidence(Content c) {
            Set<String> events = new HashSet<>();
            for (Source s : c.sources) {
                for (String event : ports.verifySource(identity, s)) {
                    text(event); require(events.add(event), "DUPLICATE_ECONOMIC_SOURCE");
                }
            }
            return List.copyOf(ports.evidenceGaps(identity, c));
        }
        private View save(Declaration d) {
            declarations.put(d.id, d); version++; return new View(version, accepted, d);
        }
        private Declaration transition(Declaration d, State state, List<String> gaps, Approval approval, UUID command, String receipt) {
            return new Declaration(d.id, d.revision, d.version + 1, d.content, d.preparer, state, gaps, approval, command, receipt);
        }
        public synchronized View read(Actor actor, UUID id) {
            return new View(version, accepted, find(actor, id, Action.READ));
        }
        public synchronized View build(Actor actor, String key, long expected, Content content) {
            return command(actor, Action.BUILD, key, hash("" + expected, content.digestValue()), content.sources, () -> {
                expected(expected); require(active == null, "EXTERNAL_OUTCOME_UNKNOWN"); chain(content);
                require(declarations.values().stream().noneMatch(d -> d.state != State.ACCEPTED && d.state != State.REJECTED && d.state != State.CANCELLED), "DRAFT_ALREADY_EXISTS");
                List<String> gaps = evidence(content);
                var d = new Declaration(UUID.randomUUID(), declarations.size() + 1L, 1, content, actor.person,
                        gaps.isEmpty() ? State.DRAFT : State.BLOCKED, gaps, null, null, null);
                return save(d);
            });
        }
        public synchronized View review(Actor actor, String key, long expected, UUID id) {
            Declaration d = find(actor, id, Action.REVIEW);
            return command(actor, Action.REVIEW, key, hash("" + expected, id.toString()), d.content.sources, () -> {
                expected(expected); require(d.state == State.DRAFT || d.state == State.BLOCKED, "INVALID_STATE");
                List<String> gaps = evidence(d.content);
                return save(transition(d, gaps.isEmpty() ? State.READY_FOR_REVIEW : State.BLOCKED, gaps, null, null, null));
            });
        }
        public synchronized View approve(Actor actor, String key, long expected, UUID id, Instant expires, Instant now) {
            Declaration d = find(actor, id, Action.APPROVE); Objects.requireNonNull(expires); Objects.requireNonNull(now);
            return command(actor, Action.APPROVE, key, hash("" + expected, id.toString(), expires.toString()), d.content.sources, () -> {
                expected(expected); require(d.state == State.READY_FOR_REVIEW, "INVALID_STATE");
                require(!d.preparer.equals(actor.person), "INDEPENDENT_APPROVER_REQUIRED");
                require(expires.isAfter(now), "APPROVAL_EXPIRED"); require(evidence(d.content).isEmpty(), "EVIDENCE_GAPS");
                return save(transition(d, State.APPROVED, List.of(), new Approval(actor.person, d.dataDigest(), expires), null, null));
            });
        }
        public synchronized View handoff(Actor actor, String key, long expected, UUID id, Instant now) {
            Declaration d = find(actor, id, Action.HANDOFF); Objects.requireNonNull(now);
            return command(actor, Action.HANDOFF, key, hash("" + expected, id.toString()), d.content.sources, () -> {
                expected(expected); require(active == null, "EXTERNAL_OUTCOME_UNKNOWN");
                require(d.state == State.APPROVED, "INVALID_STATE"); chain(d.content);
                require(d.approval != null && d.approval.expires.isAfter(now) && d.approval.digest.equals(d.dataDigest()), "APPROVAL_STALE");
                require(evidence(d.content).isEmpty(), "EVIDENCE_GAPS");
                Declaration next = transition(d, State.UNKNOWN, List.of(), d.approval, UUID.randomUUID(), null);
                active = id; return save(next);
            });
        }
        public synchronized View reconcile(Actor actor, String key, long expected, UUID id, Receipt receipt) {
            Declaration d = find(actor, id, Action.RECONCILE);
            return command(actor, Action.RECONCILE, key, hash("" + expected, id.toString(), receipt.objectRef, receipt.bytesDigest, receipt.uploader.toString()), d.content.sources, () -> {
                expected(expected); require(d.state == State.UNKNOWN && id.equals(active), "INVALID_STATE");
                VerifiedReceipt r = ports.verifyReceipt(actor, identity, d, receipt);
                require(r != null && r.identity.equals(identity) && r.declaration.equals(id) && r.command.equals(d.command)
                        && r.dataDigest.equals(d.dataDigest()) && r.bytesDigest.equals(receipt.bytesDigest), "RECEIPT_MISMATCH");
                require(r.verifier.equals(actor.person) && !r.verifier.equals(receipt.uploader) && !r.verifier.equals(d.preparer), "INDEPENDENT_VERIFIER_REQUIRED");
                String receiptKey = hash(identity.tenant.toString(), identity.authority, r.providerReference);
                require(!receiptOwners.containsKey(receiptKey) || receiptOwners.get(receiptKey).equals(id), "RECEIPT_ALREADY_USED");
                chain(d.content);
                Declaration next = transition(d, r.outcome == Outcome.ACCEPTED ? State.ACCEPTED : State.REJECTED,
                        List.of(), d.approval, d.command, receipt.objectRef);
                receiptOwners.put(receiptKey, id);
                if (r.outcome == Outcome.ACCEPTED) accepted = id;
                active = null; return save(next);
            });
        }
        /** A timeout can never undo terminal evidence; active UNKNOWN remains locked. */
        public synchronized View timeout(Actor actor, UUID id, UUID command) {
            Declaration d = find(actor, id, Action.RECONCILE);
            require(d.command != null && d.command.equals(command), "COMMAND_MISMATCH");
            require(d.state == State.UNKNOWN || d.state == State.ACCEPTED || d.state == State.REJECTED, "INVALID_STATE");
            return new View(version, accepted, d);
        }
        public synchronized View cancel(Actor actor, String key, long expected, UUID id) {
            Declaration d = find(actor, id, Action.CANCEL);
            return command(actor, Action.CANCEL, key, hash("" + expected, id.toString()), d.content.sources, () -> {
                expected(expected);
                require(d.command == null && (d.state == State.DRAFT || d.state == State.BLOCKED
                        || d.state == State.READY_FOR_REVIEW || d.state == State.APPROVED), "CANNOT_CANCEL");
                return save(transition(d, State.CANCELLED, d.gaps, null, null, null));
            });
        }
    }
}
