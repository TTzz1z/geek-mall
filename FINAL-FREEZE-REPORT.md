# Geek Mall 最终冻结验收报告

> 验收时间：2026-07-22（Asia/Shanghai）  
> 结论：**本地组合格可提交（LOCAL FREEZE COMMIT CREATED）**；远端冻结仍 **BLOCKED**  
> 本轮已关闭：`contract-check` 拓扑阻断、MySQL 8.4 clean-room 未验证项。  
> 仍未关闭：Git 历史密钥尚未清理；当前新提交尚未经过远端 CI。

## 1. 验收对象与版本

- 分支：`main`
- 上一远端基线：`4298d6b1d12144de2e140424591c0566b2072c42`
- 本轮本地冻结提交：`d8d6c52b665f611f9b6e2a18a963849d7bf52dea`（`chore: finalize geek mall portfolio release`）
- 远端：`TTzz1z/geek-mall`
- 本轮 **禁止** `git push`，**禁止** 重写 Git 历史。工作区在回填本 SHA 后保持与该提交一致。

## 2. 运行环境

| 项目 | 实际环境 |
|---|---|
| OS | Windows 11，`10.0.26100`，PowerShell |
| 时区 | Asia/Shanghai（UTC+08:00） |
| Java / Maven | 本地 `mvn verify` PASS（Java 25 运行告警仍在，非本轮失败） |
| Node / pnpm | `pnpm typecheck/test/build` PASS |
| Docker Compose | 演示项目 `geek-mall`；clean-room 项目 `geek-mall-flyway-cleanroom` |
| API 入口 | `E2E_BASE_URL=http://127.0.0.1:8088` |

## 3. 汇总

| 状态 | 数量 / 说明 |
|---|---|
| 本轮关闭的阻断 | 2：`contract-check`、MySQL 8.4 clean-room |
| 仍未关闭的冻结阻断 | 2：Git 历史密钥；新提交无远端 CI |
| 本地门禁 | 全部要求项 PASS（历史 Gitleaks 命中按已知 P0 保留，不计入“跟踪树泄露”） |

## 4. 本轮关闭项

### 4.1 contract-check

- 脚本：`scripts/contract-check.ps1`
- 删除默认 `localhost:900x` / 宿主机 `8080` 直连假设
- 对外：`E2E_BASE_URL`（默认 `http://127.0.0.1:8088`）经 mall-web nginx
- 对内：`docker compose exec` 在 Compose 网络用服务名检查 OpenAPI/health
- 静态：`contracts/`、Gateway 路由、nginx `/api` 代理
- 未执行项不计 PASS；失败退出码非 0；输出控制台摘要 + `reports/contract-check-result.json`
- **实测：PASS（17/17）**

### 4.2 MySQL 8.4 clean-room

- 脚本：`scripts/flyway-cleanroom-verify.ps1`
- 独立项目名：`geek-mall-flyway-cleanroom`；全新 MySQL 8.4 + 专用卷
- 不触碰演示栈 `geek-mall` 数据卷
- 11 库从空初始化；分三批启动应用；首轮 + 重启第二轮
- 禁止 repair / baselineOnMigrate 掩盖 / 手工改历史表 / dump 冒充
- 结束后 `down -v` 清理专用容器与卷
- **实测：PASS**；`reports/flyway-cleanroom-result.json` overall=PASS，cleanedUp=True

#### 11 库迁移结果（首轮 / 第二轮一致）

| 数据库 | versioned | repeatable | failed | maxV |
|---|---:|---:|---:|---|
| geek_system | 2 | 1 | 0 | 2 |
| geek_merchant_product | 2 | 1 | 0 | 2 |
| geek_inventory | 3 | 1 | 0 | 3 |
| geek_search | 1 | 1 | 0 | 1 |
| geek_trade | 4 | 1 | 0 | 4 |
| geek_member | 2 | 2 | 0 | 2 |
| geek_marketing | 3 | 2 | 0 | 3 |
| geek_aftersale | 1 | 0 | 0 | 1 |
| geek_service | 1 | 1 | 0 | 1 |
| geek_settlement | 1 | 1 | 0 | 1 |
| geek_ai | 3 | 1 | 0 | 3 |

#### Seed 快照（首轮 = 第二轮）

`accounts=7 products=13 skus=14 stockRows=18 addresses=4 couponTemplates=2 knowledgeDocuments=4 knowledgeChunks=6`

## 5. 本地验收命令结果

| # | 检查 | 结果 |
|---|---|---|
| 1 | `git diff --check` | PASS |
| 2 | 三套 `docker compose ... config --quiet` | PASS ×3 |
| 3 | `mvn -B -ntp verify` | PASS（临时 Redis `geek-freeze-redis`） |
| 4 | `pnpm typecheck` | PASS |
| 5 | `pnpm test` | PASS（18） |
| 6 | `pnpm build` | PASS |
| 7 | Playwright `pnpm test:api` | PASS（2/2） |
| 8 | `scripts/e2e-core-flow.ps1` | PASS（19/19） |
| 9 | `scripts/concurrency-verify.ps1` | PASS（5/5） |
| 10 | `scripts/contract-check.ps1` | PASS（17/17） |
| 11 | `scripts/flyway-cleanroom-verify.ps1` | PASS |
| 12 | 7 个关键页面 HTTP 200 | PASS（7/7） |
| 13 | Gitleaks（当前跟踪文件树，不含忽略 `.env`） | PASS（no leaks） |
| — | Gitleaks（全历史 `--all`） | FAIL 2 hits（已知历史密钥，仍为远端冻结阻断） |

`.env`：存在、被 `.gitignore` 忽略、不在 `git ls-files`、不在暂存区。

## 6. 仓库外备份

- 位置：`E:\program0713\geek-mall-freeze-backup-20260722-194117`
- 内容：`git-diff-binary.patch`、`git-status-porcelain.txt`、`untracked-files.txt`
- **未**加入仓库

## 7. 仍未关闭的冻结阻断

1. **P0：Git 历史仍含实际 `INTERNAL_SERVICE_KEY`**（Gitleaks 历史 2 命中）。需轮换密钥并用评审过的历史清理方案处理；本轮禁止改写历史。
2. **当前新提交尚未 push，无对应远端 CI run。** 基线 `4298d6b` 的 Actions 曾全绿，不能替代本提交。

## 8. 推荐表述边界

- 可表述：本地完成 contract-check 拓扑对齐、MySQL 8.4 11 库 clean-room 双轮验证，并完成本地冻结提交。
- 不得表述：已生产上线、历史密钥已清零、本提交 GitHub Actions 已绿、真实支付已接入。

## 9. 最终建议

- **允许**作为本地 portfolio 冻结提交保留与演示。
- **在完成历史密钥清理 + push 后 CI 全绿之前，不得标记为远端最终冻结版本。**
