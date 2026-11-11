import { useState } from "react";
import {
  Alert,
  Button,
  Collapse,
  Descriptions,
  Input,
  Modal,
  Space,
  Table,
  Typography,
} from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import type { JournalPersonContext } from "./journal-person-contract";
import type { Account } from "./types";
import {
  journalPersonIndependent,
  journalPersonPostCandidate,
  journalPersonRejectable,
} from "./journal-person-rules";
import { useJournalPersonResource } from "./use-journal-person";
import { useMovementEvidence } from "./movement-evidence";
type Props = {
  scope: string;
  journalId: string;
  legacyVersion: number;
  ready: boolean;
  canApprove: boolean;
  canPost: boolean;
  restriction: string | null;
  accounts: Account[];
  onChanged: () => Promise<unknown>;
  onReadDenied: () => void;
  readable: (error: unknown) => string;
};
export function JournalPersonActions(props: Props) {
  const resource = useJournalPersonResource<JournalPersonContext>(
    `${props.scope}/journals/${props.journalId}/person-sod`,
    props.ready,
    props.onReadDenied,
    props.readable,
  );
  const evidence = useMovementEvidence(
    props.scope,
    false,
    props.onReadDenied,
    props.readable,
  );
  const [form] = Form.useForm(),
    [modal, setModal] = useState<{
      action: "approve" | "post" | "terminate" | "reject";
      snapshot: JournalPersonContext;
    } | null>(null),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const context = resource.data,
    snapshot = context?.contentSnapshot;
  const usable =
    props.ready &&
    !props.restriction &&
    !resource.loading &&
    !resource.writing &&
    context?.mode.mode === "JOURNAL_PERSON_SOD" &&
    context.identityAvailability === "AVAILABLE" &&
    !!snapshot &&
    !!context.contentVersion &&
    !!context.contentDigest;
  const actor = context
    ? {
        accountId: context.currentActorAccountId,
        personId: context.currentActorPersonId,
      }
    : null;
  const independent = context
    ? journalPersonIndependent(actor, context.maker, context.lastEditor)
    : false;
  const postable = context
    ? journalPersonPostCandidate({
        businessPostAllowed: props.canPost,
        journalStatus: context.journalStatus,
        journalVersion: context.journalVersion,
        contentVersion: context.contentVersion,
        contentDigest: context.contentDigest,
        approval: context.currentApproval,
        now: Date.now(),
      })
    : false;
  function open(action: NonNullable<typeof modal>["action"]) {
    if (!context) return;
    setError("");
    form.resetFields();
    if (action === "approve") {
      const expires = new Date(Date.now() + 24 * 60 * 60 * 1000);
      form.setFieldsValue({
        expiresAt: new Date(
          expires.getTime() - expires.getTimezoneOffset() * 60000,
        )
          .toISOString()
          .slice(0, 16),
      });
    }
    setModal({ action, snapshot: context });
  }
  async function submit(values: { reason?: string; expiresAt?: string }) {
    if (!modal || !usable || !context) return;
    const frozen = modal.snapshot;
    if (!frozen.contentVersion || !frozen.contentDigest) return;
    if (
      frozen.journalVersion !== context.journalVersion ||
      frozen.contentVersion !== context.contentVersion ||
      frozen.contentDigest !== context.contentDigest
    ) {
      setError("凭证内容已变化，请关闭并重新读取后处理。");
      return;
    }
    const common = {
      schemaVersion: "journal-person-sod/1",
      expectedJournalVersion: frozen.journalVersion,
      expectedContentVersion: frozen.contentVersion,
      contentDigest: frozen.contentDigest,
    };
    let path = "",
      payload: Record<string, unknown>;
    if (modal.action === "approve") {
      if (
        !props.canApprove ||
        !independent ||
        frozen.journalStatus !== "SUBMITTED"
      )
        return;
      const expiry = Date.parse(values.expiresAt ?? "");
      if (
        !Number.isFinite(expiry) ||
        expiry <= Date.now() ||
        expiry > Date.now() + 7 * 86400000
      ) {
        setError("请选择未来7天内的明确批准到期时间。");
        return;
      }
      path = "person-approvals";
      payload = {
        ...common,
        requestedExpiresAt: new Date(expiry).toISOString(),
      };
    } else if (modal.action === "post") {
      if (!postable || !frozen.currentApproval) return;
      path = "person-post";
      payload = { ...common, approvalId: frozen.currentApproval.id };
    } else if (modal.action === "reject") {
      if (
        !props.canApprove ||
        !independent ||
        !journalPersonRejectable("JOURNAL_PERSON_SOD", frozen.journalStatus) ||
        !context.mode.capabilities.personReject
      )
        return;
      if (
        !Number.isSafeInteger(props.legacyVersion) ||
        String(props.legacyVersion) !== frozen.journalVersion
      ) {
        setError("旧退回协议的流程版本尚未与当前凭证一致，请重新读取。");
        return;
      }
      if (!values.reason?.trim() || values.reason.length > 500) {
        setError("请填写退回理由（最多500字）。");
        return;
      }
      path = "reject";
      payload = { expectedVersion: props.legacyVersion, reason: values.reason };
    } else {
      if (!props.canApprove || !independent || frozen.journalStatus !== "DRAFT")
        return;
      if (!values.reason?.trim() || values.reason.length > 1000) {
        setError("请填写行政终结理由（最多1000字）。");
        return;
      }
      path = "terminate-draft";
      payload = { ...common, reason: values.reason };
    }
    const result = await resource.write<JournalPersonContext>(
      `${props.scope}/journals/${props.journalId}/${path}`,
      payload,
    );
    if (!result) return;
    setModal(null);
    form.resetFields();
    setNotice("处理已返回，请以重新读取后的凭证及审批证明为准。");
    await props.onChanged();
    await resource.read();
  }
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Title level={5}>
        凭证人员审签 · {props.journalId}
      </Typography.Title>
      <Alert
        type="info"
        title="批准绑定明确的凭证内容与原件依据"
        description="同一人员的另一账号不能审核其制单或编辑内容。记账沿既有业务岗位，不额外要求第三人；高风险决定须近期最终登录并通过TOTP。组织人工核验不是政府身份认证。"
      />
      {(error || resource.error || evidence.error) && (
        <Alert type="error" title={error || resource.error || evidence.error} />
      )}
      {notice && <Alert type="success" title={notice} />}
      {props.restriction && <Alert type="warning" title={props.restriction} />}
      <Button
        disabled={resource.writing}
        loading={resource.loading}
        onClick={() => void resource.read()}
      >
        重新读取人员审签与冻结内容
      </Button>
      {!snapshot && (
        <Alert
          type="warning"
          title="冻结内容尚未读取成功；不能仅凭摘要批准、记账或行政终结。"
        />
      )}
      {context && (
        <Descriptions
          bordered
          size="small"
          column={1}
          items={[
            {
              key: "status",
              label: "凭证当前状态",
              children: {
                DRAFT: "草稿",
                SUBMITTED: "待审核",
                APPROVED: "已批准",
                POSTED: "已记账",
                CANCELLED: "已作废",
              }[context.journalStatus],
            },
            {
              key: "versions",
              label: "流程版本 / 内容版本",
              children: `${context.journalVersion} / ${context.contentVersion ?? "未冻结"}`,
            },
            {
              key: "maker",
              label: "原制单账号 / 人员",
              children: context.maker
                ? `${context.maker.accountId} / ${context.maker.personId}`
                : "身份证明缺失",
            },
            {
              key: "editor",
              label: "最后编辑账号 / 人员",
              children: context.lastEditor
                ? `${context.lastEditor.accountId} / ${context.lastEditor.personId}`
                : "身份证明缺失",
            },
            {
              key: "digest",
              label: "当前内容摘要",
              children: (
                <Typography.Text style={{ overflowWrap: "anywhere" }}>
                  {context.contentDigest ?? "未冻结"}
                </Typography.Text>
              ),
            },
          ]}
        />
      )}
      {snapshot && (
        <>
          <Descriptions
            bordered
            size="small"
            column={1}
            items={[
              { key: "date", label: "会计日期", children: snapshot.date },
              { key: "summary", label: "具体摘要", children: snapshot.summary },
              {
                key: "type",
                label: "类型 / 金额口径",
                children: "人工普通凭证 / 人民币 / 内部八位精确值",
              },
            ]}
          />
          <Table
            rowKey="lineNo"
            size="small"
            pagination={false}
            dataSource={snapshot.lines}
            scroll={{ x: 950 }}
            columns={[
              { title: "行号", dataIndex: "lineNo", width: 70 },
              {
                title: "实际年度科目",
                width: 300,
                render: (_, row) => {
                  const account = props.accounts.find(
                    (a) => a.id === row.accountId,
                  );
                  return account
                    ? `${account.code} ${account.name}`
                    : row.accountId;
                },
              },
              { title: "借方", dataIndex: "debit", width: 190 },
              { title: "贷方", dataIndex: "credit", width: 190 },
              { title: "行摘要", dataIndex: "memo", width: 240 },
            ]}
          />
          <Table
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={snapshot.documents}
            scroll={{ x: 1200 }}
            locale={{ emptyText: "此冻结版本尚未关联原件" }}
            columns={[
              { title: "原件文件", dataIndex: "filename", width: 220 },
              {
                title: "原件标识 / 服务端摘要",
                width: 490,
                render: (_, row) => (
                  <Typography.Text style={{ overflowWrap: "anywhere" }}>
                    {row.originalId}
                    <br />
                    {row.sha256}
                  </Typography.Text>
                ),
              },
              {
                title: "来源 / 原件位置",
                width: 280,
                render: (_, row) => `${row.source} / ${row.originalLocation}`,
              },
              {
                title: "复核",
                width: 140,
                render: (_, row) => (
                  <Button
                    onClick={() =>
                      void evidence.download(row.originalId, row.filename)
                    }
                  >
                    下载原件复核
                  </Button>
                ),
              },
            ]}
          />
        </>
      )}
      {context?.currentApproval && (
        <Descriptions
          bordered
          size="small"
          column={1}
          items={[
            {
              key: "id",
              label: "当前批准证明",
              children: context.currentApproval.id,
            },
            {
              key: "by",
              label: "真实批准账号 / 人员",
              children: `${context.currentApproval.approvedBy.accountId} / ${context.currentApproval.approvedBy.personId}`,
            },
            {
              key: "time",
              label: "批准 / 到期时间",
              children: `${context.currentApproval.approvedAt} / ${context.currentApproval.expiresAt}`,
            },
            {
              key: "content",
              label: "批准内容 / 流程版本",
              children: `${context.currentApproval.contentVersion} / ${context.currentApproval.approvedJournalVersion}`,
            },
            {
              key: "state",
              label: "证明记录状态",
              children: {
                ACTIVE: "活动批准记录（当前资格仍须重验）",
                CONSUMED: "已用于记账",
                SUPERSEDED: "已被后续流程替代",
              }[context.currentApproval.status],
            },
          ]}
        />
      )}
      {context?.postReceipt && (
        <Descriptions
          bordered
          size="small"
          column={1}
          items={[
            {
              key: "poster",
              label: "实际记账账号 / 人员",
              children: `${context.postReceipt.postedBy.accountId} / ${context.postReceipt.postedBy.personId}`,
            },
            {
              key: "receipt",
              label: "记账证明 / 批准证明",
              children: `${context.postReceipt.id} / ${context.postReceipt.approvalId}`,
            },
            {
              key: "posting",
              label: "实际记账序列 / 时间",
              children: `${context.postReceipt.postingSeq} / ${context.postReceipt.postedAt}`,
            },
          ]}
        />
      )}
      {context?.administrativeTermination && (
        <Alert
          type="warning"
          title="草稿已由独立审核人行政终结"
          description={
            <>
              {context.administrativeTermination.actor.accountId} /{" "}
              {context.administrativeTermination.actor.personId}
              <br />
              {context.administrativeTermination.decidedAt}
              <br />
              {context.administrativeTermination.reason}
              <br />
              原制单人及分录历史保留，未代原制单人提交或签字。
            </>
          }
        />
      )}
      <Space wrap>
        <Button
          type="primary"
          disabled={
            !usable ||
            !props.canApprove ||
            !independent ||
            context?.journalStatus !== "SUBMITTED"
          }
          onClick={() => open("approve")}
        >
          自然人独立审核
        </Button>
        <Button disabled={!usable || !postable} onClick={() => open("post")}>
          按人员批准正式记账
        </Button>
        <Button
          disabled={
            !usable ||
            !props.canApprove ||
            !independent ||
            !context?.mode.capabilities.personReject ||
            !journalPersonRejectable(
              "JOURNAL_PERSON_SOD",
              context.journalStatus,
            )
          }
          onClick={() => open("reject")}
        >
          独立退回待审或已批准凭证
        </Button>
        <Button
          danger
          disabled={
            !usable ||
            !props.canApprove ||
            !independent ||
            context?.journalStatus !== "DRAFT"
          }
          onClick={() => open("terminate")}
        >
          独立行政终结草稿
        </Button>
      </Space>
      {context && (
        <Collapse
          items={[
            {
              key: "proof",
              label: "冻结内容与人员证明公开快照",
              children: (
                <pre
                  style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
                >
                  {JSON.stringify(context, null, 2)}
                </pre>
              ),
            },
          ]}
        />
      )}
      <Modal
        title={
          modal?.action === "approve"
            ? "批准当前明确内容"
            : modal?.action === "post"
              ? "按指定人员批准记账"
              : modal?.action === "reject"
                ? "独立退回凭证"
                : "独立行政终结草稿"
        }
        open={!!modal}
        onCancel={() => {
          if (!resource.writing) setModal(null);
        }}
        onOk={() => form.submit()}
        confirmLoading={resource.writing}
        okButtonProps={{ disabled: !usable }}
        destroyOnHidden
      >
        {(error || resource.error) && (
          <Alert type="error" title={error || resource.error} />
        )}
        <Typography.Paragraph>
          凭证 {props.journalId}；内容版本 {modal?.snapshot.contentVersion}
          ；流程版本 {modal?.snapshot.journalVersion}
          。请核对上方完整分录与原件；决定仅绑定该冻结内容。
        </Typography.Paragraph>
        <Form form={form} layout="vertical" onFinish={submit}>
          {modal?.action === "approve" && (
            <Form.Item
              name="expiresAt"
              label="批准到期时间（本地时间，未来7天以内）"
              rules={[{ required: true }]}
            >
              <Input type="datetime-local" />
            </Form.Item>
          )}
          {(modal?.action === "terminate" || modal?.action === "reject") && (
            <Form.Item
              name="reason"
              label={
                modal.action === "reject"
                  ? "独立退回理由（旧批准将失效）"
                  : "行政终结理由"
              }
              rules={[{ required: true }]}
            >
              <Input.TextArea
                rows={4}
                maxLength={modal.action === "reject" ? 500 : 1000}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </Space>
  );
}
