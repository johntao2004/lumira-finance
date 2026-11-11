import { useEffect, useState } from "react";
import { Alert, Button, Input, Modal, Space, Typography } from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import type { RunDetail } from "./profit-closing-contract";
import {
  closingActionPayload,
  closingCommandAllowed,
  closingSameRun,
  closingTotals,
} from "./profit-closing-rules";
import {
  useProfitClosingResource,
  useProfitClosingDrafts,
  useProfitClosingIdentity,
} from "./profit-closing-resource";
export type ProfitClosingProps = {
  scope: string;
  active: boolean;
  revision: number;
  newUseAllowed: boolean;
  canMake: boolean;
  canApprove: boolean;
  canPost: boolean;
  onReadDenied: () => void;
  readable: (e: unknown) => string;
  onChanged: () => void;
};
const labels = {
  submit: "提交独立审核",
  approve: "独立批准结转",
  post: "正式记账结转",
  reject: "独立退回原受控草稿",
  withdraw: "原账号撤回待审",
  cancel: "原账号终结草稿",
  "admin-terminate": "独立行政终结草稿",
};
type Command = keyof typeof labels;
export function ProfitClosingActions({
  ui,
  run,
  onResult,
}: {
  ui: ProfitClosingProps;
  run: RunDetail | null;
  onResult: (run: RunDetail) => void;
}) {
  const resource = useProfitClosingResource<never>(
    "",
    ui.active,
    ui.revision,
    ui.onReadDenied,
    ui.readable,
  );
  const identity = useProfitClosingIdentity();
  const drafts = useProfitClosingDrafts();
  const draftKey = (action: Command, r: RunDetail) =>
    JSON.stringify([
      "action",
      action,
      r.id,
      r.version,
      r.contentVersion,
      r.contentDigest,
      r.sourceDigest,
    ]);
  const [form] = Form.useForm();
  const [modal, setModal] = useState<{
      action: Command;
      identity: string;
      run: RunDetail;
    } | null>(null),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  useEffect(() => {
    setModal(null);
    setError("");
    setNotice("");
    form.resetFields();
  }, [identity, form]);
  function allowed(action: Command) {
    return closingCommandAllowed(action, run, ui, Date.now());
  }
  async function submit(values: { reason?: string; expiresAt?: string }) {
    if (
      !modal ||
      modal.identity !== identity ||
      !run ||
      !allowed(modal.action) ||
      !closingSameRun(modal.run, run)
    ) {
      setError(
        "当前运行或来源证明已变化，请先显式重新读取；未改变原正文请求键。",
      );
      return;
    }
    try {
      if (!closingTotals(run.content.generatedLines).balanced)
        throw new Error("生成借贷不平衡，不得执行。");
      const payload: Record<string, unknown> = {
        ...closingActionPayload(modal.run, values.reason),
      };
      if (modal.action === "approve") {
        const expiry = values.expiresAt?.trim();
        if (expiry) {
          const date = Date.parse(expiry);
          if (
            !Number.isFinite(date) ||
            date <= Date.now() ||
            date > Date.now() + 7 * 86400000
          )
            throw new Error("请选择未来7天内的明确到期时间。");
          payload.requestedExpiresAt = new Date(date).toISOString();
        } else payload.requestedExpiresAt = null;
      }
      if (modal.action === "post") {
        if (
          !modal.run.currentApproval ||
          modal.run.currentApproval.id !== run.currentApproval?.id
        )
          throw new Error("批准证明已变化，请重新读取。");
        payload.approvalId = modal.run.currentApproval.id;
      }
      const result = await resource.write<RunDetail>(
        `${ui.scope}/profit-closing/runs/${run.id}/${modal.action}`,
        payload,
      );
      if (result) {
        drafts.clear(draftKey(modal.action, modal.run));
        setModal(null);
        setNotice("命令已返回，请以重新读取的当前运行、批准与记账证明为准。");
        onResult(result);
        ui.onChanged();
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : ui.readable(e));
    }
  }
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      {(resource.error || error) && (
        <Alert type="error" title={error || resource.error} />
      )}{" "}
      {notice && <Alert type="info" title={notice} />}
      <Space wrap>
        {(Object.keys(labels) as Command[]).map((action) => (
          <Button
            key={action}
            danger={action === "cancel" || action === "admin-terminate"}
            disabled={!allowed(action) || resource.writing}
            onClick={() => {
              if (!run) return;
              setError("");
              form.resetFields();
              form.setFieldsValue(drafts.get(draftKey(action, run)) ?? {});
              setModal({ action, run, identity });
            }}
          >
            {labels[action]}
          </Button>
        ))}
      </Space>
      <Typography.Text>
        审签独立于本次制备与编辑人员；已有有效独立批准后，不额外要求第三位记账人。近期最终登录/TOTP由服务端核验。
      </Typography.Text>
      <Modal
        title={modal ? labels[modal.action] : "结转操作"}
        open={!!modal && modal.identity === identity && ui.active && !!run}
        onCancel={() => setModal(null)}
        onOk={() => form.submit()}
        confirmLoading={resource.writing}
        okButtonProps={{
          disabled: !modal || !allowed(modal.action) || resource.writing,
        }}
        destroyOnHidden
      >
        <Typography.Paragraph style={{ overflowWrap: "anywhere" }}>
          运行：{modal?.run.id}；凭证：{modal?.run.journalId}；冻结内容版本：
          {modal?.run.contentVersion}；摘要：{modal?.run.contentDigest}。
        </Typography.Paragraph>
        <Form
          form={form}
          layout="vertical"
          onFinish={submit}
          onValuesChange={(_, values) => {
            if (modal) drafts.save(draftKey(modal.action, modal.run), values);
          }}
        >
          <Form.Item
            name="reason"
            label="操作及组织核验理由"
            rules={[
              { required: true, message: "请填写理由" },
              { max: 1000, message: "最多1000字" },
            ]}
          >
            <Input.TextArea rows={4} maxLength={1000} />
          </Form.Item>
          {modal?.action === "approve" && (
            <Form.Item
              name="expiresAt"
              label="明确批准到期时间（可留空使用服务端7天内规则）"
            >
              <Input type="datetime-local" />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </Space>
  );
}
