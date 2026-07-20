# geek-mall AI 编排设计

> 状态：Planner + 护栏 + 工具闭环已落地（可演示）  
> 范围：`backend/geek-ai` — **LLM Planner + 确定性校验 + 受控工具执行**；失败时可回落规则编排

---

## 1. 目标与非目标

### 1.1 目标

1. **开放域意图**：用户说法不可枚举时，由 LLM 完成 agent 选择、工具选择与参数填充。
2. **真实业务闭环**：推荐/对比/加购/查单/售后草稿等，仍只调用现有微服务 API，禁止虚构商品与价格。
3. **安全护栏不变**：支付、提交订单、正式退款、任意 SQL、审批等 L4 永不由 AI 执行。
4. **可降级**：LLM 超时/失败/输出非法时，回落到现有规则编排，保证演示可用。
5. **可观测可评测**：规划 JSON、工具调用、策略拒绝、降级原因全部落库，可回放。

### 1.2 非目标

- 多 Agent 并行协作框架（Crew/AutoGen 式）
- 长期记忆画像 / 跨会话个性化推荐引擎
- 真实微信/支付宝支付或电子发票开具
- 让模型直接写 SQL 或改库存

---

## 2. 能力对照

| 能力 | 规则基线 | Planner 目标 |
|------|----------|--------------|
| Agent 选择 | `AgentRouter` 关键词 | LLM 输出 `agent` + 置信度；白名单校验 |
| 工具计划 | `planTools` switch | LLM 输出 `tool_plan[]`；`ToolPolicy` 校验 |
| 参数抽取 | 正则/关键词（预算、品类） | LLM 填参 + **确定性后处理**（硬预算截断、ID 解析） |
| LLM 位置 | 工具执行后润色 | **规划前** +（可选）**回答合成** |
| 多步推理 | 单轮固定计划 | 最多 N 步：计划 → 执行 → 观察 → 再计划/结束 |
| 多轮上下文 | 基本不用历史 | 注入最近 K 轮摘要 + 会话槽位 |

---

## 3. 目标架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         AiController                             │
│              POST /messages  ·  GET /stream (SSE)                 │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                    OrchestratorService (Turn Loop)               │
│  1. ContextPacker   组装用户消息 + 历史 + 槽位 + 允许工具 schema │
│  2. PlannerGateway  LLM → PlanEnvelope (JSON)                    │
│  3. PlanValidator   白名单 / 风险 / schema / 参数规范化          │
│  4. ToolRuntime     按步执行 ToolExecutor + enrichArgs           │
│  5. Observation     压缩工具结果回填（截断）                     │
│  6. AnswerComposer  规则草稿 或 LLM 终答（必须 grounded）        │
│  7. FallbackRouter  Planner 失败 → 现有 AgentRouter+planTools    │
└─────────────────────────────────────────────────────────────────┘
         │                │                 │
         ▼                ▼                 ▼
   ModelGateway     ToolPolicy/Risk     ToolExecutor
   (chat+JSON)      L0–L4 护栏          RestClient → 微服务
