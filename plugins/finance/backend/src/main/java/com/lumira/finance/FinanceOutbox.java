package com.lumira.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Transactional local projection queue. No network calls or financial commands are executed. */
@Service
public class FinanceOutbox {
    static final String VIEW = "finance-journal-view-v1";
    private final FinanceStore db;
    private final FinanceService finance;

    public FinanceOutbox(FinanceStore db, FinanceService finance) {
        this.db = db;
        this.finance = finance;
    }

    static Map<String, Object> snapshot(
            FinanceStore db, Map<String, Object> b, UUID year, UUID journal) {
        return db.one(
                "SELECT j.id,j.id AS \"journalId\",j.status,j.date,j.summary,j.number,j.version AS"
                    + " \"sourceVersion\",p.posting_seq AS \"postingSeq\" FROM"
                    + " plugin_finance_journal j LEFT JOIN plugin_finance_posting p ON"
                    + " p.tenant_id=j.tenant_id AND p.book_id=j.book_id AND p.year_id=j.year_id AND"
                    + " p.journal_id=j.id WHERE j.tenant_id=? AND j.book_id=? AND j.year_id=? AND"
                    + " j.id=?",
                b.get("tenant_id"),
                b.get("id"),
                year,
                journal);
    }

    static void enqueue(
            FinanceStore db,
            FinanceService finance,
            Map<String, Object> b,
            UUID year,
            String type,
            UUID journal,
            UUID actor) {
        var payload = snapshot(db, b, year, journal);
        String encoded = finance.encode(finance.canonical(payload));
        db.update(
                "INSERT INTO"
                    + " plugin_finance_outbox(tenant_id,book_id,year_id,id,event_type,aggregate_id,aggregate_version,payload,payload_digest,created_by)"
                    + " VALUES(?,?,?,?,?,?,?,?::jsonb,?,?)",
                b.get("tenant_id"),
                b.get("id"),
                year,
                UUID.randomUUID(),
                type,
                journal,
                payload.get("sourceVersion"),
                encoded,
                FinanceService.hash(encoded),
                actor);
    }

    static final String SELECT =
            "SELECT id,event_type AS \"eventType\",aggregate_id AS"
                + " \"aggregateId\",aggregate_version AS"
                + " \"aggregateVersion\",state,attempts,next_attempt_at AS"
                + " \"nextAttemptAt\",lease_until AS \"leaseUntil\",last_error_code AS"
                + " \"lastErrorCode\",created_at AS \"createdAt\",processed_at AS \"processedAt\""
                + " FROM plugin_finance_outbox WHERE tenant_id=? AND book_id=? AND year_id=?";

    @Transactional(readOnly = true)
    public Map<String, Object> list(String actor, UUID book, UUID year, int limit, String cursor) {
        var b = finance.originalScope(actor, book, year, "manage");
        return FinancePages.page(
                db, finance, b, year, FinancePages.Kind.OUTBOX, limit, cursor, SELECT);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String actor, UUID book, UUID year, UUID id) {
        var b = finance.originalScope(actor, book, year, "manage");
        return detail(b, year, id);
    }

