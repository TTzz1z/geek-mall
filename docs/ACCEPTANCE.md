# 验收说明

本文只描述仓库当前自动化能够执行的断言和明确未覆盖的范围。历史报告、HTTP 200、脚本注释或文档中的 “PASS” 都不能替代一次新的命令执行。

## 前置条件

1. 使用本地演示配置启动服务并等待健康检查完成。
2. Flyway 已执行 Seed，`e2e_tester`、`merchant_a` 和 `merchant_b` 可登录。
3. 默认 Compose 不发布 Gateway 8080；从宿主机验收时，通过商城 nginx 的 8088 访问 `/api`：

```powershell
$env:E2E_BASE_URL='http://127.0.0.1:8088'
```

如果开发者另行在宿主机启动 Gateway，可把该变量改为实际地址。不要为验收临时把完整 Compose 暴露到公网。

## 自动化覆盖

| 范围 | 实际断言 | 入口 |
|---|---|---|
| 交易主链 | 登录、搜索、加购、地址、结算预览和携带幂等键创建订单 | `scripts/e2e-core-flow.ps1`、`e2e/tests/api-core-flow.spec.ts` |
| 模拟支付 | 创建 `SIMULATED` 支付并模拟回调；回查订单 `PAID`、支付单 `SUCCESS` | 同上 |
| 支付后库存 | 正常路径断言 `DEDUCTED`；脚本把仍为 `RESERVED` 明确标记为待补偿状态，不把它伪装成已扣减 | 同上 |
| 商家订单隔离 | 所属商家可见子订单；另一个商家列表不可见且详情为 404 | `MerchantOrderTenantIsolationTest`、两套 API smoke |
| 售后最小流转 | 买家创建 `RETURN_REFUND`，所属商家审核，断言 `APPLIED -> APPROVED` 和状态日志 | 两套 API smoke |
| 下单幂等与对象权限 | 同一幂等键的并发请求只产生一个订单；非本人订单不可见 | `scripts/concurrency-verify.ps1` |
| 库存并发 | 20 个请求竞争 5 件库存，断言成功/不足数量、锁定量和日志数量，不允许意外线程异常 | `InventoryReservationConcurrencyTest`，由并发脚本精确调用 |
| 支付回调幂等 | 相同签名回调重复执行，断言 replay 及订单、事件、库存/营销/积分副作用只处理一次 | `PaymentCallbackIdempotencyTest`，由并发脚本精确调用 |
| AI 最小链路 | 至少一个成功 `tool.result`；有足够知识证据时 citation 非空；无 key 时断言可观察的规则降级 | 两套 API smoke 与 AI 单测 |
| AI 护栏 | 硬预算、L4 工具注入、空搜索 replan、slots、grounding 和 cache | `backend/geek-ai/src/test/**` |

`scripts/concurrency-verify.ps1` 会真正运行两个指定 Maven 测试，并把非零退出码计为失败；它不会仅引用历史 `mvn verify` 结果。

## 五项任务验收追踪

