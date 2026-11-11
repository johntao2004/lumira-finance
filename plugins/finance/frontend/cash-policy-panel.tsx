import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Checkbox,
  Collapse,
  Descriptions,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { api, ApiError, mutate } from "@/platform/api/client";
import { useMovementEvidence } from "./movement-evidence";
import { movementDirectionLabel } from "./cash-movement-contract";
import {
  policyAssertions,
  purposeLabels,
  policyCreatePayload,
  policyActionPayload,
  policyPublicationLabel,
  type PolicyCatalog,
  type PolicyDetail,
  type PolicySummary,
  type PolicyRetirement,
  type PolicyForm,
} from "./cash-policy-contract";
const required = [{ required: true, message: "请填写此项" }];
const labels: Record<string, string> = {
  DRAFT: "政策草稿",
  SUBMITTED: "待独立发布审签",
  APPROVED: "已发布政策",
  REJECTED: "提案已驳回",
  CANCELLED: "提案已取消",
  RETIRED: "已受控退役",
};
const retirementLabels: Record<string, string> = {
  REQUESTED: "退役申请待审（冻结未来新用）",
  APPROVED: "已批准退役",
  REJECTED: "退役申请被驳回",
  CANCELLED: "申请人已取消退役申请",
};
const errors: Record<string, string> = {
  CASH_POLICY_PURPOSE_UNSUPPORTED:
    "业务种类与具体用途不属于该制度的普通候选目录，不能把复杂内容改名为普通业务。",
  CASH_POLICY_SUBMISSION_EXPIRED:
    "已超出7天运营审签时限，不能批准；可驳回或取消，并按最新前序建立新的不可变修订/请求。",
  CASH_POLICY_OVERLAP:
    "同维度存在适用现金日期交叠的已发布政策；待审退役仍占用，须先独立批准退役。",
  CASH_POLICY_STATE_CONFLICT:
    "政策状态或修订前序已变化，请刷新最新任意状态修订后重试。",
  CASH_POLICY_RETIREMENT_PENDING:
    "已有待审退役请求，未来新用被冻结，不能重复申请。",
  APPROVAL_STALE:
    "当前制备人/退役请求人权限或证据已失效；历史可查，待审可由独立复核人驳回。",
};
type Action =
  | "submit"
  | "approve"
  | "reject"
  | "cancel"
  | "retire"
  | "retire-approve"
  | "retire-reject"
  | "retire-cancel";
