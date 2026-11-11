import {
  closingResourceMatches,
  closingWriteScopeDenied,
  closingDraftKey,
} from "./profit-closing-rules";
import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { api, ApiError, mutate } from "@/platform/api/client";
import {
  EvidenceRequestOwner,
  evidenceRequestIdentity,
} from "./evidence-request-state";
const ClosingRequests = createContext<{
  owner: EvidenceRequestOwner;
  identity: string;
  available: boolean;
  scope: string;
  authHint: string;
  drafts: Map<string, Record<string, unknown>>;
  onActorChanged: () => void;
} | null>(null);
export function ProfitClosingBoundary({
  scope,
  authHint,
  accountId,
  personId,
  available,
  onActorChanged,
  children,
}: {
  scope: string;
  authHint: string;
  accountId: string;
  personId: string | null;
  available: boolean;
  onActorChanged: () => void;
  children: ReactNode;
}) {
  const owner = useRef(new EvidenceRequestOwner()),
    drafts = useRef(new Map<string, Record<string, unknown>>());
  const identity = evidenceRequestIdentity(scope, authHint, {
    accountId,
    personId,
  });
  useEffect(() => {
    drafts.current.clear();
    if (available) owner.current.bind(identity);
    else owner.current.clear();
  }, [identity, available]);
  useEffect(
    () => () => {
      owner.current.clear();
      drafts.current.clear();
    },
    [],
  );
  return (
    <ClosingRequests.Provider
      value={{
        owner: owner.current,
        identity,
        available,
        scope,
        authHint,
        drafts: drafts.current,
        onActorChanged,
      }}
    >
      {children}
    </ClosingRequests.Provider>
  );
}
export const closingErrors: Record<string, string> = {
  PROFIT_CLOSING_UNSUPPORTED_KIND: "当前阶段未支持，请选择受控结转类型。",
  PROFIT_CLOSING_SOURCE_UNSUPPORTED:
    "当前来源存在本片不支持的账务条件，未生成分录；请核对检查说明。",
  PROFIT_CLOSING_LIMIT_EXCEEDED:
    "本次来源或生成行超过受控同步范围，未读取成功；不能按部分结果执行。",
  PROFIT_CLOSING_STATE_CONFLICT:
    "运行状态不允许此操作，请显式读取当前真实运行。",
  JOURNAL_PERSON_MODE_REQUIRED:
    "此入口只支持已启用人员审签的V4年度，不能沿用旧账号协议。",
  JOURNAL_PERSON_IDENTITY_UNAVAILABLE:
    "当前普通业务人员绑定不可用；目录治理权限不代替有效业务身份。",
  IDEMPOTENCY_CONFLICT:
    "原请求键已绑定其他正文，请核对原操作与权威运行，不自动更换键绕过。",
  VERSION_CONFLICT: "当前版本已变化，请重新读取并核对；原正文请求键仍保留。",
  PERIOD_CLOSED:
    "此期间已关闭，不能生成新结转或记账；仍可按实际权限处理未记账退出。",
  SCOPE_DENIED: "当前账套或年度读取授权已变化，请重新选择允许范围。",
  PROFIT_CLOSING_SOURCE_STALE:
    "来源账务已变化，旧预览或批准不能产生新记账。请显式读取并按可用退出动作处理。",
  PROFIT_CLOSING_SOURCE_ALREADY_CLAIMED:
    "该来源已有结转尝试，请读取对应运行；已终结后重建须明确选择原尝试与新操作标识。",
  PROFIT_CLOSING_RUN_PENDING:
    "本年度已有未完成结转，请先读取并处理，不会自动创建第二笔。",
  PROFIT_CLOSING_NO_MOVEMENT: "当前真实余额没有待结转金额，不生成零金额凭证。",
  PROFIT_CLOSING_APPROVAL_STALE:
    "批准或当前身份资格已变化，请重新读取真实证明。",
  PROFIT_CLOSING_APPROVAL_EXPIRED:
    "批准已过期，请按可用退出动作处理后重新审核。",
  PROFIT_CLOSING_REVERSAL_DEPENDENCY:
    "存在后续有效结转依赖，须按最近有效结转优先处理；不能反向再反向。",
  PROFIT_CLOSING_PAGE_STALE:
    "列表或授权水位已变化，旧页不可续读，请显式重新读取首屏。",
  PROFIT_CLOSING_IDENTITY_CHANGED:
    "普通业务人员身份已变化，旧证明已隐藏；正在重新核对年度身份，请显式重读后处理。",
  SELF_APPROVAL_FORBIDDEN: "须独立于本运行制备和编辑人员；另一账号也不能自审。",
  PERSON_AUTH_ASSURANCE_REQUIRED:
    "须最近5分钟通过真实登录及TOTP，请正常重新登录后重试，不在业务表单填写认证证明。",
  QUERY_BUDGET_EXCEEDED:
    "本次未读取成功，请显式重读或缩小合法范围；未知不代表零。",
};
export function useProfitClosingResource<T>(
  path: string,
  active: boolean,
  revision: number,
  onReadDenied: () => void,
  readable: (e: unknown) => string,
) {
  const context = useContext(ClosingRequests),
    identity = context?.identity ?? "",
    available = context?.available ?? false;
  const [stored, setStored] = useState<{
      path: string;
      revision: number;
      identity: string;
      available: boolean;
      value: T;
    } | null>(null),
    [failure, setFailure] = useState<{
      identity: string;
      message: string;
    } | null>(null),
    [loading, setLoading] = useState(false),
    [writing, setWriting] = useState(false);
  const alive = useRef(false),
    epoch = useRef(0),
    controllers = useRef(new Set<AbortController>()),
    live = useRef({ path, active, revision, identity, available }),
    callbacks = useRef({ onReadDenied, readable });
  live.current = { path, active, revision, identity, available };
  useEffect(() => {
    callbacks.current = { onReadDenied, readable };
  }, [onReadDenied, readable]);
  function message(e: unknown) {
    return e instanceof Error && closingErrors[e.message]
      ? closingErrors[e.message]
      : callbacks.current.readable(e);
  }
  function stop() {
    epoch.current++;
    for (const c of controllers.current) c.abort();
    controllers.current.clear();
  }
  function current(version: number, id: string) {
    return (
      alive.current && epoch.current === version && live.current.identity === id
    );
  }
  function denied() {
    setStored(null);
    context?.owner.clear();
    context?.drafts.clear();
    callbacks.current.onReadDenied();
  }
  function verifyActor(value: unknown) {
    if (
      !context ||
      !value ||
      typeof value !== "object" ||
      !("currentActor" in value)
    )
      return;
    const actor = value.currentActor;
    if (
      !actor ||
      typeof actor !== "object" ||
      !("accountId" in actor) ||
      typeof actor.accountId !== "string" ||
      !("personId" in actor) ||
      !("identityAvailability" in actor)
    )
      return;
    const actorIdentity = evidenceRequestIdentity(
      context.scope,
      context.authHint,
      {
        accountId: actor.accountId,
        personId: typeof actor.personId === "string" ? actor.personId : null,
      },
    );
    if (
      actorIdentity !== identity ||
      (actor.identityAvailability === "AVAILABLE") !== available
    ) {
      context.owner.clear();
      context.drafts.clear();
      context.onActorChanged();
      throw new Error("PROFIT_CLOSING_IDENTITY_CHANGED");
    }
  }
  async function read() {
    const q = live.current;
    if (!q.active || !q.path) return;
    stop();
    setWriting(false);
    setStored(null);
    setFailure(null);
    setLoading(true);
    const version = epoch.current,
      c = new AbortController();
    controllers.current.add(c);
    try {
      const value = await api<T>(q.path, { signal: c.signal });
      if (
        current(version, q.identity) &&
        live.current.path === q.path &&
        live.current.revision === q.revision &&
        live.current.available === q.available
      ) {
        verifyActor(value);
        setStored({ ...q, value });
      }
    } catch (e) {
      if (current(version, q.identity)) {
        setFailure({
          identity: q.identity,
          message:
            message(e) + " 未读取成功，旧详情与计数已隐藏，请显式重新读取。",
        });
        if (e instanceof ApiError && e.status === 403) denied();
      }
    } finally {
      controllers.current.delete(c);
      if (current(version, q.identity)) setLoading(false);
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
    setFailure(null);
    setLoading(false);
    setWriting(false);
    if (path && active) void read();
  }, [path, active, revision, identity, available]);
  async function write<R>(
    commandPath: string,
    payload: Record<string, unknown>,
  ): Promise<R | null> {
    if (
      !alive.current ||
      !active ||
      !context ||
      !live.current.active ||
      live.current.identity !== identity ||
      live.current.available !== available
    )
      return null;
    const { owner } = context,
      lease = owner.acquire(identity);
    if (!lease) {
      setFailure({
        identity,
        message: "另一个请求尚未结束，请稍后重试原内容。",
      });
      return null;
    }
    const fingerprint = JSON.stringify([commandPath, "POST", payload]),
      idempotencyKey = owner.key(identity, fingerprint, () =>
        crypto.randomUUID(),
      ),
      version = epoch.current,
      c = new AbortController();
    controllers.current.add(c);
    setWriting(true);
    setFailure(null);
    try {
      const result = await mutate<R>(
        commandPath,
        "POST",
        { ...payload, idempotencyKey },
        c.signal,
      );
      if (
        !current(version, identity) ||
        !owner.isCurrent(lease) ||
        !live.current.active
      )
        return null;
      owner.succeeded(identity, fingerprint);
      return result;
    } catch (e) {
      if (current(version, identity) && owner.isCurrent(lease)) {
        if (
          e instanceof ApiError &&
          closingWriteScopeDenied(e.status, e.message)
        )
          denied();
        else
          setFailure({
            identity,
            message:
              message(e) +
              " 结果可能未知；正文与原请求键保留，请重试原内容或显式读取权威状态，不会自动更换操作标识。",
          });
      }
      return null;
    } finally {
      owner.release(lease);
      controllers.current.delete(c);
      if (current(version, identity)) setWriting(false);
    }
  }
  return {
    data:
      active &&
      stored &&
      closingResourceMatches(stored, path, revision, identity, available)
        ? stored.value
        : null,
    error: failure?.identity === identity ? failure.message : "",
    loading,
    writing: active && writing,
    read,
    write,
  };
}
export function useProfitClosingDrafts() {
  const context = useContext(ClosingRequests);
  const scoped = (key: string) =>
    closingDraftKey(context?.identity, context?.available, key);
  return {
    get: (key: string) => context?.drafts.get(scoped(key)),
    save: (key: string, values: Record<string, unknown>) => {
      if (!context) return;
      const k = scoped(key);
      context.drafts.delete(k);
      while (context.drafts.size >= 20) {
        const first = context.drafts.keys().next().value;
        if (first === undefined) break;
        context.drafts.delete(first);
      }
      context.drafts.set(k, { ...values });
    },
    clear: (key: string) => context?.drafts.delete(scoped(key)),
  };
}

export function useProfitClosingIdentity() {
  const context = useContext(ClosingRequests);
  return JSON.stringify([context?.identity, context?.available]);
}
