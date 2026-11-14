# 检索评测 v2

固定语料为 `R__phase6_ai_seed.sql` 中的 6 个分块，来源 ID 为 `SPU-1001`、`AFTERSALE`、`PAYMENT`。62 条中文人工编写标注按问题固定划分为 DEV 23 条、TEST 39 条。每条包含可读问题、范围、类别、相关性等级、版本要求和标注理由。该集合覆盖种子知识的验收，不代表独立文档上的泛化表现。

`SUPPORTED` 和 `VERSION` 纳入 Recall@K、MRR、nDCG 的平均分；`UNSUPPORTED` 和 `CROSS_SCOPE` 单独报告候选误召回率，不用零分拉低相关问题的分母。无答案候选误召回不等于模型回答已经编造事实。`VERSION` 检查固定语料文档版本 1；真实候选失败、批量构建、切换、回退和下架由恢复回归单独验证。

在线和评测均调用同一召回、SQL 范围/状态/版本复核、来源去重路径。评测仅将后端故障从在线的降级行为改为失败。默认评测 TEST；实验先观察 DEV，再报告 TEST，不能用 TEST 调整阈值。报告记录 RRF、候选数、相似度、索引代次和 P95（单次串行运行 nearest-rank，包含 SQL 复核及实际模型调用；非并发压力测试）。

实验前暂定门槛：Recall ≥ 0.80、MRR ≥ 0.70、nDCG ≥ 0.75、P95 ≤ 2000ms、无答案候选误召回率 ≤ 0.20、范围/版本违规为 0。这些是**尚未由历史基线校准的临时验收门槛**，报告保留失败结果。未配置真实 embedding endpoint 时为 BM25 兼容模式，其质量门槛未通过。模型实验是 BM25 与真实 embedding 的 RRF 混合检索，不是纯向量检索。

2026-11-14 的基线对比中，BM25、Qwen3 Q8_0、BGE-M3 F16 都达到源级相关问题 Recall/MRR/nDCG=1，但无答案误召回均为 10/12，不能宣称质量通过。随后仅使用 23 条 DEV 问题的实际余弦相似度选择 BGE-M3 与 0.62 证据准入阈值（DEV Recall=14/15，无答案误召回=0/8）。冻结阈值后的 TEST Recall/MRR/nDCG=0.8889、P95=61.1ms，无答案误召回=4/12，仍未达到 0.20 的暂定门槛。保留全部原始比较和开发集校准依据，不继续根据 TEST 调参。

配置 `EMBEDDING_URL` 后默认应用 0.62 准入；BM25 候选也必须通过语义准入，embedding 故障返回空证据，不回退绕过。模型名称默认 `BAAI/bge-m3`，Ollama 部署需显式设为 `bge-m3:latest`。`KNOWLEDGE_MINIMUM_SIMILARITY` 可配置，但更换模型或语料后必须重新使用 DEV 校准。设置 `KNOWLEDGE_REQUIRE_SEMANTIC_GATE=true` 时，缺少 embedding 配置也返回空证据；评测遇到缺失或故障后端直接失败。生产部署真实 embedding 服务仍是运维前置条件，临时实验服务不作为永久部署。

从 `backend` 运行（PowerShell 的 `-D` 参数须整体加引号）：

```powershell
& D:/DevTools/maven/bin/mvn.cmd -pl geek-ai -am -Pintegration `
  '-Dtest=KnowledgeIndexRecoveryTest,HybridRagServiceTest,RetrievalEvaluationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dit.test=KnowledgeMigrationsIT,KnowledgeRetrievalIT,KnowledgeParsingIT' `
  '-Dfailsafe.failIfNoSpecifiedTests=false' `
  '-Drag.reportDir=D:/Temp/maven/shoping-rag-batch' verify
```

上述测试实际运行 MySQL 8.4、Elasticsearch 8.17.3、MinIO 和 Tika 4.2.0。容器自动销毁。PDF 在内存中构建并上传 MinIO，通过真实 Tika 检测 MIME 与提取正文，同时校验伪装 PDF 和跨桶引用拒绝。

要对比 [Qwen3-Embedding-0.6B](https://ollama.com/library/qwen3-embedding:0.6b) 与 [BGE-M3](https://ollama.com/library/bge-m3)，先通过现有模型工具准备真实的 1024 维模型，然后额外传入：

```powershell
'-Drag.embedding.url=http://127.0.0.1:11435/v1' `
'-Drag.embedding.models=qwen3-embedding:0.6b,bge-m3:latest'
```

冻结的 BGE-M3 准入实验额外传入 `-Drag.embedding.models=bge-m3:latest`、`-Drag.minimum.similarity=0.62` 和独立报告目录。该实验目前显示 `passesProvisionalThresholds=false`；Java 测试通过只代表执行正确、范围/版本保护与重建正确。

本地服务使用 [Ollama 的 OpenAI 兼容 embeddings 接口](https://docs.ollama.com/api/openai-compatibility)。不配置模型时只执行 BM25。配置模型但无法加载、索引或查询时测试失败，不能使用假向量或静默降级作为模型对比。实验应保留模型文件摘要、实际量化版本、运行时版本与机器配置；Ollama 量化模型的结论不能直接等同于 Hugging Face 全精度模型。

生成的逐条 DEV/TEST JSON 与比较摘要位于指定报告目录。报告包含问题、标注理由、实际来源/版本、三个检索指标及延迟。质量门槛失败会显示 `passesProvisionalThresholds=false`；测试只断言执行正确及范围/版本安全，不把一个困难的候选误召回问题隐藏为测试成功。

管理员接口（SYSTEM_ADMIN 或 OPS）：

- `GET /api/v1/admin/ai/knowledge/index/status`：当前代次、模型、待构建分块、状态、最近错误类型。
- `POST /api/v1/admin/ai/knowledge/index/rebuild`：持久化推进代次并重置任务；旧生效版本保留，后台可重试构建。
- `POST /api/v1/admin/ai/knowledge/documents/{documentId}/activate`：READY 历史版本进入候选，重建完成才切换，可用于回退；已显式下架文档不能由此恢复。

索引完全删除会在下一次后台轮询自动检测并重建；外部自行重建同名但空的索引时需调用管理员重建入口。候选构建失败不会使旧 ACTIVE 文档失效。显式下架同步修改 SQL 状态，立即拦截已在 ES 中的旧命中；异步清理以分块 ID 和文档 ID 删除退休版本，并持久化重试。
