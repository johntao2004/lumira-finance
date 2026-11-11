import test from "node:test";
import assert from "node:assert/strict";
import {
  cashMonth,
  cashTemplate,
  cashCreatePayload,
  cashDecisionPayload,
} from "./cash-scope-contract.ts";
const item = {
  accountId: "asset-1",
  singleCashInstrument: true,
  instrumentKind: "DEMAND_DEPOSIT",
  qualification: "INCLUDED",
  restrictionKind: "NONE",
  availableOnDemand: true,
  eligibilityBasis: "documented access",
  evidenceOriginalId: "real-original",
  originalSha256: "untrusted-client-hash",
  accountVersionDigest: "fake-version",
};
test("cash scope uses whole natural months including leap year and standard-bound templates", () => {
  assert.deepEqual(cashMonth(2028, 2), {
    periodStart: "2028-02-01",
    periodEnd: "2028-02-29",
  });
  assert.deepEqual(cashMonth(2027, 2), {
    periodStart: "2027-02-01",
    periodEnd: "2027-02-28",
  });
  assert.equal(cashTemplate("CAS"), "CAS-CF-2019-A2");
  assert.equal(cashTemplate("SMALL"), "SBE-CF-2011");
  assert.throws(() => cashTemplate("CUSTOM"));
  assert.throws(() => cashMonth(2026, 13));
});
test("cash scope rejects duplicate or restricted included accounts and sends only server-owned evidence identity", () => {
  const payload = cashCreatePayload("CAS", 2026, {
    month: 1,
    reason: "test",
    items: [item],
  });
  assert.equal(payload.items[0].evidenceOriginalId, "real-original");
  assert.ok(!("originalSha256" in payload.items[0]));
  assert.ok(!("accountVersionDigest" in payload.items[0]));
  assert.throws(() =>
    cashCreatePayload("CAS", 2026, {
      month: 1,
      reason: "x",
      items: [item, item],
    }),
  );
  assert.throws(() =>
    cashCreatePayload("CAS", 2026, {
      month: 1,
      reason: "x",
      items: [{ ...item, restrictionKind: "RESTRICTED" }],
    }),
  );
  assert.throws(() =>
    cashCreatePayload("CAS", 2026, {
      month: 1,
      reason: "x",
      items: [{ ...item, availableOnDemand: false }],
    }),
  );
  assert.deepEqual(
    cashDecisionPayload(
      { version: 3, contentVersion: 1, contentDigest: "server-digest" },
      "reviewed",
    ),
    {
      schemaVersion: "cashflow-working-paper/1",
      expectedVersion: 3,
      targetContentVersion: 1,
      contentDigest: "server-digest",
      reason: "reviewed",
    },
  );
});

test("paper evidence retains explicit user provenance and physical location only in eligibility basis", () => {
  const payload = cashCreatePayload("SMALL", 2026, {
    month: 1,
    reason: "review",
    items: [
      {
        ...item,
        evidenceSource: "PAPER_SCAN",
        paperLocation: "Finance cabinet A",
      },
    ],
  });
  assert.match(payload.items[0].eligibilityBasis, /纸质凭证扫描件/);
  assert.match(payload.items[0].eligibilityBasis, /Finance cabinet A/);
  assert.ok(!("paperLocation" in payload.items[0]));
  assert.ok(!("evidenceSource" in payload.items[0]));
  assert.throws(() =>
    cashCreatePayload("CAS", 2026, {
      month: 1,
      reason: "review",
      items: [{ ...item, evidenceSource: "PAPER_SCAN", paperLocation: "" }],
    }),
  );
});

test("single cash instrument declaration is explicit and never silently inferred", () => {
  assert.throws(() =>
    cashCreatePayload("CAS", 2026, {
      month: 1,
      reason: "test",
      items: [{ ...item, singleCashInstrument: false }],
    }),
  );
  assert.throws(() =>
    cashCreatePayload("CAS", 2026, {
      month: 1,
      reason: "test",
      items: [{ ...item, singleCashInstrument: undefined }],
    }),
  );
  assert.equal(
    cashCreatePayload("CAS", 2026, { month: 1, reason: "test", items: [item] })
      .items[0].singleCashInstrument,
    true,
  );
});
