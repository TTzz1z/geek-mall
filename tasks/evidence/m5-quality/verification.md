# M5 质量验证记录

## 前端工作区（2026-11-14，AI 页面 19:47 修改后复验）

在 `frontend` 目录使用 `D:\DevTools\npm-global\pnpm.ps1`，并将 `D:\DevTools\node` 放在 PATH 前部：

- `pnpm typecheck`：通过，退出码 0。
- `pnpm test`：通过，退出码 0；5 个测试文件共 20 个测试（mall-web 12、admin-web 7、ops-screen 1），包括 AI 页面 2 个测试。
- `pnpm build`：通过，退出码 0；mobile-uniapp、mall-web、admin-web 和 ops-screen 均构建完成。构建提示 Sass legacy JS API 弃用；admin-web 和 ops-screen 提示压缩后 chunk 大于 500 kB。

## 配置和脚本（2026-11-14）

- `docker compose -f docker-compose.yml config --quiet`：通过。
- 基础文件叠加 `docker-compose.test.yml` 的 `config --quiet`：通过。
- 基础文件叠加 `docker-compose.observability.yml` 的 `config --quiet`：通过。
- `D:\DevTools\Python3.14\python.exe scripts/test_trivy_regression_gate.py`：通过，1 个测试。
- `D:\DevTools\Python3.14\python.exe scripts/event-contract-check.py`：通过，11 个事件样例。
