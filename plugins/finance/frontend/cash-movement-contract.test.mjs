import test from "node:test";
import assert from "node:assert/strict";
import {
  movementPayload,
  movementDirectionLabel,
  movementVoidPayload,
} from "./cash-movement-contract.ts";
const catalog = {
  catalogVersion: "1",
  sourceSystems: [
    {
      id: "BANK_STATEMENT_MANUAL_V1",
      evidenceKind: "BANK_STATEMENT",
      instrumentKinds: ["DEMAND_DEPOSIT"],
      label: "manual",
    },
  ],
  transactionKinds: [{ id: "ORDINARY_CUSTOMER_RECEIPT", direction: "IN" }],
};
const scope = {
  id: "approved-jan",
  status: "APPROVED",
  scopeVersion: 3,
  contentDigest: "jan-digest",
  periodStart: "2027-01-01",
  periodEnd: "2027-01-31",
  templateId: "CAS-CF-2019-A2",
  items: [
    {
      accountId: "cash-a",
      instrumentKind: "DEMAND_DEPOSIT",
      qualification: "INCLUDED",
      singleCashInstrument: true,
    },
  ],
};
const leg = {
  journalId: "posted-1",
  lineNo: 7,
  accountId: "cash-a",
  date: "2027-01-15",
  direction: "IN",
  normalizedAmount: "100.01000000",
  journalVersion: 4,
  postingSeq: 17,
  postingDigest: "posted-digest",
  number: 1,
  summary: "receipt",
  activeRegistrationId: null,
};
const form = {
  sourceSystem: "BANK_STATEMENT_MANUAL_V1",
  sourceMovementKey: "statement-account-a:row-17",
  sourceDocumentId: "jan-statement",
  sourceDocumentVersion: "1",
  evidenceOriginalId: "source-original",
  sourceRowReference: "account-a/page-1/row-17",
  occurredDate: "2027-01-15",
  direction: "IN",
  amount: "100.01",
  transactionKind: "ORDINARY_CUSTOMER_RECEIPT",
  description: "actual statement receipt",
  reason: "register independent source",
  evidenceSource: "ELECTRONIC_ORIGINAL",
};
test("registration preserves independent source amount and stable identity, binds selected scope generation and real leg facts only", () => {
  const payload = movementPayload(
    { ...form, postingSeq: 999, verified: true, normalizedAmount: "1.00" },
    { ...scope, globalLatestScopeVersion: 15 },
    "cash-a",
    leg,
    catalog,
  );
  assert.equal(payload.amount, "100.01");
  assert.equal(payload.sourceMovementKey, form.sourceMovementKey);
  assert.equal(payload.sourceRowReference, form.sourceRowReference);
  assert.equal(payload.expectedScopeVersion, 3);
  assert.equal(payload.scopeContentDigest, "jan-digest");
  assert.equal(payload.lineNo, 7);
  assert.equal(payload.expectedPostingSeq, 17);
  assert.equal(payload.expectedJournalVersion, 4);
  assert.equal(payload.evidenceKind, "BANK_STATEMENT");
  assert.ok(
    !("verified" in payload) &&
      !("postingDigest" in payload) &&
      !("normalizedAmount" in payload),
  );
});
test("mismatch, lexical excess precision, wrong direction/month and occupied cash leg all reject without rounding or splitting", () => {
  for (const amount of [
    "100.00",
    "100.02",
    "100.010",
    "1.001",
    "1.005",
    "0",
    "-1",
  ])
    assert.throws(() =>
      movementPayload({ ...form, amount }, scope, "cash-a", leg, catalog),
    );
  for (const change of [
    { direction: "OUT" },
    { occurredDate: "2027-01-16" },
    { occurredDate: "2027-02-15" },
  ])
    assert.throws(() =>
      movementPayload({ ...form, ...change }, scope, "cash-a", leg, catalog),
    );
  assert.throws(() =>
    movementPayload(
      form,
      scope,
      "cash-a",
      { ...leg, activeRegistrationId: "existing" },
      catalog,
    ),
  );
  assert.throws(() =>
    movementPayload(
      form,
      scope,
      "cash-a",
      { ...leg, postingSeq: undefined },
      catalog,
    ),
  );
});
test("catalog and approved included single-tool scope are mandatory; no custom namespace/type bypass", () => {
  for (const change of [
    { sourceSystem: "CUSTOM" },
    { transactionKind: "INTERNAL_TRANSFER" },
    { sourceMovementKey: "" },
    { sourceRowReference: "" },
  ])
    assert.throws(() =>
      movementPayload({ ...form, ...change }, scope, "cash-a", leg, catalog),
    );
  assert.throws(() =>
    movementPayload(
      form,
      { ...scope, status: "DRAFT" },
      "cash-a",
      leg,
      catalog,
    ),
  );
  assert.throws(() =>
    movementPayload(
      form,
      { ...scope, items: [{ ...scope.items[0], qualification: "EXCLUDED" }] },
      "cash-a",
      leg,
      catalog,
    ),
  );
});
test("void request binds immutable version/digest without reviving source or replacing evidence", () => {
  assert.deepEqual(
    movementVoidPayload(
      { version: 2, contentVersion: 1, contentDigest: "server-digest" },
      "wrong source row",
    ),
    {
      schemaVersion: "cash-movement-registration/1",
      expectedVersion: 2,
      targetContentVersion: 1,
      contentDigest: "server-digest",
      reason: "wrong source row",
    },
  );
});

test("unknown directions cannot masquerade as cash outflow", () => {
  assert.equal(movementDirectionLabel("IN"), "流入");
  assert.equal(movementDirectionLabel("OUT"), "流出");
  assert.equal(movementDirectionLabel("UNSUPPORTED"), "未知方向");
});
