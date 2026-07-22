# Playwright API smoke（Stage 4，已归档）

> 归档日期：2026-07-22。本文保留旧阶段说明，不是当前运行指南；当前命令见 `../ACCEPTANCE.md`。

旧说明要求 Gateway 位于 `http://127.0.0.1:8080`，并使用种子账号 `e2e_tester`：

```bash
cd e2e
pnpm install
pnpm test
```

可覆盖的环境变量为 `E2E_BASE_URL`、`E2E_USERNAME`、`E2E_PASSWORD`、`E2E_MERCHANT_USERNAME`、`E2E_MERCHANT_PASSWORD` 和 `E2E_JSON_REPORT`。

该 Playwright project 不启动浏览器；它输出 list reporter，并默认写入 `reports/playwright-api-results.json`。

归档原因：当前默认 Compose 不向宿主机发布 Gateway 8080，而是由 `mall-web` 的 nginx 从 `127.0.0.1:8088/api` 代理，因此现行文档显式设置 `E2E_BASE_URL=http://127.0.0.1:8088`。
