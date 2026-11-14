# M3 Agent 与 M5 流式故障验收

日期：2026-11-14。工作分支 codex/trade-consistency；以下为本次实际执行结果，最终全量 clean verify 由主代理归档。

## 交付

- `ai_turn` 按用户、会话、requestId 保存摘要、状态、准备上下文、计划和最终结果；不同内容复用键拒绝，运行中重发返回 RUNNING/turnId，完成后重放结果。
- 会话采用 MySQL 会话锁串行执行；不同意图遇忙拒绝。连接池预留业务查询连接，检查所有权后执行步骤和保存检查点。准备过的失败 Turn 需要原键重试或显式 DELETE 放弃，避免旧上下文覆盖新意图。
- 稳定用户/助手消息 ID、步骤 ID、已解析参数与完整结果支持恢复。审批请求重复返回同一 token。草稿号按步骤固定。
- 加购使用用户范围的 Idempotency-Key，业务事务同时增加数量并保存结果；下游提交后响应丢失时，同键 POST 重放事务结果。
- 取消持久化 CANCELLED 及步骤摘要，已完成结果不能被取消改写；取消中的 HTTP 返回 CANCEL_REQUESTED。取消不阻塞下一条意图。
- 上游 HTTP 分别配置响应头、首字节、正文空闲和总体超时；正文关闭可解除阻塞读取。输出预算统一 max_tokens，默认 2048，范围 64–8192。
- 前端仅 message.complete 标为完成。提前 EOF/断网显示未完成，重试保留 requestId；失败请求提供放弃入口；取消终态与正常完成区分。
- 指标覆盖阶段直方图、排队、首段、拒绝、取消生效耗时及限定阶段标签的超时。

## 已执行

| 检查 | 结果 |
| --- | --- |
| AgentExecutionIT，真实 MySQL 8.4 + HTTP | 最终整合 11/11 通过；含原有回归、稳定键重放/冲突、跨用户隔离、会话所有权/取消、队列饱和、持久步骤与审批恢复 |
| CartIdempotencyIT，真实 MySQL 8.4 + 脚本 HTTP | 最终整合 1/1 通过；提交后断开响应、并发重发、服务对象替换、参数冲突、不同用户同键；远程读取断言位于事务外，提交后响应生成失败也可恢复且不重复加购 |
| ModelGatewayTest + ModelBodyDeadlineTest | 3/3 通过；真实 HTTP 首字节阻塞、正文空闲、持续心跳触发总体预算 |
| AiShoppingView.test.ts | 2/2 通过；EOF 部分输出、稳定键重试、显式完成 |
| mall-web vue-tsc | 通过 |
| 真实 Chrome，隔离 synthetic SSE fixture，实际 AiShoppingView | EOF 显示未完成；点击重试获得完整回答；两次 requestId 相同；完整后未完成标记消失；零 console warn/error |

原始执行记录（临时，待主代理归档/清理）：`D:/Temp/maven/shoping-agent-batch/verify-focused.log`（Cart1通过、Agent首次9中1仅数字类型断言失败）、`verify-focused2.log`（Agent9/9及模型3通过）、`frontend-final.log`、`typecheck.log`、`browser-verification.json`、`browser-complete.txt`。

## 最终整合

2026-11-14 最终完整 reactor `-Pintegration verify` 通过：91 Surefire + 46 Failsafe，零失败/错误/跳过，包括最后新增的两项 Agent IT、超时分类和取消终态修正。加购改为远程准备、事务内标记与写入、事务外生成持久响应；补测响应生成服务故障后重试仍只增加一次。第一次 clean 后整合发现旧审批单测仍期待重复 request 报错，按新契约改为相同 token 重放后重新执行全量通过。精确统计见相邻 m5-quality/backend-tests.json。

## 边界

- 旧调用者不传 requestId 时自动分配键，保留原接口；跨请求重试保证需要调用者提供稳定键。旧加购不传 Idempotency-Key 保留原行为。
- MySQL 锁连接失效后的远程调用检查间隙不等价于跨实例全局 fencing；全局 ID 与跨实例取消仍属计划中的多实例扩展。
- 本次测试恢复使用真实 SQL 检查点和新 TurnStore/CartService 对象，没有模拟整个 JVM/主机崩溃；实际业务写入与丢失响应已经分别验证。
- Chrome fixture、临时脚本、浏览器页签及 Vite 辅助进程已清理；原始诊断/验收日志保留待统一归档，不曾写入额外备份。

Modern Web Guidance：已检查 cancel streaming fetch / abort fetch request；搜索结果无适用指南，未套用无关实现。
