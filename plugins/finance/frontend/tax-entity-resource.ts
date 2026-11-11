import type { Year } from "./types";
import type { TaxDraft } from "./tax-entity-state";
import { useEffect, useRef, useState } from "react";
import { api, ApiError, mutate } from "@/platform/api/client";
import { EvidenceRequestOwner } from "./evidence-request-state";
import {
  taxActorEqual,
  taxIdentity,
  taxConflictReferences,
} from "./tax-entity-rules";
import type {
  Actor,
  Page,
  Catalog,
  CommandResult,
  Detail,
  RevisionDetail,
} from "./tax-entity-contract";
export type TaxSelection = {
  id: string;
  revisionId: string;
  cursor: string | null;
  limit: number;
  status: string;
};
export type TaxGroup = {
  list: Page;
  catalog: Catalog;
  years: Year[];
  detail: Detail | null;
  revision: RevisionDetail | null;
};
const emptySelection: TaxSelection = {
  id: "",
  revisionId: "",
  cursor: null,
  limit: 20,
  status: "ALL",
};
const messages: Record<string, string> = {
  TAX_ENTITY_PAGE_STALE:
    "纳税主体目录或授权已变化，旧页及游标已清理，请重新读取首屏。",
  TAX_ENTITY_BUSY: "纳税主体目录暂忙，请稍后显式重试。",
  TAX_ENTITY_QUERY_BUDGET_EXCEEDED:
    "本次读取或命令未在受控时间内完成，请显式重新读取当前状态；未知结果不会显示为成功或回滚。",
  TAX_ENTITY_LIMIT_EXCEEDED: "本次范围超过受控上限，未取得完整结果。",
  TAX_ENTITY_APPROVAL_STALE:
    "提案或制备人的资格已变化，请读取权威当前状态；可由独立审核人合法驳回未决提案。",
  PERSON_AUTH_ASSURANCE_REQUIRED:
    "此操作要求近期登录并完成TOTP，请通过正常登录流程重新认证后重试。",
  SELF_APPROVAL_FORBIDDEN: "同一自然人不能审核自己的提案，请使用独立审核账号。",
  TAX_ENTITY_STATE_CONFLICT:
    "当前档案或提案状态已变化，请读取权威当前状态后处理。",
  TAX_ENTITY_SOURCE_STALE:
    "登记证据或基准已变化，请读取当前来源和提案；原正文与请求键保留。",
  VERSION_CONFLICT: "当前版本已变化，请显式读取最新状态，不自动改写冻结命令。",
  IDEMPOTENCY_CONFLICT:
    "操作标识已绑定另一正文，请核对原命令，不会自动换键重发。",
  TAX_ENTITY_HOME_SCOPE_REQUIRED: "只能在此组织的治理归属账套提出或处理修订。",
  TAX_ENTITY_IDENTITY_EXISTS:
    "此登记身份已被永久占用，请读取已有纳税主体，不能通过换年度或文件重复登记。",
};
export function useTaxEntities(
  bookId: string,
  authHint: string,
  active: boolean,
  refreshToken: number,
  onDenied: () => void,
  readable: (e: unknown) => string,
) {
  const scope = `/finance/v1/books/${bookId}/tax-entities`,
    boundary = JSON.stringify([scope, authHint]);
  const owner = useRef(new EvidenceRequestOwner()),
    drafts = useRef(new Map<string, TaxDraft>()),
    actor = useRef<Actor | null>(null),
    ownerBoundary = useRef(boundary);
  // A different book or authorization boundary must mask synchronously, before effects.
  if (ownerBoundary.current !== boundary) {
    owner.current.clear();
    drafts.current.clear();
    actor.current = null;
    ownerBoundary.current = boundary;
  }
  const [selection, setSelection] = useState<TaxSelection>(emptySelection),
    [stored, setStored] = useState<{
      boundary: string;
      refresh: number;
      group: TaxGroup | null;
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
    return taxIdentity(scope, authHint, actor.current);
  }
  function fail(error: unknown, e: number, b: string, reading: boolean) {
    if (!current(e, b)) return;
    const message =
      error instanceof Error ? messages[error.message] : undefined;
    const refs =
      error instanceof ApiError
        ? taxConflictReferences(error.status, error.message, error.issues)
        : null;
    setStored((s) => ({
      ...s,
      group: reading ? null : s.group,
      busy: false,
      error:
        (message ?? callbacks.current.readable(error)) +
        (refs
          ? ` 已有纳税主体ID：${refs.existingTaxEntityId}；治理归属账套ID：${refs.governanceHomeBookId}。请在已授权范围按已知ID读取，不会自动合并或跳转。`
          : "") +
        (reading
          ? " 未读取成功，旧纳税主体、修订及数量均不作为当前结果。"
          : " 请先显式读取当前纳税主体和修订状态；原正文及请求键保留。"),
    }));
    if (reading)
      setSelection((s) => ({ ...s, cursor: null, id: "", revisionId: "" }));
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
        error: "当前读取范围已失权，旧纳税主体与修订已清理。",
      });
      callbacks.current.onDenied();
    }
  }
  async function read(next: TaxSelection = live.current.selection) {
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
      const [list, catalog, years] = await Promise.all([
        api<Page>(
          `${q.scope}?limit=${next.limit}&status=${encodeURIComponent(next.status)}${next.cursor ? "&cursor=" + encodeURIComponent(next.cursor) : ""}`,
          { signal: c.signal, cache: "no-store" },
        ),
        api<Catalog>(`${q.scope}/catalog`, {
          signal: c.signal,
          cache: "no-store",
        }),
        api<Year[]>(`/finance/v1/books/${bookId}/years`, {
          signal: c.signal,
          cache: "no-store",
        }),
      ]);
      if (
        list.currentBookId !== bookId ||
        catalog.scope.currentBookId !== bookId ||
        catalog.scope.tenantId !== list.tenantId ||
        catalog.scope.legalEntityId !== list.legalEntityId ||
        !taxActorEqual(list.currentActor, catalog.currentActor)
      )
        throw new Error("读取期间身份或账套发生变化，请重新读取。");
      let detail: Detail | null = null,
        revision: RevisionDetail | null = null;
      if (next.id) {
        detail = await api<Detail>(`${q.scope}/${next.id}`, {
          signal: c.signal,
          cache: "no-store",
        });
        const revisionId =
          next.revisionId ||
          detail.taxEntity.pendingRevisionId ||
          detail.taxEntity.latestRevisionId;
        revision = await api<RevisionDetail>(
          `${q.scope}/${next.id}/revisions/${revisionId}`,
          { signal: c.signal, cache: "no-store" },
        );
        if (
          detail.taxEntity.version !== revision.taxEntity.version ||
          revision.taxEntity.id !== next.id ||
          revision.revision.id !== revisionId
        )
          throw new Error("纳税主体版本在读取中变化，请重新读取。");
      }
      for (const d of [detail, revision])
        if (
          d &&
          (!taxActorEqual(list.currentActor, d.currentActor) ||
            d.scope.currentBookId !== bookId ||
            d.scope.tenantId !== list.tenantId ||
            d.scope.legalEntityId !== list.legalEntityId)
        )
          throw new Error("读取期间身份或范围发生变化，请重新读取。");
      if (!current(e, q.boundary)) return;
      if (actor.current && !taxActorEqual(actor.current, list.currentActor)) {
        owner.current.clear();
        drafts.current.clear();
      }
      actor.current = list.currentActor;
      owner.current.bind(taxIdentity(q.scope, authHint, list.currentActor));
      setStored({
        boundary: q.boundary,
        refresh: q.refreshToken,
        group: { list, catalog, years, detail, revision },
        busy: false,
        error: "",
      });
    } catch (error) {
      fail(error, e, q.boundary, true);
    } finally {
      controllers.current.delete(c);
    }
  }
  async function write(
    path: string,
    payload: Record<string, unknown>,
    after: (response: CommandResult) => TaxSelection,
  ) {
    const q = live.current;
    if (
      !q.active ||
      !actor.current ||
      !stored.group ||
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
      const response = await mutate<CommandResult>(
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
          "操作已处理。请以重新读取的当前纳税主体状态为准，历史修订的拟发布状态不代表当前资格。",
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
    owner: owner.current,
    deny() {
      stop();
      owner.current.clear();
      drafts.current.clear();
      actor.current = null;
      setStored((s) => ({
        ...s,
        group: null,
        busy: false,
        error: "原件或纳税主体读取范围已失权，旧结果已清理。",
      }));
      callbacks.current.onDenied();
    },
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
