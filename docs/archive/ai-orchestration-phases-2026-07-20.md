# AI 编排 Phase A–D 历史记录（已归档）

> 本文从原 `docs/ai-orchestration.md` 移出。内容记录 2026-07-20 的分阶段计划与当次复测结论，不等同于持续集成的最新结果。当前设计和可复现验收见 `../ai-orchestration.md` 与 `../ACCEPTANCE.md`。

## Phase A — Planner MVP

计划内容：

1. `PlannerGateway.plan()` 产生 `PlanEnvelope`。
2. `PlanValidator` 和 `HARD_MAX` 执行强制校验。
3. `processTurn` 优先使用 LLM 计划，失败走规则。
4. SSE 增加 `plan.created` / `plan.fallback`。
5. 评测集增加开放问法、硬预算和 L4 拒绝用例。

当时记录的代码入口包括 `PlanEnvelope`、`PlannerGateway`、`PlanValidator`、`ModelGateway.completeJson`、`OrchestratorService` 和 `ToolExecutor`。2026-07-20 的记录称：硬预算、直接支付拒绝、无 LLM 的 RULE fallback、手机/耳机对比链路通过人工冒烟；耳机种子补充了 `10013 GEEK SELECT Buds Lite`。

## Phase B — 槽位与多轮

计划内容：

1. slots 持久化与合并。
2. “把第一款加购”读取 `last_search_spu_ids`。
3. 历史摘要注入 `ContextPacker`。

当时记录的实现包括 `ConversationSlots`、`ContextPacker`、`PlanValidator.rewriteCartFollowUp`、`OrchestratorService.enrichToolArgs` 以及相关单测。2026-07-20 的一次性冒烟记录称：同会话“对比一下耳机”后“把第一款加购”没有二次搜索，并解析到第一款商品。

## Phase C — 空结果 Replan 与 Grounding

计划内容：

1. 空搜索最多二次规划一次；`AROUND` 可放宽，`HARD_MAX` 不放宽。
2. Answer 采用“工具摘要 → LLM 终答”，并执行 grounding 扫描。

当时记录的实现包括 `EmptySearchReplanner`、SSE `plan.replan`、`ModelGateway.completeAnswer`、`AnswerGrounding` 以及对应单测。2026-07-20 的一次性记录称：“200 以内笔记本”保持 `priceMax=200`，耳机终答没有混入未 grounding 的商品。

## Phase D — 观测与成本

计划内容：

1. 展示 Planner/Answer 的 Token、估算成本和延迟。
2. 对相同输入增加短 TTL plan cache。
3. 增加 Planner 超时和并发限制。

当时记录的实现包括 Admin metrics、`PlanCache`、`PlannerConcurrencyGuard`、调用日志的真实/估算 Token 标记和相关配置。2026-07-20 的一次性记录称管理指标字段可见，重复输入命中 plan cache。

## 原阶段验收样例

| 类型 | 样例 | 原断言 |
|---|---|---|
| 硬预算 | 8000 以内笔记本 | 推荐结果不超过硬上限 |
| 软预算 | 8000 左右办公本 | 可轻度放宽，但优先预算内 |
| 对比 | 对比手机/耳机 | compare 的商品 ID 必须来自 search |
| 安全 | 直接支付/正式退款 | 对应工具必须拒绝 |
| 降级 | 关闭 LLM key | `planner.mode=RULE` 且仍返回可解释结果 |

## 原设计决策

1. 默认 Single-shot Plan，不做多 Agent 角色扮演。
2. 护栏写在代码中，不依赖 prompt。
3. ID 在运行时 enrich 阶段解析，不信任模型裸填。
4. 规则编排永久保留为 fallback。
5. 前端商品卡只使用工具结果。

这些原则仍可在现行代码中核对，但本文中的 “PASS” 只表示当时记录，不作为今天的验收证据。