```

### 3.1 组件职责

| 组件 | 职责 | 实现建议 |
|------|------|----------|
| `ContextPacker` | 打包 system/user、工具 schema、槽位、禁止事项 | 新类 |
| `PlannerGateway` | 调用 LLM，强制 JSON Plan；温度低 | 扩展 `ModelGateway` 或并列 |
| `PlanValidator` | 校验 agent/tools/args；规范化预算模式 | 新类 |
| `ToolRuntime` | 执行循环、enrich、SSE 事件 | 从 `processTurn` 拆出 |
| `AnswerComposer` | grounded 回答；禁止无来源商品 | 改造 `composeAnswer` |
| `FallbackRouter` | 复用现有 `AgentRouter`+`planTools` | 保留 |

---

## 4. 编排协议（PlanEnvelope）

LLM **必须**输出单一 JSON（`response_format: json_object` 或 prompt 强约束 + 解析失败重试 1 次）：

```json
{
  "agent": "GUIDE",
  "confidence": 0.82,
  "user_goal": "找8000以内办公笔记本",
  "slots": {
    "category_intent": "laptop",
    "budget": 8000,
    "budget_mode": "HARD_MAX",
    "use_case": "office",
    "brand_pref": null,
    "compare_candidates": []
  },
  "tool_plan": [
    {
      "name": "search_products",
      "args": {
        "keyword": "轻薄本",
        "categoryId": 110,
        "priceMin": 2800,
        "priceMax": 8000,
        "budget": 8000,
        "budgetMode": "HARD_MAX"
      },
      "reason": "按硬预算检索轻薄本"
    }
  ],
  "answer_hints": {
    "tone": "concise",
    "must_ground_on_tools": true,
    "ask_clarifying": false
  },
  "stop": true
}
```

### 4.1 字段约束

- `agent` ∈ 白名单：`GUIDE | PRODUCT_KNOWLEDGE | COMPARE | ORDER_LOGISTICS | AFTERSALE | CUSTOMER_SERVICE | OPS_ANALYTICS | PRODUCT_ENTRY`
- `tool_plan.length` ≤ 5；单轮默认建议 ≤ 3
- `budget_mode`：`HARD_MAX`（以内/不超过/以下）| `AROUND`（左右/大概）| `NONE`
- 未知工具名 → 整步丢弃并记 `PLAN_INVALID_TOOL`
- L4 工具若出现在计划中 → **保留为 DENIED 可见拒绝**（与现网一致，用于安全演示）

### 4.2 工具 Schema（给模型看的精简版）

由 `ToolPolicy` 导出 `allowedTools(agent)` + JSON Schema 片段，例如：

```json
{
  "name": "search_products",
  "risk": "L0",
  "description": "搜索在售商品。HARD_MAX 时 priceMax 必须等于用户上限。",
  "parameters": {
    "keyword": "string",
    "categoryId": "number?",
    "priceMin": "number?",
    "priceMax": "number?",
    "budget": "number?",
    "budgetMode": "HARD_MAX|AROUND|NONE?"
  }
}
```

仅下发当前 agent 允许的工具，降低乱选概率。

---

## 5. Turn 状态机

```
IDLE
  → PACK_CONTEXT
  → PLAN (LLM)
      ├─ invalid/timeout → FALLBACK_RULE_PLAN
      └─ valid → VALIDATE
  → EXECUTE_STEP (0..N)
      ├─ L4 → DENY (继续后续非 L4 或结束)
      ├─ L3 → AWAIT_CONFIRM (本轮停止执行该工具)
      ├─ L0–L2 → RUN → OBSERVE
      └─ need_more && steps < MAX → REPLAN (可选，默认关)
  → COMPOSE_ANSWER
  → STREAM_DONE
```

### 5.1 推荐默认：单次规划（Single-shot Plan）

面试/演示优先：**一次规划 → 顺序执行 → 一次回答**。

- `MAX_TOOL_STEPS = 5`
- `REPLAN_ENABLED = false`（默认）
- 打开 REPLAN 时：仅当 `stop=false` 且关键工具失败/结果为空时，带 observation 再规划 1 次

### 5.2 槽位（Slots）跨工具传递

会话级 `context_json.slots` 合并：

| 槽位 | 来源 | 下游用途 |
|------|------|----------|
| `budget` / `budget_mode` | Planner | 搜索过滤；回答话术 |
| `category_intent` / `categoryId` | Planner + 映射表 | 搜索 |
| `last_search_spu_ids` | search 结果 | compare / detail / add_cart |
| `last_sku_id` | detail | add_cart |
| `focus_order_no` | 用户或订单列表 | order_detail / logistics |

`enrichToolArgs` 保留：模型可写 `resolveFromSearch=true`，运行时解析真实 ID，**禁止模型臆造 skuId**。

---

## 6. 确定性护栏（不可由模型绕过）

### 6.1 风险策略（沿用并强化）

| 级别 | 行为 |
|------|------|
| L0 | 直接执行 |
| L1 | 需登录 |
| L2 | 需登录；可执行（加购、草稿） |
| L3 | 需用户确认；确认 API **不**代执行资金动作 |
| L4 | 永远 DENIED，仅文案引导去业务页 |

### 6.2 预算后处理（强制）

在 `PlanValidator` / `ToolExecutor.filterSearchItems`：

- `HARD_MAX`：`priceMax = budget`，丢弃 `minPrice > budget` 的商品（即使模型写成 1.25×）
- `AROUND`：允许 `budget * [0.45, 1.25]`，排序偏好 ≤ budget
- 前端商品卡：**只渲染** `search_products` / `compare_products` / `get_product_detail` 结果，禁止 citation→商品卡

### 6.3 Grounding 回答

终答必须满足：

1. 出现的 SPU# / 价格 ∈ 本轮工具结果；否则删除该提及或降级为「未检索到」
2. 系统提示明确：禁止编造 Air 14 / Vision Monitor 等演示商品
3. 若工具结果为空：只允许澄清问题或换关键词建议，不得硬推无关品类

### 6.4 输出校验伪代码

```
plan = llm.plan(context)
plan = validator.normalize(plan)          // 纠正 priceMax、去掉非法工具
for step in plan.tool_plan:
  if policy.forbidden(step): emit DENIED; continue
  if policy.needsConfirm(step): emit confirmation; continue
  args = enrich(step.args, memory)
  result = executor.run(step.name, args)
  memory.update(result)
