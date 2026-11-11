import test from "node:test";
import assert from "node:assert/strict";
import {
  EvidenceRequestOwner,
  evidenceRequestIdentity,
} from "./evidence-request-state.ts";
test("modal close, child remount, detail selection and read revision do not replace an identical frozen request key", () => {
  const owner = new EvidenceRequestOwner(),
    identity = "book/year/account/auth";
  let count = 0;
  const create = () => `key-${++count}`;
  const body = {
    expectedVersion: "1",
    contentDigest: "a".repeat(64),
    reason: "unchanged decision",
    requestedExpiresAt: "2026-11-10T00:00:00Z",
  };
  const fingerprint = JSON.stringify(["/review/approve", "POST", body]);
  const first = owner.key(identity, fingerprint, create);
  for (const event of [
    "modal-close",
    "detail-refetch",
    "header-refresh",
    "other-selection",
    "same-selection",
  ]) {
    // Each new child only receives the same parent owner; no component-local map survives.
    const remountedChild = { owner, identity };
    assert.equal(
      remountedChild.owner.key(remountedChild.identity, fingerprint, create),
      first,
      event,
    );
  }
  assert.equal(count, 1);
  assert.notEqual(
    owner.key(
      identity,
      JSON.stringify([
        "/review/approve",
        "POST",
        { ...body, reason: "new explicit reason" },
      ]),
      create,
    ),
    first,
  );
});
test("one parent owner is bounded20 across resources and retains a recent unknown upload/decision retry", () => {
  const owner = new EvidenceRequestOwner(),
    identity = "scope";
  let count = 0;
  const create = () => `key-${++count}`;
  const retry = owner.key(identity, "unknown-upload", create);
  for (let i = 0; i < 1000; i++) {
    owner.key(identity, `other-resource-${i}`, create);
    assert.equal(owner.key(identity, "unknown-upload", create), retry);
    assert.ok(owner.size() <= 20);
  }
});
test("actual scope/account/auth changes and revocation clear the cache; old completion cannot unlock a new identity", () => {
  const owner = new EvidenceRequestOwner();
  let count = 0;
  const create = () => `key-${++count}`;
  const original = owner.key("book/year/account/auth", "same-body", create);
  const old = owner.acquire("book/year/account/auth");
  assert.ok(old);
  assert.equal(owner.acquire("book/year/account/auth"), null);
  const next = owner.key("book/year/other-account/auth", "same-body", create);
  assert.notEqual(next, original);
  assert.equal(owner.isCurrent(old), false);
  const current = owner.acquire("book/year/other-account/auth");
  owner.release(old);
  assert.equal(owner.acquire("book/year/other-account/auth"), null);
  owner.release(current);
  owner.clear();
  assert.equal(owner.size(), 0);
  assert.notEqual(
    owner.key("book/year/other-account/auth", "same-body", create),
    next,
  );
  owner.bind("other-book/year/account/auth");
  assert.equal(owner.size(), 0);
});

test("UI identity clears unknown-result keys when the same account changes person, while unchanged actor refetch retains them", () => {
  const owner = new EvidenceRequestOwner();
  let n = 0;
  const create = () => `key-${++n}`;
  const original = evidenceRequestIdentity("book/year", "auth", {
    accountId: "same-account",
    personId: "person-a",
  });
  const first = owner.key(original, "same-frozen-body", create);
  assert.equal(
    owner.key(
      evidenceRequestIdentity("book/year", "auth", {
        accountId: "same-account",
        personId: "person-a",
      }),
      "same-frozen-body",
      create,
    ),
    first,
  );
  const changed = evidenceRequestIdentity("book/year", "auth", {
    accountId: "same-account",
    personId: "person-b",
  });
  assert.notEqual(owner.key(changed, "same-frozen-body", create), first);
  assert.equal(owner.size(), 1);
});
