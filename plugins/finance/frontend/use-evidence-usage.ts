import { useContext, useEffect, useRef, useState } from "react";
import { api, ApiError, mutate } from "@/platform/api/client";
import { EvidenceWriteContext } from "./evidence-write-context";
import { evidenceReadMatches } from "./evidence-usage-rules";
const messages: Record<string, string> = {
  EVIDENCE_HOME_SCOPE_UNSUPPORTED:
    "该单据只能在原账套、原年度引用。本片不支持跨范围消费，也不能通过重新扫描获得第二份额度。",
  EVIDENCE_IDENTITY_CONFLICT:
    "单据标识或文件已归属其他永久记录，不能合并、转让或重新获得额度。请核对组织记录。",
  EVIDENCE_BASIS_UNAVAILABLE:
    "尚无可用的组织复核面额依据，容量未知，不能预留或确认。",
  EVIDENCE_BASIS_REVIEW_PENDING:
    "面额依据正在校正，新预留及确认已暂停；仍可按权限退出未确认份额或更正已确认净份额。",
  EVIDENCE_BASIS_STALE:
    "份额绑定的面额依据已变化，请显式读取后编辑草稿，不会自动改绑新依据。",
  EVIDENCE_ACTIVE_RESERVATIONS:
    "仍有有效预留，请先通过真实撤回、驳回或行政终结释放，再批准新面额依据。",
  EVIDENCE_CAPACITY_BELOW_COMMITTED:
    "新的费用上限小于已确认净额，不能发布。请核对原份额与真实更正。",
  EVIDENCE_CAPACITY_EXCEEDED:
    "同一费用用途的可用额度不足，不能超额认领。请重新读取当前余额。",
  EVIDENCE_SUBMISSION_EXPIRED:
    "本次预留已过期，不能确认；请按可用退出操作处理后重新准备。",
  EVIDENCE_CONSUMER_STATE_CONFLICT:
    "份额当前状态不允许此操作，请显式读取权威状态。",
  EVIDENCE_REVERSE_EXCEEDS_REMAINING: "拟更正金额超过原份额当前可更正净额。",
  EVIDENCE_REVIEW_EXPIRED: "独立复核期限已过，请驳回或由原账号取消后重新准备。",
  EVIDENCE_PAGE_STALE: "目录或授权版本已变化，旧页已清理，请显式重新读取首屏。",
  SELF_APPROVAL_FORBIDDEN:
    "此决定须由独立人员作出，同一人员的另一账号也不能自审。",
  PERSON_AUTH_ASSURANCE_REQUIRED:
    "此操作要求最近5分钟最终登录并通过TOTP，请通过平台正常重新登录后重试。",
  EVIDENCE_USAGE_PURPOSE_UNSUPPORTED:
    "本片只支持费用归属用途，未支持结算、入账或税务消费。",
  EVIDENCE_DOCUMENT_BUNDLE_UNSUPPORTED:
    "本片仅支持单文件单张独立单据，不支持打包文件拆票。",
};
export function evidenceReadable(
  error: unknown,
  fallback: (e: unknown) => string,
) {
  return error instanceof Error && messages[error.message]
    ? messages[error.message]
    : fallback(error);
}
export function useEvidenceResource<T>(
  path: string,
  active: boolean,
  revision: number,
  onReadDenied: () => void,
  onReadFailure: (message: string) => void,
  readable: (error: unknown) => string,
) {
  const requestContext = useContext(EvidenceWriteContext);
  const [stored, setStored] = useState<{
      path: string;
      revision: number;
      value: T;
    } | null>(null),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(false),
    [writing, setWriting] = useState(false);
  const alive = useRef(false),
    epoch = useRef(0),
    controllers = useRef(new Set<AbortController>()),
    callbacks = useRef({ onReadDenied, onReadFailure, readable }),
    retained = useRef<{ path: string; value: T } | null>(null),
    currentRead = useRef({ path, active, revision });
  useEffect(() => {
    callbacks.current = { onReadDenied, onReadFailure, readable };
  }, [onReadDenied, onReadFailure, readable]);
  function stop() {
    epoch.current++;
    for (const controller of controllers.current) controller.abort();
    controllers.current.clear();
  }
  async function read() {
    const current = currentRead.current;
    if (!current.path || !current.active) return;
    stop();
    setWriting(false);
    const selected = epoch.current;
    setStored(null);
    setError("");
    setLoading(true);
    const controller = new AbortController();
    controllers.current.add(controller);
    try {
      const value = await api<T>(current.path, { signal: controller.signal });
      if (alive.current && selected === epoch.current) {
        retained.current = { path: current.path, value };
        setStored({ path: current.path, revision: current.revision, value });
      }
    } catch (cause) {
      if (alive.current && selected === epoch.current) {
        const message = evidenceReadable(cause, callbacks.current.readable);
        setError(message);
        if (cause instanceof ApiError && cause.status === 403)
          callbacks.current.onReadDenied();
        else callbacks.current.onReadFailure(message);
      }
    } finally {
      controllers.current.delete(controller);
      if (alive.current && selected === epoch.current) setLoading(false);
    }
  }
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      stop();
      retained.current = null;
    };
  }, []);
  useEffect(() => {
    currentRead.current = { path, active, revision };
    stop();
    setStored(null);
    setError("");
    setLoading(false);
    setWriting(false);
    if (active && path) void read();
  }, [path, active, revision]);
  async function write<R>(
    commandPath: string,
    payload: Record<string, unknown>,
    method: "POST" | "PATCH" = "POST",
  ): Promise<R | null> {
    if (!active || !requestContext) return null;
    const { owner, identity } = requestContext;
    const lease = owner.acquire(identity);
    if (!lease) {
      setError("另一个请求尚未结束，请稍后显式重试原内容。");
      return null;
    }
    const selected = epoch.current,
      controller = new AbortController();
    controllers.current.add(controller);
    setWriting(true);
    setError("");
    const fingerprint = JSON.stringify([commandPath, method, payload]);
    const idempotencyKey = owner.key(identity, fingerprint, () =>
      crypto.randomUUID(),
    );
    try {
      const result = await mutate<R>(
        commandPath,
        method,
        { ...payload, idempotencyKey },
        controller.signal,
      );
      if (
        !alive.current ||
        selected !== epoch.current ||
        !owner.isCurrent(lease)
      )
        return null;
      owner.succeeded(identity, fingerprint);
      return result;
    } catch (cause) {
      if (alive.current && selected === epoch.current)
        setError(
          `${evidenceReadable(cause, callbacks.current.readable)} 原表单与请求键已保留，可重试原内容或显式读取当前状态核对结果。`,
        );
      return null;
    } finally {
      controllers.current.delete(controller);
      owner.release(lease);
      if (alive.current && selected === epoch.current) setWriting(false);
    }
  }
  return {
    data:
      active && stored && evidenceReadMatches(path, revision, stored)
        ? stored.value
        : null,
    retained: retained.current?.path === path ? retained.current.value : null,
    error,
    loading,
    writing,
    read,
    write,
  };
}