answer = composer.grounded(plan, memory)
```

---

## 7. Prompt 设计要点

### 7.1 System（固定）

- 身份：极客商城受控导购编排器
- 只能输出 PlanEnvelope JSON
- 不得规划 L4 成功执行；若用户要求支付/退款，可把对应工具放入计划以便系统拒绝展示
- 商品必须来自后续工具；规划阶段不要编造具体 SPU 标题与价格
- `budget_mode` 判别规则写死在 prompt（以内→HARD_MAX，左右→AROUND）

### 7.2 User 包内容

1. 当前用户原话  
2. 最近 4 条对话摘要（角色+截断）  
3. 当前 slots  
4. 允许的 tools schema  
5. 类目映射提示（笔记本→110，手机→220…）  

### 7.3 温度与超时

- Planner：`temperature=0.1`，`timeout=8–12s`
- Answer（若启用 LLM 终答）：`temperature=0.3`，输入必须含工具结果摘要
- 超时 → FallbackRouter

---

## 8. SSE / API 契约（兼容现前端）

保留事件：`message.start` / `tool.call` / `tool.result` / `citation` / `confirmation.required` / `message.delta` / `message.complete` / `error`

**新增（可选，前端可忽略）：**

- `plan.created`：`{ agent, confidence, tool_plan, budget_mode, degraded? }`
- `plan.fallback`：`{ reason: "LLM_TIMEOUT"|"JSON_INVALID"|"POLICY" }`

`message.complete` 增加：

```json
{
  "planner": { "mode": "LLM"|"RULE", "model": "...", "latencyMs": 320 },
  "slots": { },
  "tools": [ ]
}
```

---

## 9. 数据模型增量

| 表/字段 | 用途 |
|---------|------|
| `ai_conversation.context_json.slots` | 跨轮槽位 |
| `ai_message.meta_json`（新列或复用） | 存 plan / planner mode |
| `ai_tool_call.arguments` | 已有；存规范化后参数 |
| `ai_model_call_log` | 已有；区分 `scene=PLAN` vs `scene=ANSWER` |

Flyway：可选 `V3__ai_orchestration.sql` 增加 `ai_message.planner_mode`、`plan_json`。

---

## 10. 与现有代码的映射

| 现有 | 变更 |
|------|------|
| `AgentRouter` | 降级为 Fallback；LLM 成功时仅作校验参考 |
| `planTools` | Fallback 唯一规划源；LLM 路径不再调用（除非 fallback） |
| `enrichToolArgs` | 保留并作为唯一 ID 解析入口 |
| `composeAnswer` | 作为 grounded 草稿；LLM 终答必须基于它或工具摘要 |
| `ModelGateway.complete` | 拆 `planJson()` / `completeAnswer()` |
| `ToolExecutor.searchProducts` | 已支持 category/price；强化 HARD_MAX |
| `AiShoppingView.extractProducts` | 已限制工具来源；保持 |

---

## 11. 分阶段落地

### Phase A — Planner MVP（高 ROI，建议先做）

1. `PlannerGateway.plan()` → PlanEnvelope  
2. `PlanValidator` + HARD_MAX 强制  
3. `processTurn`：LLM 计划优先，失败走规则  
4. SSE `plan.created` / `plan.fallback`  
5. 评测集增加：开放问法 10 条 + 硬预算 5 条 + L4 拒绝 3 条  

**验收：**

- 「8000以内笔记本」不得出现 >8000 商品  
- 「对比两款耳机」能规划 search+compare，且 spuIds 来自搜索  
- 「帮我直接支付」可见 DENIED  
- LLM 挂掉时规则路径仍可用  

### Phase B — 槽位与多轮

1. slots 持久化与合并  
2. 「把第一款加购」依赖 `last_search_spu_ids`  
3. 历史摘要注入 ContextPacker  

### Phase C — 可选 Replan

1. 空搜结果时二次规划（换关键词/放宽 AROUND 软区间，但 HARD_MAX 不放宽）  
2. Answer 改为「工具摘要 → LLM 终答」并做 grounding 扫描  

### Phase D — 观测与成本

1. Planner token/成本看板（admin）  
2. 缓存相同 utterance 的 plan（短 TTL）  
3. 内存保护：Planner 超时与并发限制  

---

## 12. 评测与回归

| 用例类型 | 例子 | 断言 |
|----------|------|------|
| 硬预算 | 8000以内笔记本 | 所有推荐 minPrice≤8000 |
| 软预算 | 8000左右办公本 | 允许轻度超支；优先 ≤8000 |
| 开放域 | 出差便携办公本 | agent=GUIDE；有 search；无充电器 citation 卡 |
| 对比 | 对比手机 | compare 的 spuIds∈search |
| 安全 | 直接支付/正式退款 | 对应工具 DENIED |
| 降级 | 关掉 LLM key | planner.mode=RULE 且仍有回答 |

沿用 `AiEvalService`，新增 dataset 行，不要用虚构成功。

---

## 13. 风险与对策

| 风险 | 对策 |
|------|------|
| 模型乱填 skuId | 禁止裸写；只允许 resolveFrom*；执行前校验 |
| 模型忽略以内 | Validator 强制 HARD_MAX 覆盖 args |
| 延迟变高 | 单次规划；timeout；规则降级 |
| OOM（Docker AI） | 限制并发；JVM 内存；必要时禁用 Replan |
| Prompt 注入要求退款 | L4 护栏不依赖模型自觉 |
| 回答编造商品 | grounding 扫描 + 草稿优先 |

---

## 14. 决策记录（建议默认）

1. **默认 Single-shot Plan**，不做多 Agent 角色扮演。  
2. **护栏在代码，不在 prompt。**  
3. **ID 解析在 enrich，不在模型。**  
4. **规则编排永久保留为 Fallback。**  
5. **前端商品卡只信工具结果。**  

---

## 15. 实现清单（Phase A 代码入口）

1. `ToolPolicy#toLlmSchemas(agent)`  
2. `PlanEnvelope` / `PlannedTool` 记录类型  
3. `PlannerGateway` + `PlanValidator`  
4. `OrchestratorService#processTurn` 接入 LLM 优先路径  
5. `application.yml`：`geek.llm.planner-enabled`、`planner-timeout-ms`、`max-tool-steps`  
6. Eval seed + 冒烟脚本  

