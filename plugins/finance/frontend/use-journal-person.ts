import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, mutate } from "@/platform/api/client";
import { directoryRequestKey } from "./person-directory-rules";
import type { Mode } from "./journal-person-contract";
import { JournalPersonWriteGate } from "./journal-person-rules";
const messages: Record<string, string> = {
  JOURNAL_PERSON_MODE_REQUIRED:
    "本年度须使用凭证人员审签协议，请重新读取模式与当前凭证。",
  JOURNAL_PERSON_YEAR_NOT_EMPTY:
    "该年度已有财务事实或年结依赖，不能直接启用人员审签；旧历史不会补核。",
  JOURNAL_PERSON_MODE_STALE: "年度模式或申请版本已变化，请重新读取。",
  JOURNAL_PERSON_IDENTITY_UNAVAILABLE:
    "当前账号没有可用的组织人员绑定，请联系组织治理负责人核对。业务岗位不会自动授予人员身份。",
  JOURNAL_PERSON_APPROVAL_STALE:
    "批准所绑定的内容或当前身份资格已变化，请重新读取并核对。",
  JOURNAL_PERSON_APPROVAL_EXPIRED:
    "该批准已过期，不能据此记账；请重新读取并完成新的独立审签。",
  JOURNAL_PERSON_YEAR_CLOSE_UNSUPPORTED:
    "来源或目标年度已启用凭证人员审签，年结人员审批协议尚未支持。",
  PERSON_AUTH_ASSURANCE_REQUIRED:
    "此操作要求最近5分钟内完成最终登录并通过TOTP，请通过平台重新登录后重试；不在业务表单填写验证码。",
  SELF_APPROVAL_FORBIDDEN:
    "审核或行政终结必须独立于冻结的制单及编辑人员，同一人员的另一账号也不能处理。",
};
export function journalPersonReadable(
  error: unknown,
  fallback: (error: unknown) => string,
) {
  return error instanceof Error && messages[error.message]
    ? messages[error.message]
    : fallback(error);
}
export function useJournalPersonMode(
  bookId: string,
  yearId: string,
  authHint: string,
  canRead: boolean,
  onReadDenied: () => void,
) {
  const client = useQueryClient(),
    denied = useRef(onReadDenied);
  useEffect(() => {
    denied.current = onReadDenied;
  }, [onReadDenied]);
  const prefix = ["finance", bookId, yearId, "journal-person-mode", authHint];
  const query = useQuery({
    queryKey: prefix,
    enabled: !!bookId && !!yearId && canRead,
    retry: false,
    gcTime: 0,
    refetchOnWindowFocus: false,
    queryFn: ({ signal }) =>
      api<Mode>(
        `/finance/v1/books/${bookId}/years/${yearId}/journal-person-sod`,
        { signal },
      ),
  });
  useEffect(
    () => () => {
      void client.cancelQueries({ queryKey: prefix });
      client.removeQueries({ queryKey: prefix });
    },
    [bookId, yearId, authHint, client],
  );
  useEffect(() => {
    if (
      query.isError &&
      query.error instanceof ApiError &&
      query.error.status === 403
    )
      denied.current();
  }, [query.isError, query.error]);
  const mode =
    !query.isError &&
    query.data?.bookId === bookId &&
    query.data.yearId === yearId
      ? query.data
      : undefined;
  return {
    ...query,
    mode,
    ready: canRead && !!mode && !query.isFetching && !query.isError,
  };
}
/** Only the current resource is retained; the caller keys its component by scope/auth. */
export function useJournalPersonResource<T>(
  path: string,
  active: boolean,
  onReadDenied: () => void,
  readable: (error: unknown) => string,
) {
  const [data, setData] = useState<{ path: string; value: T } | null>(null),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(false),
    [writing, setWriting] = useState(false);
  const alive = useRef(false),
    epoch = useRef(0),
    controllers = useRef(new Set<AbortController>()),
    keys = useRef(new Map<string, string>()),
    writeLock = useRef(new JournalPersonWriteGate()),
    callbacks = useRef({ onReadDenied, readable });
  useEffect(() => {
    callbacks.current = { onReadDenied, readable };
  }, [onReadDenied, readable]);
  function stop() {
    epoch.current++;
    for (const controller of controllers.current) controller.abort();
    controllers.current.clear();
  }
  async function read() {
    if (!path) return;
    stop();
    const revision = epoch.current;
    setData(null);
    setError("");
    setLoading(true);
    const controller = new AbortController();
    controllers.current.add(controller);
    try {
      const value = await api<T>(path, { signal: controller.signal });
      if (alive.current && revision === epoch.current) setData({ path, value });
    } catch (cause) {
      if (alive.current && revision === epoch.current) {
        setError(journalPersonReadable(cause, callbacks.current.readable));
        if (cause instanceof ApiError && cause.status === 403)
          callbacks.current.onReadDenied();
      }
    } finally {
      controllers.current.delete(controller);
      if (alive.current && revision === epoch.current) setLoading(false);
    }
  }
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      stop();
      keys.current.clear();
    };
  }, []);
  useEffect(() => {
    stop();
    setData(null);
    setError("");
    setLoading(false);
    setWriting(false);
    if (active && path) void read();
  }, [path, active]);
  async function write<R>(
    commandPath: string,
    payload: Record<string, unknown>,
  ): Promise<R | null> {
    if (!active) return null;
    const token = writeLock.current.acquire();
    if (!token) return null;
    setWriting(true);
    setError("");
    const revision = epoch.current;
    const controller = new AbortController();
    controllers.current.add(controller);
    const fingerprint = JSON.stringify([commandPath, payload]);
    const idempotencyKey = directoryRequestKey(keys.current, fingerprint, () =>
      crypto.randomUUID(),
    );
    try {
      const value = await mutate<R>(
        commandPath,
        "POST",
        { ...payload, idempotencyKey },
        controller.signal,
      );
      if (!alive.current || revision !== epoch.current) return null;
      keys.current.delete(fingerprint);
      return value;
    } catch (cause) {
      if (alive.current && revision === epoch.current)
        setError(
          `${journalPersonReadable(cause, callbacks.current.readable)} 请保留原内容重试或显式读取当前状态核对结果。`,
        );
      return null;
    } finally {
      controllers.current.delete(controller);
      writeLock.current.release(token);
      if (alive.current && revision === epoch.current) setWriting(false);
    }
  }
  return {
    data: active && data?.path === path ? data.value : null,
    error,
    loading,
    writing,
    read,
    write,
  };
}
