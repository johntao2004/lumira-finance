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
} from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { api, ApiError, mutate } from "@/platform/api/client";
import { useMovementEvidence } from "./movement-evidence";
import { movementDirectionLabel } from "./cash-movement-contract";
import type { Period } from "./types";
import {
  sourceReviewChecklist,
  sourceReviewCreatePayload,
  sourceReviewActionPayload,
  sourceReviewReleasePayload,
  sourceReviewReleaseAction,
  sourceReviewIndependent,
  sourceReviewUseLabel,
  sameReviewClaim,
  type ReviewClaim,
  type ReviewOriginal,
  type ReviewEvidence,
  type SourceReviewSummary,
  type SourceReviewDetail,
  type SourceReviewForm,
  type SourceReviewRelease,
} from "./cash-source-review-contract";
const required = [{ required: true, message: "请填写此项" }];
const labels: Record<string, string> = {
  DRAFT: "人工复核草稿",
  SUBMITTED: "待独立审签",
  APPROVED: "已批准人工复核",
  REJECTED: "已驳回",
  CANCELLED: "已取消",
  RELEASED: "使用关系已释放（历史保留）",
};
const releaseLabels: Record<string, string> = {
  REQUESTED: "释放申请待审（仍占用，冻结新依赖）",
  APPROVED: "独立批准释放",
  REJECTED: "释放申请已驳回",
  CANCELLED: "申请人已取消",
};
const errorLabels: Record<string, string> = {
  APPROVAL_STALE:
    "当前制备人或释放请求人授权、证据或声明快照已变化；请刷新。待审记录仍可独立驳回。",
  CASH_REVIEW_SUBMISSION_EXPIRED:
    "超过7天运营审签时限，不能批准；可驳回或取消后重新编制。",
  CASH_REVIEW_CLAIM_STALE:
    "有效现金匹配或其代次已变化，请刷新重新选择；旧提案仍可取消或独立驳回。",
  CASH_REVIEW_PENDING_WITHDRAWAL:
    "现金匹配已有待审撤回，不能建立或批准新的人工复核。",
  CASH_REVIEW_ALREADY_EFFECTIVE:
    "该声明已有有效人工复核使用关系，请查阅现有记录；不能重复建立。",
  CASH_REVIEW_PENDING_EXISTS:
    "该声明已有待处理人工复核提案，请先处理现有提案。",
  CASH_REVIEW_RELEASE_PENDING:
    "已有待审释放申请，使用关系仍占用，后续新依赖被冻结。",
  CASH_REVIEW_STATE_CONFLICT: "人工复核或释放请求状态已变化，请刷新权威记录。",
  CASH_REVIEW_UNSUPPORTED_FACTS:
    "该事实超出当前普通人民币全额业务复核边界，不能以声明替代实际证据。",
  CASH_REVIEW_IN_USE:
    "仍有有效下游使用，不能释放；请先按已实现的受控流程处理依赖。",
};
type Page<T> = { items: T[]; nextCursor: string | null };
type Action =
  | "submit"
  | "approve"
  | "reject"
  | "cancel"
  | "release"
  | "release-approve"
  | "release-reject"
  | "release-cancel";