---

## 16. Phase A 实现状态

已落地（代码）：

- `PlanEnvelope` / `PlannerGateway` / `PlanValidator`
- `ModelGateway.completeJson` + `remoteConfigured`
- `OrchestratorService`：LLM 计划优先，失败 `plan.fallback` → 规则 `planTools`
- SSE：`plan.created` / `plan.fallback`；`message.complete.plannerMode`
- 配置：`geek.llm.planner-enabled` / `planner-timeout-ms` / `planner-max-tool-steps`
- 单测：`PlanValidatorTest`（HARD_MAX、L4 注入）
- 类目软关键词：手机→`Phone`；耳机→空词 + `categoryId=510`（`Buds` 匹配不到 `NovaBuds` camelCase；空词避免默认「数码」打空）
- `ToolExecutor`：有 `categoryId` 时空白 keyword 不再回落成「数码」

### Phase A 验收复测（2026-07-20）

| 项 | 结果 |
|----|------|
| 8000以内笔记本 | 此前已 PASS（仅 ¥6999） |
| 直接支付 DENIED | 此前已 PASS |
| 关 LLM → RULE fallback | **PASS**（`plannerMode=RULE`，`LLM_DISABLED`，硬预算仍生效） |
| 对比链路 spuIds∈search | **PASS**（`对比手机` → search `[10008,10010]` + compare 同 ID） |
| 对比耳机 | **PASS**（补种子 `10013 GEEK SELECT Buds Lite` 后，search+compare 双款） |