    Map<String, Object> detail(Map<String, Object> b, UUID year, UUID id) {
        var row = db.one(SELECT + " AND id=?", b.get("tenant_id"), b.get("id"), year, id);
        var envelope =
                db.one(
                        "SELECT payload::text AS payload,payload_digest FROM plugin_finance_outbox"
                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        id);
        var payload = finance.decode((String) envelope.get("payload"));
        if (!FinanceService.hash(finance.encode(finance.canonical(payload)))
                .equals(envelope.get("payload_digest")))
            throw new FinanceException("OUTBOX_INTEGRITY_FAILED", "事件证据摘要校验失败");
        row.put("payload", payload);
        row.put("payloadDigest", envelope.get("payload_digest"));
        return row;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> projection(
            String actor, UUID book, UUID year, int limit, String cursor) {
        var b = finance.originalScope(actor, book, year, "manage");
        return FinancePages.page(
                db,
                finance,
                b,
                year,
                FinancePages.Kind.PROJECTION,
                limit,
                cursor,
                "SELECT journal_id AS id,journal_id AS"
                    + " \"journalId\",status,date,summary,number,source_version AS"
                    + " \"sourceVersion\",posting_seq AS \"postingSeq\",updated_at AS \"updatedAt\""
                    + " FROM plugin_finance_journal_projection WHERE tenant_id=? AND book_id=? AND"
                    + " year_id=?");
    }

    @Transactional
    public Map<String, Object> retry(
            String actor, UUID book, UUID year, UUID id, Map<String, Object> input) {
        return control(actor, book, year, id, input, false);
    }

    @Transactional
    public Map<String, Object> rebuild(
            String actor, UUID book, UUID year, Map<String, Object> input) {
        return control(actor, book, year, null, input, true);
    }

    Map<String, Object> control(
            String actor,
            UUID book,
            UUID year,
            UUID id,
            Map<String, Object> input,
            boolean rebuild) {
        FinanceSources.keys(
                input, Set.of("idempotencyKey", "reason"), Set.of("idempotencyKey", "reason"));
        String key = FinanceSources.exact(input, "idempotencyKey", 100);
        if (key.length() < 8) throw FinanceException.invalid("幂等键至少8字符");
        FinanceSources.exact(input, "reason", 500);
        var b = finance.originalScope(actor, book, year, "manage");
        UUID user = (UUID) b.get("actor_id");
        db.jdbc
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")
                .param(user + ":" + key)
                .query((rs, n) -> 0)
                .list();
        db.one(
                "SELECT id FROM plugin_finance_book WHERE tenant_id=? AND id=? FOR UPDATE",
                b.get("tenant_id"),
                book);
        b = finance.originalScope(actor, book, year, "manage");
        finance.lockYearWrites(b, List.of(year));
        String route =
                book
                        + "/"
                        + year
                        + "/"
                        + (rebuild ? "rebuild-projection" : "outbox/" + id + "/retry");
        String digest = FinanceService.hash(finance.encode(finance.canonical(input)));
        var cached =
                db.list(
                        "SELECT route,payload_hash,response FROM plugin_finance_idempotency WHERE"
                            + " actor_id=? AND key=?",
                        user,
                        key);
        if (!cached.isEmpty()) {
            var old = cached.getFirst();
            if (!route.equals(old.get("route")) || !digest.equals(old.get("payload_hash")))
                throw new FinanceException("IDEMPOTENCY_CONFLICT", "请求键已用于其他操作");
            return finance.decode((String) old.get("response"));
        }
        Map<String, Object> result;
        if (rebuild) {
            long count =
                    ((Number)
                                    db.one(
                                                    "SELECT count(*) AS n FROM"
                                                        + " plugin_finance_journal WHERE"
                                                        + " tenant_id=? AND book_id=? AND"
                                                        + " year_id=?",
                                                    b.get("tenant_id"),
                                                    book,
                                                    year)
                                            .get("n"))
                            .longValue();
            if (count > 1000)
                throw new FinanceException("ASYNC_REBUILD_REQUIRED", "本片只支持千条以内的范围内重建，大批量异步重建尚未开放");
            var rows =
                    db.list(
                            "SELECT id FROM plugin_finance_journal WHERE tenant_id=? AND book_id=?"
                                + " AND year_id=? ORDER BY id",
                            b.get("tenant_id"),
                            book,
                            year);
            db.update(
                    "DELETE FROM plugin_finance_journal_projection WHERE tenant_id=? AND book_id=?"
                        + " AND year_id=?",
                    b.get("tenant_id"),
                    book,
                    year);
            for (var row : rows) project(b, year, (UUID) row.get("id"));
            finance.originalAudit(user, b, year, "PROJECTION_REBUILT", year, input);
            result = Map.of("status", "REBUILT", "rows", rows.size());
        } else {
            var current =
                    db.one(
                            "SELECT state,lease_until>now() AS lease_valid FROM"
                                + " plugin_finance_outbox WHERE tenant_id=? AND book_id=? AND"
                                + " year_id=? AND id=? FOR UPDATE",
                            b.get("tenant_id"),
                            book,
                            year,
                            id);
            String state = (String) current.get("state");
            if (state.equals("PROCESSING") && Boolean.TRUE.equals(current.get("lease_valid")))
                throw new FinanceException("OUTBOX_LEASE_ACTIVE", "事件仍由有效租约处理，请稍后查询");
            if (!state.equals("DONE")) {
                db.update(
                        "UPDATE plugin_finance_outbox SET"
                            + " state='PENDING',attempts=0,next_attempt_at=now(),lease_token=NULL,lease_until=NULL,last_error_code=NULL"
                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        book,
                        year,
                        id);
                finance.originalAudit(user, b, year, "OUTBOX_RETRY", id, input);
            }
            result = detail(b, year, id);
        }
        db.update(
                "INSERT INTO plugin_finance_idempotency VALUES(?,?,?,?,?)",
                user,
                key,
                route,
                digest,
                finance.encode(result));
        return result;
    }

    /**
     * Caller uses a short independent transaction. Claims do not apply a business or projection
     * effect.
     */
    List<Map<String, Object>> claim(int maximum) {
        if (maximum < 1 || maximum > 20) throw FinanceException.invalid("消费者每轮上限20");
        UUID token = UUID.randomUUID();
        return db.list(
                "WITH picked AS (SELECT id FROM plugin_finance_outbox WHERE (state='PENDING' AND"
                    + " next_attempt_at<=now()) OR (state='PROCESSING' AND lease_until<now()) ORDER"
                    + " BY created_at,id FOR UPDATE SKIP LOCKED LIMIT ?) UPDATE"
                    + " plugin_finance_outbox o SET"
                    + " state='PROCESSING',attempts=o.attempts+1,lease_token=?,lease_until=now()+interval"
                    + " '60 seconds' FROM picked WHERE o.id=picked.id RETURNING o.id,o.lease_token",
                maximum,
                token);
    }

    /**
     * Trusted consumer context is the immutable FK-bound envelope, never a caller-supplied scope.
     */
    void consume(UUID id, UUID token) {
        var rows =
                db.list(
                        "SELECT *,lease_until>now() AS lease_valid,payload::text AS payload_text"
                            + " FROM plugin_finance_outbox WHERE id=? FOR UPDATE",
                        id);
        if (rows.isEmpty()) return;
        var event = rows.getFirst();
        if (!"PROCESSING".equals(event.get("state"))
                || !token.equals(event.get("lease_token"))
                || !Boolean.TRUE.equals(event.get("lease_valid"))) return;
        if (((Number) event.get("attempts")).intValue() > 5)
            throw new FinanceException("OUTBOX_RETRY_EXHAUSTED", "事件已达到重试上限");
        var payload = finance.decode((String) event.get("payload_text"));
        if (!FinanceService.hash(finance.encode(finance.canonical(payload)))
                .equals(event.get("payload_digest")))
            throw new FinanceException("OUTBOX_INTEGRITY_FAILED", "事件证据校验失败");
        UUID year = (UUID) event.get("year_id"), journal = (UUID) event.get("aggregate_id");
        Map<String, Object> b =
                Map.of("tenant_id", event.get("tenant_id"), "id", event.get("book_id"));
        if (!journal.toString().equals(payload.get("journalId").toString())
                || !Objects.equals(
                        ((Number) event.get("aggregate_version")).longValue(),
                        ((Number) payload.get("sourceVersion")).longValue()))
            throw new FinanceException("OUTBOX_INTEGRITY_FAILED", "事件聚合引用不一致");
        if (!Set.of("SOURCE_JOURNAL_DRAFT_CREATED", "JOURNAL_POSTED", "JOURNAL_CHANGED")
                .contains(event.get("event_type")))
            throw new FinanceException("OUTBOX_EVENT_UNSUPPORTED", "无受控本地消费者");
        var authoritative = snapshot(db, b, year, journal);
        if (((Number) authoritative.get("sourceVersion")).longValue()
                < ((Number) event.get("aggregate_version")).longValue())
            throw new FinanceException("OUTBOX_INTEGRITY_FAILED", "事件版本超出权威对象");
        if ("JOURNAL_POSTED".equals(event.get("event_type"))
                && (!"POSTED".equals(authoritative.get("status"))
                        || !"POSTED".equals(payload.get("status"))
                        || !Objects.equals(
                                authoritative.get("postingSeq"),
                                payload.get("postingSeq") instanceof Number number
                                        ? number.longValue()
                                        : null)))
            throw new FinanceException("OUTBOX_INTEGRITY_FAILED", "记账事件与正式事实不一致");
        int inserted =
                db.update(
                        "INSERT INTO"
                            + " plugin_finance_projection_receipt(tenant_id,book_id,year_id,event_id,stable_consumer,projection_version)"
                            + " VALUES(?,?,?,?,?,1) ON CONFLICT DO NOTHING",
                        event.get("tenant_id"),
                        event.get("book_id"),
                        year,
                        id,
                        VIEW);
        if (inserted == 1) project(b, year, journal);
        db.update(
                "UPDATE plugin_finance_outbox SET"
                    + " state='DONE',processed_at=now(),lease_token=NULL,lease_until=NULL,last_error_code=NULL"
                    + " WHERE id=? AND lease_token=?",
                id,
                token);
    }

    void project(Map<String, Object> b, UUID year, UUID journal) {
        var row = snapshot(db, b, year, journal);
        String digest = FinanceService.hash(finance.encode(finance.canonical(row)));
        if ("POSTED".equals(row.get("status")) && row.get("postingSeq") == null) {
            var yr =
                    db.one(
                            "SELECT precision_review_required FROM plugin_finance_year WHERE"
                                + " tenant_id=? AND book_id=? AND id=?",
                            b.get("tenant_id"),
                            b.get("id"),
                            year);
            if (!Boolean.TRUE.equals(yr.get("precision_review_required")))
                throw new FinanceException("OUTBOX_INTEGRITY_FAILED", "新过账事实缺少提交水位");
        }
        db.update(
                "INSERT INTO"
                    + " plugin_finance_journal_projection(tenant_id,book_id,year_id,journal_id,status,date,summary,number,source_version,posting_seq,payload_digest)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?) ON"
                    + " CONFLICT(tenant_id,book_id,year_id,journal_id) DO UPDATE SET"
                    + " status=excluded.status,date=excluded.date,summary=excluded.summary,number=excluded.number,source_version=excluded.source_version,posting_seq=excluded.posting_seq,payload_digest=excluded.payload_digest,updated_at=now()"
                    + " WHERE"
                    + " excluded.source_version>=plugin_finance_journal_projection.source_version",
                b.get("tenant_id"),
                b.get("id"),
                year,
                journal,
                row.get("status"),
                java.time.LocalDate.parse(row.get("date").toString()),
                row.get("summary"),
                row.get("number"),
                row.get("sourceVersion"),
                row.get("postingSeq"),
                digest);
    }

    void failed(UUID id, UUID token) {
        db.update(
                "UPDATE plugin_finance_outbox SET state=CASE WHEN attempts>=5 THEN 'FAILED' ELSE"
                    + " 'PENDING'"
                    + " END,next_attempt_at=now()+make_interval(secs=>least(300,5*power(2,least(attempts,6)))::integer),lease_token=NULL,lease_until=NULL,last_error_code='LOCAL_PROJECTION_FAILED'"
                    + " WHERE id=? AND lease_token=? AND state='PROCESSING'",
                id,
                token);
    }
}
