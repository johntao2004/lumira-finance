import { useEffect, useRef, useState } from "react";
import { Alert, Button, Space, Typography } from "antd";
import {
  EvidenceRequestOwner,
  evidenceRequestIdentity,
} from "./evidence-request-state";
import { EvidenceWriteContext } from "./evidence-write-context";
import type { Catalog } from "./evidence-usage-contract";
import { useEvidenceResource } from "./use-evidence-usage";
import { EvidenceDocumentPanel } from "./evidence-document-panel";
import { ExpenseAllocationPanel } from "./expense-allocation-panel";
import {
  EvidenceBlockers,
  EvidenceOriginalPicker,
  type EvidenceUiProps,
  type StoredEvidenceOriginal,
} from "./evidence-usage-ui";
type Props = {
  bookId: string;
  yearId: string;
  active: boolean;
  refreshToken: number;
  authHint: string;
  canRead: boolean;
  canOwner: boolean;
  canMake: boolean;
  canReview: boolean;
  onReadDenied: () => void;
  readable: (error: unknown) => string;
};
export function EvidenceUsagePanel(props: Props) {
  const requestOwner = useRef(new EvidenceRequestOwner());
  useEffect(() => () => requestOwner.current.clear(), []);
  useEffect(() => {
    if (!props.canRead) requestOwner.current.clear();
  }, [props.canRead]);
  function denied() {
    requestOwner.current.clear();
    props.onReadDenied();
  }
  const scope = `/finance/v1/books/${props.bookId}/years/${props.yearId}`;
  const [paused, setPaused] = useState(false),
    [error, setError] = useState(""),
    [revision, setRevision] = useState(0),
    [selectedDocument, setSelectedDocument] = useState(""),
    [selectedAllocation, setSelectedAllocation] = useState("");
  function selectDocument(id: string) {
    if (id !== selectedDocument) setSelectedAllocation("");
    setSelectedDocument(id);
  }
  const active = props.active && props.canRead && !paused;
  const effectiveRevision = revision + props.refreshToken;
  useEffect(() => {
    setError("");
    setPaused(false);
    setSelectedDocument("");
    setSelectedAllocation("");
  }, [props.refreshToken]);
  function failed(message: string) {
    setPaused(true);
    setError(`${message} 未读取成功，旧页、详情与计数已清理；请显式重新读取。`);
    setSelectedDocument("");
    setSelectedAllocation("");
  }
  const catalog = useEvidenceResource<Catalog>(
    `${scope}/evidence-usage/catalog`,
    active,
    effectiveRevision,
    denied,
    failed,
    props.readable,
  );
  const originals = useEvidenceResource<StoredEvidenceOriginal[]>(
    `${scope}/originals`,
    active,
    effectiveRevision,
    denied,
    failed,
    props.readable,
  );
  function changed() {
    setRevision((value) => value + 1);
  }
  const data = catalog.data;
  const known = data ?? catalog.retained;
  const identity = evidenceRequestIdentity(
    scope,
    props.authHint,
    known?.currentActor ?? null,
  );
  useEffect(() => {
    if (known?.currentActor.identityAvailability === "AVAILABLE")
      requestOwner.current.bind(identity);
    else if (known) requestOwner.current.clear();
  }, [identity, known?.currentActor.identityAvailability]);
  const ready = active && !!data && !!originals.data;
  if (!props.canRead) return null;
  const ui: EvidenceUiProps | null = known
    ? {
        scope,
        active: ready,
        revision: effectiveRevision,
        newUseAllowed:
          ready &&
          !!data?.eligibleForNewUse &&
          known.currentActor.identityAvailability === "AVAILABLE",
        canOwner: props.canOwner,
        canMake: props.canMake,
        canReview: props.canReview,
        actor: known.currentActor,
        originals: originals.data ?? [],
        refreshOriginals: originals.read,
        onChanged: changed,
        onReadDenied: denied,
        onReadFailure: failed,
        readable: props.readable,
      }
    : null;
  return (
    <EvidenceWriteContext.Provider
      value={{ owner: requestOwner.current, identity }}
    >
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Alert
          type="info"
          title="单据用途与费用归属（有限范围）"
          description="本片仅独立组织复核面额与费用归属上限、费用预留/确认及部分净份额更正。不是已报销、付款、入账或税务抵扣；不是银行退款或总账冲销。法律单据永久去重，实际引用仅原账套及原年度。"
        />
        {error && <Alert type="error" title={error} />}
        <Button
          onClick={() => {
            setError("");
            setSelectedDocument("");
            setSelectedAllocation("");
            setPaused(false);
            changed();
          }}
          loading={catalog.loading}
        >
          重新读取单据与费用首屏
        </Button>
        {!data && (
          <Alert
            type="warning"
            title="目录、身份与额度尚未读取成功；未知不代表零，也不代表可消费。"
          />
        )}
        {ui && (
          <>
            {data && (
              <>
                <EvidenceBlockers items={data.blockers} />
                <Typography.Text>
                  当前凭证协议：
                  {data.journalMode === "JOURNAL_PERSON_SOD"
                    ? "人员审签模式"
                    : "账号模式，本片不可新用"}
                  ；普通业务人员绑定：
                  {data.currentActor.identityAvailability === "AVAILABLE"
                    ? "可用"
                    : "不可用"}
                  。目录治理权限不代替业务岗位。
                </Typography.Text>
              </>
            )}
            <Typography.Title level={5}>实际原件上传入口</Typography.Title>
            <EvidenceOriginalPicker ui={ui} />
            <EvidenceDocumentPanel
              ui={ui}
              selectedId={selectedDocument}
              onSelected={selectDocument}
            />
            <ExpenseAllocationPanel
              ui={ui}
              documentId={selectedDocument}
              selectedId={selectedAllocation}
              onSelected={setSelectedAllocation}
            />
          </>
        )}
      </Space>
    </EvidenceWriteContext.Provider>
  );
}
