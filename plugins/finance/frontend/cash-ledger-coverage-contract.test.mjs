import test from "node:test";
import assert from "node:assert/strict";
import {
  coverageMoney,
  coverageDirection,
  coverageQuery,
  checkCoveragePage,
  coverageResultLabel,
  coverageDrillPath,
  coverageOriginals,
  coverageStages,
} from "./cash-ledger-coverage-contract.ts";
const id = "313c48f7-e98f-4e13-a4f7-073d465b432f";
const other = "fe2dda4f-7bfa-4541-aaff-795ce2444b2c";
const context = {
  bookId: id,
  yearId: other,
  scopeRevisionId: id,
  contextDigest: null,
};
const preview = {
  schemaVersion: "ledger-cash-coverage-preview/1",
  mode: "READ_ONLY_PREVIEW",
  pointInTimeOnly: true,
  externalSourceCompleteness: "MISSING",
  coverageVerified: false,
  formalReportContributionEnabled: false,
  reportPublishable: false,
  contextDigest: "a".repeat(64),
  scope: { bookId: id, yearId: other, scopeRevisionId: id },
  items: [],
  summary: {
    populationRowCount: 100,
    displayMatchedRowCount: 0,
    ledgerInTotal: "1000000000000000000000000.12345678",
    ledgerOutTotal: "1.00000001",
  },
};
test("missing raw/normalized totals remain unknown and large signed/legacy decimal strings are never rounded or parsed through Number", () => {
  for (const value of [null, undefined])
    assert.equal(coverageMoney(value), "未知 / 缺失");
  for (const value of [
    "0",
    "-23.450000",
    "1000000000000000000000000.12345678",
    "0.00000001",
  ])
    assert.equal(coverageMoney(value), value);
  assert.notEqual(coverageMoney(null), coverageMoney("0"));
  assert.equal(coverageDirection("UNKNOWN"), "未知方向");
  assert.equal(coverageDirection("future-kind"), "未知方向");
});
test("only real scope, bounded opaque cursor and closed stage enter the GET query; no null literals or injected fields", () => {
  const base = new URLSearchParams(coverageQuery(id, "", null));
  assert.deepEqual([...base.keys()], ["scopeRevisionId", "limit"]);
  assert.equal(base.get("limit"), "20");
  const opaque = "opaque+value/with=padding&embedded";
  const query = new URLSearchParams(
    coverageQuery(id, "NO_ACTIVE_CLAIM", opaque),
  );
  assert.equal(query.get("cursor"), opaque);
  assert.equal(query.get("primaryStage"), "NO_ACTIVE_CLAIM");
  assert.throws(() => coverageQuery("../another-book", "", null));
  assert.throws(() => coverageQuery(id, "SUPPORTED_CHAIN&limit=999", null));
  assert.throws(() => coverageQuery(id, "", "a".repeat(4001)));
});
test("one page retains whole-population summary even when filter has zero rows; it cannot infer zero population from empty items", () => {
  const result = checkCoveragePage(preview, context);
  assert.equal(result.summary.populationRowCount, 100);
  assert.equal(result.summary.displayMatchedRowCount, 0);
  assert.equal(result.items.length, 0);
  assert.equal(
    result.summary.ledgerInTotal,
    "1000000000000000000000000.12345678",
  );
  assert.equal(Object.keys(coverageStages).length, 7);
});
test("cross-page context and scope changes reject instead of stitching a newer view onto previous pages", () => {
  assert.equal(
    checkCoveragePage(preview, {
      ...context,
      contextDigest: preview.contextDigest,
    }),
    preview,
  );
  for (const changed of [
    { contextDigest: "b".repeat(64) },
    { scope: { ...preview.scope, bookId: other } },
    { scope: { ...preview.scope, yearId: id } },
    { scope: { ...preview.scope, scopeRevisionId: other } },
  ])
    assert.throws(
      () =>
        checkCoveragePage(
          { ...preview, ...changed },
          { ...context, contextDigest: preview.contextDigest },
        ),
      /CASH_COVERAGE_PREVIEW_STALE/,
    );
});
test("even complete local chains cannot silently grant external coverage, signed snapshot or report publication", () => {
  assert.match(coverageResultLabel("LEDGER_LINKS_SUPPORTED"), /外部总体仍缺失/);
  assert.match(coverageResultLabel("UNDETERMINED"), /待专业判断/);
  assert.match(coverageResultLabel("future"), /不能推断已完成/);
  for (const changed of [
    { coverageVerified: true },
    { formalReportContributionEnabled: true },
    { reportPublishable: true },
    { externalSourceCompleteness: "COMPLETE" },
    { pointInTimeOnly: false },
    { mode: "APPROVED_SNAPSHOT" },
  ])
    assert.throws(
      () => checkCoveragePage({ ...preview, ...changed }, context),
      /预览边界/,
    );
});
test("read-only drilldown uses closed existing resource paths, never arbitrary URL, SQL or unsupported generic claim endpoint", () => {
  assert.equal(
    coverageDrillPath("/finance/v1/books/a/years/b", "ALLOCATION", id),
    `/finance/v1/books/a/years/b/cashflow/allocations/${id}`,
  );
  assert.throws(() =>
    coverageDrillPath("/finance", "JOURNAL", "../../secrets"),
  );
  assert.throws(() => coverageDrillPath("/finance", "CLAIM", id));
});
test("original download choices use actual typed identities from known server evidence metadata, deduplicate and ignore arbitrary payload links", () => {
  const detail = {
    claimSnapshot: {
      sourceOriginal: {
        originalId: id,
        filename: "source.txt",
        sha256: "a".repeat(64),
      },
      scopeOriginal: {
        originalId: other,
        filename: "scope.txt",
        sha256: "b".repeat(64),
      },
    },
    evidence: [
      { originalId: id, filename: "source.txt", sha256: "a".repeat(64) },
      { originalId: "https://attacker.invalid/file", filename: "fake" },
    ],
    arbitraryUrl: "https://attacker.invalid",
  };
  const originals = coverageOriginals("REVIEW", detail);
  assert.equal(originals.length, 2);
  assert.deepEqual(
    originals.map((o) => o.id),
    [id, other],
  );
  assert.equal(originals[0].sha256, "a".repeat(64));
  assert.equal(coverageOriginals("JOURNAL", detail).length, 0);
});
