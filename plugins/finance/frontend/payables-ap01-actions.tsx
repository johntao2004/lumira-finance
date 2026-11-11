import { useRef, useState } from "react";
import { Alert, Button, Checkbox, Input, Modal, Select, Space } from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import type {
  BasisDetail,
  BasisCommandResult,
  EligibleParent,
  PayableDetail,
  PayableCommandResult,
  SupplierExpectation,
} from "./payables-ap01-contract";
import type { ApUi } from "./payables-ap01-ui";
import { useApContext, useApResource } from "./payables-ap01-resource";
import { ApFacts, ApJson, ApOriginalPicker } from "./payables-ap01-views";
import {
  apAction,
  apApprove,
  apBasisAction,
  apBasisCreate,
  apCreate,
  apDraft,
  apPost,
  apReversal,
  apSubmit,
} from "./payables-ap01-rules";
type Kind =
  | "basis-create"
  | "basis-approve"
  | "basis-reject"
  | "basis-cancel"
  | "create"
  | "full-reversal"
  | "submit"
  | "approve"
  | "post"
  | "withdraw"
  | "reject"
  | "cancel"
  | "admin-terminate";
const labels: Record<Kind, string> = {
  "basis-create": "提出独立会计额度复核",
  "basis-approve": "独立批准会计额度",
  "basis-reject": "独立驳回额度提案",
  "basis-cancel": "原账号取消额度提案",
  create: "准备真实应付会计确认草稿",
  "full-reversal": "准备原日全额会计反向",
  submit: "提交并预留会计额度",
  approve: "独立批准冻结内容",
  post: "真实记账并建立应付未结项",
  withdraw: "原账号撤回提交并释放预留",
  reject: "独立退回草稿并释放预留",
  cancel: "原账号取消草稿",
  "admin-terminate": "独立行政终结草稿",
};
type Seed = {
  parent?: EligibleParent;
  basis?: BasisDetail;
  payable?: PayableDetail;
  supplier?: SupplierExpectation;
};
type Draft = {
  identity: string;
  operationId: string;
  values: Record<string, unknown>;
  seed: Seed;
};
export function ApCommand({
  kind,
  ui,
  seed,
  enabled,
  onResult,
}: {
  kind: Kind;
  ui: ApUi;
  seed: Seed;
  enabled: boolean;
  onResult: (value: BasisCommandResult | PayableCommandResult) => void;
}) {
  const c = useApContext(),
    r = useApResource<null>("", true),
    [open, setOpen] = useState(false),
    [error, setError] = useState(""),
    [form] = Form.useForm<Record<string, unknown>>(),
    draftRef = useRef<Draft | null>(null),
    live = useRef({ identity: c.identity, ready: c.ready });
  live.current = { identity: c.identity, ready: c.ready };
  const target =
      seed.parent?.parent.allocationId ??
      seed.basis?.basis.id ??
      seed.payable?.payable.id ??
      "",
    key = JSON.stringify([c.identity, kind, target]);
  const visible = open && draftRef.current?.identity === c.identity && c.ready;
  function start() {
    if (!enabled || !c.ready) return;
    const d = apDraft(c.drafts, key, () => ({
      identity: c.identity,
      operationId: crypto.randomUUID(),
      values: {
        requestedExpiresAt: null,
        ordinaryServiceExpenseConfirmed: false,
        supplierMatchesDocumentConfirmed: false,
        serviceAcceptedConfirmed: false,
        noTaxAssetInventoryPayrollConfirmed: false,
        payableAccountUseConfirmed: false,
        organizationReviewedNotTaxVerified: false,
      },
      seed,
    })) as Draft;
    draftRef.current = d;
    form.resetFields();
    form.setFieldsValue(d.values);
    setError("");
    setOpen(true);
  }
  async function submit() {
    const d = draftRef.current;
    if (!d || d.identity !== c.identity || !c.ready || r.writing) return;
    try {
      const v = await form.validateFields();
      if (live.current.identity !== d.identity || !live.current.ready) return;
      d.values = v;
      const s = d.seed;
      let path = `${c.scope}/payables-ap01`,
        payload: Record<string, unknown>;
      if (kind === "basis-create") {
        if (!s.parent) throw new Error("缺少真实父份额。");
        path += "/accounting-bases";
        payload = apBasisCreate(v, s.parent);
      } else if (kind.startsWith("basis-")) {
        if (!s.basis) throw new Error("缺少冻结会计额度提案。");
        path += `/accounting-bases/${s.basis.basis.id}/${kind.slice(6)}`;
        payload = apBasisAction(v, s.basis);
      } else if (kind === "create") {
        if (!s.parent || !s.supplier)
          throw new Error("请先读取实际父费用份额与供应商发布版本。");
        path += "/expense-payables";
        payload = apCreate(v, s.parent, s.supplier, d.operationId);
      } else {
        if (!s.payable) throw new Error("缺少真实应付快照。");
        path += `/expense-payables/${s.payable.payable.id}/${kind}`;
        payload =
          kind === "full-reversal"
            ? apReversal(v, s.payable, d.operationId)
            : kind === "submit"
              ? apSubmit(v, s.payable)
              : kind === "approve"
                ? apApprove(v, s.payable)
                : kind === "post"
                  ? apPost(v, s.payable)
                  : apAction(v, s.payable);
      }
      const result = await r.write<BasisCommandResult | PayableCommandResult>(
        path,
        payload,
      );
      if (result && live.current.identity === d.identity) {
        c.drafts.delete(key);
        setOpen(false);
        onResult(result);
        ui.changed();
      }
    } catch (e) {
      if (live.current.identity === d.identity) setError(c.readable(e));
    }
  }
  const d = draftRef.current,
    full = kind === "create";
  return (
    <>
      <Button disabled={!enabled || !c.ready || r.writing} onClick={start}>
        {labels[kind]}
      </Button>
      <Modal
        title={labels[kind]}
        open={visible}
        width={820}
        onCancel={() => {
          if (!r.writing) setOpen(false);
        }}
        onOk={() => void submit()}
        confirmLoading={r.writing}
        okButtonProps={{ disabled: !c.ready || !enabled }}
      >
        <Alert
          type="info"
          title="命令冻结基准，不代表刷新后的当前状态。关闭、刷新或重试不会悄改正文、版本、期限、操作ID或请求键。结果未知请显式读取权威状态，不假称已回滚。"
        />
        {d?.seed.parent && (
          <ApFacts
            title="本次命令的冻结准备基准（不是刷新后的当前数据）"
            rows={[
              [
                "父份额 / 全额",
                `${d.seed.parent.parent.allocationId} / ${d.seed.parent.parent.netConfirmedAmount}`,
              ],
              [
                "法律单据 / 冻结依据",
                `${d.seed.parent.parent.documentId} / ${d.seed.parent.parent.documentExpectation.currentReviewId}`,
              ],
              [
                "会计依据 / 前驱",
                `${d.seed.parent.capacity.currentBasis?.basisId ?? "尚无"} / ${d.seed.parent.sourceSlot.latestAttemptId ?? "首个尝试"}`,
              ],
              [
                "冻结供应商 / revision",
                d.seed.supplier
                  ? `${d.seed.supplier.counterpartyId} / ${d.seed.supplier.approvedRevisionId}`
                  : "本动作不选择供应商",
              ],
            ]}
          />
        )}
        {d?.seed.payable && (
          <>
            <ApFacts
              title="本次命令的冻结内容"
              rows={[
                [
                  "应付对象 / 原制备人",
                  `${d.seed.payable.payable.id} / ${d.seed.payable.payable.maker.accountId} / ${d.seed.payable.payable.maker.personId}`,
                ],
                [
                  "类型 / 金额 / 会计日",
                  `${d.seed.payable.payable.producerKind} / ${d.seed.payable.content.amount} / ${d.seed.payable.content.accountingDate}`,
                ],
                [
                  "供应商 / 摘要",
                  `${d.seed.payable.content.supplier.displayName} / ${d.seed.payable.content.summary}`,
                ],
                [
                  "版本 / 内容digest",
                  `${d.seed.payable.payable.version} / ${d.seed.payable.payable.contentDigest}`,
                ],
              ]}
            />
            <ApJson
              value={d.seed.payable.content}
              label="可读完整冻结分录、证据及来源声明"
            />
          </>
        )}
        {error && <Alert type="error" title={error} />}{" "}
        {r.error && (
          <Alert
            type="error"
            title={`${r.error} 原正文及请求键保留，请读取当前状态后显式重试。`}
          />
        )}
        <Button
          disabled={r.writing}
          onClick={() => {
            c.drafts.delete(key);
            draftRef.current = null;
            setOpen(false);
            form.resetFields();
          }}
        >
          放弃本地正文，按当前读取重新填写（不撤销已发命令）
        </Button>
        <Form
          form={form}
          layout="vertical"
          onValuesChange={(_, values) => {
            if (draftRef.current) draftRef.current.values = values;
          }}
        >
          {kind === "basis-create" && (
            <>
              <Alert
                title={`真实父净份额 ${d?.seed.parent?.parent.netConfirmedAmount ?? "未知"}；费用上限不是会计额度。此处可独立复核零额度，但不得超过已复核面额。`}
              />
              <Form.Item
                name="accountingCapacity"
                label="独立会计确认额度（人民币最多两位小数）"
                rules={[{ required: true }]}
              >
                <Input />
              </Form.Item>
              <Form.Item
                name="basisOriginalId"
                label="会计额度依据真实原件"
                rules={[{ required: true }]}
              >
                <ApOriginalPicker
                  originals={ui.originals}
                  canUpload={ui.canMake}
                  refresh={ui.refreshOriginals}
                />
              </Form.Item>
              <Form.Item
                name="recognitionEligibilityReason"
                label="会计确认资格及额度依据"
                rules={[{ required: true }]}
              >
                <Input.TextArea maxLength={1000} />
              </Form.Item>
              <Form.Item
                name="organizationReviewedNotTaxVerified"
                valuePropName="checked"
              >
                <Checkbox>
                  本额度为组织独立复核，不代表政府验真或税务扣除资格。
                </Checkbox>
              </Form.Item>
            </>
          )}
          {full && (
            <>
              <Alert
                title={`金额由服务端取完整父净份额：${d?.seed.parent?.parent.netConfirmedAmount ?? "未知"}，不可拆分或手填金额。`}
              />
              <Form.Item
                name="accountingDate"
                label="会计日期（原年度开放期间）"
                rules={[{ required: true }]}
              >
                <Input type="date" />
              </Form.Item>
              <Form.Item
                name="dueDate"
                label="组织声明到期日（可不填，不推导支付状态）"
              >
                <Input type="date" />
              </Form.Item>
              <Form.Item
                name="expenseAccountId"
                label="实际费用借方科目"
                rules={[{ required: true }]}
              >
                <Select
                  options={ui.accounts
                    .filter(
                      (a) =>
                        a.enabled &&
                        a.category === "EXPENSE" &&
                        a.normalSide === "DEBIT" &&
                        a.purpose === "NONE",
                    )
                    .map((a) => ({
                      value: a.id,
                      label: `${a.code} ${a.name}`,
                    }))}
                />
              </Form.Item>
              <Form.Item
                name="payableAccountId"
                label="实际供应商服务应付贷方科目（不能按编码推定用途）"
                rules={[{ required: true }]}
              >
                <Select
                  options={ui.accounts
                    .filter(
                      (a) =>
                        a.enabled &&
                        a.category === "LIABILITY" &&
                        a.normalSide === "CREDIT" &&
                        a.purpose === "NONE",
                    )
                    .map((a) => ({
                      value: a.id,
                      label: `${a.code} ${a.name}`,
                    }))}
                />
              </Form.Item>
              <Form.Item
                name="serviceAcceptanceOriginalId"
                label="真实服务验收原件"
                rules={[{ required: true }]}
              >
                <ApOriginalPicker
                  originals={ui.originals}
                  canUpload={ui.canMake}
                  refresh={ui.refreshOriginals}
                />
              </Form.Item>
              <Form.Item
                name="recognitionOriginalId"
                label="真实会计确认理由原件"
                rules={[{ required: true }]}
              >
                <ApOriginalPicker
                  originals={ui.originals}
                  canUpload={ui.canMake}
                  refresh={ui.refreshOriginals}
                />
              </Form.Item>
              <Form.Item
                name="summary"
                label="凭证摘要"
                rules={[{ required: true }]}
              >
                <Input maxLength={500} />
              </Form.Item>
              <Form.Item
                name="recognitionReason"
                label="会计确认理由"
                rules={[{ required: true }]}
              >
                <Input.TextArea maxLength={1000} />
              </Form.Item>
              {[
                [
                  "ordinaryServiceExpenseConfirmed",
                  "确认仅为普通供应商服务费用",
                ],
                [
                  "supplierMatchesDocumentConfirmed",
                  "确认该供应商与本单据真实业务主体一致",
                ],
                ["serviceAcceptedConfirmed", "确认服务实际验收已完成且有依据"],
                [
                  "noTaxAssetInventoryPayrollConfirmed",
                  "确认不含税额分拆、资产、存货、工资或预付暂估",
                ],
                [
                  "payableAccountUseConfirmed",
                  "确认所选负债科目用于本项供应商服务应付，非按编码推定",
                ],
              ].map(([name, label]) => (
                <Form.Item key={name} name={name} valuePropName="checked">
                  <Checkbox>{label}</Checkbox>
                </Form.Item>
              ))}
            </>
          )}
          {kind === "full-reversal" && (
            <>
              <Alert
                type="warning"
                title="只能原会计日期及原开放期间全额反向；不是银行退款，原记账历史不改。反向也需独立提交、审签、真实记账。"
              />
              <Form.Item
                name="accountingReasonOriginalId"
                label="本次会计反向理由真实原件"
                rules={[{ required: true }]}
              >
                <ApOriginalPicker
                  originals={ui.originals}
                  canUpload={ui.canMake}
                  refresh={ui.refreshOriginals}
                />
              </Form.Item>
            </>
          )}
          {(kind === "submit" || kind === "approve") && (
            <Form.Item
              name="requestedExpiresAt"
              label="运营审签有效期（ISO UTC，可留空采用服务端受控默认；最多7天）"
            >
              <Input placeholder="YYYY-MM-DDTHH:mm:ssZ；按实际当前时间最多7天，非会计年度日期" />
            </Form.Item>
          )}
          <Form.Item
            name="reason"
            label="本次操作理由"
            rules={[{ required: true }]}
          >
            <Input.TextArea maxLength={1000} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