type Props = {
  bookId: string;
  yearId: string;
  year: number;
  active: boolean;
  canRead: boolean;
  canPrepare: boolean;
  canReview: boolean;
  restriction: string | null;
  readable: (error: unknown) => string;
  onReadDenied: () => void;
};
export function CashPolicyPanel({
  bookId,
  yearId,
  year,
  active,
  canRead,
  canPrepare,
  canReview,
  restriction,
  readable,
  onReadDenied,
}: Props) {
  const scope = `/finance/v1/books/${bookId}/years/${yearId}`;
  const base = `${scope}/cashflow/classification-policies`;
  const prefix = ["finance", bookId, yearId, "cash-policies"];
  const client = useQueryClient();
  const [cursor, setCursor] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const [kind, setKind] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [predecessor, setPredecessor] = useState<PolicySummary | null>(null);
  const [retireCursor, setRetireCursor] = useState<string | null>(null);
  const [retirement, setRetirement] = useState<PolicyRetirement | null>(null);
  const [action, setAction] = useState<Action | null>(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pending, setPending] = useState(false);
  const [form] = Form.useForm<PolicyForm>();
  const controller = useRef<AbortController | null>(null);
  const busy = useRef(false);
  const keys = useRef(new Map<string, string>());
  useEffect(() => {
    controller.current = new AbortController();
    return () => {
      controller.current?.abort();
      void client.cancelQueries({ queryKey: prefix });
      client.removeQueries({ queryKey: prefix });
    };
  }, [bookId, yearId, client]);
  const actor = useQuery({
    queryKey: [...prefix, "actor"],
    enabled: active && canRead,
    queryFn: ({ signal }) => api<{ userId: string }>("/auth/me", { signal }),
  });
  const catalog = useQuery({
    queryKey: [...prefix, "catalog"],
    enabled: active && canRead,
    queryFn: ({ signal }) => api<PolicyCatalog>(`${base}/catalog`, { signal }),
  });
  const list = useQuery({
    queryKey: [...prefix, "list", status, kind, cursor],
    enabled: active && canRead,
    queryFn: ({ signal }) =>
      api<{ items: PolicySummary[]; nextCursor: string | null }>(
        `${base}?limit=20${status ? `&status=${status}` : ""}${kind ? `&transactionKind=${encodeURIComponent(kind)}` : ""}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
        { signal },
      ),
  });
  const detail = useQuery({
    queryKey: [...prefix, "detail", selectedId],
    enabled: active && canRead && !!selectedId,
    queryFn: ({ signal }) =>
      api<PolicyDetail>(`${base}/${selectedId}`, { signal }),
  });
  const history = useQuery({
    queryKey: [...prefix, "retirements", selectedId, retireCursor],
    enabled: active && canRead && !!selectedId,
    queryFn: ({ signal }) =>
      api<{ items: PolicyRetirement[]; nextCursor: string | null }>(
        `${base}/${selectedId}/retirement-requests?limit=20${retireCursor ? `&cursor=${encodeURIComponent(retireCursor)}` : ""}`,
        { signal },
      ),
  });
  const originals = useQuery({
    queryKey: [...prefix, "originals"],
    enabled: active && canRead && canPrepare && creating,
    queryFn: ({ signal }) =>
      api<{ id: string; filename: string; sha256: string }[]>(
        `${scope}/originals`,
        { signal },
      ),
  });
  const reading = [actor, catalog, list, detail, history, originals];
  const readError = reading.find((query) => query.isError)?.error;
  const denied = reading.some(
    (query) =>
      query.isError &&
      query.error instanceof ApiError &&
      query.error.status === 403,
  );
  useEffect(() => {
    if (denied) onReadDenied();
  }, [denied, onReadDenied]);
  function message(cause: unknown) {
    return cause instanceof Error && errors[cause.message]
      ? errors[cause.message]
      : readable(cause);
  }
  const evidence = useMovementEvidence(
    scope,
    canRead && canPrepare && !restriction,
    onReadDenied,
    message,
  );
  const data = detail.data;
  const actorId = actor.data?.userId;
  const own = Boolean(actorId && data?.preparedBy === actorId);
  const independent = Boolean(actorId && data && data.preparedBy !== actorId);
  const writable = Boolean(
    !restriction && data && !detail.isError && !detail.isFetching,
  );
  const assertionsWatched = Form.useWatch("assertions", form);
  const selectedKind = Form.useWatch("catalogKind", form);
  const selectedTuple = catalog.data?.items.find(
    (tuple) => tuple.transactionKind === selectedKind,
  );
  function allowed(which: Action) {
    if (!data || !writable) return false;
    if (which === "submit") return canPrepare && own && data.status === "DRAFT";
    if (which === "cancel")
      return canPrepare && own && ["DRAFT", "SUBMITTED"].includes(data.status);
    if (which === "approve" || which === "reject")
      return canRead && canReview && independent && data.status === "SUBMITTED";
    if (which === "retire")
      return (
        canPrepare && data.status === "APPROVED" && !data.pendingRetirementId
      );
    if (
      data.status !== "APPROVED" ||
      history.isError ||
      history.isFetching ||
      retirement?.policyId !== data.id
    )
      return false;
    if (which === "retire-cancel")
      return (
        canPrepare &&
        retirement?.requestedBy === actorId &&
        retirement?.status === "REQUESTED"
      );
    return (
      canRead &&
      canReview &&
      independent &&
      retirement?.requestedBy !== actorId &&
      retirement?.status === "REQUESTED"
    );
  }
  async function write(
    path: string,
    payload: Record<string, unknown>,
    done: (result: unknown) => void,
  ) {
    if (busy.current || restriction || !canRead) return;
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
      const result = await mutate(
        path,
        "POST",
        { ...payload, idempotencyKey },
        signal,
      );
      keys.current.delete(fingerprint);
      await client.invalidateQueries({ queryKey: ["finance", bookId, yearId] });
      if (!signal.aborted) {
        done(result);
        setNotice(
          "请求已处理，请刷新权威发布/退役状态。发布资格不等于任何来源已核实、已分配或已入报表。",
        );
      }
    } catch (cause) {
      if (!signal.aborted) setError(message(cause));
    } finally {
      busy.current = false;
      if (!signal.aborted) setPending(false);
    }
  }
  function openCreate(previous: PolicySummary | null) {
    setPredecessor(previous);
    setCreating(true);
    setError("");
    form.resetFields();
    if (previous)
      form.setFieldsValue({
        policyCode: previous.policyCode,
        catalogKind: previous.transactionKind,
      });
  }
  function create(values: PolicyForm) {
    if (!canPrepare || evidence.uploading || !catalog.data) return;
    try {
      void write(
        base,
        policyCreatePayload(values, catalog.data, year, predecessor),
        (result) => {
          setCreating(false);
          setSelectedId((result as PolicyDetail).id);
          setRetireCursor(null);
        },
      );
    } catch (cause) {
      setError(message(cause));
    }
  }
  function start(which: Action, request?: PolicyRetirement) {
    setAction(which);
    setRetirement(request ?? null);
    setReason("");
    setError("");
  }
  function perform() {
    if (!action || !data || !allowed(action) || !reason.trim()) return;
    let path: string, payload: Record<string, unknown>;
    if (action === "retire") {
      path = `${base}/${data.id}/retirement-requests`;
      payload = policyActionPayload(data, reason);
    } else if (action.startsWith("retire-")) {
      if (!retirement) return;
      path = `${base}/${data.id}/retirement-requests/${retirement.id}/${action.slice(7)}`;
      payload = policyActionPayload(retirement, reason);
    } else {
      path = `${base}/${data.id}/${action}`;
      payload = policyActionPayload(data, reason);
    }
    void write(path, payload, () => setAction(null));
  }
  if (!canRead || denied) return null;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title="普通现金分类政策发布（不是现金流完成）"
        description="七种明确业务用途必须逐项判断，供应商、工资或税款名称不能一律推定为经营活动。只发布未来逐笔评估的政策资格：不核实来源、不占用声明、不分配、不生成预览或正式报表。复杂资本化工资、利息、性质不明税款、内部划转、退款、净额、外币及控制交易不支持。"
      />
      {restriction && <Alert type="warning" title={restriction} />}
      <Alert title="政策按年度开放状态处理，不要求选择或伪造某个月份。现金日期适用区间与今天不同：将来每次使用须另核对实际现金日期及当时开放年月。" />
      <Space wrap>
        <Button
          type="primary"
          disabled={!canPrepare || !!restriction || pending}
          onClick={() => openCreate(null)}
        >
          新编码首修订
        </Button>
        <Select
          aria-label="政策工作流筛选"
          value={status}
          options={[
            { value: "", label: "全部状态" },
            ...Object.keys(labels).map((value) => ({
              value,
              label: labels[value],
            })),
          ]}
          onChange={(value) => {
            setStatus(value);
            setCursor(null);
          }}
        />
        <Select
          aria-label="政策用途筛选"
          value={kind}
          style={{ minWidth: 250 }}
          options={[
            { value: "", label: "全部明确用途" },
            ...(catalog.data?.items ?? []).map((tuple) => ({
              value: tuple.transactionKind,
              label:
                purposeLabels[tuple.businessPurpose] ?? tuple.businessPurpose,
            })),
          ]}
          onChange={(value) => {
            setKind(value);
            setCursor(null);
          }}
        />
        <Button
          loading={list.isFetching}
          onClick={() => {
            void list.refetch();
            void catalog.refetch();
          }}
        >
          刷新
        </Button>
      </Space>
      {(error || readError || evidence.error) && (
        <Alert
          type="error"
          title={error || (readError ? message(readError) : evidence.error)}
        />
      )}{" "}
      {notice && <Alert type="success" title={notice} />}
      <Collapse
        items={[
          {
            key: "catalog",
            label: `查看服务端七项候选目录（不是已发布政策） · ${catalog.data?.templateId ?? "正在读取"}`,
            children: (
              <Table
                rowKey="transactionKind"
                pagination={false}
                dataSource={catalog.data?.items ?? []}
                columns={[
                  {
                    title: "具体业务用途",
                    dataIndex: "businessPurpose",
                    render: (value) => purposeLabels[value] ?? value,
                  },
                  {
                    title: "方向",
                    dataIndex: "direction",
                    render: movementDirectionLabel,
                  },
                  { title: "模板行", dataIndex: "rowId" },
                  { title: "语义行", dataIndex: "semanticLeaf" },
                ]}
              />
            ),
          },
        ]}
      />
      <Table<PolicySummary>
        rowKey="id"
        loading={list.isFetching}
        dataSource={list.isError ? [] : (list.data?.items ?? [])}
        pagination={false}
        scroll={{ x: 1200 }}
        columns={[
          { title: "政策编码", dataIndex: "policyCode" },
          { title: "修订序号", dataIndex: "revision" },
          {
            title: "用途",
            dataIndex: "businessPurpose",
            render: (value) => purposeLabels[value] ?? value,
          },
          { title: "现金日期适用起", dataIndex: "effectiveFrom" },
          { title: "现金日期适用止", dataIndex: "effectiveTo" },
          {
            title: "工作流",
            dataIndex: "status",
            render: (value) => <Tag>{labels[value] ?? value}</Tag>,
          },
          {
            title: "后续新用资格（非已使用）",
            render: (_, row) => policyPublicationLabel(row),
          },
          {
            title: "操作",
            render: (_, row) => (
              <Button
                onClick={() => {
                  setSelectedId(row.id);
                  setRetireCursor(null);
                  if (selectedId === row.id) {
                    void detail.refetch();
                    void history.refetch();
                  }
                }}
              >
                查看依据 / 审签 / 退役
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
          首屏
        </Button>
        <Button
          disabled={!list.data?.nextCursor || list.isFetching || list.isError}
          onClick={() => setCursor(list.data?.nextCursor ?? null)}
        >
          下一页
        </Button>
      </Space>
      <Modal
        title="政策发布资格与修订历史"
        open={!!selectedId}
        onCancel={() => setSelectedId(null)}
        footer={null}
        width={1150}
      >
        <Button
          loading={detail.isFetching}
          onClick={() => {
            void detail.refetch();
            void history.refetch();
          }}
        >
          刷新权威政策与退役历史
        </Button>
        {data && !detail.isError && (
          <Space orientation="vertical" style={{ width: "100%" }}>
            <Alert
              type={data.activeForNewUse ? "info" : "warning"}
              title={policyPublicationLabel(data)}
              description="activeForNewUse仅表示已发布且无待审退役，不检验今天是否落在现金日期适用区间；不是来源核实、业务适用判断或报表贡献。"
            />
            <Descriptions
              column={2}
              items={[
                {
                  key: "code",
                  label: "政策 / 修订",
                  children: `${data.policyCode} / ${data.revision}`,
                },
                {
                  key: "previous",
                  label: "前一提案修订（不等于前一有效政策）",
                  children: data.supersedesPolicyId ?? "首修订",
                },
                {
                  key: "status",
                  label: "工作流",
                  children: labels[data.status] ?? data.status,
                },
                {
                  key: "purpose",
                  label: "明确用途",
                  children:
                    purposeLabels[data.businessPurpose] ?? data.businessPurpose,
                },
                {
                  key: "period",
                  label: "未来实际现金日期适用区间（含首尾）",
                  children: `${data.effectiveFrom} 至 ${data.effectiveTo}`,
                },
                {
                  key: "template",
                  label: "制度模板 / 目录",
                  children: `${data.templateId} / ${data.dictionaryVersion}`,
                },
                {
                  key: "leaf",
                  label: "服务端映射行",
                  children: `${data.rowId} / ${data.semanticLeaf}`,
                },
                { key: "maker", label: "制备人", children: data.preparedBy },
                {
                  key: "approver",
                  label: "发布批准人",
                  children: data.approvedBy ?? "未批准",
                },
                {
                  key: "expiry",
                  label: "待审运营截止（非法律期限）",
                  children: data.submissionExpiresAt ?? "未提交",
                },
                {
                  key: "digest",
                  label: "不可变内容摘要",
                  children: data.contentDigest,
                },
                {
                  key: "version",
                  label: "版本 / 内容版本 / 发布水位",
                  children: `${data.version} / ${data.contentVersion} / ${data.policyWatermark}`,
                },
                { key: "basis", label: "政策依据", children: data.policyBasis },
                {
                  key: "applicability",
                  label: "适用性说明",
                  children: data.applicabilityStatement,
                },
                { key: "reason", label: "原始编制理由", children: data.reason },
                {
                  key: "decision",
                  label: "后续审签意见",
                  children: data.decisionReason ?? "—",
                },
              ]}
            />
            <Alert title="提交后7×24小时为运营审签时限，由服务端判断到期，不是法定期限。到期不能批准，但可驳回或取消后建立新不可变修订；已批准政策不因该提交时限到期而消失。" />
            <Table
              rowKey="originalId"
              dataSource={data.evidence}
              pagination={false}
              scroll={{ x: 1000 }}
              columns={[
                {
                  title: "角色",
                  dataIndex: "purpose",
                  render: (value) =>
                    value === "POLICY_BASIS" ? "政策依据" : "适用性支持",
                },
                { title: "真实原件", dataIndex: "filename" },
                { title: "证据说明", dataIndex: "description" },
                { title: "服务端SHA-256", dataIndex: "sha256", ellipsis: true },
                {
                  title: "操作",
                  render: (_, row) => (
                    <Button
                      onClick={() =>
                        void evidence.download(
                          row.originalId,
                          row.filename ?? "policy-evidence",
                        )
                      }
                    >
                      下载实际证据复核
                    </Button>
                  ),
                },
              ]}
            />
            <Collapse
              items={[
                {
                  key: "evidence",
                  label: "查看七项声明与服务端原件版本/摘要快照",
                  children: (
                    <pre
                      style={{
                        whiteSpace: "pre-wrap",
                        overflowWrap: "anywhere",
                      }}
                    >
                      {JSON.stringify(
                        {
                          assertions: data.assertions,
                          evidence: data.evidence,
                          limitations: data.limitations,
                        },
                        null,
                        2,
                      )}
                    </pre>
                  ),
                },
              ]}
            />
            <Space wrap>
              {(
                [
                  ["submit", "提交本人提案"],
                  ["approve", "独立批准发布"],
                  ["reject", "驳回提案"],
                  ["cancel", "取消本人提案"],
                  ["retire", "当前负责人申请受控退役"],
                ] as const
              ).map(([which, label]) => (
                <Button
                  key={which}
                  danger={which === "retire" || which === "cancel"}
                  disabled={!allowed(which) || pending}
                  onClick={() => start(which)}
                >
                  {label}
                </Button>
              ))}
              <Button
                disabled={!canPrepare || !!restriction || pending}
                onClick={() => openCreate(data)}
              >
                以此为前一提案起草新修订（须为最新任意状态）
              </Button>
            </Space>
            <Alert title="新修订的前序必须是同编码最新提案，包含草稿、已提交、被驳回或取消状态，并非上个有效政策。服务端最终核对前序与全部已发布政策的日期重叠；改编码也不能绕过重叠。" />
            <Typography.Title level={5}>受控退役请求历史</Typography.Title>
            <Alert title="待审退役不改写已发布历史，但冻结未来新用且继续占用日期重叠。7天运营期限后仍需独立驳回或由当前有权原请求人取消；只有独立批准才即时退役，不回溯重算历史。" />
            <Table<PolicyRetirement>
              rowKey="id"
              dataSource={history.isError ? [] : (history.data?.items ?? [])}
              pagination={false}
              scroll={{ x: 1150 }}
              expandable={{
                expandedRowRender: (request) => (
                  <Descriptions
                    column={1}
                    items={[
                      { key: "id", label: "请求ID", children: request.id },
                      {
                        key: "binding",
                        label: "绑定政策版本 / 摘要",
                        children: `${request.expectedPolicyVersion} / ${request.policyContentDigest}`,
                      },
                      {
                        key: "version",
                        label: "请求版本 / 摘要",
                        children: `${request.version} / ${request.contentDigest}`,
                      },
                    ]}
                  />
                ),
              }}
              columns={[
                {
                  title: "请求状态",
                  dataIndex: "status",
                  render: (value) => retirementLabels[value] ?? value,
                },
                { title: "请求人", dataIndex: "requestedBy" },
                { title: "原因", dataIndex: "reason" },
                { title: "运营审签截止", dataIndex: "reviewExpiresAt" },
                { title: "独立复核人", dataIndex: "reviewedBy" },
                { title: "复核意见", dataIndex: "decisionReason" },
                {
                  title: "操作",
                  render: (_, request) => (
                    <Space wrap>
                      <Button
                        disabled={
                          !writable ||
                          data.status !== "APPROVED" ||
                          history.isError ||
                          !canReview ||
                          !independent ||
                          request.requestedBy === actorId ||
                          request.status !== "REQUESTED" ||
                          history.isFetching ||
                          pending
                        }
                        onClick={() => start("retire-approve", request)}
                      >
                        独立批准退役
                      </Button>
                      <Button
                        disabled={
                          !writable ||
                          data.status !== "APPROVED" ||
                          history.isError ||
                          !canReview ||
                          !independent ||
                          request.requestedBy === actorId ||
                          request.status !== "REQUESTED" ||
                          history.isFetching ||
                          pending
                        }
                        onClick={() => start("retire-reject", request)}
                      >
                        驳回退役请求
                      </Button>
                      <Button
                        disabled={
                          !writable ||
                          data.status !== "APPROVED" ||
                          history.isError ||
                          !canPrepare ||
                          request.requestedBy !== actorId ||
                          request.status !== "REQUESTED" ||
                          history.isFetching ||
                          pending
                        }
                        onClick={() => start("retire-cancel", request)}
                      >
                        原请求人取消
                      </Button>
                    </Space>
                  ),
                },
              ]}
            />
            <Space>
              <Button
                disabled={!retireCursor || history.isFetching}
                onClick={() => setRetireCursor(null)}
              >
                退役历史首屏
              </Button>
              <Button
                disabled={
                  !history.data?.nextCursor ||
                  history.isFetching ||
                  history.isError
                }
                onClick={() =>
                  setRetireCursor(history.data?.nextCursor ?? null)
                }
              >
                退役历史下一页
              </Button>
            </Space>
            {(error || history.error || evidence.error) && (
              <Alert
                type="error"
                title={
                  error ||
                  (history.error ? message(history.error) : evidence.error)
                }
              />
            )}
          </Space>
        )}
        {detail.error && <Alert type="error" title={message(detail.error)} />}
      </Modal>
      <Modal
        title={
          action?.startsWith("retire")
            ? "受控政策退役处理"
            : "政策发布工作流处理"
        }
        open={!!action}
        onCancel={() => !pending && setAction(null)}
        confirmLoading={pending}
        okButtonProps={{
          disabled: !action || !allowed(action) || !reason.trim() || pending,
        }}
        onOk={perform}
      >
        <Alert
          type="warning"
          title={
            action === "retire-approve"
              ? "批准即刻退役并阻止未来新用；不回溯修改任何既有业务。"
              : "处理绑定当前记录版本和不可变内容摘要；原件保存不代表法律或银行验真。"
          }
          description={
            retirement
              ? `请求人 ${retirement.requestedBy}；原因 ${retirement.reason}；运营截止 ${retirement.reviewExpiresAt}`
              : undefined
          }
        />
        <Input.TextArea
          aria-label="政策处理理由"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          maxLength={1000}
        />
        {error && <Alert type="error" title={error} />}
      </Modal>
      <Modal
        title={predecessor ? "建立同编码新不可变修订" : "编制新政策编码首修订"}
        open={creating}
        onCancel={() => !pending && !evidence.uploading && setCreating(false)}
        footer={null}
        width={1100}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={create}
          initialValues={{
            assertions: {},
            evidence: [
              { purpose: "POLICY_BASIS" },
              { purpose: "APPLICABILITY_SUPPORT" },
            ],
          }}
        >
          <Alert
            title={
              predecessor
                ? `前一提案修订 ${predecessor.policyCode} / ${predecessor.revision} / ${labels[predecessor.status] ?? predecessor.status}；须为最新任意状态，服务端分配新序号`
                : "新编码首修订的前序为空；若编码已存在请从其最新任意状态详情建立新修订"
            }
            description="不复制已批准资格或自动勾选声明。新提案可先创建，但重叠的有效政策须受控退役后才能发布。"
          />
          <Button
            disabled={evidence.uploading || pending}
            onClick={() => {
              void catalog.refetch();
              void originals.refetch();
            }}
          >
            刷新目录与证据
          </Button>
          <Form.Item
            name="policyCode"
            label="政策编码（大写字母开头，3至50位）"
            rules={required}
          >
            <Input disabled={!!predecessor} maxLength={50} />
          </Form.Item>
          <Form.Item
            name="catalogKind"
            label="精确的普通业务种类 + 具体用途（服务端目录）"
            rules={required}
          >
            <Select
              disabled={!!predecessor}
              loading={catalog.isFetching}
              options={catalog.data?.items.map((tuple) => ({
                value: tuple.transactionKind,
                label: `${purposeLabels[tuple.businessPurpose] ?? tuple.businessPurpose} · ${movementDirectionLabel(tuple.direction)}`,
              }))}
            />
          </Form.Item>
          {selectedTuple && (
            <Alert
              title={`候选用途：${purposeLabels[selectedTuple.businessPurpose] ?? selectedTuple.businessPurpose}`}
              description={`服务端制度模板 ${catalog.data?.templateId}；候选行 ${selectedTuple.rowId} / ${selectedTuple.semanticLeaf}，并非已批准用途。`}
            />
          )}
          <Form.Item
            name="effectiveFrom"
            label="实际现金日期适用起（本年度 YYYY-MM-DD）"
            rules={required}
          >
            <Input maxLength={10} />
          </Form.Item>
          <Form.Item
            name="effectiveTo"
            label="实际现金日期适用止（本年度 YYYY-MM-DD，含首尾）"
            rules={required}
          >
            <Input maxLength={10} />
          </Form.Item>
          <Form.Item
            name="policyBasis"
            label="政策依据（不是来源自动核实）"
            rules={required}
          >
            <Input.TextArea maxLength={4000} />
          </Form.Item>
          <Form.Item
            name="applicabilityStatement"
            label="具体适用性和排除边界说明"
            rules={required}
          >
            <Input.TextArea maxLength={2000} />
          </Form.Item>
          <Form.Item name="reason" label="原始编制理由" rules={required}>
            <Input.TextArea maxLength={1000} />
          </Form.Item>
          <Typography.Title level={5}>
            七项边界声明（逐项主动勾选，不能以名称推断）
          </Typography.Title>
          {policyAssertions.map(([key, label]) => (
            <Form.Item
              key={key}
              name={["assertions", key]}
              initialValue={false}
              valuePropName="checked"
              rules={[
                {
                  validator: (_, value) =>
                    value === true
                      ? Promise.resolve()
                      : Promise.reject(new Error("须主动勾选此声明")),
                },
              ]}
            >
              <Checkbox>{label}</Checkbox>
            </Form.Item>
          ))}
          <Typography.Title level={5}>
            政策依据与适用性支持：两类真实原件
          </Typography.Title>
          <Alert title="至少各一份不同原件ID。不同UUID即使字节相同也不证明独立现实佐证或真实性；请在证据说明中说明真实来源。纸质扫描件请记录实体原件保管位置。" />
          <Form.List name="evidence">
            {(fields, { add, remove }) => (
              <Space orientation="vertical" style={{ width: "100%" }}>
                {fields.map((field) => (
                  <div
                    key={field.key}
                    style={{ border: "1px solid #ddd", padding: 12 }}
                  >
                    <Form.Item
                      name={[field.name, "purpose"]}
                      label="真实证据角色"
                      rules={required}
                    >
                      <Select
                        options={[
                          { value: "POLICY_BASIS", label: "政策依据" },
                          {
                            value: "APPLICABILITY_SUPPORT",
                            label: "适用性支持",
                          },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "originalId"]}
                      label="本年度保留的真实原件"
                      rules={required}
                    >
                      <Select
                        disabled={evidence.uploading}
                        loading={originals.isFetching}
                        options={originals.data?.map((original) => ({
                          value: original.id,
                          label: `${original.filename} · ${original.sha256}`,
                        }))}
                      />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "description"]}
                      label="证据来源与作用说明（纸质原件请注明保管位置）"
                      rules={required}
                    >
                      <Input.TextArea maxLength={500} />
                    </Form.Item>
                    <label>
                      直接上传此角色的真实原件（最多5MiB）：
                      <input
                        type="file"
                        disabled={
                          !canPrepare ||
                          !!restriction ||
                          pending ||
                          evidence.uploading
                        }
                        onChange={(event) => {
                          const file = event.target.files?.[0];
                          if (file)
                            void evidence.upload(file, async (id) => {
                              await originals.refetch();
                              if (!controller.current?.signal.aborted)
                                form.setFieldValue(
                                  ["evidence", field.name, "originalId"],
                                  id,
                                );
                            });
                          event.target.value = "";
                        }}
                      />
                    </label>
                    <Button
                      danger
                      disabled={
                        fields.length <= 2 || evidence.uploading || pending
                      }
                      onClick={() => remove(field.name)}
                    >
                      移除此证据
                    </Button>
                  </div>
                ))}
                <Button
                  disabled={
                    fields.length >= 10 || evidence.uploading || pending
                  }
                  onClick={() => add({})}
                >
                  增加证据（最多10份）
                </Button>
              </Space>
            )}
          </Form.List>
          {(error || catalog.error || originals.error || evidence.error) && (
            <Alert
              type="error"
              title={
                error ||
                (catalog.error
                  ? message(catalog.error)
                  : originals.error
                    ? message(originals.error)
                    : evidence.error)
              }
            />
          )}
          <Button
            htmlType="submit"
            type="primary"
            loading={pending}
            disabled={
              !canPrepare ||
              !!restriction ||
              pending ||
              evidence.uploading ||
              catalog.isFetching ||
              catalog.isError ||
              originals.isError ||
              !policyAssertions.every(
                ([key]) => assertionsWatched?.[key] === true,
              )
            }
          >
            保存不可变政策草稿（不发布）
          </Button>
        </Form>
      </Modal>
    </Space>
  );
}
