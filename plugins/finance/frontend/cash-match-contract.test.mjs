import test from "node:test";
import assert from "node:assert/strict";
import {
  correctionProposal,
  correctionAction,
  correctionWithdrawal,
  withdrawalDecision,
  claimOccupancy,
} from "./cash-match-contract.ts";
const old = {
  journalId: "old-journal",
  lineNo: 2,
  accountId: "cash-a",
  date: "2027-01-15",
  direction: "IN",
  normalizedAmount: "100.01000000",
  journalVersion: 4,
  postingSeq: 17,
  postingDigest: "old-digest",
  number: 1,
  summary: "old",
  activeRegistrationId: null,
};
const target = {
  ...old,
  journalId: "new-journal",
  lineNo: 7,
  postingSeq: 18,
  postingDigest: "new-digest",
};
const raw = {
  id: "raw-1",
  status: "VOIDED",
  version: 5,
  contentDigest: "raw-digest",
  accountId: "cash-a",
  occurredDate: "2027-01-15",
  direction: "IN",
  normalizedAmount: "100.01000000",
  matchedLegSnapshot: old,
};
const effective = {
  originalRegistrationId: "raw-1",
  rawStatus: "VOIDED",
  rawVersion: 5,
  rawContentDigest: "raw-digest",
  effectiveClaim: null,
  currentSourceMatchGeneration: 3,
};
const claim = {
  id: "claim-4",
  kind: "MATCH_CORRECTION",
  rawRegistrationId: "raw-1",
  correctionId: "correction-1",
  generation: 4,
  state: "ACTIVE",
};
const detail = {
  id: "correction-1",
  status: "APPROVED",
  version: 3,
  contentVersion: 1,
  contentDigest: "proposal-digest",
  expectedSourceMatchGeneration: 3,
  currentSourceMatchGeneration: 4,
  effectiveClaim: claim,
  pendingWithdrawal: null,
};
test("correction binds real target and current source version/generation without resubmitting any source facts", () => {
  const payload = correctionProposal(
    { ...raw, amount: "999", sourceSystem: "old-source" },
    effective,
    target,
    "wrong original match",
  );
  assert.equal(payload.expectedRawVersion, 5);
  assert.equal(payload.expectedSourceMatchGeneration, 3);
  assert.equal(payload.targetLineNo, 7);
  assert.equal(payload.expectedTargetPostingSeq, 18);
  assert.ok(
    !("amount" in payload) &&
      !("sourceSystem" in payload) &&
      !("accountId" in payload),
  );
  assert.equal(raw.status, "VOIDED");
});
test("proposal cannot revive raw, retain old target, alter facts, or displace an effective claim", () => {
  assert.throws(() =>
    correctionProposal(
      { ...raw, status: "REGISTERED" },
      effective,
      target,
      "reason",
    ),
  );
  assert.throws(() => correctionProposal(raw, effective, old, "reason"));
  assert.throws(() =>
    correctionProposal(
      raw,
      effective,
      { ...target, date: "2027-01-16" },
      "reason",
    ),
  );
  assert.throws(() =>
    correctionProposal(
      raw,
      effective,
      { ...target, normalizedAmount: "100.02000000" },
      "reason",
    ),
  );
  assert.throws(() =>
    correctionProposal(
      raw,
      { ...effective, effectiveClaim: claim },
      target,
      "reason",
    ),
  );
  assert.throws(() =>
    correctionProposal(raw, { ...effective, rawVersion: 4 }, target, "reason"),
  );
  assert.throws(() =>
    correctionProposal(
      raw,
      effective,
      { ...target, activeClaimId: "occupied" },
      "reason",
    ),
  );
});
test("historic approval alone cannot authorize release; only this correction current active claim can be requested", () => {
  assert.throws(() =>
    correctionWithdrawal({ ...detail, effectiveClaim: null }, "withdraw"),
  );
  assert.throws(() =>
    correctionWithdrawal(
      { ...detail, effectiveClaim: { ...claim, correctionId: "other" } },
      "withdraw",
    ),
  );
  assert.throws(() =>
    correctionWithdrawal(
      { ...detail, pendingWithdrawal: { id: "pending" } },
      "withdraw",
    ),
  );
  const payload = correctionWithdrawal(detail, "withdraw");
  assert.equal(payload.activeClaimId, "claim-4");
  assert.equal(payload.expectedSourceMatchGeneration, 4);
  assert.equal(payload.expectedVersion, 3);
  assert.ok(!("targetContentVersion" in payload));
});
test("workflow decisions bind distinct correction/withdrawal versions and current generation", () => {
  assert.deepEqual(correctionAction(detail, "review"), {
    schemaVersion: "cash-match-correction/1",
    expectedVersion: 3,
    targetContentVersion: 1,
    contentDigest: "proposal-digest",
    expectedSourceMatchGeneration: 4,
    reason: "review",
  });
  assert.deepEqual(
    withdrawalDecision(
      { version: 2, contentDigest: "withdraw-digest" },
      4,
      "release",
    ),
    {
      schemaVersion: "cash-match-correction/1",
      expectedVersion: 2,
      contentDigest: "withdraw-digest",
      expectedSourceMatchGeneration: 4,
      reason: "release",
    },
  );
});
test("typed eligible occupancy never describes VOIDED raw as revived registration", () => {
  assert.match(
    claimOccupancy({
      activeRegistrationId: "raw-1",
      activeClaimId: "claim-4",
      activeClaimKind: "MATCH_CORRECTION",
      activeCorrectionId: "correction-1",
    }),
    /原登记仍作废/,
  );
  assert.equal(claimOccupancy({ activeRegistrationId: null }), "未占用");
});
