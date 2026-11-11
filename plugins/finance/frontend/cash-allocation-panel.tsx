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
import { amount, cnyUnits, type Period } from "./types";
import {
  movementDirectionLabel,
  type MovementDetail,
} from "./cash-movement-contract";
import { purposeLabels, type PolicySummary } from "./cash-policy-contract";
import type { SourceReviewDetail } from "./cash-source-review-contract";
import {
  allocationCreatePayload,
  allocationActionPayload,
  allocationReleasePayload,
  allocationDecisionPayload,
  allocationReplacementPayload,
  allocationIndependent,
  allocationEffectLabel,
  allocationPolicyEligible,
  sameAllocationParent,
  type AllocationParentSnapshot,
  type AllocationDetail,
  type AllocationSummary,
  type AllocationRequest,
  type AllocationReplacement,
  type AllocationForm,
  type AllocationFormLine,
  type AllocationLine,
  type AllocationOriginalSnapshot,
} from "./cash-allocation-contract";
const required = [{ required: true, message: "请填写此项" }];
type Page<T> = { items: T[]; nextCursor: string | null };
type Action =
  | "submit"
  | "approve"
  | "reject"
  | "cancel"
  | "release"
  | "release-approve"
  | "release-reject"
  | "release-cancel"
  | "replace-request";
