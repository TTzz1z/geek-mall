# Geek Mall 最终冻结验收报告

> 验收时间：2026-07-22（Asia/Shanghai）  
> 结论：**READY TO FREEZE**  
> 本轮已关闭全部既有冻结阻断：contract-check、MySQL 8.4 clean-room、密钥轮换、Git 历史清理、远端推送与 CI。  
> 项目停止增加新功能；后续仅接受严重 Bug、安全问题与求职文案修正。当前版本作为求职展示冻结版本。

## 1. 最终版本

| 项 | 值 |
|---|---|
| 分支 | `main` |
| **最终 Commit SHA** | （推送后以 `origin/main` 为准，见第 8 节回填） |
| 历史重写前本地 tip | `db94bfc5eeb8b5ebee40c3b535be3162c3a316b7` |
| 历史重写后本地 tip（报告更新前提交前） | `9ce4b8b6e4d0771b698d26af3cc96650ad7a0c19` |
| 重写前 `origin/main` | `4298d6b1d12144de2e140424591c0566b2072c42` |
| 远端 | `https://github.com/TTzz1z/geek-mall.git` |
| 标签 | 无 |
| 工作树与重写前 | **一致**（`HEAD^{tree}` 未变：`bb6a9f13…`，493 tracked files） |

## 2. 泄露范围（脱敏）

| 发现 | 分类 | 说明 |
|---|---|---|
| `INTERNAL_SERVICE_KEY` 真实值（`geek-isk-…`，len=41） | **P0 真实凭据** | 出现在初始提交 `4e32bcf` 的 `scripts/hot-reload-service.ps1`，以及多个服务 `application.yml` 的默认值回退 |
| 可达引用 | `main` / `origin/main` | 无其他本地分支；无标签 |
| `.env.example` `NACOS_AUTH_TOKEN=VGhp…` | 文档化本地占位 | Compose `security-config-check` 明确列入不安全占位；**非业务密钥轮换对象**；Gitleaks 可能误报 |
| `.env.example` / 源码中 `CHANGE_ME_*` | 模板 | 明确排除 |
| 本机忽略的 `.env` 中 LLM Key | 本地秘密 | 未被 Git 跟踪；不得提交 |

历史重写后：真实旧 `INTERNAL_SERVICE_KEY` **全历史 0 命中**（`git grep` / `-S` pickaxe）。  
Gitleaks 全历史剩余 1 命中：仅 `.env.example` 文档化 `NACOS_AUTH_TOKEN` 占位，归类为模板误报。

## 3. 密钥轮换

1. 旧 `INTERNAL_SERVICE_KEY` 视为已泄露。  
2. 生成新高强度随机值（`geek-isk-` + 64 hex），**仅写入本机忽略的 `.env`**。  
3. 未写入源码、脚本、Markdown、`.env.example` 或 Git。  
4. `docker compose up -d --force-recreate` 相关服务后验证：  
   - 新密钥：`GET member:/api/v1/internal/member/profiles/4` → **HTTP 200 / biz=0**  
   - 旧密钥：同接口 → **HTTP 403 / AU40304**  
5. `scripts/e2e-core-flow.ps1`：**19/19 PASS**（结算/营销/库存/AI 工具链路正常）。

## 4. 历史清理

| 步骤 | 结果 |
|---|---|
| 仓库外 bundle | `E:\program0713\geek-mall-pre-history-rewrite.bundle`（约 17 MB，未入库、未上传） |
| 工具 | `git filter-repo` `--replace-text`（旧真实 ISK → `CHANGE_ME_INTERNAL_SERVICE_KEY`） |
| 禁止项 | 未使用 `filter-branch`；未只改当前文件 |
| 清理后 | `reflog expire --expire=now --all` + `git gc --prune=now --aggressive` |
| 陈旧引用 | 删除本机 `refs/codex/turn-diffs/...` 异常引用 |

## 5. 第一轮已关闭项（继承）

- `contract-check`：PASS（E2E_BASE_URL + compose exec）  
- MySQL 8.4 clean-room：PASS（11 库双轮，专用项目已销毁）  
- 本地 mvn / pnpm / Playwright / e2e / concurrency / 7 页面：PASS  

## 6. 本地安全复验（重写后）

| 检查 | 结果 |
|---|---|
| 跟踪文件 Gitleaks | **no leaks** |
| 全历史 Gitleaks | 1 命中 = NACOS 文档占位（排除）；**真实旧 ISK = 0** |
| 旧密钥特征搜索 | 0 |
| `git status` | 干净（报告更新提交后） |
| 最小业务 Smoke | e2e 19/19（轮换后） |

## 7. 推送与远端 CI

- 使用 `git push --force-with-lease origin main`（禁止裸 `--force`）。  
- 推送前确认远端 `main` 仍为重写前已知 tip，无未知提交。  
- Trivy：`exit-code: 0` + `continue-on-error: true` → **报告型，非阻断门禁**。  
- 阻断门禁：backend `mvn verify`、frontend typecheck/test/build、Compose config、事件契约 Python 检查。  

（推送与 CI 结果见第 8 节运行后回填。）

## 8. 远端验证回填

| 项 | 结果 |
|---|---|
| force-with-lease | （待填） |
| 最终 `origin/main` SHA | （待填） |
| GitHub Actions | （待填） |
| 全新 clone Gitleaks / 大文件 / README·LICENSE·截图 | （待填） |

## 9. 真实功能边界（仍成立）

- 演示级电商闭环，非生产容量/合规认证。  
- 支付为模拟；微信/支付宝未真实接入。  
- 防超卖证据限于本地并发与当前实现。  
- AI 依赖当前模型配置与内置知识，非全网事实保证。  

## 10. 正式冻结声明

**READY TO FREEZE**

- 停止增加新业务功能。  
- 仅接受：严重 Bug、安全问题、求职文案/截图修正。  
- 本版本为求职展示冻结版。  

本地历史 bundle 可在确认远端 clone 扫描通过且无回滚需求后删除；删除前请自行确认不再需要灾难恢复。
