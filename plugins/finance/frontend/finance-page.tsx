import { PayablesBoundary } from "./payables-ap01-resource";
import { PayablesPanel, PayablesJournal } from "./payables-ap01-panel";
import { apRaw } from "./payables-ap01-rules";
import { PayableSettlementPanel } from "./payable-settlement-panel";
import { isApSettlementJournalType } from "./payable-settlement-rules";
import { ReceivablesPanel } from "./receivables-panel";
import { isArProducerJournalType } from "./receivables-rules";
import { AssetPanel } from "./asset-panel";
import { isAssetProducerJournalType } from "./asset-rules";
import type {
  AssetCard,
  AssetDifference,
  AssetPlan,
  AssetReceipt,
} from "./asset-contract";
import {
  PayrollPanel,
  PayrollRestatementCard,
  type PayrollBatch,
  type PayrollBatchSummary,
} from "./payroll-panel";
import { TaxObligationPanel } from "./tax-obligation-panel";
import { ArchiveExportPanel } from "./archive-export-panel";
import { ReportDraftPanel } from "./report-draft-panel";
import { CounterpartyPanel } from "./counterparty-panel";
import type { List as CounterpartyList } from "./counterparty-contract";
import { TaxEntityPanel } from "./tax-entity-panel";
import type { Page as TaxEntityPage } from "./tax-entity-contract";
import { partyTabIntent } from "./counterparty-rules";
import { CarryReadinessPanel } from "./carry-readiness-panel";
import { ProfitClosingBoundary } from "./profit-closing-resource";
import { ProfitClosingPanel } from "./profit-closing-panel";
import { ProfitClosingJournal, type ClosingSeed } from "./profit-closing-run";
import { closingNormalLane } from "./profit-closing-rules";
import { JournalPagesPanel } from "./journal-pages-panel";
import { EvidenceUsagePanel } from "./evidence-usage-panel";
import { JournalPersonModePanel } from "./journal-person-mode-panel";
import { JournalPersonActions } from "./journal-person-actions";
import {
  useJournalPersonMode,
  journalPersonReadable,
} from "./use-journal-person";
import { journalPersonBlockedReasons } from "./journal-person-rules";
import { PersonDirectoryPanel } from "./person-directory-panel";
import { usePersonDirectory } from "./use-person-directory";
import { directoryTabIntent } from "./person-directory-rules";
import { tabAfterSensitiveReset } from "./journal-pages-contract";
import { CashLedgerCoveragePanel } from "./cash-ledger-coverage-panel";
import { CashAllocationPanel } from "./cash-allocation-panel";
import { CashSourceReviewPanel } from "./cash-source-review-panel";
import { CashPolicyPanel } from "./cash-policy-panel";
import { CashMatchPanel } from "./cash-match-panel";
import { CashMovementPanel } from "./cash-movement-panel";
import { CashScopePanel } from "./cash-scope-panel";
import { SourceEventsPanel } from "./source-events-panel";
import {
  yearWriteRestriction,
  yearProjectionRestriction,
} from "./year-write-scope";
import { revokedSelection } from "./scope-selection";
import { formPayload } from "./form-payload";
import { OrgFxPanel, type OrgFxApi } from "./org-fx-panel";
import { IamOpsPanel } from "./iam-ops-panel";
import { DimensionsBudgetPanel } from "./dimensions-budget-panel";
import { EvidenceFapiaoPanel } from "./evidence-fapiao-panel";
import { ExpensePanel } from "./expense-panel";
import { ApArExtPanel } from "./ap-ar-ext-panel";
import { TreasuryPanel } from "./treasury-panel";
import { InventoryAssetsPanel } from "./inventory-assets-panel";
import { GlYearendPanel } from "./gl-yearend-panel";
import { PayrollTaxExtPanel } from "./payroll-tax-ext-panel";
import { StatutoryReportsPanel } from "./statutory-reports-panel";
import { ArchiveIntAiPanel } from "./archive-int-ai-panel";
import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Card,
  Collapse,
  Descriptions,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { api, mutate, ApiError, csrf, apiBlob } from "@/platform/api/client";
import {
  type Account,
  type Book,
  type Journal,
  type Line,
  type Period,
  type Plan,
  type Year,
  amount,
  blankLine,
  units,
  cnyUnits,
} from "./types";

const errorMessages: Record<string, string> = {
  SCOPE_DENIED: "没有该企业账套的授权。",
  VERSION_CONFLICT: "记录版本已变化，请刷新后重新核对。",
  APPROVAL_STALE: "审批绑定的业务内容已变化，请重新生成预览并审批。",
  INVALID_INPUT: "提交内容不符合业务规则，请检查必填项、金额、日期和账套。",
  INVALID_CURRENCY_SCALE:
    "人民币正式借贷金额最多两位小数，请明确核对后再提交。",
  AMOUNT_OVERFLOW: "金额超过内部金额的容量上限（20位整数、8位小数）。",
  LEGACY_PRECISION_REVIEW_REQUIRED:
    "该年度属于旧版精度历史，目前仅可查阅，须经过受控复核迁移后才能处理账务。",
  forbidden: "当前账号没有该操作权限或账套授权。",
  staleRecord: "记录已变化，请刷新后重新复核。",
  RULE_VERSION_UNAVAILABLE: "尚无经审定的报表规则版本，不能生成正式报表。",
  PERIOD_CLOSED: "该会计期间已关闭。",
  UNBALANCED_JOURNAL: "凭证借贷不平衡。",
  SELF_APPROVAL_FORBIDDEN: "提交人不能批准自己的业务，请使用独立审核账号。",
  IDEMPOTENCY_CONFLICT: "同一操作标识的内容不一致，请刷新后核对。",
  AUDIT_INTEGRITY_FAILED:
    "审计证据与摘要不一致，已停止读取，请联系管理员核查。",
  AR_DEPENDENCY_UNAVAILABLE:
    "收入政策、税务事实、科目映射或原生应收记账契约尚未接入；未产生会计效果。",
  AR_CUSTOMER_STALE: "客户当前修订未批准、已冻结或不再具备客户角色。",
  AR_SOURCE_UNAVAILABLE: "来源类型、状态、客户或更正链不满足条件。",
  AR_CAPACITY_EXCEEDED: "上游确认额度不足，禁止超额预占或消费。",
  AR_STATE_CONFLICT: "当前业务对象状态不允许执行该操作。",
  AR_PERFORMANCE_NOT_OCCURRED: "履约验收日期晚于业务日期，尚未实际发生。",
};
function readable(error: unknown) {
  const code = error instanceof Error ? error.message : "";
  return (
    errorMessages[code] ??
    (error instanceof ApiError && error.status === 403
      ? errorMessages.forbidden
      : `操作未完成：${code || "服务暂不可用，请稍后重试"}`)
  );
}
const statuses: Record<string, string> = {
  DRAFT: "草稿",
  SUBMITTED: "待审核",
  APPROVED: "已审核",
  POSTED: "已记账",
  OPEN: "开放",
  CLOSING: "结转处理中",
  CLOSED: "已关闭",
  CLOSED_PENDING_ARCHIVE: "结转已提交，待归档",
  PREVIEW: "待复核",
  EXECUTED: "已执行",
  CANCELLED: "已作废",
};
const status = (value: string) => <Tag>{statuses[value] ?? value}</Tag>;
const required = [{ required: true, message: "请填写此项" }];
const key = () => crypto.randomUUID();
const roleLabels: Record<string, string> = {
  OWNER: "负责人",
  MAKER: "制单",
  REVIEWER: "审核",
  POSTER: "记账",
  READER: "只读",
};
const auditActionLabels: Record<string, string> = {
  "YEAR_year-execute": "执行年结",
  "YEAR_year-approve": "年结审批",
  CLOSING_APPROVAL_INVALIDATED: "结转审批失效",
  ORIGINAL_UPLOAD: "上传原件",
  JOURNAL_CREATE: "创建凭证",
  JOURNAL_EDIT: "编辑草稿",
  JOURNAL_SUBMIT: "提交审核",
  JOURNAL_APPROVE: "审核凭证",
  JOURNAL_POST: "正式记账",
  JOURNAL_REJECT: "退回凭证",
  JOURNAL_CANCEL: "作废草稿",
  JOURNAL_WITHDRAW: "撤回审核",
  PERIOD_CLOSE: "关闭期间",
  YEAR_PREVIEW: "年结预览",
  YEAR_APPROVE: "年结审批",
  YEAR_EXECUTE: "执行年结",
  MEMBER_GRANT: "成员授权",
  BOOK_CREATE: "创建账套",
  ACCOUNT_CREATE: "创建科目",
};
const standardLabels: Record<string, string> = {
  CAS: "企业准则",
  SMALL: "小企业准则",
};
type Dialog =
  | "book"
  | "year"
  | "account"
  | "member"
  | "journal"
  | "document"
  | "reversal"
  | "tax"
  | "closing"
  | "reject"
  | "cancel"
  | null;

