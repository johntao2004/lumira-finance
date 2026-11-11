import type { PartyDraft } from "./counterparty-state";
import { useEffect, useRef, useState } from "react";
import { api, ApiError, mutate } from "@/platform/api/client";
import { EvidenceRequestOwner } from "./evidence-request-state";
import {
  partyActorEqual,
  partyIdentity,
  partyConflictReferences,
} from "./counterparty-rules";
import type {
  Actor,
  List,
  CounterpartyDetail,
  RevisionDetail,
  RevisionList,
} from "./counterparty-contract";
export type PartySelection = {
  id: string;
  revisionId: string;
  cursor: string | null;
  revisionCursor: string | null;
  limit: number;
  status: string;
};
export type PartyGroup = {
  list: List;
  detail: CounterpartyDetail | null;
  revisions: RevisionList | null;
  revision: RevisionDetail | null;
};
const emptySelection: PartySelection = {
  id: "",
  revisionId: "",
  cursor: null,
  revisionCursor: null,
  limit: 20,
  status: "ALL",
};
const messages: Record<string, string> = {
  COUNTERPARTY_PAGE_STALE:
    "组织目录或授权已变化，旧页及游标已清理，请重新读取首屏。",
  COUNTERPARTY_BUSY: "组织目录暂忙，请稍后显式重试。",
  COUNTERPARTY_QUERY_BUDGET_EXCEEDED:
    "本次读取或命令未在受控时间内完成，请显式重新读取当前状态；未知结果不会显示为成功或回滚。",
  COUNTERPARTY_LIMIT_EXCEEDED: "本次范围超过受控上限，未取得完整结果。",
  COUNTERPARTY_APPROVAL_STALE:
    "提案或制备人的资格已变化，请读取权威当前状态；可由独立审核人合法驳回未决提案。",
  PERSON_AUTH_ASSURANCE_REQUIRED:
    "此操作要求近期登录并完成TOTP，请通过正常登录流程重新认证后重试。",
  SELF_APPROVAL_FORBIDDEN: "同一自然人不能审核自己的提案，请使用独立审核账号。",
  COUNTERPARTY_HOME_SCOPE_REQUIRED:
    "只能在此组织的治理归属账套提出或处理修订。",
  COUNTERPARTY_IDENTITY_EXISTS:
    "此登记身份已被永久占用，请读取已有组织，不能通过新组织绕过。",
};
export function useCounterparties(
  bookId: string,
  authHint: string,
  active: boolean,
  refreshToken: number,
  onDenied: () => void,
  readable: (e: unknown) => string,
) {
  const scope = `/finance/v1/books/${bookId}/counterparties`,
    boundary = JSON.stringify([scope, authHint]);
  const owner = useRef(new EvidenceRequestOwner()),
    drafts = useRef(new Map<string, PartyDraft>()),
    actor = useRef<Actor | null>(null),
    ownerBoundary = useRef(boundary);
  // A different book or authorization boundary must mask synchronously, before effects.
  if (ownerBoundary.current !== boundary) {
    owner.current.clear();
    drafts.current.clear();
    actor.current = null;
    ownerBoundary.current = boundary;
  }
  const [selection, setSelection] = useState<PartySelection>(emptySelection),
    [stored, setStored] = useState<{
      boundary: string;
      refresh: number;
      group: PartyGroup | null;
      busy: boolean;
      error: string;
    }>({
      boundary,
      refresh: refreshToken,
      group: null,
      busy: false,
      error: "",
    }),
    [writing, setWriting] = useState(false),
    [notice, setNotice] = useState("");
  const alive = useRef(false),
    epoch = useRef(0),
    controllers = useRef(new Set<AbortController>()),
    live = useRef({ boundary, active, scope, refreshToken, selection }),
    callbacks = useRef({ onDenied, readable });
  live.current = { boundary, active, scope, refreshToken, selection };
  callbacks.current = { onDenied, readable };
  function stop() {
    epoch.current++;
    for (const c of controllers.current) c.abort();
    controllers.current.clear();
  }
  function current(e: number, b: string) {
    return (
      alive.current &&
      epoch.current === e &&
      live.current.active &&
      live.current.boundary === b
    );
  }
  function identity() {
    return partyIdentity(scope, authHint, actor.current);
  }
  function fail(error: unknown, e: number, b: string, reading: boolean) {
    if (!current(e, b)) return;
    const message =
      error instanceof Error ? messages[error.message] : undefined;
    const refs =
      error instanceof ApiError
        ? partyConflictReferences(error.status, error.message, error.issues)
        : null;
    setStored((s) => ({
      ...s,
      group: reading ? null : s.group,
      busy: false,
      error:
        (message ?? callbacks.current.readable(error)) +
        (refs
          ? ` 已有组织ID：${refs.counterpartyId}；治理归属账套ID：${refs.governanceHomeBookId}。请在已授权范围按已知ID读取，不会自动合并或跳转。`
          : "") +
        (reading
          ? " 未读取成功，旧组织、修订及数量均不作为当前结果。"
          : " 请先显式读取当前组织和修订状态；原正文及请求键保留。"),
    }));
    if (reading)
      setSelection((s) => ({ ...s, cursor: null, revisionCursor: null }));
    if (
      error instanceof ApiError &&
      error.status === 403 &&
      (reading || error.message === "SCOPE_DENIED")
    ) {
      owner.current.clear();
      drafts.current.clear();
      actor.current = null;
      setStored({
        boundary: b,
        refresh: live.current.refreshToken,
        group: null,
        busy: false,
        error: "当前读取范围已失权，旧组织与修订已清理。",
      });
      callbacks.current.onDenied();
    }
  }
  async function read(next: PartySelection = live.current.selection) {
    const q = live.current;
    if (!q.active) return;
    stop();
    setWriting(false);
    const e = epoch.current,
      c = new AbortController();
    controllers.current.add(c);
    setNotice("");
    setSelection(next);
    setStored({
      boundary: q.boundary,
      refresh: q.refreshToken,
      group: null,
      busy: true,
      error: "",
    });
    try {
      const list = await api<List>(
        `${q.scope}?limit=${next.limit}&status=${encodeURIComponent(next.status)}${next.cursor ? "&cursor=" + encodeURIComponent(next.cursor) : ""}`,
        { signal: c.signal, cache: "no-store" },
      );
      if (list.currentBookId !== bookId)
        throw new Error("读取账套不一致，请重新读取。");
      let detail: CounterpartyDetail | null = null,
        revisions: RevisionList | null = null,
        revision: RevisionDetail | null = null;
      if (next.id) {
        [detail, revisions, revision] = await Promise.all([
          api<CounterpartyDetail>(`${q.scope}/${next.id}`, {
            signal: c.signal,
            cache: "no-store",
          }),
          api<RevisionList>(
            `${q.scope}/${next.id}/revisions?limit=${next.limit}${next.revisionCursor ? "&cursor=" + encodeURIComponent(next.revisionCursor) : ""}`,
            { signal: c.signal, cache: "no-store" },
          ),
          next.revisionId
            ? api<RevisionDetail>(
                `${q.scope}/${next.id}/revisions/${next.revisionId}`,
                { signal: c.signal, cache: "no-store" },
              )
            : Promise.resolve(null),
        ]);
      }
      if (
        detail &&
        revision &&
        detail.counterparty.registryVersion !==
          revision.counterparty.registryVersion
      )
        throw new Error("组织当前版本在读取中变化，请重新读取。");
      for (const d of [detail, revisions, revision])
        if (
          d &&
          (!partyActorEqual(list.currentActor, d.currentActor) ||
            d.scope.currentBookId !== bookId)
        )
          throw new Error("读取期间身份或账套变化，请重新读取。");
      if (!current(e, q.boundary)) return;
      if (actor.current && !partyActorEqual(actor.current, list.currentActor)) {
        owner.current.clear();
        drafts.current.clear();
      }
      actor.current = list.currentActor;
      owner.current.bind(partyIdentity(q.scope, authHint, list.currentActor));
      setStored({
        boundary: q.boundary,
        refresh: q.refreshToken,
        group: { list, detail, revisions, revision },
        busy: false,
        error: "",
      });
    } catch (error) {
      fail(error, e, q.boundary, true);
    } finally {
      controllers.current.delete(c);
    }
  }
  async function write<T extends CounterpartyDetail | RevisionDetail>(
    path: string,
    payload: Record<string, unknown>,
    after: (response: T) => PartySelection,
  ) {
    const q = live.current;
    if (
      !q.active ||
      !actor.current ||
      stored.busy ||
      stored.boundary !== q.boundary ||
      stored.refresh !== q.refreshToken
    )
      return;
    const keyIdentity = identity(),
      lease = owner.current.acquire(keyIdentity);
    if (!lease) return;
    const e = epoch.current,
      c = new AbortController(),
      fingerprint = JSON.stringify([path, payload]);
    controllers.current.add(c);
    setWriting(true);
    setNotice("");
    setStored((s) => ({ ...s, error: "" }));
    try {
      const idempotencyKey = owner.current.key(keyIdentity, fingerprint, () =>
        crypto.randomUUID(),
      );
      const response = await mutate<T>(
        path,
        "POST",
        { ...payload, idempotencyKey },
        c.signal,
      );
      if (!current(e, q.boundary) || !owner.current.isCurrent(lease)) return;
      owner.current.succeeded(keyIdentity, fingerprint);
      await read(after(response));
      if (alive.current && live.current.boundary === q.boundary)
        setNotice(
          "操作已处理。请以重新读取的当前组织状态为准，历史修订的拟发布状态不代表当前资格。",
        );
      return response;
    } catch (error) {
      if (owner.current.isCurrent(lease)) fail(error, e, q.boundary, false);
    } finally {
      controllers.current.delete(c);
      owner.current.release(lease);
      if (current(e, q.boundary)) setWriting(false);
    }
  }
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      stop();
      owner.current.clear();
      drafts.current.clear();
    };
  }, []);
  useEffect(() => {
    stop();
    setWriting(false);
    const next =
      stored.boundary === boundary ? live.current.selection : emptySelection;
    setSelection(next);
    setNotice("");
    setStored({
      boundary,
      refresh: refreshToken,
      group: null,
      busy: false,
      error: "",
    });
    if (active) void read(next);
    return stop;
  }, [boundary, active, refreshToken]);
  const visible =
    active &&
    stored.boundary === boundary &&
    stored.refresh === refreshToken &&
    !stored.busy;
  return {
    scope,
    boundary,
    identity: identity(),
    drafts: drafts.current,
    group: visible ? stored.group : null,
    busy: active && (!visible || stored.busy),
    error: stored.boundary === boundary ? stored.error : "",
    notice: stored.boundary === boundary ? notice : "",
    writing,
    selection,
    read,
    write,
  };
}
