# Geek Mall AI 编排

本文描述仓库当前 AI 导购实现。历史 Phase A–D 计划和一次性复测记录已移至 [`archive/ai-orchestration-phases-2026-07-20.md`](archive/ai-orchestration-phases-2026-07-20.md)。

## 目标与边界

AI 服务用于把自然语言需求转换为受控的查询、对比、加购建议、查单和售后草稿等动作。它只通过已有业务 API 获取或修改数据，不直连其他服务的数据库。

以下能力不在当前范围内：

- 代替用户提交订单、支付或正式退款；
- 任意 SQL、直接改库存或绕过业务服务；
- 视觉语言模型对文档图片的理解；
- 对外部模型的可用性、答案质量或账单金额作保证。

## 当前链路

```text
用户消息
  -> ContextPacker（近期消息与 slots）
  -> PlannerGateway（可选远程 LLM）
       -> PlanValidator（schema、预算、风险、ID 来源）
       -> 失败时 AgentRouter 规则计划
  -> ToolPolicy + ToolExecutor（JSON Schema、权限、业务工具）
       -> 空搜索最多一次 EmptySearchReplanner
       -> ToolApprovalService（待审批草稿）
  -> grounded 草稿
       -> ModelGateway 上游 SSE
       -> 校验自然文本片段，失败时回退草稿
  -> SSE 事件与 message.complete
```

主要代码位于 `backend/geek-ai/src/main/java/com/geekmall/ai/`：

| 组件 | 职责 |
|---|---|
| `OrchestratorService` | 组织单轮执行、工具调用、replan、终答和事件输出 |
| `PlannerGateway` / `ModelGateway` | 调用 OpenAI-compatible 模型；不可用时返回可识别的降级状态 |
| `PlanEnvelope` / `PlanValidator` | 表达并校验计划，限制步骤数、预算和参数来源 |
| `AgentRouter` | 无远程计划时的确定性规则 fallback |
| `ToolPolicy` / `ToolExecutor` | 校验 JSON Schema、权限和风险等级，再调用业务 API |
| `ToolApprovalService` | 验证会话归属，持久化审批与过期状态，由后台任务幂等执行已确认草稿 |
| `ConversationSlots` / `ContextPacker` | 保存上一轮搜索结果等 slots，并压缩近期上下文 |
| `EmptySearchReplanner` | 空搜索时最多重规划一次；硬预算不放宽 |
| `AnswerGrounding` | 阻止终答引用工具结果之外的商品或标识 |
| `PlanCache` / `PlannerConcurrencyGuard` | 短时计划缓存与 Planner 并发保护 |

## 计划、槽位与工具

计划由 `PlanEnvelope` 表达，包含 agent、预算语义、计划步骤和 fallback 原因等信息。模型输出必须经过 `PlanValidator`，不能直接执行。

关键约束：

- “以内/不超过”属于硬上限，replan 不得提高 `priceMax`；
- 模型不能凭空指定 `skuId` / `spuId`，运行时只能从搜索结果或会话 slots 解析；
- `max-tool-steps` 限制单轮工具数量；
- 空搜索最多 replan 一次，避免循环；
- `last_search_spu_ids`、`last_sku_id` 等 slots 支持“第一款”“把它加购”类后续表达。

当前白名单以 `ToolPolicy` 为准。查询、比较和低风险草稿可执行；支付、提交订单、正式退款等 L4 动作由代码拒绝，不能通过 prompt 覆盖。

需确认的工具先进入 `PENDING`。所属用户确认后，工作器从 `CONFIRMED` 恢复执行，草稿写入与审批完成在同一个本地事务中提交。未确认的请求过期后持久化为 `EXPIRED`。

## Grounding 与 RAG 真实性

知识文件从配置的 MinIO 文档桶读取，由 Tika 提取 PDF、DOCX、XLSX 正文和图片 OCR 文本。解析成功后保存文档来源、版本和知识范围，异步同步 Elasticsearch 索引。

