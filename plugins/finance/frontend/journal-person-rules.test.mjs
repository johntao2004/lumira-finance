import test from "node:test";
import assert from "node:assert/strict";
import {
  journalPersonRoute,
  journalPersonIndependent,
  journalCreatorAccount,
  journalPersonCounter,
  journalPersonPostCandidate,
  journalPersonRejectable,
  JournalPersonWriteGate,
} from "./journal-person-rules.ts";
test("a cancelled scope can clear visible progress without allowing overlapping commands; stale completion cannot release a later owner", () => {
  const gate = new JournalPersonWriteGate();
  const old = gate.acquire();
  assert.ok(old);
  assert.equal(gate.acquire(), null);
  // Aborting transport/clearing a view does not prove that its promise settled.
  assert.equal(gate.acquire(), null);
  gate.release(old);
  const current = gate.acquire();
  assert.ok(current);
  gate.release(old);
  assert.equal(gate.acquire(), null);
  gate.release(current);
  assert.ok(gate.acquire());
});
test("independent return can release an approved person-mode journal without requiring its old approval expiry or approver to remain valid", () => {
  assert.equal(journalPersonRejectable("JOURNAL_PERSON_SOD", "APPROVED"), true);
  assert.equal(
    journalPersonRejectable("JOURNAL_PERSON_SOD", "SUBMITTED"),
    true,
  );
  assert.equal(journalPersonRejectable("ACCOUNT_ONLY", "APPROVED"), false);
  for (const state of ["POSTED", "CANCELLED", "DRAFT"])
    assert.equal(journalPersonRejectable("JOURNAL_PERSON_SOD", state), false);
});
test("known legacy years keep legacy routes; unknown mode and unsupported origins fail closed", () => {
  for (const action of [
    "create",
    "approve",
    "post",
    "source",
    "reversal",
    "closing",
    "year-plan",
    "year-approve",
    "year-execute",
  ])
    assert.equal(journalPersonRoute("ACCOUNT_ONLY", action), "LEGACY");
  assert.equal(journalPersonRoute(null, "approve"), "BLOCKED");
  for (const action of [
    "source",
    "reversal",
    "closing",
    "year-plan",
    "year-approve",
    "year-execute",
  ])
    assert.equal(journalPersonRoute("JOURNAL_PERSON_SOD", action), "BLOCKED");
  assert.equal(journalPersonRoute("JOURNAL_PERSON_SOD", "approve"), "PERSON");
  assert.equal(journalPersonRoute("JOURNAL_PERSON_SOD", "post"), "PERSON");
  assert.equal(journalPersonRoute("JOURNAL_PERSON_SOD", "reject"), "BLOCKED");
  assert.equal(
    journalPersonRoute("JOURNAL_PERSON_SOD", "reject", true),
    "LEGACY",
  );
});
test("person independence excludes creator and editor aliases but never transfers creator-only ownership", () => {
  const maker = { accountId: "maker", personId: "person-a" },
    alias = { accountId: "alias", personId: "person-a" },
    reviewer = { accountId: "reviewer", personId: "person-b" };
  assert.equal(journalPersonIndependent(alias, maker, maker), false);
  assert.equal(journalPersonIndependent(reviewer, maker, maker), true);
  assert.equal(journalPersonIndependent(reviewer, maker, reviewer), false);
  assert.equal(journalPersonIndependent(reviewer, maker, null), false);
  assert.equal(journalCreatorAccount(alias, "maker"), false);
  assert.equal(journalCreatorAccount(maker, "maker"), true);
});
test("post requires the exact live content and approval tuple, without a third-person poster exclusion", () => {
  const base = {
    businessPostAllowed: true,
    journalStatus: "APPROVED",
    journalVersion: "9007199254740993",
    contentVersion: "7",
    contentDigest: "a".repeat(64),
    now: Date.parse("2026-11-09T00:00:00Z"),
    approval: {
      id: "real-approval",
      status: "ACTIVE",
      approvedJournalVersion: "9007199254740993",
      contentVersion: "7",
      contentDigest: "a".repeat(64),
      expiresAt: "2026-11-10T00:00:00Z",
    },
  };
  assert.equal(journalPersonPostCandidate(base), true);
  for (const update of [
    { businessPostAllowed: false },
    { contentVersion: "8" },
    { journalVersion: "9007199254740994" },
    { contentDigest: "b".repeat(64) },
    { approval: null },
    { approval: { ...base.approval, status: "CONSUMED" } },
    { approval: { ...base.approval, expiresAt: "2026-11-08T00:00:00Z" } },
  ])
    assert.equal(journalPersonPostCandidate({ ...base, ...update }), false);
  assert.equal(journalPersonCounter("9007199254740993"), "9007199254740993");
  assert.throws(() => journalPersonCounter("9223372036854775808"));
});
