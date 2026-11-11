import assert from "node:assert/strict";
import test from "node:test";
import { sourcePayload } from "./source-event-contract.ts";
test("source command preserves stable source identity and exact money while excluding arbitrary execution fields", () => {
  const input = {
    sourceSystem: "erp-a",
    sourceEventId: "sale-1",
    sourceDocumentId: "doc-1",
    sourceDocumentVersion: "2",
    eventType: "REVENUE_RECOGNIZED",
    businessDate: "2026-01-01",
    accountingDate: "2026-01-01",
    occurredAt: "2026-01-01T00:00:00Z",
    reason: "accepted",
    sourceLineId: "line-1",
    businessSemantic: "revenue",
    description: "service",
    quantity: "1",
    netAmount: "10000000000000000000.01",
    taxAmount: "0.00",
    grossAmount: "10000000000000000000.01",
    summary: "recognition",
    mappingRuleVersion: "map-1",
    handlerVersion: "handler-1",
    journalLines: [
      {
        accountId: "a",
        debit: "10000000000000000000.01",
        credit: "0.00",
        memo: "entry",
      },
    ],
    url: "https://untrusted",
    sql: "DROP",
    status: "POSTED",
  };
  const first = sourcePayload(input, "delivery-1");
  const replay = sourcePayload(
    { ...input, handlerVersion: "handler-2", mappingRuleVersion: "map-2" },
    "delivery-2",
  );
  assert.equal(first.event.sourceEventId, replay.event.sourceEventId);
  assert.equal(first.journal.lines[0].debit, "10000000000000000000.01");
  assert.equal(first.journal.lines[0].sourceLineId, "line-1");
  assert.equal(first.event.effectType, "CREATE_JOURNAL_DRAFT");
  assert.deepEqual(first.event.evidenceIds, []);
  assert.ok(
    !("url" in first) && !("sql" in first) && !("status" in first.journal),
  );
});