type Props = {
  bookId: string;
  yearId: string;
  year: number;
  active: boolean;
  canRead: boolean;
  canPrepare: boolean;
  canReview: boolean;
  canRelease: boolean;
  restriction: string | null;
  periods: Period[];
  readable: (error: unknown) => string;
  onReadDenied: () => void;
};
export function CashSourceReviewPanel({
  bookId,
  yearId,
  year,
  active,
  canRead,
  canPrepare,
  canReview,
  canRelease,
  restriction,
  periods,
  readable,
  onReadDenied,
}: Props) {
  const scope = `/finance/v1/books/${bookId}/years/${yearId}`;
  const base = `${scope}/cashflow/source-reviews`;
  const prefix = ["finance", bookId, yearId, "cash-source-reviews"];
  const client = useQueryClient();
  const [cursor, setCursor] = useState<string | null>(null);
  const [eligibleCursor, setEligibleCursor] = useState<string | null>(null);
  const [releaseCursor, setReleaseCursor] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [claim, setClaim] = useState<ReviewClaim | null>(null);
  const [action, setAction] = useState<Action | null>(null);
  const [release, setRelease] = useState<SourceReviewRelease | null>(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pending, setPending] = useState(false);
  const [form] = Form.useForm<SourceReviewForm>();
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
  const list = useQuery({
    queryKey: [...prefix, "list", status, cursor],
    enabled: active && canRead,
    queryFn: ({ signal }) =>
      api<Page<SourceReviewSummary>>(
        `${base}?limit=20${status ? `&status=${status}` : ""}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
        { signal },
      ),
  });
  const eligible = useQuery({
    queryKey: [...prefix, "eligible", eligibleCursor],
    enabled: active && canRead && creating,
    queryFn: ({ signal }) =>
      api<Page<ReviewClaim>>(
        `${base}/eligible-effective-claims?limit=20${eligibleCursor ? `&cursor=${encodeURIComponent(eligibleCursor)}` : ""}`,
        { signal },
      ),
  });
  const detail = useQuery({
    queryKey: [...prefix, "detail", selectedId],
    enabled: active && canRead && !!selectedId,
    queryFn: ({ signal }) =>
      api<SourceReviewDetail>(`${base}/${selectedId}`, { signal }),
  });
  const data = detail.data;
  const currentClaim = useQuery({
    queryKey: [
      ...prefix,
      "current-claim",
      data?.rawRegistrationId,
      data?.claimId,
    ],
    enabled: active && canRead && !!selectedId && !!data && !detail.isError,
    queryFn: ({ signal }) =>
      api<Page<ReviewClaim>>(
        `${base}/eligible-effective-claims?limit=20&rawRegistrationId=${encodeURIComponent(data!.rawRegistrationId)}&claimId=${encodeURIComponent(data!.claimId)}`,
        { signal },
      ),
  });
  const history = useQuery({
    queryKey: [...prefix, "releases", selectedId, releaseCursor],
    enabled: active && canRead && !!selectedId,
    queryFn: ({ signal }) =>
      api<Page<SourceReviewRelease>>(
        `${base}/${selectedId}/release-requests?limit=20${releaseCursor ? `&cursor=${encodeURIComponent(releaseCursor)}` : ""}`,
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
  const reads = [
    actor,
    list,
    eligible,
    detail,
    currentClaim,
    history,
    originals,
  ];
  const readError = reads.find((q) => q.isError)?.error;
  const denied = reads.some(
    (q) => q.isError && q.error instanceof ApiError && q.error.status === 403,
  );
  useEffect(() => {
    if (denied) onReadDenied();
  }, [denied, onReadDenied]);
  function message(cause: unknown) {
    return cause instanceof Error && errorLabels[cause.message]
      ? errorLabels[cause.message]
      : readable(cause);
  }
  const evidence = useMovementEvidence(
    scope,
    canRead && canPrepare && !restriction,
    onReadDenied,
    message,
  );
  const checklist = Form.useWatch("checklist", form);
  const actorId = actor.data?.userId;
  function monthOpen(snapshot: ReviewClaim | null | undefined) {
    if (
      !snapshot ||
      !/^\d{4}-\d{2}-\d{2}$/.test(snapshot.occurredDate) ||
      Number(snapshot.occurredDate.slice(0, 4)) !== year
    )
      return false;
    return periods.some(
      (p) =>
        p.month === Number(snapshot.occurredDate.slice(5, 7)) &&
        p.status === "OPEN",
    );
  }
  const current =
    !currentClaim.isError &&
    !currentClaim.isFetching &&
    !!data &&
    currentClaim.data?.items.some((c) =>
      sameReviewClaim(c, data.claimSnapshot),
    );
  function allowed(which: Action) {
    if (
      !data ||
      restriction ||
      !monthOpen(data.claimSnapshot) ||
      detail.isError ||
      detail.isFetching ||
      pending
    )
      return false;
    const own = !!actorId && data.preparedBy === actorId;
    const independent = sourceReviewIndependent(actorId, data);
    if (which === "submit")
      return canPrepare && own && data.status === "DRAFT" && !!current;
    if (which === "cancel")
      return canPrepare && own && ["DRAFT", "SUBMITTED"].includes(data.status);
    if (which === "approve")
      return (
        canRead &&
        canReview &&
        independent &&
        data.status === "SUBMITTED" &&
        !!current
      );
    if (which === "reject")
      return (
        canReview && independent && ["DRAFT", "SUBMITTED"].includes(data.status)
      );
    if (which === "release")
      return (
        canRelease &&
        data.status === "APPROVED" &&
        !!data.activeUsageId &&
        !data.pendingRelease
      );
    if (
      !release ||
      release.reviewId !== data.id ||
      release.status !== "REQUESTED" ||
      history.isError ||
      history.isFetching
    )
      return false;
    if (which === "release-cancel")
      return canRelease && release.requestedBy === actorId;
    return (
      canReview && sourceReviewIndependent(actorId, data, release.requestedBy)
    );
  }
  async function write(
    path: string,
    payload: Record<string, unknown>,
    done: (result: unknown) => void,
  ) {
    if (busy.current || restriction || !canRead || denied) return;
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
          "请求已处理，请以刷新后的当前复核及使用关系为准；不改变原来源未核实标记，不代表银行验真或报表完成。",
        );
      }
    } catch (cause) {
      if (!signal.aborted) setError(message(cause));
    } finally {
      busy.current = false;
      if (!signal.aborted) setPending(false);
    }
  }
  function start(which: Action, request?: SourceReviewRelease) {
    setRelease(request ?? null);
    setAction(which);
    setReason("");
    setError("");
  }
  function perform() {
    if (!action || !data || !allowed(action)) return;
    try {
      let path: string, payload: Record<string, unknown>;
      if (action === "release") {
        path = `${base}/${data.id}/release-requests`;
        payload = sourceReviewReleasePayload(data, reason);
      } else if (action.startsWith("release-")) {
        if (!release) return;
        path = `${base}/${data.id}/release-requests/${release.id}/${action.slice(8)}`;
        payload = sourceReviewReleaseAction(release, reason);
      } else {
        path = `${base}/${data.id}/${action}`;
        payload = sourceReviewActionPayload(data, reason);
      }
      void write(path, payload, () => setAction(null));
    } catch (cause) {
      setError(message(cause));
    }
  }
  function create(values: SourceReviewForm) {
    if (
      !canPrepare ||
      !claim ||
      !monthOpen(claim) ||
      evidence.uploading ||
      eligible.isError ||
      eligible.isFetching ||
      !eligible.data?.items.some((c) => sameReviewClaim(c, claim))
    )
      return;
    try {
      void write(base, sourceReviewCreatePayload(values, claim), (result) => {
        setCreating(false);
        setSelectedId((result as SourceReviewDetail).id);
        setReleaseCursor(null);
      });
    } catch (cause) {
      setError(message(cause));
    }
  }
  function downloadOriginal(original: ReviewOriginal, label: string) {
    return (
      <Button
        onClick={() =>
          void evidence.download(original.originalId, original.filename)
        }
      >
        {label}：{original.filename}
      </Button>
    );
  }
  function claimDetails(snapshot: ReviewClaim) {
    return (
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Descriptions
          column={2}
          items={[
            {
              key: "source",
              label: "真实来源 / 文件行",
              children: `${snapshot.sourceIdentity.sourceMovementKey} / ${snapshot.sourceIdentity.sourceRowReference}`,
            },
            {
              key: "claim",
              label: "有效匹配类型 / 代次",
              children: `${snapshot.claimKind === "MATCH_CORRECTION" ? "已批准更正（原登记仍作废）" : "原始登记"} / ${snapshot.claimGeneration}`,
            },
            {
              key: "cash",
              label: "真实现金日期 / 方向 / 全额",
              children: `${snapshot.occurredDate} / ${movementDirectionLabel(snapshot.direction)} / ${snapshot.normalizedAmount} CNY`,
            },
            {
              key: "leg",
              label: "有效凭证现金行 / 记账序列",
              children: `${snapshot.journalId} / 行${snapshot.lineNo} / ${snapshot.postingSeq}`,
            },
            {
              key: "account",
              label: "年度科目 / 本月范围修订",
              children: `${snapshot.accountId} / ${snapshot.cashScopeRevisionId}`,
            },
          ]}
        />
        <Space wrap>
          {downloadOriginal(snapshot.sourceOriginal, "下载真实来源原件")}
          {downloadOriginal(snapshot.scopeOriginal, "下载范围资格原件")}
        </Space>
        <Collapse
          items={[
            {
              key: "snapshot",
              label: "查看来源身份、文件SHA、范围与已记账匹配的完整不可变快照",
              children: (
                <pre
                  style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
                >
                  {JSON.stringify(snapshot, null, 2)}
                </pre>
              ),
            },
          ]}
        />
      </Space>
    );
  }
  if (!canRead || denied) return null;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title="逐笔来源人工复核（仅支持后续编制）"
        description="检查真实来源、当前有效现金匹配和业务用途证据，经独立审签后建立使用关系。不是银行验真、法律税务结论，不分类、不分配、不生成报表；原登记始终保留原有未核实、未分类标记。作废原登记经批准更正后可按有效更正声明复核，不能复活原登记。"
      />
      {restriction && <Alert type="warning" title={restriction} />}
      {(error || readError || evidence.error) && (
        <Alert
          type="error"
          title={error || (readError ? message(readError) : evidence.error)}
        />
      )}
      {notice && <Alert type="success" title={notice} />}
      <Space wrap>
        <Button
          type="primary"
          disabled={!canPrepare || !!restriction || pending}
          onClick={() => {
            setCreating(true);
            setClaim(null);
            setEligibleCursor(null);
            form.resetFields();
            setError("");
          }}
        >
          编制来源人工复核
        </Button>
        <Select
          aria-label="来源复核工作流筛选"
          value={status}
          options={[
            { value: "", label: "全部复核状态" },
            ...Object.entries(labels).map(([value, label]) => ({
              value,
              label,
            })),
          ]}
          onChange={(value) => {
            setStatus(value);
            setCursor(null);
          }}
        />
        <Button loading={list.isFetching} onClick={() => void list.refetch()}>
          刷新
        </Button>
      </Space>
      <Table<SourceReviewSummary>
        rowKey="id"
        pagination={false}
        loading={list.isFetching}
        dataSource={list.isError ? [] : (list.data?.items ?? [])}
        scroll={{ x: 1000 }}
        columns={[
          { title: "复核记录", dataIndex: "id", ellipsis: true },
          { title: "原来源", dataIndex: "rawRegistrationId", ellipsis: true },
          {
            title: "匹配类型",
            dataIndex: "claimKind",
            render: (value) =>
              value === "MATCH_CORRECTION"
                ? "批准更正"
                : value === "RAW_REGISTRATION"
                  ? "原始登记"
                  : value,
          },
          {
            title: "工作流历史",
            dataIndex: "status",
            render: (value) => <Tag>{labels[value] ?? value}</Tag>,
          },
          {
            title: "当前有效使用",
            dataIndex: "currentEffective",
            render: (value) =>
              value ? "有效（待释放仍占用）" : "当前无有效使用",
          },
          {
            title: "操作",
            render: (_, row) => (
              <Button
                onClick={() => {
                  setSelectedId(row.id);
                  setReleaseCursor(null);
                  if (selectedId === row.id) {
                    void detail.refetch();
                    void history.refetch();
                    void currentClaim.refetch();
                  }
                }}
              >
                查看事实 / 审签 / 释放
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
        title="来源人工复核及独立释放历史"
        open={!!selectedId}
        onCancel={() => {
          setSelectedId(null);
          setAction(null);
        }}
        footer={null}
        width={1150}
      >
        {(error || readError || evidence.error) && (
          <Alert
            type="error"
            title={error || (readError ? message(readError) : evidence.error)}
          />
        )}
        <Button
          loading={detail.isFetching}
          onClick={() => {
            void detail.refetch();
            void history.refetch();
            void currentClaim.refetch();
          }}
        >
          刷新权威复核及使用关系
        </Button>
        {data && !detail.isError && (
          <Space orientation="vertical" style={{ width: "100%" }}>
            <Alert
              type={data.currentEffective ? "info" : "warning"}
              title={sourceReviewUseLabel(data)}
            />
            {!monthOpen(data.claimSnapshot) && (
              <Alert
                type="warning"
                title="真实现金日期对应月份未开放或缺少权威期间信息，仅可查阅。"
              />
            )}
            {!["APPROVED", "RELEASED"].includes(data.status) && !current && (
              <Alert
                type="warning"
                title="当前声明正在核对、已变化或不可用于新批准；不能提交/批准。独立审核人可按旧快照驳回草稿或已提交提案，终结不可用记录；不代替制备人提交。原制备人有当前权限时仍可取消。"
              />
            )}
            <Descriptions
              column={2}
              items={[
                {
                  key: "status",
                  label: "历史工作流",
                  children: labels[data.status] ?? data.status,
                },
                {
                  key: "maker",
                  label: "复核制备人",
                  children: data.preparedBy,
                },
                {
                  key: "reviewer",
                  label: "历史批准人 / 时间",
                  children: `${data.approvedBy ?? "未批准"} / ${data.approvedAt ?? "—"}`,
                },
                {
                  key: "expires",
                  label: "本次待审运营截止",
                  children: data.submissionExpiresAt ?? "未提交",
                },
                {
                  key: "usage",
                  label: "当前有效使用关系ID",
                  children:
                    data.activeUsageId ?? "当前无有效使用（历史见释放记录）",
                },
                {
                  key: "digest",
                  label: "不可变内容摘要 / 版本",
                  children: `${data.contentDigest} / ${data.version} / 内容${data.contentVersion}`,
                },
                {
                  key: "purpose",
                  label: "具体业务用途",
                  children: data.businessPurposeStatement,
                },
                {
                  key: "facts",
                  label: "人工事实复核说明",
                  children: data.factReviewStatement,
                },
                {
                  key: "reason",
                  label: "制备理由 / 后续意见",
                  children: `${data.reason} / ${data.decisionReason ?? "—"}`,
                },
              ]}
            />
            <Alert title="提交或释放申请后7×24小时是运营审签时限（不是法定期限）。到期仍可驳回或取消；既有批准历史不会因该时限消失。释放只解除人工复核使用关系，绝不释放现金匹配声明。" />
            {claimDetails(data.claimSnapshot)}
            <Table<ReviewEvidence>
              rowKey="originalId"
              pagination={false}
              dataSource={data.evidence}
              columns={[
                {
                  title: "证据角色",
                  dataIndex: "purpose",
                  render: (value) =>
                    value === "BUSINESS_PURPOSE_SUPPORT"
                      ? "具体业务用途支持"
                      : value === "FACT_CORROBORATION"
                        ? "事实佐证"
                        : value,
                },
                { title: "实际原件", dataIndex: "filename" },
                { title: "复核说明", dataIndex: "description" },
                { title: "服务端SHA", dataIndex: "sha256", ellipsis: true },
                {
                  title: "操作",
                  render: (_, row) => (
                    <Button
                      onClick={() =>
                        void evidence.download(
                          row.originalId,
                          row.filename ?? "review-evidence",
                        )
                      }
                    >
                      下载实际业务证据复核
                    </Button>
                  ),
                },
              ]}
            />
            <Collapse
              items={[
                {
                  key: "detail",
                  label: "查看主动检查声明、服务端证据版本与读取水位",
                  children: (
                    <pre
                      style={{
                        whiteSpace: "pre-wrap",
                        overflowWrap: "anywhere",
                      }}
                    >
                      {JSON.stringify(
                        {
                          checklist: data.checklist,
                          evidence: data.evidence,
                          watermark: data.watermark,
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
                  ["submit", "提交本人复核提案"],
                  ["approve", "独立批准支持编制"],
                  ["reject", "独立驳回草稿 / 已提交提案"],
                  ["cancel", "取消本人复核提案"],
                  ["release", "当前负责人申请释放使用关系"],
                ] as const
              ).map(([which, label]) => (
                <Button
                  key={which}
                  disabled={!allowed(which)}
                  onClick={() => start(which)}
                >
                  {label}
                </Button>
              ))}
            </Space>
            {data.pendingRelease && (
              <Alert
                type="warning"
                title="待审释放期间使用关系仍有效，不允许新增依赖；须独立批准后才释放。"
              />
            )}
            <Table<SourceReviewRelease>
              rowKey="id"
              pagination={false}
              dataSource={history.isError ? [] : (history.data?.items ?? [])}
              columns={[
                {
                  title: "释放工作流",
                  dataIndex: "status",
                  render: (value) => releaseLabels[value] ?? value,
                },
                { title: "申请人", dataIndex: "requestedBy" },
                { title: "理由", dataIndex: "reason" },
                { title: "运营审签到期", dataIndex: "reviewExpiresAt" },
                { title: "独立处理人", dataIndex: "reviewedBy" },
                { title: "后续意见", dataIndex: "decisionReason" },
                {
                  title: "绑定使用关系 / 不可变申请",
                  render: (_, row) => (
                    <Collapse
                      items={[
                        {
                          key: "request",
                          label: `使用 ${row.usageId} · 申请版本 ${row.version}`,
                          children: (
                            <pre
                              style={{
                                whiteSpace: "pre-wrap",
                                overflowWrap: "anywhere",
                              }}
                            >
                              {JSON.stringify(row, null, 2)}
                            </pre>
                          ),
                        },
                      ]}
                    />
                  ),
                },
                {
                  title: "操作",
                  render: (_, row) => (
                    <Space wrap>
                      {(
                        [
                          ["release-approve", "独立批准释放"],
                          ["release-reject", "驳回释放申请"],
                          ["release-cancel", "取消本人释放申请"],
                        ] as const
                      ).map(([which, label]) => (
                        <Button
                          key={which}
                          disabled={
                            !!restriction ||
                            !monthOpen(data.claimSnapshot) ||
                            detail.isFetching ||
                            history.isFetching ||
                            pending ||
                            row.status !== "REQUESTED" ||
                            (which === "release-cancel"
                              ? !canRelease || row.requestedBy !== actorId
                              : !canReview ||
                                !sourceReviewIndependent(
                                  actorId,
                                  data,
                                  row.requestedBy,
                                ))
                          }
                          onClick={() => start(which, row)}
                        >
                          {label}
                        </Button>
                      ))}
                    </Space>
                  ),
                },
              ]}
            />
            <Space>
              <Button
                disabled={!releaseCursor || history.isFetching}
                onClick={() => setReleaseCursor(null)}
              >
                释放历史首屏
              </Button>
              <Button
                disabled={
                  !history.data?.nextCursor ||
                  history.isFetching ||
                  history.isError
                }
                onClick={() =>
                  setReleaseCursor(history.data?.nextCursor ?? null)
                }
              >
                下一页释放历史
              </Button>
            </Space>
          </Space>
        )}
      </Modal>
      <Modal
        title="编制不可变来源人工复核"
        open={creating}
        onCancel={() => {
          if (!pending && !evidence.uploading) setCreating(false);
        }}
        footer={null}
        width={1100}
      >
        {(error || readError || evidence.error) && (
          <Alert
            type="error"
            title={error || (readError ? message(readError) : evidence.error)}
          />
        )}
        <Alert title="必须实际检查文件与业务用途后主动确认；不能仅凭金额平衡、科目名或分类政策证明现金事实。原登记作废而有效更正仍在时，按当前更正声明选择。" />
        <Table<ReviewClaim>
          rowKey="claimId"
          pagination={false}
          loading={eligible.isFetching}
          dataSource={eligible.isError ? [] : (eligible.data?.items ?? [])}
          columns={[
            {
              title: "真实来源稳定键",
              render: (_, row) => row.sourceIdentity.sourceMovementKey,
            },
            {
              title: "文件行位置",
              render: (_, row) => row.sourceIdentity.sourceRowReference,
            },
            {
              title: "日期 / 方向 / 全额",
              render: (_, row) =>
                `${row.occurredDate} / ${movementDirectionLabel(row.direction)} / ${row.normalizedAmount}`,
            },
            {
              title: "有效匹配",
              render: (_, row) =>
                row.claimKind === "MATCH_CORRECTION"
                  ? "更正匹配（原登记仍作废）"
                  : "原始登记匹配",
            },
            {
              title: "操作",
              render: (_, row) => (
                <Button
                  disabled={!monthOpen(row) || pending}
                  onClick={() => setClaim(row)}
                >
                  {claim?.claimId === row.claimId ? "已选择" : "选择来源声明"}
                </Button>
              ),
            },
          ]}
        />
        <Space>
          <Button
            onClick={() => {
              setClaim(null);
              void eligible.refetch();
            }}
          >
            刷新可选有效声明
          </Button>
          <Button
            disabled={!eligibleCursor || eligible.isFetching}
            onClick={() => {
              setClaim(null);
              setEligibleCursor(null);
            }}
          >
            来源首屏
          </Button>
          <Button
            disabled={
              !eligible.data?.nextCursor ||
              eligible.isFetching ||
              eligible.isError
            }
            onClick={() => {
              setClaim(null);
              setEligibleCursor(eligible.data?.nextCursor ?? null);
            }}
          >
            下一页来源
          </Button>
        </Space>
        {claim && claimDetails(claim)}
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            checklist: Object.fromEntries(
              sourceReviewChecklist.map(([key]) => [key, false]),
            ),
            evidence: [{ purpose: "BUSINESS_PURPOSE_SUPPORT" }],
          }}
          onFinish={create}
        >
          <Form.Item
            name="businessPurposeStatement"
            label="具体业务用途及其真实依据"
            rules={required}
          >
            <Input.TextArea maxLength={2000} />
          </Form.Item>
          <Form.Item
            name="factReviewStatement"
            label="来源文件行、独立工具、现金日期/方向/全额及有效匹配的人工复核说明"
            rules={required}
          >
            <Input.TextArea maxLength={4000} />
          </Form.Item>
          {sourceReviewChecklist.map(([key, label]) => (
            <Form.Item
              key={key}
              name={["checklist", key]}
              valuePropName="checked"
            >
              <Checkbox>{label}</Checkbox>
            </Form.Item>
          ))}
          <Form.List name="evidence">
            {(fields, { add, remove }) => (
              <Space orientation="vertical" style={{ width: "100%" }}>
                {fields.map((field) => (
                  <Space
                    key={field.key}
                    orientation="vertical"
                    style={{ width: "100%", borderBottom: "1px solid #ddd" }}
                  >
                    <Form.Item
                      name={[field.name, "purpose"]}
                      label="真实业务证据角色"
                      rules={required}
                    >
                      <Select
                        options={[
                          {
                            value: "BUSINESS_PURPOSE_SUPPORT",
                            label: "具体业务用途支持（至少一份）",
                          },
                          { value: "FACT_CORROBORATION", label: "事实佐证" },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "originalId"]}
                      label="选择本年度已上传的真实原件"
                      rules={required}
                    >
                      <Select
                        showSearch
                        optionFilterProp="label"
                        options={(originals.data ?? []).map((o) => ({
                          value: o.id,
                          label: `${o.filename} · ${o.sha256}`,
                        }))}
                      />
                    </Form.Item>
                    <label>
                      直接上传真实原件字节（最大5MiB，纸质材料请上传扫描件并在说明中记录位置；上传不代表验真）
                      <input
                        aria-label={`上传复核业务证据${field.name + 1}`}
                        type="file"
                        disabled={
                          !canPrepare ||
                          !!restriction ||
                          evidence.uploading ||
                          pending
                        }
                        onChange={(event) => {
                          const file = event.target.files?.[0];
                          event.target.value = "";
                          if (file)
                            void evidence.upload(file, async (id) => {
                              await originals.refetch();
                              if (!controller.current?.signal.aborted)
                                form.setFieldValue(
                                  ["evidence", field.name, "originalId"],
                                  id,
                                );
                            });
                        }}
                      />
                    </label>
                    <Form.Item
                      name={[field.name, "description"]}
                      label="证据说明 / 纸质原件位置（如适用）"
                      rules={required}
                    >
                      <Input.TextArea maxLength={500} />
                    </Form.Item>
                    <Button
                      disabled={pending || evidence.uploading}
                      onClick={() => remove(field.name)}
                    >
                      移除此证据选择
                    </Button>
                  </Space>
                ))}
                <Button
                  disabled={fields.length >= 10 || pending}
                  onClick={() => add({ purpose: "FACT_CORROBORATION" })}
                >
                  添加真实业务证据
                </Button>
              </Space>
            )}
          </Form.List>
          <Form.Item name="reason" label="编制理由" rules={required}>
            <Input.TextArea maxLength={1000} />
          </Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            loading={pending}
            disabled={
              !canPrepare ||
              !!restriction ||
              !claim ||
              !monthOpen(claim) ||
              eligible.isError ||
              eligible.isFetching ||
              evidence.uploading ||
              !sourceReviewChecklist.every(([key]) => checklist?.[key] === true)
            }
          >
            保存不可变人工复核草稿（不批准）
          </Button>
        </Form>
      </Modal>
      <Modal
        title="人工复核 / 使用关系释放处理"
        open={!!action}
        onCancel={() => {
          if (!pending) setAction(null);
        }}
        onOk={perform}
        confirmLoading={pending}
        okButtonProps={{
          disabled: !action || !allowed(action) || !reason.trim(),
        }}
      >
        {error && <Alert type="error" title={error} />}
        <Alert title="提交/批准须当前有效声明一致；取消/驳回绑定原提案快照，不能把已失效声明替换为新声明。释放不修改来源、不释放现金匹配。" />
        <Input.TextArea
          aria-label="人工复核处理理由"
          value={reason}
          maxLength={1000}
          onChange={(event) => setReason(event.target.value)}
        />
      </Modal>
    </Space>
  );
}
