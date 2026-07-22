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
- 外部 LLM 的 SLA、全量答案质量、真实 Embedding、Milvus 写入/召回、OCR/VL；
- CMS、秒杀、结算、提现、客服等全部业务能力的端到端闭环；
- 公网入口、TLS/WAF、密钥轮换、合规和安全渗透测试。

## 判定边界

支付验收仅使用 `SIMULATED`。AI 无 key 时只验证规则编排和本地降级，不代表远程模型已经验证。API smoke 不启动浏览器，也不等价于 UI 可用性验收。JMeter 文件只是可运行的 smoke 计划，不能据此宣称生产级性能。
