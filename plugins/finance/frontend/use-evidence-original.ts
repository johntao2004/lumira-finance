import { useContext, useEffect, useRef, useState } from "react";
import { api, apiBlob, ApiError, csrf } from "@/platform/api/client";
import { EvidenceWriteContext } from "./evidence-write-context";
import {
  evidenceOriginalFingerprint,
  evidenceOriginalSizeAllowed,
} from "./evidence-original-rules";
/** EV03 byte transport only: the browser digest is a retry fingerprint, never authenticity evidence. */
export function useEvidenceOriginal(
  scope: string,
  canUpload: boolean,
  onReadDenied: () => void,
  readable: (error: unknown) => string,
) {
  const requestContext = useContext(EvidenceWriteContext);
  const [uploading, setUploading] = useState(false),
    [error, setError] = useState("");
  const alive = useRef(false),
    epoch = useRef(0),
    scopeController = useRef<AbortController | null>(null),
    urls = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  useEffect(() => {
    alive.current = true;
    epoch.current++;
    scopeController.current = new AbortController();
    setUploading(false);
    setError("");
    return () => {
      alive.current = false;
      epoch.current++;
      scopeController.current?.abort();
      for (const [url, timer] of urls.current) {
        clearTimeout(timer);
        URL.revokeObjectURL(url);
      }
      urls.current.clear();
    };
  }, [scope]);
  async function upload(file: File, onSaved: (id: string) => Promise<void>) {
    if (!canUpload || !requestContext) return;
    if (!evidenceOriginalSizeAllowed(file.size)) {
      setError("原件须非空且最多5MiB，请上传实际原文件或受支持的单张扫描件。");
      return;
    }
    const signal = scopeController.current?.signal;
    if (!signal || signal.aborted) return;
    const { owner, identity } = requestContext;
    const lease = owner.acquire(identity);
    if (!lease) {
      setError("另一个请求尚未结束，请稍后重试同一文件。");
      return;
    }
    const revision = epoch.current;
    setUploading(true);
    setError("");
    try {
      const bytes = await file.arrayBuffer();
      signal.throwIfAborted();
      const hash = await crypto.subtle.digest("SHA-256", bytes);
      signal.throwIfAborted();
      const digest = Array.from(new Uint8Array(hash), (byte) =>
        byte.toString(16).padStart(2, "0"),
      ).join("");
      const fingerprint = evidenceOriginalFingerprint(scope, file.name, digest),
        idempotencyKey = owner.key(identity, fingerprint, () =>
          crypto.randomUUID(),
        );
      const csrfToken = await csrf(signal);
      signal.throwIfAborted();
      const original = await api<{ id: string }>(
        `${scope}/originals?filename=${encodeURIComponent(file.name)}`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/octet-stream",
            "Idempotency-Key": idempotencyKey,
            [csrfToken.headerName]: csrfToken.token,
          },
          body: file,
          signal,
        },
      );
      if (
        !alive.current ||
        revision !== epoch.current ||
        signal.aborted ||
        !owner.isCurrent(lease)
      )
        return;
      owner.succeeded(identity, fingerprint);
      try {
        await onSaved(original.id);
      } catch {
        if (alive.current && revision === epoch.current && !signal.aborted)
          setError(
            "原件已保存，但列表刷新未完成。请显式重读原件列表核对，不要重复上传。",
          );
      }
    } catch (cause) {
      if (alive.current && revision === epoch.current && !signal.aborted)
        setError(
          `${readable(cause)} 原上传请求键已保留，可重试同一文件或先重读原件列表核对结果。`,
        );
    } finally {
      owner.release(lease);
      if (alive.current && revision === epoch.current && !signal.aborted)
        setUploading(false);
    }
  }
  async function download(id: string, filename: string) {
    const signal = scopeController.current?.signal;
    if (!signal || signal.aborted) return;
    const revision = epoch.current;
    setError("");
    try {
      const blob = await apiBlob(`${scope}/originals/${id}/content`, {
        signal,
        cache: "no-store",
      });
      if (!alive.current || revision !== epoch.current || signal.aborted)
        return;
      const url = URL.createObjectURL(blob),
        link = document.createElement("a");
      link.href = url;
      link.download = filename || "original";
      link.click();
      const timer = setTimeout(() => {
        URL.revokeObjectURL(url);
        urls.current.delete(url);
      }, 1000);
      urls.current.set(url, timer);
    } catch (cause) {
      if (alive.current && revision === epoch.current && !signal.aborted) {
        setError(readable(cause));
        if (cause instanceof ApiError && cause.status === 403) onReadDenied();
      }
    }
  }
  return { upload, download, uploading, error };
}
