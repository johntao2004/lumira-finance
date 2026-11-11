import { useEffect, useState } from "react";
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
  Typography,
} from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import type {
  AllocationDetail,
  AllocationPage,
  AllocationSummary,
  DocumentDetail,
  DocumentSummary,
  ReverseDetail,
} from "./evidence-usage-contract";
import {
  evidenceActionAllowed,
  evidenceAllocationMatches,
  evidenceCnyEditable,
  evidenceIndependent,
  evidenceOwns,
  evidencePositive,
  evidencePreview,
  evidenceText,
} from "./evidence-usage-rules";
import { useEvidenceResource } from "./use-evidence-usage";
import {
  EvidenceBasisView,
  EvidenceBlockers,
  EvidenceOriginalPicker,
  EvidenceOriginalView,
  EvidenceReason,
  type EvidenceUiProps,
} from "./evidence-usage-ui";
const required = [{ required: true, message: "请填写此项" }];
const allocationStates = {
  DRAFT: "拟费用归属草稿（不占额）",
  SUBMITTED: "已提交待独立确认",
  CONFIRMED: "费用归属已确认（不是付款或入账）",
  CANCELLED: "已终结未确认份额",
};
const usageStates = {
  RESERVED: "真实未确认预留",
  COMMITTED: "已确认占用",
  RELEASED: "未确认预留已释放",
};
const actionLabels: Record<string, string> = {
  "edit-allocation": "编辑原账号草稿并明确当前依据",
  submit: "提交并预留费用额度",
  confirm: "独立确认费用归属",
  withdraw: "原账号撤回并释放预留",
  reject: "独立驳回并释放预留",
  "admin-terminate": "独立行政终结未确认份额",
  cancel: "原账号取消草稿",
  "request-reverse": "提出已确认净份额更正",
  "approve-reverse": "独立批准部分净份额更正",
  "reject-reverse": "独立驳回更正",
  "cancel-reverse": "原申请账号取消更正",
};
export function ExpenseAllocationPanel({
  ui,
  documentId,
  selectedId,
  onSelected: setSelectedId,
}: {
  ui: EvidenceUiProps;
  documentId: string;
  selectedId: string;
  onSelected: (id: string) => void;
}) {
  const base = `${ui.scope}/evidence-usage/allocations`;
  const [cursor, setCursor] = useState<string | null>(null),
    [limit, setLimit] = useState(20),
    [reverseOverride, setReverseOverride] = useState("");
  useEffect(() => {
    setCursor(null);
    setReverseOverride("");
  }, [documentId]);
  useEffect(() => {
    setCursor(null);
    setReverseOverride("");
  }, [ui.revision]);
  const page = useEvidenceResource<AllocationPage>(
    `${base}?limit=${limit}${documentId ? `&documentId=${documentId}` : ""}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
    ui.active,
    ui.revision,
    ui.onReadDenied,
    ui.onReadFailure,
    ui.readable,
  );
  const detail = useEvidenceResource<AllocationDetail>(
    selectedId ? `${base}/${selectedId}` : "",
    ui.active && !!selectedId,
    ui.revision,
    ui.onReadDenied,
    ui.onReadFailure,
    ui.readable,
  );
  const allocation = evidenceAllocationMatches(
    selectedId,
    documentId,
    detail.data?.allocation,
  )
    ? detail.data?.allocation
    : undefined;
  const selectedDocumentId = documentId || allocation?.documentId || "";
  const document = useEvidenceResource<DocumentDetail>(
    selectedDocumentId
      ? `${ui.scope}/evidence-usage/documents/${selectedDocumentId}`
      : "",
    ui.active && !!selectedDocumentId,
    ui.revision,
    ui.onReadDenied,
    ui.onReadFailure,
    ui.readable,
  );
  const reverseId = allocation
    ? reverseOverride ||
      allocation.pendingReverseRequestId ||
      allocation.latestReverseRequestId ||
      ""
    : "";
  const reverse = useEvidenceResource<ReverseDetail>(
    selectedId && reverseId
      ? `${base}/${selectedId}/reverse-requests/${reverseId}`
      : "",
    ui.active && !!selectedId && !!reverseId,
    ui.revision,
    ui.onReadDenied,
    ui.onReadFailure,
    ui.readable,
  );
  const [form] = Form.useForm();
  const proposed = Form.useWatch("amount", form);
  const [modal, setModal] = useState<{
      action: string;
      allocation: AllocationSummary | null;
      reverse: ReverseDetail | null;
      document: DocumentSummary | null;
    } | null>(null),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const busy = page.writing || detail.writing || reverse.writing;
  function allowed(action: string) {
    if (!ui.active) return false;
    if (ui.actor.identityAvailability !== "AVAILABLE") return false;
    if (action === "create-allocation")
      return (
        evidenceActionAllowed(
          document.data?.document.allowedActions ?? [],
          action,
          ui.newUseAllowed,
          ui.canMake,
        ) &&
        !!documentId &&
        !!document.data?.document.currentBasis
      );
    const isReverseDecision = [
      "approve-reverse",
      "reject-reverse",
      "cancel-reverse",
    ].includes(action);
    const actions = isReverseDecision
      ? reverse.data?.allowedActions
      : allocation?.allowedActions;
    const permission =
      action === "request-reverse" || action === "cancel-reverse"
        ? ui.canOwner
        : [
              "confirm",
              "reject",
              "admin-terminate",
              "approve-reverse",
              "reject-reverse",
            ].includes(action)
          ? ui.canReview
          : ui.canMake;
    if (
      !evidenceActionAllowed(
        actions ?? [],
        action,
        ui.newUseAllowed,
        permission,
      )
    )
      return false;
    if (
      (action === "submit" || action === "confirm") &&
      document.data?.document.newUseFrozen
    )
      return false;
    if (isReverseDecision && reverse.data)
      return action === "cancel-reverse"
        ? evidenceOwns(ui.actor, reverse.data.requestedBy)
        : evidenceIndependent(ui.actor, reverse.data.requestedBy);
    if (!allocation) return false;
    if (["edit-allocation", "submit", "withdraw", "cancel"].includes(action))
      return evidenceOwns(ui.actor, allocation.preparedBy);
    if (["confirm", "reject", "admin-terminate"].includes(action))
      return (
        evidenceIndependent(ui.actor, allocation.preparedBy) &&
        evidenceIndependent(ui.actor, allocation.lastEditor)
      );
    return true;
  }
  function open(action: string) {
    form.resetFields();
    setError("");
    if (action === "edit-allocation" && allocation)
      form.setFieldsValue({
        amount: evidenceCnyEditable(allocation.amount),
        occurredDate: allocation.occurredDate,
        description: allocation.description,
        purposeEvidenceOriginalId: allocation.purposeEvidence.originalId,
      });
    setModal({
      action,
      allocation: allocation ?? null,
      reverse: reverse.data,
      document: document.data?.document ?? null,
    });
  }
  async function submit(values: Record<string, unknown>) {
    if (!modal || !ui.active || busy) return;
    const action = modal.action;
    if (!allowed(action)) return;
    try {
      let path = base,
        method: "POST" | "PATCH" = "POST",
        payload: Record<string, unknown>;
      if (action === "create-allocation" || action === "edit-allocation") {
        const basis = document.data?.document.currentBasis;
        if (
          !basis ||
          !modal.document ||
          modal.document.id !== document.data?.document.id ||
          modal.document.currentBasis?.basisVersion !== basis.basisVersion
        ) {
          setError("单据依据版本已变化，请关闭表单并重新读取后明确选择。");
          return;
        }
        const fields = {
          expectedBasisVersion: basis.basisVersion,
          occurredDate: evidenceText(values, "occurredDate", 10),
          amount: evidencePositive(values.amount),
          purposeEvidenceOriginalId: evidenceText(
            values,
            "purposeEvidenceOriginalId",
            36,
          ),
          description: evidenceText(values, "description", 1000),
        };
        if (action === "create-allocation")
          payload = {
            schemaVersion: "evidence-usage/1",
            documentId: modal.document.id,
            sourceSystem: "ORG_MANUAL_EXPENSE_V1",
            businessChainKey: evidenceText(values, "businessChainKey", 128),
            sourceOperationKey: evidenceText(values, "sourceOperationKey", 128),
            purpose: "EXPENSE_ALLOCATION",
            ...fields,
          };
        else {
          if (
            !modal.allocation ||
            allocation?.version !== modal.allocation.version ||
            allocation.contentDigest !== modal.allocation.contentDigest
          )
            return;
          path += `/${modal.allocation.id}`;
          method = "PATCH";
          payload = {
            schemaVersion: "evidence-usage/1",
            expectedVersion: modal.allocation.version,
            contentDigest: modal.allocation.contentDigest,
            ...fields,
          };
        }
      } else if (action === "request-reverse") {
        const frozen = modal.allocation;
        if (
          !frozen ||
          frozen.id !== allocation?.id ||
          frozen.version !== allocation.version ||
          frozen.contentDigest !== allocation.contentDigest
        ) {
          setError("原份额版本已变化，请重新读取后准备更正。");
          return;
        }
        path += `/${frozen.id}/reverse-requests`;
        payload = {
          schemaVersion: "evidence-usage/1",
          expectedAllocationVersion: frozen.version,
          allocationContentDigest: frozen.contentDigest,
          amount: evidencePositive(values.amount, "拟更正金额"),
          basisOriginalId: evidenceText(values, "basisOriginalId", 36),
          reason: evidenceText(values, "reason", 1000),
          scopeOfEffect:
            "EVIDENCE_CAPACITY_ONLY_NOT_BANK_REFUND_OR_GL_CORRECTION",
        };
      } else if (
        ["approve-reverse", "reject-reverse", "cancel-reverse"].includes(action)
      ) {
        const frozen = modal.reverse;
        if (
          !frozen ||
          frozen.id !== reverse.data?.id ||
          frozen.version !== reverse.data.version ||
          frozen.contentDigest !== reverse.data.contentDigest
        )
          return;
        path += `/${selectedId}/reverse-requests/${frozen.id}/${action.split("-")[0]}`;
        payload = {
          schemaVersion: "evidence-usage/1",
          expectedVersion: frozen.version,
          contentDigest: frozen.contentDigest,
          reason: evidenceText(values, "reason", 1000),
        };
      } else {
        const frozen = modal.allocation;
        if (
          !frozen ||
          frozen.id !== allocation?.id ||
          frozen.version !== allocation.version ||
          frozen.contentDigest !== allocation.contentDigest
        ) {
          setError("份额内容已变化，请重新读取。");
          return;
        }
        path += `/${frozen.id}/${action}`;
        payload = {
          schemaVersion: "evidence-usage/1",
          expectedVersion: frozen.version,
          contentDigest: frozen.contentDigest,
          reason: evidenceText(values, "reason", 1000),
        };
      }
      const writer =
        action === "create-allocation"
          ? page
          : ["approve-reverse", "reject-reverse", "cancel-reverse"].includes(
                action,
              )
            ? reverse
            : detail;
      const result = await writer.write<AllocationDetail | ReverseDetail>(
        path,
        payload,
        method,
      );
      if (!result) return;
      setModal(null);
      form.resetFields();
      setNotice(
        "份额处理已返回，请以重新读取后的当前预留、已确认净额与更正状态为准。不是付款、报销或入账结果。",
      );
      if ("allocation" in result) setSelectedId(result.allocation.id);
      setReverseOverride("");
      ui.onChanged();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "请核对提交内容。");
    }
  }
  const commandError =
    error || page.error || detail.error || document.error || reverse.error;
  const bucket = document.data?.document.buckets.find(
    (item) => item.purpose === "EXPENSE_ALLOCATION",
  );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Title level={5}>真实费用归属份额</Typography.Title>
      <Alert
        type="info"
        title="仅费用归属，不等于已报销、付款或会计入账"
        description="草稿不占额；提交才预留，独立确认后占用。未确认退出释放预留，已确认仅通过独立部分净份额更正；本片不执行银行退款或总账冲销。"
      />
      {commandError && <Alert type="error" title={commandError} />}
      {notice && <Alert type="success" title={notice} />}
      <Space wrap>
        <Button
          type="primary"
          disabled={busy || !allowed("create-allocation")}
          onClick={() => open("create-allocation")}
        >
          为所选单据准备费用归属
        </Button>
        <Select
          aria-label="费用份额每页条数"
          value={limit}
          disabled={busy || page.loading}
          options={[20, 50, 100].map((value) => ({
            value,
            label: `每页${value}条`,
          }))}
          onChange={(value) => {
            setLimit(value);
            setCursor(null);
            setSelectedId("");
          }}
        />
        <Button
          disabled={busy || page.loading}
          onClick={() => {
            if (cursor) setCursor(null);
            else void page.read();
            setSelectedId("");
          }}
        >
          份额首屏
        </Button>
        <Button
          disabled={busy || page.loading || !page.data?.nextCursor}
          onClick={() => {
            setCursor(page.data?.nextCursor ?? null);
            setSelectedId("");
          }}
        >
          份额下一页
        </Button>
      </Space>
      {!documentId && (
        <Typography.Text type="secondary">
          创建份额前请在上方选择真实单据。当前列表显示本年度允许读取的费用份额。
        </Typography.Text>
      )}
      <Table
        rowKey="id"
        size="small"
        pagination={false}
        loading={page.loading}
        dataSource={page.data?.items ?? []}
        scroll={{ x: 1300 }}
        locale={{
          emptyText: page.data ? "当前页无份额" : "未读取成功，不代表零条",
        }}
        columns={[
          {
            title: "真实业务链 / 稳定操作",
            width: 300,
            render: (_, row) => (
              <>
                {row.businessChainKey}
                <br />
                {row.sourceOperationKey}
              </>
            ),
          },
          {
            title: "状态",
            width: 250,
            render: (_, row) => allocationStates[row.status],
          },
          {
            title: "原份额 / 已更正 / 当前确认净额",
            width: 310,
            render: (_, row) =>
              `${row.amount} / ${row.reversedAmount} / ${row.netConfirmedAmount}`,
          },
          {
            title: "预留状态",
            width: 200,
            render: (_, row) =>
              row.reservationState
                ? usageStates[row.reservationState]
                : "无当前预留，草稿不占额",
          },
          {
            title: "操作",
            width: 150,
            render: (_, row) => (
              <Button
                disabled={busy}
                onClick={() => {
                  setSelectedId(row.id);
                  setReverseOverride("");
                }}
              >
                查看具体份额
              </Button>
            ),
          },
        ]}
      />
      {allocation && detail.data && (
        <>
          <Descriptions
            bordered
            size="small"
            column={1}
            items={[
              {
                key: "id",
                label: "份额 / 单据",
                children: `${allocation.id} / ${allocation.documentId}`,
              },
              {
                key: "chain",
                label: "真实归属链 / 稳定操作",
                children: `${allocation.businessChainId} / ${allocation.businessChainKey} / ${allocation.sourceOperationKey}`,
              },
              {
                key: "status",
                label: "业务状态 / 预留状态",
                children: `${allocationStates[allocation.status]} / ${allocation.reservationState ? usageStates[allocation.reservationState] : "无当前预留"}`,
              },
              {
                key: "amount",
                label: "原金额 / 累计更正 / 当前确认净额",
                children: `${allocation.amount} / ${allocation.reversedAmount} / ${allocation.netConfirmedAmount}`,
              },
              {
                key: "description",
                label: "具体用途 / 发生日期",
                children: `${allocation.description} / ${allocation.occurredDate}`,
              },
              {
                key: "maker",
                label: "原制备 / 最后编辑账号及人员",
                children: `${allocation.preparedBy.accountId} / ${allocation.preparedBy.personId}；${allocation.lastEditor.accountId} / ${allocation.lastEditor.personId}`,
              },
              {
                key: "versions",
                label: "流程版本 / 内容版本 / 摘要",
                children: (
                  <Typography.Text style={{ overflowWrap: "anywhere" }}>
                    {allocation.version} / {allocation.contentVersion} /{" "}
                    {allocation.contentDigest}
                  </Typography.Text>
                ),
              },
              {
                key: "confirm",
                label: "真实确认决定",
                children: allocation.confirmedBy
                  ? `${allocation.confirmationId} / ${allocation.confirmedBy.accountId} / ${allocation.confirmedBy.personId} / ${allocation.confirmedAt}`
                  : "尚无确认决定",
              },
              {
                key: "expiry",
                label: "本次预留 / 截止时间",
                children: `${allocation.submissionId ?? "无"} / ${allocation.reservationExpiresAt ?? "无"}`,
              },
            ]}
          />
          <EvidenceBlockers items={allocation.blockers} />
          <Typography.Text strong>
            本份额冻结的原组织依据（不会悄改为新版本）
          </Typography.Text>
          <EvidenceBasisView basis={allocation.basis} ui={ui} />
          <Typography.Text strong>实际用途证据</Typography.Text>
          <EvidenceOriginalView original={allocation.purposeEvidence} ui={ui} />
          {detail.data.latestTermination && (
            <Alert
              type="warning"
              title={`最近未确认退出：${{ WITHDRAW: "原账号撤回", REJECT: "独立驳回", ADMIN_TERMINATE: "独立行政终结", CANCEL: "原账号取消" }[detail.data.latestTermination.action]}`}
              description={`${detail.data.latestTermination.actor.accountId} / ${detail.data.latestTermination.actor.personId} / ${detail.data.latestTermination.decidedAt}；${detail.data.latestTermination.reason}。保留原制备人，不冒充付款退款。`}
            />
          )}
          <Space wrap>
            {[
              "edit-allocation",
              "submit",
              "confirm",
              "withdraw",
              "reject",
              "admin-terminate",
              "cancel",
              "request-reverse",
            ].map((action) => (
              <Button
                key={action}
                disabled={busy || !allowed(action)}
                onClick={() => open(action)}
              >
                {actionLabels[action]}
              </Button>
            ))}
            <Button
              disabled={busy || detail.loading}
              onClick={() => void detail.read()}
            >
              重新读取当前份额
            </Button>
            <Button
              disabled={busy || !allocation.latestReverseRequestId}
              onClick={() => {
                const target =
                  allocation.pendingReverseRequestId ||
                  allocation.latestReverseRequestId ||
                  "";
                if (target === reverseId) void reverse.read();
                else setReverseOverride(target);
              }}
            >
              查看最近或待审更正
            </Button>
          </Space>
          <Collapse
            items={[
              {
                key: "allocation",
                label: "份额与真实消费完整快照",
                children: (
                  <pre
                    style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
                  >
                    {JSON.stringify(detail.data, null, 2)}
                  </pre>
                ),
              },
            ]}
          />
        </>
      )}
      {reverse.data && (
        <>
          <Typography.Title level={5}>
            已确认份额更正 · {reverse.data.id}
          </Typography.Title>
          <Alert
            type="warning"
            title="仅调整费用归属净份额，不是银行退款、付款撤销或总账冲销"
          />
          <Descriptions
            bordered
            size="small"
            column={1}
            items={[
              {
                key: "original",
                label: "原确认 / 原实际占用 / 原份额",
                children: `${reverse.data.originalConfirmationId} / ${reverse.data.originalCommittedUsageId} / ${reverse.data.allocationId}`,
              },
              {
                key: "amount",
                label: "本次拟减少净份额",
                children: reverse.data.amount,
              },
              {
                key: "reason",
                label: "具体更正依据",
                children: reverse.data.reason,
              },
              {
                key: "requester",
                label: "申请账号 / 人员",
                children: `${reverse.data.requestedBy.accountId} / ${reverse.data.requestedBy.personId}`,
              },
              {
                key: "reviewer",
                label: "真实决定账号 / 人员",
                children: reverse.data.reviewedBy
                  ? `${reverse.data.reviewedBy.accountId} / ${reverse.data.reviewedBy.personId} / ${reverse.data.reviewedAt} / ${reverse.data.decisionReason}`
                  : "尚无决定",
              },
              {
                key: "status",
                label: "更正决定状态",
                children: {
                  PENDING: "待独立批准",
                  APPROVED: "已实际减少净份额（原确认历史保留）",
                  REJECTED: "已驳回，未更正",
                  CANCELLED: "原账号已取消，未更正",
                }[reverse.data.status],
              },
              {
                key: "version",
                label: "版本 / 摘要 / 期限",
                children: (
                  <Typography.Text style={{ overflowWrap: "anywhere" }}>
                    {reverse.data.version} / {reverse.data.contentDigest} /{" "}
                    {reverse.data.expiresAt}
                  </Typography.Text>
                ),
              },
            ]}
          />
          <EvidenceOriginalView original={reverse.data.basisOriginal} ui={ui} />
          <EvidenceBlockers items={reverse.data.blockers} />
          <Space wrap>
            {["approve-reverse", "reject-reverse", "cancel-reverse"].map(
              (action) => (
                <Button
                  key={action}
                  disabled={busy || !allowed(action)}
                  onClick={() => open(action)}
                >
                  {actionLabels[action]}
                </Button>
              ),
            )}
            <Button
              disabled={busy || !reverse.data.previousReverseRequestId}
              onClick={() =>
                setReverseOverride(reverse.data!.previousReverseRequestId ?? "")
              }
            >
              查看上一笔更正历史
            </Button>
          </Space>
          <Collapse
            items={[
              {
                key: "reverse",
                label: "不可变更正完整快照",
                children: (
                  <pre
                    style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
                  >
                    {JSON.stringify(reverse.data, null, 2)}
                  </pre>
                ),
              },
            ]}
          />
        </>
      )}
      <Modal
        open={!!modal && ui.active}
        width={760}
        title={
          modal?.action === "create-allocation"
            ? "准备真实费用归属（不占额度）"
            : (actionLabels[modal?.action ?? ""] ?? "费用份额处理")
        }
        onCancel={() => {
          if (!busy) setModal(null);
        }}
        onOk={() => form.submit()}
        confirmLoading={busy}
        okButtonProps={{
          disabled: !ui.active || !modal || !allowed(modal.action),
        }}
        destroyOnHidden
      >
        {commandError && <Alert type="error" title={commandError} />}
        <Form form={form} layout="vertical" onFinish={submit}>
          {(modal?.action === "create-allocation" ||
            modal?.action === "edit-allocation") && (
            <>
              {modal.action === "create-allocation" && (
                <>
                  <Form.Item
                    name="businessChainKey"
                    label="真实费用归属事项链标识（不是报销/付款流水，不要随机生成）"
                    rules={required}
                  >
                    <Input maxLength={128} />
                  </Form.Item>
                  <Form.Item
                    name="sourceOperationKey"
                    label="本次真实稳定操作标识（重复投递必须保持相同）"
                    rules={required}
                  >
                    <Input maxLength={128} />
                  </Form.Item>
                </>
              )}
              <Typography.Paragraph>
                明确绑定当前已读取组织依据版本{" "}
                {modal.document?.currentBasis?.basisVersion ?? "未知"}
                ；不会自动改写原确认历史。
              </Typography.Paragraph>
              <Form.Item
                name="occurredDate"
                label="真实发生日期（YYYY-MM-DD）"
                rules={required}
              >
                <Input placeholder="2028-01-01" />
              </Form.Item>
              <Form.Item
                name="amount"
                label="本份额正人民币金额（最多2位）"
                rules={required}
              >
                <Input />
              </Form.Item>
              <Typography.Paragraph>
                当前费用可用额：{bucket?.available ?? "未知"}；拟占后估算：
                {evidencePreview(bucket?.available ?? null, proposed)}
                。草稿不实际占额，提交时由服务端重验。
              </Typography.Paragraph>
              <Form.Item
                name="purposeEvidenceOriginalId"
                label="实际费用用途证据原件"
                rules={required}
              >
                <EvidenceOriginalPicker ui={ui} />
              </Form.Item>
              <Form.Item
                name="description"
                label="具体费用归属说明（不声称已付款或入账）"
                rules={required}
              >
                <Input.TextArea rows={4} maxLength={1000} />
              </Form.Item>
            </>
          )}
          {modal?.action === "request-reverse" && (
            <>
              <Typography.Paragraph>
                原确认份额 {modal.allocation?.id}；当前可更正净额{" "}
                {modal.allocation?.netConfirmedAmount}
                。独立批准后仅减少费用净份额，不退款或冲销总账。
              </Typography.Paragraph>
              <Form.Item
                name="amount"
                label="拟减少的正人民币金额（最多2位）"
                rules={required}
              >
                <Input />
              </Form.Item>
              <Form.Item
                name="basisOriginalId"
                label="实际更正依据原件"
                rules={required}
              >
                <EvidenceOriginalPicker ui={ui} />
              </Form.Item>
            </>
          )}
          {modal &&
            !["create-allocation", "edit-allocation"].includes(
              modal.action,
            ) && <EvidenceReason />}
        </Form>
      </Modal>
    </Space>
  );
}
