import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
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
import { api, ApiError, mutate } from "@/platform/api/client";
import type { Period } from "./types";
import type {
  CashLeg,
  MovementDetail,
  MovementSummary,
} from "./cash-movement-contract";
import { movementDirectionLabel } from "./cash-movement-contract";
import { useMovementEvidence } from "./movement-evidence";
import {
  correctionProposal,
  correctionAction,
  correctionWithdrawal,
  withdrawalDecision,
  claimOccupancy,
  type MatchCorrection,
  type MatchEffective,
  type WithdrawalRequest,
} from "./cash-match-contract";
const labels: Record<string, string> = {
  DRAFT: "更正草稿",
  SUBMITTED: "待独立审签",
  APPROVED: "曾获批准（当前效力见有效匹配）",
  REJECTED: "已驳回",
  CANCELLED: "已取消",
  WITHDRAWN: "已受控撤回",
  REQUESTED: "撤回申请待审",
};
const errors: Record<string, string> = {
  CASH_MATCH_RAW_NOT_VOIDED: "仅可更正已作废且未使用来源的错误匹配。",
  CASH_MATCH_SOURCE_CHANGED:
    "不可变来源事实或摘要不一致，请刷新核对，不能改写来源。",
  CASH_MATCH_TARGET_INVALID:
    "新目标不是与来源科目、日期、方向和全额金额完全一致的有效已记账现金腿。",
  CASH_MATCH_TARGET_UNCHANGED:
    "目标必须不同于原错误匹配，不能借更正恢复旧匹配。",
  CASH_MATCH_PENDING_EXISTS:
    "该来源已有待处理更正，请先处理其现有草稿或待审单。",
  CASH_MATCH_EFFECTIVE_EXISTS:
    "已有当前有效匹配，必须先独立受控撤回，不能覆盖。",
  CASH_MATCH_GENERATION_STALE: "来源匹配代次已变化，请刷新当前声明后再处理。",
  CASH_MATCH_WITHDRAWAL_PENDING:
    "已有撤回申请待处理，当前占用仍保留，不能并发新增使用。",
  CASH_REGISTRATION_IN_USE: "当前匹配已有实际有效使用，不能撤回或替换。",
  CASH_LEG_ALREADY_REGISTERED: "目标现金腿已被有效声明占用，不能重复占用。",
};
type Action =
  | "submit"
  | "approve"
  | "reject"
  | "cancel"
  | "request-withdrawal"
  | "withdraw-approve"
  | "withdraw-reject";
