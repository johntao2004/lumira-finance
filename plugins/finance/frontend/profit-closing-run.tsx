import { Alert, Button, Space } from "antd";
import type { RunDetail, JournalProducerLink } from "./profit-closing-contract";
import { ProfitClosingProof } from "./profit-closing-views";
import {
  ProfitClosingActions,
  type ProfitClosingProps,
} from "./profit-closing-actions";
import { useProfitClosingResource } from "./profit-closing-resource";
export type ClosingSeed = {
  run: RunDetail;
  action: "residual" | "reversal" | "rebuild";
};
export function ProfitClosingRun({
  ui,
  runId,
  onPrepare,
}: {
  ui: ProfitClosingProps;
  runId: string;
  onPrepare?: (seed: ClosingSeed) => void;
}) {
  const detail = useProfitClosingResource<RunDetail>(
    runId ? `${ui.scope}/profit-closing/runs/${runId}` : "",
    ui.active && !!runId,
    ui.revision,
    ui.onReadDenied,
    ui.readable,
  );
  const run = detail.data?.id === runId ? detail.data : null;
  const mayPrepare =
    ui.active &&
    ui.newUseAllowed &&
    ui.canMake &&
    run?.currentActor.identityAvailability === "AVAILABLE" &&
    !!run.currentActor.personId;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Button onClick={() => void detail.read()} loading={detail.loading}>
        重新读取结转运行与证明
      </Button>
      {detail.error && <Alert type="error" title={detail.error} />}{" "}
      {!run && (
        <Alert
          type="warning"
          title="结转运行及内容尚未读取成功，旧证明已隐藏；不能据此审签或记账。"
        />
      )}
      {run && <ProfitClosingProof run={run} />}
      <ProfitClosingActions
        ui={ui}
        run={run}
        onResult={() => void detail.read()}
      />
      {run && onPrepare && (
        <Space wrap>
          <Button
            disabled={
              !mayPrepare ||
              !run.allowedActions.includes("propose-residual") ||
              run.status !== "POSTED" ||
              run.producerKind === "TRANSFER_REVERSAL" ||
              !run.postProof
            }
            onClick={() => onPrepare({ run, action: "residual" })}
          >
            读取当前残余并准备合法追加
          </Button>
          <Button
            disabled={
              !mayPrepare ||
              !run.allowedActions.includes("propose-full-reversal") ||
              run.producerKind === "TRANSFER_REVERSAL" ||
              run.status !== "POSTED" ||
              run.economicEffect !== "ACTIVE" ||
              !run.postProof ||
              !!run.postedReversalRunId
            }
            onClick={() => onPrepare({ run, action: "reversal" })}
          >
            读取一次全额反向预览（最近有效结转优先）
          </Button>
          <Button
            disabled={!mayPrepare || run.status !== "CANCELLED"}
            onClick={() => onPrepare({ run, action: "rebuild" })}
          >
            明确以前次已终结尝试重建新操作
          </Button>
        </Space>
      )}
    </Space>
  );
}
export function ProfitClosingJournal({
  ui,
  journalId,
  rawType,
  onPrepare,
}: {
  ui: ProfitClosingProps;
  journalId: string;
  rawType: string | undefined;
  onPrepare: (seed: ClosingSeed) => void;
}) {
  const resolver = useProfitClosingResource<JournalProducerLink>(
    `${ui.scope}/profit-closing/journals/${journalId}/producer`,
    ui.active && rawType !== "NORMAL",
    ui.revision,
    ui.onReadDenied,
    ui.readable,
  );
  const link = resolver.data?.journalId === journalId ? resolver.data : null;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Button onClick={() => void resolver.read()} loading={resolver.loading}>
        重新读取系统凭证来源关系
      </Button>
      {resolver.error && <Alert type="error" title={resolver.error} />}{" "}
      {!link && (
        <Alert
          type="warning"
          title="系统凭证来源关系尚未读取成功，不能使用旧人工凭证操作。"
        />
      )}
      {link && link.resolution !== "FOUND" && (
        <Alert
          type="warning"
          title={
            link.reason ??
            "本凭证没有当前支持的受控结转运行证明，仅可查阅；不会改走旧人工审签。"
          }
        />
      )}{" "}
      {link?.resolution === "FOUND" && link.runId && link.producerKind && (
        <ProfitClosingRun
          key={link.runId}
          ui={ui}
          runId={link.runId}
          onPrepare={onPrepare}
        />
      )}
    </Space>
  );
}
