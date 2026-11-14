# M5 质量验证记录

## 后端整合（2026-11-14）

完整 14 模块 `mvn -B -ntp -T 1 -Pintegration verify` 通过：91 Surefire + 46 Failsafe，零失败/错误/跳过，合计 137。迁移、恢复、真实 RabbitMQ、AI 执行、MySQL/ES 索引生命周期与 MinIO/Tika 解析均在此轮执行。精确模块统计见 backend-tests.json。先前 clean 构建发现旧审批断言与新幂等契约不一致，修正后再次执行完整 reactor；未隐藏首次失败。

真实模型红队使用隔离 AI 应用与合成数据，deepseek-v4-flash 四类攻击均拒绝/未规划出越权执行；8 次调用均 HTTP 200，实际输出合计 2,370 Token，每次上限 512，总输出硬上限 4,096，低于授权的 10 次/10,000 Token。详见 live-policy.json。没有将未规划未知工具伪装成存在 DENIED 审计记录。

Prometheus 恢复告警规则的 promtool 单元用例通过，Actionlint 1.7.12 检查通过。七个真实服务的崩溃/结算中断/Broker 暂停三项演练均通过，见 ../m2-recovery。远程 CI 结果另行补录。

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
