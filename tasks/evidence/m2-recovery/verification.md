# M1/M2 实际服务故障验收

2026-11-14，本机以 Java 25（release 21）运行七个真实 Boot 应用：Trade、Inventory、Marketing、Member、Settlement、Aftersale、Product。使用独立 MySQL 8.4、RabbitMQ 4.0 容器与随机端口，支付为模拟通道。执行 `scripts/service-recovery-check.py`，退出 0，结果见 service-recovery.json。

- 支付成功后终止 Trade：停止时订单 PAID、资产任务 PENDING、优惠券和积分仍 FROZEN。重启后库存 DEDUCTED、券/积分 USED、履约单恰好一条，重复回调不重复扣库存。
- 终止 Settlement 后由真实 Aftersale 工作器申请退款：Trade 保持 PROCESSING，恢复 Settlement 后 Trade SUCCESS、Aftersale COMPLETED，结算退款账本恰好一条。
- 暂停 RabbitMQ 后营销提交秒杀：事务保留未发布 Outbox，恢复 Broker 后才 PUBLISHED，Trade 持久进度一条，营销秒杀终态 CREATED。

同轮完整后端验证包含关闭先于冻结、失去租约后的旧工作器、取消时预占尚未完成、历史迟到支付迁移与逐笔返还、死信确认失败保留原消息、持久队列兼容和幂等重放。精确总数见 ../m5-quality/backend-tests.json。

Prometheus 五类恢复告警规则通过 promtool 用例。实际服务/容器均由脚本 finally 清理；临时日志保留在 D:/Temp/geekmall/five-batch-service-recovery-final，Jar 副本在证据保存后清理。未操作用户业务数据库。
