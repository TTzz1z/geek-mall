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

2026-11-14 完成本地整合验收。后端 14 个模块全部构建成功，76 项单元测试与 25 项集成测试全部通过，失败、错误和跳过均为 0。商城前端类型检查、10 项测试及生产构建通过。11 种事件样例与必填字段反例、Trivy 差异门禁脚本及三套 Compose 配置检查通过。

本轮补齐秒杀持久化恢复、营销释放重放、审批所属会话校验和安全流式输出。模型自然文本按完整短句校验后发送，上游异常或输出校验失败时通过 `message.reset` 替换为安全草稿。公开工具名称仍可进入正常 LLM 规划路径。Prompt 原文匹配针对已测试的回显形式，语义改写的完整检测能力尚未建立。

下表记录本轮和此前验证的范围。远程 CI 尚未执行，支付使用项目已有的模拟通道。知识检索支持公开 PRODUCT/RULE 范围，审批工具执行其声明的草稿生成操作。

| 任务 | 验收点与证据 | 边界与待复验 |
|---|---|---|
| 1 交易一致性与恢复 | 已有本地验证覆盖支付后工作器替换、重复回调、库存暂不可用重试、结算失败时退款保持 PROCESSING、租约到期重领、Outbox 不可路由重试及确认后发布。`TradeInfrastructureIT` 使用 MySQL 8.4 和 RabbitMQ；`PaymentCallbackIdempotencyTest` 覆盖重复回调。 | 依赖故障以可控替身模拟；不等同于多个真实业务服务停机后的端到端故障演练。本轮历史升级、8 项秒杀恢复和 5 项营销释放测试全部通过。 |
| 2 Agent 工具治理与审批 | `AgentExecutionIT` 用脚本化模型响应和真实 MySQL 验证非法/高风险工具拒绝、审批后一次生成草稿；单元测试覆盖参数策略、审批归属、过期与 fallback。草稿工具的业务效果是生成一次草稿。 | 模型是确定性替身，不代表外部模型攻击覆盖完备；本轮审批并发、回滚恢复、所属用户资源核验和实际 HTTP 安全流测试通过。 |
| 3 真实知识库 | 已有本地验证四类文档真实解析；仅提取有效正文后 READY；解析失败同步返回且不入库，索引失败由异步任务重试。Elasticsearch 与 Qwen3-Embedding-0.6B、BGE-M3 Q8 在六条标注查询上的 Recall@5/MRR 均为 1.000，nDCG@5 为 0.972；BM25 三项均为 1.000。价格和库存来自业务接口。 | 上述是此前本机实验，不是本轮重新运行的结果；小样本只支持回归判断。当前知识查询隔离范围为公开 PRODUCT/RULE。 |
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

完整后端套件入口（2026-11-14 本机执行通过）：

```powershell
$env:ACCEPTANCE_TEMP_DIR='D:/Temp/geekmall/final-acceptance'
D:/DevTools/maven/bin/mvn.cmd -B -ntp -f backend/pom.xml -Pintegration verify
python -m pip install jsonschema==4.23.0
python scripts/event-contract-check.py
python scripts/test_trivy_regression_gate.py
```

两项保留事件 `inventory.stock.deducted.v1` 和 `aftersale.refund.completed.v1` 只保留历史类型与占位样例，尚无已确认的生产者，不能视为链路已测试。严格生产者 schema 是附加检查，历史 V1 消费者 schema 保持兼容。

本轮关键集成测试包括 `SeckillRecoveryIT` 8 项、`SeckillReleaseRecoveryIT` 5 项、`AgentExecutionIT` 6 项、`AllServiceMigrationsIT` 2 项、`TradeInfrastructureIT` 4 项。秒杀覆盖已提交营销事件之后的阶段恢复、重复消费、租约接管、历史订单升级和事务外远程调用。营销覆盖 Redis 成功后数据库失败、响应丢失、并发释放和过期扫描竞态。AI 通过真实 HTTP 验证首个校验后短句早于模型完成、拒绝内容不进入增量、降级结果持久化、取消上游和异步认证，以及未登录 401、跨用户取消 403。

秒杀入口的 Redis 抢购扣减与营销事件入库之前的崩溃窗口仍属于既有待改进范围。此前 Jaeger、Prometheus、Nginx/Gateway 及四种文档解析的本机实验记录继续适用，本轮未重新部署这些组件。既有依赖漏洞通过基准报告保留，本轮未重新下载漏洞库扫描。

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
