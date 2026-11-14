# 恢复能力迁移与运行

本轮在隔离环境验证迁移和故障恢复，没有对用户业务库执行迁移或部署。支付仍使用模拟通道。部署多实例、接入真实支付渠道、私有知识库 ACL 不在本轮范围。

MinIO Docker Hub 镜像在干净 GitHub runner 上已无法拉取。本机此前缓存的实际分发为 coollabsio 的 GHCR 镜像，已检查远程 manifest 并固定同一摘要 `sha256:a4938f37f1be1841b8e7b627ad0207b265345fd0d063e42d7410c78af0e63e68`，Compose 与解析集成测试均直接引用真实仓库，没有新增本地 retag 映射。它是原 RELEASE.2025-04-22T22-12-26Z 的分发，不是平台升级或安全清零；[MinIO 上游仓库](https://github.com/minio/minio) 已归档且改为源码分发，长期平台替换/升级仍需独立处理。

## 迁移顺序

先停止旧 Trade 和 AI 实例及其工作器，再迁移并启动新版本；这不是支持新旧二进制混跑的滚动升级。支付入口停机期间由支付渠道保留并重试通知，恢复后核对支付单和渠道流水。

1. Inventory 使用携带 orderId 的释放入口，为取消订单写入关闭记录。
2. Marketing V5、Member V3 建立订单级关闭标记，并从既有资产预留状态回填。启动新版本以拒绝关闭后到达的冻结。
3. Trade V8 将迟到支付按 paymentNo 建立返还记录和任务，旧订单级任务退役。历史异常流水缺失或非模拟渠道进入人工处理；不自动修改历史成功退款。取消订单的库存和资产释放任务会重新执行一次，以补齐关闭标记。V9 增加用户范围的加购步骤键。
4. AI V7 建立 Turn、计划和步骤检查点；V8 将已有 READY 文档的最新版本保留为 ACTIVE，其余版本退役，首次轮询建立索引代次。V9 增加真实 ES index_uuid；旧 NULL 身份在首次轮询时触发一次受控重建。

启动后核对 Flyway 校验结果、过期 RUNNING 任务、MANUAL 状态、迟到返还和最老积压。历史成功退款通过只读 `trade_historical_refund_reconciliation` 视图供运营与渠道核实，不能仅依据本地 SUCCESS 自动修账。

旧 Trade 将 PAYMENT_AFTER_CLOSE 的 business_no 理解为订单号，新版本理解为支付单号；旧 AI 不识别候选激活状态。迁移后不能直接回退到旧二进制。需要修复后向前发布；知识文档版本回退使用新版本的管理员激活接口。

## RabbitMQ 隔离与重放

新版本不修改既有业务队列的声明参数，保留已入队消息。监听器有限重试后使用 mandatory 和 publisher confirm 发布至持久 DLQ，确认失败则保留原消息重试。部署时可执行 `scripts/rabbit-recovery-policies.py --container <broker>`，安装仅匹配三条关键队列的死信策略。

SYSTEM_ADMIN 可通过 Trade/Inventory 的 dead-letters 管理接口重放白名单事件类型；每批最多 100 条。事件 ID 保持不变，确认原队列收到后才确认 DLQ 消息。非法事件留在 DLQ，需修复内容后处理。重放审计记录用户、队列、数量和请求 ID；消费者数据库去重仍是最终幂等保障。

Prometheus 恢复告警已添加规则用例。缺少 Broker/数据库观测时指标返回 NaN，不伪装成积压为零。

## Agent 和知识库

调用者必须为同一意图重试保留 requestId。未传键的旧接口会生成新键，不提供跨请求的重试保证。已准备的失败 Turn 使用原键恢复，或显式放弃后发起新意图。取消保留已提交业务步骤，不能撤销已经完成的加购。

加购事务只保存步骤标记和数量写入，远程商品/库存读取位于事务外。完整响应生成失败时，已提交标记阻止再次加购，重试继续生成并持久化响应。并发返回使用同一份已保存结果。

知识候选全部分块完成索引后才激活，失败期间旧 ACTIVE 版本继续使用。显式下架由 SQL 立即拦截。SQL 成功标记绑定物理 ES UUID，每轮写入后也核对身份；删除或同名自动重建会使全部旧标记失效，再从 SQL 重建映射和分块。`KNOWLEDGE_INDEX` 必须是 AI 独占的物理派生索引，不能指向共享索引或别名；身份变化时程序可能删除并重新创建此派生索引。Compose 仅禁止 `geek-knowledge-*` 自动创建，其他业务索引保持原行为；即使外部服务允许自动创建，UUID 防护仍有真实故障回归。

真实 embedding 服务需独立部署。Ollama 示例为 `EMBEDDING_URL=http://<host>:11434/v1`、`EMBEDDING_MODEL=bge-m3:latest`、`KNOWLEDGE_REQUIRE_SEMANTIC_GATE=true`。HF 兼容服务须使用其实际模型名称。0.62 是当前 BGE-M3 DEV 校准值，更换模型或语料后需重新校准；TEST 无答案误召回 33.3% 尚未达到暂定 20% 门槛。未配置 embedding 的 BM25 兼容模式也未通过该质量门槛。

`LLM_HEADER_TIMEOUT_MS`、`LLM_FIRST_BYTE_TIMEOUT_MS`、`LLM_BODY_IDLE_TIMEOUT_MS`、`LLM_TOTAL_TIMEOUT_MS` 与 `LLM_MAX_OUTPUT_TOKENS` 均传入 Compose 的 AI 服务。日志与 Trace 不保存 Prompt 或用户 JWT。

## 安全门禁范围

源码依赖和实际 AI 运行镜像都比较基线与当前提交，新增 HIGH/CRITICAL 以及 HIGH 升为 CRITICAL 会使 CI 失败。运行镜像基线使用对应旧提交的 Jar 和 Dockerfile，以同一扫描标签和同一漏洞数据库比较 OS 与嵌套 Jar；报告包含实际镜像 ID 和提交标签。现有漏洞仍需平台治理，门禁成功不代表零漏洞。

首次 AI 镜像扫描发现 10 条 CRITICAL、33 条 HIGH，其中 Guava CVE-2026-102554 未被源码扫描列出。本轮将常规 Maven Guava 依赖固定到 [33.7.2-jre](https://github.com/google/guava/releases/tag/v33.7.2) 并通过完整验证；运行镜像仍从 `nacos-client-2.4.2.jar` 检出内嵌的 Guava 32.1.3-android，这种 shaded 副本不能被 POM 覆盖。基线与当前镜像均为 43 条发现，新增为 0，不宣称该 CVE 已彻底修复；Nacos/平台升级保留为独立工作。运行镜像差异检查已经具有阻断作用。
