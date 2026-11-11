import { useEffect, useState } from "react";
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
  Typography,
} from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import type {
  DocumentDetail,
  DocumentPage,
  DocumentSummary,
  ReviewDetail,
} from "./evidence-usage-contract";
import {
  evidenceActionAllowed,
  evidenceCnyEditable,
  evidenceIndependent,
  evidenceOwns,
  evidenceReviewFields,
  evidenceText,
} from "./evidence-usage-rules";
import { useEvidenceResource } from "./use-evidence-usage";
import {
  EvidenceBasisView,
  EvidenceBlockers,
  EvidenceBuckets,
  EvidenceOriginalPicker,
  EvidenceOriginalView,
  EvidenceReason,
  type EvidenceUiProps,
} from "./evidence-usage-ui";
const required = [{ required: true, message: "请填写此项" }];
const states = {
  PENDING: "待独立复核",
  APPROVED: "已发布依据",
  REJECTED: "已驳回",
  CANCELLED: "原账号已取消",
};
export function EvidenceDocumentPanel({
  ui,
  selectedId,
  onSelected,
}: {
  ui: EvidenceUiProps;
  selectedId: string;
  onSelected: (id: string) => void;
}) {
  const base = `${ui.scope}/evidence-usage/documents`;
  const [cursor, setCursor] = useState<string | null>(null),
    [limit, setLimit] = useState(20),
    [reviewOverride, setReviewOverride] = useState("");
  useEffect(() => {
    setCursor(null);
  }, [ui.revision]);
  useEffect(() => {
    setReviewOverride("");
  }, [ui.revision, selectedId]);
  const page = useEvidenceResource<DocumentPage>(
    `${base}?limit=${limit}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
    ui.active,
    ui.revision,
    ui.onReadDenied,
    ui.onReadFailure,
    ui.readable,
  );
  const detail = useEvidenceResource<DocumentDetail>(
    selectedId ? `${base}/${selectedId}` : "",
    ui.active && !!selectedId,
    ui.revision,
    ui.onReadDenied,
    ui.onReadFailure,
    ui.readable,
  );
  const document = detail.data?.document;
  const reviewId = document
    ? reviewOverride ||
      document.pendingReviewProposalId ||
      document.latestReviewProposalId ||
      ""
    : "";
  const review = useEvidenceResource<ReviewDetail>(
    selectedId && reviewId
      ? `${base}/${selectedId}/review-proposals/${reviewId}`
      : "",
    ui.active && !!selectedId && !!reviewId,
    ui.revision,
    ui.onReadDenied,
    ui.onReadFailure,
    ui.readable,
  );
  const [form] = Form.useForm();
  const [modal, setModal] = useState<{
      action:
        | "create-document"
        | "create-review"
        | "approve-review"
        | "reject-review"
        | "cancel-review";
      document: DocumentSummary | null;
      review: ReviewDetail | null;
    } | null>(null),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const working = detail.writing || review.writing || page.writing;
  function allowed(action: string) {
    if (!ui.active) return false;
    const permission =
      action === "approve-review" || action === "reject-review"
        ? ui.canReview
        : ui.canOwner;
    const source =
      action === "create-review"
        ? document?.allowedActions
        : review.data?.allowedActions;
    return (
      evidenceActionAllowed(
        source ?? [],
        action,
        ui.newUseAllowed,
        permission,
      ) && ui.actor.identityAvailability === "AVAILABLE"
    );
  }
  function open(action: NonNullable<typeof modal>["action"]) {
    form.resetFields();
    setError("");
    if (action === "create-review" && document?.currentBasis) {
      const basis = document.currentBasis;
      form.setFieldsValue({
        issuerKey: basis.issuerKey,
        externalIdentityKey: basis.externalIdentityKey,
        faceGross: evidenceCnyEditable(basis.faceGross),
        expenseCapacity: evidenceCnyEditable(basis.expenseCapacity),
        basisOriginalId: basis.basisOriginal.originalId,
        sourceType: basis.sourceType,
        originalLocation: basis.originalLocation,
      });
    }
    setModal({ action, document: document ?? null, review: review.data });
  }
  async function submit(values: Record<string, unknown>) {
    if (!modal || working || !ui.active) return;
    const action = modal.action;
    try {
      let path = base,
        payload: Record<string, unknown>;
      if (action === "create-document") {
        if (!ui.canOwner || !ui.newUseAllowed) return;
        payload = {
          schemaVersion: "evidence-usage/1",
          originalId: evidenceText(values, "originalId", 36),
          fields: evidenceReviewFields(values),
        };
      } else if (action === "create-review") {
        if (
          !allowed(action) ||
          !modal.document ||
          document?.id !== modal.document.id ||
          document.version !== modal.document.version
        ) {
          setError("单据版本已变化，请重新读取后准备。");
          return;
        }
        path += `/${modal.document.id}/review-proposals`;
        payload = {
          schemaVersion: "evidence-usage/1",
          expectedDocumentVersion: modal.document.version,
          fields: evidenceReviewFields(values),
        };
      } else {
        const frozen = modal.review,
          current = review.data;
        if (
          !frozen ||
          !current ||
          current.id !== frozen.id ||
          current.version !== frozen.version ||
          current.contentDigest !== frozen.contentDigest ||
          !allowed(action)
        ) {
          setError("当前复核对象或可用操作已变化，请显式重新读取。");
          return;
        }
        if (
          action === "cancel-review"
            ? !evidenceOwns(ui.actor, frozen.preparedBy)
            : !evidenceIndependent(ui.actor, frozen.preparedBy)
        )
          return;
        path += `/${selectedId}/review-proposals/${frozen.id}/${action.split("-")[0]}`;
        payload = {
          schemaVersion: "evidence-usage/1",
          expectedVersion: frozen.version,
          contentDigest: frozen.contentDigest,
          reason: evidenceText(values, "reason", 1000),
        };
      }
      const writer =
        action === "create-document"
          ? page
          : action === "create-review"
            ? detail
            : review;
      const result = await writer.write<DocumentDetail | ReviewDetail>(
        path,
        payload,
      );
      if (!result) return;
      setModal(null);
      form.resetFields();
      setNotice(
        "单据或依据处理已返回，请以重新读取的当前依据、冻结状态与额度为准。",
      );
      if ("document" in result) onSelected(result.document.id);
      setReviewOverride("");
      ui.onChanged();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "请核对提交内容。");
    }
  }
  const commandError = error || page.error || detail.error || review.error;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Title level={5}>永久法律单据与组织复核依据</Typography.Title>
      {commandError && <Alert type="error" title={commandError} />}
      {notice && <Alert type="success" title={notice} />}
      <Space wrap>
        <Button
          type="primary"
          disabled={!ui.canOwner || !ui.newUseAllowed || working}
          onClick={() => open("create-document")}
        >
          登记单据并提出面额复核
        </Button>
        <Select
          aria-label="单据每页条数"
          value={limit}
          disabled={working || page.loading}
          options={[20, 50, 100].map((value) => ({
            value,
            label: `每页${value}条`,
          }))}
          onChange={(value) => {
            setCursor(null);
            setLimit(value);
            onSelected("");
          }}
        />
        <Button
          disabled={working || page.loading}
          onClick={() => {
            if (cursor) setCursor(null);
            else void page.read();
            onSelected("");
          }}
        >
          单据首屏
        </Button>
        <Button
          disabled={working || page.loading || !page.data?.nextCursor}
          onClick={() => {
            setCursor(page.data?.nextCursor ?? null);
            onSelected("");
          }}
        >
          单据下一页
        </Button>
      </Space>
      <Table
        rowKey="id"
        size="small"
        loading={page.loading}
        pagination={false}
        dataSource={page.data?.items ?? []}
        scroll={{ x: 1150 }}
        locale={{
          emptyText: page.data ? "当前页无单据" : "未读取成功，不代表零条",
        }}
        columns={[
          { title: "稳定单据标识", dataIndex: "id", width: 310 },
          {
            title: "实际来源文件",
            width: 240,
            render: (_, row) => row.sourceOriginal.filename,
          },
          {
            title: "当前依据 / 面额 / 费用上限",
            width: 270,
            render: (_, row) =>
              row.currentBasis
                ? `${row.currentBasis.basisVersion} / ${row.currentBasis.faceGross} / ${row.currentBasis.expenseCapacity}`
                : "尚未组织复核，金额未知",
          },
          {
            title: "新用",
            width: 130,
            render: (_, row) =>
              row.newUseFrozen ? "校正冻结" : "按当前资格核对",
          },
          {
            title: "操作",
            width: 160,
            render: (_, row) => (
              <Button disabled={working} onClick={() => onSelected(row.id)}>
                查看单据与份额
              </Button>
            ),
          },
        ]}
      />
      {document && detail.data && (
        <>
          <Descriptions
            size="small"
            bordered
            column={1}
            items={[
              {
                key: "id",
                label: "法律单据 / 版本",
                children: `${document.id} / ${document.version}`,
              },
              {
                key: "home",
                label: "永久原账套 / 原年度",
                children: `${document.homeBookId} / ${document.homeYearId}`,
              },
              {
                key: "source",
                label: "来源声明 / 实体原件位置",
                children: `${document.sourceType === "PAPER_SCAN" ? "纸质扫描" : "电子原件"} / ${document.originalLocation ?? "无实体位置声明"}`,
              },
              {
                key: "auth",
                label: "真实性",
                children: "未验证；组织声明与额度复核不构成政府验真",
              },
            ]}
          />
          <EvidenceOriginalView original={document.sourceOriginal} ui={ui} />
          <EvidenceBlockers items={document.blockers} />
          {document.newUseFrozen && (
            <Alert
              type="warning"
              title="面额校正期间暂停新的提交和确认；合法退出与已确认净份额更正仍可按权限处理。"
            />
          )}
          <EvidenceBuckets buckets={document.buckets} />
          <EvidenceBasisView basis={document.currentBasis} ui={ui} />
          <Space wrap>
            <Button
              disabled={working || !allowed("create-review")}
              onClick={() => open("create-review")}
            >
              提出同一单据依据校正
            </Button>
            <Button
              disabled={working || detail.loading}
              onClick={() => void detail.read()}
            >
              重新读取当前单据
            </Button>
            <Button
              disabled={!reviewId || working}
              onClick={() => {
                if (reviewOverride) setReviewOverride("");
                else void review.read();
              }}
            >
              查看最近或待审依据
            </Button>
          </Space>
          <Collapse
            items={[
              {
                key: "aliases",
                label: "永久身份别名（不会合并其他单据）",
                children: (
                  <>
                    <pre
                      style={{
                        whiteSpace: "pre-wrap",
                        overflowWrap: "anywhere",
                      }}
                    >
                      {JSON.stringify(detail.data.identityAliases, null, 2)}
                    </pre>
                    {detail.data.aliasesTruncated && (
                      <Alert
                        type="warning"
                        title="仅显示前100条别名，不代表全部历史。"
                      />
                    )}
                  </>
                ),
              },
            ]}
          />
        </>
      )}
      {review.data && (
        <>
          <Typography.Title level={5}>
            具体面额依据复核 · {review.data.id}
          </Typography.Title>
          <Descriptions
            size="small"
            bordered
            column={1}
            items={[
              {
                key: "status",
                label: "状态",
                children: states[review.data.status],
              },
              {
                key: "identity",
                label: "拟单据身份",
                children: `${review.data.fields.issuerKey} / ${review.data.fields.externalIdentityKey}`,
              },
              {
                key: "amount",
                label: "拟面额 / 拟费用上限",
                children: `${review.data.fields.faceGross} / ${review.data.fields.expenseCapacity}`,
              },
              {
                key: "source",
                label: "拟来源 / 原件位置",
                children: `${review.data.fields.sourceType === "PAPER_SCAN" ? "纸质扫描" : "电子原件"} / ${review.data.fields.originalLocation ?? "无实体位置声明"}`,
              },
              {
                key: "maker",
                label: "制备账号 / 人员",
                children: `${review.data.preparedBy.accountId} / ${review.data.preparedBy.personId}`,
              },
              {
                key: "reason",
                label: "真实组织依据说明",
                children: review.data.fields.reason,
              },
              {
                key: "version",
                label: "版本 / 摘要 / 截止时间",
                children: (
                  <Typography.Text style={{ overflowWrap: "anywhere" }}>
                    {review.data.version} / {review.data.contentDigest} /{" "}
                    {review.data.expiresAt}
                  </Typography.Text>
                ),
              },
              {
                key: "declarations",
                label: "冻结的主动声明",
                children: `单文件单单据：${review.data.fields.singleFileSingleDocumentConfirmed ? "已确认" : "缺失"}；已知标识：${review.data.fields.allKnownDocumentIdentifiersDeclared ? "已声明" : "缺失"}；同一单据：${review.data.fields.sameDocumentConfirmed ? "已确认" : "缺失"}`,
              },
              {
                key: "decision",
                label: "真实决定",
                children: review.data.decidedBy
                  ? `${review.data.decidedBy.accountId} / ${review.data.decidedBy.personId} / ${review.data.decidedAt} / ${review.data.decisionReason}`
                  : "尚无决定",
              },
            ]}
          />
          <EvidenceOriginalView original={review.data.basisOriginal} ui={ui} />
          <EvidenceBlockers items={review.data.blockers} />
          <Space wrap>
            <Button
              disabled={
                working ||
                !allowed("approve-review") ||
                !evidenceIndependent(ui.actor, review.data.preparedBy)
              }
              onClick={() => open("approve-review")}
            >
              独立批准面额依据
            </Button>
            <Button
              disabled={
                working ||
                !allowed("reject-review") ||
                !evidenceIndependent(ui.actor, review.data.preparedBy)
              }
              onClick={() => open("reject-review")}
            >
              独立驳回依据
            </Button>
            <Button
              disabled={
                working ||
                !allowed("cancel-review") ||
                !evidenceOwns(ui.actor, review.data.preparedBy)
              }
              onClick={() => open("cancel-review")}
            >
              原账号取消依据
            </Button>
            <Button
              disabled={!review.data.previousReviewProposalId || working}
              onClick={() =>
                setReviewOverride(review.data!.previousReviewProposalId ?? "")
              }
            >
              查看上一笔依据历史
            </Button>
          </Space>
          <Collapse
            items={[
              {
                key: "review",
                label: "不可变复核完整快照",
                children: (
                  <pre
                    style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
                  >
                    {JSON.stringify(review.data, null, 2)}
                  </pre>
                ),
              },
            ]}
          />
        </>
      )}
      <Modal
        open={!!modal && ui.active}
        title={
          modal?.action === "create-document"
            ? "登记单据与初次面额依据"
            : modal?.action === "create-review"
              ? "提出同一单据依据校正"
              : "独立面额依据决定"
        }
        width={760}
        onCancel={() => {
          if (!working) setModal(null);
        }}
        onOk={() => form.submit()}
        confirmLoading={working}
        okButtonProps={{ disabled: !ui.active }}
        destroyOnHidden
      >
        {commandError && <Alert type="error" title={commandError} />}
        <Form form={form} layout="vertical" onFinish={submit}>
          {(modal?.action === "create-document" ||
            modal?.action === "create-review") && (
            <>
              {modal.action === "create-document" && (
                <Form.Item
                  name="originalId"
                  label="真实来源原件（可由员工先上传）"
                  rules={required}
                >
                  <EvidenceOriginalPicker ui={ui} />
                </Form.Item>
              )}
              <Form.Item
                name="issuerKey"
                label="真实开具机构稳定标识"
                rules={required}
              >
                <Input maxLength={128} />
              </Form.Item>
              <Form.Item
                name="externalIdentityKey"
                label="真实单据永久唯一标识（不要随机生成）"
                rules={required}
              >
                <Input maxLength={200} />
              </Form.Item>
              <Form.Item
                name="faceGross"
                label="单据面额（人民币最多2位）"
                rules={required}
              >
                <Input />
              </Form.Item>
              <Form.Item
                name="expenseCapacity"
                label="独立组织复核的费用归属上限（非负且不超过面额）"
                rules={required}
              >
                <Input />
              </Form.Item>
              <Form.Item
                name="sourceType"
                label="实际来源声明（不是验真）"
                rules={required}
              >
                <Select
                  options={[
                    { value: "ELECTRONIC_ORIGINAL", label: "电子原件" },
                    { value: "PAPER_SCAN", label: "纸质扫描件" },
                  ]}
                />
              </Form.Item>
              <Form.Item
                name="originalLocation"
                label="实体原件位置（纸质扫描必填，电子原件可留空）"
              >
                <Input maxLength={500} />
              </Form.Item>
              <Form.Item
                name="basisOriginalId"
                label="组织面额复核依据的真实原件"
                rules={required}
              >
                <EvidenceOriginalPicker ui={ui} />
              </Form.Item>
              {[
                [
                  "singleFileSingleDocumentConfirmed",
                  "我确认本文件仅对应这一张独立单据，不是打包多票。",
                ],
                [
                  "allKnownDocumentIdentifiersDeclared",
                  "我已声明本单据全部已知稳定标识，不以换扫描件或年度重复取得额度。",
                ],
                [
                  "sameDocumentConfirmed",
                  "我确认这些身份及依据属于同一法律单据，不合并其他单据。",
                ],
              ].map(([name, label]) => (
                <Form.Item
                  key={name}
                  name={name}
                  valuePropName="checked"
                  rules={[
                    {
                      validator: (_, value) =>
                        value === true
                          ? Promise.resolve()
                          : Promise.reject(new Error("请主动确认")),
                    },
                  ]}
                >
                  <Checkbox>{label}</Checkbox>
                </Form.Item>
              ))}
            </>
          )}
          <EvidenceReason />
        </Form>
      </Modal>
    </Space>
  );
}
