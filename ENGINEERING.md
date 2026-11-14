# Geek Mall 工程说明

## 架构

Geek Mall 采用前端多应用、Gateway 和业务域微服务结构。Docker 部署时，前端 nginx 通过同源 `/api` 代理到 Compose 网络内的 `gateway:8080`；业务服务和中间件默认不发布宿主机端口。

| 层 | 组成 |
|---|---|
| 前端应用 | `mall-web`、`admin-web`、`mobile-uniapp`、`ops-screen` |
| 接入层 | `geek-gateway` |
| 业务服务 | auth、merchant-product、inventory-fulfillment、search-recommend、trade、member、marketing、aftersale、service-center、settlement、ai |
| 基础设施 | MySQL、Redis、RabbitMQ、Elasticsearch、MinIO、Nacos |
| 可选观测 | Prometheus、Grafana、Jaeger、Blackbox Exporter |

后端使用 Java 21、Spring Boot 3.3.5、Spring Cloud 2023.0.3 和 Spring Cloud Alibaba 2023.0.3.2。前端使用 pnpm workspace 管理 Vue/TypeScript/Vite 应用和共享包。

关键工程取舍：

- 每个业务域维护自己的数据表和 Flyway 迁移，不使用跨库强事务；跨服务副作用通过幂等、事件/outbox 和补偿处理。
- 交易域保存总订单、商家子订单和订单项；商家查询从 JWT 的 `merchantId` 推导租户范围，不接受调用方指定任意租户。
- AI 只调用受控业务 API，不直连业务数据库；高风险工具在代码层拒绝。
- 默认 Compose 面向本机演示，入口和密钥策略不能直接作为公网部署方案。

## 数据库与迁移

`deploy/mysql/init-databases.sql` 创建各业务库；服务启动后由 Flyway 执行 `backend/geek-*/src/main/resources/db/migration/` 下的迁移。

| 服务 | 主要数据库 | 迁移内容 |
|---|---|---|
| auth-system | `geek_system` | 用户、角色、租户和本地演示账号 |
| merchant-product | `geek_merchant_product` | 店铺、品牌、SPU/SKU、商品治理与 Seed |
| inventory-fulfillment | `geek_inventory` | 库存、预占、outbox、履约和物流 |
| search-recommend | `geek_search` | 搜索消费记录与 Seed；商品检索由 Elasticsearch Gateway 承担 |
| trade | `geek_trade` | 购物车、总订单、商家子订单、支付、退款和事件消费 |
| member | `geek_member` | 会员、积分和地址 |
| marketing | `geek_marketing` | 优惠、秒杀、CMS、A/B 和 outbox |
| aftersale-service | `geek_aftersale` | 售后单和状态日志 |
| service-center | `geek_service` | 客服会话、工单和 SLA |
| settlement | `geek_settlement` | 结算、提现和日汇总 |
| ai | `geek_ai` | 会话、消息、知识、评测、调用日志和 provenance |

约定：

- `V*__*.sql` 是版本迁移，已发布后不应改写；新结构通过新版本迁移演进。
- `R__*.sql` 是可重复 Seed，用于本地演示和验收，不能作为生产账号初始化方式。
- 仓库根目录不保存本地数据库 dump；备份脚本输出到被 Git 忽略的 `backups/`。

## 接口与契约

外部 HTTP API 统一使用 `/api/v1/**`，由 Gateway 路由。Docker 前端部署通过同源 `/api` 访问，避免把 Gateway 直接发布到宿主机。

主要接口域：

- 认证：`/api/v1/auth/**`；
- 商品与搜索：`/api/v1/products/**`、`/api/v1/search/**`；
- 交易：`/api/v1/trade/**`、`/api/v1/payments/**`；
- 商家订单：`/api/v1/merchant/orders/**`；
- 售后：`/api/v1/aftersales/**`；
- AI：`/api/v1/ai/**`。

写请求携带 `X-Request-Id`；创建订单要求 `Idempotency-Key`。当前机器可读契约位于：

- `contracts/events/domain-event.schema.json`；
- `contracts/events/samples/*.json`；
- `contracts/http/checkout-preview.request.json`。

V1 消费者兼容 schema 保持历史约束；`producer-event.schema.json` 为当前生产者提供更严格的字段校验。库存扣减和售后退款完成事件为保留类型，当前未找到对应生产者。契约样例不是完整 OpenAPI；应同时验证实际生产者、消费者、事件 ID 幂等与 schema 兼容。

