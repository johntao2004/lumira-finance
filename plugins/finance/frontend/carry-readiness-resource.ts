import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/platform/api/client";
import type { Summary, AccountPage } from "./carry-readiness-contract";
import {
  carryActorEqual,
  carrySelectionKey,
  carrySummaryMatches,
  carryPageMatches,
  carrySummaryPath,
  carryAccountsPath,
  carryGroupVisible,
  type CarrySelection,
} from "./carry-readiness-rules";
type Group = { summary: Summary; page: AccountPage };
type State = {
  key: string;
  phase: "IDLE" | "SUMMARY" | "PAGE" | "READY" | "FAILED";
  group: Group | null;
  error: string;
};
const messages: Record<string, string> = {
  CARRY_READINESS_STALE:
    "源或目标账务、流程、科目、期间或授权已变化，旧观察及游标已清理。请显式重新读取双年度首屏。",
  CARRY_READINESS_OBSERVATION_UNAVAILABLE:
    "当前观察已不可用，不能续读旧游标。请显式重新读取双年度首屏。",
  CARRY_READINESS_LIMIT_EXCEEDED:
    "本次范围超过受控只读上限，未取得完整观察；不会用部分数据或零代替。",
  QUERY_BUDGET_EXCEEDED:
    "本次读取未完成，请稍后显式重读；未知金额及数量不会显示为零。",
  SCOPE_DENIED:
    "当前账号未获此双年度读取范围，旧观察已清理，请重新选择允许的范围。",
  CARRY_READINESS_CONTEXT_MISMATCH:
    "读取期间的范围、身份或观察令牌不一致，整组结果已清理，请显式重新读取。",
  CARRY_READINESS_ACTOR_CHANGED:
    "普通业务人员身份已变化，正在重新核对当前身份；请显式重新读取双年度首屏。",
};
export function useCarryReadiness(
  selection: CarrySelection,
  active: boolean,
  onReadDenied: () => void,
  onActorChanged: () => void,
  readable: (e: unknown) => string,
) {
  const key = carrySelectionKey(selection);
  const [state, setState] = useState<State>({
    key,
    phase: "IDLE",
    group: null,
    error: "",
  });
  const alive = useRef(false),
    epoch = useRef(0),
    controller = useRef<AbortController | null>(null),
    live = useRef({ selection, key, active }),
    currentState = useRef(state),
    callbacks = useRef({ onReadDenied, onActorChanged, readable });
  live.current = { selection, key, active };
  currentState.current = state;
  useEffect(() => {
    callbacks.current = { onReadDenied, onActorChanged, readable };
  }, [onReadDenied, onActorChanged, readable]);
  function stop() {
    epoch.current++;
    controller.current?.abort();
    controller.current = null;
  }
  function current(version: number, requestKey: string) {
    return (
      alive.current &&
      epoch.current === version &&
      live.current.active &&
      live.current.key === requestKey
    );
  }
  function failure(e: unknown, version: number, requestKey: string) {
    if (!current(version, requestKey)) return;
    const text =
      e instanceof Error && messages[e.message]
        ? messages[e.message]
        : callbacks.current.readable(e);
    setState({
      key: requestKey,
      phase: "FAILED",
      group: null,
      error: `${text} 未读取成功，源年末、目标期初、本年发生、检查数量及游标均已隐藏；不能按0或部分结果继续。`,
    });
    if (e instanceof ApiError && e.status === 403)
      callbacks.current.onReadDenied();
  }
  async function accounts(
    s: CarrySelection,
    summary: Summary,
    cursor: string | null,
    signal: AbortSignal,
  ) {
    const page = await api<AccountPage>(
      carryAccountsPath(s, summary.observation.token, cursor),
      { signal, cache: "no-store" },
    );
    if (!carryPageMatches(page, summary, s))
      throw new Error("CARRY_READINESS_CONTEXT_MISMATCH");
    return page;
  }
  async function readFirst() {
    const q = live.current;
    if (!q.active) return;
    stop();
    const version = epoch.current,
      c = new AbortController();
    controller.current = c;
    setState({ key: q.key, phase: "SUMMARY", group: null, error: "" });
    try {
      const summary = await api<Summary>(carrySummaryPath(q.selection), {
        signal: c.signal,
        cache: "no-store",
      });
      if (!current(version, q.key)) return;
      if (!carrySummaryMatches(summary, q.selection))
        throw new Error("CARRY_READINESS_CONTEXT_MISMATCH");
      if (
        q.selection.actorHint &&
        !carryActorEqual(summary.currentActor, q.selection.actorHint)
      ) {
        callbacks.current.onActorChanged();
        throw new Error("CARRY_READINESS_ACTOR_CHANGED");
      }
      setState({ key: q.key, phase: "PAGE", group: null, error: "" });
      const page = await accounts(q.selection, summary, null, c.signal);
      if (current(version, q.key))
        setState({
          key: q.key,
          phase: "READY",
          group: { summary, page },
          error: "",
        });
    } catch (e) {
      failure(e, version, q.key);
    } finally {
      if (controller.current === c) controller.current = null;
    }
  }
  async function next() {
    const q = live.current,
      previous = currentState.current;
    if (
      !q.active ||
      previous.key !== q.key ||
      previous.phase !== "READY" ||
      !previous.group?.page.nextCursor
    )
      return;
    const accepted = previous.group;
    stop();
    const version = epoch.current,
      c = new AbortController();
    controller.current = c;
    setState({ key: q.key, phase: "PAGE", group: null, error: "" });
    try {
      const page = await accounts(
        q.selection,
        accepted.summary,
        accepted.page.nextCursor,
        c.signal,
      );
      if (current(version, q.key))
        setState({
          key: q.key,
          phase: "READY",
          group: { summary: accepted.summary, page },
          error: "",
        });
    } catch (e) {
      failure(e, version, q.key);
    } finally {
      if (controller.current === c) controller.current = null;
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
    setState({ key, phase: "IDLE", group: null, error: "" });
    if (active) void readFirst();
  }, [key, active]);
  const matches = active && state.key === key;
  return {
    key,
    group: carryGroupVisible(state.key, key, active, state.phase)
      ? state.group
      : null,
    error: matches ? state.error : "",
    loading: matches && (state.phase === "SUMMARY" || state.phase === "PAGE"),
    phase: matches ? state.phase : "IDLE",
    readFirst,
    next,
  };
}
