import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Collapse,
  Checkbox,
  Descriptions,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { api, ApiError, mutate, csrf, apiBlob } from "@/platform/api/client";
import type { Account, Period } from "./types";
import {
  cashCreatePayload,
  cashDecisionPayload,
  cashTemplate,
  type CashScopeSummary,
  type CashScopeDetail,
  type CashScopeItem,
} from "./cash-scope-contract";
const required = [{ required: true, message: "请填写此项" }];
const labels: Record<string, string> = {
  DRAFT: "草稿",
  SUBMITTED: "待独立复核",
  APPROVED: "已批准范围资格",
  REJECTED: "已驳回",
  CASH_ON_HAND: "库存现金",
  DEMAND_DEPOSIT: "活期存款",
  INCLUDED: "纳入",
  EXCLUDED: "排除",
  NONE: "无使用限制",
  RESTRICTED: "受限",
  OTHER: "其他限制",
};
const errors: Record<string, string> = {
  CASH_SCOPE_STALE: "引用的科目或证据已变化，请重新核对范围。",
  CASH_SCOPE_CHANGE_UNSUPPORTED:
    "本月已有生效范围；本片不支持替换或月中范围变更。",
  CASH_SCOPE_REVIEW_REQUIRED: "尚未完成所需现金范围资格复核。",
  APPROVAL_STALE: "内容或引用已变化，原审批摘要不能继续使用。",
  SELF_APPROVAL_FORBIDDEN: "编制人不能复核自己的范围，请使用独立复核账号。",
};
type Props = {
  bookId: string;
  yearId: string;
  year: number;
  standard: string;
  role: string | undefined;
  active: boolean;
  canRead: boolean;
  canCreate: boolean;
  canApprove: boolean;
  restriction: string | null;
  accounts: Account[];
  periods: Period[];
  readable: (error: unknown) => string;
  onReadDenied: () => void;
};
export function CashScopePanel({
  bookId,
  yearId,
  year,
  standard,
  role,
  active,
  canRead,
  canCreate,
  canApprove,
  restriction,
  accounts,
  periods,
  readable,
  onReadDenied,
}: Props) {
  const base = `/finance/v1/books/${bookId}/years/${yearId}/cashflow/scope-revisions`;
  const client = useQueryClient();
  const [cursor, setCursor] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [action, setAction] = useState<"submit" | "approve" | "reject" | null>(
    null,
  );
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pending, setPending] = useState(false);
  const [form] = Form.useForm<{
    month: number;
    reason: string;
    items: CashScopeItem[];
  }>();
  const controller = useRef<AbortController | null>(null);
  const busy = useRef(false);
  const keys = useRef(new Map<string, string>());
  const objectUrls = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const [uploading, setUploading] = useState(false);
  const uploadLock = useRef(false);
  useEffect(() => {
    controller.current = new AbortController();
    return () => {
      controller.current?.abort();
      for (const [url, timer] of objectUrls.current) {
        clearTimeout(timer);
        URL.revokeObjectURL(url);
      }
      objectUrls.current.clear();
      void client.cancelQueries({
        queryKey: ["finance", bookId, yearId, "cash-scope"],
      });
      client.removeQueries({
        queryKey: ["finance", bookId, yearId, "cash-scope"],
      });
    };
  }, [bookId, yearId, client]);
  const identity = useQuery({
    queryKey: ["finance", bookId, yearId, "cash-scope", "actor"],
    enabled: active && canRead,
    queryFn: ({ signal }) => api<{ userId: string }>("/auth/me", { signal }),
  });
  const list = useQuery({
    queryKey: ["finance", bookId, yearId, "cash-scope", "list", cursor],
    enabled: active && canRead,
    queryFn: ({ signal }) =>
      api<{
        items: CashScopeSummary[];
        nextCursor: string | null;
        asOfWatermark: unknown;
      }>(
        `${base}?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
        { signal },
      ),
  });
  const detail = useQuery({
    queryKey: ["finance", bookId, yearId, "cash-scope", "detail", selectedId],
    enabled: active && canRead && !!selectedId,
    queryFn: ({ signal }) =>
      api<CashScopeDetail>(`${base}/${selectedId}`, { signal }),
  });
  const originals = useQuery({
    queryKey: ["finance", bookId, yearId, "cash-scope", "originals"],
    enabled: active && canCreate && creating,
    queryFn: ({ signal }) =>
      api<{ id: string; filename: string; sha256: string }[]>(
        `/finance/v1/books/${bookId}/years/${yearId}/originals`,
        { signal },
      ),
  });
  const readError =
    list.error ?? detail.error ?? identity.error ?? originals.error;
  const denied = readError instanceof ApiError && readError.status === 403;
  useEffect(() => {
    if (denied) onReadDenied();
  }, [denied, onReadDenied]);
  async function uploadEvidence(file: File, itemIndex: number) {
    if (uploadLock.current || !canCreate || role !== "OWNER" || restriction)
      return;
    if (file.size > 5 * 1024 * 1024) {
      setError("原件最大5MiB");
      return;
    }
    const signal = controller.current?.signal;
    if (!signal || signal.aborted) return;
    uploadLock.current = true;
    setUploading(true);
    setError("");
    try {
      const path = `/finance/v1/books/${bookId}/years/${yearId}/originals?filename=${encodeURIComponent(file.name)}`;
      const bytes = await file.arrayBuffer();
      const digest = await crypto.subtle.digest("SHA-256", bytes);
      // Local digest only matches unknown-outcome retries; server hashes stored bytes independently.
      const fingerprint = JSON.stringify([
        path,
        Array.from(new Uint8Array(digest), (b) =>
          b.toString(16).padStart(2, "0"),
        ).join(""),
      ]);
      const idempotencyKey =
        keys.current.get(fingerprint) ?? crypto.randomUUID();
      keys.current.set(fingerprint, idempotencyKey);
      const token = await csrf(signal);
      const original = await api<{ id: string }>(path, {
        method: "POST",
        headers: {
          "Content-Type": "application/octet-stream",
          [token.headerName]: token.token,
          "Idempotency-Key": idempotencyKey,
        },
        body: file,
        signal,
      });
      keys.current.delete(fingerprint);
      await client.invalidateQueries({
        queryKey: ["finance", bookId, yearId, "cash-scope", "originals"],
      });
      if (!signal.aborted) {
        form.setFieldValue(
          ["items", itemIndex, "evidenceOriginalId"],
          original.id,
        );
        setNotice("原始字节已保存并选中，服务端计算哈希；不代表已验真或验签。");
      }
    } catch (cause) {
      if (!signal.aborted) {
        setError(message(cause));
        if (cause instanceof ApiError && cause.status === 403) onReadDenied();
      }
    } finally {
      uploadLock.current = false;
      if (!signal.aborted) setUploading(false);
    }
  }
  async function downloadEvidence(id: string, filename: string) {
    const signal = controller.current?.signal;
    if (!signal || signal.aborted) return;
    try {
      const blob = await apiBlob(
        `/finance/v1/books/${bookId}/years/${yearId}/originals/${id}/content`,
        { signal },
      );
      if (signal.aborted) return;
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename || "original";
      link.click();
      const timer = setTimeout(() => {
        URL.revokeObjectURL(url);
        objectUrls.current.delete(url);
      }, 1000);
      objectUrls.current.set(url, timer);
    } catch (cause) {
      if (!signal.aborted) {
        setError(message(cause));
        if (cause instanceof ApiError && cause.status === 403) onReadDenied();
      }
    }
  }
  function message(cause: unknown) {
    return cause instanceof Error && errors[cause.message]
      ? errors[cause.message]
      : readable(cause);
  }
  const data = detail.data;
  const actor = identity.data?.userId;
  const periodWritable = Boolean(
    data &&
    periods.some(
      (period) =>
        period.month === Number(data.periodStart.slice(5, 7)) &&
        period.status === "OPEN",
    ),
  );
  const canSubmit = Boolean(
    periodWritable &&
    !detail.isError &&
    canCreate &&
    role === "OWNER" &&
    actor &&
    data?.preparedBy === actor &&
    data.status === "DRAFT",
  );
  const canReview = Boolean(
    periodWritable &&
    !detail.isError &&
    canApprove &&
    ["OWNER", "REVIEWER"].includes(role ?? "") &&
    actor &&
    data?.preparedBy !== actor &&
    data?.lastEditedBy !== actor &&
    data?.status === "SUBMITTED",
  );
  async function write(
    path: string,
    payload: Record<string, unknown>,
    done: (result: CashScopeDetail) => void,
  ) {
    if (busy.current || restriction) return;
    const signal = controller.current?.signal;
    if (!signal || signal.aborted) return;
    busy.current = true;
    setPending(true);
    setError("");
    setNotice("");
    const fingerprint = JSON.stringify([path, payload]);
    const idempotencyKey = keys.current.get(fingerprint) ?? crypto.randomUUID();
    keys.current.set(fingerprint, idempotencyKey);
    try {
      const result = await mutate<CashScopeDetail>(
        path,
        "POST",
        { ...payload, idempotencyKey },
        signal,
      );
      keys.current.delete(fingerprint);
      await client.invalidateQueries({
        queryKey: ["finance", bookId, yearId, "cash-scope"],
      });
      if (!signal.aborted) {
        done(result);
        setNotice(
          "请求已处理，请刷新核对权威状态；范围资格批准不代表流水、分配或报表已完成。",
        );
      }
    } catch (cause) {
      if (!signal.aborted) setError(message(cause));
    } finally {
      busy.current = false;
      if (!signal.aborted) setPending(false);
    }
  }
  function create(values: {
    month: number;
    reason: string;
    items: CashScopeItem[];
  }) {
    if (!canCreate || role !== "OWNER" || uploading) return;
    try {
      void write(base, cashCreatePayload(standard, year, values), (result) => {
        setCreating(false);
        setSelectedId(result.id);
      });
    } catch (cause) {
      setError(message(cause));
    }
  }
  if (!canRead || denied) return null;
  const accountOptions = accounts
    .filter(
      (a) => a.enabled && a.category === "ASSET" && a.normalSide === "DEBIT",
    )
    .map((a) => ({ value: a.id, label: `${a.code} ${a.name}` }));
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title="现金范围资格复核 · 单法人 / 人民币 / 完整自然月"
        description={`当前制度模板：${standard === "CAS" ? "企业准则 CAS-CF-2019-A2" : standard === "SMALL" ? "小企业准则 SBE-CF-2011" : "不可用"}。仅复核库存现金与活期存款资格；未涵盖现金流水、分配、覆盖核对或正式三表。来源证据未自动验真。`}
      />
      {restriction && <Alert type="warning" title={restriction} />}
      <Space>
        <Button
          type="primary"
          disabled={!canCreate || role !== "OWNER" || !!restriction || pending}
          onClick={() => {
            setCreating(true);
            setError("");
            form.resetFields();
          }}
        >
          编制月度范围
        </Button>
        <Button loading={list.isFetching} onClick={() => void list.refetch()}>
          刷新
        </Button>
      </Space>
      {(error || readError) && (
        <Alert type="error" title={error || message(readError)} />
      )}{" "}
      {notice && <Alert type="success" title={notice} />}
      <Table<CashScopeSummary>
        rowKey="id"
        dataSource={list.isError ? [] : (list.data?.items ?? [])}
        loading={list.isFetching}
        pagination={false}
        scroll={{ x: 850 }}
        columns={[
          { title: "月初", dataIndex: "periodStart" },
          { title: "月末", dataIndex: "periodEnd" },
          { title: "模板", dataIndex: "templateId" },
          {
            title: "状态",
            dataIndex: "status",
            render: (v) => <Tag>{labels[v] ?? v}</Tag>,
          },
          { title: "编制人", dataIndex: "preparedBy" },
          { title: "内容版本", dataIndex: "contentVersion" },
          { title: "范围水位", dataIndex: "scopeVersion" },
          {
            title: "操作",
            render: (_, row) => (
              <Button
                onClick={() => {
                  setSelectedId(row.id);
                  if (selectedId === row.id) void detail.refetch();
                }}
              >
                查看 / 复核
              </Button>
            ),
          },
        ]}
      />
      <Space>
        <Button
          disabled={!cursor || list.isFetching}
          onClick={() => setCursor(null)}
        >
          返回首屏
        </Button>
        <Button
          disabled={!list.data?.nextCursor || list.isFetching || list.isError}
          onClick={() => setCursor(list.data?.nextCursor ?? null)}
        >
          下一页
        </Button>
      </Space>
      <Modal
        title="月度现金范围资格详情"
        open={!!selectedId}
        onCancel={() => setSelectedId(null)}
        footer={null}
        width={1000}
      >
        <Button
          loading={detail.isFetching}
          onClick={() => void detail.refetch()}
        >
          刷新权威详情
        </Button>
        {data && !detail.isError && (
          <Space orientation="vertical" style={{ width: "100%" }}>
            {!periodWritable && (
              <Alert
                type="warning"
                title="该范围所覆盖期间未开放，不能提交或审签；仍可查阅历史范围。"
              />
            )}
            <Descriptions
              column={2}
              items={[
                {
                  key: "period",
                  label: "完整期间",
                  children: `${data.periodStart} 至 ${data.periodEnd}`,
                },
                {
                  key: "status",
                  label: "状态",
                  children: labels[data.status] ?? data.status,
                },
                { key: "template", label: "模板", children: data.templateId },
                { key: "version", label: "记录版本", children: data.version },
                {
                  key: "content",
                  label: "内容版本",
                  children: data.contentVersion,
                },
                {
                  key: "scope",
                  label: "范围水位",
                  children: data.scopeVersion ?? "尚未批准",
                },
                {
                  key: "digest",
                  label: "批准绑定摘要",
                  children: data.contentDigest,
                },
                { key: "maker", label: "编制人", children: data.preparedBy },
                {
                  key: "reviewer",
                  label: "复核人",
                  children: data.approvedBy ?? "未复核",
                },
                { key: "reason", label: "编制依据", children: data.reason },
                {
                  key: "decision",
                  label: "处理意见",
                  children: data.decisionReason ?? "—",
                },
              ]}
            />
            <Table
              rowKey="accountId"
              dataSource={data.items}
              pagination={false}
              scroll={{ x: 900 }}
              columns={[
                {
                  title: "科目",
                  dataIndex: "accountSnapshot",
                  render: (snapshot) => {
                    const a = snapshot as { code?: string; name?: string };
                    return `${a?.code ?? ""} ${a?.name ?? ""}`;
                  },
                },
                {
                  title: "类别",
                  dataIndex: "instrumentKind",
                  render: (v) => labels[v] ?? v,
                },
                {
                  title: "资格",
                  dataIndex: "qualification",
                  render: (v) => labels[v] ?? v,
                },
                {
                  title: "限制",
                  dataIndex: "restrictionKind",
                  render: (v) => labels[v] ?? v,
                },
                {
                  title: "随时支取",
                  dataIndex: "availableOnDemand",
                  render: (v) => (v ? "是" : "否"),
                },
                { title: "资格依据", dataIndex: "eligibilityBasis" },
                {
                  title: "单一工具声明",
                  dataIndex: "singleCashInstrument",
                  render: (value) =>
                    value === true ? "编制人已声明（非验真）" : "未声明",
                },
                { title: "证据文件", dataIndex: "originalFilename" },
                {
                  title: "复核证据",
                  render: (_, item) => (
                    <Button
                      onClick={() =>
                        void downloadEvidence(
                          item.evidenceOriginalId,
                          item.originalFilename ?? "original",
                        )
                      }
                    >
                      下载实际原件
                    </Button>
                  ),
                },
              ]}
              expandable={{
                expandedRowRender: (item) => (
                  <Descriptions
                    column={1}
                    items={[
                      {
                        key: "account",
                        label: "科目快照摘要",
                        children: item.accountVersionDigest,
                      },
                      {
                        key: "original",
                        label: "服务端原件SHA-256",
                        children: item.originalSha256,
                      },
                      {
                        key: "id",
                        label: "原件ID",
                        children: item.evidenceOriginalId,
                      },
                    ]}
                  />
                ),
              }}
            />
            <Collapse
              items={[
                {
                  key: "snapshots",
                  label: "查看服务端不可变快照及数据水位",
                  children: (
                    <pre
                      style={{
                        whiteSpace: "pre-wrap",
                        overflowWrap: "anywhere",
                      }}
                    >
                      {JSON.stringify(
                        {
                          items: data.items,
                          asOfWatermark: data.asOfWatermark,
                        },
                        null,
                        2,
                      )}
                    </pre>
                  ),
                },
              ]}
            />
            <Space>
              <Button
                disabled={
                  !canSubmit || !!restriction || pending || detail.isFetching
                }
                onClick={() => {
                  setAction("submit");
                  setReason("");
                }}
              >
                提交本人草稿
              </Button>
              <Button
                disabled={
                  !canReview || !!restriction || pending || detail.isFetching
                }
                onClick={() => {
                  setAction("approve");
                  setReason("");
                }}
              >
                独立批准资格
              </Button>
              <Button
                danger
                disabled={
                  !canReview || !!restriction || pending || detail.isFetching
                }
                onClick={() => {
                  setAction("reject");
                  setReason("");
                }}
              >
                驳回并说明原因
              </Button>
            </Space>
            {error && <Alert type="error" title={error} />}
          </Space>
        )}
        {detail.error && <Alert type="error" title={message(detail.error)} />}
      </Modal>
      <Modal
        title={
          action === "submit"
            ? "提交范围资格"
            : action === "approve"
              ? "独立批准月度范围资格"
              : "驳回月度范围资格"
        }
        open={!!action}
        onCancel={() => !pending && setAction(null)}
        confirmLoading={pending}
        okButtonProps={{
          disabled:
            !reason.trim() ||
            !!restriction ||
            detail.isFetching ||
            detail.isError ||
            !periodWritable,
        }}
        onOk={() => {
          if (
            !action ||
            !data ||
            (action === "submit" ? !canSubmit : !canReview)
          )
            return;
          void write(
            `${base}/${data.id}/${action}`,
            cashDecisionPayload(data, reason),
            (result) => {
              setAction(null);
              setSelectedId(result.id);
            },
          );
        }}
      >
        <Alert title="操作绑定当前内容版本和摘要。批准仅建立该月现金范围资格，不代表对账、现金流分配或正式报表已完成。" />
        <Input.TextArea
          aria-label="处理意见"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          maxLength={1000}
        />
        {error && <Alert type="error" title={error} />}
      </Modal>
      <Modal
        title="编制月度现金范围资格"
        open={creating}
        onCancel={() => !pending && !uploading && setCreating(false)}
        footer={null}
        width={1050}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={create}
          initialValues={{
            items: [
              {
                instrumentKind: "CASH_ON_HAND",
                qualification: "INCLUDED",
                restrictionKind: "NONE",
                availableOnDemand: true,
              },
            ],
          }}
        >
          <Typography.Paragraph>
            模板：
            {["CAS", "SMALL"].includes(standard)
              ? cashTemplate(standard)
              : "不可用"}
            ；范围为单法人人民币账套。必须选择已上传的同年度证据，不能手工提供哈希或虚构科目版本。
          </Typography.Paragraph>
          <Form.Item name="month" label="完整自然月" rules={required}>
            <Select
              options={periods
                .filter((p) => p.status === "OPEN")
                .map((p) => ({
                  value: p.month,
                  label: `${year}年${p.month}月（完整月）`,
                }))}
            />
          </Form.Item>
          <Form.Item name="reason" label="编制理由与依据" rules={required}>
            <Input.TextArea maxLength={1000} />
          </Form.Item>
          <Alert
            type="warning"
            title="本片要求一个年度科目对应一种独立现金工具；混合资金总账科目不支持，尚未建立完整银行账户域。请在资格依据中写明实际独立工具及判断证据。"
          />
          <Form.List name="items">
            {(fields, { add, remove }) => (
              <Space orientation="vertical" style={{ width: "100%" }}>
                {fields.map((field) => (
                  <div
                    key={field.key}
                    style={{ border: "1px solid #ddd", padding: 12 }}
                  >
                    <Form.Item
                      name={[field.name, "accountId"]}
                      label="有效借方资产科目"
                      rules={required}
                    >
                      <Select options={accountOptions} />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "singleCashInstrument"]}
                      initialValue={false}
                      valuePropName="checked"
                      rules={[
                        {
                          validator: (_, value) =>
                            value === true
                              ? Promise.resolve()
                              : Promise.reject(
                                  new Error("请主动确认单一现金工具声明"),
                                ),
                        },
                      ]}
                    >
                      <Checkbox>
                        此年度科目仅对应一个独立现金工具/账户（混合资金或多银行总账科目不支持；本声明不等于银行验真）
                      </Checkbox>
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "instrumentKind"]}
                      label="资金工具类型"
                      rules={required}
                    >
                      <Select
                        options={[
                          { value: "CASH_ON_HAND", label: "库存现金" },
                          { value: "DEMAND_DEPOSIT", label: "活期存款" },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "qualification"]}
                      label="范围资格"
                      rules={required}
                    >
                      <Select
                        options={[
                          { value: "INCLUDED", label: "纳入" },
                          { value: "EXCLUDED", label: "排除" },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "restrictionKind"]}
                      label="使用限制"
                      rules={required}
                    >
                      <Select
                        options={[
                          { value: "NONE", label: "无限制" },
                          { value: "RESTRICTED", label: "受限" },
                          { value: "OTHER", label: "其他" },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "availableOnDemand"]}
                      label="可随时支取"
                      valuePropName="checked"
                    >
                      <Switch />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "eligibilityBasis"]}
                      label="资格判断依据"
                      rules={required}
                    >
                      <Input.TextArea maxLength={2000} />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "evidenceOriginalId"]}
                      label="真实已上传原件或扫描件"
                      rules={required}
                    >
                      <Select
                        loading={originals.isFetching}
                        disabled={uploading}
                        options={originals.data?.map((o) => ({
                          value: o.id,
                          label: `${o.filename} · ${o.sha256}`,
                        }))}
                      />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "evidenceSource"]}
                      label="证据来源（记入资格依据）"
                      rules={required}
                    >
                      <Select
                        options={[
                          { value: "ELECTRONIC_ORIGINAL", label: "电子原件" },
                          { value: "PAPER_SCAN", label: "纸质凭证扫描件" },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item noStyle shouldUpdate>
                      {() =>
                        form.getFieldValue([
                          "items",
                          field.name,
                          "evidenceSource",
                        ]) === "PAPER_SCAN" ? (
                          <Form.Item
                            name={[field.name, "paperLocation"]}
                            label="纸质原件保管位置（记入资格依据）"
                            rules={required}
                          >
                            <Input maxLength={300} />
                          </Form.Item>
                        ) : null
                      }
                    </Form.Item>
                    <label>
                      直接上传真实原件或纸质扫描件（最多5MiB）：
                      <input
                        type="file"
                        disabled={
                          uploading || pending || !!restriction || !canCreate
                        }
                        onChange={(e) => {
                          const file = e.target.files?.[0];
                          if (file) void uploadEvidence(file, field.name);
                          e.target.value = "";
                        }}
                      />
                    </label>
                    <Button
                      danger
                      disabled={fields.length <= 1 || uploading}
                      onClick={() => remove(field.name)}
                    >
                      移除此项
                    </Button>
                  </div>
                ))}
                <Button
                  disabled={fields.length >= 20 || uploading}
                  onClick={() =>
                    add({
                      instrumentKind: "CASH_ON_HAND",
                      qualification: "EXCLUDED",
                      restrictionKind: "OTHER",
                      availableOnDemand: false,
                    })
                  }
                >
                  增加范围科目
                </Button>
              </Space>
            )}
          </Form.List>
          {(error || originals.error) && (
            <Alert type="error" title={error || message(originals.error)} />
          )}
          <Button
            type="primary"
            htmlType="submit"
            loading={pending}
            disabled={
              !canCreate ||
              !!restriction ||
              pending ||
              uploading ||
              originals.isError
            }
          >
            保存不可变范围草稿
          </Button>
        </Form>
      </Modal>
    </Space>
  );
}
