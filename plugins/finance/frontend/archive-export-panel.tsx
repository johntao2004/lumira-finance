import { useEffect, useRef, useState } from "react";
import { Alert, Button, Card, Descriptions, Select, Space, Table, Tag, Typography } from "antd";

export type ArchiveRequestSummary = {
  requestId: string;
  preflightId: string;
  purpose: string;
  state: string;
  version: string;
  yearIds: string[];
  blockers: string[];
  manifestSha256: string | null;
  artifactSha256: string | null;
  artifactBytes: string | null;
  legalArchiveComplete: boolean;
};

export type ArchivePreflightResult = {
  preflightId: string;
  purpose: string;
  proposalDigest: string;
  expiresAt: string;
  blockers: string[];
  counts: { dataset: string; value: string | null; status: string }[];
  totals: { dataset: string; debit: string | null; credit: string | null; precision: number }[];
};

type Props = {
  identity: string;
  bookId: string;
  yearId: string;
  active: boolean;
  canExport: boolean;
  canCreate: boolean;
  canReview: boolean;
  canPost: boolean;
  read: <T>(url: string, signal: AbortSignal) => Promise<T>;
  write: <T>(url: string, body: Record<string, unknown>) => Promise<T>;
  downloadBlob: (url: string) => Promise<{ blob: Blob; filename: string }>;
};

export function ArchiveExportPanel(props: Props) {
  return <ArchiveWorkbench key={props.identity} {...props} />;
}

