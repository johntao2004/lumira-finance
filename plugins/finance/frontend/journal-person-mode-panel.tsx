import { useState } from "react";
import {
  Alert,
  Button,
  Collapse,
  Descriptions,
  Input,
  Modal,
  Space,
  Typography,
} from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import type { Mode, ModeRequestDetail } from "./journal-person-contract";
import { JournalPersonModeStatus } from "./journal-person-mode-status";
import { useJournalPersonResource } from "./use-journal-person";
type Props = {
  scope: string;
  mode: Mode | undefined;
  ready: boolean;
  active: boolean;
  canManage: boolean;
  canApprove: boolean;
  restriction: string | null;
  error: string;
  onRefresh: () => Promise<unknown>;
  onReadDenied: () => void;
  readable: (error: unknown) => string;
};
export function JournalPersonModePanel(props: Props) {
  const { mode, scope } = props;
  const requestId =
    mode?.pendingActivationRequestId ??
    mode?.latestActivationRequestId ??
    mode?.activationRequestId;
  const resource = useJournalPersonResource<ModeRequestDetail>(
    requestId
      ? `${scope}/journal-person-sod/activation-requests/${requestId}`
      : "",
    props.active && props.ready,
    props.onReadDenied,
    props.readable,
  );
  const [form] = Form.useForm();
  const [modal, setModal] = useState<{
      action: "create" | "approve" | "reject" | "cancel";
      request: ModeRequestDetail | null;
      generation: string;
    } | null>(null),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const request = resource.data;
  const available =
    props.ready &&
    mode?.identityAvailability === "AVAILABLE" &&
    !!mode.currentActorPersonId &&
    !props.restriction &&
    !resource.loading &&
    !resource.writing;
  const independent =
    !!mode?.currentActorPersonId &&
    !!request &&
    mode.currentActorPersonId !== request.requestedPersonId;
  function open(action: NonNullable<typeof modal>["action"]) {
    if (!mode) return;
    form.resetFields();
    setError("");
    setModal({ action, request, generation: mode.modeGeneration });
  }
  async function submit(values: { reason?: string }) {
    if (!modal || !mode || !available) return;
    if (!values.reason?.trim() || values.reason.length > 1000) {
      setError("请填写操作理由（最多1000字）。");
      return;
    }
    if (modal.generation !== mode.modeGeneration) {
      setError("模式版本已变化，请关闭并重新读取申请。");
      return;
    }
    const action = modal.action;
    if (
      action === "create"
        ? !props.canManage
        : action === "cancel"
          ? !props.canManage
          : !props.canApprove
    )
      return;
    if (
      action === "create" &&
      (!mode.activationEligible ||
        mode.mode !== "ACCOUNT_ONLY" ||
        mode.pendingActivationRequestId)
    )
      return;
    if (
      action !== "create" &&
      (!modal.request ||
        !request ||
        modal.request.status !== "PENDING" ||
        modal.request.id !== request?.id ||
        modal.request.version !== request.version)
    )
      return;
    if (
      action === "cancel" &&
      modal.request?.requestedBy !== mode.currentActorAccountId
    )
      return;
    if ((action === "approve" || action === "reject") && !independent) return;
    if (
      action === "approve" &&
      Date.parse(modal.request!.expiresAt) <= Date.now()
    )
      return;
    const path = `${scope}/journal-person-sod/activation-requests${action === "create" ? "" : `/${modal.request!.id}/${action}`}`;
    const payload =
      action === "create"
        ? {
            schemaVersion: "journal-person-sod/1",
            expectedModeGeneration: modal.generation,
            reason: values.reason,
          }
        : {
            schemaVersion: "journal-person-sod/1",
            expectedVersion: modal.request!.version,
            requestDigest: modal.request!.requestDigest,
            reason: values.reason,
          };
    const result = await resource.write<ModeRequestDetail>(path, payload);
    if (!result) return;
    setModal(null);
    form.resetFields();
    setNotice("申请处理已返回，请以重新读取后的当前模式和申请状态为准。");
    await props.onRefresh();
    if (requestId === result.id) await resource.read();
  }
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <JournalPersonModeStatus
        mode={mode?.mode ?? null}
        busy={!props.ready && !props.error}
        error={props.error}
        generation={mode?.modeGeneration ?? null}
        effectiveAt={mode?.effectiveAt ?? null}
        blockers={mode?.blockers.map((b) => b.message) ?? []}
      />
      {props.restriction && <Alert type="warning" title={props.restriction} />}
      {(error || resource.error) && (
        <Alert type="error" title={error || resource.error} />
      )}
      {notice && <Alert type="success" title={notice} />}
      <Alert
        type="info"
        title="启用只允许符合条件的新年度，且不能降回账号模式"
        description="允许先配置科目；是否为空年及12期开放由服务端完整检查。申请与独立批准均要求近期最终登录并通过TOTP，7天内处理。此处无需目录治理人员权限，仍须具有相应业务岗位和普通人员绑定。"
      />
      <Space wrap>
        <Button
          onClick={() => void props.onRefresh()}
          disabled={resource.writing}
        >
          重新读取年度模式
        </Button>
        <Button
          disabled={!requestId || resource.writing || !props.ready}
          loading={resource.loading}
          onClick={() => void resource.read()}
        >
          重新读取启用申请
        </Button>
        <Button
          type="primary"
          disabled={
            !available ||
            !props.canManage ||
            mode?.mode !== "ACCOUNT_ONLY" ||
            !mode.activationEligible ||
            !!mode.pendingActivationRequestId
          }
          onClick={() => open("create")}
        >
          申请启用人员审签
        </Button>
      </Space>
      {request && (
        <>
          <Descriptions
            column={1}
            bordered
            size="small"
            items={[
              { key: "id", label: "申请", children: request.id },
              {
                key: "status",
                label: "状态",
                children: {
                  PENDING: "待独立批准",
                  APPROVED: "已批准启用",
                  REJECTED: "已驳回",
                  CANCELLED: "原账号已取消",
                }[request.status],
              },
              {
                key: "maker",
                label: "申请账号 / 人员",
                children: `${request.requestedBy} / ${request.requestedPersonId}`,
              },
              { key: "reason", label: "申请理由", children: request.reason },
              {
                key: "expiry",
                label: "申请 / 到期时间",
                children: `${request.createdAt} / ${request.expiresAt}`,
              },
              {
                key: "digest",
                label: "版本 / 摘要",
                children: (
                  <Typography.Text style={{ overflowWrap: "anywhere" }}>
                    {request.version} / {request.requestDigest}
                  </Typography.Text>
                ),
              },
              {
                key: "decision",
                label: "实际决定账号 / 人员",
                children: `${request.decidedBy ?? "尚无"} / ${request.decidedPersonId ?? "尚无"}`,
              },
              {
                key: "decisionReason",
                label: "决定说明",
                children: request.decisionReason ?? "尚无决定",
              },
            ]}
          />
          <Space wrap>
            <Button
              disabled={
                !available ||
                !props.canApprove ||
                !independent ||
                request.status !== "PENDING" ||
                Date.parse(request.expiresAt) <= Date.now()
              }
              onClick={() => open("approve")}
            >
              独立批准启用
            </Button>
            <Button
              disabled={
                !available ||
                !props.canApprove ||
                !independent ||
                request.status !== "PENDING"
              }
              onClick={() => open("reject")}
            >
              独立驳回启用申请
            </Button>
            <Button
              disabled={
                !available ||
                !props.canManage ||
                request.status !== "PENDING" ||
                request.requestedBy !== mode?.currentActorAccountId
              }
              onClick={() => open("cancel")}
            >
              原账号取消启用申请
            </Button>
          </Space>
          <Collapse
            items={[
              {
                key: "snapshot",
                label: "启用申请公开快照",
                children: (
                  <pre
                    style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
                  >
                    {JSON.stringify(request, null, 2)}
                  </pre>
                ),
              },
            ]}
          />
        </>
      )}
      <Modal
        title={
          modal?.action === "create"
            ? "申请单向启用年度凭证人员审签"
            : modal?.action === "approve"
              ? "独立批准启用"
              : modal?.action === "reject"
                ? "独立驳回启用申请"
                : "原账号取消启用申请"
        }
        open={!!modal}
        onCancel={() => {
          if (!resource.writing) setModal(null);
        }}
        onOk={() => form.submit()}
        confirmLoading={resource.writing}
        okButtonProps={{ disabled: !available }}
        destroyOnHidden
      >
        {(error || resource.error) && (
          <Alert type="error" title={error || resource.error} />
        )}
        <Form form={form} layout="vertical" onFinish={submit}>
          <Form.Item
            name="reason"
            label="操作理由"
            rules={[{ required: true, message: "请填写理由" }]}
          >
            <Input.TextArea maxLength={1000} rows={4} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