Elasticsearch 按 `PRODUCT` 或 `RULE` 过滤 BM25 与向量召回。配置 `EMBEDDING_URL` 后，Java 侧用 RRF 融合两路结果，所有候选还须通过语义相似度准入，再复核数据库范围、状态和激活版本。默认模型为 BGE-M3，阈值 0.62；embedding 故障返回空证据。未配置向量服务时保留 BM25 兼容模式；设 `KNOWLEDGE_REQUIRE_SEMANTIC_GATE=true` 可要求缺失后端也返回空证据。本次冻结 TEST 的无答案候选误召回率仍为 33.3%，尚未达到暂定 20% 门槛，不能将兼容模式或当前实验称为质量全面通过。实验结果见 [检索评测说明](../backend/geek-ai/src/main/resources/eval/retrieval-v2.md)。

商品卡、价格和库存使用业务工具结果。自然文本流经 `AnswerStreamGuard` 校验再发送，校验失败时替换成已基于业务证据生成的草稿。

## API 与事件

前端通过 Gateway 调用 `POST /api/v1/ai/conversations/{conversationNo}/stream`，通过 `DELETE /api/v1/ai/conversations/{conversationNo}/turns/{turnId}` 取消当前用户的上游请求。主要 SSE 事件如下。

- `plan.created`：计划已创建；
- `plan.fallback`：远程计划不可用，使用规则计划；
- `plan.replan`：空结果触发一次受限重规划；
- `tool.result`：工具执行结果；
- `message.delta`：已校验的上游文本片段；
- `message.reset`：用降级结果替换已显示的增量；
- `message.complete`：本轮完成，包含 planner mode 等摘要。

调用方必须容忍新增事件字段，并以 `message.complete` 作为终态；具体字段以 `AiController` 和 `OrchestratorService` 当前返回为准。

## 配置与观测

配置集中在 `backend/geek-ai/src/main/resources/application.yml` 与 `.env.example`。关键开关包括 Planner/Replan、超时、最大步骤、plan cache、并发上限和可选 LLM endpoint/key。

流式线程池限制工作线程与排队容量，饱和时返回 503。观测配置可向 Jaeger 导出 Agent Turn 及 Planner、Tool、RAG、Answer 各阶段的 span，并向 Prometheus 暴露阶段耗时。Trace 记录阶段和运行结果，用户文本和内部 Prompt 留在业务调用内。

模型返回 OpenAI-compatible `usage` 时记录上游 Token；缺失时标记为估算。管理端展示的成本始终是本地估算，不是供应商账单。调用日志通过 Flyway 迁移保存 provenance 和真实/估算标记。

无 `LLM_API_KEY` 时，系统应走可观察的 `RULE + LLM_DISABLED` 降级路径；这只能证明本地 fallback，不代表远程模型已经验证。

## 测试锚点

| 风险 | 自动化锚点 |
|---|---|
| 硬预算、L4 工具注入 | `PlanValidatorTest`、`ToolPolicyTest` |
| 多轮 slots | `ConversationSlotsTest` |
| 空搜索 replan | `EmptySearchReplannerTest` |
| 商品 grounding | `AnswerGroundingTest` |
| plan cache | `PlanCacheTest` |
| RAG citation / 降级 | `HybridRagServiceTest`、`scripts/e2e-core-flow.ps1`、`e2e/tests/api-core-flow.spec.ts` |

可复现命令和未覆盖范围见 [`ACCEPTANCE.md`](ACCEPTANCE.md)。

## 技术债

- 扩大现有标注查询集并为持续评测建立质量阈值；
- 对远程模型增加更完整的超时、限流、熔断、数据脱敏与供应商故障演练；
- 为 SSE 契约建立机器可校验的 schema 和兼容策略；
- 将估算成本与供应商账单对账后再用于财务判断。
