import { useEffect, useRef, useState } from "react";
import { api, apiBlob, csrf, ApiError } from "@/platform/api/client";
export function useMovementEvidence(
  scope: string,
  canUpload: boolean,
  onReadDenied: () => void,
  readable: (error: unknown) => string,
) {
  const controller = useRef<AbortController | null>(null);
  const keys = useRef(new Map<string, string>());
  const urls = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const busy = useRef(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    controller.current = new AbortController();
    return () => {
      controller.current?.abort();
      for (const [url, timer] of urls.current) {
        clearTimeout(timer);
        URL.revokeObjectURL(url);
      }
      urls.current.clear();
    };
  }, [scope]);
  async function upload(file: File, onSaved: (id: string) => Promise<void>) {
    if (!canUpload || busy.current) return;
    if (file.size > 5 * 1024 * 1024) {
      setError("原件最大5MiB");
      return;
    }
    const signal = controller.current?.signal;
    if (!signal || signal.aborted) return;
    busy.current = true;
    setUploading(true);
    setError("");
    try {
      const path = `${scope}/originals?filename=${encodeURIComponent(file.name)}`;
      const bytes = await file.arrayBuffer();
      const digest = await crypto.subtle.digest("SHA-256", bytes);
      const fingerprint = JSON.stringify([
        path,
        Array.from(new Uint8Array(digest), (b) =>
          b.toString(16).padStart(2, "0"),
        ).join(""),
      ]);
      const key = keys.current.get(fingerprint) ?? crypto.randomUUID();
      keys.current.set(fingerprint, key);
      const token = await csrf(signal);
      const original = await api<{ id: string }>(path, {
        method: "POST",
        headers: {
          "Content-Type": "application/octet-stream",
          [token.headerName]: token.token,
          "Idempotency-Key": key,
        },
        body: file,
        signal,
      });
      keys.current.delete(fingerprint);
      if (!signal.aborted) await onSaved(original.id);
    } catch (cause) {
      if (!signal.aborted) {
        setError(readable(cause));
        if (cause instanceof ApiError && cause.status === 403) onReadDenied();
      }
    } finally {
      busy.current = false;
      if (!signal.aborted) setUploading(false);
    }
  }
  async function download(id: string, filename: string) {
    const signal = controller.current?.signal;
    if (!signal || signal.aborted) return;
    setError("");
    try {
      const blob = await apiBlob(`${scope}/originals/${id}/content`, {
        signal,
      });
      if (signal.aborted) return;
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename || "original";
      link.click();
      const timer = setTimeout(() => {
        URL.revokeObjectURL(url);
        urls.current.delete(url);
      }, 1000);
      urls.current.set(url, timer);
    } catch (cause) {
      if (!signal.aborted) {
        setError(readable(cause));
        if (cause instanceof ApiError && cause.status === 403) onReadDenied();
      }
    }
  }
  return { upload, download, uploading, error };
}
