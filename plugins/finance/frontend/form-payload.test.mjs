import assert from "node:assert/strict";
import test from "node:test";
import { formPayload } from "./form-payload.ts";
test("document payload excludes server metadata and shared form defaults", () => {
  assert.deepEqual(
    formPayload("document", {
      originalId: "original-1",
      source: "PAPER_SCAN",
      originalLocation: "cabinet A",
      filename: "server.pdf",
      sha256: "server-hash",
      standard: "CAS",
      year: 2026,
      category: "ASSET",
      role: "READER",
    }),
    {
      originalId: "original-1",
      source: "PAPER_SCAN",
      originalLocation: "cabinet A",
    },
  );
  assert.deepEqual(
    formPayload("journal", {
      date: "2026-01-01",
      summary: "entry",
      filename: "server.pdf",
      role: "READER",
    }),
    { date: "2026-01-01", summary: "entry" },
  );
});

test("member payload retains explicit year scope and selected UUID array", () => {
  assert.deepEqual(
    formPayload("member", {
      username: "reviewer",
      role: "REVIEWER",
      yearScope: "SELECTED",
      yearIds: ["year-2026", "year-2027"],
      standard: "CAS",
      filename: "readonly.pdf",
    }),
    {
      username: "reviewer",
      role: "REVIEWER",
      yearScope: "SELECTED",
      yearIds: ["year-2026", "year-2027"],
    },
  );
});