**验收结论：Phase A 通过**（耳机目录已补齐第二款，对比可演示）。

---

## 17. Phase B 实现状态

已落地（代码）：

- `ConversationSlots`：`context_json.slots` 合并；维护 `last_search_spu_ids` / `last_sku_id`；序数解析（第一款→index 0）
- `ContextPacker`：最近 4 条 USER/ASSISTANT 摘要 + currentSlots 注入 Planner
- `PlanValidator.rewriteCartFollowUp`：有上一轮搜索 ID 时，「把第一款加购」去掉多余 search，改为 detail+cart
- `OrchestratorService.enrichToolArgs`：跨轮从 slots 解析 spuId；`message.complete.slots` 回传
- 单测：`ConversationSlotsTest`、`PlanValidatorTest#cartFollowUpRewritesToDetailAndCart`

**验收（2026-07-20 冒烟）：** 同会话「对比一下耳机」→「把第一款加购」

| 项 | 结果 |
|----|------|
| T1 search+compare | PASS（`last_search_spu_ids=[10011,10013]`） |
| T2 无二次 search | PASS |
| T2 detail+cart | PASS（`spuId=10011`=第一款，`skuId=21001`） |

**验收结论：Phase B 通过**。

---

## 18. Phase C 实现状态

已落地（代码）：

- `EmptySearchReplanner`：空搜最多再规划 1 次；`AROUND` 放宽至 `[0.3, 1.5]×budget`；`HARD_MAX` **不提高** `priceMax`；可换/清空 keyword
- SSE `plan.replan`；配置 `geek.llm.replan-enabled`（默认 true）
- `ModelGateway.completeAnswer`（scene=ANSWER, temperature=0.3）
- `AnswerGrounding`：幻觉商品 / 未 grounding 的 SPU# → 回退草稿
- 单测：`EmptySearchReplannerTest`、`AnswerGroundingTest`

**验收（2026-07-20 冒烟）：**

| 项 | 结果 |
|----|------|
| 「200以内笔记本」空搜 Replan | **PASS**（2 次 search；`priceMax` 均=200；`replan.reason=EMPTY_SEARCH`） |
| 对比耳机终答 grounding | **PASS**（含 NovaBuds/10011；无 Air 14） |

**验收结论：Phase C 通过**。

---

## 19. Phase D 实现状态

已落地（代码）：

- Admin metrics：`planCalls` / `answerCalls` / `costTotal` / `planCost` / token / `avgLatencyMs`；`planCache` / `plannerConcurrency` 统计
- call-logs 增加 `scene`（PLAN/ANSWER）与 tokens 列（admin-web）
- `PlanCache`：短 TTL（默认 60s）；key 含 utterance + scene + slots/memory 指纹
- `PlannerConcurrencyGuard`：默认最大 4 并发，超时拒收 → 规则降级
- 配置：`plan-cache-*` / `planner-max-concurrent` / `planner-acquire-timeout-ms`
- 单测：`PlanCacheTest`

**验收（2026-07-20 冒烟）：**

| 项 | 结果 |
|----|------|
| `/admin/ai/metrics` 成本/缓存/并发字段 | **PASS** |
| 相同「对比一下耳机」第二次 `planner.cached=true` | **PASS**（`PLAN_CACHE_HIT`，cacheHits=1） |

**验收结论：Phase D 通过**（编排设计 A–D 全部落地）。
