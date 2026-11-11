import test from "node:test";
import assert from "node:assert/strict";
import {
  yearWriteRestriction,
  yearProjectionRestriction,
} from "./year-write-scope.ts";
test("financial write eligibility requires confirmed V4 precision and an open year", () => {
  const confirmed = { precisionProfile: "V4", precisionReviewRequired: false };
  assert.equal(yearWriteRestriction({ ...confirmed, status: "OPEN" }), null);
  for (const status of [
    "CLOSING",
    "CLOSED_PENDING_ARCHIVE",
    "CLOSED",
    "UNKNOWN",
  ])
    assert.ok(yearWriteRestriction({ ...confirmed, status }));
  assert.match(
    yearWriteRestriction({ ...confirmed, status: "CLOSED_PENDING_ARCHIVE" }),
    /待归档/,
  );
  assert.ok(
    yearWriteRestriction({
      ...confirmed,
      status: "OPEN",
      precisionProfile: "LEGACY_V3",
    }),
  );
  assert.ok(
    yearWriteRestriction({
      ...confirmed,
      status: "OPEN",
      precisionReviewRequired: true,
    }),
  );
  assert.ok(yearWriteRestriction(undefined));
  assert.ok(yearWriteRestriction({ status: "OPEN" }));
});

test("projection recovery remains available for known V4 closed years without reopening financial writes", () => {
  const v4 = { precisionProfile: "V4", precisionReviewRequired: false };
  for (const status of ["OPEN", "CLOSING", "CLOSED_PENDING_ARCHIVE", "CLOSED"])
    assert.equal(yearProjectionRestriction({ ...v4, status }), null);
  assert.ok(yearWriteRestriction({ ...v4, status: "CLOSED" }));
  for (const year of [
    undefined,
    { ...v4, status: "UNKNOWN" },
    { ...v4 },
    { ...v4, status: "OPEN", precisionProfile: "LEGACY_V3" },
    { ...v4, status: "CLOSED", precisionReviewRequired: true },
  ])
    assert.ok(yearProjectionRestriction(year));
});
