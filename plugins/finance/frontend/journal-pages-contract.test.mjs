import test from "node:test";
import assert from "node:assert/strict";
import {
  emptyPageFilters,
  pageQuery,
  canonicalPageCounter,
  ledgerLineKey,
  checkFinancePage,
  pageAsOfKey,
  unknownPageTotal,
  pageCachePolicy,
  forgetPageQueries,
  tabAfterSensitiveReset,
} from "./journal-pages-contract.ts";
const id = "313c48f7-e98f-4e13-a4f7-073d465b432f",
  yearId = "fe2dda4f-7bfa-4541-aaff-795ce2444b2c";
const context = {
  resource: "journals",
  bookId: id,
  yearId,
  accountId: "",
  expectedContext: null,
};
const page = {
  schemaVersion: "journal-ledger-pages/1",
  resource: "journals",
  scope: { bookId: id, yearId },
  asOf: {
    readEpoch: id,
    generation: "9007199254740992",
    authorizationContextDigest: "a".repeat(64),
  },
  contextDigest: "b".repeat(64),
  items: [],
  returnedCount: 0,
  nextCursor: null,
  previousCursor: null,
  hasNext: false,
  hasPrevious: false,
  totalCount: null,
  totalStatus: "NOT_COMPUTED",
};
test("literal keyword preserves spaces, case, percent, underscore and backslash without client wildcard rewriting", () => {
  const filters = {
    ...emptyPageFilters(),
    dateFrom: "2026-01-01",
    dateTo: "2026-12-31",
    keyword: "  Mix%_\\Case  ",
    status: "POSTED",
  };
  const q = new URLSearchParams(pageQuery("journals", 2026, filters, 50, null));
  assert.equal(q.get("keyword"), filters.keyword);
  assert.equal(q.get("status"), "POSTED");
  assert.equal(q.get("limit"), "50");
  assert.equal(q.has("cursor"), false);
  assert.throws(
    () =>
      pageQuery(
        "journals",
        2026,
        { ...filters, dateFrom: "", dateTo: "" },
        20,
        null,
      ),
    /显式指定/,
  );
  assert.throws(
    () =>
      pageQuery(
        "journals",
        2026,
        { ...emptyPageFilters(), keyword: " " },
        20,
        null,
      ),
    /显式指定/,
  );
  assert.equal(
    new URLSearchParams(
      pageQuery("journals", 2026, emptyPageFilters(), 20, null),
    ).has("keyword"),
    false,
  );
});
test("date pair is real, inclusive and in selected year; resource whitelist excludes unrelated form defaults", () => {
  for (const change of [
    { dateFrom: "2026-01-01", dateTo: "" },
    { dateFrom: "2025-12-31", dateTo: "2026-01-01" },
    { dateFrom: "2026-02-29", dateTo: "2026-03-01" },
    { dateFrom: "2026-06-01", dateTo: "2026-05-01" },
  ])
    assert.throws(() =>
      pageQuery(
        "journals",
        2026,
        { ...emptyPageFilters(), ...change },
        20,
        null,
      ),
    );
  assert.doesNotThrow(() =>
    pageQuery(
      "journals",
      2028,
      { ...emptyPageFilters(), dateFrom: "2028-02-29", dateTo: "2028-02-29" },
      100,
      null,
    ),
  );
  const q = new URLSearchParams(
    pageQuery(
      "ledger",
      2026,
      {
        ...emptyPageFilters(),
        accountId: id,
        keyword: "ignored",
        status: "POSTED",
        sql: "forged",
      },
      20,
      null,
    ),
  );
  assert.deepEqual([...q.keys()], ["limit", "accountId"]);
});
test("UI page sizes are bounded choices and opaque next/previous cursor is roundtripped without decoding or numeric reconstruction", () => {
  for (const limit of [20, 50, 100])
    assert.equal(
      new URLSearchParams(
        pageQuery("journals", 2026, emptyPageFilters(), limit, "opaque+/=&"),
      ).get("cursor"),
      "opaque+/=&",
    );
  for (const limit of [0, 1, 200, 1000000])
    assert.throws(() =>
      pageQuery("journals", 2026, emptyPageFilters(), limit, null),
    );
  assert.throws(() =>
    pageQuery("journals", 2026, emptyPageFilters(), 20, "a".repeat(4001)),
  );
  assert.throws(
    () => pageQuery("ledger", 2026, emptyPageFilters(), 20, null),
    /真实科目/,
  );
});
test("technical bigint counters retain exact values above2^53 and never accept aliases, overflow or Number input", () => {
  for (const value of [
    "0",
    "9007199254740992",
    "9007199254740993",
    "9223372036854775807",
  ])
    assert.equal(canonicalPageCounter(value), true);
  for (const value of [
    "00",
    "01",
    "+1",
    " 1",
    "1e3",
    "-1",
    "9223372036854775808",
    1,
  ])
    assert.equal(canonicalPageCounter(value), false);
  const newer = {
    ...page,
    asOf: { ...page.asOf, generation: "9007199254740993" },
  };
  assert.notEqual(pageAsOfKey(page), pageAsOfKey(newer));
  assert.throws(
    () =>
      checkFinancePage(newer, {
        ...context,
        expectedContext: page.contextDigest,
        expectedAsOf: pageAsOfKey(page),
      }),
    /LIST_PAGE_STALE/,
  );
});
test("foreign scope, resource or changed context invalidates whole page; unknown total and empty filter page never become zero total", () => {
  assert.equal(checkFinancePage(page, context), page);
  assert.match(unknownPageTotal(page.totalCount), /不是0/);
  for (const changed of [
    { scope: { ...page.scope, bookId: yearId } },
    { scope: { ...page.scope, yearId: id } },
    { contextDigest: "c".repeat(64) },
  ])
    assert.throws(
      () =>
        checkFinancePage(
          { ...page, ...changed },
          { ...context, expectedContext: page.contextDigest },
        ),
      /LIST_PAGE_STALE/,
    );
  assert.throws(
    () => checkFinancePage({ ...page, totalCount: 0 }, context),
    /边界不符/,
  );
  assert.throws(
    () =>
      checkFinancePage({ ...page, hasNext: true, nextCursor: null }, context),
    /游标协议/,
  );
});
test("same journal/account multiple lines have distinct keys; ledger old six-place amount and null sequence are preserved", () => {
  const lines = [
    {
      journalId: id,
      lineNo: 1,
      debit: "34.560001",
      credit: "0.000000",
      postingSeq: null,
    },
    {
      journalId: id,
      lineNo: 2,
      debit: "10.000000",
      credit: "0.000000",
      postingSeq: null,
    },
  ];
  assert.equal(new Set(lines.map(ledgerLineKey)).size, 2);
  assert.equal(lines[0].debit, "34.560001");
  assert.equal(lines[0].postingSeq, null);
  const ledger = {
    ...page,
    resource: "ledger",
    scope: { ...page.scope, accountId: id },
    asOf: {
      readEpoch: id,
      year: {
        postingSeq: "9007199254740993",
        openingVersion: "0",
        baselineVersion: "0",
        sourceCloseVersion: "0",
        legacyUnsequencedFacts: true,
      },
      accountDigest: "d".repeat(64),
      authorizationContextDigest: "a".repeat(64),
    },
    items: lines,
    returnedCount: 2,
  };
  assert.equal(
    checkFinancePage(ledger, { ...context, resource: "ledger", accountId: id }),
    ledger,
  );
});
test("retiring every page actively cancels/removes the entire resource cache; repeated traversal cannot retain all prior cursor keys", () => {
  assert.equal(pageCachePolicy.gcTime, 0);
  assert.equal(pageCachePolicy.retry, false);
  const prefix = ["finance", id, yearId, "journals-pages", "auth-A"],
    unrelated = ["finance", id, yearId, "ledger-pages", "auth-A"];
  const entries = new Map([[JSON.stringify(unrelated), "other-resource"]]);
  const order = [];
  const matches = (key, queryKey) => {
    const path = JSON.parse(key);
    return queryKey.every((part, i) => part === path[i]);
  };
  const cache = {
    cancelQueries: async ({ queryKey }) => {
      order.push("cancel");
    },
    removeQueries: ({ queryKey }) => {
      order.push("remove");
      for (const key of entries.keys())
        if (matches(key, queryKey)) entries.delete(key);
    },
  };
  for (let i = 0; i < 10000; i++) {
    forgetPageQueries(cache, prefix);
    entries.set(
      JSON.stringify([...prefix, { cursor: `opaque-${i}` }]),
      "current",
    );
    assert.equal(entries.size, 2);
  }
  assert.deepEqual(order.slice(0, 2), ["cancel", "remove"]);
  assert.equal(entries.get(JSON.stringify(unrelated)), "other-resource");
  forgetPageQueries(cache, prefix);
  assert.equal(entries.size, 1);
});
test("ordinary sensitive resets preserve readable tab intent; hidden owner tabs still fall back without retaining content", () => {
  for (const tab of [
    "ledger",
    "cash-ledger-coverage",
    "cash-scope",
    "journals",
  ])
    assert.equal(tabAfterSensitiveReset(tab, false), tab);
  for (const tab of ["audit", "source-events", "outbox"]) {
    assert.equal(tabAfterSensitiveReset(tab, true), tab);
    assert.equal(tabAfterSensitiveReset(tab, false), "journals");
  }
});
