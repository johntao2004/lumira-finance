import { useEffect, useRef, useState } from "react";
import {
  Alert,
  Button,
  Checkbox,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Typography,
} from "antd";
import { EvidenceWriteContext } from "./evidence-write-context";
import { useTaxEntities } from "./tax-entity-resource";
import {
  boundedTaxDraft,
  taxAction,
  taxCanDecide,
  taxCreate,
  taxProposal,
  taxStatus,
  taxUuid,
} from "./tax-entity-rules";
import {
  TaxCurrent,
  TaxProfile,
  TaxReceipt,
  TaxRevision,
} from "./tax-entity-views";
import { TaxEvidencePicker } from "./tax-entity-original";
import type { TaxDraft } from "./tax-entity-state";
import type {
  CommandResult,
  OriginalReference,
  TaxEntitySummary,
} from "./tax-entity-contract";
export type TaxEntityPanelProps = {
  bookId: string;
  authHint: string;
  active: boolean;
  refreshToken: number;
  role: string;
  grants: Record<string, boolean>;
  onReadDenied: () => void;
  readable: (e: unknown) => string;
};
/** Book-level registry. Do not key this component or its request owner by the financial navigation year. */
export function TaxEntityPanel({
  bookId,
  authHint,
  active,
  refreshToken,
  role,
  grants,
  onReadDenied,
  readable,
}: TaxEntityPanelProps) {
  const resource = useTaxEntities(
    bookId,
    authHint,
    active && !!bookId && !!grants.read,
    refreshToken,
    onReadDenied,
    readable,
  );
  const [knownId, setKnownId] = useState(""),
    [modal, setModal] = useState<TaxDraft | null>(null),
    [formError, setFormError] = useState(""),
    [receipt, setReceipt] = useState<{
      identity: string;
      result: CommandResult;
    } | null>(null),
    [form] = Form.useForm<Record<string, unknown>>();
  const group = resource.group,
    detail = group?.detail,
    revision = group?.revision,
    actor = group?.catalog.currentActor;
  const canMake =
    role === "OWNER" &&
    !!grants.manage &&
    !!grants.create &&
    actor?.identityAvailability === "AVAILABLE" &&
    !!actor.personId;
  const available = !!group && !resource.busy && !resource.writing,
    home = detail?.scope.governanceHomeBookId === bookId;
  const currentModal = modal?.identity === resource.identity ? modal : null;
  const boundary = useRef(resource.boundary),
    live = useRef({ alive: true, identity: resource.identity });
  live.current.identity = resource.identity;
  useEffect(() => {
    live.current.alive = true;
    return () => {
      live.current.alive = false;
    };
  }, []);
  useEffect(() => {
    if (boundary.current !== resource.boundary) {
      boundary.current = resource.boundary;
      setKnownId("");
      setModal(null);
      setReceipt(null);
      setFormError("");
      form.resetFields();
    }
  }, [resource.boundary, form]);
  function open(kind: TaxDraft["kind"]) {
    if (!available) return;
    const key = JSON.stringify([
      kind,
      kind === "create" ? "new" : revision?.revision.id,
    ]);
    const saved = resource.drafts.get(key);
    const r = kind === "create" ? null : revision;
    if (kind !== "create" && !r) return;
    const content = r?.content;
    const evidence: OriginalReference | null = content
      ? {
          yearId: content.registrationEvidence.yearId,
          originalId: content.registrationEvidence.id,
          expectedVersion: content.registrationEvidence.version,
          expectedSha256: content.registrationEvidence.sha256,
          sourceType: content.registrationEvidence.sourceType,
          originalLocation: content.registrationEvidence.originalLocation,
        }
      : null;
    const values: Record<string, unknown> = content
      ? {
          ...content,
          registrationEvidence: undefined,
          evidenceYearId: content.registrationEvidence.yearId,
          sourceType: content.registrationEvidence.sourceType,
          originalLocation: content.registrationEvidence.originalLocation,
          desiredStatus: "ACTIVE",
          singleTaxpayerConfirmed: false,
          allKnownRegistrationIdentifiersDeclared: false,
          organizationReviewedNotGovernmentVerified: false,
          reason: "",
        }
      : {};
    const draft: TaxDraft =
      saved?.identity === resource.identity
        ? saved
        : {
            identity: resource.identity,
            key,
            kind,
            operationId: crypto.randomUUID(),
            values,
            revision: r ?? null,
            evidence,
          };
    boundedTaxDraft(resource.drafts, key, draft);
    setModal(draft);
    setFormError("");
    form.resetFields();
    form.setFieldsValue(draft.values);
  }
  function saveDraft(
    values: Record<string, unknown>,
    evidence = currentModal?.evidence ?? null,
  ) {
    if (!currentModal) return;
    const next = {
      ...currentModal,
      values: { ...currentModal.values, ...values },
      evidence,
    };
    boundedTaxDraft(resource.drafts, next.key, next);
    setModal(next);
  }
  const inputMode =
    currentModal?.kind === "create" || currentModal?.kind === "propose";
  const canDecide =
    !!revision &&
    !!actor &&
    taxCanDecide(actor, revision, role, !!grants.approve);
  const permitted = (kind: TaxDraft["kind"]) => {
    if (!available) return false;
    if (kind === "create")
      return canMake && !!group?.catalog.allowedActions.includes("CREATE");
    if (!revision || !home) return false;
    if (kind === "propose")
      return (
        canMake &&
        revision.revision.id === revision.taxEntity.latestRevisionId &&
        revision.allowedActions.includes("PROPOSE_REVISION")
      );
    if (kind === "cancel")
      return (
        canMake &&
        actor?.accountId === revision.revision.preparedBy.accountId &&
        revision.allowedActions.includes("CANCEL")
      );
    return (
      canDecide &&
      revision.allowedActions.includes(
        kind === "approve" ? "APPROVE" : "REJECT",
      )
    );
  };
  const frozenStillReadable =
    !!currentModal &&
    (currentModal.kind === "create" ||
      !!(
        revision &&
        currentModal.revision &&
        revision.revision.id === currentModal.revision.revision.id
      ));
  async function submit() {
    if (!currentModal || !frozenStillReadable || !permitted(currentModal.kind))
      return;
    const identity = resource.identity;
    try {
      const values = await form.validateFields();
      saveDraft(values);
      const frozen = currentModal.revision;
      let payload: Record<string, unknown>,
        path = resource.scope;
      if (currentModal.kind === "create") {
        if (!currentModal.evidence)
          throw new Error("请选择真实原件和授权来源年度。");
        payload = taxCreate(
          values,
          currentModal.operationId,
          currentModal.evidence,
        );
      } else {
        if (!frozen) throw new Error("缺少冻结修订基准，请重新读取。");
        path += `/${frozen.taxEntity.id}`;
        if (currentModal.kind === "propose") {
          if (!currentModal.evidence) throw new Error("请选择真实登记证据。");
          path += "/revision-proposals";
          payload = taxProposal(values, frozen, currentModal.evidence);
        } else {
          path += `/revisions/${frozen.revision.id}/${currentModal.kind}`;
          payload = taxAction(values, frozen);
        }
      }
      setFormError("");
      const result = await resource.write(path, payload, (response) => ({
        ...resource.selection,
        id: response.current.taxEntity.id,
        revisionId: response.current.revision.id,
        cursor: null,
      }));
      if (result && live.current.alive && live.current.identity === identity) {
        resource.drafts.delete(currentModal.key);
        setReceipt({ identity, result });
        setModal(null);
        form.resetFields();
      }
    } catch (e) {
      if (live.current.alive && live.current.identity === identity)
        setFormError(e instanceof Error ? e.message : "请核对必填字段。");
    }
  }
  function select(id: string, revisionId = "") {
    void resource.read({ ...resource.selection, id, revisionId });
  }
  const currentReceipt =
    receipt?.identity === resource.identity ? receipt.result : null;
  return (
    <EvidenceWriteContext.Provider
      value={{ owner: resource.owner, identity: resource.identity }}
    >
      <Space orientation="vertical" style={{ width: "100%", minWidth: 0 }}>
        <Alert
          type="info"
          title="纳税主体组织人工登记与独立发布"
          description="本页登记中国组织声明的稳定纳税身份，不是政府验真。税型、科技优惠、税率、期限及资格均须另行核验；未接入税额计算、正式申报或缴款。税额未知，不显示为零。主体跨会计年度保持同一ID；本地停用与恢复不代表官方注销或恢复。"
        />
        <Space wrap>
          <Button
            loading={resource.busy}
            disabled={resource.writing}
            onClick={() =>
              void resource.read({ ...resource.selection, cursor: null })
            }
          >
            显式重新读取首屏
          </Button>
          <Button
            type="primary"
            disabled={!permitted("create")}
            onClick={() => open("create")}
          >
            登记新纳税主体
          </Button>
          <Select
            aria-label="纳税主体状态筛选"
            value={resource.selection.status}
            disabled={!available}
            options={["ALL", "UNPUBLISHED", "ACTIVE", "INACTIVE"].map(
              (value) => ({
                value,
                label: value === "ALL" ? "全部本地状态" : taxStatus(value),
              }),
            )}
            onChange={(status) =>
              void resource.read({
                ...resource.selection,
                status,
                cursor: null,
                id: "",
                revisionId: "",
              })
            }
          />
          <Select
            aria-label="纳税主体每页条数"
            value={resource.selection.limit}
            disabled={!available}
            options={[20, 50, 100].map((value) => ({
              value,
              label: `每页${value}`,
            }))}
            onChange={(limit) =>
              void resource.read({
                ...resource.selection,
                limit,
                cursor: null,
                id: "",
                revisionId: "",
              })
            }
          />
        </Space>
        {resource.error && <Alert type="error" title={resource.error} />}{" "}
        {resource.notice && <Alert type="success" title={resource.notice} />}
        {!group && (
          <Typography.Text>
            {resource.busy
              ? "正在重新读取，旧主体、修订及操作暂不可用。"
              : "未读取成功或尚未读取；不将未知数量显示为零。"}
          </Typography.Text>
        )}
        {group && (
          <>
            <Typography.Paragraph>
              本列表仅含当前治理归属账套的主体；总量未计算。其他授权账套的已知主体可按稳定ID读取。当前账号：
              {actor?.accountId}；组织身份：
              {actor?.identityAvailability === "AVAILABLE"
                ? "已绑定（组织核验）"
                : "未具备登记治理身份，仅依权限读取"}
              。
            </Typography.Paragraph>
            <Table<TaxEntitySummary>
              rowKey="id"
              size="small"
              pagination={false}
              scroll={{ x: 850 }}
              dataSource={group.list.items}
              columns={[
                {
                  title: "稳定主体ID",
                  dataIndex: "id",
                  width: 250,
                  render: (id: string) => (
                    <Typography.Text
                      style={{ overflowWrap: "anywhere" }}
                      copyable
                    >
                      {id}
                    </Typography.Text>
                  ),
                },
                {
                  title: "登记号",
                  width: 170,
                  render: (_, row) => row.identity.canonicalTaxRegistrationNo,
                },
                {
                  title: "当前已发布名称",
                  dataIndex: "effectiveDisplayName",
                  width: 180,
                  render: (v: string | null) => v ?? "尚未发布",
                },
                {
                  title: "本地当前状态",
                  width: 170,
                  render: (_, row) => taxStatus(row.effectiveStatus),
                },
                {
                  title: "查阅",
                  width: 100,
                  render: (_, row) => (
                    <Button
                      disabled={!available}
                      onClick={() => select(row.id)}
                    >
                      读取详情
                    </Button>
                  ),
                },
              ]}
            />
            <Space wrap>
              <Button
                disabled={!available || !resource.selection.cursor}
                onClick={() =>
                  void resource.read({
                    ...resource.selection,
                    cursor: null,
                    id: "",
                    revisionId: "",
                  })
                }
              >
                返回首屏
              </Button>
              <Button
                disabled={!available || !group.list.nextCursor}
                onClick={() =>
                  void resource.read({
                    ...resource.selection,
                    cursor: group.list.nextCursor,
                    id: "",
                    revisionId: "",
                  })
                }
              >
                下一页
              </Button>
            </Space>
            <Space.Compact style={{ width: "100%" }}>
              <Input
                aria-label="已知纳税主体ID"
                value={knownId}
                onChange={(e) => setKnownId(e.target.value)}
                placeholder="已知稳定主体UUID（仍须当前与归属账套双重读取权限）"
              />
              <Button
                disabled={!available || !taxUuid.test(knownId)}
                onClick={() => select(knownId)}
              >
                按已知ID读取
              </Button>
            </Space.Compact>
            {group.catalog.limitations.map((message, index) => (
              <Typography.Paragraph key={index}>{message}</Typography.Paragraph>
            ))}
          </>
        )}
        {group && detail && (
          <>
            <TaxCurrent value={detail.taxEntity} />
            {detail.effectiveContent && (
              <TaxProfile
                key={`${resource.identity}:${detail.taxEntity.id}:effective`}
                value={detail.effectiveContent}
                onDenied={resource.deny}
                readable={readable}
              />
            )}
            <Space wrap>
              <Button
                disabled={!available}
                onClick={() =>
                  select(detail.taxEntity.id, detail.taxEntity.latestRevisionId)
                }
              >
                读取最新修订
              </Button>
              {detail.taxEntity.approvedRevision && (
                <Button
                  disabled={!available}
                  onClick={() =>
                    select(
                      detail.taxEntity.id,
                      detail.taxEntity.approvedRevision!.id,
                    )
                  }
                >
                  读取当前批准修订
                </Button>
              )}
              {detail.taxEntity.pendingRevisionId && (
                <Button
                  disabled={!available}
                  onClick={() =>
                    select(
                      detail.taxEntity.id,
                      detail.taxEntity.pendingRevisionId!,
                    )
                  }
                >
                  读取待复核提案
                </Button>
              )}
              <Button
                disabled={!permitted("propose")}
                onClick={() => open("propose")}
              >
                准备不可变修订
              </Button>
            </Space>
            {!home && (
              <Alert
                type="info"
                title="这是跨账套授权查阅；写入必须回到真实治理归属账套，不会自动迁移主体。"
              />
            )}
            {detail.blockers.map((b, index) => (
              <Alert key={index} type="warning" title={b.message} />
            ))}
          </>
        )}
        {group && revision && (
          <>
            <TaxRevision
              key={`${resource.identity}:${revision.revision.id}`}
              value={revision}
              onDenied={resource.deny}
              readable={readable}
            />
            <Space wrap>
              {revision.revision.previousRevisionId && (
                <Button
                  disabled={!available}
                  onClick={() =>
                    select(
                      revision.taxEntity.id,
                      revision.revision.previousRevisionId!,
                    )
                  }
                >
                  读取上一条历史修订
                </Button>
              )}
              <Button
                disabled={!permitted("approve")}
                onClick={() => open("approve")}
              >
                独立批准发布
              </Button>
              <Button
                disabled={!permitted("reject")}
                onClick={() => open("reject")}
              >
                独立驳回
              </Button>
              <Button
                disabled={!permitted("cancel")}
                onClick={() => open("cancel")}
              >
                原制备账号取消
              </Button>
            </Space>
            {revision.blockers.map((b, index) => (
              <Alert key={index} type="warning" title={b.message} />
            ))}
          </>
        )}
        {group && currentReceipt && (
          <TaxReceipt
            value={currentReceipt.commandReceipt}
            replayed={currentReceipt.replayed}
          />
        )}
        <Modal
          open={!!currentModal && active && !!grants.read}
          title={
            currentModal?.kind === "create"
              ? "登记新纳税主体"
              : currentModal?.kind === "propose"
                ? "准备纳税主体修订"
                : "纳税主体独立治理决定"
          }
          width={860}
          destroyOnHidden={false}
          onCancel={() => {
            setModal(null);
            setFormError("");
          }}
          footer={
            <Space wrap>
              <Button
                disabled={resource.writing}
                onClick={() => setModal(null)}
              >
                关闭并保留正文
              </Button>
              <Button
                type="primary"
                loading={resource.writing}
                disabled={
                  !currentModal ||
                  !frozenStillReadable ||
                  !permitted(currentModal.kind)
                }
                onClick={() => void submit()}
              >
                提交此冻结命令
              </Button>
            </Space>
          }
        >
          {currentModal && (
            <Space
              orientation="vertical"
              style={{ width: "100%", minWidth: 0 }}
            >
              <Alert
                type="info"
                title="治理命令须近期正常登录并完成TOTP；不是动作绑定认证。未知结果保留原正文与请求键，请显式读取当前状态后重试。"
              />
              {!available && (
                <Alert
                  type="warning"
                  title="正在读取或未读取成功，旧基准不驱动可写操作。"
                />
              )}
              {group && currentModal.revision && (
                <>
                  <TaxCurrent
                    value={currentModal.revision.taxEntity}
                    title="命令冻结基准（非当前状态）"
                  />
                  {detail &&
                    detail.taxEntity.id ===
                      currentModal.revision.taxEntity.id && (
                      <TaxCurrent
                        value={detail.taxEntity}
                        title="本次权威读取的当前状态"
                      />
                    )}
                  {!inputMode && (
                    <TaxProfile
                      key={`${resource.identity}:${currentModal.key}:frozen`}
                      value={currentModal.revision.content}
                      onDenied={resource.deny}
                      readable={readable}
                    />
                  )}
                </>
              )}
              <Form
                form={form}
                layout="vertical"
                disabled={!available}
                onValuesChange={(_, values) => saveDraft(values)}
              >
                {group && inputMode && (
                  <>
                    {currentModal.kind === "create" && (
                      <Form.Item
                        name="taxNumber"
                        label="真实中国组织登记号（永久身份，ASCII字母转大写）"
                        rules={[{ required: true }]}
                      >
                        <Input maxLength={64} />
                      </Form.Item>
                    )}
                    {currentModal.kind === "propose" && (
                      <Form.Item
                        name="desiredStatus"
                        label="拟发布本地档案状态"
                        rules={[{ required: true }]}
                      >
                        <Select
                          options={(currentModal.revision?.taxEntity
                            .effectiveStatus === "UNPUBLISHED"
                            ? ["ACTIVE"]
                            : ["ACTIVE", "INACTIVE"]
                          ).map((value) => ({
                            value,
                            label: taxStatus(value),
                          }))}
                        />
                      </Form.Item>
                    )}
                    <Form.Item
                      name="displayName"
                      label="组织登记名称"
                      rules={[{ required: true }]}
                    >
                      <Input maxLength={200} />
                    </Form.Item>
                    <Form.Item
                      name="entityKind"
                      label="组织类型声明"
                      rules={[{ required: true }]}
                    >
                      <Select
                        options={[
                          { value: "LEGAL_ENTITY", label: "法人主体" },
                          { value: "BRANCH", label: "分支机构" },
                          {
                            value: "OTHER_REGISTERED_TAXPAYER",
                            label: "其他已登记纳税主体",
                          },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item
                      name="registrationTypeDeclaration"
                      label="登记类型（组织声明，不自动启用税型资格）"
                      rules={[{ required: true }]}
                    >
                      <Input maxLength={100} />
                    </Form.Item>
                    <Form.Item
                      name="residencyDeclaration"
                      label="居民属性声明"
                      rules={[{ required: true }]}
                    >
                      <Select
                        options={[
                          { value: "RESIDENT", label: "居民（组织声明）" },
                          {
                            value: "NON_RESIDENT",
                            label: "非居民（组织声明）",
                          },
                          { value: "UNKNOWN", label: "待核验" },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item
                      name="establishedOn"
                      label="设立日期（可未知，YYYY-MM-DD）"
                    >
                      <Input />
                    </Form.Item>
                    <Form.Item
                      name="cancelledOn"
                      label="官方注销日期声明（可未知；本地停用不会自动填写）"
                    >
                      <Input />
                    </Form.Item>
                    <Form.Item
                      name="authorityCodeDeclaration"
                      label="主管机构代码声明（可未知）"
                    >
                      <Input maxLength={100} />
                    </Form.Item>
                    <Form.Item
                      name="administrativeDivisionDeclaration"
                      label="行政区划代码声明（可未知）"
                    >
                      <Input maxLength={100} />
                    </Form.Item>
                    <Form.Item
                      name="organizationVerificationBasis"
                      label="组织人工核验依据"
                      rules={[{ required: true }]}
                    >
                      <Input.TextArea maxLength={1000} rows={3} />
                    </Form.Item>
                    <TaxEvidencePicker
                      key={`${currentModal.identity}:${currentModal.key}`}
                      bookId={bookId}
                      years={group?.years ?? []}
                      yearId={
                        typeof currentModal.values.evidenceYearId === "string"
                          ? currentModal.values.evidenceYearId
                          : (currentModal.evidence?.yearId ?? "")
                      }
                      onYearChange={(yearId) =>
                        saveDraft(
                          {
                            ...form.getFieldsValue(true),
                            evidenceYearId: yearId,
                          },
                          null,
                        )
                      }
                      value={currentModal.evidence}
                      onChange={(evidence) =>
                        saveDraft(
                          {
                            ...currentModal.values,
                            ...form.getFieldsValue(true),
                          },
                          evidence,
                        )
                      }
                      canUpload={canMake && available}
                      onDenied={resource.deny}
                      readable={readable}
                    />
                    <Form.Item
                      name="sourceType"
                      label="证据来源声明"
                      rules={[{ required: true }]}
                    >
                      <Select
                        options={[
                          { value: "ELECTRONIC_ORIGINAL", label: "电子原文件" },
                          { value: "PAPER_SCAN", label: "纸质扫描件" },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item
                      name="originalLocation"
                      label="纸质原件位置（纸扫必填；仅组织声明）"
                    >
                      <Input maxLength={500} />
                    </Form.Item>
                    <Form.Item
                      name="singleTaxpayerConfirmed"
                      valuePropName="checked"
                    >
                      <Checkbox>
                        我确认本次材料对应一个真实纳税主体，不是批量混合登记。
                      </Checkbox>
                    </Form.Item>
                    <Form.Item
                      name="allKnownRegistrationIdentifiersDeclared"
                      valuePropName="checked"
                    >
                      <Checkbox>
                        已声明全部已知登记标识，不以换账套、年度或文件重复登记。
                      </Checkbox>
                    </Form.Item>
                    <Form.Item
                      name="organizationReviewedNotGovernmentVerified"
                      valuePropName="checked"
                    >
                      <Checkbox>
                        这是组织人工核验声明，不是政府认证或税务资格核准。
                      </Checkbox>
                    </Form.Item>
                  </>
                )}
                {group && (
                  <Form.Item
                    name="reason"
                    label="本次治理理由（原文与请求键一并保留）"
                    rules={[{ required: true }]}
                  >
                    <Input.TextArea maxLength={1000} rows={3} />
                  </Form.Item>
                )}
              </Form>
              {formError && <Alert type="error" title={formError} />}
            </Space>
          )}
        </Modal>
      </Space>
    </EvidenceWriteContext.Provider>
  );
}