type Props = {
  openRequest?: { id: string; revision: number } | null;
  bookId: string;
  yearId: string;
  active: boolean;
  canRead: boolean;
  canCreate: boolean;
  canApprove: boolean;
  restriction: string | null;
  periods: Period[];
  readable: (error: unknown) => string;
  onReadDenied: () => void;
};
export function CashMatchPanel({
  openRequest,
  bookId,
  yearId,
  active,
  canRead,
  canCreate,
  canApprove,
  restriction,
  periods,
  readable,
  onReadDenied,
}: Props) {
  const scope = `/finance/v1/books/${bookId}/years/${yearId}`;
  const base = `${scope}/cashflow/match-corrections`;
  const prefix = ["finance", bookId, yearId, "cash-matches"];
  const client = useQueryClient();
  const [cursor, setCursor] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [rawCursor, setRawCursor] = useState<string | null>(null);
  const [rawId, setRawId] = useState("");
  const [legCursor, setLegCursor] = useState<string | null>(null);
  const [target, setTarget] = useState<CashLeg | null>(null);
  const [proposalReason, setProposalReason] = useState("");
  const [withdrawCursor, setWithdrawCursor] = useState<string | null>(null);
  const [withdrawal, setWithdrawal] = useState<WithdrawalRequest | null>(null);
  const [action, setAction] = useState<Action | null>(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pending, setPending] = useState(false);
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
  useEffect(() => {
    if (openRequest) {
      setSelectedId(openRequest.id);
      setWithdrawCursor(null);
    }
  }, [openRequest]);
  const actor = useQuery({
    queryKey: [...prefix, "actor"],
    enabled: active && canRead,
    queryFn: ({ signal }) => api<{ userId: string }>("/auth/me", { signal }),
  });
  const list = useQuery({
    queryKey: [...prefix, "list", status, cursor],
    enabled: active && canRead,
    queryFn: ({ signal }) =>
      api<{ items: MatchCorrection[]; nextCursor: string | null }>(
        `${base}?limit=20${status ? `&status=${status}` : ""}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
        { signal },
      ),
  });
  const detail = useQuery({
    queryKey: [...prefix, "detail", selectedId],
    enabled: active && canRead && !!selectedId,
    queryFn: ({ signal }) =>
      api<MatchCorrection>(`${base}/${selectedId}`, { signal }),
  });
  const history = useQuery({
    queryKey: [...prefix, "withdrawals", selectedId, withdrawCursor],
    enabled: active && canRead && !!selectedId,
    queryFn: ({ signal }) =>
      api<{ items: WithdrawalRequest[]; nextCursor: string | null }>(
        `${base}/${selectedId}/withdrawal-requests?limit=20${withdrawCursor ? `&cursor=${encodeURIComponent(withdrawCursor)}` : ""}`,
        { signal },
      ),
  });
  const raws = useQuery({
    queryKey: [...prefix, "raw-list", rawCursor],
    enabled: active && canRead && creating,
    queryFn: ({ signal }) =>
      api<{ items: MovementSummary[]; nextCursor: string | null }>(
        `${scope}/cashflow/movement-registrations?status=VOIDED&limit=20${rawCursor ? `&cursor=${encodeURIComponent(rawCursor)}` : ""}`,
        { signal },
      ),
  });
  const raw = useQuery({
    queryKey: [...prefix, "raw", rawId],
    enabled: active && canRead && creating && !!rawId,
    queryFn: ({ signal }) =>
      api<MovementDetail>(`${scope}/cashflow/movement-registrations/${rawId}`, {
        signal,
      }),
  });
  const effective = useQuery({
    queryKey: [...prefix, "effective", rawId],
    enabled: active && canRead && creating && !!rawId,
    queryFn: ({ signal }) =>
      api<MatchEffective>(
        `${scope}/cashflow/movement-registrations/${rawId}/effective-match`,
        { signal },
      ),
  });
  const legs = useQuery({
    queryKey: [...prefix, "eligible", rawId, legCursor],
    enabled: active && canRead && creating && !!rawId,
    queryFn: ({ signal }) =>
      api<{ items: CashLeg[]; nextCursor: string | null }>(
        `${base}/eligible-cash-legs?originalRegistrationId=${encodeURIComponent(rawId)}&limit=20${legCursor ? `&cursor=${encodeURIComponent(legCursor)}` : ""}`,
        { signal },
      ),
  });
  const reading = [actor, list, detail, history, raws, raw, effective, legs];
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
  const evidence = useMovementEvidence(scope, false, onReadDenied, message);
  const data = detail.data;
  const actorId = actor.data?.userId;
  const openMonth = (date: string) =>
    periods.some(
      (period) =>
        period.month === Number(date.slice(5, 7)) && period.status === "OPEN",
    );
  const originalId = data?.rawSnapshot?.preparedBy;
  const own = Boolean(
    actorId && data && actorId === originalId && actorId === data.preparedBy,
  );
  const independent = Boolean(
    actorId &&
    data &&
    actorId !== originalId &&
    actorId !== data.preparedBy &&
    actorId !== data.lastEditedBy,
  );
  const writable = Boolean(
    !restriction &&
    data &&
    data.rawStatus === "VOIDED" &&
    Number.isSafeInteger(data.currentSourceMatchGeneration) &&
    data.currentSourceMatchGeneration >= 0 &&
    openMonth(String(data.rawSnapshot.occurredDate ?? "")) &&
    !detail.isError &&
    !detail.isFetching,
  );
  const currentClaim = data?.effectiveClaim;
  const ownClaim = Boolean(
    currentClaim?.state === "ACTIVE" && currentClaim.correctionId === data?.id,
  );
  function allowed(which: Action) {
    if (!data || !writable) return false;
    if (which === "submit") return canCreate && own && data.status === "DRAFT";
    if (which === "cancel")
      return canCreate && own && ["DRAFT", "SUBMITTED"].includes(data.status);
    if (which === "approve")
      return (
        canApprove &&
        independent &&
        data.status === "SUBMITTED" &&
        data.approvalEnabled === true
      );
    if (which === "reject")
      return canApprove && independent && data.status === "SUBMITTED";
    if (which === "request-withdrawal")
      return (
        canCreate &&
        own &&
        data.status === "APPROVED" &&
        ownClaim &&
        !data.pendingWithdrawal &&
        data.approvalEnabled === true
      );
    return (
      canApprove &&
      independent &&
      withdrawal?.status === "REQUESTED" &&
      data.approvalEnabled === true
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
    const key = keys.current.get(fingerprint) ?? crypto.randomUUID();
    keys.current.set(fingerprint, key);
    try {
      const result = await mutate(
        path,
        "POST",
        { ...payload, idempotencyKey: key },
        signal,
      );
      keys.current.delete(fingerprint);
      await client.invalidateQueries({ queryKey: ["finance", bookId, yearId] });
      if (!signal.aborted) {
        done(result);
        setNotice(
          "请求已处理，请刷新权威当前匹配。历史批准不等于当前生效，原登记始终作废且不参与报表。",
        );
      }
    } catch (cause) {
      if (!signal.aborted) setError(message(cause));
    } finally {
      busy.current = false;
      if (!signal.aborted) setPending(false);
    }
  }
  function propose() {
    if (
      !canCreate ||
      !raw.data ||
      !effective.data ||
      !target ||
      raw.data.preparedBy !== actorId ||
      !openMonth(raw.data.occurredDate)
    )
      return;
    try {
      void write(
        base,
        correctionProposal(raw.data, effective.data, target, proposalReason),
        (result) => {
          setCreating(false);
          setSelectedId((result as MatchCorrection).id);
          setWithdrawCursor(null);
        },
      );
    } catch (cause) {
      setError(message(cause));
    }
  }
  function perform() {
    if (!action || !data || !allowed(action) || !reason.trim()) return;
    try {
      let path = `${base}/${data.id}/${action}`;
      let payload: Record<string, unknown>;
      if (action === "request-withdrawal") {
        path = `${base}/${data.id}/withdrawal-requests`;
        payload = correctionWithdrawal(data, reason);
      } else if (
        action === "withdraw-approve" ||
        action === "withdraw-reject"
      ) {
        if (!withdrawal) return;
        path = `${base}/${data.id}/withdrawal-requests/${withdrawal.id}/${action === "withdraw-approve" ? "approve" : "reject"}`;
        payload = withdrawalDecision(
          withdrawal,
          data.currentSourceMatchGeneration,
          reason,
        );
      } else payload = correctionAction(data, reason);
      void write(path, payload, () => {
        setAction(null);
        setWithdrawal(null);
      });
    } catch (cause) {
      setError(message(cause));
    }
  }
  const canPropose = Boolean(
    canCreate &&
    actorId &&
    raw.data?.preparedBy === actorId &&
    raw.data.status === "VOIDED" &&
    effective.data?.rawStatus === "VOIDED" &&
    !effective.data?.effectiveClaim &&
    !restriction &&
    openMonth(raw.data.occurredDate) &&
    !raw.isFetching &&
    !effective.isFetching &&
    !raw.isError &&
    !effective.isError,
  );
  function start(which: Action) {
    if (!which.startsWith("withdraw-")) setWithdrawal(null);
    setAction(which);
    setReason("");
    setError("");
  }
  if (!canRead)
    return (
      <Alert
        type="warning"
        title="匹配更正需要明确读取权限；制单或审签权限不自动授予读取权限。"
      />
    );
  if (denied) return null;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title="已作废来源的现金腿匹配更正（不改来源事实）"
        description="只更正未使用来源的错误已记账现金腿。原登记永久作废、来源金额/日期/科目/证据不变；更正仍未核实、未分类、不参与报表。错误来源事实、内部转账、退款、拆分或跨期间更正本片不支持。仅原制备人可提案或申请撤回，不支持离职接管；账号独立不等同于同一自然人身份核验。"
      />
      {restriction && <Alert type="warning" title={restriction} />}
      <Space wrap>
        <Button
          type="primary"
          disabled={!canCreate || !!restriction || pending}
          onClick={() => {
            setCreating(true);
            setRawId("");
            setTarget(null);
            setRawCursor(null);
            setLegCursor(null);
            setProposalReason("");
            setError("");
          }}
        >
          原制备人提出匹配更正
        </Button>
        <Select
          aria-label="更正状态"
          value={status}
          options={[
            { value: "", label: "全部更正状态" },
            ...[
              "DRAFT",
              "SUBMITTED",
              "APPROVED",
              "REJECTED",
              "CANCELLED",
              "WITHDRAWN",
            ].map((value) => ({ value, label: labels[value] })),
          ]}
          onChange={(value) => {
            setStatus(value);
            setCursor(null);
          }}
        />
        <Button onClick={() => void list.refetch()} loading={list.isFetching}>
          刷新列表
        </Button>
      </Space>
      {(error || readError || evidence.error) && (
        <Alert
          type="error"
          title={error || (readError ? message(readError) : evidence.error)}
        />
      )}{" "}
      {notice && <Alert type="success" title={notice} />}
      <Table<MatchCorrection>
        rowKey="id"
        dataSource={list.isError ? [] : (list.data?.items ?? [])}
        loading={list.isFetching}
        pagination={false}
        scroll={{ x: 1050 }}
        columns={[
          { title: "原作废登记", dataIndex: "originalRegistrationId" },
          {
            title: "工作流状态",
            dataIndex: "status",
            render: (value) => <Tag>{labels[value] ?? value}</Tag>,
          },
          { title: "提案人", dataIndex: "preparedBy" },
          { title: "新目标凭证", dataIndex: "targetJournalId" },
          { title: "新目标行", dataIndex: "targetLineNo" },
          { title: "历史批准人", dataIndex: "approvedBy" },
          { title: "历史批准声明", dataIndex: "approvedClaimId" },
          {
            title: "操作",
            render: (_, row) => (
              <Button
                onClick={() => {
                  setSelectedId(row.id);
                  setWithdrawCursor(null);
                  if (selectedId === row.id) {
                    void detail.refetch();
                    void history.refetch();
                  }
                }}
              >
                查看当前效力 / 历史
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
          disabled={!list.data?.nextCursor || list.isError || list.isFetching}
          onClick={() => setCursor(list.data?.nextCursor ?? null)}
        >
          下一页
        </Button>
      </Space>
      <Modal
        title="匹配更正：历史决定与当前效力"
        open={!!selectedId}
        onCancel={() => setSelectedId(null)}
        footer={null}
        width={1100}
      >
        <Button
          onClick={() => {
            void detail.refetch();
            void history.refetch();
          }}
          loading={detail.isFetching}
        >
          刷新权威详情与撤回历史
        </Button>
        {data && !detail.isError && (
          <Space orientation="vertical" style={{ width: "100%" }}>
            <Alert
              type="warning"
              title={`原登记仍为作废；当前${currentClaim ? "有有效匹配声明" : "没有有效匹配声明"}`}
              description={
                ownClaim
                  ? "当前声明属于本更正，撤回申请待审期间不会释放占用。"
                  : currentClaim
                    ? "当前有效声明属于其他匹配，不能把本历史批准当作当前生效。"
                    : "历史批准记录保留，不代表当前仍有效。"
              }
            />
            {data.pendingWithdrawal && (
              <Alert title="已有撤回申请待独立审签，当前占用仍保留，不可视为来源可重新使用。" />
            )}
            {data.approvalEnabled !== true && (
              <Alert
                type="warning"
                title="完整有效声明与受控撤回能力未启用，禁止批准形成有效匹配。"
              />
            )}
            <Descriptions
              column={2}
              items={[
                {
                  key: "raw",
                  label: "原登记 / 状态",
                  children: `${data.originalRegistrationId} / ${data.rawStatus}`,
                },
                {
                  key: "state",
                  label: "更正工作流",
                  children: labels[data.status] ?? data.status,
                },
                {
                  key: "generation",
                  label: "当前来源匹配代次",
                  children: data.currentSourceMatchGeneration,
                },
                {
                  key: "historic",
                  label: "历史批准代次",
                  children: data.approvedMatchGeneration ?? "未批准",
                },
                {
                  key: "approvedBy",
                  label: "历史批准人",
                  children: data.approvedBy ?? "未批准",
                },
                {
                  key: "approvedClaim",
                  label: "历史批准声明ID",
                  children: data.approvedClaimId ?? "无",
                },
                {
                  key: "current",
                  label: "当前有效声明",
                  children: currentClaim
                    ? `${currentClaim.id} / ${currentClaim.kind} / 代次${currentClaim.generation}`
                    : "无",
                },
                {
                  key: "content",
                  label: "不可变提案摘要",
                  children: data.contentDigest,
                },
                { key: "reason", label: "提案依据", children: data.reason },
                {
                  key: "decision",
                  label: "处理意见",
                  children: data.decisionReason ?? "—",
                },
              ]}
            />
            <Table
              rowKey="name"
              pagination={false}
              dataSource={[
                { name: "原错误现金腿", ...data.originalMatchSnapshot },
                { name: "提案目标现金腿", ...data.targetLegSnapshot },
              ]}
              scroll={{ x: 950 }}
              columns={[
                { title: "证据位置", dataIndex: "name" },
                { title: "凭证", dataIndex: "journalId" },
                { title: "实际行号", dataIndex: "lineNo" },
                { title: "日期", dataIndex: "date" },
                {
                  title: "方向",
                  dataIndex: "direction",
                  render: movementDirectionLabel,
                },
                { title: "全额金额", dataIndex: "normalizedAmount" },
                { title: "版本", dataIndex: "journalVersion" },
                { title: "过账水位", dataIndex: "postingSeq" },
              ]}
            />
            <Button
              onClick={() => {
                const original = data.rawSnapshot.originalSnapshot as
                  { id?: string; filename?: string } | undefined;
                if (original?.id)
                  void evidence.download(
                    original.id,
                    original.filename ?? "original",
                  );
              }}
              disabled={
                !(
                  data.rawSnapshot.originalSnapshot as
                    { id?: string } | undefined
                )?.id
              }
            >
              下载原不可变来源证据
            </Button>
            <Button
              disabled={
                !(
                  data.rawSnapshot.scopeItemSnapshot as
                    { evidenceOriginalId?: string } | undefined
                )?.evidenceOriginalId
              }
              onClick={() => {
                const item = data.rawSnapshot.scopeItemSnapshot as
                  | { evidenceOriginalId?: string; originalFilename?: string }
                  | undefined;
                if (item?.evidenceOriginalId)
                  void evidence.download(
                    item.evidenceOriginalId,
                    item.originalFilename ?? "scope-evidence",
                  );
              }}
            >
              下载原范围资格证据
            </Button>
            <Collapse
              items={[
                {
                  key: "snapshot",
                  label: "不可变来源/旧目标/新目标及当前有效声明快照",
                  children: (
                    <pre
                      style={{
                        whiteSpace: "pre-wrap",
                        overflowWrap: "anywhere",
                      }}
                    >
                      {JSON.stringify(
                        {
                          raw: data.rawSnapshot,
                          originalMatch: data.originalMatchSnapshot,
                          target: data.targetLegSnapshot,
                          effectiveClaim: data.effectiveClaim,
                          pendingWithdrawal: data.pendingWithdrawal,
                          watermark: data.asOfWatermark,
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
                  ["submit", "提交本人草稿"],
                  ["approve", "独立批准匹配"],
                  ["reject", "驳回更正"],
                  ["cancel", "原提案人取消"],
                  ["request-withdrawal", "申请受控撤回有效匹配"],
                ] as const
              ).map(([which, label]) => (
                <Button
                  key={which}
                  danger={which === "request-withdrawal" || which === "cancel"}
                  disabled={!allowed(which) || pending}
                  onClick={() => start(which)}
                >
                  {label}
                </Button>
              ))}
            </Space>
            <Typography.Title level={5}>
              撤回申请与独立复核历史（待审不释放声明）
            </Typography.Title>
            <Table<WithdrawalRequest>
              expandable={{
                expandedRowRender: (row) => (
                  <Descriptions
                    column={1}
                    items={[
                      { key: "id", label: "撤回请求", children: row.id },
                      {
                        key: "version",
                        label: "请求版本",
                        children: row.version,
                      },
                      {
                        key: "digest",
                        label: "批准绑定摘要",
                        children: row.contentDigest,
                      },
                      {
                        key: "generation",
                        label: "申请时来源代次",
                        children: row.expectedSourceMatchGeneration,
                      },
                      {
                        key: "expected",
                        label: "绑定的更正版本",
                        children: row.expectedCorrectionVersion,
                      },
                    ]}
                  />
                ),
              }}
              rowKey="id"
              dataSource={history.isError ? [] : (history.data?.items ?? [])}
              pagination={false}
              scroll={{ x: 1000 }}
              columns={[
                { title: "申请时间", dataIndex: "requestedAt" },
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (value) =>
                    (
                      ({
                        REQUESTED: "撤回申请待审",
                        APPROVED: "已批准撤回",
                        REJECTED: "撤回申请被驳回",
                      }) as Record<string, string>
                    )[value] ?? value,
                },
                { title: "目标有效声明", dataIndex: "activeClaimId" },
                { title: "申请人", dataIndex: "requestedBy" },
                { title: "申请理由", dataIndex: "reason" },
                { title: "独立复核人", dataIndex: "reviewedBy" },
                { title: "复核意见", dataIndex: "decisionReason" },
                {
                  title: "处理",
                  render: (_, row) => (
                    <Space>
                      <Button
                        disabled={
                          !canApprove ||
                          !independent ||
                          !writable ||
                          data.approvalEnabled !== true ||
                          row.status !== "REQUESTED" ||
                          pending ||
                          history.isFetching
                        }
                        onClick={() => {
                          setWithdrawal(row);
                          start("withdraw-approve");
                        }}
                      >
                        批准撤回
                      </Button>
                      <Button
                        disabled={
                          !canApprove ||
                          !independent ||
                          !writable ||
                          data.approvalEnabled !== true ||
                          row.status !== "REQUESTED" ||
                          pending ||
                          history.isFetching
                        }
                        onClick={() => {
                          setWithdrawal(row);
                          start("withdraw-reject");
                        }}
                      >
                        驳回撤回申请
                      </Button>
                    </Space>
                  ),
                },
              ]}
            />
            <Space>
              <Button
                disabled={!withdrawCursor || history.isFetching}
                onClick={() => setWithdrawCursor(null)}
              >
                撤回历史首屏
              </Button>
              <Button
                disabled={
                  !history.data?.nextCursor ||
                  history.isFetching ||
                  history.isError
                }
                onClick={() =>
                  setWithdrawCursor(history.data?.nextCursor ?? null)
                }
              >
                撤回历史下一页
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
          action?.startsWith("withdraw-")
            ? "独立复核撤回申请"
            : action === "request-withdrawal"
              ? "申请撤回当前有效匹配"
              : "更正工作流处理"
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
            action === "withdraw-approve"
              ? "批准将原子释放所选当前声明；原登记仍作废，之后没有本更正的有效匹配。"
              : "请求绑定权威版本、内容摘要与来源匹配代次；不改变任何来源事实或记账。"
          }
          description={
            withdrawal
              ? `撤回申请：${withdrawal.id}；目标声明：${withdrawal.activeClaimId}；申请理由：${withdrawal.reason}`
              : currentClaim
                ? `当前有效声明：${currentClaim.id}`
                : undefined
          }
        />
        <Input.TextArea
          aria-label="匹配处理理由"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          maxLength={1000}
        />
        {error && <Alert type="error" title={error} />}
      </Modal>
      <Modal
        title="原制备人提出不改变来源事实的匹配更正"
        open={creating}
        onCancel={() => !pending && setCreating(false)}
        footer={null}
        width={1100}
      >
        <Button
          disabled={pending}
          onClick={() => {
            void raws.refetch();
            if (rawId) {
              void raw.refetch();
              void effective.refetch();
              void legs.refetch();
            }
          }}
        >
          刷新作废来源、当前声明与目标选择
        </Button>
        <Table<MovementSummary>
          rowKey="id"
          loading={raws.isFetching}
          dataSource={raws.isError ? [] : (raws.data?.items ?? [])}
          pagination={false}
          columns={[
            { title: "稳定来源", dataIndex: "sourceMovementKey" },
            { title: "原登记状态", dataIndex: "status" },
            { title: "日期", dataIndex: "occurredDate" },
            {
              title: "方向",
              dataIndex: "direction",
              render: movementDirectionLabel,
            },
            { title: "原金额", dataIndex: "normalizedAmount" },
            { title: "原制备人", dataIndex: "preparedBy" },
            {
              title: "选择",
              render: (_, row) => (
                <Button
                  onClick={() => {
                    setRawId(row.id);
                    setTarget(null);
                    setLegCursor(null);
                    setProposalReason("");
                  }}
                >
                  查看 / 选择此作废来源
                </Button>
              ),
            },
          ]}
        />
        <Space>
          <Button
            disabled={!rawCursor || raws.isFetching}
            onClick={() => setRawCursor(null)}
          >
            来源首屏
          </Button>
          <Button
            disabled={!raws.data?.nextCursor || raws.isFetching || raws.isError}
            onClick={() => setRawCursor(raws.data?.nextCursor ?? null)}
          >
            来源下一页
          </Button>
        </Space>
        {raw.data && effective.data && (
          <>
            <Alert
              title={`原来源永久作废：${raw.data.id}；事实 ${raw.data.occurredDate} / ${movementDirectionLabel(raw.data.direction)} / ${raw.data.normalizedAmount} / 科目${raw.data.accountId}`}
              description={`当前来源匹配代次 ${effective.data.currentSourceMatchGeneration}；${effective.data.effectiveClaim ? "已有有效声明，须先受控撤回" : "无当前有效声明"}；${raw.data.preparedBy === actorId ? "你是原制备人" : "你不是原制备人，不能提案或申请撤回"}`}
            />
            <Button
              onClick={() =>
                void evidence.download(
                  raw.data!.originalSnapshot.id,
                  raw.data!.originalSnapshot.filename,
                )
              }
            >
              下载实际原来源证据核对
            </Button>
          </>
        )}
        <Typography.Title level={5}>
          选择另一个完整相同事实的POSTED现金腿（不编辑金额/日期/来源）
        </Typography.Title>
        <Table<CashLeg>
          rowKey={(row) => `${row.journalId}:${row.lineNo}`}
          pagination={false}
          loading={legs.isFetching}
          dataSource={legs.isError ? [] : (legs.data?.items ?? [])}
          rowSelection={{
            type: "radio",
            selectedRowKeys: target
              ? [`${target.journalId}:${target.lineNo}`]
              : [],
            getCheckboxProps: (row) => ({
              disabled: !!row.activeClaimId || !!row.activeRegistrationId,
            }),
            onChange: (_, rows) => setTarget(rows[0] ?? null),
          }}
          scroll={{ x: 950 }}
          columns={[
            { title: "凭证号", dataIndex: "number" },
            { title: "真实行号", dataIndex: "lineNo" },
            { title: "日期", dataIndex: "date" },
            {
              title: "方向",
              dataIndex: "direction",
              render: movementDirectionLabel,
            },
            { title: "账面完整金额", dataIndex: "normalizedAmount" },
            { title: "版本", dataIndex: "journalVersion" },
            { title: "记账水位", dataIndex: "postingSeq" },
            { title: "占用", render: (_, row) => claimOccupancy(row) },
          ]}
        />
        <Space>
          <Button
            disabled={!legCursor || legs.isFetching}
            onClick={() => setLegCursor(null)}
          >
            目标首屏
          </Button>
          <Button
            disabled={!legs.data?.nextCursor || legs.isFetching || legs.isError}
            onClick={() => setLegCursor(legs.data?.nextCursor ?? null)}
          >
            目标下一页
          </Button>
        </Space>
        <Input.TextArea
          aria-label="匹配更正提案理由"
          placeholder="说明原来源事实正确、原现金腿为何错误及新目标依据"
          value={proposalReason}
          onChange={(event) => setProposalReason(event.target.value)}
          maxLength={1000}
        />
        {(error || readError || evidence.error) && (
          <Alert
            type="error"
            title={error || (readError ? message(readError) : evidence.error)}
          />
        )}
        <Button
          type="primary"
          disabled={
            !canPropose ||
            !target ||
            !proposalReason.trim() ||
            pending ||
            legs.isFetching ||
            legs.isError
          }
          loading={pending}
          onClick={propose}
        >
          保存不可变匹配更正草稿（不生效）
        </Button>
      </Modal>
    </Space>
  );
}
