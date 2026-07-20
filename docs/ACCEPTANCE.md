# 验收说明（公开版）

面向作品集展示的验收摘要。本地可复现命令见根目录 `README.md` / `ENGINEERING.md`。

## 1. 验收范围

| 范围 | 内容 |
|---|---|
| 交易主链 | 搜索 → 加购 → 结算 → 下单 → 模拟支付 → 订单已支付 |
| 库存 | 预占 / 扣减；并发下不超卖（单测 + 脚本） |
| 支付幂等 | 模拟支付重复回调不重复入账 |
| 权限 | 管理端鉴权；商家数据隔离 |
| AI 导购 | 可检索/对比/建议；禁止代付、代下单、正式退款 |
| 工程 | Compose 配置、契约样例、前端构建、CI 骨架 |

**明确不在范围**：真实微信/支付宝扣款、税控发票、浏览器级全量 UI E2E、生产级容灾。

## 2. 核心闭环与验证入口

| 闭环 | 如何验证 |
|---|---|
| 浏览—下单—模拟支付 | `scripts/e2e-core-flow.ps1` 或 `scripts/showcase-phase5.ps1` |
| 展示面无演示账号硬编码 | `scripts/scan-demo-hints.py` |
| 后端质量 | `cd backend && mvn -B -ntp verify` |
| 前端质量 | `cd frontend && pnpm typecheck && pnpm test && pnpm build` |
| 契约 | `scripts/contract-check.ps1`（CI 同步校验） |
| 并发幂等 | `scripts/concurrency-verify.ps1` |
| 安全抽查 | `scripts/security-verify.ps1` |
| AI 回归 | `scripts/ai-regression.ps1` |

近期本地重跑：`showcase-phase5` / `e2e-core-flow` / `stage4-verify` / `stage5-verify` 均为通过（交易订单可达 `PAID`）。

## 3. 已知边界

1. 支付通道为 `SIMULATED`，页面明示无真实扣款。  
2. AI 依赖可选的外部 LLM；无 Key 时可降级规则编排。  
3. OCR/VL、Milvus 为可替换骨架。  
4. 低配机器不建议全量 `docker compose build`；推荐 `demo-up.ps1` + 本机构建。  
5. 移动端完整 AI 对话体验以 PC 商城为主。

## 4. 账号说明

公开文档**不列出密码**。本地演示角色（买家 / 管理员 / 商家 / API 验收）见 `README.md`「演示账号」一节；界面不预填、不展示凭据。