本次补齐工作的当前状态以 [执行清单](../tasks/todo.md) 和 [版本化证据](../tasks/evidence/m5-quality/verification.md) 为准。代码已提交到 [草稿 PR #1](https://github.com/TTzz1z/geek-mall/pull/1)，没有合并或部署。不能将全部任务描述为总验收通过：冻结 RAG TEST 的无答案候选误召回率为 33.3%，仍高于暂定 20% 门槛。

| 任务 | 本次新增且已实际执行的验证 | 当前边界 |
|---|---|---|
| 1 交易一致性 | 普通取消关闭库存/券/积分、租约失效、逐笔迟到支付返还；七个真实应用验证支付提交后终止 Trade、结算中断时退款不成功、Broker 暂停后的 Outbox 恢复 | 支付为模拟通道；旧工作器必须协调停机迁移，见 [迁移说明](recovery-rollout.md) |
| 2 Agent 治理 | Turn/加购步骤持久幂等，真实 MySQL+HTTP 的 AgentExecutionIT 11 项；真实远程模型四类合成攻击共 8 次调用、2,370 输出 Token，无越权执行 | 固定攻击样例；跨实例全局 ID/取消和完整模型安全评估仍属扩展 |
| 3 知识库 | MySQL/ES 索引生命周期、MinIO/Tika 真 PDF 与伪装文件拒绝；62 条合成问题、BM25/Qwen3 Q8_0/BGE-M3 F16 真模型对比和冻结阈值报告 | 仅 3 个来源、6 分块，标注尚无独立人工专家复核；FPR 4/12 未通过暂定门槛，不能宣称泛化质量达标 |
| 4 流式与观测 | 真实 HTTP 首字节/idle/total 超时、取消、饱和、明确终态；真实 Chrome 检查 EOF 未完成与稳定键重试；前端最新 20 测试和构建通过 | Gateway/Jaeger 的完整部署实验沿用历史证据，本轮未重新部署该链路 |
| 5 质量门禁 | 完整后端 93 Surefire + 48 Failsafe、前端 20 测试、实际七服务演练、真实模型独立验收；目标代码 1d8a21d 的 CI 四个 job 全绿，包含运行镜像差异阻断 | 修复了首轮 MinIO 镜像不可拉取；安全报告仍保留 43 条镜像基线漏洞，含无法由 POM 覆盖的 Nacos shaded Guava，不等于安全清零 |

后端计数以 [backend-tests.json](../tasks/evidence/m5-quality/backend-tests.json) 的实际运行版本为准，新增增量会另记最终结果。真实服务断言见 [M2 记录](../tasks/evidence/m2-recovery/verification.md)，模型调用和前端证据见 [M3 记录](../tasks/evidence/m3-agent/verification.md)。检索实现与质量结果见 [检索评测说明](../backend/geek-ai/src/main/resources/eval/retrieval-v2.md)。

### 上一轮历史基线（b6351e4）

以下记录属于本次补齐前的历史基线，不代表当前目标提交的测试数量或远程 CI 状态。

2026-11-14 本地全量回归覆盖后端 14 个模块，76 项 Surefire 测试与 32 项 Failsafe 集成测试全部通过，失败、错误和跳过均为 0。前端工作区类型检查、测试及生产构建通过，其中商城 10 项测试通过。11 种事件样例与必填字段反例、Trivy 差异门禁脚本及三套 Compose 配置检查通过。

首轮补齐秒杀订单持久化恢复、审批所属会话校验和安全流式输出。模型自然文本按完整短句校验后发送，上游异常或输出校验失败时通过 `message.reset` 替换为安全草稿。公开工具名称仍可进入正常 LLM 规划路径。Prompt 原文匹配针对已测试的回显形式，语义改写的完整检测能力尚未建立。

下表记录本轮和此前验证的范围。远程 CI 尚未执行，支付使用项目已有的模拟通道。知识检索支持公开 PRODUCT/RULE 范围，审批工具执行其声明的草稿生成操作。

| 任务 | 验收点与证据 | 边界与待复验 |
|---|---|---|
| 1 交易一致性与恢复 | 已有本地验证覆盖支付后工作器替换、重复回调、库存暂不可用重试、结算失败时退款保持 PROCESSING、租约到期重领、Outbox 不可路由重试及确认后发布。`TradeInfrastructureIT` 使用 MySQL 8.4 和 RabbitMQ。`PaymentCallbackIdempotencyTest` 覆盖重复回调，`SeckillRecoveryIT` 覆盖 8 项交易侧恢复。`SeckillInventoryIT` 在 MySQL 8.4 验证 11 项秒杀入口与回补场景，包括两种排序规则下的历史迁移。 | 集成套件对部分业务服务使用可控替身。支付使用模拟通道。 |
| 2 Agent 工具治理与审批 | `AgentExecutionIT` 用脚本化模型响应和真实 MySQL 验证非法/高风险工具拒绝、审批后一次生成草稿；单元测试覆盖参数策略、审批归属、过期与 fallback。草稿工具的业务效果是生成一次草稿。 | 模型是确定性替身，不代表外部模型攻击覆盖完备；本轮审批并发、回滚恢复、所属用户资源核验和实际 HTTP 安全流测试通过。 |
| 3 真实知识库 | 已有本地验证四类文档真实解析。提取有效正文后文档进入 READY，索引失败由异步任务重试。`KnowledgeMigrationsIT` 验证历史文件隔离和来源迁移。此前 Elasticsearch 与 Qwen3-Embedding-0.6B、BGE-M3 Q8 在六条标注查询上的 Recall@5/MRR 均为 1.000，nDCG@5 为 0.972。BM25 三项均为 1.000。价格和库存来自业务接口。 | 四类文件解析和检索指标沿用此前实验。当前知识查询隔离范围为公开 PRODUCT/RULE。 |
| 4 流式输出与观测 | 已有本地验证经 Gateway/nginx 的首 token、取消和饱和 503，以及 Jaeger 阶段链路；相关实现及单元测试在 `geek-ai`。 | 本轮真实 HTTP 重测通过首个校验后短句早于模型完成、上游取消及安全降级。Jaeger 与网关代理实验沿用此前结果。 |
| 5 集成测试与质量门禁 | `-Pintegration verify` 配置包含 MySQL/RabbitMQ 的 `TradeInfrastructureIT`、脚本化模型的 `AgentExecutionIT`，以及 11 服务全新迁移和交易 V4 历史数据升级的 `AllServiceMigrationsIT`。本轮 `AllServiceMigrationsIT` 两项通过，覆盖 11 服务独立库全新迁移和带已支付订单、处理中退款的 V4→V7 升级恢复任务；`scripts/event-contract-check.py` 的 11 种样例、严格生产者 schema 和缺字段反例通过；履约生产者 1 项、交易消费端 2 项契约测试通过。Promptfoo 独立四例使用运行中的应用，但 CI 设置 `LLM_ENABLED=false`。Trivy 对基准和当前版本分别构建后扫描，脚本测试覆盖路径及严重性升级。 | 跨服务实际生产者与消费者并未全部成对运行；Promptfoo 固定攻击且不检验远程 LLM。此前本地完整双扫描比较为新增 0 项、既有 661 项；本轮未重新运行完整双扫描，也未远程执行 CI。 |

迁移和故障用例需启动 Docker；在仓库根目录运行：

以下为本轮已执行且通过的定向命令（临时 Maven 日志已清理）：

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
$env:ACCEPTANCE_TEMP_DIR='D:/Temp/geekmall/final-acceptance'
D:/DevTools/maven/bin/mvn.cmd -B -ntp -f backend/pom.xml -pl geek-inventory-fulfillment -am '-Dtest=FulfillmentEventProducerContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
D:/DevTools/maven/bin/mvn.cmd -B -ntp -f backend/pom.xml -pl geek-trade -am -Pintegration '-Dtest=EventConsumerContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=AllServiceMigrationsIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
python scripts/event-contract-check.py
python scripts/test_trivy_regression_gate.py
docker compose -f docker-compose.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.test.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.observability.yml config --quiet
```

完整后端套件入口（2026-11-14 本机执行通过，认证集成测试使用本机 6379 的 Redis）：

```powershell
$env:ACCEPTANCE_TEMP_DIR='D:/Temp/geekmall/final-acceptance'
D:/DevTools/maven/bin/mvn.cmd -B -ntp -f backend/pom.xml -Pintegration clean verify
python -m pip install jsonschema==4.23.0
python scripts/event-contract-check.py
python scripts/test_trivy_regression_gate.py
```

两项保留事件 `inventory.stock.deducted.v1` 和 `aftersale.refund.completed.v1` 只保留历史类型与占位样例，尚无已确认的生产者，不能视为链路已测试。严格生产者 schema 是附加检查，历史 V1 消费者 schema 保持兼容。

首轮的 `SeckillRecoveryIT` 覆盖 8 项交易侧恢复，包括营销事件提交后的阶段恢复、重复消费、租约接管、历史订单升级和事务外远程调用。`AgentExecutionIT` 通过真实 HTTP 验证首个校验后短句早于模型完成、拒绝内容不进入增量、降级结果持久化、取消上游和异步认证，以及未登录 401、跨用户取消 403。

此前 Jaeger、Prometheus、Nginx/Gateway 及四种文档解析的本机实验记录继续适用，本轮未重新部署这些组件。既有依赖漏洞通过基准报告保留，本轮未重新下载漏洞库扫描。

### 秒杀库存迁移

公开下单和内部下单共用营销服务的 MySQL 库存事务。库存占用、秒杀订单和 Outbox 同时提交。Trade 继续通过 RabbitMQ 收到相同格式的秒杀事件，营销 Outbox 等待发布确认。

已有环境先暂停秒杀流量并停止全部旧版 `geek-marketing` 实例及其调度器，再由新版实例运行 Flyway。迁移按已提交的秒杀订单重建库存占用，排除已进入释放流程的记录。核对可售数量和待发布事件后，再恢复秒杀流量。旧版实例使用 Redis 计数，新版使用 MySQL，两者需要协调切换。上线后若需退回 Redis 方案，需要先暂停流量并按已提交订单重建 Redis 库存和限购计数。

迁移后可在营销库核对库存占用。查询返回的商品应逐一核查，超出配置库存的商品在核对期间可售数量为零。

```sql
SELECT i.session_id, i.sku_id, i.stock_qty, i.reserved_qty, COALESCE(o.held, 0) AS held
FROM marketing_seckill_item i
LEFT JOIN (
    SELECT session_id, sku_id, SUM(quantity) AS held
    FROM marketing_seckill_order
    WHERE status IN ('QUEUED','CREATED','TIMEOUT') AND quantity > 0
    GROUP BY session_id, sku_id
) o ON o.session_id = i.session_id AND o.sku_id = i.sku_id
WHERE i.reserved_qty <> COALESCE(o.held, 0) OR i.reserved_qty > i.stock_qty;
```

历史上已标为成功的退款如有疑问，按退款编号与支付渠道记录、结算流水核对。本次交易迁移恢复仍在处理中的退款任务，历史成功状态由对账结果决定是否需要修正。

### 知识库历史文档

旧解析器留下的文件记录中，带占位内容或旧解析标记的文档进入 `REPARSE_REQUIRED`。检索会按 MySQL 的最新状态筛除这些记录。管理员可将原文件放入配置的 MinIO 文档桶，使用相同知识范围、来源类型和迁移后的来源 ID 重新入库。原来没有来源 ID 的记录使用 `legacy-document-<id>`。Tika 提取正文成功后，新版本进入 `READY` 并排队同步索引。仓库中的演示手册以明文资料入库。

### CI 漏洞比较基准

首次推送新分支时，Trivy 比较该分支与默认分支的共同祖先。PR 使用目标分支 SHA，已有分支的推送使用推送前 SHA。缺少有效比较基准时门禁失败。`actionlint`、基准选择用例和 HIGH 升为 CRITICAL 的阻断用例已在本地通过。

## 执行命令

后端和前端基础验证：

```powershell
Set-Location backend
mvn -B -ntp verify
Set-Location ..\frontend
pnpm install --frozen-lockfile
pnpm typecheck
pnpm test
pnpm build
Set-Location ..
```

Compose、契约和 API 验收：

```powershell
docker compose -f docker-compose.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.test.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.observability.yml config --quiet
powershell -File scripts\contract-check.ps1

$env:E2E_BASE_URL='http://127.0.0.1:8088'
powershell -File scripts\e2e-core-flow.ps1
powershell -File scripts\concurrency-verify.ps1
Set-Location e2e
pnpm install --frozen-lockfile
pnpm test:api
```

## 结果文件

脚本在控制台输出人类可读摘要，并可生成下列机器可读文件：

- `reports/e2e-core-flow-result.json`；
- `reports/concurrency-verify-result.json`；
- `e2e/reports/playwright-api-results.json`。

这些文件是一次性测试产物，均被 Git 忽略，不应提交或作为长期文档归档。PowerShell 脚本可用 `-OutputJson` 覆盖路径，Playwright 可用 `E2E_JSON_REPORT` 覆盖路径。任何断言失败、指定 Maven 测试失败或命令非零退出，都应视为验收失败。

## 未自动化覆盖

- 浏览器级完整 UI E2E、视觉回归、跨浏览器交互和移动真机；
- 真实微信/支付宝扣款、第三方支付网络回调、退款到账和税控电子发票；
- 退货物流、仓库收货、换货/维修和完整履约；
- 生产容量、长时间稳定性、故障注入、多活容灾和备份恢复目标；
- 外部 LLM 的 SLA、全量答案质量、Milvus 写入/召回与更大规模 OCR 质量；
- CMS、秒杀、结算、提现、客服等全部业务能力的端到端闭环；
- 公网入口、TLS/WAF、密钥轮换、合规和安全渗透测试。

## 判定边界

支付验收仅使用 `SIMULATED`。AI 无 key 时只验证规则编排和本地降级，不代表远程模型已经验证。API smoke 不启动浏览器，也不等价于 UI 可用性验收。JMeter 文件只是可运行的 smoke 计划，不能据此宣称生产级性能。
