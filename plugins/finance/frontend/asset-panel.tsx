import { useEffect, useRef, useState } from "react";
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Modal, Space, Table, Tag, Typography } from "antd";
import type { AssetApi, AssetCard, AssetDifference, AssetPlan } from "./asset-contract";
import { isDefinitiveAssetRejection, validateAssetPendingCommand } from "./asset-rules";

// scopeKey MUST include authenticated account + tenant/book/year/period. No Number money conversion.
export function AssetPanel({ api, scopeKey, canMake, canReview, canPost }: {api: AssetApi; scopeKey: string; canMake: boolean; canReview: boolean; canPost: boolean}) {
  return <AssetWorkbench key={scopeKey} storageKey={`finance.asset.pending.${scopeKey}`} api={api} canMake={canMake} canReview={canReview} canPost={canPost} />;
}
function AssetWorkbench({api, storageKey, canMake, canReview, canPost}: {api: AssetApi; storageKey: string; canMake: boolean; canReview: boolean; canPost: boolean}) {
  const [cards,setCards]=useState<AssetCard[]>([]),[plan,setPlan]=useState<AssetPlan|null>(null),[planId,setPlanId]=useState("");
  const [error,setError]=useState(""),[busy,setBusy]=useState(false),[cursor,setCursor]=useState<string|null>(null),[refresh,setRefresh]=useState(0);
  const [differences,setDifferences]=useState<AssetDifference[]|null>(null),[selected,setSelected]=useState<AssetCard|null>(null),[mode,setMode]=useState<"depreciate"|"estimate">("depreciate");
  const [form]=Form.useForm(); const alive=useRef(true);
  // Ambiguous network/5xx responses retain the exact command and key for explicit retry; definitive 4xx rollbacks clear it.
  const pending=useRef<{action: Parameters<AssetApi["command"]>[0]; body: Record<string,unknown>; key:string}|null>(null);
  useEffect(()=>{alive.current=true;try{const saved=sessionStorage.getItem(storageKey);if(saved){pending.current=validateAssetPendingCommand(saved);setError("存在尚未确认的资产操作，请使用原请求重试。");}}catch(e){setError(String(e));}return()=>{alive.current=false;};},[storageKey]);
  useEffect(()=>{const abort=new AbortController();setError("");setCards([]);api.cards(cursor,abort.signal).then(v=>{if(!abort.signal.aborted)setCards(v);}).catch(e=>{if(!abort.signal.aborted)setError(String(e));});return()=>abort.abort();},[api,cursor,refresh]);
  useEffect(()=>{if(!planId){setPlan(null);return;}const abort=new AbortController();setPlan(null);api.detail(planId,abort.signal).then(v=>{if(!abort.signal.aborted)setPlan(v);}).catch(e=>{if(!abort.signal.aborted)setError(String(e));});return()=>abort.abort();},[api,planId,refresh]);
  async function send(action: Parameters<AssetApi["command"]>[0],body:Record<string,unknown>,retry=false) {
    if(busy)return;
    if(pending.current && !retry){setError("上一请求结果未确认，请先用原请求重试。");return;}
    pending.current ??= {action,body,key:crypto.randomUUID()};
    const command=pending.current;setBusy(true);setError("");
    try{sessionStorage.setItem(storageKey,JSON.stringify(command));const r=await api.command(command.action,command.body,command.key);sessionStorage.removeItem(storageKey);pending.current=null;if(alive.current){setPlanId(r.planId);setSelected(null);setRefresh(x=>x+1);}}
    catch(e){if(isDefinitiveAssetRejection(e)){sessionStorage.removeItem(storageKey);pending.current=null;}if(alive.current)setError(e instanceof Error ? e.message : String(e));}
    finally{if(alive.current)setBusy(false);}
  }
  async function reconcile(){const abort=new AbortController();setBusy(true);setDifferences(null);try{const rows=await api.reconciliation(abort.signal);if(alive.current)setDifferences(rows);}catch(e){if(alive.current)setError(String(e));}finally{if(alive.current)setBusy(false);}}
  const action=(name:"approve"|"post"|"cancel")=>{if(plan)void send(name,{planId:plan.plan.id,expectedVersion:plan.version,reason:"资产工作台独立处理"});};
  return <Space orientation="vertical" style={{width:"100%"}}>
    <Alert type="info" title="资产会计子账（有界人民币 AS01 / AS02）" description="固定资产与有限寿命无形资产的取得、直线法折旧/摊销、未来估计变更、处置及完整反向冲销分别审签记账并与总账实时对账。税务扣除资格未核验（NOT_VERIFIED）。" />
    {error&&<Alert type="error" title={error} />}
    {pending.current&&<Button disabled={busy} onClick={()=>{const p=pending.current!;void send(p.action,p.body,true);}}>使用原请求键重试未确认操作</Button>}
    <Card title="资产卡片">
      <Table<AssetCard> rowKey="id" dataSource={cards} pagination={false} scroll={{x:950}} columns={[
        {title:"资产",dataIndex:"name"},{title:"状态",dataIndex:"status"},{title:"原值",dataIndex:"cost"},{title:"累计折旧 / 摊销",dataIndex:"accumulated"},
        {title:"已计提期数",dataIndex:"elapsed"},{title:"最近期间",dataIndex:"lastPeriod"},
        {title:"操作",render:(_,c)=><Space><Button disabled={!canMake||busy||c.status!=="IN_USE"} onClick={()=>{setSelected(c);setMode("depreciate");form.resetFields();}}>本期测算</Button><Button disabled={!canMake||busy||c.status!=="IN_USE"} onClick={()=>{setSelected(c);setMode("estimate");form.resetFields();}}>变更未来估计</Button></Space>}
      ]}/>
      <Space><Button disabled={!cursor||busy} onClick={()=>setCursor(null)}>返回首页</Button><Button disabled={cards.length<100||busy} onClick={()=>setCursor(cards.at(-1)!.id)}>下一页</Button><Button disabled={busy} onClick={()=>setRefresh(x=>x+1)}>刷新</Button><Button disabled={busy} onClick={()=>void reconcile()}>与总账对账</Button></Space>
    </Card>
    <Card title="计划及独立批准">
      <Input.Search placeholder="输入服务器返回的资产计划编号" onSearch={setPlanId} enterButton="读取计划" />
      {plan&&<><Descriptions items={[{key:"id",label:"编号",children:plan.plan.id},{key:"state",label:"状态",children:<Tag>{plan.status}</Tag>},{key:"digest",label:"内容摘要",children:<Typography.Text copyable>{plan.digest}</Typography.Text>},{key:"journal",label:"实际凭证",children:plan.journal??"无凭证（尚未记账或无金额变更）"}]}/>
      <Table rowKey={r=>r.after.id} pagination={false} dataSource={plan.plan.changes} columns={[{title:"资产",render:(_,r)=>r.after.name},{title:"计提额",render:(_,r)=>r.trace?.recognized??"不适用"},{title:"舍入差额",render:(_,r)=>r.trace?.roundingAdjustment??"不适用"},{title:"剩余基数",render:(_,r)=>r.trace?.remaining??"不适用"}]}/>
      {plan.plan.estimate&&<Space align="start"><Table rowKey="period" size="small" dataSource={plan.plan.estimate.oldSchedule} columns={[{title:"原政策期间",dataIndex:"period"},{title:"金额",dataIndex:"amount"}]}/><Table rowKey="period" size="small" dataSource={plan.plan.estimate.newSchedule} columns={[{title:"新政策期间",dataIndex:"period"},{title:"金额",dataIndex:"amount"}]}/></Space>}
      <Space><Button disabled={busy||!canReview||plan.status!=="SUBMITTED"} onClick={()=>action("approve")}>独立批准</Button><Button disabled={busy||!canPost||plan.status!=="APPROVED"} onClick={()=>action("post")}>执行批准计划</Button><Button disabled={busy||!canMake||!["SUBMITTED","APPROVED"].includes(plan.status)} onClick={()=>action("cancel")}>撤销未执行计划</Button></Space></>}
    </Card>
    {differences&&<Card title="资产子账与总账（按所选截止日）"><Table<AssetDifference> rowKey="account" dataSource={differences} columns={[{title:"科目",dataIndex:"account"},{title:"子账借方净额",dataIndex:"subledgerDebitNet"},{title:"总账借方净额",dataIndex:"ledgerDebitNet"},{title:"差额",dataIndex:"difference"},{title:"来源水位",dataIndex:"watermark"}]}/></Card>}
    <Modal open={!!selected} title={mode==="estimate"?"未来估计变更及影响预览":"本期折旧 / 摊销测算"} confirmLoading={busy} onCancel={()=>{if(!busy)setSelected(null);}} onOk={()=>void form.validateFields().then(v=>{if(selected)void send(mode,{...v,assetId:selected.id,expectedVersion:selected.version});})}>
      <Form form={form} layout="vertical"><Form.Item name="reason" label="依据和理由" rules={[{required:true,max:1000}]}><Input.TextArea /></Form.Item>
      {mode==="estimate"&&<><Form.Item name="remainingMonths" label="批准的剩余月数" rules={[{required:true}]}><InputNumber min={1} max={1200} precision={0}/></Form.Item><Form.Item name="residual" label="人民币残值" rules={[{required:true,pattern:/^(0|[1-9]\d{0,19})(\.\d{1,2})?$/}]}><Input /></Form.Item><Form.Item name="assessmentId" label="已独立批准的政策评估编号" rules={[{required:true}]}><Input /></Form.Item></>}
      </Form>
    </Modal>
  </Space>;
}
