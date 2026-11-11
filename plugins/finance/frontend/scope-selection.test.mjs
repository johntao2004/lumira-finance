import assert from "node:assert/strict";
import test from "node:test";
import { revokedSelection } from "./scope-selection.ts";
test("only authoritative removal revokes a selected scope", () => {
  assert.equal(
    revokedSelection("year-2026", true, [{ id: "year-2027" }]),
    true,
  );
  assert.equal(revokedSelection("year-2026", false, []), false);
  assert.equal(
    revokedSelection("year-2026", true, [{ id: "year-2026" }]),
    false,
  );
  assert.equal(revokedSelection("", true, []), false);
});
