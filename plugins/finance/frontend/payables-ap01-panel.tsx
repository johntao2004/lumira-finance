import { useState } from "react";
import { Alert, Button, Space, Tabs } from "antd";
import { useApContext, useApResource } from "./payables-ap01-resource";
import {
  ApBases,
  ApOpenItems,
  ApPayables,
  ApPreparation,
  ApPayableDetailView,
} from "./payables-ap01-sections";
import {
  ApBlockers,
  ApJson,
  type ApStoredOriginal,
} from "./payables-ap01-views";
import type {
  Catalog,
  BasisCommandResult,
  PayableCommandResult,
  Producer,
} from "./payables-ap01-contract";
import type { Account } from "./types";
import { apProducerMatches } from "./payables-ap01-rules";
import type { ApUi } from "./payables-ap01-ui";
export type PayablesProps = {
  active: boolean;
  canOwner: boolean;
  canMake: boolean;
  canReview: boolean;
  canPost: boolean;
  restriction: string | null;
  onChanged?: () => void;
};
export function PayablesPanel(props: PayablesProps) {
  const c = useApContext();
  return <PayablesWorkbench key={c.identity} {...props} />;
}
function PayablesWorkbench(props: PayablesProps) {
  const c = useApContext(),
    catalog = useApResource<Catalog>(
      `${c.scope}/payables-ap01/catalog`,
      props.active,
    ),
    originals = useApResource<ApStoredOriginal[]>(
      `${c.scope}/originals`,
      props.active,
    ),
    accounts = useApResource<Account[]>(`${c.scope}/accounts`, props.active),
    [tab, setTab] = useState("prepare"),
    [payableId, setPayableId] = useState(""),
    [basisId, setBasisId] = useState(""),
    [parentId, setParentId] = useState(""),
    [result, setResult] = useState<
      PayableCommandResult | BasisCommandResult | null
    >(null);
  const data = catalog.data,
    ready =
      props.active && c.ready && !!data && !!originals.data && !!accounts.data,
    actorAvailable = data?.currentActor.identityAvailability === "AVAILABLE";
  const ui: ApUi = {
    canOwner: props.canOwner && !!actorAvailable && ready,
    canMake: props.canMake && !!actorAvailable && ready,
    canReview: props.canReview && !!actorAvailable && ready,
    canPost: props.canPost && !!actorAvailable && ready,
    newUse: !!data?.eligibleForNewUse && !props.restriction,
    originals: originals.data ?? [],
    accounts: accounts.data ?? [],
    changed: () => {
      c.refresh();
      props.onChanged?.();
    },
    refreshOriginals: originals.read,
  };
  function onResult(value: PayableCommandResult | BasisCommandResult) {
    setResult(value);
    if ("payable" in value.current) {
      setPayableId(value.current.payable.id);
      setTab("payables");
    } else {
      setBasisId(value.current.basis.id);
      setTab("bases");
    }
  }
  return (
    <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
      <Alert
        type="info"
        title="普通供应商服务费用 → 真实应付会计确认"
        description="真实全父份额、独立会计额度与两行非现金分录。批准不等于记账；真实记账才产生总账、会计用途占用和本系统应付未结项。不是报销完成、付款、银行验真、税务抵扣或完整企业子账。"
      />
      {props.restriction && <Alert type="warning" title={props.restriction} />}
      <Button
        onClick={() => {
          c.refresh();
        }}
      >
        显式重新读取应付工作台
      </Button>
      {!ready && (
        <Alert title="当前会计确认工作台未读取成功或正在核对范围、身份及模式，旧详情与动作已隐藏；未知不按零处理。" />
      )}
      {catalog.error && <Alert type="error" title={catalog.error} />}{" "}
      {data && <ApBlockers items={data.blockers} />}{" "}
      {result && (
        <ApJson
          value={result.commandReceipt}
          label="已收到命令的历史收据（不是当前状态；结果以重读详情为准）"
        />
      )}
      {data && accounts.data && (
        <Tabs
          destroyOnHidden
          activeKey={tab}
          onChange={setTab}
          items={[
            {
              key: "prepare",
              label: "真实父份额与准备",
              children: (
                <ApPreparation
                  ui={ui}
                  onResult={onResult}
                  initialId={parentId}
                  onSelected={setParentId}
                />
              ),
            },
            {
              key: "bases",
              label: "独立会计额度复核",
              children: (
                <ApBases
                  initialId={basisId}
                  onSelected={setBasisId}
                  ui={ui}
                  onResult={onResult}
                />
              ),
            },
            {
              key: "payables",
              label: "应付审签与真实记账",
              children: (
                <ApPayables
                  initialId={payableId}
                  onSelected={setPayableId}
                  ui={ui}
                  onResult={onResult}
                />
              ),
            },
            {
              key: "items",
              label: "本系统未结项与对照",
              children: <ApOpenItems />,
            },
          ]}
        />
      )}
    </Space>
  );
}
export function PayablesJournal({
  journalId,
  rawType,
  props,
}: {
  journalId: string;
  rawType: string | undefined;
  props: PayablesProps;
}) {
  const c = useApContext(),
    resolver = useApResource<Producer>(
      `${c.scope}/payables-ap01/journals/${journalId}/producer`,
      props.active,
    ),
    catalog = useApResource<Catalog>(
      `${c.scope}/payables-ap01/catalog`,
      props.active,
    ),
    originals = useApResource<ApStoredOriginal[]>(
      `${c.scope}/originals`,
      props.active,
    ),
    accounts = useApResource<Account[]>(`${c.scope}/accounts`, props.active),
    [result, setResult] = useState<
      PayableCommandResult | BasisCommandResult | null
    >(null),
    link = resolver.data?.journalId === journalId ? resolver.data : null,
    available = catalog.data?.currentActor.identityAvailability === "AVAILABLE";
  const ui: ApUi = {
    canOwner: props.canOwner && !!available,
    canMake: props.canMake && !!available,
    canReview: props.canReview && !!available,
    canPost: props.canPost && !!available,
    newUse: !!catalog.data?.eligibleForNewUse && !props.restriction,
    originals: originals.data ?? [],
    accounts: accounts.data ?? [],
    refreshOriginals: originals.read,
    changed: () => {
      c.refresh();
      props.onChanged?.();
    },
  };
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title="此凭证由真实AP协议生成，只能通过应付来源审签/记账/退出或全额反向，不能沿用人工NORMAL或损益结转协议。"
      />
      <Button onClick={() => void resolver.read()}>重读真实应付凭证来源</Button>
      {resolver.error && <Alert type="error" title={resolver.error} />}{" "}
      {!apProducerMatches(link, journalId, rawType) || !link?.payableId ? (
        <Alert
          type="warning"
          title={
            link?.reason ??
            "真实应付来源尚未读取成功；仅可查阅原凭证，不回退到旧动作。"
          }
        />
      ) : (
        catalog.data &&
        originals.data &&
        accounts.data && (
          <ApPayableDetailView
            key={link.payableId}
            id={link.payableId}
            ui={ui}
            onResult={(v) => setResult(v)}
          />
        )
      )}{" "}
      {result && (
        <ApJson value={result.commandReceipt} label="本次真实命令历史收据" />
      )}
    </Space>
  );
}