## 部署与配置

### 本地 Compose

```powershell
Copy-Item .env.example .env
docker compose -f docker-compose.yml config --quiet
docker compose up -d --build
```

默认发布：`mall-web` 8088、`admin-web` 8089、`mobile-uniapp` 8090、`ops-screen` 8091，全部绑定 `127.0.0.1`。Gateway 8080、业务端口 9001–9011 和中间件端口留在 Compose 网络。

`docker-compose.debug.yml` 只用于开发机本地调试，以回环地址发布 MySQL、Redis、RabbitMQ、Elasticsearch、MinIO 和 Nacos。`docker-compose.observability.yml` 增加 Prometheus、Grafana、Jaeger 和 Blackbox Exporter。`docker-compose.test.yml` 为测试 profile 提供资源约束。

### 环境变量

- `.env.example` 只保存可提交的占位配置；`.env` 被 Git 忽略并可能包含本地密钥。
- 真实 `LLM_API_KEY` 只能放入本地/部署环境的秘密管理系统。
- `PAYMENT_WECHAT_ENABLED` 和 `PAYMENT_ALIPAY_ENABLED` 默认 `false`；仓库没有可直接上线的真实支付凭据和回调配置。
- 共享环境应设置 `GEEK_SHARED_ENVIRONMENT=true`；Compose 的启动检查会拒绝文档化占位密钥和仅适合本机的 Nacos 关闭认证配置。

### 构建策略

日常开发可在宿主机运行 Maven/pnpm，只用 Compose 启动依赖。`backend/Dockerfile.prebuilt`、`frontend/Dockerfile.prebuilt` 和相关脚本支持把宿主机构建产物装入镜像；这些产物写入 `target/`、`dist/` 或 `.docker-stage/`，不进入版本库。

## 安全

- 认证服务签发 Access/Refresh Token；服务端鉴权和租户过滤是最终边界，前端菜单隐藏不构成授权。
- 密码通过 `Pbkdf2PasswordHasher` 处理；演示 Seed 使用公开固定密码，只能用于本地。
- Gateway/服务过滤器传播认证与请求标识；内部服务访问由公共安全组件校验。
- 创建订单、支付回调、库存预占和事件消费包含幂等/并发防护，并有针对性测试。
- 商品文档上传由 `UploadFileValidator` 执行类型、大小和文件名等检查；仍需在公网部署前补充恶意内容扫描和对象存储策略。
- AI 通过 `ToolPolicy`、`PlanValidator` 和 `AnswerGrounding` 限制工具、参数来源和输出；prompt 不能覆盖 L4 拒绝策略。
- 本地 Compose 不得通过公网隧道、路由 NAT 或云端口映射直接暴露。公网部署需要独立的入口、TLS、WAF/限流、密钥管理、网络策略和审计设计。

## 测试与质量

| 层 | 命令/入口 | 说明 |
|---|---|---|
| 后端 | `mvn -B -ntp -f backend/pom.xml -Pintegration verify` | 单元测试与基于 Docker 的 MySQL 8.4、RabbitMQ、Agent 集成测试 |
| 前端 | `cd frontend; pnpm typecheck; pnpm test; pnpm build` | 类型、Vitest 和构建 |
| Compose | `docker compose -f docker-compose.yml config --quiet` | 配置展开校验 |
| 契约 | `python scripts/event-contract-check.py` | 安装 jsonschema 后验证历史和生产者 schema、样例及缺字段反例；`scripts/contract-check.ps1` 另验证运行中服务 |
| API 主链 | `scripts/e2e-core-flow.ps1` | 交易、支付、商家隔离、售后和 AI 最小断言 |
| 并发专项 | `scripts/concurrency-verify.ps1` | 幂等下单及精确 Maven 并发/回调测试 |
| Playwright API | `cd e2e; pnpm test:api` | 不启动浏览器的 API smoke |
| 性能计划 | `perf/jmeter/geek-mall-smoke.jmx` | 小型 JMeter 计划，不代表容量结论 |
| CI | `.github/workflows/ci.yml` | 后端、前端、Compose、契约和 Trivy |

默认 Compose 下应先设置 `$env:E2E_BASE_URL='http://127.0.0.1:8088'`，让 API 验收通过商城 nginx 代理。报告和构建目录均被 Git 忽略，清理后可由命令重新生成。详细断言见 [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md)。

