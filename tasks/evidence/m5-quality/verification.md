# M5 质量验证记录

## 后端整合（2026-11-14）

最终代码 1d8a21d 的完整 14 模块 `mvn -B -ntp -T 1 -Pintegration verify` 通过：93 Surefire + 48 Failsafe，零失败/错误/跳过，合计 141。MySQL 迁移、交易恢复、真实 RabbitMQ、AI 执行、ES UUID/候选生命周期与 MinIO/Tika 解析均实际执行。精确模块统计见 backend-tests.json。先前 clean 构建发现旧审批断言与新幂等契约不一致，修正后再次执行完整 reactor；未隐藏首次失败。

真实模型红队使用隔离 AI 应用与合成数据，deepseek-v4-flash 四类攻击均拒绝/未规划出越权执行；8 次调用均 HTTP 200，实际输出合计 2,370 Token，每次上限 512，总输出硬上限 4,096，低于授权的 10 次/10,000 Token。详见 live-policy.json。没有将未规划未知工具伪装成存在 DENIED 审计记录。

Prometheus 恢复告警规则的 promtool 单元用例通过，Actionlint 1.7.12 检查通过。七个真实服务的崩溃/结算中断/Broker 暂停三项演练均通过，见 ../m2-recovery。

## 远程 CI（2026-11-14）

[run 43595300786](https://github.com/TTzz1z/geek-mall/actions/runs/43595300786) 针对 head 1d8a21d、检查合并提交 f452ddf：backend、frontend、compose-contract-security、runtime-security 全部成功。Java 21 干净 runner 独立通过同样 141 项后端测试、七服务演练、Promptfoo 四例零失败和完整前端检查。机器记录及精确 SHA 见 ci.json，完整原始报告保留在 GitHub artifacts。

源码依赖差异检查为新增 0、既有 661 个文件/包/CVE 键。实际镜像比较为新增 0、既有 43 条（10 CRITICAL、33 HIGH）。常规 Guava 已为 33.7.2-jre，但 Nacos 2.4.2 Jar 内的 Guava 32.1.3-android 仍检出 CVE-2026-102554；没有将 POM 升级误报为 shaded 副本已修复。详见 runtime-findings.json。两个安全门禁均具有失败退出码；重复项最高严重度归并的两项 Python 回归通过。

首次 run 43593311967 的后端因已撤下的 Docker Hub MinIO 镜像拉取失败，已保留失败记录；修复真实 GHCR 仓库和 digest 后，干净 runner 的真实解析集成测试通过。缺失手动触发基准 SHA 的分支也已修正。

## 前端工作区（2026-11-14，AI 页面 19:47 修改后复验）

在 `frontend` 目录使用 `D:\DevTools\npm-global\pnpm.ps1`，并将 `D:\DevTools\node` 放在 PATH 前部：

- `pnpm typecheck`：通过，退出码 0。
- `pnpm test`：通过，退出码 0；5 个测试文件共 20 个测试（mall-web 12、admin-web 7、ops-screen 1），包括 AI 页面 2 个测试。
- `pnpm build`：通过，退出码 0；mobile-uniapp、mall-web、admin-web 和 ops-screen 均构建完成。构建提示 Sass legacy JS API 弃用；admin-web 和 ops-screen 提示压缩后 chunk 大于 500 kB。

## 配置和脚本（2026-11-14）

- `docker compose -f docker-compose.yml config --quiet`：通过。
- 基础文件叠加 `docker-compose.test.yml` 的 `config --quiet`：通过。
- 基础文件叠加 `docker-compose.observability.yml` 的 `config --quiet`：通过。
- `D:\DevTools\Python3.14\python.exe scripts/test_trivy_regression_gate.py`：最终通过，2 个测试。
- `D:\DevTools\Python3.14\python.exe scripts/event-contract-check.py`：通过，11 个事件样例。
