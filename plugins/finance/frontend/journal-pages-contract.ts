export type PagedJournalQuery = {
  status?: "DRAFT" | "SUBMITTED" | "APPROVED" | "POSTED" | "CANCELLED";
  dateFrom?: string;
  dateTo?: string;
  keyword?: string;
  limit?: number;
  cursor?: string;
};
export type PagedLedgerQuery = {
  accountId: string;
  dateFrom?: string;
  dateTo?: string;
  limit?: number;
  cursor?: string;
};
export type PagedJournalScope = {
  tenantId: string;
  legalEntityId: string;
  bookId: string;
  yearId: string;
  precisionProfile: "V4" | "LEGACY_V3";
  precisionReviewRequired: boolean;
  yearStatus: "OPEN" | "CLOSING" | "CLOSED_PENDING_ARCHIVE" | "CLOSED";
  periodStateDigest: string;
  dateFrom: string;
  dateTo: string;
};
export type PagedLedgerScope = {
  tenantId: string;
  legalEntityId: string;
  bookId: string;
  yearId: string;
  precisionProfile: "V4" | "LEGACY_V3";
  precisionReviewRequired: boolean;
  yearStatus: "OPEN" | "CLOSING" | "CLOSED_PENDING_ARCHIVE" | "CLOSED";
  periodStateDigest: string;
  dateFrom: string;
  dateTo: string;
  accountId: string;
  accountCode: string;
  accountName: string;
  accountEnabled: boolean;
  accountDigest: string;
};
export type PagedPostedWatermark = {
  postingSeq: string;
  openingVersion: string;
  baselineVersion: string;
  sourceCloseVersion: string;
  legacyUnsequencedFacts: boolean;
};
export type PagedJournalAsOf = {
  readEpoch: string;
  generation: string;
  authorizationContextDigest: string;
};
export type PagedLedgerAsOf = {
  readEpoch: string;
  year: PagedPostedWatermark;
  accountDigest: string;
  authorizationContextDigest: string;
};
export type PagedJournalSummary = {
  id: string;
  date: string;
  summary: string;
  type:
    | "NORMAL"
    | "REVERSAL"
    | "PROFIT_CLOSE"
    | "YEAR_END"
    | "AP_EXPENSE"
    | "AP_EXPENSE_REVERSAL"
    | "AR_REVENUE"
    | "AR_RECEIPT"
    | "AR_ALLOCATION"
    | "AR_REVERSAL"
    | "ASSET_ACQUIRE"
    | "ASSET_DEPRECIATE"
    | "ASSET_DISPOSE"
    | "ASSET_REVERSE"
    | "ASSET_CORRECT_DEPRECIATION";
  status: "DRAFT" | "SUBMITTED" | "APPROVED" | "POSTED" | "CANCELLED";
  version: number;
  createdBy: string;
  approvedBy: string | null;
  number: number | null;
  periodId: string;
};
export type PagedLedgerLine = {
  journalId: string;
  lineNo: number;
  accountId: string;
  date: string;
  number: number | null;
  summary: string;
  debit: string;
  credit: string;
  amountFormatVersion: "LEGACY_6" | "V4_8";
  postingSeq: string | null;
  postingDigest: string | null;
  journalVersion: number;
};
export type PagedJournalPage = {
  schemaVersion: "journal-ledger-pages/1";
  resource: "journals";
  scope: PagedJournalScope;
  asOf: PagedJournalAsOf;
  contextDigest: string;
  items: Array<PagedJournalSummary>;
  returnedCount: number;
  nextCursor: string | null;
  previousCursor: string | null;
  hasNext: boolean;
  hasPrevious: boolean;
  totalCount: null;
  totalStatus: "NOT_COMPUTED";
  limitations: Array<string>;
};
export type PagedLedgerPage = {
  schemaVersion: "journal-ledger-pages/1";
  resource: "ledger";
  scope: PagedLedgerScope;
  asOf: PagedLedgerAsOf;
  contextDigest: string;
  items: Array<PagedLedgerLine>;
  returnedCount: number;
  nextCursor: string | null;
  previousCursor: string | null;
  hasNext: boolean;
  hasPrevious: boolean;
  totalCount: null;
  totalStatus: "NOT_COMPUTED";
  limitations: Array<string>;
};
export type FinancePageResource = "journals" | "ledger";
export type PageFilters = {
  status: string;
  dateFrom: string;
  dateTo: string;
  keyword: string;
  accountId: string;
};
export const emptyPageFilters = (): PageFilters => ({
  status: "",
  dateFrom: "",
  dateTo: "",
  keyword: "",
  accountId: "",
});
export const pageCachePolicy = {
  gcTime: 0,
  retry: false,
  staleTime: Infinity,
  refetchOnWindowFocus: false,
  refetchOnReconnect: false,
} as const;
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
function dateInYear(value: string, year: number) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number(value.slice(0, 4)) !== year)
    return false;
  const date = new Date(`${value}T00:00:00Z`);
  return (
    Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
  );
}
export function pageQuery(
  resource: FinancePageResource,
  year: number,
  filters: PageFilters,
  limit: number,
  cursor: string | null,
) {
  if (![20, 50, 100].includes(limit))
    throw new Error("每页请选择20、50或100条");
  if (Boolean(filters.dateFrom) !== Boolean(filters.dateTo))
    throw new Error("日期起止须同时明确填写或同时留空");
  if (
    filters.dateFrom &&
    (!dateInYear(filters.dateFrom, year) ||
      !dateInYear(filters.dateTo, year) ||
      filters.dateFrom > filters.dateTo)
  )
    throw new Error("日期必须是当前年度内真实起止日期，且起日不晚于止日");
  const q = new URLSearchParams({ limit: String(limit) });
  if (filters.dateFrom) {
    q.set("dateFrom", filters.dateFrom);
    q.set("dateTo", filters.dateTo);
  }
  if (resource === "journals") {
    if (
      filters.status &&
      !["DRAFT", "SUBMITTED", "APPROVED", "POSTED", "CANCELLED"].includes(
        filters.status,
      )
    )
      throw new Error("凭证状态无效");
    if (filters.keyword.length > 100)
      throw new Error("关键词最多100个UTF-16代码单元");
    if (filters.keyword !== "" && !filters.dateFrom)
      throw new Error("非空关键词必须显式指定同年度日期起止，空格也是文字");
    if (filters.status) q.set("status", filters.status);
    if (filters.keyword !== "") q.set("keyword", filters.keyword);
  } else if (resource === "ledger") {
    if (!uuid.test(filters.accountId))
      throw new Error("请选择当前年度真实科目，历史停用科目也可查阅");
    q.set("accountId", filters.accountId);
  } else throw new Error("分页资源无效");
  if (cursor !== null) {
    if (!cursor || cursor.length > 4000)
      throw new Error("分页游标不可用，请重新读取首屏");
    q.set("cursor", cursor);
  }
  return q.toString();
}
export function canonicalPageCounter(value: string) {
  return (
    typeof value === "string" &&
    /^(0|[1-9][0-9]{0,18})$/.test(value) &&
    BigInt(value) <= 9223372036854775807n
  );
}
export function ledgerLineKey(
  line: Pick<PagedLedgerLine, "journalId" | "lineNo">,
) {
  return `${line.journalId}:${line.lineNo}`;
}
export function checkFinancePage(
  data: PagedJournalPage | PagedLedgerPage,
  context: {
    resource: FinancePageResource;
    bookId: string;
    yearId: string;
    accountId: string;
    expectedContext: string | null;
    expectedAsOf?: string | null;
  },
) {
  if (
    data.schemaVersion !== "journal-ledger-pages/1" ||
    data.resource !== context.resource ||
    data.totalCount !== null ||
    data.totalStatus !== "NOT_COMPUTED"
  )
    throw new Error("分页响应边界不符，未展示数据");
  if (
    data.scope.bookId !== context.bookId ||
    data.scope.yearId !== context.yearId ||
    (data.resource === "ledger" &&
      data.scope.accountId !== context.accountId) ||
    !/^[a-f0-9]{64}$/.test(data.contextDigest) ||
    (context.expectedContext !== null &&
      data.contextDigest !== context.expectedContext)
  )
    throw new Error("LIST_PAGE_STALE");
  if (
    context.expectedAsOf != null &&
    pageAsOfKey(data) !== context.expectedAsOf
  )
    throw new Error("LIST_PAGE_STALE");
  const counters =
    data.resource === "journals"
      ? [data.asOf.generation]
      : [
          data.asOf.year.postingSeq,
          data.asOf.year.openingVersion,
          data.asOf.year.baselineVersion,
          data.asOf.year.sourceCloseVersion,
        ];
  if (counters.some((value) => !canonicalPageCounter(value)))
    throw new Error("技术水位不是有效精确字符串，请联系管理员");
  if (
    data.items.length > 100 ||
    data.returnedCount !== data.items.length ||
    data.hasNext !== (data.nextCursor !== null) ||
    data.hasPrevious !== (data.previousCursor !== null)
  )
    throw new Error("分页行数或游标协议不一致，请重新读取");
  return data;
}
export function pageAsOfKey(data: PagedJournalPage | PagedLedgerPage) {
  return data.resource === "journals"
    ? JSON.stringify([
        data.asOf.readEpoch,
        data.asOf.generation,
        data.asOf.authorizationContextDigest,
      ])
    : JSON.stringify([
        data.asOf.readEpoch,
        data.asOf.year.postingSeq,
        data.asOf.year.openingVersion,
        data.asOf.year.baselineVersion,
        data.asOf.year.sourceCloseVersion,
        data.asOf.year.legacyUnsequencedFacts,
        data.asOf.accountDigest,
        data.asOf.authorizationContextDigest,
      ]);
}
export function unknownPageTotal(value: null) {
  return value === null
    ? "总数未计算（不是0，也不是当前页条数）"
    : "总数状态未知";
}
export function tabAfterSensitiveReset(
  current: string,
  canManageOwner: boolean,
) {
  return ["audit", "source-events", "outbox"].includes(current) &&
    !canManageOwner
    ? "journals"
    : current;
}
export function forgetPageQueries(
  cache: {
    cancelQueries: (filters: {
      queryKey: readonly unknown[];
    }) => Promise<unknown>;
    removeQueries: (filters: { queryKey: readonly unknown[] }) => void;
  },
  prefix: readonly unknown[],
) {
  void cache.cancelQueries({ queryKey: prefix });
  cache.removeQueries({ queryKey: prefix });
}