function ArchiveWorkbench(p: Props) {
  const root = `/finance/v1/books/${p.bookId}/archive-exports`;
  const [purpose, setPurpose] = useState<"DIAGNOSTIC" | "ARCHIVE" | "EXIT">("DIAGNOSTIC");
  const [preflight, setPreflight] = useState<ArchivePreflightResult | null>(null);
  const [items, setItems] = useState<ArchiveRequestSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [refresh, setRefresh] = useState(0);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  useEffect(() => {
    if (!p.active || !p.bookId || !p.canExport) return;
    const ctrl = new AbortController();
    setLoading(true);
    void p
      .read<{ items: ArchiveRequestSummary[] }>(root, ctrl.signal)
      .then((res) => {
        if (!ctrl.signal.aborted && alive.current) {
          setItems(res.items);
          setError("");
        }
      })
      .catch((e: unknown) => {
        if (!ctrl.signal.aborted && alive.current) setError(String(e));
      })
      .finally(() => {
        if (!ctrl.signal.aborted && alive.current) setLoading(false);
      });
    return () => ctrl.abort();
  }, [p.active, p.bookId, p.canExport, p.identity, p.read, root, refresh]);

  if (!p.canExport) {
    return <Alert type="info" title="需要独立财务导出权限（platform:finance:export）以访问电子档案与离线导出" />;
  }

  async function runPreflight() {
    if (busy) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const res = await p.write<ArchivePreflightResult>(`${root}/preflights`, {
        yearIds: [p.yearId],
        purpose,
      });
      if (alive.current) setPreflight(res);
    } catch (e: unknown) {
      if (alive.current) setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  async function runAction(url: string, body: Record<string, unknown>, msg?: string) {
    if (busy) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await p.write(url, body);
      if (alive.current) {
        if (msg) setNotice(msg);
        setRefresh((x) => x + 1);
      }
    } catch (e: unknown) {
      if (alive.current) setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  async function handleDownload(row: ArchiveRequestSummary) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      const { blob } = await p.downloadBlob(`${root}/${row.requestId}/download`);
      const buf = await blob.arrayBuffer();
      const digestBuf = await crypto.subtle.digest("SHA-256", buf);
      const actualHex = Array.from(new Uint8Array(digestBuf))
        .map((b) => b.toString(16).padStart(2, "0"))
        .join("");
      if (row.artifactSha256 && actualHex !== row.artifactSha256) {
        throw new Error("下载字节SHA-256与服务端记录不一致");
      }
      if (alive.current) {
        setNotice(`已校验下载 ustar 包 (${buf.byteLength} 字节, SHA-256 ${actualHex.slice(0, 16)}...)`);
      }
    } catch (e: unknown) {
      if (alive.current) setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="warning"
        title="电子档案与离线 ustar 导出包（ARC01 / ARC03）"
        description="预检冻结来源水位与借贷合计，经独立自然人批准后生成确定性 POSIX ustar 包（支持 node plugins/finance/archive/verify-archive.mjs 离线校验）。法定正式三表未发布前 ARCHIVE / EXIT 全量交付受阻断保护；DIAGNOSTIC 诊断快照包可完整生成并校验字节完整性（legalArchiveComplete=false）。"
      />
      {error && <Alert type="error" title={error} />}
      {notice && <Alert type="success" title={notice} />}

      <Card title="1. 范围预检与申请发起" size="small">
        <Space wrap>
          <Select
            aria-label="导出目的"
            value={purpose}
            style={{ width: 280 }}
            onChange={setPurpose}
            options={[
              { value: "DIAGNOSTIC", label: "诊断与离线核验包（DIAGNOSTIC）" },
              { value: "EXIT", label: "受控退订迁移包（EXIT）" },
              { value: "ARCHIVE", label: "正式会计归档包（ARCHIVE）" },
            ]}
          />
          <Button type="primary" disabled={busy || !p.yearId} onClick={() => void runPreflight()}>
            执行导出预检
          </Button>
          {preflight && (
            <Button
              disabled={!p.canCreate || busy}
              onClick={() =>
                void runAction(
                  `${root}/requests`,
                  {
                    preflightId: preflight.preflightId,
                    proposalDigest: preflight.proposalDigest,
                    idempotencyKey: `arc-req-${crypto.randomUUID()}`,
                  },
                  "已创建导出申请（REQUESTED）",
                )
              }
            >
              发起导出申请
            </Button>
          )}
        </Space>
        {preflight && (
          <Descriptions size="small" column={2} style={{ marginTop: 12 }}>
            <Descriptions.Item label="预检编号">{preflight.preflightId}</Descriptions.Item>
            <Descriptions.Item label="提案摘要">
              <Typography.Text copyable>{preflight.proposalDigest.slice(0, 24)}...</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="快照计数">
              {preflight.counts.map((c) => `${c.dataset}:${c.value ?? "未知"}`).join(" / ")}
            </Descriptions.Item>
            <Descriptions.Item label="阻断项">
              {preflight.blockers.length ? preflight.blockers.join(", ") : "无阻断"}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Card>

      <Card title="2. 导出申请、独立审签与离线包交付" size="small">
        <Table<ArchiveRequestSummary>
          rowKey="requestId"
          loading={loading}
          dataSource={items}
          pagination={false}
          scroll={{ x: 960 }}
          columns={[
            { title: "申请编号", dataIndex: "requestId" },
            { title: "目的", dataIndex: "purpose" },
            { title: "状态", dataIndex: "state", render: (s: string) => <Tag>{s}</Tag> },
            { title: "版本", dataIndex: "version" },
            {
              title: "制品字节 / Manifest摘要",
              render: (_, r) =>
                r.artifactBytes
                  ? `${r.artifactBytes} B / ${r.manifestSha256?.slice(0, 12)}...`
                  : "尚未生成",
            },
            {
              title: "操作",
              render: (_, r) => (
                <Space wrap>
                  {r.state === "REQUESTED" && (
                    <Button
                      size="small"
                      disabled={!p.canReview || busy}
                      onClick={() =>
                        void p
                          .read<{ proposalDigest: string }>(`${root}/${r.requestId}`, new AbortController().signal)
                          .then((d) =>
                            runAction(`${root}/${r.requestId}/approve`, {
                              version: Number(r.version),
                              proposalDigest: d.proposalDigest,
                              reason: "独立自然人复核批准导出范围与水位",
                              idempotencyKey: `arc-app-${crypto.randomUUID()}`,
                            }),
                          )
                      }
                    >
                      独立批准
                    </Button>
                  )}
                  {r.state === "APPROVED" && (
                    <Button
                      size="small"
                      type="primary"
                      disabled={(!p.canPost && !p.canCreate) || busy}
                      onClick={() =>
                        void runAction(`${root}/${r.requestId}/generate`, {
                          version: Number(r.version),
                          idempotencyKey: `arc-gen-${crypto.randomUUID()}`,
                        })
                      }
                    >
                      生成ustar包
                    </Button>
                  )}
                  {(r.state === "READY" || r.state === "DELIVERED") && (
                    <Button size="small" disabled={busy} onClick={() => void handleDownload(r)}>
                      校验下载
                    </Button>
                  )}
                  {r.state === "READY" && r.artifactSha256 && r.manifestSha256 && (
                    <Button
                      size="small"
                      disabled={busy}
                      onClick={() =>
                        void runAction(`${root}/${r.requestId}/receipt`, {
                          version: Number(r.version),
                          artifactSha256: r.artifactSha256,
                          manifestSha256: r.manifestSha256,
                          verificationReportSha256: r.manifestSha256,
                          reason: "接收方已完成ustar离线包完整性复核",
                          idempotencyKey: `arc-rcpt-${crypto.randomUUID()}`,
                        })
                      }
                    >
                      登记接收回执
                    </Button>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Card>
    </Space>
  );
}
