import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Alert, Button } from "antd";
import { api, ApiError, mutate } from "@/platform/api/client";
import { EvidenceRequestOwner } from "./evidence-request-state";
import { EvidenceWriteContext } from "./evidence-write-context";
import { apActorMatches, apRequestIdentity } from "./payables-ap01-rules";
import type { Actor } from "./payables-ap01-contract";
type RequestContext = {
  scope: string;
  identity: string;
  actor: Actor;
  owner: EvidenceRequestOwner;
  drafts: Map<string, unknown>;
  ready: boolean;
  revision: number;
  denied: () => void;
  failed: (text: string) => void;
  readable: (e: unknown) => string;
  actorChanged: () => void;
  refresh: () => void;
};
const Context = createContext<RequestContext | null>(null);
export function PayablesBoundary({
  scope,
  authHint,
  actor,
  ready,
  revision,
  denied,
  readable,
  actorChanged,
  children,
}: {
  scope: string;
  authHint: string;
  actor: Actor;
  ready: boolean;
  revision: number;
  denied: () => void;
  readable: (e: unknown) => string;
  actorChanged: () => void;
  children: ReactNode;
}) {
  const identity = apRequestIdentity(scope, authHint, actor),
    owner = useRef(new EvidenceRequestOwner()),
    drafts = useRef(new Map<string, unknown>()),
    previous = useRef(identity),
    [failure, setFailure] = useState<{
      identity: string;
      revision: number;
      message: string;
    } | null>(null),
    [retry, setRetry] = useState(0);
  if (previous.current !== identity) {
    previous.current = identity;
    owner.current.clear();
    drafts.current.clear();
  }
  useEffect(
    () => () => {
      owner.current.clear();
      drafts.current.clear();
    },
    [],
  );
  const paused =
    failure?.identity === identity && failure.revision === revision;
  function onDenied() {
    owner.current.clear();
    drafts.current.clear();
    denied();
  }
  return (
    <Context.Provider
      value={{
        scope,
        identity,
        actor,
        owner: owner.current,
        drafts: drafts.current,
        ready: ready && !paused,
        revision: revision + retry,
        denied: onDenied,
        failed: (message) => setFailure({ identity, revision, message }),
        readable,
        actorChanged,
        refresh: () => {
          setFailure(null);
          setRetry((v) => v + 1);
        },
      }}
    >
      <EvidenceWriteContext.Provider value={{ owner: owner.current, identity }}>
        {paused && failure && (
          <Alert
            type="error"
            title={`${failure.message} 未读取成功，旧页及详情已隐藏。`}
            action={
              <Button
                onClick={() => {
                  setFailure(null);
                  setRetry((v) => v + 1);
                }}
              >
                显式重新读取应付首屏
              </Button>
            }
          />
        )}
        {children}
      </EvidenceWriteContext.Provider>
    </Context.Provider>
  );
}
export function useApContext() {
  const c = useContext(Context);
  if (!c) throw new Error("AP读取上下文未配置");
  return c;
}
const messages: Record<string, string> = {
  PERSON_AUTH_ASSURANCE_REQUIRED:
    "此操作需要最近5分钟正常登录并完成TOTP，请重新登录后重试原正文。",
  SELF_APPROVAL_FORBIDDEN: "同一自然人不能审核自己的业务，请使用独立审核账号。",
  AP_PAGE_STALE: "应付范围或资料已变化，请显式重新读取首屏。",
  AP_QUERY_BUDGET_EXCEEDED: "本次操作未完成受控读取，不能按零或部分结果继续。",
  AP_SOURCE_STALE:
    "父费用、原件、科目或日期来源已变化，不能自动改绑；请重读当前证明并显式准备新尝试。",
  AP_SUPPLIER_STALE:
    "供应商当前发布资格或版本已变化。历史记账保留，新用须重新核对。",
  AP_BASIS_PENDING:
    "会计或原面额依据正在校正，新提交/批准/记账暂停，合法退出另按权限处理。",
  AP_BASIS_UNAVAILABLE: "没有独立会计确认额度，未知不能按费用上限或零替代。",
  AP_CAPACITY_EXCEEDED:
    "独立会计用途额度不足，请重新读取实际预留与净已记占用。",
  AP_PARENT_IN_USE:
    "父费用正被会计确认依赖占用；须先真实退出或完整会计反向，不能直接减少父份额。",
  AP_PENDING_PARENT_REVERSAL: "父费用已有待审反向，新应付消费暂不可用。",
  AP_APPROVAL_EXPIRED:
    "运营批准已过期；原预留不会由页面自动释放，请按合法退出流程处理。",
  AP_SUBMISSION_EXPIRED: "运营提交已过期；请真实撤回、驳回或终结后再显式准备。",
  AP_SOURCE_ALREADY_CLAIMED:
    "本父份额已有实际尝试或占用，请读取当前来源槽与最新尝试，不能换操作键重复入账。",
  AP_DEPENDENCY_PRESENT: "仍有真实下游依赖，不能执行本次全额会计反向。",
  AP_SOURCE_UNSUPPORTED:
    "本片仅支持同账套年度普通供应商服务费用全额确认及原日全额反向；该来源不在范围内。",
  AP_BUSY: "应付业务暂忙，请显式读取权威状态后重试原正文。",
};
export function useApResource<T>(path: string, active: boolean) {
  const c = useApContext(),
    [stored, setStored] = useState<{
      path: string;
      identity: string;
      revision: number;
      data: T;
    } | null>(null),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(false),
    [writing, setWriting] = useState(false),
    alive = useRef(false),
    epoch = useRef(0),
    controllers = useRef(new Set<AbortController>()),
    live = useRef({ path, active, context: c });
  live.current = { path, active, context: c };
  function stop() {
    epoch.current++;
    for (const a of controllers.current) a.abort();
    controllers.current.clear();
  }
  function current(e: number, id: string, p: string) {
    return (
      alive.current &&
      epoch.current === e &&
      live.current.active &&
      live.current.context.ready &&
      live.current.context.identity === id &&
      live.current.context.revision === c.revision &&
      live.current.path === p
    );
  }
  function failure(
    e: unknown,
    version: number,
    id: string,
    p: string,
    reading: boolean,
  ) {
    if (!current(version, id, p)) return;
    const text =
      e instanceof Error && messages[e.message]
        ? messages[e.message]
        : live.current.context.readable(e);
    setError(text);
    if (reading) {
      setStored(null);
      live.current.context.failed(text);
    }
    if (
      e instanceof ApiError &&
      e.status === 403 &&
      (reading || e.message === "SCOPE_DENIED")
    )
      live.current.context.denied();
  }
  async function read() {
    const q = live.current;
    if (!q.active || !q.context.ready || !q.path) return;
    stop();
    setWriting(false);
    setStored(null);
    setError("");
    setLoading(true);
    const v = epoch.current,
      a = new AbortController();
    controllers.current.add(a);
    try {
      const data = await api<T>(q.path, {
        signal: a.signal,
        cache: "no-store",
      });
      if (!current(v, q.context.identity, q.path)) return;
      if (data && typeof data === "object" && "currentActor" in data) {
        const actor = data.currentActor as Actor;
        if (!apActorMatches(actor, q.context.actor)) {
          q.context.actorChanged();
          throw new Error("读取期间人员身份已变化，请重新读取。");
        }
      }
      setStored({
        path: q.path,
        identity: q.context.identity,
        revision: q.context.revision,
        data,
      });
    } catch (e) {
      failure(e, v, q.context.identity, q.path, true);
    } finally {
      controllers.current.delete(a);
      if (current(v, q.context.identity, q.path)) setLoading(false);
    }
  }
  async function write<R>(
    path: string,
    payload: Record<string, unknown>,
  ): Promise<R | null> {
    const q = live.current;
    if (!q.active || !q.context.ready) return null;
    const lease = q.context.owner.acquire(q.context.identity);
    if (!lease) return null;
    const v = epoch.current,
      a = new AbortController(),
      fingerprint = JSON.stringify([path, payload]);
    controllers.current.add(a);
    setWriting(true);
    setError("");
    try {
      const idempotencyKey = q.context.owner.key(
        q.context.identity,
        fingerprint,
        () => crypto.randomUUID(),
      );
      const result = await mutate<R>(
        path,
        "POST",
        { ...payload, idempotencyKey },
        a.signal,
      );
      if (
        !current(v, q.context.identity, q.path) ||
        !q.context.owner.isCurrent(lease)
      )
        return null;
      q.context.owner.succeeded(q.context.identity, fingerprint);
      return result;
    } catch (e) {
      if (q.context.owner.isCurrent(lease))
        failure(e, v, q.context.identity, q.path, false);
      return null;
    } finally {
      controllers.current.delete(a);
      q.context.owner.release(lease);
      if (current(v, q.context.identity, q.path)) setWriting(false);
    }
  }
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      stop();
    };
  }, []);
  useEffect(() => {
    stop();
    setStored(null);
    setError("");
    setLoading(false);
    setWriting(false);
    if (active && c.ready) void read();
    return stop;
  }, [path, active, c.identity, c.ready, c.revision]);
  return {
    data:
      active &&
      c.ready &&
      stored?.identity === c.identity &&
      stored.path === path &&
      stored.revision === c.revision
        ? stored.data
        : null,
    error,
    loading,
    writing,
    read,
    write,
  };
}
