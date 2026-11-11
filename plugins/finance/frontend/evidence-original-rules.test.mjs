import test from "node:test";
import assert from "node:assert/strict";
import {
  evidenceOriginalFingerprint,
  evidenceOriginalRequestKey,
  evidenceOriginalSizeAllowed,
  evidenceOriginalMaximumBytes,
} from "./evidence-original-rules.ts";
test("EV03 byte upload has an exact5MiB boundary without accepting fractional or invalid byte counts", () => {
  assert.equal(evidenceOriginalSizeAllowed(evidenceOriginalMaximumBytes), true);
  for (const size of [evidenceOriginalMaximumBytes + 1, 0, -1, 1.5, NaN])
    assert.equal(evidenceOriginalSizeAllowed(size), false);
});
test("same actual file retry retains its key across failed variants while at most20 fingerprints survive; scope and bytes distinguish requests", () => {
  const keys = new Map();
  let count = 0;
  const create = () => `key-${++count}`;
  const scope = "/finance/v1/books/book/years/year";
  const same = evidenceOriginalFingerprint(
    scope,
    "original.pdf",
    "a".repeat(64),
  );
  const original = evidenceOriginalRequestKey(keys, same, create);
  for (let i = 0; i < 1000; i++) {
    const fingerprint = evidenceOriginalFingerprint(
      scope,
      `other-${i}.pdf`,
      "b".repeat(64),
    );
    evidenceOriginalRequestKey(keys, fingerprint, create);
    assert.equal(evidenceOriginalRequestKey(keys, same, create), original);
    assert.ok(keys.size <= 20);
  }
  assert.notEqual(
    same,
    evidenceOriginalFingerprint(
      scope + "/other",
      "original.pdf",
      "a".repeat(64),
    ),
  );
  assert.notEqual(
    same,
    evidenceOriginalFingerprint(scope, "original.pdf", "c".repeat(64)),
  );
  assert.throws(() =>
    evidenceOriginalFingerprint(scope, "original.pdf", "fake-server-hash"),
  );
});