const labels: Record<string, string> = {
  DRAFT: "分配草稿",
  SUBMITTED: "待独立审签",
  APPROVED: "已批准分配",
  REJECTED: "已驳回",
  CANCELLED: "已取消",
  RELEASED: "依赖已释放（历史保留）",
  SUPERSEDED: "已被原子替换（历史保留）",
};
const requestLabels: Record<string, string> = {
  REQUESTED: "申请待独立审签",
  APPROVED: "申请已批准",
  REJECTED: "申请已驳回",
  CANCELLED: "申请已取消",
};
const errorLabels: Record<string, string> = {
  CASH_ALLOCATION_PARENT_STALE:
    "父级人工复核、使用关系或现金匹配已变化；请刷新。旧草稿仍可取消或独立驳回。",
  CASH_ALLOCATION_PARENT_RELEASE_PENDING:
    "父级人工复核正在申请释放，不能建立或批准新的分配。",
  CASH_ALLOCATION_POLICY_UNAVAILABLE:
    "政策未发布、正在退役或不适用于这笔真实现金日期/用途；不得改名为其他普通用途。",
  CASH_ALLOCATION_SUM_MISMATCH:
    "组件合计必须精确等于父级来源全额，不自动舍入、补残差或拆掉差额。",
  CASH_ALLOCATION_PENDING_EXISTS:
    "同父级已有待处理提案，请先完成、取消或独立驳回。",
  CASH_ALLOCATION_ALREADY_EFFECTIVE:
    "同父级已有有效分配；请使用受控释放或真实原子替换。",
  CASH_ALLOCATION_STATE_CONFLICT: "分配或请求状态已变化，请刷新权威历史。",
  CASH_ALLOCATION_SUBMISSION_EXPIRED:
    "已超过7天运营审签期限（非法律期限）；可驳回或取消，再明确建立新提案。",
  CASH_ALLOCATION_RELEASE_PENDING:
    "已有待审释放，当前分配仍有效，但冻结后续新依赖。",
  CASH_ALLOCATION_REPLACEMENT_PENDING:
    "已有待审原子替换，旧效果继续有效，不能竞争释放或重复替换。",
  CASH_ALLOCATION_REPLACEMENT_REQUIRED:
    "这是替换候选，不能普通批准；须发起并独立批准真实原子替换请求。",
  CASH_ALLOCATION_IN_USE:
    "存在实际有效依赖，不能释放或覆盖，请先处理对应受控流程。",
  CASH_ALLOCATION_UNSUPPORTED_FACTS:
    "仅支持与来源种类一致的普通用途全额组件；混合资本化、利息、不明税款、内部划转、退款等不支持。",
  APPROVAL_STALE:
    "当前新制备人或申请人授权、证据或父级快照已变化。旧记录可查，待审可独立驳回。",
};
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
export function CashAllocationPanel({
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
  const base = `${scope}/cashflow/allocations`;
  const prefix = ["finance", bookId, yearId, "cash-allocations"];
  const client = useQueryClient();
  const [cursor, setCursor] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const [parentCursor, setParentCursor] = useState<string | null>(null);
  const [policyCursor, setPolicyCursor] = useState<string | null>(null);
  const [releaseCursor, setReleaseCursor] = useState<string | null>(null);
  const [replacementCursor, setReplacementCursor] = useState<string | null>(
    null,
  );
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [parent, setParent] = useState<AllocationParentSnapshot | null>(null);
  const [oldForDraft, setOldForDraft] = useState<AllocationDetail | null>(null);
  const [policiesSelected, setPoliciesSelected] = useState<
    Record<string, PolicySummary>
  >({});
  const [action, setAction] = useState<Action | null>(null);
  const [release, setRelease] = useState<AllocationRequest | null>(null);
  const [replacement, setReplacement] = useState<AllocationReplacement | null>(
    null,
  );
  const [replacementAction, setReplacementAction] = useState<
    "approve" | "reject" | "cancel" | null
  >(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pending, setPending] = useState(false);
  const [form] = Form.useForm<AllocationForm>();
  const controller = useRef<AbortController | null>(null);
  const keys = useRef(new Map<string, string>());
  const busy = useRef(false);
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
      api<Page<AllocationSummary>>(
        `${base}?limit=20${status ? `&status=${status}` : ""}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
        { signal },
      ),
  });
  const candidates = useQuery({
    queryKey: [...prefix, "parents", parentCursor],
    enabled: active && canRead && creating,
    queryFn: ({ signal }) =>
      api<Page<AllocationParentSnapshot>>(
        `${base}/eligible-source-reviews?limit=20${parentCursor ? `&cursor=${encodeURIComponent(parentCursor)}` : ""}`,
        { signal },
      ),
  });
  const detail = useQuery({
    queryKey: [...prefix, "detail", selectedId],
    enabled: active && canRead && !!selectedId,
    queryFn: ({ signal }) =>
      api<AllocationDetail>(`${base}/${selectedId}`, { signal }),
  });
  const data = detail.data;
  const inspectingParent = creating ? parent : data?.parentSnapshot;
  const review = useQuery({
    queryKey: [...prefix, "review", inspectingParent?.reviewId],
    enabled: active && canRead && !!inspectingParent,
    queryFn: ({ signal }) =>
      api<SourceReviewDetail>(
        `${scope}/cashflow/source-reviews/${inspectingParent!.reviewId}`,
        { signal },
      ),
  });
  const raw = useQuery({
    queryKey: [...prefix, "raw", inspectingParent?.rawRegistrationId],
    enabled: active && canRead && !!inspectingParent,
    queryFn: ({ signal }) =>
      api<MovementDetail>(
        `${scope}/cashflow/movement-registrations/${inspectingParent!.rawRegistrationId}`,
        { signal },
      ),
  });
  const currentParent = useQuery({
    queryKey: [...prefix, "current-parent", data?.reviewId, data?.claimId],
    enabled: active && canRead && !!selectedId && !!data && !detail.isError,
    queryFn: ({ signal }) =>
      api<Page<AllocationParentSnapshot>>(
        `${base}/eligible-source-reviews?limit=20&reviewId=${encodeURIComponent(data!.reviewId)}&claimId=${encodeURIComponent(data!.claimId)}`,
        { signal },
      ),
  });
  const predecessor = useQuery({
    queryKey: [...prefix, "predecessor", data?.supersedesAllocationId],
    enabled:
      active && canRead && !!data?.supersedesAllocationId && !detail.isError,
    queryFn: ({ signal }) =>
      api<AllocationDetail>(`${base}/${data!.supersedesAllocationId}`, {
        signal,
      }),
  });
  const releaseHistory = useQuery({
    queryKey: [...prefix, "releases", selectedId, releaseCursor],
    enabled: active && canRead && !!selectedId,
    queryFn: ({ signal }) =>
      api<Page<AllocationRequest>>(
        `${base}/${selectedId}/release-requests?limit=20${releaseCursor ? `&cursor=${encodeURIComponent(releaseCursor)}` : ""}`,
        { signal },
      ),
  });
  const replacementHistory = useQuery({
    queryKey: [...prefix, "replacements", selectedId, replacementCursor],
    enabled: active && canRead && !!selectedId,
    queryFn: ({ signal }) =>
      api<Page<AllocationReplacement>>(
        `${base}/${selectedId}/replacement-requests?limit=20${replacementCursor ? `&cursor=${encodeURIComponent(replacementCursor)}` : ""}`,
        { signal },
      ),
  });
  const replacementPair = useQuery({
    queryKey: [
      ...prefix,
      "replacement-pair",
      replacement?.id,
      replacement?.version,
    ],
    enabled: active && canRead && !!replacement,
    queryFn: async ({ signal }) => {
      const [old, next] = await Promise.all([
        api<AllocationDetail>(`${base}/${replacement!.oldAllocationId}`, {
          signal,
        }),
        api<AllocationDetail>(`${base}/${replacement!.newAllocationId}`, {
          signal,
        }),
      ]);
      return { old, next };
    },
  });
  const policies = useQuery({
    queryKey: [
      ...prefix,
      "policy-candidates",
      parent?.transactionKind,
      policyCursor,
    ],
    enabled: active && canRead && creating && !!parent,
    queryFn: ({ signal }) =>
      api<Page<PolicySummary>>(
        `${scope}/cashflow/classification-policies?limit=20&status=APPROVED&transactionKind=${encodeURIComponent(parent!.transactionKind)}${policyCursor ? `&cursor=${encodeURIComponent(policyCursor)}` : ""}`,
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
  useEffect(() => {
    if (!policies.data) return;
    const latest = policies.data.items;
    setPoliciesSelected((previous) => {
      let changed = false;
      const next = { ...previous };
      for (const policy of latest) {
        if (previous[policy.id] && previous[policy.id] !== policy) {
          next[policy.id] = policy;
          changed = true;
        }
      }
      return changed ? next : previous;
    });
  }, [policies.data]);
  const queries = [
    actor,
    list,
    candidates,
    detail,
    review,
    raw,
    currentParent,
    predecessor,
    releaseHistory,
    replacementHistory,
    replacementPair,
    policies,
    originals,
  ];
  const readError = queries.find((q) => q.isError)?.error;
  const denied = queries.some(
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
  const actorId = actor.data?.userId;
  const rawCreator =
    raw.isError || raw.isFetching ? undefined : raw.data?.preparedBy;
  const formLines = Form.useWatch("lines", form) as
    AllocationFormLine[] | undefined;
  const current =
    !!data &&
    !currentParent.isError &&
    !currentParent.isFetching &&
    currentParent.data?.items.some((p) =>
      sameAllocationParent(p, data.parentSnapshot),
    );
  function monthOpen(snapshot: AllocationParentSnapshot | null | undefined) {
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
  function writable(snapshot: AllocationParentSnapshot | null | undefined) {
    return (
      canRead && !restriction && !denied && !pending && monthOpen(snapshot)
    );
  }
  function allowed(which: Action) {
    if (
      !data ||
      !writable(data.parentSnapshot) ||
      detail.isError ||
      detail.isFetching
    )
      return false;
    const own = !!actorId && data.preparedBy === actorId;
    const independent = allocationIndependent(
      actorId,
      rawCreator,
      data.preparedBy,
    );
    if (which === "submit")
      return canPrepare && own && data.status === "DRAFT" && !!current;
    if (which === "cancel")
      return canPrepare && own && ["DRAFT", "SUBMITTED"].includes(data.status);
    if (which === "approve")
      return (
        canReview &&
        independent &&
        data.status === "SUBMITTED" &&
        !data.supersedesAllocationId &&
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
        data.currentEffective &&
        !!data.activeDependencyId &&
        !data.pendingRelease &&
        !data.pendingReplacement
      );
    if (which === "replace-request")
      return (
        canPrepare &&
        own &&
        data.status === "SUBMITTED" &&
        !!data.supersedesAllocationId &&
        !!current &&
        !predecessor.isError &&
        !predecessor.isFetching &&
        !!predecessor.data?.currentEffective &&
        !data.pendingReplacement &&
        !predecessor.data.pendingRelease &&
        !predecessor.data.pendingReplacement
      );
    if (
      !release ||
      release.allocationId !== data.id ||
      release.status !== "REQUESTED" ||
      releaseHistory.isError ||
      releaseHistory.isFetching
    )
      return false;
    if (which === "release-cancel")
      return canRelease && release.requestedBy === actorId;
    return (
      canReview &&
      allocationIndependent(
        actorId,
        rawCreator,
        data.preparedBy,
        release.requestedBy,
      )
    );
  }
  function replacementAllowed(which: "approve" | "reject" | "cancel") {
    const pair = replacementPair.data;
    if (
      !replacement ||
      replacement.status !== "REQUESTED" ||
      !pair ||
      replacementPair.isError ||
      replacementPair.isFetching ||
      !writable(pair.next.parentSnapshot)
    )
      return false;
    if (which === "cancel")
      return canPrepare && replacement.requestedBy === actorId;
    return (
      canReview &&
      allocationIndependent(
        actorId,
        rawCreator,
        pair.old.preparedBy,
        pair.next.preparedBy,
        replacement.requestedBy,
      )
    );
  }
  async function write(
    path: string,
    payload: object,
    done: (result: unknown) => void,
  ) {
    if (busy.current || !canRead || restriction || denied) return;
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
          "请求已处理，请以刷新后的当前分配、依赖和替换历史为准。未生成覆盖对账、工作底稿或正式报表。",
        );
      }
    } catch (cause) {
      if (!signal.aborted) setError(message(cause));
    } finally {
      busy.current = false;
      if (!signal.aborted) setPending(false);
    }
  }
  function selectDetail(id: string) {
    setSelectedId(id);
    setReleaseCursor(null);
    setReplacementCursor(null);
    setAction(null);
    if (id === selectedId) {
      void detail.refetch();
      void currentParent.refetch();
      void releaseHistory.refetch();
      void replacementHistory.refetch();
    }
  }
  function openCreate(old: AllocationDetail | null) {
    setCreating(true);
    setOldForDraft(old);
    setParent(old?.parentSnapshot ?? null);
    setPoliciesSelected({});
    setPolicyCursor(null);
    setParentCursor(null);
    setError("");
    form.resetFields();
  }
  function create(values: AllocationForm) {
    if (
      !canPrepare ||
      !parent ||
      !writable(parent) ||
      evidence.uploading ||
      policies.isError ||
      policies.isFetching ||
      originals.isError
    )
      return;
    try {
      void write(
        base,
        allocationCreatePayload(
          values,
          parent,
          Object.values(policiesSelected),
          oldForDraft,
        ),
        (result) => {
          setCreating(false);
          selectDetail((result as AllocationDetail).id);
        },
      );
    } catch (cause) {
      setError(message(cause));
    }
  }
  function start(which: Action, request?: AllocationRequest) {
    setAction(which);
    setRelease(request ?? null);
    setReason("");
    setError("");
  }
  function perform() {
    if (!action || !data || !allowed(action)) return;
    try {
      let path: string, payload: object;
      if (action === "release") {
        path = `${base}/${data.id}/release-requests`;
        payload = allocationReleasePayload(data, reason);
      } else if (action === "replace-request") {
        if (!predecessor.data) return;
        path = `${base}/replacement-requests`;
        payload = allocationReplacementPayload(predecessor.data, data, reason);
      } else if (action.startsWith("release-")) {
        if (!release) return;
        path = `${base}/${data.id}/release-requests/${release.id}/${action.slice(8)}`;
        payload = allocationDecisionPayload(release, reason);
      } else {
        path = `${base}/${data.id}/${action}`;
        payload = allocationActionPayload(data, reason);
      }
      void write(path, payload, () => setAction(null));
    } catch (cause) {
      setError(message(cause));
    }
  }
  function performReplacement() {
    if (
      !replacement ||
      !replacementAction ||
      !replacementAllowed(replacementAction)
    )
      return;
    try {
      void write(
        `${base}/replacement-requests/${replacement.id}/${replacementAction}`,
        allocationDecisionPayload(replacement, reason),
        () => {
          setReplacementAction(null);
          setReplacement(null);
        },
      );
    } catch (cause) {
      setError(message(cause));
    }
  }
  function originalDownload(
    original: AllocationOriginalSnapshot,
    label: string,
  ) {
    return (
      <Button
        style={{
          maxWidth: "100%",
          height: "auto",
          whiteSpace: "normal",
          overflowWrap: "anywhere",
          textAlign: "left",
        }}
        onClick={() =>
          void evidence.download(original.originalId, original.filename)
        }
      >
        {label}：{original.filename}
      </Button>
    );
  }
  function frozen(value: unknown, label: string) {
    return (
      <Collapse
        items={[
          {
            key: "snapshot",
            label,
            children: (
              <pre style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
                {JSON.stringify(value, null, 2)}
              </pre>
            ),
          },
        ]}
      />
    );
  }
  function lineTable(lines: AllocationLine[]) {
    return (
      <Table<AllocationLine>
        rowKey="componentKey"
        tableLayout="fixed"
        style={{ overflowWrap: "anywhere" }}
        pagination={false}
        scroll={{ x: 1610 }}
        dataSource={lines}
        columns={[
          { title: "真实组件键", dataIndex: "componentKey", width: 180 },
          {
            title: "金额（人民币全额）",
            dataIndex: "normalizedAmount",
            width: 180,
          },
          {
            title: "已冻结具体用途",
            width: 210,
            render: (_, row) =>
              purposeLabels[row.businessPurpose] ?? row.businessPurpose,
          },
          {
            title: "政策 / 修订 / 模板行",
            width: 240,
            render: (_, row) =>
              `${row.policySnapshot.policyCode} / ${row.policySnapshot.revision} / ${row.policySnapshot.rowId}`,
          },
          { title: "实际用途依据", dataIndex: "description", width: 300 },
          {
            title: "证据复核",
            width: 260,
            render: (_, row) =>
              originalDownload(row.purposeEvidence, "下载组件用途原件"),
          },
          {
            title: "不可变证据 / 政策快照",
            width: 240,
            render: (_, row) => frozen(row, "查看服务端版本与摘要"),
          },
        ]}
      />
    );
  }
  function parentView(snapshot: AllocationParentSnapshot) {
    return (
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Descriptions
          column={2}
          items={[
            {
              key: "parent",
              label: "真实父级人工复核 / 使用关系",
              children: `${snapshot.reviewId} / ${snapshot.activeReviewUsageId}`,
            },
            {
              key: "cash",
              label: "现金日期 / 方向 / 全额",
              children: `${snapshot.occurredDate} / ${movementDirectionLabel(snapshot.direction)} / ${snapshot.grossAmount} CNY`,
            },
            {
              key: "raw",
              label: "原来源 / 有效声明",
              children: `${snapshot.rawRegistrationId} / ${snapshot.claimKind === "MATCH_CORRECTION" ? "已批准更正（原Raw仍作废）" : "原始登记"} / ${snapshot.claimGeneration}`,
            },
            {
              key: "match",
              label: "完整已记账现金行",
              children: `${snapshot.journalId} / 行${snapshot.lineNo} / 记账序列${snapshot.postingSeq}`,
            },
          ]}
        />
        {review.data?.id === snapshot.reviewId && !review.isError && (
          <Space wrap>
            {originalDownload(
              review.data.claimSnapshot.sourceOriginal,
              "下载真实来源原件",
            )}
            {originalDownload(
              review.data.claimSnapshot.scopeOriginal,
              "下载范围资格原件",
            )}
            {review.data.evidence.map((e) => (
              <Button
                key={e.originalId}
                onClick={() =>
                  void evidence.download(
                    e.originalId,
                    e.filename ?? "human-review-evidence",
                  )
                }
              >
                下载人工复核业务证据：{e.filename}
              </Button>
            ))}
          </Space>
        )}
        {frozen(snapshot, "查看真实父级复核、来源、范围和匹配的冻结快照")}
      </Space>
    );
  }
  let totalLabel = "请独立输入各实际组成部分，不从父级或凭证自动填金额";
  if (formLines?.length) {
    try {
      totalLabel = `组件精确合计 ${amount(formLines.reduce((total, line) => total + cnyUnits(line.amount ?? ""), 0n))} CNY；父级全额 ${parent?.grossAmount ?? "未选择"}`;
    } catch {
      totalLabel = "存在未填或不合法人民币金额；最多2位小数，不自动舍入";
    }
  }
  const visibleError =
    error || (readError ? message(readError) : evidence.error);
  if (!canRead || denied) return null;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title="普通现金组成分配（不是现金流完成）"
        description="仅基于已独立批准的人工复核和有效使用关系，将真实来源全额分成1至20项正人民币组件。每项须有具体普通用途政策及真实原件，合计精确等于父级全额；同一政策可对应不同真实组件。当前仅同种普通用途，不支持混合资本化、利息、不明税款、内部划转、退款、净额或外币。原来源与现金匹配不变，不生成覆盖对账、工作底稿或正式报表。"
      />
      {restriction && <Alert type="warning" title={restriction} />}
      {visibleError && <Alert type="error" title={visibleError} />}
      {notice && <Alert type="success" title={notice} />}
      <Space wrap>
        <Button
          type="primary"
          disabled={!canPrepare || !!restriction || pending}
          onClick={() => openCreate(null)}
        >
          编制普通组成分配
        </Button>
        <Select
          aria-label="分配工作流筛选"
          value={status}
          options={[
            { value: "", label: "全部分配状态" },
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
      <Table<AllocationSummary>
        rowKey="id"
        pagination={false}
        loading={list.isFetching}
        dataSource={list.isError ? [] : (list.data?.items ?? [])}
        scroll={{ x: 1100 }}
        columns={[
          { title: "分配记录", dataIndex: "id", ellipsis: true },
          { title: "父级人工复核", dataIndex: "reviewId", ellipsis: true },
          {
            title: "工作流历史",
            dataIndex: "status",
            render: (value) => <Tag>{labels[value] ?? value}</Tag>,
          },
          {
            title: "当前实际依赖",
            render: (_, row) =>
              row.currentEffective
                ? "有效（待审释放/替换仍占用）"
                : "当前无有效分配",
          },
          {
            title: "操作",
            render: (_, row) => (
              <Button onClick={() => selectDetail(row.id)}>
                查看组件 / 审签 / 释放替换
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
        title="普通分配效果与独立处理历史"
        open={!!selectedId}
        footer={null}
        width={1200}
        onCancel={() => {
          setSelectedId(null);
          setAction(null);
          setReplacement(null);
        }}
      >
        {visibleError && <Alert type="error" title={visibleError} />}
        <Button
          loading={detail.isFetching}
          onClick={() => {
            void detail.refetch();
            void currentParent.refetch();
            void releaseHistory.refetch();
            void replacementHistory.refetch();
            if (data?.supersedesAllocationId) void predecessor.refetch();
          }}
        >
          刷新权威效果与历史
        </Button>
        {data && !detail.isError && (
          <Space orientation="vertical" style={{ width: "100%" }}>
            <Alert title={allocationEffectLabel(data)} />
            <Alert title="提交与处理请求的7×24小时为运营审签期限（非法律期限）。取消/独立驳回可终结过期草稿或已提交提案；历史政策退役不会自动抹除已生效分配，仍可受控释放。" />
            {!monthOpen(data.parentSnapshot) && (
              <Alert
                type="warning"
                title="真实现金日期所在月份未开放或权威期间缺失，仅可查阅。"
              />
            )}
            {!["APPROVED", "RELEASED", "SUPERSEDED"].includes(data.status) &&
              !current && (
                <Alert
                  type="warning"
                  title="父级正在核对或不可用于新提交/批准；独立审核人仍可按旧快照驳回草稿或已提交提案。"
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
                  label: "分配制备人",
                  children: data.preparedBy,
                },
                {
                  key: "approval",
                  label: "历史批准人 / 时间",
                  children: `${data.approvedBy ?? "未批准"} / ${data.approvedAt ?? "—"}`,
                },
                {
                  key: "expiry",
                  label: "本次运营审签截止",
                  children: data.submissionExpiresAt ?? "未提交",
                },
                {
                  key: "dependency",
                  label: "当前有效依赖",
                  children:
                    data.activeDependencyId ?? "当前无有效依赖（历史见请求）",
                },
                {
                  key: "total",
                  label: "冻结组件合计",
                  children: `${data.normalizedTotal} CNY`,
                },
                {
                  key: "digest",
                  label: "内容摘要 / 版本",
                  children: `${data.contentDigest} / ${data.version} / 内容${data.contentVersion}`,
                },
                {
                  key: "reason",
                  label: "编制理由 / 后续意见",
                  children: `${data.reason} / ${data.decisionReason ?? "—"}`,
                },
              ]}
            />
            <Space wrap>
              {data.supersedesAllocationId && (
                <Button
                  onClick={() => selectDetail(data.supersedesAllocationId!)}
                >
                  查看拟替换 / 历史旧分配
                </Button>
              )}
              {data.supersededByAllocationId && (
                <Button
                  onClick={() => selectDetail(data.supersededByAllocationId!)}
                >
                  查看继任分配（其后续释放不抹除原替换历史）
                </Button>
              )}
            </Space>
            {parentView(data.parentSnapshot)}
            {lineTable(data.lines)}
            <Space wrap>
              {(
                [
                  ["submit", "提交本人分配提案"],
                  ["approve", "独立批准普通分配"],
                  ["reject", "独立驳回草稿 / 已提交提案"],
                  ["cancel", "取消本人分配提案"],
                  ["release", "当前负责人申请释放分配依赖"],
                  ["replace-request", "新制备人申请真实原子替换"],
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
              <Button
                disabled={
                  !canPrepare ||
                  !writable(data.parentSnapshot) ||
                  detail.isFetching ||
                  !data.currentEffective ||
                  !data.activeDependencyId ||
                  !!data.pendingRelease ||
                  !!data.pendingReplacement ||
                  !current
                }
                onClick={() => openCreate(data)}
              >
                以当前旧效果编制原子替换候选
              </Button>
            </Space>
            {data.supersedesAllocationId && (
              <Alert title="这是同父级替换候选。提交后须另外申请真实原子替换并独立审签，不能使用普通批准。独立审批须排除Raw制备人、旧/新分配制备人及请求人。" />
            )}
            {frozen(
              { watermark: data.watermark, limitations: data.limitations },
              "查看读取水位与范围限制（非报表发布）",
            )}
            <TypographyTitle text="受控释放请求历史" />
            <Table<AllocationRequest>
              rowKey="id"
              pagination={false}
              dataSource={
                releaseHistory.isError ? [] : (releaseHistory.data?.items ?? [])
              }
              columns={[
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (value) => requestLabels[value] ?? value,
                },
                {
                  title: "申请人 / 理由",
                  render: (_, row) => `${row.requestedBy} / ${row.reason}`,
                },
                { title: "运营审签到期", dataIndex: "reviewExpiresAt" },
                {
                  title: "独立处理人 / 意见",
                  render: (_, row) =>
                    `${row.reviewedBy ?? "未处理"} / ${row.decisionReason ?? "—"}`,
                },
                {
                  title: "精确绑定",
                  render: (_, row) => frozen(row, "查看依赖、摘要和版本"),
                },
                {
                  title: "操作",
                  render: (_, row) => (
                    <Space wrap>
                      {(
                        [
                          ["release-approve", "独立批准释放依赖"],
                          ["release-reject", "驳回释放申请"],
                          ["release-cancel", "取消本人释放申请"],
                        ] as const
                      ).map(([which, label]) => (
                        <Button
                          key={which}
                          disabled={
                            !writable(data.parentSnapshot) ||
                            detail.isFetching ||
                            releaseHistory.isFetching ||
                            row.status !== "REQUESTED" ||
                            (which === "release-cancel"
                              ? !canRelease || row.requestedBy !== actorId
                              : !canReview ||
                                !allocationIndependent(
                                  actorId,
                                  rawCreator,
                                  data.preparedBy,
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
                disabled={!releaseCursor || releaseHistory.isFetching}
                onClick={() => setReleaseCursor(null)}
              >
                释放历史首屏
              </Button>
              <Button
                disabled={
                  !releaseHistory.data?.nextCursor ||
                  releaseHistory.isError ||
                  releaseHistory.isFetching
                }
                onClick={() =>
                  setReleaseCursor(releaseHistory.data?.nextCursor ?? null)
                }
              >
                下一页释放历史
              </Button>
            </Space>
            <TypographyTitle text="真实原子替换请求历史" />
            <Table<AllocationReplacement>
              rowKey="id"
              pagination={false}
              dataSource={
                replacementHistory.isError
                  ? []
                  : (replacementHistory.data?.items ?? [])
              }
              columns={[
                {
                  title: "历史状态",
                  dataIndex: "status",
                  render: (value) => requestLabels[value] ?? value,
                },
                {
                  title: "旧 → 新分配",
                  render: (_, row) =>
                    `${row.oldAllocationId} → ${row.newAllocationId}`,
                },
                {
                  title: "申请人 / 理由",
                  render: (_, row) => `${row.requestedBy} / ${row.reason}`,
                },
                { title: "运营审签到期", dataIndex: "reviewExpiresAt" },
                {
                  title: "操作",
                  render: (_, row) => (
                    <Button
                      onClick={() => {
                        setReplacement(row);
                        setReplacementAction(null);
                        setReason("");
                        setError("");
                      }}
                    >
                      查看新旧影响 / 独立审签
                    </Button>
                  ),
                },
              ]}
            />
            <Space>
              <Button
                disabled={!replacementCursor || replacementHistory.isFetching}
                onClick={() => setReplacementCursor(null)}
              >
                替换历史首屏
              </Button>
              <Button
                disabled={
                  !replacementHistory.data?.nextCursor ||
                  replacementHistory.isError ||
                  replacementHistory.isFetching
                }
                onClick={() =>
                  setReplacementCursor(
                    replacementHistory.data?.nextCursor ?? null,
                  )
                }
              >
                下一页替换历史
              </Button>
            </Space>
          </Space>
        )}
      </Modal>
      <Modal
        title="编制不可变普通分配组件"
        open={creating}
        footer={null}
        width={1150}
        onCancel={() => {
          if (!pending && !evidence.uploading) setCreating(false);
        }}
      >
        {visibleError && <Alert type="error" title={visibleError} />}
        <Alert
          title={
            oldForDraft
              ? `原子替换候选：绑定当前旧分配 ${oldForDraft.id}，旧效果在独立批准替换前继续有效。`
              : "从真实已批准人工复核选择父级，不能从凭证或余额自动造现金。"
          }
        />
        {!oldForDraft && (
          <>
            <Table<AllocationParentSnapshot>
              rowKey="reviewId"
              pagination={false}
              loading={candidates.isFetching}
              dataSource={
                candidates.isError ? [] : (candidates.data?.items ?? [])
              }
              columns={[
                { title: "真实人工复核父级", dataIndex: "reviewId" },
                {
                  title: "现金日期 / 方向 / 全额",
                  render: (_, row) =>
                    `${row.occurredDate} / ${movementDirectionLabel(row.direction)} / ${row.grossAmount}`,
                },
                {
                  title: "有效匹配",
                  render: (_, row) =>
                    row.claimKind === "MATCH_CORRECTION"
                      ? "更正声明（Raw仍作废）"
                      : "原始登记声明",
                },
                {
                  title: "操作",
                  render: (_, row) => (
                    <Button
                      disabled={!monthOpen(row) || pending}
                      onClick={() => {
                        setParent(row);
                        setPolicyCursor(null);
                        setPoliciesSelected({});
                        form.resetFields();
                      }}
                    >
                      {parent?.reviewId === row.reviewId
                        ? "已选择"
                        : "选择批准人工复核"}
                    </Button>
                  ),
                },
              ]}
            />
            <Space>
              <Button
                onClick={() => {
                  setParent(null);
                  void candidates.refetch();
                }}
              >
                刷新父级候选
              </Button>
              <Button
                disabled={!parentCursor || candidates.isFetching}
                onClick={() => {
                  setParent(null);
                  setParentCursor(null);
                }}
              >
                父级首屏
              </Button>
              <Button
                disabled={
                  !candidates.data?.nextCursor ||
                  candidates.isFetching ||
                  candidates.isError
                }
                onClick={() => {
                  setParent(null);
                  setParentCursor(candidates.data?.nextCursor ?? null);
                }}
              >
                下一页父级
              </Button>
            </Space>
          </>
        )}
        {parent && parentView(parent)}
        <Alert title="仅从服务端已发布政策选择符合本笔现金日期和业务种类的明确用途。今天是否在适用区间不等于本笔现金日期；不按科目、供应商、工资或税款名称推断。" />
        <Table<PolicySummary>
          rowKey="id"
          pagination={false}
          loading={policies.isFetching}
          dataSource={policies.isError ? [] : (policies.data?.items ?? [])}
          columns={[
            {
              title: "政策 / 修订",
              render: (_, row) => `${row.policyCode} / ${row.revision}`,
            },
            {
              title: "明确用途",
              render: (_, row) =>
                purposeLabels[row.businessPurpose] ?? row.businessPurpose,
            },
            {
              title: "现金日期适用区间",
              render: (_, row) => `${row.effectiveFrom} 至 ${row.effectiveTo}`,
            },
            {
              title: "本笔新用候选",
              render: (_, row) =>
                parent && allocationPolicyEligible(row, parent)
                  ? "可选择（服务端仍复核）"
                  : "不适用 / 待退役冻结",
            },
          ]}
        />
        <Space>
          <Button disabled={!parent} onClick={() => void policies.refetch()}>
            刷新当前政策候选
          </Button>
          <Button
            disabled={!policyCursor || policies.isFetching}
            onClick={() => setPolicyCursor(null)}
          >
            政策首屏
          </Button>
          <Button
            disabled={
              !policies.data?.nextCursor ||
              policies.isFetching ||
              policies.isError
            }
            onClick={() => setPolicyCursor(policies.data?.nextCursor ?? null)}
          >
            下一页政策
          </Button>
        </Space>
        <Form
          form={form}
          layout="vertical"
          initialValues={{ lines: [{ ordinaryPurposeConfirmed: false }] }}
          onFinish={create}
        >
          <Alert title={totalLabel} />
          <Form.List name="lines">
            {(fields, { add, remove }) => (
              <Space orientation="vertical" style={{ width: "100%" }}>
                {fields.map((field) => (
                  <Space
                    key={field.key}
                    orientation="vertical"
                    style={{ width: "100%", borderBottom: "1px solid #ddd" }}
                  >
                    <Form.Item
                      name={[field.name, "componentKey"]}
                      label={`第${field.name + 1}项真实组件唯一键`}
                      rules={required}
                    >
                      <Input maxLength={80} />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "amount"]}
                      label="实际正人民币金额（最多2位小数，不自动回填）"
                      rules={required}
                    >
                      <Input inputMode="decimal" />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "policyId"]}
                      label="选择当前已发布且适用于本笔的真实政策"
                      rules={required}
                    >
                      <Select
                        showSearch
                        optionFilterProp="label"
                        options={Object.values({
                          ...policiesSelected,
                          ...Object.fromEntries(
                            (policies.data?.items ?? []).map((p) => [p.id, p]),
                          ),
                        })
                          .filter(
                            (p) =>
                              parent && allocationPolicyEligible(p, parent),
                          )
                          .map((p) => ({
                            value: p.id,
                            label: `${p.policyCode} / ${p.revision} · ${purposeLabels[p.businessPurpose] ?? p.businessPurpose}`,
                          }))}
                        onSelect={(id) => {
                          const selected =
                            policies.data?.items.find((p) => p.id === id) ??
                            policiesSelected[id];
                          if (selected)
                            setPoliciesSelected((previous) => ({
                              ...previous,
                              [id]: selected,
                            }));
                        }}
                      />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "purposeEvidenceOriginalId"]}
                      label="此真实组成部分的实际用途原件"
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
                      直接上传实际用途原件（最大5MiB；纸质材料请上传扫描件并在依据说明记录存放位置；非银行验真）
                      <input
                        type="file"
                        aria-label={`上传组件用途原件${field.name + 1}`}
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
                                  [
                                    "lines",
                                    field.name,
                                    "purposeEvidenceOriginalId",
                                  ],
                                  id,
                                );
                            });
                        }}
                      />
                    </label>
                    <Form.Item
                      name={[field.name, "description"]}
                      label="实际组成部分、具体用途及证据依据（纸质位置如适用）"
                      rules={required}
                    >
                      <Input.TextArea maxLength={1000} />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "ordinaryPurposeConfirmed"]}
                      valuePropName="checked"
                    >
                      <Checkbox>
                        我已检查此组件真实用途及实际原件，确属所选普通用途；未按科目名推断，无未支持特殊组成。
                      </Checkbox>
                    </Form.Item>
                    <Button
                      disabled={pending || evidence.uploading}
                      onClick={() => remove(field.name)}
                    >
                      移除此组件
                    </Button>
                  </Space>
                ))}
                <Button
                  disabled={
                    fields.length >= 20 || pending || evidence.uploading
                  }
                  onClick={() => add({ ordinaryPurposeConfirmed: false })}
                >
                  添加真实组成部分（不自动补残差）
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
              !parent ||
              !writable(parent) ||
              evidence.uploading ||
              policies.isFetching ||
              policies.isError ||
              !formLines?.length ||
              !formLines.every((line) => line.ordinaryPurposeConfirmed === true)
            }
          >
            保存不可变分配草稿（不批准）
          </Button>
        </Form>
      </Modal>
      <Modal
        title="分配工作流 / 依赖释放处理"
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
        <Alert title="普通批准仅已提交非替换提案；取消/驳回使用冻结旧父级快照，不代提交。释放仅解除本分配依赖；原子替换另走新旧绑定请求。" />
        <Input.TextArea
          aria-label="分配工作流理由"
          maxLength={1000}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
      </Modal>
      <Modal
        title="真实原子替换：新旧影响与独立审签"
        open={!!replacement}
        footer={null}
        width={1200}
        onCancel={() => {
          if (!pending) {
            setReplacement(null);
            setReplacementAction(null);
          }
        }}
      >
        {visibleError && <Alert type="error" title={visibleError} />}
        <Alert title="独立批准将一次事务把旧分配标为已被替换并释放旧依赖，同时批准新分配、建立新依赖并切换同父级指针。待审期间旧效果不变；拒绝/取消申请不批准新候选。未来新分配合法释放或再替换，不抹除本次历史决定。" />
        <Button
          loading={replacementPair.isFetching}
          onClick={() => void replacementPair.refetch()}
        >
          刷新权威新旧详情
        </Button>
        {replacement &&
          frozen(replacement, "查看替换请求的同父级绑定、版本、摘要及历史决定")}
        {replacementPair.data && !replacementPair.isError && (
          <>
            <TypographyTitle
              text={`旧分配 ${replacementPair.data.old.id} · ${labels[replacementPair.data.old.status] ?? replacementPair.data.old.status}`}
            />
            <Alert title={allocationEffectLabel(replacementPair.data.old)} />
            {lineTable(replacementPair.data.old.lines)}
            <TypographyTitle
              text={`新分配 ${replacementPair.data.next.id} · ${labels[replacementPair.data.next.status] ?? replacementPair.data.next.status}`}
            />
            <Alert title={allocationEffectLabel(replacementPair.data.next)} />
            {parentView(replacementPair.data.next.parentSnapshot)}
            {lineTable(replacementPair.data.next.lines)}
            <Space wrap>
              {(
                [
                  ["approve", "独立批准真实原子替换"],
                  ["reject", "独立驳回替换申请"],
                  ["cancel", "取消本人替换申请"],
                ] as const
              ).map(([which, label]) => (
                <Button
                  key={which}
                  disabled={!replacementAllowed(which)}
                  onClick={() => {
                    setReplacementAction(which);
                    setReason("");
                    setError("");
                  }}
                >
                  {label}
                </Button>
              ))}
            </Space>
          </>
        )}
        {replacementAction && (
          <Space orientation="vertical" style={{ width: "100%" }}>
            <Input.TextArea
              aria-label="原子替换处理理由"
              maxLength={1000}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
            <Button
              type="primary"
              loading={pending}
              disabled={
                !reason.trim() || !replacementAllowed(replacementAction)
              }
              onClick={performReplacement}
            >
              确认本次独立替换处理
            </Button>
          </Space>
        )}
      </Modal>
    </Space>
  );
}
function TypographyTitle({ text }: { text: string }) {
  return <h4>{text}</h4>;
}
