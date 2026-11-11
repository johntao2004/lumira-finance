import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/platform/api/client";
import {
  pageCachePolicy,
  pageQuery,
  checkFinancePage,
  forgetPageQueries,
  pageAsOfKey,
  type PageFilters,
  type FinancePageResource,
  type PagedJournalPage,
  type PagedLedgerPage,
} from "./journal-pages-contract";
type Request = {
  filters: PageFilters;
  limit: number;
  cursor: string | null;
  expectedContext: string | null;
  expectedAsOf: string | null;
  nonce: string;
};
type Props = {
  bookId: string;
  yearId: string;
  year: number;
  resource: FinancePageResource;
  authHint: string;
  active: boolean;
  canRead: boolean;
  onReadDenied: () => void;
  onResetDetails: () => void;
  readable: (error: unknown) => string;
};
const errors: Record<string, string> = {
  LIST_PAGE_STALE:
    "列表事实或授权上下文已变化，旧页和全部游标已移除。请显式重新读取首屏，不拼接旧页。",
  LIST_BASELINE_UNAVAILABLE:
    "分页技术基线不可用，不能当成零条记录。请联系管理员。",
  QUERY_BUDGET_EXCEEDED:
    "查询超过受控预算（单语句3秒 / 读服务10秒），未返回部分页或零结果。请缩小日期范围再重新读取。",
  LIST_VALUE_UNSUPPORTED:
    "记录金额、技术水位或业务整数超出分页协议支持范围，未舍入或跳过记录。请联系管理员。",
};
export function useFinancePages({
  bookId,
  yearId,
  year,
  resource,
  authHint,
  active,
  canRead,
  onReadDenied,
  onResetDetails,
  readable,
}: Props) {
  const client = useQueryClient();
  const prefix = ["finance", bookId, yearId, `${resource}-pages`, authHint];
  const [request, setRequest] = useState<Request | null>(null);
  const [error, setError] = useState("");
  const query = useQuery({
    ...pageCachePolicy,
    queryKey: [...prefix, request],
    enabled: active && canRead && !!bookId && !!yearId && !!request,
    queryFn: async ({ signal }) => {
      const selected = request!;
      const path = resource === "journals" ? "journal-pages" : "ledger-pages";
      const data = await api<PagedJournalPage | PagedLedgerPage>(
        `/finance/v1/books/${bookId}/years/${yearId}/${path}?${pageQuery(resource, year, selected.filters, selected.limit, selected.cursor)}`,
        { signal },
      );
      return checkFinancePage(data, {
        resource,
        bookId,
        yearId,
        accountId: selected.filters.accountId,
        expectedContext: selected.expectedContext,
        expectedAsOf: selected.expectedAsOf,
      });
    },
  });
  function reset(closeDetails = true) {
    forgetPageQueries(client, prefix);
    setRequest(null);
    setError("");
    if (closeDetails) onResetDetails();
  }
  useEffect(
    () => () => {
      forgetPageQueries(client, prefix);
    },
    [bookId, yearId, resource, authHint, client],
  );
  useEffect(() => {
    if (!active || !canRead) reset(false);
  }, [active, canRead]);
  const denied =
    query.isError &&
    query.error instanceof ApiError &&
    (query.error.status === 403 || query.error.message === "SCOPE_DENIED");
  useEffect(() => {
    if (denied) onReadDenied();
  }, [denied, onReadDenied]);
  useEffect(() => {
    if (!query.isError || !request) return;
    const cause = query.error;
    reset();
    setError(
      cause instanceof Error && errors[cause.message]
        ? errors[cause.message]
        : readable(cause),
    );
  }, [query.isError, query.error, request]);
  const page =
    active && canRead && request && !query.isError && !query.isFetching
      ? query.data
      : undefined;
  function read(
    filters: PageFilters,
    limit: number,
    cursor: string | null,
    expectedContext: string | null,
    expectedAsOf: string | null,
  ) {
    if (!active || !canRead || denied || query.isFetching) return;
    try {
      pageQuery(resource, year, filters, limit, cursor);
      reset();
      setRequest({
        filters: { ...filters },
        limit,
        cursor,
        expectedContext,
        expectedAsOf,
        nonce: crypto.randomUUID(),
      });
    } catch (cause) {
      reset();
      setError(readable(cause));
    }
  }
  function first(filters: PageFilters, limit: number) {
    read(filters, limit, null, null, null);
  }
  function move(direction: "next" | "previous") {
    if (!page || !request) return;
    const cursor = direction === "next" ? page.nextCursor : page.previousCursor;
    if (cursor)
      read(
        request.filters,
        request.limit,
        cursor,
        page.contextDigest,
        pageAsOfKey(page),
      );
  }
  return { page, error, loading: query.isFetching, first, move, reset };
}
