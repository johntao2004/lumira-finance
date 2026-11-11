import { useEffect, useRef, useState } from "react";
import { Alert, Button, Select, Space, Typography } from "antd";
import { api, ApiError } from "@/platform/api/client";
import type {
  OriginalReference,
  OriginalSnapshot,
} from "./tax-entity-contract";
import type { Year } from "./types";
import { useEvidenceOriginal } from "./use-evidence-original";
import { yearWriteRestriction } from "./year-write-scope";
import { taxOriginalVersion } from "./tax-entity-rules";
type Original = {
  id: string;
  version: string | number;
  sha256: string;
  filename: string;
};
export function TaxEvidencePicker({
  bookId,
  years,
  value,
  onChange,
  yearId,
  onYearChange,
  canUpload,
  onDenied,
  readable,
}: {
  bookId: string;
  years: Year[];
  yearId: string;
  onYearChange: (id: string) => void;
  value: OriginalReference | null;
  onChange: (value: OriginalReference | null) => void;
  canUpload: boolean;
  onDenied: () => void;
  readable: (e: unknown) => string;
}) {
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Text>
        登记证据年度（仅服务器授权结果；与工作台会计年度分开）
      </Typography.Text>
      <Select
        aria-label="登记证据年度"
        style={{ width: "100%" }}
        value={yearId || undefined}
        options={years.map((y) => ({ value: y.id, label: String(y.year) }))}
        onChange={(id) => {
          onYearChange(id);
        }}
      />
      {yearId && years.some((y) => y.id === yearId) ? (
        <TaxOriginalList
          key={`${bookId}:${yearId}`}
          bookId={bookId}
          year={years.find((y) => y.id === yearId)!}
          value={value}
          onChange={onChange}
          canUpload={canUpload}
          onDenied={onDenied}
          readable={readable}
        />
      ) : (
        <Typography.Text>
          请先选择实际授权年度；不会自动借用账套权限读取原件。
        </Typography.Text>
      )}
    </Space>
  );
}
function TaxOriginalList({
  bookId,
  year,
  value,
  onChange,
  canUpload,
  onDenied,
  readable,
}: {
  bookId: string;
  year: Year;
  value: OriginalReference | null;
  onChange: (value: OriginalReference | null) => void;
  canUpload: boolean;
  onDenied: () => void;
  readable: (e: unknown) => string;
}) {
  const scope = `/finance/v1/books/${bookId}/years/${year.id}`;
  const [items, setItems] = useState<Original[]>([]),
    [busy, setBusy] = useState(true),
    [error, setError] = useState("");
  const alive = useRef(false),
    controller = useRef<AbortController | null>(null);
  const transport = useEvidenceOriginal(
    scope,
    canUpload && !yearWriteRestriction(year),
    onDenied,
    readable,
  );
  const callbacks = useRef({ onDenied, readable, onChange });
  callbacks.current = { onDenied, readable, onChange };
  async function load(selectedId?: string) {
    controller.current?.abort();
    const c = new AbortController();
    controller.current = c;
    setBusy(true);
    setItems([]);
    setError("");
    try {
      const rows = await api<Original[]>(`${scope}/originals`, {
        signal: c.signal,
        cache: "no-store",
      });
      if (!alive.current || c.signal.aborted) return;
      for (const row of rows) {
        taxOriginalVersion(row.version);
        if (!/^[a-f0-9]{64}$/.test(row.sha256))
          throw new Error("原件摘要未知，请重新读取。");
      }
      setItems(rows);
      if (selectedId) {
        const item = rows.find((r) => r.id === selectedId);
        if (!item) throw new Error("上传后未读取到原件，请显式重新读取。");
        callbacks.current.onChange({
          yearId: year.id,
          originalId: item.id,
          expectedVersion: taxOriginalVersion(item.version),
          expectedSha256: item.sha256,
          sourceType: value?.sourceType ?? "ELECTRONIC_ORIGINAL",
          originalLocation: value?.originalLocation ?? null,
        });
      }
    } catch (e) {
      if (alive.current && !c.signal.aborted) {
        setError(callbacks.current.readable(e));
        if (e instanceof ApiError && e.status === 403)
          callbacks.current.onDenied();
      }
    } finally {
      if (alive.current && !c.signal.aborted) setBusy(false);
    }
  }
  useEffect(() => {
    alive.current = true;
    void load();
    return () => {
      alive.current = false;
      controller.current?.abort();
    };
  }, [scope]);
  const selected = items.find((i) => i.id === value?.originalId);
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      {error && <Alert type="error" title={`原件未读取成功：${error}`} />}
      <Select
        aria-label="登记证据原件"
        style={{ width: "100%" }}
        loading={busy}
        disabled={busy}
        value={selected?.id}
        options={items.map((i) => ({
          value: i.id,
          label: `${i.filename} · ${i.id}`,
        }))}
        onChange={(id) => {
          const item = items.find((i) => i.id === id);
          if (item)
            onChange({
              yearId: year.id,
              originalId: item.id,
              expectedVersion: taxOriginalVersion(item.version),
              expectedSha256: item.sha256,
              sourceType: value?.sourceType ?? "ELECTRONIC_ORIGINAL",
              originalLocation: value?.originalLocation ?? null,
            });
        }}
      />
      <Space wrap>
        <Button
          disabled={busy || transport.uploading}
          onClick={() => void load()}
        >
          重新读取原件
        </Button>
        <Button
          disabled={!selected || busy}
          onClick={() => {
            if (selected)
              void transport.download(selected.id, selected.filename);
          }}
        >
          下载所选原件
        </Button>
      </Space>
      {canUpload && !yearWriteRestriction(year) ? (
        <label>
          上传真实原件（最多5MiB）
          <input
            aria-label="上传登记证据原件"
            type="file"
            disabled={busy || transport.uploading}
            onChange={(event) => {
              const file = event.currentTarget.files?.[0];
              event.currentTarget.value = "";
              if (file) void transport.upload(file, async (id) => load(id));
            }}
          />
        </label>
      ) : (
        <Typography.Text>
          此年度仅可选择已有原件，当前不具备原件上传资格。
        </Typography.Text>
      )}
      {selected && (
        <Typography.Paragraph style={{ overflowWrap: "anywhere" }}>
          服务器SHA-256：{selected.sha256}；版本：
          {taxOriginalVersion(selected.version)}。文件已上传不代表真实性已验证。
        </Typography.Paragraph>
      )}
      {value && !busy && !selected && (
        <Alert
          type="warning"
          title="冻结表单中的原件未出现在本次授权读取中，请重新选择真实原件。"
        />
      )}
      {transport.error && <Alert type="error" title={transport.error} />}
    </Space>
  );
}
export function TaxOriginalDownload({
  original,
  onDenied,
  readable,
}: {
  original: OriginalSnapshot;
  onDenied: () => void;
  readable: (e: unknown) => string;
}) {
  const transport = useEvidenceOriginal(
    `/finance/v1/books/${original.bookId}/years/${original.yearId}`,
    false,
    onDenied,
    readable,
  );
  return (
    <Space orientation="vertical">
      <Typography.Paragraph style={{ overflowWrap: "anywhere" }}>
        原件：{original.filename} · {original.id}；年度ID：{original.yearId}
        ；服务器版本：{original.version}；SHA-256：{original.sha256}；上传账号：
        {original.createdBy}。来源：
        {original.sourceType === "PAPER_SCAN" ? "纸质扫描件" : "电子原文件"}
        ；纸质位置：{original.originalLocation ?? "未登记"}；真实性未验证。
      </Typography.Paragraph>
      <Button
        onClick={() => void transport.download(original.id, original.filename)}
      >
        下载登记证据复核
      </Button>
      {transport.error && <Alert type="error" title={transport.error} />}
    </Space>
  );
}