export function FinancePage({
  permissions: grants,
  authorizationHint = "",
}: {
  permissions: Record<string, boolean>;
  authorizationHint?: string;
}) {
  const queryClient = useQueryClient();
  const [bookId, setBookId] = useState("");
  const [cashMatchOpen, setCashMatchOpen] = useState<{
    id: string;
    revision: number;
  } | null>(null);
  const [yearId, setYearId] = useState("");
  const [periodId, setPeriodId] = useState("");
  const [dialog, setDialog] = useState<Dialog>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pending, setPending] = useState(false);
  const [editing, setEditing] = useState<Journal | null>(null);
  const [selected, setSelected] = useState<Journal | null>(null);
  const [lines, setLines] = useState<Line[]>([blankLine(), blankLine()]);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [targetYearId, setTargetYearId] = useState("");
  const [preview, setPreview] = useState<{
    digest: string;
    canClose: boolean;
    blockers: string[];
  } | null>(null);
  const [reportRequested, setReportRequested] = useState(false);
  const [activeTab, setActiveTab] = useState("journals");
  const [evidenceRefreshToken, setEvidenceRefreshToken] = useState(0);
  const [closingSeed, setClosingSeed] = useState<ClosingSeed | null>(null);
  const [planLoading, setPlanLoading] = useState(false);
  const planReadSequence = useRef(0);
  const [auditCursor, setAuditCursor] = useState<string | null>(null);
  const [form] = Form.useForm();
  const requestKeys = useRef(new Map<string, string>());
  const lock = useRef(false);
  const epoch = useRef(0);
  const mounted = useRef(true);
  const journalOpenController = useRef<AbortController | null>(null);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      journalOpenController.current?.abort();
    };
  }, []);
  const root = "/finance/v1";
  const scope = `${root}/books/${bookId}/years/${yearId}`;
  const books = useQuery({
    queryKey: ["finance", "books"],
    enabled: grants.read,
    queryFn: ({ signal }) => api<Book[]>(`${root}/books`, { signal }),
  });
  const bookRole = books.data?.find((b) => b.id === bookId)?.currentRole;
  const pageAuthHint = JSON.stringify([authorizationHint, bookRole ?? null]);
  const previousPageAuth = useRef(pageAuthHint);
  useEffect(() => {
    if (previousPageAuth.current !== pageAuthHint) {
      clearJournalPageDetails();
      previousPageAuth.current = pageAuthHint;
    }
  }, [pageAuthHint]);
  useEffect(() => {
    const next = tabAfterSensitiveReset(
      activeTab,
      Boolean(grants.manage && bookRole === "OWNER"),
    );
    if (next !== activeTab) setActiveTab(next);
  }, [activeTab, grants.manage, bookRole]);
  const years = useQuery({
    queryKey: ["finance", bookId, "years"],
    enabled: !!bookId,
    queryFn: ({ signal }) =>
      api<Year[]>(`${root}/books/${bookId}/years`, { signal }),
  });
  const selectedYear = years.data?.find((y) => y.id === yearId);
  const scopeRestriction = yearId ? yearWriteRestriction(selectedYear) : null;
  const scopeReadOnly = Boolean(scopeRestriction);
  const permissions = Object.fromEntries(
    Object.entries(grants).map(([operation, allowed]) => [
      operation,
      allowed &&
        (!scopeReadOnly ||
          !["create", "approve", "post", "close"].includes(operation)) &&
        (operation === "read" ||
          !bookId ||
          bookRole === "OWNER" ||
          (operation === "create" && bookRole === "MAKER") ||
          (operation === "approve" && bookRole === "REVIEWER") ||
          (operation === "post" && bookRole === "POSTER")),
    ]),
  );
  const accounts = useQuery({
    queryKey: ["finance", bookId, yearId, "accounts"],
    enabled: !!yearId,
    queryFn: ({ signal }) => api<Account[]>(`${scope}/accounts`, { signal }),
  });
  const periods = useQuery({
    queryKey: ["finance", bookId, yearId, "periods"],
    enabled: !!yearId,
    queryFn: ({ signal }) => api<Period[]>(`${scope}/periods`, { signal }),
  });
  const balances = useQuery({
    queryKey: ["finance", bookId, yearId, "balance"],
    enabled: !!yearId,
    queryFn: ({ signal }) =>
      api<{
        rows: (Account & {
          debit: string;
          credit: string;
          balance: string;
          opening: string;
        })[];
        debit: string;
        credit: string;
        balanced: boolean;
        watermark?: {
          postingSeq: number;
          openingVersion: number;
          baselineVersion: number;
          sourceCloseVersion: number;
        };
      }>(`${scope}/trial-balance`, { signal }),
  });
  const members = useQuery({
    queryKey: ["finance", bookId, "members"],
    enabled: !!bookId && permissions.manage,
    queryFn: ({ signal }) =>
      api<
        {
          userId: string;
          username: string;
          role: string;
          yearScope: string;
          yearIds: string[];
          enabled: boolean;
        }[]
      >(`${root}/books/${bookId}/members`, { signal }),
  });
  const reports = useQuery({
    queryKey: ["finance", bookId, yearId, "reports"],
    enabled: !!yearId && reportRequested,
    retry: false,
    queryFn: ({ signal }) => api<unknown>(`${scope}/reports`, { signal }),
  });
  const originals = useQuery({
    queryKey: ["finance", bookId, yearId, "originals"],
    enabled: !!yearId,
    queryFn: ({ signal }) =>
      api<
        { id: string; filename: string; sha256: string; sizeBytes: number }[]
      >(`${scope}/originals`, { signal }),
  });
  const arCounterparties = useQuery({
    queryKey: ["finance", bookId, "ar-counterparties", evidenceRefreshToken],
    enabled: Boolean(
      bookId &&
      yearId &&
      grants.read &&
      (activeTab === "receivables" || activeTab === "payables-ap01"),
    ),
    queryFn: ({ signal }) =>
      api<CounterpartyList>(
        `${root}/books/${bookId}/counterparties?limit=50&status=ACTIVE`,
        { signal },
      ),
  });
  const downloadUrls = useRef(new Set<string>());
  useEffect(
    () => () => {
      for (const url of downloadUrls.current) URL.revokeObjectURL(url);
      downloadUrls.current.clear();
    },
    [],
  );
  async function uploadOriginal(file: File) {
    if (scopeReadOnly) {
      setError(scopeRestriction ?? "年度财务只读");
      return;
    }
    if (lock.current) return;
    if (file.size > 5 * 1024 * 1024) {
      setError("原件最多5MiB");
      return;
    }
    lock.current = true;
    setPending(true);
    setError("");
    const revision = epoch.current;
    const path = `${scope}/originals?filename=${encodeURIComponent(file.name)}`;
    try {
      // This digest only identifies a retry; the server independently hashes authoritative bytes.
      const digest = await crypto.subtle.digest(
        "SHA-256",
        await file.arrayBuffer(),
      );
      const hash = Array.from(new Uint8Array(digest), (byte) =>
        byte.toString(16).padStart(2, "0"),
      ).join("");
      const fingerprint = JSON.stringify([path, file.name, hash]);
      const idempotencyKey = requestKeys.current.get(fingerprint) ?? key();
      requestKeys.current.set(fingerprint, idempotencyKey);
      const token = await csrf();
      await api(path, {
        method: "POST",
        headers: {
          "Content-Type": "application/octet-stream",
          [token.headerName]: token.token,
          "Idempotency-Key": idempotencyKey,
        },
        body: file,
      });
      requestKeys.current.delete(fingerprint);
      await queryClient.invalidateQueries({
        queryKey: ["finance", bookId, yearId, "originals"],
      });
      if (mounted.current && revision === epoch.current)
        setNotice("原件字节已保存，服务端已计算SHA-256；尚未验真或验签。");
    } catch (cause) {
      if (mounted.current && revision === epoch.current)
        setError(readable(cause));
    } finally {
      lock.current = false;
      if (mounted.current && revision === epoch.current) setPending(false);
    }
  }
  async function downloadOriginal(id: string, filename: string) {
    const revision = epoch.current;
    try {
      const blob = await apiBlob(`${scope}/originals/${id}/content`);
      if (!mounted.current || revision !== epoch.current) return;
      const url = URL.createObjectURL(blob);
      downloadUrls.current.add(url);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      link.click();
      setTimeout(() => {
        URL.revokeObjectURL(url);
        downloadUrls.current.delete(url);
      }, 1000);
    } catch (cause) {
      if (mounted.current && revision === epoch.current)
        setError(readable(cause));
    }
  }
  const closePlans = useQuery({
    queryKey: ["finance", bookId, yearId, "year-close-plans"],
    enabled: !!yearId,
    queryFn: ({ signal }) =>
      api<Plan[]>(`${scope}/year-close-plans`, { signal }),
  });
  const targetAccounts = useQuery({
    queryKey: [
      "finance",
      bookId,
      plan?.targetYearId,
      "accounts",
      "year-close-preview",
    ],
    enabled: Boolean(bookId && plan?.targetYearId),
    queryFn: ({ signal }) =>
      api<Account[]>(
        `${root}/books/${bookId}/years/${plan?.targetYearId}/accounts`,
        { signal },
      ),
  });
  const targetMappingReady = Boolean(
    !planLoading &&
    plan?.preview &&
    targetAccounts.isSuccess &&
    !targetAccounts.isFetching &&
    plan.preview.balances.every((row) =>
      targetAccounts.data.some((account) => account.id === row.targetAccountId),
    ),
  );
  const targetMappingMessage = targetAccounts.isError
    ? "目标年度科目读取失败，请刷新后重试；批准和执行已禁用。"
    : planLoading || targetAccounts.isFetching || !targetAccounts.isSuccess
      ? "正在读取目标年度科目；读取完成前不能批准或执行。"
      : "部分目标科目已缺失，请重新核对结转映射；批准和执行已禁用。";
  const auditEvents = useQuery({
    queryKey: ["finance", bookId, yearId, "audit-events", auditCursor],
    enabled: Boolean(
      bookId &&
      yearId &&
      grants.manage &&
      bookRole === "OWNER" &&
      activeTab === "audit",
    ),
    queryFn: ({ signal }) =>
      api<{
        items: {
          id: string;
          actorId: string;
          action: string;
          targetId: string | null;
          payloadHash: string;
          createdAt: string;
          payload: unknown;
        }[];
        nextCursor: string | null;
      }>(
        `${scope}/audit-events?limit=20${auditCursor ? `&cursor=${encodeURIComponent(auditCursor)}` : ""}`,
        { signal },
      ),
  });
  const closePlanDetail = useQuery({
    queryKey: ["finance", bookId, yearId, "year-close-plan", plan?.id],
    enabled: !!plan?.id,
    queryFn: ({ signal }) =>
      api<Plan>(`${scope}/year-close-plans/${plan?.id}`, { signal }),
  });
  useEffect(() => {
    if (closePlanDetail.data) setPlan(closePlanDetail.data);
  }, [closePlanDetail.data]);
  const detail = useQuery({
    queryKey: ["finance", bookId, yearId, "journal-detail", selected?.id],
    enabled: !!selected,
    queryFn: ({ signal }) =>
      api<Journal>(`${scope}/journals/${selected?.id}`, { signal }),
  });
  useEffect(() => {
    if (detail.data) setSelected(detail.data);
  }, [detail.data]);
  const documents = useQuery({
    queryKey: ["finance", bookId, yearId, "documents", selected?.id],
    enabled: !!selected,
    queryFn: ({ signal }) =>
      api<
        {
          id: string;
          filename: string;
          sha256: string;
          source: string;
          originalLocation: string;
        }[]
      >(`${scope}/journals/${selected?.id}/documents`, { signal }),
  });
  const currentBook = books.data?.find((b) => b.id === bookId);
  const directory = usePersonDirectory(
    currentBook?.tenantId ?? "",
    authorizationHint,
    Boolean(grants["person-read"]),
  );
  useEffect(() => {
    const next =
      activeTab === "tax-entities"
        ? bookId && grants.read
          ? activeTab
          : "journals"
        : (partyTabIntent(
            activeTab,
            Boolean(bookId),
            Boolean(yearId),
            Boolean(directory.configuration),
            Boolean(grants.read),
          ) ??
          directoryTabIntent(
            activeTab,
            Boolean(yearId),
            Boolean(directory.configuration),
          ));
    if (next !== activeTab) setActiveTab(next);
  }, [activeTab, bookId, yearId, directory.configuration, grants.read]);
  const currentYear = years.data?.find((y) => y.id === yearId);
  const personMode = useJournalPersonMode(
    bookId,
    yearId,
    pageAuthHint,
    Boolean(grants.read),
    sourceReadDenied,
  );
  const planTarget = plan?.targetYearId ?? targetYearId;
  const targetPersonMode = useJournalPersonMode(
    bookId,
    planTarget,
    pageAuthHint,
    Boolean(grants.read && planTarget),
    sourceReadDenied,
  );
  const manualJournalReady =
    personMode.ready &&
    (personMode.mode?.mode === "ACCOUNT_ONLY" ||
      personMode.mode?.identityAvailability === "AVAILABLE");
  const personProtocol = personMode.mode?.mode === "JOURNAL_PERSON_SOD";
  const v4TypedJournal =
    isArProducerJournalType(selected?.type) ||
    isAssetProducerJournalType(selected?.type) ||
    isApSettlementJournalType(selected?.type);
  const personJournalNormal =
    !apRaw(selected?.type) &&
    !v4TypedJournal &&
    (!personProtocol || closingNormalLane(selected?.type));
  const personOwnsJournal =
    !personProtocol ||
    personMode.mode?.currentActorAccountId === selected?.createdBy;
  const sourcePersonRestriction = !personMode.ready
    ? "请先成功读取年度凭证审签模式。"
    : personProtocol
      ? journalPersonBlockedReasons.source
      : null;
  const closingPersonRestriction = !personMode.ready
    ? "请先成功读取年度凭证审签模式。"
    : personProtocol
      ? journalPersonBlockedReasons.closing
      : null;
  const yearPersonRestriction =
    !personMode.ready || (planTarget && !targetPersonMode.ready)
      ? "源或目标年度审签模式尚未读取成功。"
      : personProtocol || targetPersonMode.mode?.mode === "JOURNAL_PERSON_SOD"
        ? journalPersonBlockedReasons["year-plan"]
        : null;
  const currentPeriod = periods.data?.find((p) => p.id === periodId);
  const [payrollEntityId, setPayrollEntityId] = useState("");
  const payrollTaxEntities = useQuery({
    queryKey: ["finance", bookId, "payroll-tax-entities", evidenceRefreshToken],
    enabled: Boolean(
      bookId &&
      yearId &&
      grants.read &&
      (activeTab === "payroll" || activeTab === "tax-obligations"),
    ),
    queryFn: ({ signal }) =>
      api<TaxEntityPage>(`${root}/books/${bookId}/tax-entities?limit=50`, {
        signal,
      }),
  });
  const effectivePayrollEntityId =
    payrollEntityId || payrollTaxEntities.data?.items[0]?.id || "";
  const assetAccountingDate = currentPeriod
    ? `${currentYear?.year ?? 2026}-${String(currentPeriod.month).padStart(2, "0")}-15`
    : `${currentYear?.year ?? 2026}-01-15`;
  const assetApi = useMemo(
    () => ({
      cards: (after: string | null, signal: AbortSignal) =>
        api<AssetCard[]>(
          `${scope}/assets/cards?accountingDate=${assetAccountingDate}&limit=100${after ? `&after=${encodeURIComponent(after)}` : ""}`,
          { signal },
        ),
      detail: (id: string, signal: AbortSignal) =>
        api<AssetPlan>(
          `${scope}/assets/plans/${encodeURIComponent(id)}?accountingDate=${assetAccountingDate}`,
          { signal },
        ),
      reconciliation: (signal: AbortSignal) =>
        api<AssetDifference[]>(
          `${scope}/assets/reconciliation?accountingDate=${assetAccountingDate}`,
          { signal },
        ),
      command: (
        action: "depreciate" | "estimate" | "approve" | "post" | "cancel",
        body: Record<string, unknown>,
        idempotencyKey: string,
      ) =>
        mutate<AssetReceipt>(
          action === "depreciate"
            ? `${scope}/assets/depreciations`
            : action === "estimate"
              ? `${scope}/assets/estimate-changes`
              : `${scope}/assets/plans/${encodeURIComponent(String(body.planId ?? ""))}/${action}`,
          "POST",
          {
            accountingDate: assetAccountingDate,
            idempotencyKey,
            payload: body,
          },
        ),
    }),
    [scope, assetAccountingDate],
  );
  const payrollYearNumber = currentYear?.year ?? 0;
  const payrollApi = useMemo(
    () => ({
      listBatches: async (signal: AbortSignal) => {
        const setup = await api<{ batches: PayrollBatchSummary[] }>(
          `${root}/books/${bookId}/payroll/${encodeURIComponent(effectivePayrollEntityId)}/${payrollYearNumber}`,
          { signal },
        );
        return setup.batches ?? [];
      },
      read: (id: string, signal: AbortSignal) =>
        api<PayrollBatch>(
          `${root}/books/${bookId}/payroll/${encodeURIComponent(effectivePayrollEntityId)}/${payrollYearNumber}/${encodeURIComponent(id)}`,
          { signal },
        ),
      act: (
        id: string,
        body: {
          command: { key: string; client: string; expectedVersion: number };
          action: "CALCULATE" | "REVIEW" | "APPROVE" | "LOCK" | "CANCEL";
        },
      ) =>
        mutate<PayrollBatch>(
          `${root}/books/${bookId}/payroll/${encodeURIComponent(effectivePayrollEntityId)}/${payrollYearNumber}/${encodeURIComponent(id)}/actions`,
          "POST",
          body as unknown as Record<string, unknown>,
        ),
    }),
    [bookId, effectivePayrollEntityId, payrollYearNumber],
  );
  const orgFxApi = useMemo(
    (): OrgFxApi => ({
      listBranches: (entityId, signal) =>
        api(`/finance/v1/entities/${entityId}/branches`, { signal }),
      createBranch: (entityId, data) =>
        mutate(`/finance/v1/entities/${entityId}/branches`, "POST", data),
      transitionLifecycle: (entityId, data) =>
        mutate(`/finance/v1/entities/${entityId}/lifecycle`, "POST", data),
      getLifecycle: (entityId) =>
        api(`/finance/v1/entities/${entityId}/lifecycle`),
      listTaxBureaus: () => api("/finance/v1/tax-bureaus"),
      assessStandardEligibility: (entityId, data) =>
        mutate(
          `/finance/v1/entities/${entityId}/standard-eligibility`,
          "POST",
          data,
        ),
      createPolicyVersion: (bId, data) =>
        mutate(`/finance/v1/books/${bId}/policy-versions`, "POST", data),
      approvePolicyVersion: (bId, vId, data) =>
        mutate(
          `/finance/v1/books/${bId}/policy-versions/${vId}/approve`,
          "POST",
          data,
        ),
      validateMultiDate: (bId, data) =>
        mutate(`/finance/v1/books/${bId}/multi-date/validate`, "POST", data),
      handlePeriodReopen: (bId, pId, data) =>
        mutate(
          `/finance/v1/books/${bId}/periods/${pId}/reopen`,
          "POST",
          data,
        ),
      registerFxRate: (data) =>
        mutate("/finance/v1/fx/rate-snapshots", "POST", data),
      translateLine: (data) =>
        mutate("/finance/v1/fx/translations/single", "POST", data),
      createFxRevaluationPlan: (bId, data) =>
        mutate(
          `/finance/v1/books/${bId}/fx-revaluations/plans`,
          "POST",
          data,
        ),
      resolveTaxObligations: (data) =>
        mutate("/finance/v1/tax-obligations/resolve", "POST", data),
      listCurrencies: () => api("/finance/v1/currencies"),
    }),
    [],
  );
  async function selectPlan(id: string) {
    const revision = epoch.current;
    const sequence = ++planReadSequence.current;
    setPlanLoading(true);
    setError("");
    try {
      const result = await api<Plan>(`${scope}/year-close-plans/${id}`);
      if (
        !mounted.current ||
        revision !== epoch.current ||
        sequence !== planReadSequence.current
      )
        return;
      queryClient.setQueryData(
        ["finance", bookId, yearId, "year-close-plan", id],
        result,
      );
      setPlan(result);
    } catch (cause) {
      if (
        mounted.current &&
        revision === epoch.current &&
        sequence === planReadSequence.current
      ) {
        setPlan(null);
        setError(readable(cause));
      }
    } finally {
      if (
        mounted.current &&
        revision === epoch.current &&
        sequence === planReadSequence.current
      )
        setPlanLoading(false);
    }
  }
  function resetContext() {
    setClosingSeed(null);
    journalOpenController.current?.abort();
    planReadSequence.current++;
    setPlanLoading(false);
    void queryClient.cancelQueries({
      queryKey: ["finance", bookId, yearId],
    });
    queryClient.removeQueries({
      queryKey: ["finance", bookId, yearId],
    });
    epoch.current++;
    setSelected(null);
    setCashMatchOpen(null);
    setEditing(null);
    setLines([blankLine(), blankLine()]);
    setDialog(null);
    setPreview(null);
    setPlan(null);
    setTargetYearId("");
    setPayrollEntityId("");
    setReportRequested(false);
    setAuditCursor(null);
    setError("");
    setNotice("");
    form.resetFields();
  }
  function open(value: Dialog) {
    setEditing(null);
    setError("");
    form.resetFields();
    if (value === "member")
      form.setFieldsValue({
        yearScope: "SELECTED",
        yearIds: yearId ? [yearId] : [],
      });
    setLines([blankLine(), blankLine()]);
    setDialog(value);
  }
  async function write(
    path: string,
    data: Record<string, unknown>,
    done?: (result: unknown) => void,
    method: "POST" | "PATCH" = "POST",
  ) {
    if (path.startsWith(scope + "/")) {
      const journalCommand =
        path === `${scope}/journals` || path.startsWith(`${scope}/journals/`);
      const yearCommand = path.startsWith(`${scope}/year-close-plans`);
      if (
        (journalCommand || yearCommand || path === `${scope}/closing-drafts`) &&
        !personMode.ready
      ) {
        setError("年度凭证审签模式尚未读取成功，请先重新读取。");
        return;
      }
      if (yearCommand && yearPersonRestriction) {
        setError(yearPersonRestriction);
        return;
      }
      if (
        selected &&
        apRaw(selected.type) &&
        path.startsWith(`${scope}/journals/${selected.id}`)
      ) {
        setError(
          "真实AP来源凭证只允许应付协议操作，不允许沿用人工凭证或损益结转命令。",
        );
        return;
      }
      if (
        selected &&
        v4TypedJournal &&
        path.startsWith(`${scope}/journals/${selected.id}`)
      ) {
        setError(
          "销售应收与资产会计子账原生凭证仅允许其业务子账协议操作，不允许沿用人工凭证或损益结转命令。",
        );
        return;
      }
      if (personProtocol) {
        if (
          selected &&
          !closingNormalLane(selected.type) &&
          path.startsWith(`${scope}/journals/${selected.id}`)
        ) {
          setError(
            "系统生成凭证仅允许其真实结转运行协议；不能沿用人工凭证编辑、提交、原件或审签命令。",
          );
          return;
        }
        if (path === `${scope}/closing-drafts`) {
          setError(closingPersonRestriction ?? "本模式不支持系统结转草稿");
          return;
        }
        if (journalCommand && /\/(approve|post|reversals)$/.test(path)) {
          setError(
            /\/reversals$/.test(path)
              ? (journalPersonBlockedReasons.reversal ?? "本模式暂不支持冲销")
              : "请使用当前凭证的人员审签操作，不能沿用旧审核或记账协议。",
          );
          return;
        }
        if (journalCommand && !manualJournalReady) {
          setError("当前普通业务人员绑定不可用，不能处理该模式凭证。");
          return;
        }
      }
    }
    if (scopeReadOnly && path.startsWith(scope + "/")) {
      setError(scopeRestriction ?? "年度财务只读");
      return;
    }
    if (lock.current) return;
    lock.current = true;
    setPending(true);
    setError("");
    setNotice("");
    const revision = epoch.current;
    try {
      const fingerprint = JSON.stringify([path, method, data]);
      const idempotencyKey = requestKeys.current.get(fingerprint) ?? key();
      requestKeys.current.set(fingerprint, idempotencyKey);
      const result = await mutate<unknown>(path, method, {
        ...data,
        idempotencyKey,
      });
      requestKeys.current.delete(fingerprint);
      await queryClient.invalidateQueries({ queryKey: ["finance"] });
      if (mounted.current && revision === epoch.current) {
        done?.(result);
        setNotice("操作已保存，请核对最新状态。");
      }
    } catch (cause) {
      if (mounted.current && revision === epoch.current)
        setError(journalPersonReadable(cause, readable));
    } finally {
      lock.current = false;
      if (mounted.current) setPending(false);
    }
  }
  function submit(values: Record<string, unknown>) {
    if (!dialog) return;
    const payload = formPayload(dialog, values);
    if (dialog === "closing")
      return write(`${scope}/closing-drafts`, payload, (result) => {
        setSelected(result as Journal);
        setDialog(null);
      });
    if ((dialog === "reject" || dialog === "cancel") && selected)
      return write(
        `${scope}/journals/${selected.id}/${dialog}`,
        { ...payload, expectedVersion: selected.version },
        (result) => {
          setSelected(result as Journal);
          setDialog(null);
        },
      );
    if (dialog === "book")
      return write(
        `${root}/books`,
        { ...payload, baseCurrency: "CNY", year: Number(values.year) },
        () => setDialog(null),
      );
    if (dialog === "year")
      return write(
        `${root}/books/${bookId}/years`,
        { year: Number(values.year) },
        () => setDialog(null),
      );
    if (dialog === "member")
      return write(
        `${root}/books/${bookId}/members`,
        {
          ...payload,
          yearIds: payload.yearScope === "ALL" ? [] : payload.yearIds,
          enabled: true,
        },
        () => setDialog(null),
      );
    if (dialog === "account")
      return write(`${scope}/accounts`, payload, () => setDialog(null));
    if (dialog === "tax")
      return write(`${root}/books/${bookId}/tax-profile`, payload, () => {
        setDialog(null);
        setNotice("税务档案已登记，状态待专业复核；外部申报未启用。");
      });
    if (dialog === "document" && selected)
      return write(
        `${scope}/journals/${selected.id}/documents`,
        { ...payload, expectedVersion: selected.version },
        () => setDialog(null),
      );
    if (dialog === "reversal" && selected)
      return write(
        `${scope}/journals/${selected.id}/reversals`,
        { ...payload, expectedVersion: selected.version },
        (result) => {
          setSelected(result as Journal);
          setDialog(null);
        },
      );
    if (dialog === "journal") {
      try {
        let debit = 0n;
        let credit = 0n;
        for (const line of lines) {
          if (!line.accountId) throw new Error("每条分录都必须选择科目");
          const d = cnyUnits(line.debit);
          const c = cnyUnits(line.credit);
          if ((d === 0n) === (c === 0n))
            throw new Error("每条分录只能填写借方或贷方且金额须大于零");
          debit += d;
          credit += c;
        }
        if (lines.length < 2 || debit !== credit)
          throw new Error("凭证至少两条分录且借贷合计必须相等");
      } catch (cause) {
        setError(readable(cause));
        return;
      }
      return write(
        editing ? `${scope}/journals/${editing.id}` : `${scope}/journals`,
        {
          ...payload,
          lines: lines.map((line) => ({
            accountId: line.accountId,
            debit: line.debit,
            credit: line.credit,
            memo: line.memo,
          })),
          ...(editing ? { expectedVersion: editing.version } : {}),
        },
        (result) => {
          setDialog(null);
          setSelected(result as Journal);
          setEditing(null);
        },
        editing ? "PATCH" : "POST",
      );
    }
  }
  const accountOptions =
    accounts.data
      ?.filter((a) => a.enabled)
      .map((a) => ({ value: a.id, label: `${a.code} ${a.name}` })) ?? [];
  const queryError = [
    books,
    years,
    accounts,
    periods,
    balances,
    members,
    documents,
    detail,
    closePlans,
    closePlanDetail,
    auditEvents,
  ].find((q) => q.isError)?.error;
  const readDenied = [
    books,
    years,
    accounts,
    periods,
    balances,
    members,
    originals,
    documents,
    detail,
    closePlans,
    closePlanDetail,
    auditEvents,
    reports,
  ].some(
    (query) =>
      query.isError &&
      query.error instanceof ApiError &&
      (query.error.status === 403 || query.error.message === "SCOPE_DENIED"),
  );
  const bookRevoked = revokedSelection(bookId, books.isSuccess, books.data);
  const yearRevoked = revokedSelection(
    yearId,
    years.isSuccess && !years.isFetching,
    years.data,
  );
  useEffect(() => {
    if (!bookId || (!bookRevoked && !yearRevoked && !readDenied)) return;
    const prefix =
      yearRevoked && !bookRevoked && !readDenied
        ? ["finance", bookId, yearId]
        : ["finance", bookId];
    resetContext();
    setActiveTab("journals");
    setYearId("");
    setPeriodId("");
    if (bookRevoked || readDenied) setBookId("");
    void queryClient.cancelQueries({ queryKey: prefix });
    queryClient.removeQueries({ queryKey: prefix });
    if (readDenied)
      queryClient.removeQueries({ queryKey: ["finance", "books"] });
  }, [bookId, yearId, bookRevoked, yearRevoked, readDenied, queryClient]);
  function sourceReadDenied() {
    const prefix = ["finance", bookId];
    resetContext();
    setActiveTab("journals");
    setBookId("");
    setYearId("");
    setPeriodId("");
    void queryClient.cancelQueries({ queryKey: prefix });
    queryClient.removeQueries({ queryKey: prefix });
  }
  async function openSourceJournal(id: string) {
    const revision = epoch.current;
    journalOpenController.current?.abort();
    const controller = new AbortController();
    journalOpenController.current = controller;
    try {
      const journal = await api<Journal>(`${scope}/journals/${id}`, {
        signal: controller.signal,
      });
      if (
        !controller.signal.aborted &&
        mounted.current &&
        revision === epoch.current
      )
        setSelected(journal);
    } catch (cause) {
      if (
        !controller.signal.aborted &&
        mounted.current &&
        revision === epoch.current
      ) {
        if (
          cause instanceof ApiError &&
          (cause.status === 403 || cause.message === "SCOPE_DENIED")
        )
          sourceReadDenied();
        else setError(readable(cause));
      }
    } finally {
      if (journalOpenController.current === controller)
        journalOpenController.current = null;
    }
  }
  function clearJournalPageDetails() {
    epoch.current++;
    journalOpenController.current?.abort();
    setSelected(null);
    setEditing(null);
    if (dialog === "journal" || dialog === "reversal") {
      setDialog(null);
      form.resetFields();
    }
    for (const resource of ["journal-detail", "documents"]) {
      const queryKey = ["finance", bookId, yearId, resource];
      void queryClient.cancelQueries({ queryKey });
      queryClient.removeQueries({ queryKey });
    }
  }
  const closingUi = {
    scope,
    active: Boolean(
      grants.read && personProtocol && personMode.ready && yearId,
    ),
    revision: evidenceRefreshToken,
    newUseAllowed: !scopeRestriction && personMode.ready,
    canMake: Boolean(permissions.create),
    canApprove: Boolean(permissions.approve),
    canPost: Boolean(permissions.post),
    onReadDenied: sourceReadDenied,
    readable,
    onChanged: () => {
      void queryClient.invalidateQueries({
        queryKey: ["finance", bookId, yearId],
      });
    },
  };
  function prepareClosing(seed: ClosingSeed) {
    setClosingSeed(seed);
    setSelected(null);
    setActiveTab("close");
  }
  const buttons = (
    <Space wrap>
      <Button disabled={!grants.manage || pending} onClick={() => open("book")}>
        新建企业账套
      </Button>
      <Button
        disabled={!bookId || !permissions.manage || pending}
        onClick={() => open("year")}
      >
        新增年度
      </Button>
      <Button
        disabled={!bookId || !permissions.manage || pending}
        onClick={() => open("member")}
      >
        成员授权
      </Button>
      <Button
        disabled={!bookId || !permissions.manage || pending}
        onClick={() => open("tax")}
      >
        登记税务档案
      </Button>
      <Button
        onClick={() => {
          setEvidenceRefreshToken((value) => value + 1);
          void queryClient.invalidateQueries({ queryKey: ["finance"] });
        }}
      >
        刷新
      </Button>
    </Space>
  );
  if (bookId && (bookRevoked || yearRevoked || readDenied))
    return (
      <Alert
        type="warning"
        title="账套或年度授权已变化，正在清理当前上下文，请重新选择允许范围。"
      />
    );
  if (!permissions.read)
    return <Alert type="warning" title="没有财务读取权限" />;
  return (
    <PayablesBoundary
      scope={scope}
      authHint={pageAuthHint}
      actor={{
        accountId: personMode.data?.currentActorAccountId ?? "",
        personId: personMode.data?.currentActorPersonId ?? null,
        identityAvailability:
          personMode.data?.identityAvailability ?? "UNCONFIGURED",
      }}
      ready={Boolean(grants.read && yearId && personMode.ready)}
      revision={evidenceRefreshToken}
      denied={sourceReadDenied}
      readable={readable}
      actorChanged={() => {
        void personMode.refetch();
      }}
    >
      <ProfitClosingBoundary
        scope={scope}
        authHint={pageAuthHint}
        accountId={personMode.data?.currentActorAccountId ?? ""}
        personId={personMode.data?.currentActorPersonId ?? null}
        available={personMode.data?.identityAvailability === "AVAILABLE"}
        onActorChanged={() => {
          void personMode.refetch();
        }}
      >
        <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
          <Typography.Title level={3}>企业财务工作台</Typography.Title>
          <Alert
            type="info"
            title="会计内核试运行"
            description="当前支持人民币总账闭环。正式税务申报、支付执行和 AI 写入未开放。原件须先上传字节并由服务端计算哈希。纸质凭证须上传扫描件并登记纸质原件位置，保留来源标记；状态 SOURCE_UNVERIFIED，不代表已验真或验签。"
          />
          {scopeReadOnly && <Alert type="warning" title={scopeRestriction} />}
          {buttons}
          <Card size="small">
            <Space wrap>
              <Select
                aria-label="账套"
                disabled={pending}
                placeholder="选择账套"
                style={{ minWidth: 220 }}
                value={bookId || undefined}
                options={books.data?.map((b) => ({
                  value: b.id,
                  label: b.name,
                }))}
                onChange={(id) => {
                  resetContext();
                  setBookId(id);
                  setYearId("");
                  setPeriodId("");
                }}
              />
              <Select
                aria-label="年度"
                disabled={pending}
                placeholder="选择年度"
                value={yearId || undefined}
                style={{ width: 150 }}
                options={years.data?.map((y) => ({
                  value: y.id,
                  label: `${y.year} ${statuses[y.status] ?? y.status}`,
                }))}
                onChange={(id) => {
                  resetContext();
                  setYearId(id);
                  setPeriodId("");
                }}
              />
              <Select
                aria-label="期间"
                disabled={pending}
                placeholder="选择会计期间"
                value={periodId || undefined}
                style={{ width: 180 }}
                options={periods.data?.map((p) => ({
                  value: p.id,
                  label: `${p.month}月 ${statuses[p.status] ?? p.status}`,
                }))}
                onChange={(id) => {
                  setPeriodId(id);
                  setPreview(null);
                }}
              />
            </Space>
            <Typography.Paragraph style={{ marginTop: 12, marginBottom: 0 }}>
              租户：
              {currentBook
                ? `${currentBook.tenantName ?? "租户"} (${currentBook.tenantId})`
                : "未选择"}
              　法人：
              {currentBook
                ? `${currentBook.legalEntityName ?? "法人"} (${currentBook.legalEntityId})`
                : "未选择"}
              　账套：{currentBook?.name ?? "未选择"}　年度：
              {currentYear?.year ?? "未选择"}　岗位：
              {roleLabels[bookRole ?? ""] ?? "未选择"}　期间：
              {currentPeriod ? `${currentPeriod.month}月` : "未选择"}　制度：
              {standardLabels[currentBook?.standard ?? ""] ?? "未选择"}　币种：
              {currentBook?.baseCurrency ?? "—"}
            </Typography.Paragraph>
          </Card>
          {yearId && (
            <Space wrap>
              <Tag>
                {personMode.mode?.mode === "JOURNAL_PERSON_SOD"
                  ? "本年度凭证：人员独立审签"
                  : personMode.mode?.mode === "ACCOUNT_ONLY"
                    ? "本年度凭证：账号级审签"
                    : "本年度凭证审签模式尚未读取"}
              </Tag>
              {personMode.error && (
                <Typography.Text type="danger">
                  {journalPersonReadable(personMode.error, readable)}
                </Typography.Text>
              )}
            </Space>
          )}
          {(error || queryError) && (
            <Alert
              type="error"
              showIcon
              title={error || readable(queryError)}
            />
          )}
          {notice && (
            <Alert
              type="success"
              title={notice}
              closable
              onClose={() => setNotice("")}
            />
          )}
          {currentBook &&
            grants["person-read"] &&
            (directory.error || directory.denied) && (
              <Alert
                type="warning"
                title="该租户组织人工核验目录不可读或尚未配置；账套负责人身份不授予目录治理权限。"
                action={<Button onClick={directory.retry}>重查目录授权</Button>}
              />
            )}
          {!yearId && !directory.configuration && !(bookId && grants.read) ? (
            <Alert title="请选择企业账套及年度后处理业务。没有账套时，先新建企业账套。" />
          ) : (
            <Tabs
              activeKey={
                !yearId && activeTab !== "tax-entities"
                  ? (partyTabIntent(
                      activeTab,
                      Boolean(bookId),
                      false,
                      Boolean(directory.configuration),
                      Boolean(grants.read),
                    ) ??
                    (directory.configuration ? "person-directory" : activeTab))
                  : activeTab
              }
              onChange={setActiveTab}
              items={[
                ...(bookId && grants.read
                  ? [
                      {
                        key: "tax-entities",
                        label: "纳税主体登记",
                        children: (
                          <TaxEntityPanel
                            bookId={bookId}
                            authHint={pageAuthHint}
                            active={activeTab === "tax-entities"}
                            refreshToken={evidenceRefreshToken}
                            role={bookRole ?? ""}
                            grants={grants}
                            onReadDenied={sourceReadDenied}
                            readable={readable}
                          />
                        ),
                      },
                      {
                        key: "counterparties",
                        label: "往来组织",
                        children: (
                          <CounterpartyPanel
                            bookId={bookId}
                            authHint={pageAuthHint}
                            active={activeTab === "counterparties"}
                            refreshToken={evidenceRefreshToken}
                            role={bookRole ?? ""}
                            grants={grants}
                            onReadDenied={sourceReadDenied}
                            readable={readable}
                          />
                        ),
                      },
                      {
                        key: "org-fx",
                        label: "组织机构与外币汇率",
                        children: (
                          <OrgFxPanel
                            api={orgFxApi}
                            scopeKey={`${currentBook?.tenantId ?? ""}:${bookId}`}
                            canMake={Boolean(
                              grants.read &&
                                permissions.create &&
                                !scopeRestriction,
                            )}
                            canReview={Boolean(
                              grants.read &&
                                permissions.approve &&
                                !scopeRestriction,
                            )}
                          />
                        ),
                      },
                      {
                        key: "iam-ops",
                        label: "权限与运维治理",
                        children: (
                          <div style={{ maxWidth: "100%", minWidth: 0, overflowX: "auto" }}>
                            <IamOpsPanel
                              tenantId={currentBook?.tenantId ?? ""}
                              bookId={bookId}
                              currentAccountId={
                                personMode.data?.currentActorAccountId ?? ""
                              }
                              currentPersonId={
                                personMode.data?.currentActorPersonId ?? ""
                              }
                              isAuditor={bookRole === "AUDITOR"}
                              canManage={Boolean(
                                grants.manage && bookRole === "OWNER",
                              )}
                            />
                          </div>
                        ),
                      },
                    ]
                  : []),
                ...(yearId
                  ? [
                      {
                        key: "payables-ap01",
                        label: "应付会计确认与结算",
                        children: (
                          <Space
                            orientation="vertical"
                            style={{ width: "100%" }}
                          >
                            <PayablesPanel
                              active={activeTab === "payables-ap01"}
                              canOwner={Boolean(
                                grants.read &&
                                permissions.manage &&
                                permissions.create &&
                                bookRole === "OWNER",
                              )}
                              canMake={Boolean(
                                grants.read && permissions.create,
                              )}
                              canReview={Boolean(
                                grants.read && permissions.approve,
                              )}
                              canPost={Boolean(grants.read && permissions.post)}
                              restriction={scopeRestriction}
                            />
                            <PayableSettlementPanel
                              identity={`${currentBook?.tenantId ?? ""}:${bookId}:${yearId}:${pageAuthHint}:${personMode.data?.currentActorAccountId ?? ""}`}
                              scope={scope}
                              yearNumber={currentYear?.year ?? 2026}
                              active={activeTab === "payables-ap01"}
                              canCreate={Boolean(
                                grants.read &&
                                permissions.create &&
                                !scopeRestriction,
                              )}
                              canReview={Boolean(
                                grants.read &&
                                permissions.approve &&
                                !scopeRestriction,
                              )}
                              canPost={Boolean(
                                grants.read &&
                                permissions.post &&
                                !scopeRestriction,
                              )}
                              suppliers={(arCounterparties.data?.items ?? [])
                                .filter(
                                  (c) =>
                                    c.newUseEligible && c.effectiveRevision,
                                )
                                .map((c) => ({
                                  value: c.id,
                                  label: `${c.effectiveDisplayName ?? c.identity.identifier} (${c.identity.identifier})`,
                                }))}
                              cashAccounts={(accounts.data ?? [])
                                .filter(
                                  (a) =>
                                    a.enabled &&
                                    a.category === "ASSET" &&
                                    a.normalSide === "DEBIT",
                                )
                                .map((a) => ({
                                  value: a.id,
                                  label: `${a.code} ${a.name}`,
                                }))}
                              originals={(originals.data ?? []).map((o) => ({
                                value: o.id,
                                label: `${o.filename} (${o.id.slice(0, 8)})`,
                              }))}
                              read={(url, signal) => api(url, { signal })}
                              write={(url, body) => mutate(url, "POST", body)}
                            />
                          </Space>
                        ),
                      },
                      {
                        key: "receivables",
                        label: "销售应收管理",
                        children: (
                          <ReceivablesPanel
                            identity={`${currentBook?.tenantId ?? ""}:${bookId}:${yearId}:${pageAuthHint}:${personMode.data?.currentActorAccountId ?? ""}:${personMode.data?.currentActorPersonId ?? ""}`}
                            scope={scope}
                            active={activeTab === "receivables"}
                            defaultAsOf={assetAccountingDate}
                            canCreate={Boolean(
                              grants.read &&
                              permissions.create &&
                              !scopeRestriction,
                            )}
                            canReview={Boolean(
                              grants.read &&
                              permissions.approve &&
                              !scopeRestriction,
                            )}
                            canPost={Boolean(
                              grants.read &&
                              permissions.post &&
                              !scopeRestriction,
                            )}
                            customers={(arCounterparties.data?.items ?? [])
                              .filter(
                                (c) => c.newUseEligible && c.effectiveRevision,
                              )
                              .map((c) => ({
                                value: c.id,
                                revisionId: c.effectiveRevision!.id,
                                label: `${c.effectiveDisplayName ?? c.identity.identifier} (${c.identity.identifier})`,
                              }))}
                            originals={(originals.data ?? []).map((o) => ({
                              value: o.id,
                              label: `${o.filename} (${o.id.slice(0, 8)})`,
                            }))}
                            read={(url, signal) => api(url, { signal })}
                            write={(url, body) =>
                              mutate(
                                url,
                                "POST",
                                body as Record<string, unknown>,
                              )
                            }
                          />
                        ),
                      },
                      {
                        key: "assets",
                        label: "资产会计子账",
                        children: currentYear ? (
                          <AssetPanel
                            api={assetApi}
                            scopeKey={`${currentBook?.tenantId ?? ""}:${bookId}:${yearId}:${periodId}:${assetAccountingDate}:${pageAuthHint}:${personMode.data?.currentActorAccountId ?? ""}`}
                            canMake={Boolean(
                              grants.read &&
                              permissions.create &&
                              !scopeRestriction,
                            )}
                            canReview={Boolean(
                              grants.read &&
                              permissions.approve &&
                              !scopeRestriction,
                            )}
                            canPost={Boolean(
                              grants.read &&
                              permissions.post &&
                              !scopeRestriction,
                            )}
                          />
                        ) : (
                          <Alert type="info" title="正在读取当前会计年度..." />
                        ),
                      },
                      {
                        key: "payroll",
                        label: "工资与累计预扣",
                        children: (
                          <Space
                            orientation="vertical"
                            style={{ width: "100%" }}
                          >
                            <Space wrap>
                              <Select
                                aria-label="扣缴纳税主体"
                                placeholder="选择已登记扣缴纳税主体"
                                style={{ minWidth: 280 }}
                                value={effectivePayrollEntityId || undefined}
                                options={(
                                  payrollTaxEntities.data?.items ?? []
                                ).map((item) => ({
                                  value: item.id,
                                  label: `${item.effectiveDisplayName ?? item.identity.canonicalTaxRegistrationNo} (${item.identity.canonicalTaxRegistrationNo})`,
                                }))}
                                onChange={setPayrollEntityId}
                              />
                              <Input
                                aria-label="扣缴纳税主体编号"
                                placeholder="或输入扣缴纳税主体UUID"
                                style={{ width: 320 }}
                                value={effectivePayrollEntityId}
                                onChange={(e) =>
                                  setPayrollEntityId(e.target.value)
                                }
                              />
                            </Space>
                            <PayrollPanel
                              api={payrollApi}
                              scopeKey={`${currentBook?.tenantId ?? ""}:${bookId}:${yearId}:${effectivePayrollEntityId}:${pageAuthHint}:${personMode.data?.currentActorAccountId ?? ""}`}
                              canReadSalary={Boolean(
                                grants.read && effectivePayrollEntityId,
                              )}
                              actions={[
                                ...(permissions.create && !scopeRestriction
                                  ? (["CALCULATE", "CANCEL"] as const)
                                  : []),
                                ...(permissions.approve && !scopeRestriction
                                  ? (["REVIEW", "APPROVE"] as const)
                                  : []),
                                ...(permissions.post && !scopeRestriction
                                  ? (["LOCK"] as const)
                                  : []),
                              ]}
                            />
                            <PayrollRestatementCard
                              identity={`${currentBook?.tenantId ?? ""}:${bookId}:${yearId}:${effectivePayrollEntityId}:${pageAuthHint}:${personMode.data?.currentActorAccountId ?? ""}`}
                              bookId={bookId}
                              yearId={yearId}
                              taxEntityId={effectivePayrollEntityId}
                              taxYear={payrollYearNumber}
                              active={activeTab === "payroll"}
                              canCreate={Boolean(
                                grants.read &&
                                permissions.create &&
                                !scopeRestriction,
                              )}
                              canReview={Boolean(
                                grants.read &&
                                permissions.approve &&
                                !scopeRestriction,
                              )}
                              read={(url, signal) => api(url, { signal })}
                              write={(url, body) => mutate(url, "POST", body)}
                            />
                          </Space>
                        ),
                      },
                      {
                        key: "tax-obligations",
                        label: "纳税申报义务",
                        children: (
                          <TaxObligationPanel
                            identity={`${currentBook?.tenantId ?? ""}:${bookId}:${yearId}:${pageAuthHint}:${personMode.data?.currentActorAccountId ?? ""}`}
                            bookId={bookId}
                            yearId={yearId}
                            yearNumber={currentYear?.year ?? 2026}
                            active={activeTab === "tax-obligations"}
                            canManage={Boolean(
                              grants.read && permissions.manage,
                            )}
                            canCreate={Boolean(
                              grants.read &&
                              permissions.create &&
                              !scopeRestriction,
                            )}
                            canReview={Boolean(
                              grants.read &&
                              permissions.approve &&
                              !scopeRestriction,
                            )}
                            canPost={Boolean(
                              grants.read &&
                              permissions.post &&
                              !scopeRestriction,
                            )}
                            taxEntities={(
                              payrollTaxEntities.data?.items ?? []
                            )
                              .filter((item) => item.approvedRevision !== null)
                              .map((item) => ({
                                value: item.id,
                                label: `${item.effectiveDisplayName ?? item.identity.canonicalTaxRegistrationNo} (${item.identity.canonicalTaxRegistrationNo})`,
                              }))}
                            originals={(originals.data ?? []).map((o) => ({
                              value: o.id,
                              label: `${o.filename} (${o.id.slice(0, 8)})`,
                            }))}
                            read={(url, signal) => api(url, { signal })}
                            write={(url, body) =>
                              mutate(url, "POST", body)
                            }
                          />
                        ),
                      },
                      {
                        key: "archive-exports",
                        label: "电子档案导出",
                        children: (
                          <ArchiveExportPanel
                            identity={`${currentBook?.tenantId ?? ""}:${bookId}:${yearId}:${pageAuthHint}:${personMode.data?.currentActorAccountId ?? ""}`}
                            bookId={bookId}
                            yearId={yearId}
                            active={activeTab === "archive-exports"}
                            canExport={Boolean(grants.read && grants.export)}
                            canCreate={Boolean(
                              grants.read &&
                              (bookRole === "OWNER" || bookRole === "MAKER"),
                            )}
                            canReview={Boolean(
                              grants.read &&
                              (bookRole === "OWNER" || bookRole === "REVIEWER"),
                            )}
                            canPost={Boolean(
                              grants.read &&
                              (bookRole === "OWNER" || bookRole === "POSTER"),
                            )}
                            read={(url, signal) => api(url, { signal })}
                            write={(url, body) =>
                              mutate(url, "POST", body)
                            }
                            downloadBlob={async (url) => {
                              const blob = await apiBlob(url);
                              const parts = url.split("/");
                              const reqId = parts[parts.length - 2] ?? "package";
                              return {
                                blob,
                                filename: `finance-archive-${reqId}.tar`,
                              };
                            }}
                          />
                        ),
                      },
                      {
                        key: "carry-readiness",
                        label: "年度承接只读预检",
                        children: (
                          <CarryReadinessPanel
                            key={`${bookId}:${yearId}:${pageAuthHint}:carry-readiness`}
                            contextReady={personMode.ready}
                            bookId={bookId}
                            tenantId={currentBook?.tenantId ?? ""}
                            legalEntityId={currentBook?.legalEntityId ?? ""}
                            bookName={currentBook?.name ?? ""}
                            sourceYearId={yearId}
                            years={years.data ?? []}
                            authHint={pageAuthHint}
                            actorHint={
                              personMode.data
                                ? {
                                    accountId:
                                      personMode.data.currentActorAccountId,
                                    personId:
                                      personMode.data.currentActorPersonId,
                                    identityAvailability:
                                      personMode.data.identityAvailability,
                                  }
                                : null
                            }
                            refreshToken={evidenceRefreshToken}
                            active={activeTab === "carry-readiness"}
                            canRead={Boolean(grants.read)}
                            onReadDenied={sourceReadDenied}
                            onActorChanged={() => {
                              void personMode.refetch();
                            }}
                            readable={readable}
                          />
                        ),
                      },
                      {
                        key: "evidence-usage",
                        label: "单据用途与费用归属",
                        children: (
                          <EvidenceUsagePanel
                            authHint={pageAuthHint}
                            key={`${bookId}:${yearId}:${pageAuthHint}:evidence-usage`}
                            bookId={bookId}
                            yearId={yearId}
                            active={activeTab === "evidence-usage"}
                            refreshToken={evidenceRefreshToken}
                            canRead={Boolean(grants.read)}
                            canOwner={Boolean(
                              grants.read &&
                              grants.manage &&
                              grants.create &&
                              bookRole === "OWNER",
                            )}
                            canMake={Boolean(
                              grants.read &&
                              grants.create &&
                              ["OWNER", "MAKER"].includes(bookRole ?? ""),
                            )}
                            canReview={Boolean(
                              grants.read &&
                              grants.approve &&
                              ["OWNER", "REVIEWER"].includes(bookRole ?? ""),
                            )}
                            onReadDenied={sourceReadDenied}
                            readable={readable}
                          />
                        ),
                      },
                      {
                        key: "journal-person",
                        label: "凭证人员审签",
                        children: (
                          <JournalPersonModePanel
                            key={`${bookId}:${yearId}:${pageAuthHint}`}
                            scope={scope}
                            mode={personMode.mode}
                            ready={personMode.ready}
                            active={activeTab === "journal-person"}
                            canManage={Boolean(
                              grants.manage && bookRole === "OWNER",
                            )}
                            canApprove={Boolean(
                              grants.read &&
                              grants.approve &&
                              ["OWNER", "REVIEWER"].includes(bookRole ?? ""),
                            )}
                            restriction={scopeRestriction}
                            error={
                              personMode.error
                                ? journalPersonReadable(
                                    personMode.error,
                                    readable,
                                  )
                                : ""
                            }
                            onRefresh={() => personMode.refetch()}
                            onReadDenied={sourceReadDenied}
                            readable={readable}
                          />
                        ),
                      },
                      {
                        key: "journals",
                        label: "凭证",
                        children: (
                          <Space
                            orientation="vertical"
                            style={{ width: "100%" }}
                          >
                            <Button
                              type="primary"
                              disabled={
                                !permissions.create ||
                                pending ||
                                !manualJournalReady
                              }
                              onClick={() => open("journal")}
                            >
                              新增凭证草稿
                            </Button>
                            <JournalPagesPanel
                              key={`${bookId}:${yearId}:journals:${pageAuthHint}`}
                              bookId={bookId}
                              yearId={yearId}
                              year={currentYear?.year ?? 0}
                              resource="journals"
                              authHint={pageAuthHint}
                              active={activeTab === "journals"}
                              canRead={Boolean(grants.read)}
                              accounts={accounts.data ?? []}
                              onReadDenied={sourceReadDenied}
                              onResetDetails={clearJournalPageDetails}
                              onJournal={(id) => void openSourceJournal(id)}
                              readable={readable}
                            />
                          </Space>
                        ),
                      },
                      {
                        key: "dimensions-budget",
                        label: "核算维度与预算控制",
                        children: <DimensionsBudgetPanel />,
                      },
                      {
                        key: "evidence-fapiao",
                        label: "发票与原始证据链",
                        children: (
                          <div style={{ maxWidth: "100%", minWidth: 0, overflowX: "auto" }}>
                            <EvidenceFapiaoPanel
                              tenantId={currentBook?.tenantId ?? ""}
                              legalEntityId={currentBook?.legalEntityId ?? ""}
                              canCreate={Boolean(
                                grants.read &&
                                  permissions.create &&
                                  !scopeRestriction,
                              )}
                              canReview={Boolean(
                                grants.read &&
                                  permissions.approve &&
                                  !scopeRestriction,
                              )}
                            />
                          </div>
                        ),
                      },
                      {
                        key: "expense",
                        label: "费用报销与商旅标准",
                        children: (
                          <div style={{ maxWidth: "100%", minWidth: 0, overflowX: "auto" }}>
                            <ExpensePanel
                              identity={`${currentBook?.tenantId ?? ""}:${bookId}:${yearId}:${pageAuthHint}:${personMode.data?.currentActorAccountId ?? ""}`}
                              scope={`${root}/books/${bookId}/expense`}
                              active={activeTab === "expense"}
                              canCreate={Boolean(
                                grants.read &&
                                  permissions.create &&
                                  !scopeRestriction,
                              )}
                              canReview={Boolean(
                                grants.read &&
                                  permissions.approve &&
                                  !scopeRestriction,
                              )}
                              canApprove={Boolean(
                                grants.read &&
                                  permissions.approve &&
                                  !scopeRestriction,
                              )}
                              employees={(arCounterparties.data?.items ?? []).map(
                                (c) => ({
                                  value: c.id,
                                  label: `${c.effectiveDisplayName ?? c.identity.identifier} (${c.identity.identifier})`,
                                }),
                              )}
                              departments={[]}
                              projects={[]}
                              read={(url, signal) => api(url, { signal })}
                              write={(url, body) => mutate(url, "POST", body)}
                            />
                          </div>
                        ),
                      },
                      {
                        key: "ap-ar-ext",
                        label: "应付暂估与应收CAS14/减值",
                        children: (
                          <ApArExtPanel
                            identity={`${currentBook?.tenantId ?? ""}:${bookId}:${yearId}:${pageAuthHint}:${personMode.data?.currentActorAccountId ?? ""}`}
                            scope={scope}
                            active={activeTab === "ap-ar-ext"}
                            canCreate={Boolean(
                              grants.read &&
                                permissions.create &&
                                !scopeRestriction,
                            )}
                            canReview={Boolean(
                              grants.read &&
                                permissions.approve &&
                                !scopeRestriction,
                            )}
                            accountingStandard={
                              currentBook?.standard ??
                              ((currentBook as any)?.accountingStandard) ??
                              "CAS"
                            }
                            suppliers={(arCounterparties.data?.items ?? [])
                              .filter(
                                (c) => c.newUseEligible && c.effectiveRevision,
                              )
                              .map((c) => ({
                                value: c.id,
                                label: `${c.effectiveDisplayName ?? c.identity.identifier} (${c.identity.identifier})`,
                              }))}
                            customers={(arCounterparties.data?.items ?? [])
                              .filter(
                                (c) => c.newUseEligible && c.effectiveRevision,
                              )
                              .map((c) => ({
                                value: c.id,
                                label: `${c.effectiveDisplayName ?? c.identity.identifier} (${c.identity.identifier})`,
                              }))}
                            read={(url, signal) => api(url, { signal })}
                            write={(url, body) =>
                              mutate(url, "POST", body as any)
                            }
                          />
                        ),
                      },
                      {
                        key: "treasury",
                        label: "资金司库与银行对账",
                        children: (
                          <TreasuryPanel
                            tenantId={currentBook?.tenantId ?? ""}
                            bookId={bookId}
                            currentUserId={
                              personMode.data?.currentActorPersonId ?? ""
                            }
                          />
                        ),
                      },
                      {
                        key: "inventory-assets",
                        label: "存货与资产延展核算",
                        children: (
                          <InventoryAssetsPanel
                            scopeKey={`${currentBook?.tenantId ?? ""}:${bookId}:${yearId}`}
                            canMake={Boolean(
                              grants.read &&
                                permissions.create &&
                                !scopeRestriction,
                            )}
                            canReview={Boolean(
                              grants.read &&
                                permissions.approve &&
                                !scopeRestriction,
                            )}
                            canPost={Boolean(
                              grants.read &&
                                permissions.post &&
                                !scopeRestriction,
                            )}
                          />
                        ),
                      },
                      {
                        key: "gl-yearend",
                        label: "总账期末与关账治理",
                        children: (
                          <GlYearendPanel
                            tenantId={currentBook?.tenantId ?? ""}
                            bookId={bookId}
                            yearId={yearId}
                            standard={
                              currentBook?.standard ??
                              ((currentBook as any)?.accountingStandard) ??
                              "CAS"
                            }
                          />
                        ),
                      },
                      {
                        key: "payroll-tax-ext",
                        label: "薪酬奖金与税控日历",
                        children: (
                          <PayrollTaxExtPanel
                            bookId={bookId}
                            tenantId={currentBook?.tenantId ?? ""}
                            currentUserId={
                              personMode.data?.currentActorPersonId ?? ""
                            }
                          />
                        ),
                      },
                      {
                        key: "statutory-reports",
                        label: "法定报表与经营看板",
                        children: (
                          <StatutoryReportsPanel
                            tenantId={currentBook?.tenantId ?? ""}
                            bookId={bookId}
                            yearId={yearId}
                          />
                        ),
                      },
                      {
                        key: "archive-int-ai",
                        label: "档案管理与智能工作流",
                        children: <ArchiveIntAiPanel />,
                      },
                      {
                        key: "accounts",
                        label: "科目",
                        children: (
                          <Space
                            orientation="vertical"
                            style={{ width: "100%" }}
                          >
                            <Button
                              disabled={
                                scopeReadOnly || !permissions.manage || pending
                              }
                              onClick={() => open("account")}
                            >
                              新增科目
                            </Button>
                            <Table<Account>
                              rowKey="id"
                              dataSource={accounts.data ?? []}
                              columns={[
                                { title: "编码", dataIndex: "code" },
                                { title: "科目", dataIndex: "name" },
                                { title: "类别", dataIndex: "category" },
                                { title: "结转用途", dataIndex: "purpose" },
                                { title: "余额方向", dataIndex: "normalSide" },
                                {
                                  title: "状态",
                                  dataIndex: "enabled",
                                  render: (v) => (v ? "有效" : "停用"),
                                },
                              ]}
                            />
                          </Space>
                        ),
                      },
                      {
                        key: "balance",
                        label: "试算平衡",
                        children: (
                          <>
                            <Typography.Paragraph>
                              年度试算：包含正式期初和本年已记账发生额；选择月份不改变年度口径。
                            </Typography.Paragraph>
                            <Alert
                              type={
                                balances.data?.balanced ? "success" : "warning"
                              }
                              title={
                                balances.data
                                  ? `含正式期初的年度合计：借方 ${balances.data.debit} / 贷方 ${balances.data.credit}；${balances.data.balanced ? "平衡" : "不平衡"}`
                                  : "正在读取已记账事实"
                              }
                            />
                            <Table
                              rowKey="accountId"
                              dataSource={balances.data?.rows ?? []}
                              scroll={{ x: 650 }}
                              columns={[
                                { title: "科目", dataIndex: "name" },
                                {
                                  title: "正式期初（借正贷负）",
                                  dataIndex: "opening",
                                },
                                { title: "本年借方发生", dataIndex: "debit" },
                                { title: "本年贷方发生", dataIndex: "credit" },
                                {
                                  title: "期末余额（借正贷负）",
                                  dataIndex: "balance",
                                },
                              ]}
                            />
                            {balances.data?.watermark && (
                              <Collapse
                                items={[
                                  {
                                    key: "watermark",
                                    label: "查看年度数据水位",
                                    children: (
                                      <Descriptions
                                        column={2}
                                        items={[
                                          {
                                            key: "posting",
                                            label: "记账水位",
                                            children:
                                              balances.data.watermark
                                                .postingSeq,
                                          },
                                          {
                                            key: "opening",
                                            label: "期初版本",
                                            children:
                                              balances.data.watermark
                                                .openingVersion,
                                          },
                                          {
                                            key: "baseline",
                                            label: "基线版本",
                                            children:
                                              balances.data.watermark
                                                .baselineVersion,
                                          },
                                          {
                                            key: "close",
                                            label: "来源结账版本",
                                            children:
                                              balances.data.watermark
                                                .sourceCloseVersion,
                                          },
                                        ]}
                                      />
                                    ),
                                  },
                                ]}
                              />
                            )}
                          </>
                        ),
                      },
                      {
                        key: "ledger",
                        label: "明细账",
                        children: (
                          <JournalPagesPanel
                            key={`${bookId}:${yearId}:ledger:${pageAuthHint}`}
                            bookId={bookId}
                            yearId={yearId}
                            year={currentYear?.year ?? 0}
                            resource="ledger"
                            authHint={pageAuthHint}
                            active={activeTab === "ledger"}
                            canRead={Boolean(grants.read)}
                            accounts={accounts.data ?? []}
                            onReadDenied={sourceReadDenied}
                            onResetDetails={clearJournalPageDetails}
                            onJournal={(id) => void openSourceJournal(id)}
                            readable={readable}
                          />
                        ),
                      },
                      {
                        key: "reports",
                        label: "财务报表",
                        children: (
                          <Space
                            orientation="vertical"
                            style={{ width: "100%" }}
                          >
                            <Alert title="资产负债表、利润表与现金流量表需要经审定的制度及规则版本。缺失项显示 MISSING，不作为零或正式报告。" />
                            <Button
                              onClick={() => {
                                if (reportRequested) void reports.refetch();
                                else setReportRequested(true);
                              }}
                              loading={reports.isFetching}
                            >
                              读取报表
                            </Button>
                            {reports.error && (
                              <Alert
                                type="warning"
                                title={readable(reports.error)}
                              />
                            )}
                            {reports.data !== undefined && (
                              <Alert
                                type="warning"
                                title="MISSING · 未生成正式财务报表"
                                description={
                                  <pre style={{ whiteSpace: "pre-wrap" }}>
                                    {JSON.stringify(reports.data, null, 2)}
                                  </pre>
                                }
                              />
                            )}
                            <ReportDraftPanel
                              identity={`${currentBook?.tenantId ?? ""}:${bookId}:${yearId}:${pageAuthHint}:${personMode.data?.currentActorAccountId ?? ""}`}
                              scope={scope}
                              standard={currentBook?.standard ?? "CAS"}
                              yearNumber={currentYear?.year ?? 2026}
                              active={activeTab === "reports"}
                              canManage={Boolean(
                                grants.read &&
                                permissions.manage &&
                                !scopeRestriction,
                              )}
                              canReview={Boolean(
                                grants.read &&
                                permissions.approve &&
                                !scopeRestriction,
                              )}
                              accounts={(accounts.data ?? []).map((a) => ({
                                value: a.id,
                                label: `${a.code} ${a.name} (${a.category}/${a.normalSide})`,
                              }))}
                              read={(url, signal) => api(url, { signal })}
                              write={(url, body) =>
                                mutate(url, "POST", body)
                              }
                            />
                          </Space>
                        ),
                      },
                      {
                        key: "close",
                        label: "月结与年结",
                        children: (
                          <Space
                            orientation="vertical"
                            style={{ width: "100%" }}
                          >
                            {personProtocol ? (
                              <ProfitClosingPanel
                                key={`${bookId}:${yearId}:${pageAuthHint}`}
                                ui={closingUi}
                                year={currentYear?.year ?? 0}
                                seed={closingSeed}
                                onSeedConsumed={() => setClosingSeed(null)}
                              />
                            ) : (
                              <>
                                <Typography.Title level={5}>
                                  期末损益结转
                                </Typography.Title>
                                <Alert title="先生成损益结转凭证，完成提交、独立审核和记账；年末再生成本年利润转留存收益凭证，并走相同审签流程。科目由明确用途配置，不按编码猜测。" />
                                {closingPersonRestriction && (
                                  <Alert
                                    type="warning"
                                    title={closingPersonRestriction}
                                  />
                                )}
                                <Button
                                  disabled={
                                    !permissions.create ||
                                    pending ||
                                    !!closingPersonRestriction
                                  }
                                  onClick={() => open("closing")}
                                >
                                  生成结转待审草稿
                                </Button>
                              </>
                            )}
                            <Typography.Title level={5}>
                              月结预检
                            </Typography.Title>
                            <Typography.Text>
                              所选岗位：{roleLabels[bookRole ?? ""] ?? "未选择"}
                              　期间：
                              {currentPeriod
                                ? `${currentPeriod.month}月`
                                : "请在上方选择期间"}
                            </Typography.Text>
                            <Button
                              disabled={
                                !currentPeriod || !permissions.close || pending
                              }
                              onClick={() =>
                                currentPeriod &&
                                void write(
                                  `${scope}/periods/${currentPeriod.id}/close-preview`,
                                  { expectedVersion: currentPeriod.version },
                                  (result) =>
                                    setPreview(result as typeof preview),
                                )
                              }
                            >
                              生成月结预检
                            </Button>
                            {preview && (
                              <>
                                <Alert
                                  type={
                                    preview.canClose ? "success" : "warning"
                                  }
                                  title={
                                    preview.canClose
                                      ? "检查通过，执行前请核对摘要"
                                      : "存在结账阻断项"
                                  }
                                  description={
                                    <>
                                      <div>摘要：{preview.digest}</div>
                                      {preview.blockers.map((b, i) => (
                                        <div key={i}>
                                          {typeof b === "string"
                                            ? b
                                            : JSON.stringify(b)}
                                        </div>
                                      ))}
                                    </>
                                  }
                                />
                                <Button
                                  danger
                                  disabled={
                                    !preview.canClose ||
                                    !permissions.close ||
                                    pending
                                  }
                                  onClick={() =>
                                    currentPeriod &&
                                    void write(
                                      `${scope}/periods/${currentPeriod.id}/close`,
                                      {
                                        expectedVersion: currentPeriod.version,
                                        digest: preview.digest,
                                      },
                                      () => setPreview(null),
                                    )
                                  }
                                >
                                  确认关闭所选期间
                                </Button>
                              </>
                            )}
                            <Typography.Title level={5}>
                              年度结转
                            </Typography.Title>
                            {yearPersonRestriction && (
                              <Alert
                                type="warning"
                                title={yearPersonRestriction}
                              />
                            )}
                            <Table<Plan>
                              rowKey="id"
                              loading={closePlans.isFetching}
                              dataSource={closePlans.data ?? []}
                              scroll={{ x: 700 }}
                              columns={[
                                {
                                  title: "源年度",
                                  dataIndex: "sourceYearId",
                                  render: (id) =>
                                    years.data?.find((y) => y.id === id)
                                      ?.year ?? id,
                                },
                                {
                                  title: "目标年度",
                                  dataIndex: "targetYearId",
                                  render: (id) =>
                                    years.data?.find((y) => y.id === id)
                                      ?.year ?? id,
                                },
                                {
                                  title: "状态",
                                  dataIndex: "status",
                                  render: status,
                                },
                                { title: "创建人", dataIndex: "createdBy" },
                                { title: "批准人", dataIndex: "approvedBy" },
                                {
                                  title: "操作",
                                  render: (_, row) => (
                                    <Button
                                      onClick={() => void selectPlan(row.id)}
                                    >
                                      查看影响 / 处理
                                    </Button>
                                  ),
                                },
                              ]}
                            />
                            <Select
                              aria-label="结转目标年度"
                              placeholder="选择目标年度"
                              style={{ width: 220 }}
                              value={targetYearId || undefined}
                              options={years.data
                                ?.filter((y) => y.id !== yearId)
                                .map((y) => ({
                                  value: y.id,
                                  label: String(y.year),
                                }))}
                              onChange={(id) => {
                                setTargetYearId(id);
                                setPlan(null);
                              }}
                            />
                            <Button
                              disabled={
                                !targetYearId ||
                                !permissions.close ||
                                pending ||
                                !!yearPersonRestriction
                              }
                              onClick={() =>
                                void write(
                                  `${scope}/year-close-plans`,
                                  { targetYearId },
                                  (result) => {
                                    const value = result as Plan;
                                    if (value.id) setPlan(value);
                                    else {
                                      setPlan(null);
                                      setError(
                                        `年结被阻断：${value.blockers?.join("；") ?? "请检查期间状态"}`,
                                      );
                                    }
                                  },
                                )
                              }
                            >
                              生成年结预览
                            </Button>
                            {plan && (
                              <>
                                <Typography.Title level={5}>
                                  结转影响预览
                                </Typography.Title>
                                <Typography.Paragraph>
                                  请核对源/目标年度、期初金额及科目映射。批准绑定以下内容及摘要；任何变化后须重新预览与审批。
                                </Typography.Paragraph>
                                {!targetMappingReady && (
                                  <Alert
                                    type="warning"
                                    title={targetMappingMessage}
                                  />
                                )}
                                {plan.preview ? (
                                  <>
                                    <Typography.Paragraph>
                                      源年度版本：{plan.preview.sourceVersion}
                                      ；目标年度版本：
                                      {plan.preview.targetVersion}
                                    </Typography.Paragraph>
                                    <Table
                                      rowKey="code"
                                      pagination={false}
                                      dataSource={plan.preview.balances ?? []}
                                      scroll={{ x: 600 }}
                                      columns={[
                                        {
                                          title: "源科目编码",
                                          dataIndex: "code",
                                        },
                                        {
                                          title: "目标科目",
                                          dataIndex: "targetAccountId",
                                          render: (id) =>
                                            targetAccounts.data?.find(
                                              (a) => a.id === id,
                                            )
                                              ? `${targetAccounts.data.find((a) => a.id === id)!.code} ${targetAccounts.data.find((a) => a.id === id)!.name}`
                                              : "目标科目尚不可用",
                                        },
                                        {
                                          title: "结转期初金额（人民币）",
                                          dataIndex: "balance",
                                        },
                                      ]}
                                    />
                                  </>
                                ) : (
                                  <Alert
                                    type="warning"
                                    title="正在读取结转金额与映射明细；明细不可用时禁止批准和执行。"
                                  />
                                )}
                                <Descriptions
                                  bordered
                                  size="small"
                                  column={1}
                                  items={[
                                    {
                                      key: "id",
                                      label: "批次",
                                      children: plan.id,
                                    },
                                    {
                                      key: "status",
                                      label: "状态",
                                      children: status(plan.status),
                                    },
                                    {
                                      key: "digest",
                                      label: "批准绑定摘要",
                                      children: plan.digest,
                                    },
                                    {
                                      key: "blockers",
                                      label: "阻断项",
                                      children: (plan.blockers ?? []).length
                                        ? (plan.blockers ?? [])
                                            .map((b) =>
                                              typeof b === "string"
                                                ? b
                                                : JSON.stringify(b),
                                            )
                                            .join("；")
                                        : "无",
                                    },
                                  ]}
                                />
                                <Space>
                                  <Button
                                    disabled={
                                      !permissions.approve ||
                                      !!yearPersonRestriction ||
                                      pending ||
                                      !targetMappingReady ||
                                      (plan.blockers ?? []).length > 0
                                    }
                                    onClick={() =>
                                      void write(
                                        `${scope}/year-close-plans/${plan.id}/approve`,
                                        {
                                          expectedVersion: plan.version,
                                          digest: plan.digest,
                                        },
                                        (result) => setPlan(result as Plan),
                                      )
                                    }
                                  >
                                    独立审批
                                  </Button>
                                  <Button
                                    danger
                                    disabled={
                                      !permissions.close ||
                                      !!yearPersonRestriction ||
                                      pending ||
                                      !targetMappingReady ||
                                      plan.status !== "APPROVED"
                                    }
                                    onClick={() =>
                                      void write(
                                        `${scope}/year-close-plans/${plan.id}/execute`,
                                        {
                                          expectedVersion: plan.version,
                                          digest: plan.digest,
                                        },
                                        (result) => setPlan(result as Plan),
                                      )
                                    }
                                  >
                                    执行结转
                                  </Button>
                                </Space>
                              </>
                            )}
                          </Space>
                        ),
                      },
                      ...(grants.manage && bookRole === "OWNER"
                        ? [
                            {
                              key: "audit",
                              label: "财务审计",
                              children: (
                                <Space
                                  orientation="vertical"
                                  style={{ width: "100%" }}
                                >
                                  <Typography.Paragraph>
                                    只读查看当前账套年度审计，每页最多20条。
                                  </Typography.Paragraph>
                                  {auditEvents.isError && (
                                    <Alert
                                      type="error"
                                      title={readable(auditEvents.error)}
                                    />
                                  )}
                                  <Table
                                    rowKey="id"
                                    loading={auditEvents.isFetching}
                                    dataSource={
                                      auditEvents.isError
                                        ? []
                                        : (auditEvents.data?.items ?? [])
                                    }
                                    pagination={false}
                                    scroll={{ x: 850 }}
                                    columns={[
                                      { title: "时间", dataIndex: "createdAt" },
                                      {
                                        title: "业务动作",
                                        dataIndex: "action",
                                        render: (action: string) =>
                                          auditActionLabels[action] ?? action,
                                      },
                                      { title: "操作者", dataIndex: "actorId" },
                                      {
                                        title: "业务对象",
                                        dataIndex: "targetId",
                                      },
                                      {
                                        title: "内容摘要",
                                        dataIndex: "payloadHash",
                                        ellipsis: true,
                                      },
                                    ]}
                                    expandable={{
                                      expandedRowRender: (row) => (
                                        <Space
                                          orientation="vertical"
                                          style={{ width: "100%" }}
                                        >
                                          <Typography.Text copyable>
                                            审计ID：{row.id}
                                          </Typography.Text>
                                          <Typography.Text copyable>
                                            完整摘要：{row.payloadHash}
                                          </Typography.Text>
                                          <Collapse
                                            items={[
                                              {
                                                key: "snapshot",
                                                label: "查看业务快照 JSON",
                                                children: (
                                                  <pre
                                                    style={{
                                                      whiteSpace: "pre-wrap",
                                                      overflowWrap: "anywhere",
                                                    }}
                                                  >
                                                    {JSON.stringify(
                                                      row.payload,
                                                      null,
                                                      2,
                                                    )}
                                                  </pre>
                                                ),
                                              },
                                            ]}
                                          />
                                        </Space>
                                      ),
                                    }}
                                  />
                                  <Space>
                                    <Button
                                      disabled={
                                        !auditCursor || auditEvents.isFetching
                                      }
                                      onClick={() => setAuditCursor(null)}
                                    >
                                      返回首屏
                                    </Button>
                                    <Button
                                      disabled={
                                        !auditEvents.data?.nextCursor ||
                                        auditEvents.isFetching ||
                                        auditEvents.isError
                                      }
                                      onClick={() =>
                                        setAuditCursor(
                                          auditEvents.data?.nextCursor ?? null,
                                        )
                                      }
                                    >
                                      下一页
                                    </Button>
                                  </Space>
                                </Space>
                              ),
                            },
                          ]
                        : []),
                      ...(grants.manage && bookRole === "OWNER"
                        ? (["source-events", "outbox"] as const).map(
                            (mode) => ({
                              key: mode,
                              label:
                                mode === "source-events"
                                  ? "业务来源"
                                  : "事件处理",
                              children: (
                                <SourceEventsPanel
                                  key={`${bookId}:${yearId}:${mode}`}
                                  bookId={bookId}
                                  yearId={yearId}
                                  mode={mode}
                                  active={activeTab === mode}
                                  canManage={
                                    grants.manage && bookRole === "OWNER"
                                  }
                                  canCreate={Boolean(
                                    grants.manage &&
                                    grants.create &&
                                    bookRole === "OWNER",
                                  )}
                                  restriction={
                                    mode === "outbox"
                                      ? yearProjectionRestriction(selectedYear)
                                      : (scopeRestriction ??
                                        sourcePersonRestriction ??
                                        null)
                                  }
                                  accounts={accounts.data ?? []}
                                  readable={readable}
                                  onReadDenied={sourceReadDenied}
                                  onJournal={(id) => void openSourceJournal(id)}
                                />
                              ),
                            }),
                          )
                        : []),
                      {
                        key: "cash-scope",
                        label: "现金范围",
                        children: (
                          <CashScopePanel
                            key={`${bookId}:${yearId}:cash-scope`}
                            bookId={bookId}
                            yearId={yearId}
                            year={currentYear?.year ?? 0}
                            standard={currentBook?.standard ?? ""}
                            role={bookRole}
                            active={activeTab === "cash-scope"}
                            canRead={Boolean(grants.read)}
                            canCreate={Boolean(
                              grants.manage &&
                              grants.create &&
                              bookRole === "OWNER",
                            )}
                            canApprove={Boolean(
                              grants.approve &&
                              ["OWNER", "REVIEWER"].includes(bookRole ?? ""),
                            )}
                            restriction={scopeRestriction}
                            accounts={accounts.data ?? []}
                            periods={periods.data ?? []}
                            readable={readable}
                            onReadDenied={sourceReadDenied}
                          />
                        ),
                      },
                      {
                        key: "cash-movements",
                        label: "现金来源登记",
                        children: (
                          <CashMovementPanel
                            onCorrection={(id) => {
                              setCashMatchOpen((previous) => ({
                                id,
                                revision: (previous?.revision ?? 0) + 1,
                              }));
                              setActiveTab("cash-match");
                            }}
                            key={`${bookId}:${yearId}:cash-movements`}
                            bookId={bookId}
                            yearId={yearId}
                            active={activeTab === "cash-movements"}
                            canRead={Boolean(grants.read)}
                            canCreate={Boolean(
                              grants.create &&
                              ["OWNER", "MAKER"].includes(bookRole ?? ""),
                            )}
                            restriction={scopeRestriction}
                            periods={periods.data ?? []}
                            readable={readable}
                            onReadDenied={sourceReadDenied}
                          />
                        ),
                      },
                      {
                        key: "cash-match",
                        label: "现金匹配更正",
                        children: (
                          <CashMatchPanel
                            openRequest={cashMatchOpen}
                            key={`${bookId}:${yearId}:cash-match`}
                            bookId={bookId}
                            yearId={yearId}
                            active={activeTab === "cash-match"}
                            canRead={Boolean(grants.read)}
                            canCreate={Boolean(
                              grants.create &&
                              ["OWNER", "MAKER"].includes(bookRole ?? ""),
                            )}
                            canApprove={Boolean(
                              grants.approve &&
                              ["OWNER", "REVIEWER"].includes(bookRole ?? ""),
                            )}
                            restriction={scopeRestriction}
                            periods={periods.data ?? []}
                            readable={readable}
                            onReadDenied={sourceReadDenied}
                          />
                        ),
                      },
                      {
                        key: "cash-source-review",
                        label: "来源人工复核",
                        children: (
                          <CashSourceReviewPanel
                            key={`${bookId}:${yearId}:cash-source-review`}
                            bookId={bookId}
                            yearId={yearId}
                            year={currentYear?.year ?? 0}
                            active={activeTab === "cash-source-review"}
                            canRead={Boolean(grants.read)}
                            canPrepare={Boolean(
                              grants.create &&
                              ["OWNER", "MAKER"].includes(bookRole ?? ""),
                            )}
                            canReview={Boolean(
                              grants.approve &&
                              ["OWNER", "REVIEWER"].includes(bookRole ?? ""),
                            )}
                            canRelease={Boolean(
                              grants.manage &&
                              grants.create &&
                              bookRole === "OWNER",
                            )}
                            restriction={scopeRestriction}
                            periods={periods.data ?? []}
                            readable={readable}
                            onReadDenied={sourceReadDenied}
                          />
                        ),
                      },
                      {
                        key: "cash-allocation",
                        label: "普通现金分配",
                        children: (
                          <CashAllocationPanel
                            key={`${bookId}:${yearId}:cash-allocation`}
                            bookId={bookId}
                            yearId={yearId}
                            year={currentYear?.year ?? 0}
                            active={activeTab === "cash-allocation"}
                            canRead={Boolean(grants.read)}
                            canPrepare={Boolean(
                              grants.create &&
                              ["OWNER", "MAKER"].includes(bookRole ?? ""),
                            )}
                            canReview={Boolean(
                              grants.approve &&
                              ["OWNER", "REVIEWER"].includes(bookRole ?? ""),
                            )}
                            canRelease={Boolean(
                              grants.manage &&
                              grants.create &&
                              bookRole === "OWNER",
                            )}
                            restriction={scopeRestriction}
                            periods={periods.data ?? []}
                            readable={readable}
                            onReadDenied={sourceReadDenied}
                          />
                        ),
                      },
                      {
                        key: "cash-ledger-coverage",
                        label: "账务现金行差异",
                        children: (
                          <CashLedgerCoveragePanel
                            key={`${bookId}:${yearId}:cash-ledger-coverage`}
                            bookId={bookId}
                            yearId={yearId}
                            active={activeTab === "cash-ledger-coverage"}
                            canRead={Boolean(grants.read)}
                            readable={readable}
                            onReadDenied={sourceReadDenied}
                          />
                        ),
                      },
                      {
                        key: "cash-policy",
                        label: "普通现金政策",
                        children: (
                          <CashPolicyPanel
                            key={`${bookId}:${yearId}:cash-policy`}
                            bookId={bookId}
                            yearId={yearId}
                            year={currentYear?.year ?? 0}
                            active={activeTab === "cash-policy"}
                            canRead={Boolean(grants.read)}
                            canPrepare={Boolean(
                              grants.manage &&
                              grants.create &&
                              bookRole === "OWNER",
                            )}
                            canReview={Boolean(
                              grants.read &&
                              grants.approve &&
                              ["OWNER", "REVIEWER"].includes(bookRole ?? ""),
                            )}
                            restriction={scopeRestriction}
                            readable={readable}
                            onReadDenied={sourceReadDenied}
                          />
                        ),
                      },
                      {
                        key: "members",
                        label: "账套成员",
                        children: (
                          <Table
                            rowKey="userId"
                            dataSource={members.data ?? []}
                            columns={[
                              { title: "账号", dataIndex: "username" },
                              {
                                title: "岗位",
                                dataIndex: "role",
                                render: (value) => roleLabels[value] ?? value,
                              },
                              {
                                title: "成员状态",
                                dataIndex: "enabled",
                                render: (value) => (value ? "启用" : "停用"),
                              },
                              {
                                title: "操作",
                                render: (_, member) =>
                                  permissions.manage && bookRole === "OWNER" ? (
                                    <Popconfirm
                                      title={
                                        member.enabled
                                          ? "确认停用此成员？"
                                          : "确认重新启用此成员？"
                                      }
                                      description="保持其现有岗位和年度范围，仅更改启用状态。"
                                      okText="确认"
                                      cancelText="取消"
                                      onConfirm={() =>
                                        write(
                                          `${root}/books/${bookId}/members`,
                                          {
                                            username: member.username,
                                            role: member.role,
                                            yearScope: member.yearScope,
                                            yearIds: member.yearIds,
                                            enabled: !member.enabled,
                                          },
                                        )
                                      }
                                    >
                                      <Button
                                        danger={member.enabled}
                                        disabled={pending}
                                      >
                                        {member.enabled ? "停用" : "重新启用"}
                                      </Button>
                                    </Popconfirm>
                                  ) : null,
                              },
                              {
                                title: "年度授权范围",
                                dataIndex: "yearScope",
                                render: (value) =>
                                  value === "ALL"
                                    ? "全部年度（明确授权）"
                                    : "指定年度",
                              },
                              {
                                title: "授权年度",
                                dataIndex: "yearIds",
                                render: (ids: string[] = []) =>
                                  ids
                                    .map(
                                      (id) =>
                                        years.data?.find(
                                          (year) => year.id === id,
                                        )?.year ?? id,
                                    )
                                    .join("、") || "—",
                              },
                            ]}
                          />
                        ),
                      },
                    ]
                  : []),
                ...(directory.configuration
                  ? [
                      {
                        key: "person-directory",
                        label: "组织人工核验目录",
                        children: (
                          <PersonDirectoryPanel
                            key={`${currentBook?.tenantId}:${authorizationHint}`}
                            configuration={directory.configuration}
                            authorizationPending={
                              directory.authorizationPending
                            }
                            configurationError={directory.error}
                            active={!yearId || activeTab === "person-directory"}
                            canPropose={Boolean(grants["person-propose"])}
                            canConfirm={Boolean(grants["person-confirm"])}
                            onReadDenied={directory.onReadDenied}
                            readable={readable}
                          />
                        ),
                      },
                    ]
                  : []),
              ]}
            />
          )}
          <Modal
            title={
              selected
                ? `凭证详情 · ${selected.number ?? selected.id}`
                : "凭证详情"
            }
            open={!!selected}
            width={900}
            onCancel={() => setSelected(null)}
            footer={null}
            destroyOnHidden
          >
            {selected && (
              <Space orientation="vertical" style={{ width: "100%" }}>
                <Descriptions
                  column={2}
                  items={[
                    { key: "date", label: "会计日期", children: selected.date },
                    {
                      key: "status",
                      label: "状态",
                      children: status(selected.status),
                    },
                    {
                      key: "summary",
                      label: "摘要",
                      children: selected.summary,
                    },
                    {
                      key: "version",
                      label: "版本",
                      children: selected.version,
                    },
                    {
                      key: "maker",
                      label: "制单人",
                      children: selected.createdBy,
                    },
                    {
                      key: "reviewer",
                      label: "审核人",
                      children: selected.approvedBy ?? "未审核",
                    },
                  ]}
                />
                <Table
                  rowKey={(_, i) => String(i)}
                  pagination={false}
                  dataSource={selected.lines}
                  scroll={{ x: 600 }}
                  columns={[
                    {
                      title: "科目",
                      dataIndex: "accountId",
                      render: (id) =>
                        accounts.data?.find((a) => a.id === id)?.name ?? id,
                    },
                    { title: "借方", dataIndex: "debit" },
                    { title: "贷方", dataIndex: "credit" },
                    { title: "摘要", dataIndex: "memo" },
                  ]}
                />
                {personJournalNormal && (
                  <Space wrap>
                    <Button
                      disabled={
                        !permissions.create ||
                        !manualJournalReady ||
                        !personOwnsJournal ||
                        selected.status !== "DRAFT" ||
                        (selected.type !== undefined &&
                          selected.type !== "NORMAL") ||
                        !detail.data ||
                        pending
                      }
                      onClick={() => {
                        open("journal");
                        setEditing(selected);
                        setLines(selected.lines.map((l) => ({ ...l })));
                        form.setFieldsValue({
                          date: selected.date,
                          summary: selected.summary,
                        });
                      }}
                    >
                      编辑草稿
                    </Button>
                    {(
                      [
                        ["submit", "提交审核", "DRAFT", "create"],
                        ["approve", "审核通过", "SUBMITTED", "approve"],
                        ["post", "正式记账", "APPROVED", "post"],
                        ["withdraw", "撤回待审", "SUBMITTED", "create"],
                      ] as const
                    )
                      .filter(
                        ([action]) =>
                          !personProtocol ||
                          !(action === "approve" || action === "post"),
                      )
                      .map(([action, label, needed, permission]) => (
                        <Button
                          key={action}
                          loading={pending}
                          disabled={
                            !permissions[permission] ||
                            !personJournalNormal ||
                            !manualJournalReady ||
                            ((action === "submit" || action === "withdraw") &&
                              !personOwnsJournal) ||
                            selected.status !== needed ||
                            pending
                          }
                          onClick={() =>
                            void write(
                              `${scope}/journals/${selected.id}/${action}`,
                              { expectedVersion: selected.version },
                              (result) => setSelected(result as Journal),
                            )
                          }
                        >
                          {label}
                        </Button>
                      ))}
                    <Button
                      disabled={
                        !permissions.create ||
                        pending ||
                        !manualJournalReady ||
                        !personOwnsJournal ||
                        !personJournalNormal ||
                        (personProtocol && selected.status !== "DRAFT")
                      }
                      onClick={() => open("document")}
                    >
                      关联原件登记
                    </Button>
                    {!personProtocol && (
                      <Button
                        disabled={
                          !permissions.approve ||
                          !manualJournalReady ||
                          selected.status !== "SUBMITTED" ||
                          pending
                        }
                        onClick={() => open("reject")}
                      >
                        退回并说明原因
                      </Button>
                    )}
                    <Button
                      danger
                      disabled={
                        !permissions.create ||
                        selected.status !== "DRAFT" ||
                        pending ||
                        !personJournalNormal ||
                        !manualJournalReady ||
                        !personOwnsJournal
                      }
                      onClick={() => open("cancel")}
                    >
                      作废草稿
                    </Button>
                    <Button
                      disabled={
                        !permissions.create ||
                        selected.status !== "POSTED" ||
                        pending ||
                        !manualJournalReady ||
                        personProtocol
                      }
                      onClick={() => open("reversal")}
                    >
                      申请冲销凭证
                    </Button>
                  </Space>
                )}
                {apRaw(selected.type) && (
                  <PayablesJournal
                    journalId={selected.id}
                    rawType={selected.type}
                    props={{
                      active: personMode.ready,
                      canOwner: Boolean(
                        grants.read &&
                        permissions.manage &&
                        permissions.create &&
                        bookRole === "OWNER",
                      ),
                      canMake: Boolean(grants.read && permissions.create),
                      canReview: Boolean(grants.read && permissions.approve),
                      canPost: Boolean(grants.read && permissions.post),
                      restriction: scopeRestriction,
                      onChanged: () => {
                        void queryClient.invalidateQueries({
                          queryKey: ["finance", bookId, yearId],
                        });
                        void openSourceJournal(selected.id);
                      },
                    }}
                  />
                )}
                {v4TypedJournal && (
                  <Alert
                    type="info"
                    title="销售应收 / 资产会计子账原生凭证"
                    description="本凭证由销售应收（AR01/AR02）或资产会计子账（AS01/AS02）原生生产器基于经独立批准的判断或计划自动生成并入账；请前往对应业务页签查看单据链、计划及总账对账明细，不允许沿用人工凭证或损益结转命令。"
                  />
                )}
                {personProtocol &&
                  !apRaw(selected.type) &&
                  !v4TypedJournal &&
                  closingNormalLane(selected.type) && (
                    <>
                      <Alert
                        type="warning"
                        title={journalPersonBlockedReasons.reversal}
                      />
                      <JournalPersonActions
                        key={`${bookId}:${yearId}:${selected.id}:${pageAuthHint}`}
                        scope={scope}
                        journalId={selected.id}
                        legacyVersion={selected.version}
                        ready={personMode.ready}
                        canApprove={Boolean(grants.read && permissions.approve)}
                        canPost={Boolean(permissions.post)}
                        restriction={scopeRestriction}
                        accounts={accounts.data ?? []}
                        onChanged={async () => {
                          await queryClient.invalidateQueries({
                            queryKey: ["finance", bookId, yearId],
                          });
                          await openSourceJournal(selected.id);
                        }}
                        onReadDenied={sourceReadDenied}
                        readable={readable}
                      />
                    </>
                  )}
                {personProtocol &&
                  !apRaw(selected.type) &&
                  !v4TypedJournal &&
                  !closingNormalLane(selected.type) && (
                    <ProfitClosingJournal
                      key={`${scope}:${selected.id}:${pageAuthHint}`}
                      ui={closingUi}
                      journalId={selected.id}
                      rawType={selected.type}
                      onPrepare={prepareClosing}
                    />
                  )}
                {personJournalNormal ? (
                  <>
                    <Typography.Title level={5}>
                      原件字节与索引
                    </Typography.Title>
                    <label>
                      上传原始文件（最多5MiB，保存不代表验真）：
                      <input
                        type="file"
                        disabled={!permissions.create || pending}
                        onChange={(e) => {
                          const file = e.target.files?.[0];
                          if (file) void uploadOriginal(file);
                          e.target.value = "";
                        }}
                      />
                    </label>
                    <Table
                      rowKey="id"
                      dataSource={originals.data ?? []}
                      columns={[
                        { title: "原件", dataIndex: "filename" },
                        { title: "服务端SHA-256", dataIndex: "sha256" },
                        { title: "字节数", dataIndex: "sizeBytes" },
                        {
                          title: "操作",
                          render: (_, row) => (
                            <Button
                              onClick={() =>
                                void downloadOriginal(row.id, row.filename)
                              }
                            >
                              下载原始字节
                            </Button>
                          ),
                        },
                      ]}
                    />
                    <Typography.Title level={5}>凭证关联索引</Typography.Title>
                    <Table
                      rowKey="id"
                      dataSource={documents.data ?? []}
                      columns={[
                        { title: "文件名", dataIndex: "filename" },
                        { title: "SHA-256", dataIndex: "sha256" },
                        { title: "原件位置", dataIndex: "originalLocation" },
                      ]}
                    />
                  </>
                ) : (
                  <Alert
                    type="info"
                    title="本系统结转内容不关联手工原件；依据为真实账簿来源、受控运行及独立批准/记账证明。旧人工凭证命令未开放。"
                  />
                )}
                {error && <Alert type="error" title={error} />}
              </Space>
            )}
          </Modal>
          <Modal
            title={
              {
                book: "新建企业账套",
                year: "新增会计年度",
                account: "新增会计科目",
                member: "账套成员授权",
                journal: "凭证草稿",
                document: "登记原件索引",
                reversal: "创建冲销凭证",
                tax: "登记税务档案",
                closing: "生成期末结转待审草稿",
                reject: "退回凭证",
                cancel: "作废草稿",
              }[dialog ?? "book"]
            }
            open={!!dialog}
            onCancel={() => !pending && setDialog(null)}
            width={dialog === "journal" ? 1000 : 600}
            footer={null}
            destroyOnHidden
            maskClosable={!pending}
          >
            <Form
              form={form}
              layout="vertical"
              onFinish={submit}
              initialValues={{
                standard: "CAS",
                year: 2026,
                normalSide: "DEBIT",
                category: "ASSET",
                role: "READER",
              }}
            >
              {(dialog === "reject" || dialog === "cancel") && (
                <Form.Item name="reason" label="原因" rules={required}>
                  <Input.TextArea maxLength={1000} />
                </Form.Item>
              )}
              {dialog === "closing" && (
                <>
                  <Form.Item name="stage" label="结转步骤" rules={required}>
                    <Select
                      options={[
                        {
                          value: "PROFIT_LOSS",
                          label: "第一步：损益结转至本年利润",
                        },
                        {
                          value: "YEAR_END",
                          label: "第二步：本年利润转留存收益（年末）",
                        },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item
                    name="date"
                    label="会计日期 YYYY-MM-DD"
                    rules={required}
                  >
                    <Input />
                  </Form.Item>
                </>
              )}
              {dialog === "book" && (
                <>
                  <Form.Item name="name" label="账套名称" rules={required}>
                    <Input maxLength={150} />
                  </Form.Item>
                  <Form.Item name="tenantId" label="已有授权租户（留空新建）">
                    <Select
                      allowClear
                      options={Array.from(
                        new Map(
                          (books.data ?? []).map((b) => [
                            b.tenantId,
                            {
                              value: b.tenantId,
                              label: b.tenantName ?? b.tenantId,
                            },
                          ]),
                        ).values(),
                      )}
                      onChange={() =>
                        form.setFieldValue("legalEntityId", undefined)
                      }
                    />
                  </Form.Item>
                  <Form.Item
                    noStyle
                    shouldUpdate={(before, after) =>
                      before.tenantId !== after.tenantId
                    }
                  >
                    {() => (
                      <Form.Item
                        name="legalEntityId"
                        label="已有授权法人（留空新建）"
                      >
                        <Select
                          allowClear
                          options={Array.from(
                            new Map(
                              (books.data ?? [])
                                .filter(
                                  (b) =>
                                    b.tenantId ===
                                    form.getFieldValue("tenantId"),
                                )
                                .map((b) => [
                                  b.legalEntityId,
                                  {
                                    value: b.legalEntityId,
                                    label: b.legalEntityName ?? b.legalEntityId,
                                  },
                                ]),
                            ).values(),
                          )}
                        />
                      </Form.Item>
                    )}
                  </Form.Item>
                  <Form.Item
                    name="legalEntityName"
                    label="法人名称"
                    rules={required}
                  >
                    <Input maxLength={150} />
                  </Form.Item>
                  <Form.Item
                    name="registrationId"
                    label="统一社会信用代码"
                    rules={required}
                  >
                    <Input maxLength={30} />
                  </Form.Item>
                  <Form.Item name="standard" label="会计制度" rules={required}>
                    <Select
                      options={[
                        { value: "CAS", label: "企业会计准则" },
                        { value: "SMALL", label: "小企业会计准则" },
                      ]}
                    />
                  </Form.Item>
                </>
              )}
              {(dialog === "book" || dialog === "year") && (
                <Form.Item
                  name="year"
                  label="公历年度"
                  rules={[
                    ...required,
                    { pattern: /^\d{4}$/, message: "请输入四位公历年度" },
                  ]}
                >
                  <Input inputMode="numeric" />
                </Form.Item>
              )}
              {dialog === "account" && (
                <>
                  <Form.Item name="code" label="科目编码" rules={required}>
                    <Input maxLength={30} />
                  </Form.Item>
                  <Form.Item name="name" label="科目名称" rules={required}>
                    <Input maxLength={100} />
                  </Form.Item>
                  <Form.Item
                    name="normalSide"
                    label="余额方向"
                    rules={required}
                  >
                    <Select
                      options={[
                        { value: "DEBIT", label: "借方" },
                        { value: "CREDIT", label: "贷方" },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item name="category" label="会计要素" rules={required}>
                    <Select
                      options={[
                        { value: "ASSET", label: "资产" },
                        { value: "LIABILITY", label: "负债" },
                        { value: "EQUITY", label: "权益" },
                        { value: "REVENUE", label: "收入" },
                        { value: "EXPENSE", label: "费用" },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item
                    name="purpose"
                    label="结转用途"
                    initialValue="NONE"
                    rules={required}
                  >
                    <Select
                      options={[
                        { value: "NONE", label: "普通科目" },
                        { value: "CURRENT_YEAR_PROFIT", label: "本年利润" },
                        { value: "RETAINED_EARNINGS", label: "留存收益" },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item name="cashFlowCategory" label="现金流活动">
                    <Select
                      allowClear
                      options={[
                        { value: "OPERATING", label: "经营" },
                        { value: "INVESTING", label: "投资" },
                        { value: "FINANCING", label: "筹资" },
                      ]}
                    />
                  </Form.Item>
                </>
              )}
              {dialog === "member" && (
                <>
                  <Form.Item
                    name="username"
                    label="已有平台账号"
                    rules={required}
                  >
                    <Input />
                  </Form.Item>
                  <Form.Item name="role" label="账套岗位" rules={required}>
                    <Select
                      options={[
                        { value: "OWNER", label: "负责人" },
                        { value: "MAKER", label: "制单" },
                        { value: "REVIEWER", label: "审核" },
                        { value: "POSTER", label: "记账" },
                        { value: "READER", label: "只读" },
                      ]}
                    />
                  </Form.Item>
                </>
              )}
              {dialog === "member" && (
                <>
                  <Form.Item
                    name="yearScope"
                    label="年度授权范围"
                    rules={required}
                  >
                    <Select
                      options={[
                        { value: "SELECTED", label: "仅指定年度" },
                        { value: "ALL", label: "全部年度（含未来新增年度）" },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item
                    noStyle
                    shouldUpdate={(before, after) =>
                      before.yearScope !== after.yearScope
                    }
                  >
                    {() =>
                      form.getFieldValue("yearScope") === "SELECTED" ? (
                        <Form.Item
                          name="yearIds"
                          label="允许访问的年度"
                          rules={[
                            {
                              required: true,
                              type: "array",
                              min: 1,
                              message: "至少选择一个年度",
                            },
                          ]}
                        >
                          <Select
                            mode="multiple"
                            options={years.data?.map((year) => ({
                              value: year.id,
                              label: String(year.year),
                            }))}
                          />
                        </Form.Item>
                      ) : (
                        <Alert title="仅在明确需要跨年度授权时选择全部年度。账套岗位与年度范围同时生效。" />
                      )
                    }
                  </Form.Item>
                </>
              )}
              {(dialog === "journal" || dialog === "reversal") && (
                <Form.Item
                  name="date"
                  label="会计日期（YYYY-MM-DD）"
                  rules={[
                    ...required,
                    {
                      pattern: /^\d{4}-\d{2}-\d{2}$/,
                      message: "日期格式应为 YYYY-MM-DD",
                    },
                  ]}
                >
                  <Input placeholder="2026-01-01" />
                </Form.Item>
              )}
              {dialog === "reversal" && (
                <Form.Item name="reason" label="更正原因" rules={required}>
                  <Input.TextArea maxLength={1000} />
                </Form.Item>
              )}
              {dialog === "journal" && (
                <>
                  <Form.Item name="summary" label="凭证摘要" rules={required}>
                    <Input maxLength={500} />
                  </Form.Item>
                  <Typography.Paragraph>
                    人民币提交金额最多2位小数；内部定点精度8位，不自动舍入，每行仅借方或贷方。保存后由独立账号审核。
                  </Typography.Paragraph>
                  {lines.map((line, index) => (
                    <Card key={index} size="small" style={{ marginBottom: 8 }}>
                      <Space wrap>
                        <Select
                          aria-label={`分录${index + 1}科目`}
                          placeholder="科目"
                          style={{ minWidth: 200 }}
                          value={line.accountId || undefined}
                          options={accountOptions}
                          onChange={(value) =>
                            setLines((old) =>
                              old.map((l, i) =>
                                i === index ? { ...l, accountId: value } : l,
                              ),
                            )
                          }
                        />
                        {(["debit", "credit", "memo"] as const).map((field) => (
                          <Input
                            key={field}
                            aria-label={`分录${index + 1}${field}`}
                            placeholder={
                              {
                                debit: "借方金额",
                                credit: "贷方金额",
                                memo: "行摘要",
                              }[field]
                            }
                            value={line[field]}
                            style={{ width: field === "memo" ? 180 : 140 }}
                            onChange={(e) => {
                              const value = e.target.value;
                              setLines((old) =>
                                old.map((l, i) =>
                                  i === index ? { ...l, [field]: value } : l,
                                ),
                              );
                            }}
                          />
                        ))}
                        <Button
                          danger
                          disabled={lines.length <= 2}
                          onClick={() =>
                            setLines((old) => old.filter((_, i) => i !== index))
                          }
                        >
                          移除
                        </Button>
                      </Space>
                    </Card>
                  ))}
                  <Button
                    onClick={() => setLines((old) => [...old, blankLine()])}
                  >
                    增加分录
                  </Button>
                  <Typography.Paragraph>
                    {(() => {
                      try {
                        return `借方合计 ${amount(lines.reduce((s, l) => s + units(l.debit), 0n))} / 贷方合计 ${amount(lines.reduce((s, l) => s + units(l.credit), 0n))}`;
                      } catch {
                        return "请填写有效金额";
                      }
                    })()}
                  </Typography.Paragraph>
                </>
              )}
              {dialog === "document" && (
                <Form.Item
                  name="originalId"
                  label="已上传原件或纸质扫描件"
                  rules={required}
                >
                  <Select
                    allowClear
                    options={originals.data?.map((o) => ({
                      value: o.id,
                      label: `${o.filename} · ${o.sha256}`,
                    }))}
                    onChange={(id) => {
                      const original = originals.data?.find((o) => o.id === id);
                      if (original)
                        form.setFieldsValue({
                          filename: original.filename,
                          sha256: original.sha256,
                          source: "ELECTRONIC_ORIGINAL",
                          originalLocation: `original:${id}`,
                        });
                    }}
                  />
                </Form.Item>
              )}
              {dialog === "document" && (
                <>
                  <Alert title="必须关联已上传字节。纸质凭证请先上传扫描件，选择纸质扫描件来源并登记实体原件保管位置。SOURCE_UNVERIFIED 不代表验真或验签。" />
                  <Form.Item name="source" label="凭证来源" rules={required}>
                    <Select
                      options={[
                        { value: "ELECTRONIC_ORIGINAL", label: "电子原件" },
                        { value: "PAPER_SCAN", label: "纸质凭证扫描件" },
                      ]}
                    />
                  </Form.Item>
                  {[
                    ["filename", "服务端文件名"],
                    ["sha256", "原件 SHA-256"],
                    ["originalLocation", "原件保管位置"],
                  ].map(([name, label]) => (
                    <Form.Item
                      key={name}
                      name={name}
                      label={label}
                      rules={
                        name === "sha256"
                          ? [
                              ...required,
                              {
                                pattern: /^[a-fA-F0-9]{64}$/,
                                message: "须为64位SHA-256",
                              },
                            ]
                          : required
                      }
                    >
                      <Input
                        readOnly={name === "filename" || name === "sha256"}
                      />
                    </Form.Item>
                  ))}
                </>
              )}
              {dialog === "tax" && (
                <>
                  <Alert title="登记后为 UNREVIEWED，不能直接用于正式申报或优惠计算。" />
                  {[
                    ["vatIdentity", "增值税身份"],
                    ["incomeTaxMethod", "所得税征收方式"],
                    ["region", "主管地区"],
                    ["notes", "政策依据及说明"],
                  ].map(([name, label]) => (
                    <Form.Item
                      key={name}
                      name={name}
                      label={label}
                      rules={required}
                    >
                      <Input />
                    </Form.Item>
                  ))}
                </>
              )}
              {error && (
                <Alert
                  type="error"
                  title={error}
                  style={{ marginBottom: 12 }}
                />
              )}
              <Button
                type="primary"
                htmlType="submit"
                loading={pending}
                disabled={pending}
              >
                保存
              </Button>
            </Form>
          </Modal>
        </Space>
      </ProfitClosingBoundary>
    </PayablesBoundary>
  );
}
