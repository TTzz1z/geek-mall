# ENGINEERING.md · 极客商城

## 1. 架构总览

```text
浏览器 / H5
    │
    ▼
mall-web :8088 · admin-web :8089 · mobile :8090 · ops :8091
    │  (nginx 同源反代 /api → gateway)
    ▼
geek-gateway :8080
    ├─ auth-system :9001
    ├─ merchant-product :9002
    ├─ inventory-fulfillment :9003
    ├─ search-recommend :9004
    ├─ trade :9005
    ├─ member :9006
    ├─ marketing :9007
    ├─ aftersale :9008
    ├─ service-center :9009
    ├─ settlement :9010
    └─ ai :9011
中间件：MySQL · Redis · RabbitMQ · Nacos · Elasticsearch · MinIO
可选：Prometheus / Grafana / Jaeger（见 observability/）
```

**关键决策**

- 交易最终一致性优先，避免大范围分布式事务框架
- AI **不直连业务库**，只调受控 HTTP API；L4 动作（支付/下单/正式退款）拒绝执行
- 支付通道可插拔；演示默认 `SIMULATED`
- 日常构建：本机 Maven / pnpm + Compose 跑中间件；避免低配机全量镜像内编译

AI 编排详设：[docs/ai-orchestration.md](docs/ai-orchestration.md)

---

## 2. 目录结构

| 路径 | 说明 |
|---|---|
| `backend/geek-*` | 各微服务（含 Flyway 迁移与种子） |
| `backend/geek-common` | 公共 Web / 安全组件 |
| `frontend/apps/*` | mall / admin / mobile-uniapp / ops-screen |
| `frontend/packages/*` | 共享类型与工具 |
| `scripts/` | 启动、热加载、冒烟、备份 |
| `contracts/` | 领域事件 Schema + 样例（CI / `contract-check`） |
| `e2e/` | Playwright API 冒烟 |
| `observability/` | Prometheus / Grafana 配置 |
| `perf/jmeter/` | JMeter 计划（报告目录 gitignore，运行时生成） |
| `deploy/` | 前端 nginx 等部署片段 |
| `docs/` | 验收说明、AI 设计、截图 |
| `assets/product-covers/` | 可选 SVG 封面源（运行时以 mall `public/covers` JPEG 为准） |

Compose 变体（`docker-compose.dev.yml` / `test` / `observability`）保留在**仓库根目录**，与 CI、脚本默认路径一致，降低相对路径风险。

---

## 3. 视觉语言（摘要）

商城前台为浅色产品摄影风：冰川灰画布、低圆角、克制阴影；强调色 Geek 橙 `#FF5A1F` 用于价格与主操作。实现以 `frontend/apps/mall-web` 样式变量为准。

---

## 4. 数据与模型要点

- 权威订单：`geek_trade.trade_order` / `trade_sub_order` / `trade_order_item`
- 接口前缀：`/api/v1/trade/**`、`/api/v1/payments/**`
- `geek_system.demo_merchant_order` 仅为早期样例，**不是**真实交易源
- 商品封面 / 店铺 logo 使用同源 `/covers/*.jpg`
- 本地演示角色见 `README.md`；**勿用于生产**

---

## 5. 接口与契约

- 对外统一经 Gateway：`http://127.0.0.1:8080/api/v1/**`
- 前端 Docker 部署时 `VITE_API_BASE_URL` 置空，由 nginx 反代 `/api`
- 契约：`contracts/events/domain-event.schema.json` + `samples/`；`contracts/http/checkout-preview.request.json`
- 写操作普遍要求 `X-Request-Id`；下单强制 `Idempotency-Key`

---

## 6. 配置与密钥

| 文件 | 用途 |
|---|---|
| `.env.example` | 可提交模板（占位密钥） |
| `.env` | **本地私密**（gitignore），含 DB / JWT / LLM Key |

- 真实 `LLM_API_KEY` 只放 `.env`
- `PAYMENT_WECHAT_ENABLED` / `PAYMENT_ALIPAY_ENABLED` 默认 `false`

---

## 7. 部署与运行

### 7.1 一键演示

```powershell
powershell -File scripts\demo-up.ps1
```

### 7.2 AI 本机热加载（可选）

```powershell
docker compose stop ai
# .env: AI_SERVICE_URI=http://host.docker.internal:9011
powershell -File scripts\hot-reload-service.ps1 -Module geek-ai -LocalPort 9011
docker compose up -d --no-deps --force-recreate gateway
```

预构建 AI 镜像：`scripts\build-ai-image.ps1`

### 7.3 前端镜像（推荐 prebuilt）

```powershell
cd frontend
$env:VITE_API_BASE_URL=''
pnpm --filter @geek-mall/mall-web build
cd ..
docker build -f frontend/Dockerfile.prebuilt --build-arg APP=mall-web -t geek-mall-mall-web frontend
docker compose up -d mall-web --no-deps --force-recreate
```

### 7.4 移动端

- 工程：`frontend/apps/mobile-uniapp`
- H5：`pnpm --filter @geek-mall/mobile-uniapp build:h5`（8090）
- 微信：导入 `dist/build/mp-weixin`；`manifest.json` 中 appid 默认留空；不接真实微信登录/支付

### 7.5 观测与备份

```powershell
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --no-build
powershell -File scripts\backup.ps1   # 输出 backups/（gitignore）
```

---

## 8. 测试与验收

| 类型 | 命令 |
|---|---|
| 后端 | `cd backend; mvn -B -ntp verify` |
| 前端 | `cd frontend; pnpm typecheck; pnpm test; pnpm build` |
| Compose | `docker compose -f docker-compose.yml config` |
| 契约 | `scripts\contract-check.ps1` |
| 交易主链 | `scripts\e2e-core-flow.ps1` |
| 展示面 + 主链 | `scripts\showcase-phase5.ps1` |
| 公开验收说明 | [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) |
| CI | `.github/workflows/ci.yml` |

脚本生成的报告写入 `reports/` 或 `perf/reports/`（均 gitignore，目录由运行时创建）。

---

## 9. 安全与质量护栏

- Access + Refresh Token；写接口后端鉴权为准
- 库存条件更新预占；支付模拟幂等
- AI 工具白名单 + 风险分级
- 演示痕迹扫描：`scripts/scan-demo-hints.py`