### AI 知识库与评测

上传接口接收 `minio://<MINIO_BUCKET>/<object-key>`。PDF、DOCX、XLSX、PNG、JPEG 和 TIFF 由 Tika 4.2.0 读取和识别，提取到有效正文后写入 `READY`。索引任务独立重试。旧版占位解析记录会被标记为 `REPARSE_REQUIRED`，可用相同来源标识重新入库。商品同步只写商品知识，价格和库存由业务接口提供。商品和平台规则查询分别读取公开的 `PRODUCT` 与 `RULE` 知识库。

配置 `EMBEDDING_URL` 为 OpenAI 兼容接口根路径，例如 `http://embedding:8000/v1`。端点须返回 1024 维向量。未配置时使用 Elasticsearch BM25。对比模型时，分别使用 `Qwen/Qwen3-Embedding-0.6B` 和 `BAAI/bge-m3`，为每个模型设置不同的 `KNOWLEDGE_INDEX`，等待索引任务完成后，以管理员令牌调用 `POST /api/v1/admin/ai/knowledge/evaluate?k=5`。响应包含 Recall@K、MRR 和 nDCG。

本轮使用 Elasticsearch 8.17.3 和真实 Ollama 模型，对 62 条合成标注问题执行 DEV/TEST 划分评测：Qwen3 为 Q8_0，BGE-M3 为 F16。基线相关问题的源级 Recall/MRR/nDCG 均为 1，但无答案候选误召回为 83.3%。仅用 DEV 校准 BGE-M3 0.62 准入阈值后，冻结 TEST 的三项检索指标为 0.889、无答案误召回为 33.3%，仍未通过暂定 20% 门槛。数据仅覆盖 3 来源、6 分块且尚无独立人工专家复核，不代表泛化表现。报告、部署配置和失败结果见 [检索评测说明](backend/geek-ai/src/main/resources/eval/retrieval-v2.md)。

独立红队用例位于 `e2e/ai-redteam`。设置隔离环境的 `AI_REDTEAM_URL` 和普通用户的 `AI_REDTEAM_TOKEN`，运行 `npx --yes promptfoo@0.125.1 eval --config e2e/ai-redteam/promptfooconfig.yaml --no-cache`。

Trivy 在安装当前版本之前扫描基准，并在每次安装前清除 CI runner 的项目 SNAPSHOT 缓存，使用同一份漏洞库扫描当前版本和基准提交。PR 以目标分支提交为基准，推送以前一个提交为基准，首次推送或手动触发使用与默认分支的 merge-base。源码依赖和实际 AI 运行镜像均阻断新增 HIGH、CRITICAL 及 HIGH 升为 CRITICAL 的发现；重复发现按最高严重级别归并。既有漏洞仍保留在报告中，门禁成功不代表零漏洞。

## 可观测性与运维

`observability/` 保存 Prometheus 抓取/告警和 Grafana provisioning/dashboard。`scripts/observability-up.ps1`、`backup.ps1|sh`、`restore.ps1|sh`、`backup-restore-drill.ps1` 提供本地演示入口。

这些配置用于本地验证，没有形成生产 SLO、值班流程、日志集中化、备份保留策略或异地灾备保证。备份恢复脚本在真实环境使用前必须进行权限、加密、保留和恢复点评审。

## 技术债

- 三个 workspace 包 `shared-utils`、`shared-icons`、`business-enums` 当前未被应用导入，但仍在 workspace 中；完整回归后再决定删除或复用。
- `scripts/` 中保留 Phase/Stage 命名的历史验证入口；应逐步合并为按能力命名的稳定命令。
- HTTP/事件契约只覆盖少量样例，缺少完整 OpenAPI、生成客户端和兼容性门禁。
- Playwright 当前是 API smoke，缺少浏览器级主路径、视觉回归和移动真机自动化。
- 微信/支付宝、发票、退款到账和仓储物流仍是模拟或骨架，不可视为生产集成。
- AI 支持配置 1024 维 Embedding 和 Elasticsearch 混合检索，未配置时回退 BM25；当前知识查询范围为公开 PRODUCT/RULE。远程模型质量、供应商故障、数据脱敏和账单对账仍需补齐。
- 本地观测与备份配置尚未达到生产 SLO、审计和灾备要求。
