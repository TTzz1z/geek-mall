# 恢复能力迁移与运行

本轮在隔离环境验证迁移和故障恢复，没有对用户业务库执行迁移或部署。支付仍使用模拟通道。部署多实例、接入真实支付渠道、私有知识库 ACL 不在本轮范围。

## 迁移顺序

先停止旧 Trade 和 AI 实例及其工作器，再迁移并启动新版本；这不是支持新旧二进制混跑的滚动升级。支付入口停机期间由支付渠道保留并重试通知，恢复后核对支付单和渠道流水。

1. Inventory 使用携带 orderId 的释放入口，为取消订单写入关闭记录。
2. Marketing V5、Member V3 建立订单级关闭标记，并从既有资产预留状态回填。启动新版本以拒绝关闭后到达的冻结。
3. Trade V8 将迟到支付按 paymentNo 建立返还记录和任务，旧订单级任务退役。历史异常流水缺失或非模拟渠道进入人工处理；不自动修改历史成功退款。取消订单的库存和资产释放任务会重新执行一次，以补齐关闭标记。V9 增加用户范围的加购步骤键。
4. AI V7 建立 Turn、计划和步骤检查点；V8 将已有 READY 文档的最新版本保留为 ACTIVE，其余版本退役，首次轮询建立索引代次。

启动后核对 Flyway 校验结果、过期 RUNNING 任务、MANUAL 状态、迟到返还和最老积压。历史成功退款通过只读 `trade_historical_refund_reconciliation` 视图供运营与渠道核实，不能仅依据本地 SUCCESS 自动修账。

旧 Trade 将 PAYMENT_AFTER_CLOSE 的 business_no 理解为订单号，新版本理解为支付单号；旧 AI 不识别候选激活状态。迁移后不能直接回退到旧二进制。需要修复后向前发布；知识文档版本回退使用新版本的管理员激活接口。

## RabbitMQ 隔离与重放

新版本不修改既有业务队列的声明参数，保留已入队消息。监听器有限重试后使用 mandatory 和 publisher confirm 发布至持久 DLQ，确认失败则保留原消息重试。部署时可执行 `scripts/rabbit-recovery-policies.py --container <broker>`，安装仅匹配三条关键队列的死信策略。

SYSTEM_ADMIN 可通过 Trade/Inventory 的 dead-letters 管理接口重放白名单事件类型；每批最多 100 条。事件 ID 保持不变，确认原队列收到后才确认 DLQ 消息。非法事件留在 DLQ，需修复内容后处理。重放审计记录用户、队列、数量和请求 ID；消费者数据库去重仍是最终幂等保障。

Prometheus 恢复告警已添加规则用例。缺少 Broker/数据库观测时指标返回 NaN，不伪装成积压为零。

## Agent 和知识库

调用者必须为同一意图重试保留 requestId。未传键的旧接口会生成新键，不提供跨请求的重试保证。已准备的失败 Turn 使用原键恢复，或显式放弃后发起新意图。取消保留已提交业务步骤，不能撤销已经完成的加购。

加购事务只保存步骤标记和数量写入，远程商品/库存读取位于事务外。完整响应生成失败时，已提交标记阻止再次加购，重试继续生成并持久化响应。并发返回使用同一份已保存结果。

知识候选全部分块完成索引后才激活，失败期间旧 ACTIVE 版本继续使用。显式下架由 SQL 立即拦截。删除整个 ES 索引可自动重建；外部重建同名空索引时使用管理员 rebuild 接口。

真实 embedding 服务需独立部署。Ollama 示例为 `EMBEDDING_URL=http://<host>:11434/v1`、`EMBEDDING_MODEL=bge-m3:latest`、`KNOWLEDGE_REQUIRE_SEMANTIC_GATE=true`。HF 兼容服务须使用其实际模型名称。0.62 是当前 BGE-M3 DEV 校准值，更换模型或语料后需重新校准；TEST 无答案误召回 33.3% 尚未达到暂定 20% 门槛。未配置 embedding 的 BM25 兼容模式也未通过该质量门槛。

`LLM_HEADER_TIMEOUT_MS`、`LLM_FIRST_BYTE_TIMEOUT_MS`、`LLM_BODY_IDLE_TIMEOUT_MS`、`LLM_TOTAL_TIMEOUT_MS` 与 `LLM_MAX_OUTPUT_TOKENS` 均传入 Compose 的 AI 服务。日志与 Trace 不保存 Prompt 或用户 JWT。
